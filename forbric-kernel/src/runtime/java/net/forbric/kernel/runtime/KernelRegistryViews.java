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
	 * The value behind one of this class's proxies, or the input unchanged. A mirrored field and its original are
	 * the same registry, so anything that sweeps registries by identity (the {@code RegisterEvent} sweep) has to
	 * see one object, not two: the duplicate made a mod's {@code DeferredRegister} run twice, and the second pass
	 * died on "Adding duplicate key".
	 */
	public static Object unwrap(Object value) {
		Object current = value;
		while (current != null && java.lang.reflect.Proxy.isProxyClass(current.getClass())) {
			InvocationHandler handler = java.lang.reflect.Proxy.getInvocationHandler(current);
			if (handler instanceof View) {
				current = ((View) handler).target();
			} else if (handler instanceof ListenableView) {
				current = ((ListenableView) handler).registry();
			} else {
				return current;
			}
		}
		return current;
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
		// The mirrored slot is read by code that then treats the value as the registry it knows (measured:
		// RegisterEvent casts it to WritableRegistry at Registry.register). A proxy that only declares
		// DefaultedRegistry fails that cast, so it declares every interface the wrapped registry implements.
		return Proxy.newProxyInstance(type.getClassLoader(), answerAs(registry.getClass(), type), new View(registry, type));
	}

	private static final String LISTENABLE = "net.fabricmc.fabric.impl.registry.sync.ListenableRegistry";
	private static final String EVENT = "net.fabricmc.fabric.api.event.Event";
	private static final String EVENT_FACTORY = "net.fabricmc.fabric.api.event.EventFactory";

	/**
	 * The two Fabric events a registry answers for, per registry, so a listener registered through one view is seen by
	 * the next lookup. Weak keys: a registry lives as long as the game does, but nothing here should outlive one.
	 */
	private static final java.util.Map<Object, Object> ADDED = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
	private static final java.util.Map<Object, Object> REMAPPED = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

	/**
	 * Returns {@code registry} when it already implements fabric-registry-sync's {@code ListenableRegistry}, otherwise a
	 * view of it. Called from the guest's own {@code ListenableRegistry.get}, whose remaining body is a throw:
	 * fabric-registry-sync supplies that interface with a mixin applied to {@code MappedRegistry}, and on a merged base
	 * that mixin can fail on a single {@code @Shadow} (measured: {@code lambda$freeze$5()V}, a lambda the two patched
	 * families number differently). A Forge registry wrapper then is a {@code MappedRegistry} without the interface, and
	 * every {@code RegistryEntryAddedCallback.event(registry)} — the first one is {@code ItemModelShaper}'s — dies with
	 * "Unsupported registry: minecraft:item".
	 *
	 * <p>Without fabric-api the type is absent and the input is returned, so this code is inert on a Forge-only line.
	 */
	public static Object listenable(Object registry) {
		if (registry == null) return null;
		ClassLoader loader = registry.getClass().getClassLoader();
		Class<?> type = type(LISTENABLE, loader);
		if (type == null) return registry;
		if (type.isInstance(registry)) return registry;
		// A mirrored defaulted slot hands this class its own view; wrapping the view would implement only the
		// interfaces the view declares (measured: `$Proxy34 cannot be cast to WritableRegistry` once that view
		// reached a registry callback), so the proxy always wraps the registry behind the view.
		if (java.lang.reflect.Proxy.isProxyClass(registry.getClass())) {
			java.lang.reflect.InvocationHandler handler = java.lang.reflect.Proxy.getInvocationHandler(registry);
			if (handler instanceof View) registry = ((View) handler).target();
		}
		// The value this proxy stands in for is handed back to fabric code that immediately casts it to the
		// registry interface it knows (measured: `$Proxy34 cannot be cast to WritableRegistry` out of the entry
		// callback). Implementing only ListenableRegistry is not enough: the proxy must answer as every interface
		// the wrapped registry already implements, and every such method is delegated to it below.
		return Proxy.newProxyInstance(type.getClassLoader(), answerAs(registry.getClass(), type), new ListenableView(registry));
	}

	/**
	 * The registry's own add-object event, created with Fabric's own {@code EventFactory} so a listener registered
	 * through it is invoked by {@link #fire(Object, Object)} and by whoever else asks for the same registry.
	 */
	public static Object addObjectEvent(Object registry) {
		return eventFor(registry, ADDED, "net.fabricmc.fabric.api.event.registry.RegistryEntryAddedCallback");
	}

	/** The registry's remap event. Nothing on this kernel fires it yet; it exists so listeners can register. */
	public static Object remapEvent(Object registry) {
		return eventFor(registry, REMAPPED, "net.fabricmc.fabric.api.event.registry.RegistryIdRemapCallback");
	}

	private static Object eventFor(Object registry, java.util.Map<Object, Object> events, String callbackName) {
		synchronized (events) {
			Object existing = events.get(registry);
			if (existing != null) return existing;
			Object created = createEvent(registry.getClass().getClassLoader(), callbackName);
			if (created != null) events.put(registry, created);
			return created;
		}
	}

	private static Object createEvent(ClassLoader loader, String callbackName) {
		try {
			Class<?> factory = type(EVENT_FACTORY, loader);
			Class<?> callback = type(callbackName, loader);
			if (factory == null || callback == null) return null;
			Method create = null;
			for (Method candidate : factory.getMethods()) {
				if (!candidate.getName().equals("createArrayBacked")) continue;
				if (candidate.getParameterCount() != 2) continue;
				if (candidate.getParameterTypes()[0] != Class.class) continue;
				create = candidate;
				break;
			}
			if (create == null) return null;
			java.util.function.Function<Object[], Object> invoker = listeners -> dispatch(callback, listeners);
			return create.invoke(null, callback, invoker);
		} catch (ReflectiveOperationException | LinkageError unreadable) {
			return null;
		}
	}

	/**
	 * The single callback Fabric's event hands a caller: it invokes every registered listener in order. Built as a
	 * proxy because the callback's signature belongs to the guest's version of fabric-api, not to this source.
	 */
	private static Object dispatch(Class<?> callback, Object[] listeners) {
		return Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[] {callback}, (proxy, method, arguments) -> {
			if (method.getDeclaringClass() == Object.class) {
				return switch (method.getName()) {
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == (arguments == null ? null : arguments[0]);
					default -> "forbric callback dispatch";
				};
			}
			Object answer = null;
			for (Object listener : listeners) {
				if (listener == null) continue;
				answer = method.invoke(listener, arguments);
			}
			return answer;
		});
	}

	private static Class<?> type(String name, ClassLoader loader) {
		ClassLoader effective = loader != null ? loader : KernelRegistryViews.class.getClassLoader();
		try {
			return Class.forName(name, false, effective);
		} catch (ClassNotFoundException | LinkageError absent) {
			return null;
		}
	}

	private static final class ListenableView implements InvocationHandler {

		private final Object registry;

		ListenableView(Object registry) {
			this.registry = registry;
		}

		/** The registry behind this view; {@link #unwrap(Object)} returns it to a sweep. */
		Object registry() {
			return registry;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
			String name = method.getName();
			int arity = arguments == null ? 0 : arguments.length;
			if (arity == 0 && name.equals("fabric_getAddObjectEvent")) return addObjectEvent(registry);
			if (arity == 0 && name.equals("fabric_getRemapEvent")) return remapEvent(registry);
			if (arity == 0 && name.equals("hashCode")) return System.identityHashCode(proxy);
			if (arity == 0 && name.equals("toString")) return "forbric listenable view of " + registry;
			if (arity == 1 && name.equals("equals")) return proxy == arguments[0];
			// Everything else is the wrapped registry's own answer. Without this branch the proxy could only be
			// used where fabric happens to call the two event getters; handing it back to code that registers an
			// entry (or checks an interface) threw NoSuchMethodException on the first ordinary call.
			Method target = methodOn(registry.getClass(), name, method.getParameterTypes());
			if (target == null) throw new NoSuchMethodException(name);
			return invokeOn(target, registry, arguments);
		}
	}

	/**
	 * Every interface the wrapped value answers to, plus the one this proxy exists to add. Interfaces of the
	 * superclass chain are included because a Forge wrapper implements {@code MappedRegistry} while its base holds
	 * the interfaces a caller may cast to.
	 */
	private static Class<?>[] answerAs(Class<?> actual, Class<?> extra) {
		java.util.LinkedHashSet<Class<?>> all = new java.util.LinkedHashSet<>();
		all.add(extra);
		for (Class<?> type = actual; type != null && type != Object.class; type = type.getSuperclass()) {
			for (Class<?> implemented : type.getInterfaces()) collectInterfaces(implemented, all);
		}
		all.removeIf(type -> type == null || !type.isInterface());
		return all.toArray(new Class<?>[0]);
	}

	private static void collectInterfaces(Class<?> type, java.util.Set<Class<?>> into) {
		if (!into.add(type)) return;
		for (Class<?> parent : type.getInterfaces()) collectInterfaces(parent, into);
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

		/** The value this view stands in for; {@link #listenable(Object)} wraps that, never the view. */
		Object target() {
			return registry;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
			String name = method.getName();
			int arity = arguments == null ? 0 : arguments.length;
			if (arity == 0 && name.equals("getDefaultKey")) return defaultKey();
			if (arity == 0 && name.equals("hashCode")) return System.identityHashCode(proxy);
			if (arity == 0 && name.equals("toString")) return "forbric defaulted view of " + registry;
			if (arity == 1 && name.equals("equals")) return proxy == arguments[0];
			// Only the class chain first. A default method declared on an interface re-dispatches through the
			// virtual call on its receiver: invoking it with the proxy as receiver re-enters this handler and
			// recurses until the stack dies (measured: $Proxy35.register twice, "Exception caught during firing
			// event: null" out of RegisterEvent.register). The real registry is the receiver a default was
			// written for, so it is used for that case below.
			Method target = methodOnClass(registry.getClass(), name, method.getParameterTypes());
			if (target == null) {
				target = methodOn(type, name, method.getParameterTypes());
				if (target == null) throw new NoSuchMethodException(name);
				return target.invoke(registry, arguments);
			}
			return invokeOn(target, registry, arguments);
		}

		private Object defaultKey() {
			Method byId = methodOn(registry.getClass(), "byId", new Class<?>[] {int.class});
			if (byId == null) return null;
			Object defaultEntry;
			try {
				defaultEntry = invokeOn(byId, registry, new Object[] {0});
			} catch (Throwable thrown) {
				return null;
			}
			if (defaultEntry == null) return null;
			Method getKey = methodOn(registry.getClass(), "getKey", new Class<?>[] {Object.class});
			if (getKey == null) return null;
			try {
				return invokeOn(getKey, registry, new Object[] {defaultEntry});
			} catch (Throwable thrown) {
				return null;
			}
		}

	}

		/**
		 * Delegates and rethrows the target's own throwable. Letting InvocationTargetException escape would make
		 * Proxy wrap it in an UndeclaredThrowableException, and the caller would lose the registry's message
		 * (measured: `Exception caught during firing event: null`).
		 */
		private static Object invokeOn(Method method, Object target, Object[] arguments) throws Throwable {
			try {
				method.setAccessible(true);
				return method.invoke(target, arguments);
			} catch (IllegalAccessException unreadable) {
				return null;
			} catch (InvocationTargetException thrown) {
				throw thrown.getCause() != null ? thrown.getCause() : thrown;
			}
		}

		/** Declared methods only, no interfaces: a default there re-dispatches through the receiver. */
		private static Method methodOnClass(Class<?> owner, String name, Class<?>[] parameters) {
			for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
				for (Method candidate : current.getDeclaredMethods()) {
					if (!candidate.getName().equals(name)) continue;
					if (!Arrays.equals(candidate.getParameterTypes(), parameters)) continue;
					return candidate;
				}
			}
			return null;
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
