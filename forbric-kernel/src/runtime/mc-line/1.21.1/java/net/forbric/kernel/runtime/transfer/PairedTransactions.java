package net.forbric.kernel.runtime.transfer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;

/**
 * THE ROLLBACK LAYER of a line that has ONE transaction engine: Fabric's. See
 * {@code docs/1.21.1-port.md}, "Stage 1.1: the transaction question, decided before any code" -- this class is that
 * decision in code, and the reason it is not a reimplementation of a transaction is measured, not assumed.
 *
 * <p>26.2's twin of this class paired TWO engines: it opened a NeoForge transaction that was the peer of the Fabric
 * one and made them close at the same nesting boundary, and the real rollback was each native store's own
 * {@code SnapshotJournal}. 1.21.1 has no NeoForge transaction to pair (the same javap that shows
 * {@code neoforge.transfer} absent shows {@code ItemStackHandler.insertItem(int, ItemStack, boolean)} as the whole
 * mechanism), so there is nothing to pair and nothing native to journal. What survives is Fabric's engine, which
 * still has both halves of the protocol -- {@code TransactionContext.addCloseCallback} and
 * {@code base.SnapshotParticipant}, the class Fabric documents for exactly this -- so the port MOVES THE SNAPSHOT
 * to the bridge's side and lets the surviving engine drive it. {@link Journal} below is that participant base.
 *
 * <p>Verified against the 1.21.1 module's bytecode rather than its documentation ({@code javap -c -p} on
 * {@code SnapshotParticipant}): a snapshot is created once per nesting depth at first touch; an abort at any depth
 * calls {@code readSnapshot} and then {@code releaseSnapshot}; a commit at depth &gt; 0 promotes the snapshot to
 * the parent scope and re-registers, so a later abort of a shallower scope still restores; the outermost commit
 * calls {@code releaseSnapshot} and registers the participant for {@code afterOuterClose}, which is where
 * {@code onFinalCommit} runs -- and only on commit, because that branch is only reached when the result was not
 * an abort. Fabric runs close callbacks in REVERSE registration order and collects their exceptions instead of
 * stopping at the first, which is why one failing participant cannot leave another one un-restored.
 *
 * <p>WHAT IS WEAKER THAN 26.2, stated here rather than discovered later: the pre-commit invariant check. 26.2 ran it
 * from {@code beforeClose}, before the native engine changed anything, so a failure could still refuse a commit.
 * On 1.21.1 the same check runs (a) before every mutation, where it really does refuse, and (b) from
 * {@code beforeClose} if the {@code TransferTransactionHooks} transform installed on this line -- reachable, though
 * its NeoForge anchors are absent by construction here. Without (b) it runs from a close callback, where Fabric has
 * already decided the outcome and an exception can only be reported. (a) is the half that protects the snapshot:
 * every write this bridge makes is refused before it happens.
 */
public final class PairedTransactions {
	private PairedTransactions() { }
	private static final ThreadLocal<State> LOCAL = ThreadLocal.withInitial(State::new);
	private static volatile boolean hooksChecked;
	private static final class State {
		/** Pre-commit invariants, keyed by the ROOT scope that must hold them (26.2 did the same, for the same reason). */
		final IdentityHashMap<Object, IdentityHashMap<Object, Runnable>> validations = new IdentityHashMap<>();
		int closing;
	}

	/**
	 * The per-thread journal base. One journal covers every store the CURRENT transaction touches, which is what
	 * makes aliases work: two handlers sharing a NonNullList, or two views of one tank, are captured by ONE graph
	 * snapshot instead of two snapshots that would each restore half of it.
	 */
	abstract static class Journal<S> extends SnapshotParticipant<S> {
		private final List<S> live = new ArrayList<>();
		protected final S tracked(S snapshot) { live.add(snapshot); return snapshot; }
		/** A store first touched at depth n must still be restored by a shallower abort: every live snapshot learns it. */
		abstract void include(Object store);
		/** Drop the thread state once the last snapshot is gone, as 26.2's journals did. */
		@Override protected void releaseSnapshot(S snapshot) { live.remove(snapshot); if (live.isEmpty()) clear(); }
		abstract void clear();
	}

	/**
	 * Capture {@code store} into {@code journal} and register the journal with the scope that is about to write it.
	 * Every mutating adapter calls this BEFORE the write, which is the whole of the "snapshot ourselves" decision.
	 */
	static void begin(Journal<?> journal, Object store, TransactionContext context) {
		Objects.requireNonNull(context, "A transfer without a transaction context cannot be rolled back");
		usable();
		journal.include(store);
		journal.updateSnapshots(context);
	}

	/**
	 * A scope to perform one consumer operation in: a nested scope of the transaction already open on this thread
	 * (whose commit then belongs to that caller), or a root scope of our own for a consumer that has none -- which is
	 * every Forge and NeoForge caller, since neither of their APIs has a transaction.
	 */
	static Transaction scope() {
		usable();
		return Transaction.isOpen() ? Transaction.getCurrentUnsafe().openNested() : Transaction.openOuter();
	}

	/** Resource-specific invariants attached to the ROOT scope of {@code context}, as on 26.2. */
	public static void addValidation(TransactionContext context, Object owner, Runnable validation) {
		State state = usable();
		Objects.requireNonNull(validation);
		Transaction root = context.getOpenTransaction(0);
		state.validations.computeIfAbsent(root, ignored -> new IdentityHashMap<>()).put(owner, validation);
	}
	public static void removeValidation(Object owner) {
		State state = LOCAL.get();
		state.validations.values().forEach(group -> group.remove(owner));
		state.validations.values().removeIf(IdentityHashMap::isEmpty);
	}

	private static State usable() {
		checkHooks();
		State state = LOCAL.get();
		if (state.closing != 0) throw new IllegalStateException("Cross-API transfer during a transaction close callback is unsupported");
		return state;
	}

	/**
	 * Called by {@code TransferTransactionHooks} at the head of a native {@code open}: a close callback must not open
	 * a new scope, because that scope's writes would never be restored by the scope being closed.
	 */
	public static void beforeOpen() {
		if (LOCAL.get().closing != 0) throw new IllegalStateException("A transaction close callback cannot open a new native scope during a paired close");
	}

	/**
	 * Called by {@code TransferTransactionHooks} before the native close body runs, which on this line is the only
	 * place a check can still refuse a commit. The validations run FIRST, so a failure leaves the closing counter
	 * untouched; the counter is what {@link #beforeOpen} refuses on.
	 */
	public static void beforeClose(Object transaction, boolean committed) {
		State state = LOCAL.get();
		if (committed) {
			var group = state.validations.get(transaction);
			if (group != null) for (Runnable validation : List.copyOf(group.values())) validation.run();
		}
		state.validations.remove(transaction);
		state.closing++;
	}

	/** The native close ran. Always release the bookkeeping, even on failure. */
	public static void afterClose(Object transaction, Throwable nativeFailure) {
		State state = LOCAL.get();
		if (state.closing > 0) state.closing--;
		state.validations.remove(transaction);
		if (state.closing == 0 && state.validations.isEmpty()) LOCAL.remove();
	}

	/**
	 * Replaces Fabric's {@code OuterCloseCallback} invocation, as the transform does on 26.2. There the call was
	 * DEFERRED until both engines had closed; here there is no peer, and deferring would mean nothing ever runs it
	 * (nothing else drains that queue), so it runs now. The hook stays because the transform replaces the call site.
	 */
	public static void fabricFinal(Object callback, Object result) {
		((TransactionContext.OuterCloseCallback) callback).afterOuterClose((TransactionContext.Result) result);
	}

	/**
	 * What this line actually depends on, checked before any store is touched. 26.2 checked the transform's marker
	 * on two NeoForge classes and two Fabric ones; the NeoForge half does not exist here and is not needed, and the
	 * Fabric half is replaced by the public API this class is written against -- a stronger check than a marker,
	 * because it names the members the code calls.
	 */
	public static void checkHooks() {
		if (hooksChecked) return;
		for (String[] member : List.of(
				new String[] { "net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext", "addCloseCallback" },
				new String[] { "net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext", "addOuterCloseCallback" },
				new String[] { "net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext", "getOpenTransaction" },
				new String[] { "net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext", "nestingDepth" },
				new String[] { "net.fabricmc.fabric.api.transfer.v1.transaction.Transaction", "openOuter" },
				new String[] { "net.fabricmc.fabric.api.transfer.v1.transaction.Transaction", "openNested" },
				new String[] { "net.fabricmc.fabric.api.transfer.v1.transaction.Transaction", "getCurrentUnsafe" },
				new String[] { "net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant", "updateSnapshots" })) {
			boolean found = false;
			try {
				Class<?> type = Class.forName(member[0], false, PairedTransactions.class.getClassLoader());
				for (Method method : type.getMethods()) if (method.getName().equals(member[1])) { found = true; break; }
			} catch (ClassNotFoundException | LinkageError missing) {
				throw new IllegalStateException("Transfer transaction API is missing: " + member[0], missing);
			}
			if (!found) throw new IllegalStateException("Transfer transaction API is missing: " + member[0] + "." + member[1]);
		}
		hooksChecked = true;
	}
}
