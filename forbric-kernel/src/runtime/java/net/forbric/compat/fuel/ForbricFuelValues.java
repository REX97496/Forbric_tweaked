package net.forbric.compat.fuel;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.SequencedSet;

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
 * <p>On 1.21.1 there is no FuelValues at all. Fuel lives in AbstractFurnaceBlockEntity: a private static
 * volatile Map of item to burn time, filled by getFuel(), queried through isFuel(ItemStack), and extended
 * through FurnaceFuelBurnTimeEvent. Phase D of docs/1.21.1-architecture-phases.md takes that event as the
 * 1.21.1 extension point.
 *
 * <p>WHAT THIS DOES NOT DO. It does not model NeoForge data maps and it does not replace NeoForge or
 * MinecraftForge fuel arbitration -- both of those arbitrate through the same event that the Fabric
 * listener below wires into, and the listener only overrides when Fabric has an entry for the item.
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
	 * does NOT exist on 26.2 -- there fuel moved into FuelValues itself and the furnace asks that instead,
	 * which javap confirms: the 26.2 merged base has no getFuel at all. A direct call would therefore
	 * compile on 1.21.1 and break the 26.2 build. So the name is looked up instead of linked: it resolves on
	 * 1.21.1 and returns the real table, it does not resolve on 26.2 and returns empty, which is harmless
	 * because the rename table never rewrites 26.2's sources and nothing there refers to this class.
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
	 * Fabric's own fuel registry, wired into 1.21.1's FurnaceFuelBurnTimeEvent. This is D.2 -- the part that
	 * makes Fabric-registered fuel actually burn in a furnace, which is the whole reason KernelFabricFuel
	 * exists. Without it the merged server never sees a Fabric mod's fuel at all.
	 *
	 * <p>WHY LAZILY, FROM HERE. On 26.2 the kernel's fuel bridge is installed by MIXIN INJECTORS into
	 * FuelValues.vanillaBurnTimes -- FabricFuelValuesInjector puts KernelFabricFuel.apply before its
	 * Builder.build() and takePending at its head. 1.21.1 has no vanillaBurnTimes at all, so those injectors
	 * have no target, and the kernel has no other subscription precedent: all five NeoForge.EVENT_BUS uses
	 * in the runtime are post(), none is addListener(). So the install happens on the first burn-time query
	 * instead -- a point the furnace already reaches on every tick that burns something -- by which time the
	 * bus is certainly live. No new wiring point and no edit to the kernel.
	 *
	 * <p>WHY REFLECTION, AND WHY THE LOOKUP IS LOOSE. fabric-content-registries-v0, which holds
	 * FuelRegistry, is not on the measurement classpath (only base, lookup-api and transfer-api are
	 * extracted), so a direct reference would not compile. That module also ships in INTERMEDIARY mappings
	 * -- javap shows Item2ObjectMap.get(net.minecraft.class_1935), not get(Item) -- so looking the method up
	 * by exact parameter type would miss. The lookup takes the first one-argument method named get instead,
	 * which is immune to the naming.
	 */
	private static volatile boolean fabricFuelsInstalled;

	private static void installFabricFuels() {
		if (fabricFuelsInstalled) return;
		synchronized (ForbricFuelValues.class) {
			if (fabricFuelsInstalled) return;
			Object registry;
			java.lang.reflect.Method get;
			try {
				Class<?> fuels = Class.forName("net.fabricmc.fabric.api.registry.FuelRegistry");
				registry = fuels.getField("INSTANCE").get(null);
				get = null;
				for (Class<?> c = fuels; c != null && get == null; c = c.getSuperclass()) {
					for (Class<?> iface : c.getInterfaces()) {
						for (java.lang.reflect.Method m : iface.getMethods()) {
							if (m.getName().equals("get") && m.getParameterCount() == 1) {
								get = m;
								break;
							}
						}
						if (get != null) break;
					}
				}
				if (get == null) return;
			} catch (ReflectiveOperationException | RuntimeException absent) {
				return;
			}
			java.lang.reflect.Method lookup = get;
			net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
					net.neoforged.neoforge.event.furnace.FurnaceFuelBurnTimeEvent.class, event -> {
						try {
							Object burn = lookup.invoke(registry, event.getItemStack().getItem());
							if (burn instanceof Integer time && time > 0) event.setBurnTime(time);
						} catch (ReflectiveOperationException | RuntimeException ignored) {
							// an unreadable Fabric entry leaves the NeoForge answer standing
						}
					});
			fabricFuelsInstalled = true;
		}
	}

	/**
	 * 26.2's EventHooks.getItemBurnTime(ItemStack, int, RecipeType, FuelValues) -- four arguments. 1.21.1's
	 * hook takes THREE: there is no FuelValues to pass because the type does not exist on that line. This
	 * bridge accepts the fourth argument so the kernel's call site keeps its spelling, posts the event
	 * directly rather than delegating (the rename table has a row for the delegating form and it rewrote
	 * this very line the first time this bridge was written), and returns the arbitrated value.
	 *
	 * <p>The fourth argument is accepted and NOT consulted. 1.21.1's event has no parameter for a fuel
	 * table, so there is nothing on that line to hand it to. The burn time still comes from the same place
	 * it does natively: the caller's base value, arbitrated by FurnaceFuelBurnTimeEvent. This is a signature
	 * bridge, not a behaviour change, and nothing in it pretends the table was read.
	 */
	public static int itemBurnTime(ItemStack stack, int base, RecipeType<?> type, ForbricFuelValues fuel) {
		installFabricFuels();
		// ItemStack.getBurnTime(RecipeType) exists on BOTH lines and is where the event is posted natively,
		// so the arbitration happens inside it and this bridge does not have to name the event constructor
		// -- which differs between lines and broke the 26.2 build when this method built the event itself.
		int nativeBurn = stack.getBurnTime(type);
		return nativeBurn > 0 ? nativeBurn : base;
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
