/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.lang.reflect.*;
import java.util.*;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;

/** Replays NewRegistryEvent only to listeners added by client-window Forge constructors. */
final class LateForgeRegistryDeclarations {
	private LateForgeRegistryDeclarations() { }
	/**
	 * The listeners already on the global {@code NewRegistryEvent} bus, or {@code null} when this line has no such
	 * bus to read.
	 *
	 * <p>{@code null} is a real answer, not a failure to hide: 1.21.1's {@code NewRegistryEvent} declares no
	 * {@code BUS} field, and its EventBus 6 listeners live on each mod's own {@code IEventBus} — there is exactly
	 * one bus tree to walk on 26.2 and none here. Returning an empty list would be worse than {@code null}: the
	 * caller diffs before/after, so an empty "before" would classify EVERY existing listener as newly added and
	 * replay the whole declaration stream, registering the baseline's registries a second time.
	 */
	static List<Object> snapshot(ClassLoader loader) throws ReflectiveOperationException {
		Object bus;
		try {
			bus = Class.forName("net.minecraftforge.registries.NewRegistryEvent", false, loader).getField("BUS").get(null);
		} catch (NoSuchFieldException noGlobalBus) {
			return null;
		}
		List<Object> result=new ArrayList<>();collect(bus,result,Collections.newSetFromMap(new IdentityHashMap<>()));return result;
	}
	private static void collect(Object bus,List<Object> result,Set<Object> seen)throws ReflectiveOperationException{
		if(!seen.add(bus))return;
		for(String field:List.of("backingList","monitorBackingList"))result.addAll((List<?>)bus.getClass().getMethod(field).invoke(bus));
		for(Object child:(List<?>)bus.getClass().getMethod("children").invoke(bus))collect(child,result,seen);
	}
	static List<Object> added(List<Object> before,List<Object> after){
		Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());seen.addAll(before);
		return after.stream().filter(seen::add).toList();
	}
	static void fire(ClassLoader loader,List<Object> before)throws ReflectiveOperationException{
		// Two ways this line cannot do the diff, both answered before any reflection that would throw:
		//   * `before == null` — snapshot() found no global NewRegistryEvent bus to walk (1.21.1). Diffing against
		//     an empty list would call every existing listener new and replay the whole stream.
		//   * no BusGroup — 1.21.1 runs EventBus 6, which has no BusGroup.create; the late-declaration replay is a
		//     per-event-bus construction that only EventBus 7 supports.
		// In both cases the mod's own NewRegistryEvent listener is still reachable through the bus it already sits
		// on (KernelForgeBaseline posts on each handle), so what is lost is the REPLAY for a listener added after
		// that pass — say one line, not a warning, because it is the expected shape of 1.21.1 rather than a fault.
		if(before==null){
			ForbricLog.debug("[Forbric/Forge] no global NewRegistryEvent bus on this line, so there is no listener "
					+ "snapshot to diff the late-constructing mods against; their own bus already carries their "
					+ "listener and it was posted when they were constructed");
			return;
		}
		List<Object> listeners=added(before,snapshot(loader));if(listeners.isEmpty())return;
		Class<?> eventClass=Class.forName("net.minecraftforge.registries.NewRegistryEvent",false,loader);
		Class<?> groupClass;
		try{
			groupClass=Class.forName("net.minecraftforge.eventbus.api.bus.BusGroup",false,loader);
		}catch(ClassNotFoundException eventBusSix){
			ForbricLog.debug("[Forbric/Forge] this line runs EventBus 6 (no BusGroup), so a listener added after the "
					+ "NewRegistryEvent pass cannot be replayed on a synthetic bus; it stays on the bus it was added to");
			return;
		}
		Class<?> busClass=Class.forName(ForeignType.EVENT_BUS.binary(Ecosystem.FORGE),false,loader);
		Object group=groupClass.getMethod("create",String.class).invoke(null,"forbric-late-registry-declarations");
		Object bus=busClass.getMethod("create",groupClass,Class.class).invoke(null,group,eventClass);
		try{
			Method add=busClass.getMethod("addListener",Class.forName(ForeignType.EVENT_LISTENER.binary(Ecosystem.FORGE),false,loader));
			for(Object listener:listeners)add.invoke(bus,listener);
			groupClass.getMethod("startup").invoke(group);
			Object event=eventClass.getDeclaredConstructor().newInstance();
			busClass.getMethod("post",Class.forName(ForeignType.EVENT.binary(Ecosystem.FORGE),false,loader)).invoke(bus,event);
			Method fill=eventClass.getDeclaredMethod("fill");fill.setAccessible(true);fill.invoke(event);
			ForbricLog.info("[Forbric/Forge] delivered NewRegistryEvent to %d newly added client-window listener(s) "
					+ "without replaying the baseline declarations",listeners.size());
		}finally{groupClass.getMethod("dispose").invoke(group);}
	}
}
