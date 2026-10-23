/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.function.Supplier;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FlowerPotBlock;

/**
 * Which full pot a plant makes, for every family's way of declaring one (FlowerPotRepairInjector).
 *
 * <p>The merged {@code FlowerPotBlock.useItemOn} is MinecraftForge's body: it looks the plant up in the empty pot's
 * {@code fullPots} map, which only MinecraftForge's constructor and {@code addPlant} ever filled, and the merge kept
 * NeoForge's bodies of both. NeoForge declares a pot by its constructor (empty pot + plant) and, on 26.2, keeps them
 * in {@code GameData}'s pot table, which its registry bake callback fills — and that callback never runs on the merged
 * base, where the block registry is MinecraftForge's wrapper. So every lookup answered air.
 *
 * <p>The lookup now asks, in order: the empty pot's explicit {@code addPlant} entries (MinecraftForge's API), then
 * the fallback table, which {@link #rebuildTable} fills from every registered pot the way NeoForge's bake does. A
 * vanilla or Fabric pot is in that table too: NeoForge's (Block, Properties) constructor names the vanilla empty pot.
 *
 * <p><b>1.21.1 line: the table is the kernel's own.</b> 26.2 reads and fills NeoForge's
 * {@code net.neoforged.neoforge.registries.GameData.getFlowerPotBlockTable()}, a {@code Table<Block, Block, Block>}
 * keyed (empty pot, plant) -&gt; full pot, filled by NeoForge's own block-registry bake callback. Neither that member
 * nor the tables it returns exist on 1.21.1, and the measurement is narrower than "the class is gone" — the CALLBACK
 * class is on both lines and only its fields moved (javap, both carriers):
 *
 * <pre>
 *   26.2    NeoForgeRegistryCallbacks$BlockCallbacks
 *             INSTANCE, BLOCKSTATE_TO_ID_MAP,
 *             EMPTY_POT_AND_FLOWER_TO_FULL_POT_TABLE : Table&lt;Block, Block, Block&gt;
 *             LEGACY_EMPTY_POT_AND_FLOWER_TO_FULL_POT_TABLE : Lazy&lt;Table&lt;Block, Identifier, Supplier&lt;Block&gt;&gt;&gt;
 *   1.21.1  NeoForgeRegistryCallbacks$BlockCallbacks
 *             INSTANCE, BLOCKSTATE_TO_ID_MAP                                    (neither pot table)
 * </pre>
 *
 * <p>So on this line the same table is built and owned here, with the same contents and the same
 * {@link #rebuildTable} filler. {@link #invalidateLegacyView} keeps its reflection: it finds the class on 1.21.1 and
 * then fails on the missing field, which its existing {@code catch (Throwable)} already treats as "nothing to
 * invalidate". What changes is who else reads the table: on 26.2 NeoForge's own code does; on 1.21.1 nobody else
 * does, because there the pot mapping lives per instance in {@code FlowerPotBlock.fullPots} (javap: 1.21.1 has
 * {@code getFullPotsView()} and no {@code getFullPot(Block)}; 26.2 has both), which is the MinecraftForge side the
 * merge kept. Nothing else in this tree consults this table.
 */
public final class KernelFlowerPots {
	/** 1.21.1's stand-in for NeoForge's pot table: same shape, same contents, owned here. */
	private static final Table<Block, Block, Block> FULL_POTS = HashBasedTable.create();

	private static Table<Block, Block, Block> potTable() {
		return FULL_POTS;
	}

	private KernelFlowerPots() {
	}

	/** The full pot {@code content} makes in {@code self}'s empty pot, or air. Supplier failures remain visible. */
	public static Block fullPotFor(Map<?, ?> explicit, FlowerPotBlock self, Block content) {
			if (explicit != null && !explicit.isEmpty()) {
				ResourceLocation key = BuiltInRegistries.BLOCK.getKey(content);
				// An unregistered block answers the default key (air); that is not a request for the air slot.
				boolean aliased = content != Blocks.AIR && key.equals(BuiltInRegistries.BLOCK.getDefaultKey());
				if (!aliased && explicit.containsKey(key)) {
					return (Block)((Supplier<?>)explicit.get(key)).get();
				}
			}
			Block full = potTable().get(self.getEmptyPot(), content);
			return full != null ? full : Blocks.AIR;
	}
	/** Decorates the SDK's already computed answer; native guards and table reads have already happened once. */
	public static Block fullPotOrNative(Block nativeAnswer,Map<?,?> explicit,FlowerPotBlock empty,Block content){
		if(explicit!=null&&!explicit.isEmpty()){ResourceLocation key=BuiltInRegistries.BLOCK.getKey(content);boolean aliased=content!=Blocks.AIR&&key.equals(BuiltInRegistries.BLOCK.getDefaultKey());
			if(!aliased&&explicit.containsKey(key))return (Block)((Supplier<?>)explicit.get(key)).get();}
		return nativeAnswer;
	}
	/** A legacy query has already evaluated its explicit supplier. Only its absent-entry AIR answer needs the shared table. */
	public static Block legacyPotOrNative(Block nativeAnswer,FlowerPotBlock empty,Block content){
		if(nativeAnswer!=Blocks.AIR)return nativeAnswer;Block full=potTable().get(empty,content);return full==null?nativeAnswer:full;
	}

	/**
	 * Fills the fallback pot table from every registered pot, as NeoForge's bake callback would: each pot that is not
	 * its own empty pot is the full pot of (its empty pot, its plant). Returns how many pots were entered, or -1 on
	 * failure.
	 */
	public static int rebuildTable() {
		// The same switches as the rest of the flower pot repair (NativeCoremodParity, FlowerPotRepairInjector).
		if ("off".equalsIgnoreCase(System.getProperty("forbric.coremodParity", "on"))
				|| "off".equalsIgnoreCase(System.getProperty("forbric.flowerPotRepair", "on"))) return -1;
		try {
			Table<Block, Block, Block> table = potTable();
			table.clear();
			int entered = 0, failed = 0;
			for (Block block : BuiltInRegistries.BLOCK) {
				if (!(block instanceof FlowerPotBlock pot)) continue;
				try {
					FlowerPotBlock empty = pot.getEmptyPot();
					if (empty == pot) continue;
					table.put(empty, pot.getPotted(), pot);
					entered++;
				} catch (Throwable unresolved) {
					failed++;   // one mod's unbound supplier must not cost every other pot its entry
				}
			}
			invalidateLegacyView();
			ForbricLog.info("[Forbric/FlowerPot] filled the flower pot table with %d pot(s)%s — NeoForge's bake callback "
					+ "never runs on the merged block registry, so every plant looked up air", entered,
					failed == 0 ? "" : " (" + failed + " pot(s) could not name their plant yet)");
			return entered;
		} catch (Throwable failure) {
			ForbricLog.warn("[Forbric/FlowerPot] could not fill the flower pot table", Reflect.unwrap(failure));
			return -1;
		}
	}

	/** The (empty pot, id) → supplier view NeoForge derives lazily from the table; drop it so it re-derives. */
	private static void invalidateLegacyView() {
		try {
			Class<?> callbacks = Class.forName("net.neoforged.neoforge.registries.NeoForgeRegistryCallbacks$BlockCallbacks");
			Field lazy = callbacks.getDeclaredField("LEGACY_EMPTY_POT_AND_FLOWER_TO_FULL_POT_TABLE");
			lazy.setAccessible(true);
			Object value = lazy.get(null);
			value.getClass().getMethod("invalidate").invoke(value);
		} catch (Throwable absent) {
			ForbricLog.debug("[Forbric/FlowerPot] NeoForge's legacy pot view could not be invalidated: %s", absent);
		}
	}
}
