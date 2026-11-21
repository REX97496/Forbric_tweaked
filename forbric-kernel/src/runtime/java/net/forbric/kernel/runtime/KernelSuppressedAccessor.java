/*
 * Forbric - a Minecraft mod loader that runs Fabric and NeoForge mods on one merged base.
 * Copyright (c) Forbric contributors. All rights reserved.
 */

package net.forbric.kernel.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.Type;

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
	public static Object get(Class<?> caller, String[] targets, String field, String desc, Object receiver) {
		Field resolved = fieldOf(caller, targets, field, desc, receiver);
		try {
			return resolved.get(receiver);
		} catch (ReflectiveOperationException | RuntimeException e) {
			throw new IllegalStateException("[Forbric/Accessor] " + caller.getName() + " cannot read " + field + " on "
					+ resolved.getDeclaringClass().getName(), e);
		}
	}

	/** Writes {@code field} on {@code receiver} (null for static). */
	public static void put(Class<?> caller, String[] targets, String field, String desc, Object receiver, Object value) {
		Field resolved = fieldOf(caller, targets, field, desc, receiver);
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
	public static Object call(Class<?> caller, String[] targets, String method, String desc, Object receiver,
			Object[] args) {
		Method found = methodOf(caller, targets, method, desc, args);
		Class<?> owner = found.getDeclaringClass();
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

	/**
	 * Resolves the field an accessor names, by name first and then — when this base spells the member differently —
	 * by the type the accessor itself declares.
	 *
	 * <p>Why the second pass. An accessor mixin's {@code @Accessor("STRIPPED_BLOCK_SET")} carries the name from the line
	 * the mod was written against. On the merged base the same field is {@code AxeItem.STRIPPABLES}; Mixin cannot bind
	 * the accessor, the kernel suppresses that mixin, and this class stands in for it — but the author's spelling still
	 * names nothing. Measured on the 1.21.1 instance: 8 accessor mixins were reported as {@code cannot bind} for exactly
	 * this reason (axe/hoe/shovel/farmer/villager content registries, command argument types, block entity types, the
	 * loot table's random sequence). The accessor's own descriptor says what type it needs, and {@code AxeItem} declares
	 * exactly one {@code Map} field, so the answer is unambiguous. Only a UNIQUE candidate is accepted: two fields of the
	 * same type would be a guess, and the failure message lists them so the next round can decide with evidence.
	 */
	private static Field fieldOf(Class<?> caller, String[] targets, String field, String desc, Object receiver) {
		String key = caller.getClassLoader().getName() + '#' + String.join(",", targets) + '#' + field + '/' + desc;
		Field cached = FIELDS.get(key);
		if (cached != null) return cached;
		List<String> tried = new ArrayList<>();
		List<Class<?>> owners = new ArrayList<>();
		for (String target : targets) {
			Class<?> owner = load(caller, target);
			if (owner == null) {
				tried.add(target + " (not loadable)");
				continue;
			}
			owners.add(owner);
			Field found = declaredField(owner, field);
			if (found != null) {
				FIELDS.put(key, found);
				return found;
			}
			tried.add(owner.getName());
		}
		Field rebound = byType(owners, fieldTypeOf(desc), receiver == null, field, tried);
		if (rebound != null) {
			FIELDS.put(key, rebound);
			return rebound;
		}
		throw new IllegalStateException("[Forbric/Accessor] " + caller.getName() + " declares an @Accessor for field '"
				+ field + "', and no target declares it on this base; tried " + tried);
	}

	/** The type an accessor reads or writes, read out of the descriptor the loader passed along with it. */
	private static Type fieldTypeOf(String desc) {
		try {
			Type[] args = Type.getArgumentTypes(desc);
			return args.length == 0 ? Type.getReturnType(desc) : args[args.length - 1];
		} catch (RuntimeException unreadable) {
			return Type.VOID_TYPE;
		}
	}

	/** The one field of this type the targets declare, or null when there is none or more than one. */
	private static Field byType(List<Class<?>> owners, Type expected, boolean wantStatic, String field,
			List<String> tried) {
		if (expected == null || expected.getSort() == Type.VOID) return null;
		List<Field> candidates = new ArrayList<>();
		for (Class<?> owner : owners) {
			for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
				collectFields(c, expected, wantStatic, candidates);
			}
			for (Class<?> itf : interfaces(owner)) collectFields(itf, expected, wantStatic, candidates);
		}
		if (candidates.size() == 1) {
			Field only = candidates.get(0);
			try {
				only.setAccessible(true);
			} catch (RuntimeException inaccessible) {
				return null;
			}
			ForbricLog.info("[Forbric/Accessor] '%s' is spelled '%s' on this base; the accessor's own"
					+ " descriptor asks for a %s, and %s declares exactly one", field, only.getName(),
					expected.getClassName(), only.getDeclaringClass().getName());
			return only;
		}
		if (candidates.size() > 1) {
			List<String> named = new ArrayList<>();
			for (Field candidate : candidates) named.add(candidate.getDeclaringClass().getName() + '.' + candidate.getName());
			tried.add(candidates.size() + " fields of type " + expected.getClassName() + " " + named
					+ " (a guess was not taken)");
		}
		return null;
	}

	private static void collectFields(Class<?> owner, Type expected, boolean wantStatic, List<Field> into) {
		Field[] declared;
		try {
			declared = owner.getDeclaredFields();
		} catch (RuntimeException | LinkageError unreadable) {
			return;
		}
		for (Field candidate : declared) {
			if (Modifier.isStatic(candidate.getModifiers()) != wantStatic) continue;
			if (Type.getType(candidate.getType()).equals(expected)) into.add(candidate);
		}
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

	/**
	 * The method an invoker names, by name across the targets first, then — when this base spells it differently — by
	 * the descriptor the loader passed along, accepting only an unambiguous answer.
	 */
	private static Method methodOf(Class<?> caller, String[] targets, String method, String desc, Object[] args) {
		List<String> tried = new ArrayList<>();
		List<Class<?>> owners = new ArrayList<>();
		for (String target : targets) {
			Class<?> owner = load(caller, target);
			if (owner == null) {
				tried.add(target + " (not loadable)");
				continue;
			}
			owners.add(owner);
			Method found = declaredMethod(owner, method, args);
			if (found != null) return found;
			tried.add(owner.getName());
		}
		Method rebound = byDescriptor(owners, desc, args, method, tried);
		if (rebound != null) return rebound;
		throw new IllegalStateException("[Forbric/Accessor] " + caller.getName() + " declares an @Invoker for '"
				+ method + "' with " + args.length + " argument(s), and no target declares it on this base; tried "
				+ tried);
	}

	/** One target's answer for a named method, cached. */
	private static Method declaredMethod(Class<?> owner, String method, Object[] args) {
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

	/** The one method the targets declare with this exact parameter and return types, or null. */
	private static Method byDescriptor(List<Class<?>> owners, String desc, Object[] args, String method,
			List<String> tried) {
		Type[] wanted;
		Type returns;
		try {
			wanted = Type.getArgumentTypes(desc);
			returns = Type.getReturnType(desc);
		} catch (RuntimeException unreadable) {
			return null;
		}
		List<Method> candidates = new ArrayList<>();
		for (Class<?> owner : owners) {
			for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
				collectMethods(c, wanted, returns, args, candidates);
			}
			for (Class<?> itf : interfaces(owner)) collectMethods(itf, wanted, returns, args, candidates);
		}
		if (candidates.size() == 1) {
			Method only = candidates.get(0);
			try {
				only.setAccessible(true);
			} catch (RuntimeException inaccessible) {
				return null;
			}
			ForbricLog.info("[Forbric/Accessor] '%s' is spelled '%s' on this base; the invoker's own descriptor asks"
					+ " for a method with the same parameters and return type, and %s declares exactly one",
					method, only.getName(), only.getDeclaringClass().getName());
			return only;
		}
		if (candidates.size() > 1) {
			List<String> named = new ArrayList<>();
			for (Method candidate : candidates) named.add(candidate.getDeclaringClass().getName() + '.' + candidate.getName());
			tried.add(candidates.size() + " methods with that descriptor " + named + " (a guess was not taken)");
		}
		return null;
	}

	private static void collectMethods(Class<?> owner, Type[] wanted, Type returns, Object[] args, List<Method> into) {
		Method[] declared;
		try {
			declared = owner.getDeclaredMethods();
		} catch (RuntimeException | LinkageError unreadable) {
			return;
		}
		for (Method candidate : declared) {
			if (candidate.getParameterCount() != wanted.length) continue;
			if (!Type.getReturnType(candidate).equals(returns)) continue;
			Type[] actual = Type.getArgumentTypes(candidate);
			boolean same = true;
			for (int i = 0; i < wanted.length; i++) {
				if (!actual[i].equals(wanted[i])) {
					same = false;
					break;
				}
			}
			if (!same || !fits(candidate.getParameterTypes(), args)) continue;
			into.add(candidate);
		}
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
