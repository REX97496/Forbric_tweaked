/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.function.Function;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * Puts vanilla's spawn-position calls back where vanilla makes them.
 *
 * <p>Vanilla asks {@code mob.checkSpawnRules(level, reason)} and then {@code mob.checkSpawnObstruction(level)} at
 * each place a mob's spawn position is judged. NeoForge's patches replaced every such pair with one hook that posts
 * {@code MobSpawnEvent.PositionCheck} and runs the same two calls inside itself when its listeners leave the
 * decision to them: {@code EventHooks.checkSpawnPositionSpawner(mob, level, SPAWNER, spawnData, this)} in
 * {@code BaseSpawner.serverTick} (where vanilla skips the rules for spawn data with custom rules), and
 * {@code EventHooks.checkSpawnPosition(mob, level, reason)} in {@code NaturalSpawner} (natural and chunk-generation
 * spawns) and {@code SpawnUtil} (summoned mobs). The merged base kept NeoForge's bodies, so a Fabric mixin that
 * redirects those calls finds nothing: architectury's {@code MixinBaseSpawner} and {@code MixinNaturalSpawner}
 * (its spawn-check event) are required injections, and on the popular pack each stopped a STRICT dedicated server.
 *
 * <p>The replacement is NeoForge's hook, inlined: the same event posted the same way
 * ({@code KernelSpawnPosition.decide}), its non-DEFAULT answer used as is, and on DEFAULT the two vanilla calls,
 * in vanilla's order, from the caller itself. Nothing is decided differently; the calls are merely where vanilla
 * had them again. A method that already calls {@code checkSpawnRules} is left alone.
 *
 * <p>The edit adds branches, so the class's frames are recomputed, from the hierarchy read as resources
 * ({@link MergedBaseFrameRecomputer#commonSuperClass}); when that cannot be walked the class is left unchanged.
 */
public final class SpawnPositionCallsInjector implements ClassTransformer {
	static final String PROPERTY = "forbric.spawnPositionCalls";
	static final String TARGET = "net.minecraft.world.level.BaseSpawner";
	static final String NATURAL = "net.minecraft.world.level.NaturalSpawner", SUMMON = "net.minecraft.util.SpawnUtil";
	static final List<String> TARGETS = List.of(TARGET, NATURAL, SUMMON);
	static final String HOST_DESC = "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)V";
	static final String NEO = "net/neoforged/neoforge/event/EventHooks", NEO_NAME = "checkSpawnPositionSpawner";
	static final String NEO_DESC = "(Lnet/minecraft/world/entity/Mob;Lnet/minecraft/world/level/ServerLevelAccessor;"
			+ "Lnet/minecraft/world/entity/EntitySpawnReason;Lnet/minecraft/world/level/SpawnData;Lnet/minecraft/world/level/BaseSpawner;)Z";
	static final String NEO_POSITION = "checkSpawnPosition", NEO_POSITION_DESC = "(Lnet/minecraft/world/entity/Mob;"
			+ "Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/entity/EntitySpawnReason;)Z";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelSpawnPosition", RUNTIME_POSITION = "decidePosition";
	static final String DECIDE_DESC = "(Lnet/minecraft/world/entity/Mob;Lnet/minecraft/world/level/ServerLevelAccessor;"
			+ "Lnet/minecraft/world/entity/EntitySpawnReason;Lnet/minecraft/world/level/BaseSpawner;)I";
	static final String MOB = "net/minecraft/world/entity/Mob";
	static final String SPAWN_DATA = "Lnet/minecraft/world/level/SpawnData;";
	static final String BASE_SPAWNER = "Lnet/minecraft/world/level/BaseSpawner;";
	static final String CHECK_RULES = "checkSpawnRules", CHECK_RULES_DESC = "(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/world/entity/EntitySpawnReason;)Z";
	static final String CHECK_OBSTRUCTION = "checkSpawnObstruction", CHECK_OBSTRUCTION_DESC = "(Lnet/minecraft/world/level/LevelReader;)Z";

	private final MergedBaseFrameRecomputer hierarchy;

	/** @param classBytes a class file by resource path, read without loading it (the kernel's game resources) */
	public SpawnPositionCallsInjector(Function<String, byte[]> classBytes) {
		this.hierarchy = new MergedBaseFrameRecomputer(path -> {
			byte[] bytes = classBytes.apply(path);
			if (bytes != null) return bytes;
			try (InputStream in = ClassLoader.getSystemResourceAsStream(path)) {
				return in == null ? null : in.readAllBytes();
			} catch (IOException unreadable) {
				return null;
			}
		});
	}

	static boolean enabled() { return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on")); }

	@Override public String name() { return "forbric-spawner-position-calls"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("spawner position calls explicitly left inside NeoForge's hook with -D" + PROPERTY + "=off");
		return AnchorSet.of(TARGETS.stream().map(target -> new AnchorSet.Anchor(target, AnchorSet.Severity.REQUIRED,
				"a Fabric mixin redirecting checkSpawnRules/checkSpawnObstruction there finds no call to bind to")).toArray(AnchorSet.Anchor[]::new));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || !TARGETS.contains(className) || bytes == null || bytes.length == 0) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		int restored = 0;
		if (TARGET.equals(className)) {
			List<MethodNode> hosts = node.methods.stream().filter(m -> m.name.equals("serverTick") && m.desc.equals(HOST_DESC)).toList();
			if (hosts.size() != 1) return declined(className, bytes, "serverTick is missing or ambiguous");
			MethodNode host = hosts.getFirst();
			if (callsCheckRules(host)) return bytes;   // vanilla's shape already
			List<MethodInsnNode> hooks = hooks(host, NEO_NAME, 5);
			if (hooks.size() != 1) return declined(className, bytes, "expected exactly one NeoForge spawner position hook, found " + hooks.size());
			inline(host, hooks.getFirst(), true);
			restored++;
		}
		for (MethodNode method : node.methods) {
			if (callsCheckRules(method)) continue;
			for (MethodInsnNode hook : hooks(method, NEO_POSITION, 3)) {
				inline(method, hook, false);
				restored++;
			}
		}
		if (restored == 0) return bytes;
		try {
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
				@Override protected String getCommonSuperClass(String type1, String type2) {
					String answer = hierarchy.commonSuperClass(type1, type2);
					if (answer == null || answer.isEmpty()) {
						ForbricLog.warn("[Forbric/Spawner] no common superclass of %s and %s from the hierarchy; using Object", type1, type2);
						return "java/lang/Object";
					}
					return answer;
				}
			};
			node.accept(writer);
			byte[] out = writer.toByteArray();
			ForbricLog.info("[Forbric/Spawner] put checkSpawnRules/checkSpawnObstruction back into %s at %d call site(s), after NeoForge's "
					+ "PositionCheck event as its hook ran them, so a Fabric mixin redirecting them binds", className, restored);
			return out;
		} catch (Throwable unresolved) {
			return retarget(className, bytes, unresolved);
		}
	}

	/**
	 * The fallback for a class whose frames cannot be recomputed: point the hook itself at the kernel, which posts the
	 * same event and runs vanilla's two checks on DEFAULT. A method-reference swap changes no instruction count, so the
	 * class's own frames stay valid and {@code ClassWriter(0)} writes it. What is lost is only the mixin-redirect seam —
	 * the calls happen one frame down — which is still more than NeoForge's hook offered, since its body was equally
	 * unreachable to a Fabric redirect.
	 */
	private static byte[] retarget(String className, byte[] bytes, Throwable unresolved) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		int moved = 0;
		for (MethodNode method : node.methods) {
			for (MethodInsnNode hook : hooks(method, NEO_POSITION, 3)) {
				hook.owner = RUNTIME;
				hook.name = RUNTIME_POSITION;
				moved++;
			}
		}
		if (moved == 0) {
			return declined(className, bytes, "its frames could not be recomputed: " + unresolved + " at" + firstFrames(unresolved));
		}
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		ForbricLog.warn("[Forbric/Spawner] %s's frames could not be recomputed (%s at%s), so its %d position check(s) stay one frame "
				+ "down in the kernel instead of in the caller: the event and vanilla's two checks still run, but a Fabric mixin "
				+ "redirecting checkSpawnRules there will not bind", className, unresolved, firstFrames(unresolved), moved);
		return writer.toByteArray();
	}

	/** The top frames of a frame-recomputation failure, so the log names the method ASM choked on. */
	private static String firstFrames(Throwable failure) {
		StringBuilder out = new StringBuilder();
		StackTraceElement[] trace = failure.getStackTrace();
		for (int i = 0; i < Math.min(6, trace.length); i++) out.append(i == 0 ? " " : " <- ").append(trace[i]);
		return out.toString();
	}

	private static boolean callsCheckRules(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(MOB) && call.name.equals(CHECK_RULES)) return true;
		}
		return false;
	}

	/**
	 * Finds the hook by its name and parameter count rather than by one exact descriptor, because the type that
	 * names a spawn reason differs between lines: 26.2 calls it {@code EntitySpawnReason}, and 1.21.1 has no such
	 * class at all — there the same parameter is {@code MobSpawnType}. Matching the literal descriptor made this
	 * repair report "found 0" on every 1.21.1 launch while the hook was sitting right there.
	 */
	private static List<MethodInsnNode> hooks(MethodNode method, String name, int parameters) {
		List<MethodInsnNode> found = new java.util.ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(NEO)
					&& call.name.equals(name) && parametersOf(call.desc) == parameters
					&& call.desc.endsWith(")Z")) found.add(call);
		}
		return found;
	}

	private static int parametersOf(String desc) {
		String body = desc.substring(1, desc.indexOf(')'));
		int count = 0;
		for (int i = 0; i < body.length(); i++) if (body.charAt(i) == ';' || body.charAt(i) == '[') count++;
		return body.isEmpty() ? 0 : count - countOfArrays(body);
	}

	private static int countOfArrays(String body) {
		int arrays = 0;
		for (int i = 0; i < body.length(); i++) if (body.charAt(i) == '[') arrays++;
		return arrays;
	}

	/**
	 * The kernel's own entry takes the same arguments as the hook minus the spawn data, and returns an int: a
	 * negative value means "no listener decided, run vanilla's rules", 0 or 1 is the event's answer. Derived from
	 * the hook's own descriptor so the reason type is whatever this line declares.
	 */
	private static String decideDesc(String hookDesc) {
		String body = hookDesc.substring(1, hookDesc.indexOf(')'));
		StringBuilder out = new StringBuilder("(");
		int start = 0;
		for (int i = 0; i < body.length(); i++) {
			if (body.charAt(i) != ';') continue;
			String param = body.substring(start, i + 1);
			start = i + 1;
			if (param.equals(SPAWN_DATA)) continue;
			out.append(param);
		}
		// The kernel entry always takes the spawner as its last argument, whether or not the hook it replaces did: the
		// spawner call sites pass theirs, and the plain position checks pass null. Leaving it out of the descriptor
		// made every plain site push one argument too many, so the verifier saw the level where it wanted the mob.
		if (!body.contains(BASE_SPAWNER)) out.append(BASE_SPAWNER);
		return out.append(")I").toString();
	}

	/**
	 * Replaces {@code hook} with its own body: the event through {@code KernelSpawnPosition.decide}, and on DEFAULT
	 * the vanilla calls. The spawner form also takes the spawn data and the spawner, and skips the rules for data
	 * that carries custom ones, as vanilla's {@code serverTick} does.
	 */
	private static void inline(MethodNode host, MethodInsnNode hook, boolean spawner) {
		int mob = host.maxLocals, level = mob + 1, reason = mob + 2, data = mob + 3, spawnerSlot = mob + 4;
		LabelNode byVanilla = new LabelNode(), obstruction = new LabelNode(), denied = new LabelNode(), done = new LabelNode();
		InsnList code = new InsnList();
		if (spawner) { code.add(new VarInsnNode(Opcodes.ASTORE, spawnerSlot)); code.add(new VarInsnNode(Opcodes.ASTORE, data)); }
		code.add(new VarInsnNode(Opcodes.ASTORE, reason)); code.add(new VarInsnNode(Opcodes.ASTORE, level));
		code.add(new VarInsnNode(Opcodes.ASTORE, mob));
		code.add(new VarInsnNode(Opcodes.ALOAD, mob)); code.add(new VarInsnNode(Opcodes.ALOAD, level));
		code.add(new VarInsnNode(Opcodes.ALOAD, reason));
		code.add(spawner ? new VarInsnNode(Opcodes.ALOAD, spawnerSlot) : new InsnNode(Opcodes.ACONST_NULL));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "decide", decideDesc(hook.desc), false));
		code.add(new InsnNode(Opcodes.DUP)); code.add(new JumpInsnNode(Opcodes.IFLT, byVanilla));
		code.add(new JumpInsnNode(Opcodes.GOTO, done));                 // 1 or 0: the listeners decided
		code.add(byVanilla); code.add(new InsnNode(Opcodes.POP));
		if (spawner) {
			code.add(new VarInsnNode(Opcodes.ALOAD, data));
			code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/SpawnData", "getCustomSpawnRules", "()Ljava/util/Optional;", false));
			code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "isPresent", "()Z", false));
			code.add(new JumpInsnNode(Opcodes.IFNE, obstruction));
		}
		code.add(new VarInsnNode(Opcodes.ALOAD, mob)); code.add(new VarInsnNode(Opcodes.ALOAD, level)); code.add(new VarInsnNode(Opcodes.ALOAD, reason));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MOB, CHECK_RULES, CHECK_RULES_DESC, false));
		code.add(new JumpInsnNode(Opcodes.IFEQ, denied));
		code.add(obstruction);
		code.add(new VarInsnNode(Opcodes.ALOAD, mob)); code.add(new VarInsnNode(Opcodes.ALOAD, level));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MOB, CHECK_OBSTRUCTION, CHECK_OBSTRUCTION_DESC, false));
		code.add(new JumpInsnNode(Opcodes.IFEQ, denied));
		code.add(new InsnNode(Opcodes.ICONST_1)); code.add(new JumpInsnNode(Opcodes.GOTO, done));
		code.add(denied); code.add(new InsnNode(Opcodes.ICONST_0));
		code.add(done);
		// Before the call, not after: the hook's own arguments are still on the stack there and this code pops them into
		// locals. Inserted after, it popped the hook's boolean result instead and read slots nothing had written — the
		// verifier named exactly that ("expected Mob, but found ServerLevel") and ASM's frame merge then failed on the
		// unbalanced stack it produced.
		host.instructions.insertBefore(hook, code);
		host.instructions.remove(hook);
		host.maxLocals += spawner ? 5 : 3;
	}

	private static byte[] declined(String className, byte[] bytes, String reason) {
		ForbricLog.warn("[Forbric/Spawner] left %s's position checks inside NeoForge's hook: %s", className, reason);
		return bytes;
	}
}
