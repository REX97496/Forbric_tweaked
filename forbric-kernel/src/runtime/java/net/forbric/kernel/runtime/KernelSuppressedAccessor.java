/*
 * Forbric - a Minecraft mod loader that runs Fabric and NeoForge mods on one merged base.
 * Copyright (c) Forbric contributors. All rights reserved.
 */

package net.forbric.kernel.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Implements the {@code @Accessor} / {@code @Invoker} methods of a guest mixin this kernel had to suppress.
 *
 * <p>Mixin normally rewrites those bodies: {@code @Accessor("MAX_PAYLOAD_SIZE")} on a {@code static int getMaxPayloadSize()}
 * becomes a field read, and until Mixin runs the method body is a stub that throws
 * {@code UnsupportedOperationException: Implemented via mixin}. On the merged base some accessor mixins cannot bind at all
 * (measured: {@code fabric-data-attachment-api-v1}'s {@code CustomPayloadS2CPacketAccessor}, whose {@code @Mixin} target
 * resolves but whose {@code @Accessor} field the merge moved), so this kernel removes them from the config. The class is
 * then still referenced by the mod's own code ({@code AttachmentChange.<clinit>} calls {@code getMaxPayloadSize}), and a
 * class defined unchanged throws from that stub and takes the whole entrypoint with it.</p>
 *
 * <p>So the loader rewrites those stubs into calls to this class, which reads or writes the field through reflection.
 * The accessor keeps its meaning, the mod's initialiser runs, and a field that genuinely is not in this base fails with a
 * message that names the field and every target that was tried.</p>
 */
public final class KernelSuppressedAccessor {

	private static final ConcurrentMap<String, Field> FIELDS = new ConcurrentHashMap<>();
	private static final ConcurrentMap<String, Method> METHODS = new ConcurrentHashMap<>();

	private KernelSuppressedAccessor() {
	}

	/** Reads {@code field} from the first of {@code targets} that declares it, on {@code receiver} (null for static). */
	public static Object get(Class<?> caller, String[] targets, String field, Object receiver) {
		Field resolved = fieldOf(caller, targets, field);
		try {
			return resolved.get(receiver);
		} catch (ReflectiveOperationException | RuntimeException e) {
			throw new IllegalStateException("[Forbric/Accessor] " + caller.getName() + " cannot read " + field + " on "
					+ resolved.getDeclaringClass().getName(), e);
		}
	}

	/** Writes {@code field} on {@code receiver} (null for static). */
	public static void put(Class<?> caller, String[] targets, String field, Object receiver, Object value) {
		Field resolved = fieldOf(caller, targets, field);
		try {
			resolved.set(receiver, value);
		} catch (ReflectiveOperationException | RuntimeException e) {
			throw new IllegalStateException("[Forbric/Accessor] " + caller.getName() + " cannot write " + field + " on "
					+ resolved.getDeclaringClass().getName(), e);
		}
	}

	/**
	 * Calls {@code method} on the first of {@code targets} that declares it — what {@code @Invoker} would have wired.
	 * Wraps a checked exception thrown by the target back into an unchecked one; the stub it replaces could not have
	 * declared it either.
	 */
	public static Object call(Class<?> caller, String[] targets, String method, Object receiver, Object[] args) {
		Class<?> owner = ownerOf(caller, targets, method, args);
		Method found = methodOf(owner, method, args);
		try {
			return found.invoke(receiver, args);
		} catch (java.lang.reflect.InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException runtime) throw runtime;
			if (cause instanceof Error error) throw error;
			throw new IllegalStateException("[Forbric/Accessor] " + caller.getName() + " called " + method
					+ ", which threw a checked exception the stub could not have declared", cause);
		} catch (ReflectiveOperationException | RuntimeException e) {
			throw new IllegalStateException("[Forbric/Accessor] " + caller.getName() + " cannot call " + method
					+ " on " + owner.getName(), e);
		}
	}

	private static Field fieldOf(Class<?> caller, String[] targets, String field) {
		String key = caller.getClassLoader().getName() + '#' + String.join(",", targets) + '#' + field;
		Field cached = FIELDS.get(key);
		if (cached != null) return cached;
		List<String> tried = new ArrayList<>();
		for (String target : targets) {
			Class<?> owner = load(caller, target);
			if (owner == null) {
				tried.add(target + " (not loadable)");
				continue;
			}
			Field found = declaredField(owner, field);
			if (found != null) {
				FIELDS.put(key, found);
				return found;
			}
			tried.add(owner.getName());
		}
		throw new IllegalStateException("[Forbric/Accessor] " + caller.getName() + " declares an @Accessor for field '"
				+ field + "', and no target declares it on this base; tried " + tried);
	}

	private static Field declaredField(Class<?> owner, String field) {
		for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
			try {
				Field found = c.getDeclaredField(field);
				found.setAccessible(true);
				return found;
			} catch (NoSuchFieldException ignored) {
				// keep walking the hierarchy
			} catch (RuntimeException inaccessible) {
				return null;
			}
		}
		for (Class<?> itf : interfaces(owner)) {
			try {
				Field found = itf.getDeclaredField(field);
				found.setAccessible(true);
				return found;
			} catch (NoSuchFieldException | RuntimeException ignored) {
				// not on this interface
			}
		}
		return null;
	}

	private static List<Class<?>> interfaces(Class<?> owner) {
		List<Class<?>> all = new ArrayList<>();
		collectLater(owner, all, new java.util.HashSet<>());
		return all;
	}

	private static void collectLater(Class<?> c, List<Class<?>> into, java.util.Set<Class<?>> seen) {
		if (c == null || !seen.add(c)) return;
		for (Class<?> itf : c.getInterfaces()) {
			into.add(itf);
			collectLater(itf, into, seen);
		}
		collectLater(c.getSuperclass(), into, seen);
	}

	private static Class<?> ownerOf(Class<?> caller, String[] targets, String method, Object[] args) {
		List<String> tried = new ArrayList<>();
		for (String target : targets) {
			Class<?> owner = load(caller, target);
			if (owner == null) {
				tried.add(target + " (not loadable)");
				continue;
			}
			if (methodOf(owner, method, args) != null) return owner;
			tried.add(owner.getName());
		}
		throw new IllegalStateException("[Forbric/Accessor] " + caller.getName() + " declares an @Invoker for '"
				+ method + "' with " + args.length + " argument(s), and no target declares it on this base; tried "
				+ tried);
	}

	private static Method methodOf(Class<?> owner, String method, Object[] args) {
		String key = owner.getName() + '#' + method + '/' + args.length;
		Method cached = METHODS.get(key);
		if (cached != null) return cached;
		for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
			for (Method candidate : c.getDeclaredMethods()) {
				if (!candidate.getName().equals(method) || candidate.getParameterCount() != args.length) continue;
				if (!fits(candidate.getParameterTypes(), args)) continue;
				try {
					candidate.setAccessible(true);
				} catch (RuntimeException inaccessible) {
					continue;
				}
				METHODS.put(key, candidate);
				return candidate;
			}
		}
		return null;
	}

	private static boolean fits(Class<?>[] parameters, Object[] args) {
		for (int i = 0; i < parameters.length; i++) {
			if (args[i] == null) {
				if (parameters[i].isPrimitive()) return false;
			} else if (!boxed(parameters[i]).isInstance(args[i])) {
				return false;
			}
		}
		return true;
	}

	private static Class<?> boxed(Class<?> type) {
		if (!type.isPrimitive()) return type;
		if (type == int.class) return Integer.class;
		if (type == long.class) return Long.class;
		if (type == double.class) return Double.class;
		if (type == float.class) return Float.class;
		if (type == boolean.class) return Boolean.class;
		if (type == byte.class) return Byte.class;
		if (type == short.class) return Short.class;
		if (type == char.class) return Character.class;
		return type;
	}

	private static Class<?> load(Class<?> caller, String target) {
		for (ClassLoader loader : loaders(caller)) {
			try {
				return Class.forName(target, false, loader);
			} catch (ClassNotFoundException | LinkageError ignored) {
				// try the next loader
			}
		}
		return null;
	}

	private static List<ClassLoader> loaders(Class<?> caller) {
		List<ClassLoader> out = new ArrayList<>(3);
		ClassLoader own = caller.getClassLoader();
		if (own != null) out.add(own);
		ClassLoader context = Thread.currentThread().getContextClassLoader();
		if (context != null && context != own) out.add(context);
		return out;
	}
}
