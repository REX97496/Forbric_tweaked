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
 *
 * <p><b>How a leftover is recognised (Round 157).</b> The first version asked
 * {@code GuestJarRemap.leftoverMembersOf(className)}, a map the rewrite filled while it answered names. A warm boot
 * hands back the cached jars without rewriting anything, so that map came up empty and this guard did nothing —
 * measured: 85 handlers never attached on a cold boot, 83 on a warm one, the two the guard had left out being
 * attached again. The question this class actually asks is narrower than "which members did the table miss": a class
 * file that calls a member literally named {@code method_1234} or {@code field_567} cannot work on a mojmap base no
 * matter how it got there, because the official names are camel case. So the test is now made from the handler's own
 * body, and a cold and a warm boot answer it the same way.
 */
final class MixinUnmappedMemberHandlers {
	/** A member name no official-mapped base declares; yarn/intermediary spellings look exactly like this. */
	private static final java.util.regex.Pattern INTERMEDIARY_MEMBER =
			java.util.regex.Pattern.compile("^(?:method|field)_\\d+$");

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
		if (HANDLERS_SEEN.get() == 0) {
			ForbricLog.durableInfo("[Forbric/Mixin] no guest mixin declared an injection handler this session, so the"
					+ " guard that leaves out a handler calling an intermediary-spelled member had nothing to look at");
			return;
		}
		ForbricLog.durableInfo("[Forbric/Mixin] %d handler(s) were checked for calls to a member still spelled the"
				+ " intermediary way; %d of them in %d mixin class(es) were left out because their own body makes such"
				+ " a call, which is the difference between 'the hook is not attached' and 'the hook runs and throws"
				+ " NoSuchMethodError in game time'",
				HANDLERS_SEEN.get(), LEFT_OUT.get(), SEEN.get());
	}

	/** Removes the injection annotation of every handler in this mixin that reaches an unmapped member. */
	static void leaveOut(ClassNode node) {
		if (node == null || node.name == null) return;
		Map<String, Set<String>> leftOut = new LinkedHashMap<>();
		for (MethodNode method : node.methods) {
			if (!isHandler(method)) continue;
			HANDLERS_SEEN.incrementAndGet();
			Set<String> reached = reachedIntermediary(node, method);
			if (reached.isEmpty()) continue;
			stripHandlerAnnotations(method);
			leftOut.put(method.name + method.desc, reached);
		}
		if (leftOut.isEmpty()) return;
		SEEN.incrementAndGet();
		LEFT_OUT.addAndGet(leftOut.size());
		ForbricLog.info("[Forbric/Mixin] left out %d handler(s) of %s: the body calls a member still spelled the"
				+ " intermediary way, which no official-mapped base declares, so attaching one would raise"
				+ " NoSuchMethodError at first use instead of not attaching here; the rest of the mixin still applies: %s",
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
	 * The intermediary-spelled members this handler's body reaches, following the lambdas it names in this same
	 * class: a handler that calls a {@code lambda$…} body which makes the call is exactly as fatal, and javac puts
	 * the call inside the lambda body. Each answer is spelled {@code owner.name} so the log can be compared with
	 * {@code javap} without guessing which call it was.
	 */
	private static Set<String> reachedIntermediary(ClassNode owner, MethodNode handler) {
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
						if (INTERMEDIARY_MEMBER.matcher(call.name).matches()) reached.add(call.owner + "." + call.name);
					} else if (insn instanceof FieldInsnNode field) {
						if (INTERMEDIARY_MEMBER.matcher(field.name).matches()) reached.add(field.owner + "." + field.name);
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
		for (MethodNode method : node.methods) {
			if (isHandler(method) && !reachedIntermediary(node, method).isEmpty()) names.add(method.name + method.desc);
		}
		return names;
	}
}
