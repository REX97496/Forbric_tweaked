package net.forbric.kernel.runtime;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

/**
 * Presents a plain registry as a {@code DefaultedRegistry} when a mod asks for it at that type.
 *
 * <p>The merged base widens {@code BuiltInRegistries}' defaulted slots to {@code Registry}, because one family's
 * patched sources declare them that way, and then stores whatever {@code registerDefaulted} returned. On this base
 * that value is Forge's {@code NamespacedWrapper}: it holds {@code get}, {@code getKey(T)} and {@code byId(int)}, all
 * inherited from {@code MappedRegistry}, but not {@code getDefaultKey()} — that method only exists on its subclass
 * {@code NamespacedDefaultedWrapper}. Making the parent implement the interface instead is not an option: Forge's
 * {@code GameData.getWrapper} decides "was defaulted when not expected" by testing the wrapper against that interface,
 * so every plain registry would start throwing {@code IllegalStateException} during Bootstrap.
 *
 * <p>So the adapter is built here, per value, and only for the slots that need it. Everything is reflective on
 * purpose: this source compiles on every supported line, including the ones where the type is spelled {@code Identifier}
 * rather than {@code ResourceLocation}, and none of those spellings appear in the bytecode of this class.
 *
 * <p>{@code getDefaultKey()} answers the key of entry zero, which is where a defaulted registry keeps its default —
 * the same rule vanilla's own defaulted registry follows.
 */
public final class KernelRegistryViews {

	private KernelRegistryViews() {
	}

	/**
	 * Returns {@code registry} unchanged when it already is a {@code DefaultedRegistry}, otherwise a view over it.
	 * Returns {@code null} for {@code null}, so a mirrored field can never turn a missing slot into a live one.
	 */
	public static Object asDefaulted(Object registry) {
		if (registry == null) return null;
		Class<?> type = defaultedType(registry.getClass().getClassLoader());
		if (type == null) return registry;
		if (type.isInstance(registry)) return registry;
		return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, new View(registry, type));
	}

	private static Class<?> defaultedType(ClassLoader loader) {
		ClassLoader effective = loader != null ? loader : KernelRegistryViews.class.getClassLoader();
		try {
			return Class.forName("net.minecraft.core.DefaultedRegistry", false, effective);
		} catch (ClassNotFoundException | LinkageError noDefaultedType) {
			return null;
		}
	}

	private static final class View implements InvocationHandler {

		private final Object registry;
		private final Class<?> type;

		View(Object registry, Class<?> type) {
			this.registry = registry;
			this.type = type;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
			String name = method.getName();
			int arity = arguments == null ? 0 : arguments.length;
			if (arity == 0 && name.equals("getDefaultKey")) return defaultKey();
			if (arity == 0 && name.equals("hashCode")) return System.identityHashCode(proxy);
			if (arity == 0 && name.equals("toString")) return "forbric defaulted view of " + registry;
			if (arity == 1 && name.equals("equals")) return proxy == arguments[0];
			Method target = methodOn(registry.getClass(), name, method.getParameterTypes());
			if (target == null) {
				target = methodOn(type, name, method.getParameterTypes());
				if (target == null) throw new NoSuchMethodException(name);
				// On the proxy, not on this handler: the method is a default of the interface the proxy implements,
				// so the proxy is the receiver it was written for.
				return target.invoke(proxy, arguments);
			}
			return invokeOn(target, registry, arguments);
		}

		private Object defaultKey() {
			Method byId = methodOn(registry.getClass(), "byId", new Class<?>[] {int.class});
			if (byId == null) return null;
			Object defaultEntry;
			try {
				defaultEntry = invokeOn(byId, registry, new Object[] {0});
			} catch (InvocationTargetException thrown) {
				return null;
			}
			if (defaultEntry == null) return null;
			Method getKey = methodOn(registry.getClass(), "getKey", new Class<?>[] {Object.class});
			if (getKey == null) return null;
			try {
				return invokeOn(getKey, registry, new Object[] {defaultEntry});
			} catch (InvocationTargetException thrown) {
				return null;
			}
		}

		private static Object invokeOn(Method method, Object target, Object[] arguments)
				throws InvocationTargetException {
			try {
				method.setAccessible(true);
				return method.invoke(target, arguments);
			} catch (IllegalAccessException unreadable) {
				return null;
			}
		}

		private static Method methodOn(Class<?> owner, String name, Class<?>[] parameters) {
			for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
				for (Method candidate : current.getDeclaredMethods()) {
					if (!candidate.getName().equals(name)) continue;
					if (!Arrays.equals(candidate.getParameterTypes(), parameters)) continue;
					return candidate;
				}
				// Interfaces are searched per class in the chain, not once at the end: holderByNameCodec is a default
				// on Registry, which a wrapper reaches through its superclass, and looking only at the subclass's own
				// interfaces missed it — the call then fell through to the proxy and died with "object is not an
				// instance of declaring class".
				for (Class<?> implemented : current.getInterfaces()) {
					Method found = methodOn(implemented, name, parameters);
					if (found != null) return found;
				}
			}
			return null;
		}
	}
}
