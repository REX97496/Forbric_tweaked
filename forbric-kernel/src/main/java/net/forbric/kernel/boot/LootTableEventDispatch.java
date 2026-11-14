/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.boot;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import net.forbric.kernel.transform.LootTableEventBridgeInjector;
import net.forbric.kernel.util.ForbricLog;

/** Runs a proved original closed loot callback group through the native typed loot seam.
 * The generated helper preserves mutable Event invokers, callbacks, exceptions and holder iteration. Older or
 * unknown source groups use the public API fallback but remain reported as unproved; that fallback alone is
 * not evidence of equivalence. Native cancellation is decided before either Fabric dispatch path. */
public final class LootTableEventDispatch {
	static final String EVENTS = "net.fabricmc.fabric.api.loot.v3.LootTableEvents";
	static final String EVENT = "net.fabricmc.fabric.api.event.Event";
	static final String REPLACE_IFACE = EVENTS + "$Replace";
	static final String MODIFY_IFACE = EVENTS + "$Modify";
	static final String LOADED_IFACE = EVENTS + "$Loaded";
	static final String BUILDER_API = "net.fabricmc.fabric.api.loot.v3.FabricLootTableBuilder";
	static final String SOURCE = "net.fabricmc.fabric.api.loot.v3.LootTableSource";
	static final String LOOT_UTIL = "net.fabricmc.fabric.impl.loot.LootUtil";
	static final String LOOT_TABLE = "net.minecraft.world.level.storage.loot.LootTable";
	static final String LOOT_TABLE_BUILDER = LOOT_TABLE + "$Builder";
	static final String RESOURCE_KEY = "net.minecraft.resources.ResourceKey";
	static final String HOLDER_LOOKUP_PROVIDER = "net.minecraft.core.HolderLookup$Provider";
	static final String RESOURCE_MANAGER = "net.minecraft.server.packs.resources.ResourceManager";
	static final String REGISTRY = "net.minecraft.core.Registry";

	/**
	 * Everything the dispatch calls, {@code Object}-typed so a test can substitute it.
	 *
	 * @param replace  {@code (key, table, source, provider) -> LootTable or null}, on the CURRENT invoker
	 * @param modify   {@code (key, builder, source, provider) -> void}, on the CURRENT invoker
	 * @param loaded   {@code (resourceManager, registry) -> void}, on the CURRENT invoker
	 * @param copyOf   {@code (table) -> builder}
	 * @param build    {@code (builder) -> table}
	 * @param dataPack {@code LootTableSource.DATA_PACK}
	 * @param replaced {@code LootTableSource.REPLACED}
	 * @param sources  {@code LootUtil.SOURCES}, a thread-local {@code Map<Identifier, LootTableSource>}
	 */
	record Handles(MethodHandle replace, MethodHandle modify, MethodHandle loaded, MethodHandle copyOf,
			MethodHandle build, Object dataPack, Object replaced, ThreadLocal<?> sources, MethodHandle bindHolders) {
		Handles(MethodHandle replace,MethodHandle modify,MethodHandle loaded,MethodHandle copyOf,MethodHandle build,Object dataPack,Object replaced,ThreadLocal<?> sources){
			this(replace,modify,loaded,copyOf,build,dataPack,replaced,sources,MethodHandles.dropArguments(MethodHandles.empty(MethodType.methodType(void.class)),0,Object.class));
		}
	}

	private enum State { UNRESOLVED, PRESENT, ABSENT }

	private static volatile ClassLoader guestLoader;
	private static volatile State state = State.UNRESOLVED;
	private static volatile Handles handles;
	private static volatile LootSourceCallbacks.Callbacks sourceCallbacks;
	/**
	 * Whether {@code LootTable} actually carries fabric's accessor methods.
	 *
	 * <p>Measured on the merged base: {@code @Accessor field LootTable.randomSequenceId} cannot bind, so the mixin is
	 * suppressed and {@code FabricLootTableBuilder.copyOf(table)} — which casts to {@code LootTableAccessor} to read
	 * the pools — throws {@code IllegalClassLoadError} instead of returning a builder. Firing REPLACE/MODIFY through
	 * that path took the whole world load down ("Failed to load level data or datapacks"), so the dispatch asks the
	 * class itself whether the accessor bound before it uses it.
	 */
	private static volatile boolean accessorsBound;
	private static final java.util.concurrent.atomic.AtomicBoolean ACCESSOR_SAID = new java.util.concurrent.atomic.AtomicBoolean();


	private static final AtomicInteger OFFERED = new AtomicInteger();
	private static final AtomicInteger REPLACE_TOOK = new AtomicInteger();
	private static final AtomicInteger MODIFY_FIRED = new AtomicInteger();

	private LootTableEventDispatch() {
	}

	/** One switch for the injector, the runtime shim and this dispatch. */
	public static boolean enabled() {
		return LootTableEventBridgeInjector.enabled();
	}

	public static void bind(ClassLoader loader) {
		guestLoader = loader;
		LootSourceCallbacks.bind(loader);
		synchronized (LootTableEventDispatch.class) {
			state = State.UNRESOLVED;
			handles = null;
			sourceCallbacks=null;
			OFFERED.set(0);
			REPLACE_TOOK.set(0);
			MODIFY_FIRED.set(0);
		}
	}

	/** Test seam: fabric-api is on no test classpath, so the handles have to be substitutable. */
	static void bindForTest(Handles substitute) {
		synchronized (LootTableEventDispatch.class) {
			guestLoader=null;
			handles = substitute;
			sourceCallbacks=null;
			// The substitute handles are what the tests measure; no LootTable class is loaded to ask about accessors.
			accessorsBound = substitute != null;
			state = substitute == null ? State.ABSENT : State.PRESENT;
			OFFERED.set(0);
			REPLACE_TOOK.set(0);
			MODIFY_FIRED.set(0);
		}
	}

	/**
	 * The original callback, for one table the native hooks have let through.
	 *
	 * @param provider the {@code HolderLookup.Provider} of the reload
	 * @param key      {@code ResourceKey<LootTable>} of {@code id}
	 * @param id       the table's {@code Identifier}
	 * @param table    the table as NeoForge returned it (never null here)
	 * @return the table after Fabric's events, or {@code table} itself when there is nothing to ask
	 */
	public static Object afterLoad(Object provider, Object key, Object id, Object table) {
		if (!enabled() || table == null || !resolve()) return table;
		if (!accessorsBound) {
			if (ACCESSOR_SAID.compareAndSet(false, true)) {
				ForbricLog.warn("[Forbric/LootBridge] this base cannot run fabric-loot-api-v3's REPLACE/MODIFY: its "
						+ "accessor and builder mixins are suppressed on the merged base (@Accessor LootTable."
						+ "randomSequenceId cannot bind), so FabricLootTableBuilder.copyOf() has no pools to read and "
						+ "calling it threw IllegalClassLoadError for LootTableAccessor and killed the world load. "
						+ "A mod's REPLACE/MODIFY listener is registered and stays uncalled; ALL_LOADED still fires");
			}
			return table;
		}
		LootSourceCallbacks.Callbacks original=sourceCallbacks;
		if(original!=null){try{Object result=original.afterLoad().invoke(provider,id,table);OFFERED.incrementAndGet();return result;}catch(Throwable failure){throw propagate(failure);}}
		Handles h = handles;
		try {
			Object source = h.dataPack();
			Object map = h.sources().get();
			if (map instanceof Map<?, ?> m) {
				Object recorded = m.get(id);
				if (recorded != null) source = recorded;
			}
			Object current = table;
			Object swapped = h.replace().invoke(key, current, source, provider);
			if (swapped != null) {
				current = swapped;
				source = h.replaced();
				REPLACE_TOOK.incrementAndGet();
			}
			Object builder = h.copyOf().invoke(current);
			h.modify().invoke(key, builder, source, provider);
			MODIFY_FIRED.incrementAndGet();
			OFFERED.incrementAndGet();
			return h.build().invoke(builder);
		} catch (Throwable t) {
			throw propagate(t);
		}
	}

	/** The original completion callback: ALL_LOADED, source cleanup, then holder binding. */
	public static void allLoaded(Object resourceManager, Object registry) {
		if (!enabled() || !resolve()) return;
		LootSourceCallbacks.Callbacks original=sourceCallbacks;
		if(original!=null){try{original.allLoaded().invoke(resourceManager,registry);OFFERED.set(0);return;}catch(Throwable failure){throw propagate(failure);}}
		Handles h = handles;
		try {
			h.loaded().invoke(resourceManager, registry);
			h.sources().remove();
			h.bindHolders().invoke(registry);
		} catch (Throwable t) {
			throw propagate(t);
		}
		ForbricLog.info("[Forbric/LootBridge] offered %d loot table(s) to fabric-loot-api-v3: REPLACE took %d, MODIFY "
				+ "fired %d, ALL_LOADED fired with %d entries — ReloadableServerRegistriesMixin's modifyLootTable cannot "
				+ "bind on the merged base (NeoForge swapped the lambda's parameters and split its one map into two), so "
				+ "the kernel fires the events from NeoForge's own LootTableLoadEvent seam", OFFERED.getAndSet(0),
				REPLACE_TOOK.getAndSet(0), MODIFY_FIRED.getAndSet(0), sizeOf(registry));
	}
	/**
	 * 1.21.1's completion seam: {@code ReloadableServerRegistries.scheduleElementParse} returns the future that fills
	 * one {@code LootDataType}'s registry, and that method is the whole of the reload's per-registry work. Wrapping
	 * the future for {@code LootDataType.TABLE} makes {@link #allLoaded} run once the loot-table registry is
	 * populated, which is where fabric-loot-api-v3's own {@code ReloadableRegistriesMixin} fires it — a mixin this
	 * base cannot take (it misses one anchor and is suppressed), so without this wrapper no mod's
	 * {@code ALL_LOADED} listener is ever called.
	 *
	 * <p>Only the table registry: the same method parses predicates and item modifiers, and firing ALL_LOADED with
	 * one of those would hand listeners a registry they never asked about.
	 *
	 * @param future         the {@code CompletableFuture<WritableRegistry<?>>} the method is about to return
	 * @param dataType       the {@code LootDataType} it was parsing
	 * @param resourceManager the reload's {@code ResourceManager}, which listeners receive
	 * @return {@code future} itself when this is not the loot-table registry, otherwise a derived future that fires
	 *         the event and yields the same registry
	 */
	public static Object afterParse(Object future, Object dataType, Object resourceManager) {
		if (!enabled() || !(future instanceof CompletableFuture<?> pending) || !isLootTables(dataType)) return future;
		return pending.thenApply(registry -> {
			try {
				boolean canFire = enabled() && resolve();
				allLoaded(resourceManager, registry);
				ForbricLog.info("[Forbric/LootBridge] the loot-table registry finished loading (%d entries) — "
						+ "LootTableEvents.ALL_LOADED %s", sizeOf(registry), canFire
								? "was fired at this point, which is where fabric-loot-api-v3's own mixin fires it"
								: "was NOT fired: fabric-loot-api-v3 is not on this loader, so no mod asked for it");
			} catch (Throwable failure) {
				ForbricLog.warn("[Forbric/LootBridge] a mod's ALL_LOADED listener threw while the loot table registry "
						+ "completed — the reload continues with the tables as they are", failure);
			}
			return registry;
		});
	}

	/** Whether {@code dataType} is {@code LootDataType.TABLE}, by identity against the loaded class's own constant. */
	private static boolean isLootTables(Object dataType) {
		if (dataType == null) return false;
		try {
			Class<?> type = Class.forName("net.minecraft.world.level.storage.loot.LootDataType", false,
					dataType.getClass().getClassLoader());
			return type.getField("TABLE").get(null) == dataType;
		} catch (Throwable absent) {
			return false;
		}
	}

	@SuppressWarnings("unchecked") private static <T extends Throwable> RuntimeException propagate(Throwable failure)throws T{throw (T)failure;}
	private static void bindHolders(MethodHandle elements,MethodHandle value,MethodHandle set,Object registry)throws Throwable{
		java.util.stream.Stream<?> stream=(java.util.stream.Stream<?>)elements.invoke(registry);
		stream.forEach(holder->{try{Object table=value.invoke(holder);set.invoke(table,holder);}catch(Throwable failure){throw propagate(failure);}});
	}

	private static int sizeOf(Object registry) {
		try {
			return (Integer) registry.getClass().getMethod("size").invoke(registry);
		} catch (Throwable absent) {
			return -1;
		}
	}


	/**
	 * Whether fabric-loot-api-v3's accessor actually bound to {@code LootTable} here.
	 *
	 * <p>Asked of the class, not of the mixin config: {@code fabric_getPools} is the accessor's own method, so its
	 * presence is the same fact {@code FabricLootTableBuilder.copyOf} depends on. Never load the accessor interface
	 * to check — on this base that load is exactly what throws {@code IllegalClassLoadError}.
	 */
	private static boolean hasAccessor(Class<?> lootTable) {
		try {
			lootTable.getMethod("fabric_getPools");
			return true;
		} catch (NoSuchMethodException | LinkageError absent) {
			return false;
		}
	}

	private static synchronized boolean resolve() {
		if (sourceCallbacks != null) return true;
		ClassLoader loader = guestLoader;
		if (loader == null) {
			if(state==State.PRESENT)return true; // explicitly bound test handles need no guest class loader
			state = State.ABSENT;
			return false;
		}
		LootSourceCallbacks.Callbacks migrated=LootSourceCallbacks.callbacks(loader);
		if(migrated!=null){sourceCallbacks=migrated;state=State.PRESENT;
			try{accessorsBound=hasAccessor(Class.forName(LOOT_TABLE,false,loader));}catch(Throwable absent){accessorsBound=false;}
			return true;}
		if(state==State.PRESENT)return true;
		if(state==State.ABSENT)return false;
		try {
			// Initialise LootTableEvents: its three Event fields are what everything below is bound to.
			Class<?> events = Class.forName(EVENTS, true, loader);
			Class<?> event = Class.forName(EVENT, false, loader);
			Class<?> replaceIface = Class.forName(REPLACE_IFACE, false, loader);
			Class<?> modifyIface = Class.forName(MODIFY_IFACE, false, loader);
			Class<?> loadedIface = Class.forName(LOADED_IFACE, false, loader);
			Class<?> builderApi = Class.forName(BUILDER_API, false, loader);
			Class<?> source = Class.forName(SOURCE, false, loader);
			Class<?> lootUtil = Class.forName(LOOT_UTIL, true, loader);
			Class<?> lootTable = Class.forName(LOOT_TABLE, false, loader);
			Class<?> builder = Class.forName(LOOT_TABLE_BUILDER, false, loader);
			Class<?> resourceKey = Class.forName(RESOURCE_KEY, false, loader);
			Class<?> provider = Class.forName(HOLDER_LOOKUP_PROVIDER, false, loader);
			Class<?> resourceManager = Class.forName(RESOURCE_MANAGER, false, loader);
			Class<?> registry = Class.forName(REGISTRY, false, loader);
			Class<?> holder = Class.forName("net.minecraft.core.Holder",false,loader);
			Class<?> reference = Class.forName("net.minecraft.core.Holder$Reference",false,loader);

			MethodHandles.Lookup lookup = MethodHandles.publicLookup();
			MethodHandle invoker = lookup.findVirtual(event, "invoker", MethodType.methodType(Object.class));

			MethodHandle replace = lookup.findVirtual(replaceIface, "replaceLootTable",
					MethodType.methodType(lootTable, resourceKey, lootTable, source, provider));
			MethodHandle modify = lookup.findVirtual(modifyIface, "modifyLootTable",
					MethodType.methodType(void.class, resourceKey, builder, source, provider));
			MethodHandle loaded = lookup.findVirtual(loadedIface, "onLootTablesLoaded",
					MethodType.methodType(void.class, resourceManager, registry));
			MethodHandle copyOf = lookup.findStatic(builderApi, "copyOf", MethodType.methodType(builder, lootTable));
			MethodHandle build = lookup.findVirtual(builder, "build", MethodType.methodType(lootTable));
			// fabric-loot-api-v3 no longer ships FabricLootTable: the interface and the LootTable mixin that
			// implemented it are gone, so nothing rebinds a loaded table's holder any more. Optional on purpose —
			// requiring it made every REPLACE, MODIFY and ALL_LOADED listener silent on a loader that has the events.
			MethodHandle binding = MethodHandles.dropArguments(MethodHandles.empty(MethodType.methodType(void.class)), 0, Object.class);
			try {
				Class<?> fabricTable = Class.forName("net.fabricmc.fabric.impl.loot.FabricLootTable",false,loader);
				MethodHandle elements=lookup.findVirtual(registry,"listElements",MethodType.methodType(java.util.stream.Stream.class));
				MethodHandle value=lookup.findVirtual(reference,"value",MethodType.methodType(Object.class));
				MethodHandle set=lookup.findVirtual(fabricTable,"fabric$setHolder",MethodType.methodType(void.class,holder));
				binding=MethodHandles.insertArguments(MethodHandles.lookup().findStatic(LootTableEventDispatch.class,"bindHolders",MethodType.methodType(void.class,MethodHandle.class,MethodHandle.class,MethodHandle.class,Object.class)),0,elements,value,set);
			} catch (ClassNotFoundException | NoSuchMethodException goneUpstream) {
				ForbricLog.info("[Forbric/LootBridge] this fabric-loot-api-v3 has no FabricLootTable (the interface was "
						+ "removed upstream), so a loaded table keeps the holder the registry already gave it, and "
						+ "REPLACE/MODIFY/ALL_LOADED fire without that extra rebinding step");
			}

			Handles resolved = new Handles(
					onCurrentInvoker(replace, invoker, events.getField("REPLACE").get(null), 4, Object.class),
					onCurrentInvoker(modify, invoker, events.getField("MODIFY").get(null), 4, void.class),
					onCurrentInvoker(loaded, invoker, events.getField("ALL_LOADED").get(null), 2, void.class),
					copyOf.asType(MethodType.genericMethodType(1)),
					build.asType(MethodType.genericMethodType(1)),
					source.getField("DATA_PACK").get(null),
					source.getField("REPLACED").get(null),
					(ThreadLocal<?>) lootUtil.getField("SOURCES").get(null),binding);
			handles = resolved;
			accessorsBound = hasAccessor(lootTable);
			state = State.PRESENT;
			return true;
		} catch (Throwable absent) {
			// A normal instance without fabric-loot-api-v3 — not a defect, so debug rather than warn.
			ForbricLog.debug("[Forbric/LootBridge] fabric-loot-api-v3 not present (%s) — loot tables load exactly as "
					+ "NeoForge returns them", String.valueOf(absent));
			state = State.ABSENT;
			return false;
		}
	}

	/**
	 * {@code (args...) -> method(event.invoker(), args...)}: the invoker is looked up on EVERY call, because
	 * fabric's {@code Event} rebuilds it whenever a listener registers.
	 */
	private static MethodHandle onCurrentInvoker(MethodHandle method, MethodHandle invoker, Object event, int arity,
			Class<?> returns) {
		MethodHandle current = invoker.bindTo(event);    // () -> Object
		Class<?>[] params = new Class<?>[arity];
		java.util.Arrays.fill(params, Object.class);
		MethodHandle generic = method.asType(MethodType.methodType(returns, Object.class).appendParameterTypes(params));
		return MethodHandles.collectArguments(generic, 0, current);
	}
}
