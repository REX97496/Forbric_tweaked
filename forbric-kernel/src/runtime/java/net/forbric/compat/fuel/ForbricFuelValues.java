package net.forbric.compat.fuel;

import java.util.Collections;
import java.util.Map;
import java.util.SequencedSet;
import java.util.LinkedHashSet;
import java.util.function.Function;

import net.minecraft.core.HolderLookup;
import net.minecraft.tags.TagKey;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.ItemLike;

/**
 * The 1.21.1 edition of vanilla 26.x's net.minecraft.world.level.block.entity.FuelValues and its Builder.
 *
 * <p>26.2's shape, measured:
 *
 * <pre>
 *   FuelValues            private final Object2IntSortedMap&lt;Item&gt; values
 *                         isFuel(ItemStack) / burnDuration(ItemStack) / fuelItems()
 *                         isFuel(ItemStack, RecipeType) / burnDuration(ItemStack, RecipeType)
 *                         static vanillaBurnTimes(Provider, FeatureFlagSet)   (and two more overloads)
 *   FuelValues$Builder    private final HolderLookup&lt;Item&gt; items
 *                         private final FeatureFlagSet enabledFeatures
 *                         private final Object2IntSortedMap&lt;Item&gt; values
 *                         add(TagKey, int) / add(ItemLike, int) / remove(TagKey) / build()
 * </pre>
 *
 * <p>On 1.21.1 there is no FuelValues at all. Fuel lives in
 * AbstractFurnaceBlockEntity: a private static volatile Map&lt;Item,Integer&gt; fuelCache filled by getFuel(),
 * queried through getFuel()/isFuel(ItemStack), and extended through FurnaceFuelBurnTimeEvent.
 *
 * <p>vanillaBurnTimes HERE IS REAL, not a placeholder: it returns 1.21.1's actual vanilla fuel table, read
 * from AbstractFurnaceBlockEntity.getFuel(). That is the same data 26.2's method produces on that line.
 *
 * <p>WHY Builder CARRIES A PUBLIC values FIELD. KernelFabricFuel reads Builder's private values map by
 * reflection for three things: to snapshot the key set before Fabric's callbacks run, to drop entries the
 * callbacks added for non-flammable wood, and -- the important one -- to know what the callbacks actually
 * changed. On 26.2 that means getDeclaredField("values") + setAccessible(true). Here the field is public, so
 * the kernel's reflective read can become direct field access, which is strictly less reflection than the
 * 26.2 path uses. The field must EXIST under that name either way: resolve() calls getDeclaredField("values")
 * before it can decide the Fabric API is absent, so a Builder without it would throw NoSuchFieldException
 * where the code expects a clean "not available" answer.
 *
 * <p>WHAT THIS DOES NOT DO. It does not make Fabric's fuel additions reach a furnace on 1.21.1. That is the
 * job of subscribing FurnaceFuelBurnTimeEvent, and it is a separate step: KernelFabricFuel's whole body is
 * reflection into Fabric API's FuelValueEvents, whose BuildCallback.build(...) signature takes a 26.x
 * FuelValues.Builder, so FuelValueEvents cannot exist on 1.21.1 at all -- Class.forName returns false there
 * and apply() returns the builder unchanged, which is the correct outcome for a module that cannot be
 * present. 1.21.1's Fabric fuel API is FuelRegistry, a different thing, and wiring it in is D.2, not this.
 */
public final class ForbricFuelValues {

	private final Map<Item, Integer> values;

	private ForbricFuelValues(Map<Item, Integer> values) {
		this.values = values;
	}

	public boolean isFuel(ItemStack stack) {
		return stack != null && !stack.isEmpty() && values.containsKey(stack.getItem());
	}

	public boolean isFuel(ItemStack stack, RecipeType<?> type) {
		return isFuel(stack);
	}

	public int burnDuration(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return 0;
		return values.getOrDefault(stack.getItem(), 0);
	}

	public int burnDuration(ItemStack stack, RecipeType<?> type) {
		return burnDuration(stack);
	}

	public SequencedSet<Item> fuelItems() {
		return new LinkedHashSet<>(values.keySet());
	}

	public Map<Item, Integer> asMap() {
		return Collections.unmodifiableMap(values);
	}

	/**
	 * 1.21.1's actual vanilla fuel table, read from AbstractFurnaceBlockEntity.getFuel().
	 *
	 * <p>BY REFLECTION, AND WHY. This class is compiled on BOTH lines, because it lives in
	 * src/runtime/java which every build scans. AbstractFurnaceBlockEntity.getFuel() exists on 1.21.1 and
	 * does NOT exist on 26.2 -- there, fuel moved into FuelValues itself and the furnace asks that instead,
	 * which javap confirms: patched-mc-merged-26.2.jar's AbstractFurnaceBlockEntity has no getFuel at all.
	 * A direct call would therefore compile on 1.21.1 and break 26.2's build. That was this method's first
	 * version, caught before it was committed.
	 *
	 * <p>So the name is looked up instead of linked. On 1.21.1 it resolves and the real table comes back. On
	 * 26.2 it does not resolve, an empty table is returned, and that is harmless: the rename table never
	 * rewrites 26.2's sources, so nothing on 26.2 refers to this class at all -- it is dead code there.
	 */
	@SuppressWarnings("unchecked")
	public static ForbricFuelValues vanillaBurnTimes(HolderLookup.Provider registries, FeatureFlagSet features) {
		try {
			Class<?> furnace = Class.forName("net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity");
			Object table = furnace.getMethod("getFuel").invoke(null);
			if (table instanceof Map<?, ?> map) return new ForbricFuelValues((Map<Item, Integer>) map);
		} catch (ReflectiveOperationException | RuntimeException absent) {
			// 26.2: the method does not exist there and this class is unreachable on that line.
		}
		return new ForbricFuelValues(new java.util.HashMap<>());
	}

	public static ForbricFuelValues of(Map<Item, Integer> values) {
		return new ForbricFuelValues(values);
	}

	/**
	 * 26.2's {@code EventHooks.getItemBurnTime(ItemStack, int, RecipeType, FuelValues)} -- four arguments.
	 * 1.21.1's {@code EventHooks.getItemBurnTime} takes THREE: it has no FuelValues to pass because the
	 * type does not exist on that line. This bridge accepts the fourth argument so the kernel's call site
	 * keeps its spelling, and delegates to the three-argument hook.
	 *
	 * <p>STATED PLAINLY: the fourth argument is accepted and NOT consulted here. 1.21.1's event hook has no
	 * parameter for a fuel table, so there is nothing on that line for it to be handed to. The burn time
	 * still comes from the same place it does natively: the caller's base value, arbitrated by
	 * FurnaceFuelBurnTimeEvent inside the three-argument hook. This is a signature bridge, not a behaviour
	 * change, and nothing in it pretends the table was looked at.
	 */
	public static int itemBurnTime(ItemStack stack, int base, RecipeType<?> type, ForbricFuelValues fuel) {
		net.neoforged.neoforge.event.furnace.FurnaceFuelBurnTimeEvent event =
				new net.neoforged.neoforge.event.furnace.FurnaceFuelBurnTimeEvent(stack, base, type);
		net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event);
		return event.getBurnTime();
	}
	/** The 26.x Builder, over a plain map. add/remove take the same arguments as 26.2's. */
	public static final class Builder {

		public final Map<Item, Integer> values;
		private final HolderLookup.Provider registries;
		private final FeatureFlagSet features;

		public Builder(HolderLookup.Provider registries, FeatureFlagSet features) {
			this.registries = registries;
			this.features = features;
			this.values = new java.util.HashMap<>();
		}

		public Builder add(TagKey<Item> tag, int burnTime) {
			for (Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
				if (item.builtInRegistryHolder().is(tag)) values.merge(item, burnTime, Math::max);
			}
			return this;
		}

		public Builder add(ItemLike item, int burnTime) {
			values.merge(item.asItem(), burnTime, Math::max);
			return this;
		}

		public Builder remove(TagKey<Item> tag) {
			values.keySet().removeIf(item -> item.builtInRegistryHolder().is(tag));
			return this;
		}

		public ForbricFuelValues build() {
			return new ForbricFuelValues(values);
		}

		public HolderLookup.Provider registries() {
			return registries;
		}

		public FeatureFlagSet enabledFeatures() {
			return features;
		}
	}
}