/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import net.forbric.kernel.remap.GuestJarRemap;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Leaves out a guest mixin's handler when the member it calls has no row in this line's namespace table.
 *
 * <p>Why this exists. The refmap-key repair (see {@code GuestJarRemap.REFMAP_KEYS}) makes a guest mixin's
 * {@code @At} selectors resolve, so handlers that never attached on the merged base now attach: measured on a
 * cold boot, {@code injection warning} 172 -> 87 and unattached handlers 169 -> 85. The same change also makes
 * those handlers RUN, and a guest jar can still name a member the shipped table has no row for — the rewrite
 * leaves such a name in intermediary spelling, and the JVM answers {@code NoSuchMethodError} the first time the
 * handler executes, minutes after the boot looked healthy. Measured, once, before the table was repaired:
 * {@code NoSuchMethodError: 'int net.minecraft.server.MinecraftServer.method_3773()'} from
 * {@code ServerLoginNetworkAddon.sendCompressionPacket} through {@code ServerLoginNetworkHandlerMixin}, which took
 * the integrated server down.
 *
 * <p>Dropping the whole mixin was tried for that and made it worse — it also drops the classes that IMPLEMENT an
 * interface the remaining code still calls, which turns a {@code NoSuchMethodError} inside fabric's login addon
 * into an {@code AbstractMethodError} from {@code Connection.disconnectAddon}. So the unit here is the HANDLER:
 * its injection annotation is removed and the rest of the mixin still applies. That is exactly the state Mixin
 * itself reaches when a selector does not resolve ("this handler is not attached"), so nothing that worked stops
 * working; what changes is that the failure cannot be deferred into game time.
 *
 * <p>Runs before {@code FinalMixinApplications.remember(node)}, so the ledger does not expect a handler that is
 * gone. Silent when the class calls no unmapped member, which is every class of every jar that was rewritten
 * completely.
 */
final class MixinUnmappedMemberHandlers {
	/** Method annotations that make Mixin run a body. {@code @At}/{@code @Slice}/{@code @Desc} are nested, never here. */
	private static final Set<String> HANDLERS = Set.of(
			"Lorg/spongepowered/asm/mixin/injection/Inject;",
			"Lorg/spongepowered/asm/mixin/injection/Redirect;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
			"Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
			"Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
			"Lcom/llamalad7/mixinextras/injector/WrapOperation;",
			"Lcom/llamalad7/mixinextras/injector/WrapWithCondition;");

	private MixinUnmappedMemberHandlers() {
	}

	/** Mixin classes that passed through with at least one member in intermediary spelling. */
	private static final java.util.concurrent.atomic.AtomicInteger SEEN =
			new java.util.concurrent.atomic.AtomicInteger();
	/** Handlers those classes declared. */
	private static final java.util.concurrent.atomic.AtomicInteger HANDLERS_SEEN =
			new java.util.concurrent.atomic.AtomicInteger();
	/** Handlers left out because their own body calls one of those members. */
	private static final java.util.concurrent.atomic.AtomicInteger LEFT_OUT =
			new java.util.concurrent.atomic.AtomicInteger();
	private static final java.util.concurrent.atomic.AtomicBoolean SAID =
			new java.util.concurrent.atomic.AtomicBoolean();

	/** Why the guard exists, and how often it had to act, at shutdown: zero is a measurement, not an assumption. */
	static void summarise() {
		if (!SAID.compareAndSet(false, true)) return;
		if (SEEN.get() == 0) {
			ForbricLog.durableInfo("[Forbric/Mixin] no guest mixin still named a member in intermediary spelling, so"
					+ " the guard that leaves out a handler calling one had nothing to do this session");
			return;
		}
		ForbricLog.durableInfo("[Forbric/Mixin] %d mixin class(es) still name a member in intermediary spelling"
				+ " (the table has no row for it); their %d handler(s) were checked and %d left out because the"
				+ " handler itself calls one, which is the difference between 'the hook is not attached' and"
				+ " 'the hook runs and throws NoSuchMethodError in game time'",
				SEEN.get(), HANDLERS_SEEN.get(), LEFT_OUT.get());
	}

	/** Removes the injection annotation of every handler in this mixin that reaches an unmapped member. */
	static void leaveOut(ClassNode node) {
		if (node == null || node.name == null) return;
		Set<String> unmapped = GuestJarRemap.leftoverMembersOf(node.name);
		if (unmapped == null || unmapped.isEmpty()) return;
		SEEN.incrementAndGet();
		Map<String, Set<String>> leftOut = new LinkedHashMap<>();
		for (MethodNode method : node.methods) {
			if (!isHandler(method)) continue;
			HANDLERS_SEEN.incrementAndGet();
			Set<String> reached = reachedUnmapped(node, method, unmapped);
			if (reached.isEmpty()) continue;
			stripHandlerAnnotations(method);
			leftOut.put(method.name + method.desc, reached);
		}
		if (leftOut.isEmpty()) return;
		LEFT_OUT.addAndGet(leftOut.size());
		ForbricLog.info("[Forbric/Mixin] left out %d handler(s) of %s: the member they call has no row in this line's"
				+ " namespace table, so attaching one would raise NoSuchMethodError at first use instead of not"
				+ " attaching here; the rest of the mixin still applies: %s",
				leftOut.size(), node.name.replace('/', '.'), leftOut);
	}

	private static boolean isHandler(MethodNode method) {
		if (method.visibleAnnotations != null && method.visibleAnnotations.stream().anyMatch(MixinUnmappedMemberHandlers::isHandler)) return true;
		return method.invisibleAnnotations != null && method.invisibleAnnotations.stream().anyMatch(MixinUnmappedMemberHandlers::isHandler);
	}

	private static boolean isHandler(AnnotationNode annotation) {
		return annotation != null && annotation.desc != null && HANDLERS.contains(annotation.desc);
	}

	private static void stripHandlerAnnotations(MethodNode method) {
		if (method.visibleAnnotations != null) method.visibleAnnotations.removeIf(MixinUnmappedMemberHandlers::isHandler);
		if (method.invisibleAnnotations != null) method.invisibleAnnotations.removeIf(MixinUnmappedMemberHandlers::isHandler);
	}

	/**
	 * The unmapped members this handler's body reaches, following the lambdas it names in this same class: a
	 * handler that calls a {@code lambda$…} body which makes the call is exactly as fatal, and javac puts the call
	 * inside the lambda body.
	 */
	private static Set<String> reachedUnmapped(ClassNode owner, MethodNode handler, Set<String> unmapped) {
		Set<String> reached = new LinkedHashSet<>();
		Set<MethodNode> pending = new LinkedHashSet<>();
		Set<String> visited = new LinkedHashSet<>();
		pending.add(handler);
		for (int depth = 0; depth < 4 && !pending.isEmpty(); depth++) {
			Set<MethodNode> next = new LinkedHashSet<>();
			for (MethodNode method : pending) {
				if (!visited.add(method.name + method.desc) || method.instructions == null) continue;
				for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn instanceof MethodInsnNode call) {
						if (unmapped.contains(call.name)) reached.add(call.name);
					} else if (insn instanceof FieldInsnNode field) {
						if (unmapped.contains(field.name)) reached.add(field.name);
					} else if (insn instanceof InvokeDynamicInsnNode dynamic) {
						for (Object argument : dynamic.bsmArgs) {
							if (!(argument instanceof org.objectweb.asm.Handle handle)) continue;
							if (!handle.getOwner().equals(owner.name)) continue;
							for (MethodNode candidate : owner.methods) {
								if (candidate.name.equals(handle.getName()) && candidate.desc.equals(handle.getDesc())) next.add(candidate);
							}
						}
					}
				}
			}
			pending = next;
		}
		return reached;
	}

	/** Names of the handlers this pass would leave out, without editing anything; for the census. */
	static java.util.List<String> wouldLeaveOut(ClassNode node) {
		java.util.List<String> names = new ArrayList<>();
		if (node == null || node.name == null) return names;
		Set<String> unmapped = GuestJarRemap.leftoverMembersOf(node.name);
		if (unmapped == null || unmapped.isEmpty()) return names;
		for (MethodNode method : node.methods) {
			if (isHandler(method) && !reachedUnmapped(node, method, unmapped).isEmpty()) names.add(method.name + method.desc);
		}
		return names;
	}
}
