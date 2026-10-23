package net.forbric.kernel.runtime.transfer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.world.item.ItemStack;

/**
 * The audited snapshot of the LEGACY handler stores on this line: {@code items.ItemStackHandler} and
 * {@code fluids.capability.templates.FluidTank} in BOTH ecosystems' flavours -- the stores whose whole transferable
 * state is a graph this class can copy and put back.
 *
 * <p>THIS IS WHERE THE PORT PUTS THE ROLLBACK. On 26.2 the standard stores journal themselves (they are
 * {@code SnapshotJournal}s) and this class exists only for the Forge flavour, which has no transaction: the bridge
 * hands the consumer's {@code TransactionContext} to the store. 1.21.1's stores have no journal at all, so the
 * bridge snapshots them BEFORE every mutation and registers the snapshot with the consumer's Fabric
 * {@code TransactionContext} through {@link PairedTransactions.Journal}. See {@code docs/1.21.1-port.md},
 * "Stage 1.1". Nothing is simulated: a mutating call is a real call, and the undo is the snapshot.
 *
 * <p>THE CERTIFICATE IS STRUCTURAL HERE, NOT A BYTECODE FINGERPRINT, and that is a documented weakening rather
 * than an oversight. 26.2's proof was {@code ForgeTransferShapeAudit.AUDITED} -- SHA-256 fingerprints of the
 * post-transform 26.2 bytecode of each store and helper class. Those hashes cannot match 1.21.1's classes, so this
 * line certifies by shape instead: the store's class must be EXACTLY the standard class of one of the two
 * ecosystems (a subclass could override a transfer method and keep state this snapshot cannot see), the private
 * field layout must be the expected one, the tank's validator must be the registered-pure default instance, and an
 * item's capacity/component helpers must not be overridden. That catches everything the fingerprint caught for the
 * stores themselves; it does NOT catch a third party that transforms the standard class in place. Producing a
 * 1.21.1 fingerprint set is a separate job and is not attempted by this port.
 *
 * <p>Aliases are preserved: two handlers sharing one NonNullList, two slots sharing one ItemStack, and a tank and
 * an external caller sharing a FluidStack all restore together, because the snapshot copies the OBJECT GRAPH -- the
 * mutable ItemStack/FluidStack instances first, then the containers that hold them.
 */
public final class ForgeSnapshotAdapters {
	private ForgeSnapshotAdapters() { }
	private static final Class<?> FORGE_ITEMS = net.minecraftforge.items.ItemStackHandler.class;
	private static final Class<?> NEO_ITEMS = net.neoforged.neoforge.items.ItemStackHandler.class;
	private static final TankShape FORGE_TANK = new TankShape(net.minecraftforge.fluids.capability.templates.FluidTank.class);
	private static final TankShape NEO_TANK = new TankShape(net.neoforged.neoforge.fluids.capability.templates.FluidTank.class);
	private static final Field FORGE_STACKS = field(FORGE_ITEMS, "stacks");
	private static final Field NEO_STACKS = field(NEO_ITEMS, "stacks");
	private static final Field STACK_COMPONENTS = field(ItemStack.class, "components");
	private static final Field STACK_COUNT = field(ItemStack.class, "count");
	private static final Field STACK_POP_TIME = field(ItemStack.class, "popTime");
	private static final Set<Predicate<?>> PURE_VALIDATORS = Collections.newSetFromMap(new IdentityHashMap<>());
	private static final ThreadLocal<SnapshotJournal> CURRENT = new ThreadLocal<>();
	private static boolean forgeDefaultValidatorKnown, neoDefaultValidatorKnown;
	private static volatile boolean stackLayoutAudited;
	private static Field field(Class<?> type, String name) {
		try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
		catch (ReflectiveOperationException drift) { throw new ExceptionInInitializerError(drift); }
	}
	/**
	 * The auditable store behind the wrappers we built: a live view resolves to the store it would write right now,
	 * and a cross-ecosystem facade to the store it delegates to. The audit -- and the snapshot -- are about THAT
	 * object, never about our own wrapper.
	 */
	static Object target(Object store) {
		for (int depth = 0; depth < 4; depth++) {
			Object next = store instanceof LiveTransferEndpoints.LiveItems items ? items.resolvedItems()
					: store instanceof LiveTransferEndpoints.LiveFluids fluids ? fluids.resolvedFluids()
					: ForgeLegacyFacades.unwrap(store);
			if (next == null || next == store) return store;
			store = next;
		}
		return store;
	}
	private static Field stacksOf(Object handler) {
		if (handler == null) return null;
		if (handler.getClass() == FORGE_ITEMS) return FORGE_STACKS;
		if (handler.getClass() == NEO_ITEMS) return NEO_STACKS;
		return null;
	}
	private static TankShape tankOf(Object handler) {
		if (handler == null) return null;
		if (handler.getClass() == FORGE_TANK.type) return FORGE_TANK;
		if (handler.getClass() == NEO_TANK.type) return NEO_TANK;
		return null;
	}
	private static Object read(Field field, Object owner) {
		try { return field.get(owner); }
		catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
	}
	private static int readInt(Field field, Object owner) { return (Integer) read(field, owner); }

	/** The three mutable fields a tank's snapshot restores, resolved once per ecosystem. */
	private record TankShape(Class<?> type, Field fluid, Field capacity, Field validator) {
		TankShape(Class<?> type) { this(type, field(type, "fluid"), field(type, "capacity"), field(type, "validator")); }
	}

	/** Register only an explicitly audited, side-effect-free predicate INSTANCE; never a name-based guess. */
	public static synchronized void approvePureValidator(Predicate<?> validator) { PURE_VALIDATORS.add(java.util.Objects.requireNonNull(validator)); }

	/** Whether {@code store} is a standard item store this line can snapshot. Reports the refusal once per class. */
	public static boolean supportsItems(Object candidate) {
		Object store = target(candidate);
		Field stacks = stacksOf(store);
		boolean supported = stacks != null && read(stacks, store).getClass() == NonNullList.class && stackLayoutAudited();
		if (!supported && candidate != null) TransferIssues.reportType("TRANSFER_HELPER_UNVERIFIED", store.getClass().getName(),
				"an item store is snapshotted only when it is exactly the standard ItemStackHandler of either ecosystem; a subclass can keep transfer state this snapshot cannot see");
		return supported;
	}

	/** Whether {@code store} is a standard fluid store this line can snapshot, with a registered-pure validator. */
	public static synchronized boolean supportsFluids(Object candidate) {
		Object store = target(candidate);
		TankShape shape = tankOf(store);
		if (shape == null || !stackLayoutAudited()) {
			if (store != null) TransferIssues.reportType("TRANSFER_HELPER_UNVERIFIED", store.getClass().getName(),
					"a fluid store is snapshotted only when it is exactly the standard FluidTank of either ecosystem");
			return false;
		}
		// Do not run even a probe constructor until its final definition has been certified.
		if (shape == FORGE_TANK && !forgeDefaultValidatorKnown) {
			PURE_VALIDATORS.add((Predicate<?>) read(shape.validator, new net.minecraftforge.fluids.capability.templates.FluidTank(0)));
			forgeDefaultValidatorKnown = true;
		}
		if (shape == NEO_TANK && !neoDefaultValidatorKnown) {
			PURE_VALIDATORS.add((Predicate<?>) read(shape.validator, new net.neoforged.neoforge.fluids.capability.templates.FluidTank(0)));
			neoDefaultValidatorKnown = true;
		}
		if (PURE_VALIDATORS.contains(read(shape.validator, store))) return true;
		TransferIssues.reportType("TRANSFER_HELPER_UNVERIFIED", store.getClass().getName(),
				"this tank's validator is not an approved side-effect-free instance, so restoring the tank could not be proved safe");
		return false;
	}

	/** The structural half of the certificate: the mutable helpers the snapshot writes back must be these fields. */
	private static boolean stackLayoutAudited() {
		if (!stackLayoutAudited) {
			stackLayoutAudited = STACK_COMPONENTS.getType() == PatchedDataComponentMap.class && STACK_COUNT.getType() == int.class
					&& STACK_POP_TIME.getType() == int.class;
			if (!stackLayoutAudited) TransferIssues.reportType("TRANSFER_HELPER_UNVERIFIED", ItemStack.class.getName(),
					"ItemStack's mutable layout changed; the item snapshot cannot restore what it cannot name");
		}
		return stackLayoutAudited;
	}

	private static SnapshotJournal current() {
		SnapshotJournal journal = CURRENT.get();
		if (journal == null) { journal = new SnapshotJournal(); CURRENT.set(journal); }
		return journal;
	}

	/** Capture the store and register the undo with the consumer's transaction. Call before EVERY mutation. */
	static void prepare(TransactionContext context, Object store) { current().prepare(target(store), context); }

	/** Arm the "this block changed" notification, which runs once after the ROOT commit. */
	static void changed(Object owner, Runnable changed) { current().notifications.put(owner, changed); }

	static void checkAmount(int moved, int maximum) {
		if (moved < 0 || moved > maximum) throw new IllegalStateException("Provider returned invalid amount " + moved + "/" + maximum);
	}

	private record TankBinding(Object fluid, int capacity, Object validator) {
		static TankBinding of(Object tank) {
			TankShape shape = tankOf(tank);
			return new TankBinding(read(shape.fluid, tank), readInt(shape.capacity, tank), read(shape.validator, tank));
		}
		boolean matches(Object tank) {
			TankShape shape = tankOf(tank);
			return read(shape.fluid, tank) == fluid && readInt(shape.capacity, tank) == capacity
					&& read(shape.validator, tank) == validator;
		}
		void restore(Object tank) {
			TankShape shape = tankOf(tank);
			try {
				shape.fluid.set(tank, fluid);
				shape.capacity.setInt(tank, capacity);
				shape.validator.set(tank, validator);
			} catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
		}
	}

	/**
	 * One fluid stack's mutable state. The two ecosystems' stacks are unrelated classes with the same two mutable
	 * parts: an amount and either a tag (Forge) or a component patch (NeoForge, whose 1.21.1 stack is already
	 * component-based). Both are written back through the mutable holder itself.
	 */
	private record FluidValue(Object stack, int amount, net.minecraft.nbt.CompoundTag tag, PatchedDataComponentMap components) {
		static FluidValue of(Object stack) {
			if (stack instanceof net.minecraftforge.fluids.FluidStack forge) {
				return new FluidValue(stack, forge.getAmount(), forge.hasTag() ? forge.getTag().copy() : null, null);
			}
			var neo = (net.neoforged.neoforge.fluids.FluidStack) stack;
			return new FluidValue(stack, neo.getAmount(), null, ((PatchedDataComponentMap) neo.getComponents()).copy());
		}
		void restore() {
			if (stack instanceof net.minecraftforge.fluids.FluidStack forge) {
				forge.setTag(tag == null ? null : tag.copy());
				forge.setAmount(amount);
				return;
			}
			var neo = (net.neoforged.neoforge.fluids.FluidStack) stack;
			((PatchedDataComponentMap) neo.getComponents()).restorePatch(components.asPatch());
			neo.setAmount(amount);
		}
	}

	private record ItemValue(int count, int popTime, PatchedDataComponentMap components) {
		static ItemValue of(ItemStack stack) {
			return new ItemValue(readInt(STACK_COUNT, stack), readInt(STACK_POP_TIME, stack),
					((PatchedDataComponentMap) read(STACK_COMPONENTS, stack)).copy());
		}
		void restore(ItemStack stack) {
			try {
				STACK_COUNT.setInt(stack, count);
				STACK_POP_TIME.setInt(stack, popTime);
				((PatchedDataComponentMap) STACK_COMPONENTS.get(stack)).restorePatch(components.asPatch());
			} catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
		}
	}

	private static final class GraphSnapshot {
		final IdentityHashMap<Object, NonNullList<ItemStack>> bindings = new IdentityHashMap<>();
		final IdentityHashMap<NonNullList<ItemStack>, List<ItemStack>> slots = new IdentityHashMap<>();
		final IdentityHashMap<ItemStack, ItemValue> items = new IdentityHashMap<>();
		final IdentityHashMap<Object, TankBinding> tanks = new IdentityHashMap<>();
		final IdentityHashMap<Object, FluidValue> fluids = new IdentityHashMap<>();
		final IdentityHashMap<Object, Runnable> notifications;
		GraphSnapshot(IdentityHashMap<Object, Runnable> notifications) { this.notifications = new IdentityHashMap<>(notifications); }

		void include(Object store) {
			Field stacksField = stacksOf(store);
			if (stacksField != null) {
				@SuppressWarnings("unchecked") NonNullList<ItemStack> list = (NonNullList<ItemStack>) read(stacksField, store);
				bindings.putIfAbsent(store, list);
				if (slots.containsKey(list)) return;
				List<ItemStack> originals = List.copyOf(list); slots.put(list, originals);
				for (ItemStack original : originals) if (original != ItemStack.EMPTY) items.computeIfAbsent(original, ItemValue::of);
				return;
			}
			TankShape shape = tankOf(store);
			if (shape != null) {
				TankBinding binding = tanks.computeIfAbsent(store, TankBinding::of);
				Object fluid = binding.fluid();
				if (fluid != null && !emptyFluid(fluid)) fluids.computeIfAbsent(fluid, FluidValue::of);
			}
		}
		private static boolean emptyFluid(Object stack) {
			return stack instanceof net.minecraftforge.fluids.FluidStack forge ? forge.isEmpty()
					: ((net.neoforged.neoforge.fluids.FluidStack) stack).isEmpty();
		}
		void restore() {
			// Restore the actual mutable stack objects before reconnecting their containers. External aliases and
			// OTHER containers then see rollback too, instead of retaining the uncommitted count/tag.
			for (var entry : items.entrySet()) entry.getValue().restore(entry.getKey());
			for (var entry : fluids.entrySet()) entry.getValue().restore();
			for (var entry : slots.entrySet()) {
				NonNullList<ItemStack> list = entry.getKey(); List<ItemStack> originals = entry.getValue();
				while (list.size() > originals.size()) list.remove(list.size() - 1);
				while (list.size() < originals.size()) list.add(ItemStack.EMPTY);
				for (int i = 0; i < originals.size(); i++) list.set(i, originals.get(i));
			}
			for (var entry : bindings.entrySet()) {
				try { stacksOf(entry.getKey()).set(entry.getKey(), entry.getValue()); }
				catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
			}
			for (var entry : tanks.entrySet()) entry.getValue().restore(entry.getKey());
		}
	}

	private static final class SnapshotJournal extends PairedTransactions.Journal<GraphSnapshot> {
		final IdentityHashMap<Object, NonNullList<ItemStack>> items = new IdentityHashMap<>();
		final IdentityHashMap<NonNullList<ItemStack>, Integer> sizes = new IdentityHashMap<>();
		final IdentityHashMap<Object, TankBinding> tanks = new IdentityHashMap<>();
		final List<GraphSnapshot> snapshots = new ArrayList<>();
		IdentityHashMap<Object, Runnable> notifications = new IdentityHashMap<>();

		@SuppressWarnings("unchecked")
		void prepare(Object store, TransactionContext context) {
			Field stacks = stacksOf(store);
			if (stacks != null) {
				NonNullList<ItemStack> list = (NonNullList<ItemStack>) read(stacks, store);
				items.putIfAbsent(store, list); sizes.putIfAbsent(list, list.size());
			} else tanks.putIfAbsent(store, TankBinding.of(store));
			PairedTransactions.addValidation(context, this, this::validate);
			PairedTransactions.begin(this, store, context); // include() runs inside, and it validates before any write
		}
		void validate() {
			for (var entry : items.entrySet()) if (read(stacksOf(entry.getKey()), entry.getKey()) != entry.getValue())
				throw new IllegalStateException("Inventory backing changed during a transaction; abort before using its replacement");
			for (var entry : sizes.entrySet()) if (entry.getKey().size() != entry.getValue())
				throw new IllegalStateException("Inventory size changed outside the transaction");
			for (var entry : tanks.entrySet()) if (!entry.getValue().matches(entry.getKey()))
				throw new IllegalStateException("Tank binding/capacity/validator changed outside the transaction");
		}
		void include(Object store) {
			validate();
			for (GraphSnapshot snapshot : snapshots) snapshot.include(store);
		}
		protected GraphSnapshot createSnapshot() {
			GraphSnapshot snapshot = new GraphSnapshot(notifications);
			for (Object handler : items.keySet()) snapshot.include(handler);
			for (Object tank : tanks.keySet()) snapshot.include(tank);
			snapshots.add(snapshot);
			return tracked(snapshot);
		}
		@Override protected void releaseSnapshot(GraphSnapshot snapshot) { snapshots.remove(snapshot); super.releaseSnapshot(snapshot); }
		protected void readSnapshot(GraphSnapshot snapshot) {
			snapshot.restore();
			items.clear(); sizes.clear(); tanks.clear();
			for (var entry : snapshot.bindings.entrySet()) items.put(entry.getKey(), entry.getValue());
			for (var list : items.values()) sizes.put(list, list.size());
			for (var entry : snapshot.tanks.entrySet()) tanks.put(entry.getKey(), entry.getValue());
			notifications = new IdentityHashMap<>(snapshot.notifications);
		}
		protected void onFinalCommit() {
			List<Runnable> callbacks = new ArrayList<>(notifications.values()); clear();
			RuntimeException failure = null;
			for (Runnable callback : callbacks) try { callback.run(); }
			catch (RuntimeException thrown) { if (failure == null) failure = thrown; else failure.addSuppressed(thrown); }
			if (failure != null) throw failure;
		}
		void clear() {
			items.clear(); sizes.clear(); tanks.clear(); snapshots.clear(); notifications.clear();
			PairedTransactions.removeValidation(this);
			if (CURRENT.get() == this) CURRENT.remove();
		}
	}

}
