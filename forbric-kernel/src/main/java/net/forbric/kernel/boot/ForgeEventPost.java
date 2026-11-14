/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.util.List;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;

/**
 * Posts a MinecraftForge mod-bus event the way the line this kernel is running against posts it.
 *
 * <p>WHY THIS EXISTS: three call sites used to do {@code eventClass.getField("BUS").get(null)}, and on 1.21.1 that
 * field does not exist on either event class. Measured with javap against the forge carrier this base was built
 * from: {@code net.minecraftforge.registries.NewRegistryEvent} declares a logger, a registries list, two
 * {@code create(...)} overloads, {@code fill()}, {@code buildRegistry} and {@code toString()} — no {@code BUS};
 * {@code net.minecraftforge.registries.DataPackRegistryEvent} likewise. So every post threw
 * {@code NoSuchFieldException: BUS} before a listener ran, and everything MinecraftForge declares through those
 * events — {@code forge:fluid_type} and the other custom registries, every custom datapack registry codec —
 * silently never existed.
 *
 * <p>THE BUS ITSELF DIFFERS BY LINE, which is why this is a strategy and not a field read. On 1.21.1 Forge runs
 * EventBus 6: one {@code IEventBus} per mod, minted by {@code BusBuilder.builder().build()}, and the kernel already
 * holds each of them in {@code KernelForgeModContext.Handle.busGroup()} (the 1.21.1 edition of that class stores an
 * {@code IEventBus} there). On 26.2 the same slot holds EventBus 7's {@code BusGroup}, which cannot post at all —
 * the event has to go through {@code ModLoader.get().postEvent(event)}, which resolves a per-event bus from the
 * group. So: post on every handle that IS an {@code IEventBus}; if none was, use {@code ModLoader}; if that does
 * not exist either, fall back to an older line's per-event {@code BUS} field.
 *
 * <p>No logging here: every caller reports the consequence (which family's registries are missing), and one report
 * naming the consequence beats two naming the mechanism.
 */
final class ForgeEventPost {
	private ForgeEventPost() { }

	/**
	 * Delivers {@code event} to MinecraftForge's mod bus on {@code loader}.
	 *
	 * @param handles the kernel's per-mod bus handles; entries that are not an {@code IEventBus} on this line are
	 *                skipped rather than treated as errors
	 * @return how many handles received the event
	 * @throws ReflectiveOperationException when no posting form exists at all on this line
	 */
	static int post(ClassLoader loader, Object event, List<Object> handles) throws ReflectiveOperationException {
		Class<?> eventBus = null;
		try {
			eventBus = Class.forName(ForeignType.EVENT_BUS.binary(Ecosystem.FORGE), false, loader);
		} catch (ClassNotFoundException absent) {
			// no IEventBus on this line: nothing to post on directly
			ForbricLog.debug("[Forbric/Forge] no IEventBus on this line, so the event goes through ModLoader: "
					+ absent);
		}

		int posted = 0;
		// The bus's OWN post method, found on the bus object itself rather than through a class constant. Measured
		// the hard way: ForeignType.EVENT_BUS.binary(FORGE) is net.minecraftforge.eventbus.api.bus.EventBus, the
		// 26.2 spelling; on 1.21.1 the bus is net.minecraftforge.eventbus.api.IEventBus, so the constant threw
		// ClassNotFoundException and every handle was skipped as "not an IEventBus" — including the container bus
		// Forge actually registered its DeferredRegisters on.
		for (Object handle : handles) {
			if (handle == null) continue;
			java.lang.reflect.Method post = postOn(handle.getClass(), event);
			if (post == null) continue;
			post.invoke(handle, event);
			posted++;
		}
		if (posted > 0) return posted;

		// EventBus 7 (26.2): ModLoader resolves the per-event bus from each mod's group and delivers in mod order.
		try {
			Class<?> modLoader = Class.forName(ForeignType.FML_MOD_LOADER.binary(Ecosystem.FORGE), false, loader);
			Object instance = modLoader.getMethod("get").invoke(null);
			java.lang.reflect.Method postEvent = singleArgumentMethod(modLoader, "postEvent");
			postEvent.invoke(instance, event);
			return 1;
		} catch (ClassNotFoundException | NoSuchMethodException absent) {
			ForbricLog.debug("[Forbric/Forge] no ModLoader.postEvent on this line; falling back to the event's BUS");
		}

		Object bus = event.getClass().getField("BUS").get(null);
		Class<?> busType = eventBus != null && eventBus.isInstance(bus) ? eventBus : bus.getClass();
		singleArgumentMethod(busType, "post").invoke(bus, event);
		return 1;
	}

	/** The one-argument method called {@code name} declared by {@code owner}, whatever it names its parameter type. */
	private static java.lang.reflect.Method singleArgumentMethod(Class<?> owner, String name)
			throws NoSuchMethodException {
		for (java.lang.reflect.Method candidate : owner.getMethods()) {
			if (candidate.getName().equals(name) && candidate.getParameterCount() == 1) return candidate;
		}
		throw new NoSuchMethodException(owner.getName() + "." + name + "(?)");
	}

	/**
	 * The public {@code post(...)} method on {@code bus} that takes {@code event}, or {@code null} when the object is
	 * not a bus at all.
	 *
	 * <p>Looked up on the object's own class and interfaces instead of through a class constant, so a bus whose name
	 * differs by line (EventBus 6 {@code IEventBus} vs EventBus 7 {@code BusGroup}) still posts.
	 */
	private static java.lang.reflect.Method postOn(Class<?> bus, Object event) {
		for (java.lang.reflect.Method candidate : bus.getMethods()) {
			if (!candidate.getName().equals("post") || candidate.getParameterCount() != 1) continue;
			if (!candidate.getParameterTypes()[0].isInstance(event)) continue;
			return candidate;
		}
		for (Class<?> iface : bus.getInterfaces()) {
			java.lang.reflect.Method found = postOn(iface, event);
			if (found != null) return found;
		}
		Class<?> parent = bus.getSuperclass();
		return parent == null || parent == Object.class ? null : postOn(parent, event);
	}
}
