package net.forbric.kernel.classloading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.AncestorComposition;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.transform.ForgeCapabilityCompositionTransformer;

/**
 * The 1.21.1 launch blocker, proved against the real 1.21.1 merged base: a certificate is produced for
 * {@code Entity}, {@code Level} and {@code BlockEntity}, and {@link RequiredAncestorCompositions#verify} stops
 * throwing for them.
 *
 * <p>The premise is the base's own manifest, not a changelog: on this line all three rows name the <em>same</em>
 * removed superclass, {@code net/minecraftforge/common/capabilities/CapabilityProvider}, where 26.2 names a different
 * {@code $}-nested provider per root. {@code protocol()} was written against the 26.2 rows and returns false on this
 * one at its first two preconditions; this test is what says the second shape is real.
 *
 * <p>It reads two things a clean checkout does not have, so it skips there rather than failing:
 * <ul>
 *   <li>the 1.21.1 staged tree, {@code -Dforbric.stagedRoot1211=<dir>} or the sibling of the 26.2 one;</li>
 *   <li>the 1.21.1 runtime overlay compiled in this line's own names,
 *       {@code -Dforbric.runtimeClasses1211=<dir>} or {@code build/x11/classes1211-runtime}. That directory is
 *       produced by {@code build/x11/compile-1211-runtime.sh}, which compiles
 *       {@code src/runtime/mc-line/1.21.1/java/net/forbric/kernel/runtime/KernelForgeCapabilities.java} against the
 *       real 1.21.1 merged base and carrier — <b>not a stub</b>: the rest of the 1.21.1 runtime source set still does
 *       not compile, and compiling the capability provider alone is what makes this a test of real bytes.</li>
 * </ul>
 */
class ForgeCapabilityComposition1211Test {
	private static final String ENTITY = "net/minecraft/world/entity/Entity";
	private static final String LEVEL = "net/minecraft/world/level/Level";
	private static final String BLOCK_ENTITY = "net/minecraft/world/level/block/entity/BlockEntity";
	private static final String CARRIER = "net/minecraftforge/common/capabilities/CapabilityProvider";
	private static final String AS_FIELD = CARRIER + "$AsField";
	private static final String DRAGON_PART = "net/minecraft/world/entity/boss/EnderDragonPart";
	private static final String MANIFEST = "META-INF/forbric/required-ancestor-compositions.tsv";
	private static final String RUNTIME = "net/forbric/kernel/runtime/KernelForgeCapabilities";
	// The emitted names, spelled out: the assertions are about the artifact the transformer writes, and these are
	// what a jar shows. Kept as literals so the production constants stay package-private.
	private static final String CAP_PROVIDER = "capProvider";
	private static final String COMPOSED_FIELD = "forbric$forgeCaps";
	private static final String ACCESSOR = "forbric$caps";
	private static final String ACCESSOR_DESC = "()Lnet/minecraftforge/common/capabilities/CapabilityProvider$AsField;";

	private static final Path STAGED = staged1211();
	private static final Path MERGED = STAGED.resolve("merged-base/patched-mc-merged-1.21.1.jar");
	private static final Path FORGE = STAGED.resolve("forge-runtime/forge-runtime.jar");
	private static final Path RUNTIME_CLASSES = runtimeClasses1211();

	/**
	 * The 1.21.1 tree: {@code -Dforbric.stagedRoot1211}, else the first candidate that actually holds the base. It is
	 * staged beside the 26.2 root ({@code .dev/staged-1211/run}), which is not a sibling of anything derivable from the
	 * 26.2 root once Gradle's fixture policy leaves that property unset, so the candidates are spelled out.
	 */
	private static Path staged1211() {
		String configured = System.getProperty("forbric.stagedRoot1211");
		if (configured != null) return Path.of(configured);
		Path work = Path.of(System.getProperty("user.dir", "."));
		List<Path> candidates = new ArrayList<>(List.of(
				work.resolve(".dev/staged-1211/run"),
				work.resolve("../forbric-kernel/.dev/staged-1211/run").normalize(),
				work.resolve("forbric-kernel/.dev/staged-1211/run")));
		Path staged = TestFixtures.stagedRoot();
		if (staged.getParent() != null && staged.getParent().getParent() != null) {
			candidates.add(staged.getParent().getParent().resolve("staged-1211/run"));
		}
		for (Path candidate : candidates) {
			if (Files.isRegularFile(candidate.resolve("merged-base/patched-mc-merged-1.21.1.jar"))) return candidate;
		}
		return candidates.get(0);
	}

	private static Path runtimeClasses1211() {
		String configured = System.getProperty("forbric.runtimeClasses1211");
		return configured != null ? Path.of(configured)
				: Path.of(System.getProperty("user.dir", "build"), "build/x11/classes1211-runtime");
	}

	/**
	 * The acceptance criterion. {@code RequiredAncestorCompositions.verify} is the exact method
	 * {@code ForbricClassLoader.define} runs before defining a class; a {@code LinkageError} from it is the launch
	 * blocker. No file is defined here — that is the point of the seam: the proof is about bytes.
	 */
	@Test
	void theRealBaseIsProvedForTheThreeRootsItRequires() throws Exception {
		Function<String, byte[]> reader = reader();
		RequiredAncestorCompositions contracts = new RequiredAncestorCompositions();
		ForgeCapabilityCompositionTransformer transformer = new ForgeCapabilityCompositionTransformer(reader);
		contracts.register(transformer);

		List<AncestorComposition.Requirement> requirements = manifest(reader);
		assertEquals(Set.of(ENTITY, LEVEL, BLOCK_ENTITY),
				requirements.stream().map(AncestorComposition.Requirement::owner)
						.filter(owner -> Set.of(ENTITY, LEVEL, BLOCK_ENTITY).contains(owner)).collect(Collectors.toSet()),
				"premise: this base requires exactly the three capability roots");
		// The premise that makes the old certificate impossible: ONE removed class for all three roots.
		assertEquals(Set.of(CARRIER), requirements.stream().map(AncestorComposition.Requirement::sourceSuperclass)
				.filter(CARRIER::equals).collect(Collectors.toSet()));
		assertEquals(3, requirements.stream().filter(r -> CARRIER.equals(r.sourceSuperclass())).count(),
				"premise: 26.2 names a different $nested provider per root; this line names the carrier itself three times");

		// The control: with no proof registered, the same call throws. This is what "the blocker is real" means.
		for (AncestorComposition.Requirement requirement : requirements) {
			if (!Set.of(ENTITY, LEVEL, BLOCK_ENTITY).contains(requirement.owner())) continue;
			byte[] emitted = transformer.transform(requirement.owner().replace('/', '.'), require(reader, requirement.owner() + ".class"), null);
			assertThrows(LinkageError.class,
					() -> new RequiredAncestorCompositions().verify(requirement.owner().replace('/', '.'), emitted, reader),
					requirement.owner() + " must be refused by a loader with no composition registered");
			contracts.verify(requirement.owner().replace('/', '.'), emitted, reader);   // and accepted by one with it
		}

		// A row this subsystem does not cover is NOT silently claimed: EnderDragonPart is PartEntity's composition.
		for (AncestorComposition.Requirement requirement : requirements) {
			if (!DRAGON_PART.equals(requirement.owner())) continue;
			assertFalse(transformer.proves(requirement, require(reader, requirement.owner() + ".class"), reader),
					"the capability protocol must not claim the PartEntity row");
			assertThrows(LinkageError.class, () -> contracts.verify(requirement.owner().replace('/', '.'),
					require(reader, requirement.owner() + ".class"), reader),
					"and the loader still refuses it until PartEntity's own composition proves it");
		}
	}

	/** P6, the one per-root fact that survives this line's model change: the delegate's {@code Class} literal. */
	@Test
	void aDelegateThatNamesTheWrongRootIsRefused() throws Exception {
		Function<String, byte[]> reader = reader();
		ForgeCapabilityCompositionTransformer transformer = new ForgeCapabilityCompositionTransformer(reader);
		byte[] emitted = transformer.transform(LEVEL.replace('/', '.'), require(reader, LEVEL + ".class"), null);
		AncestorComposition.Requirement requirement = requirement(reader, LEVEL);
		assertTrue(transformer.proves(requirement, emitted, reader));

		byte[] wrongLiteral = require(reader, RUNTIME + "$Entities.class");
		Map<String, byte[]> lies = Map.of(RUNTIME + "$Levels.class", wrongLiteral);
		assertFalse(transformer.proves(requirement, emitted, lying(reader, lies)),
				"a Level composed from the Entity delegate would post a Level to AttachCapabilitiesEvent<Entity> and lose every listener");
	}

	/** P5: the certificate is only worth something if it refuses a carrier that stopped keying the event on the Class. */
	@Test
	void aCarrierWhoseGatherNoLongerReadsBaseClassIsRefused() throws Exception {
		Function<String, byte[]> reader = reader();
		ForgeCapabilityCompositionTransformer transformer = new ForgeCapabilityCompositionTransformer(reader);
		byte[] emitted = transformer.transform(ENTITY.replace('/', '.'), require(reader, ENTITY + ".class"), null);
		AncestorComposition.Requirement requirement = requirement(reader, ENTITY);
		assertTrue(transformer.proves(requirement, emitted, reader));

		ClassNode carrier = parse(require(reader, CARRIER + ".class"));
		MethodNode gather = carrier.methods.stream()
				.filter(m -> (m.name + m.desc).equals("doGatherCapabilities(Lnet/minecraftforge/common/capabilities/ICapabilityProvider;)V"))
				.findFirst().orElseThrow();
		gather.instructions.insert(new InsnNode(Opcodes.NOP));   // same meaning, different bytecode: the proof is a fingerprint
		assertFalse(transformer.proves(requirement, emitted,
				lying(reader, Map.of(CARRIER + ".class", write(carrier)))));
	}

	/** P2: state the composition does not account for cannot appear in the carrier unnoticed. */
	@Test
	void extraCarrierStateIsRefused() throws Exception {
		Function<String, byte[]> reader = reader();
		ForgeCapabilityCompositionTransformer transformer = new ForgeCapabilityCompositionTransformer(reader);
		byte[] emitted = transformer.transform(BLOCK_ENTITY.replace('/', '.'), require(reader, BLOCK_ENTITY + ".class"), null);
		AncestorComposition.Requirement requirement = requirement(reader, BLOCK_ENTITY);
		assertTrue(transformer.proves(requirement, emitted, reader));

		ClassNode carrier = parse(require(reader, CARRIER + ".class"));
		carrier.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "unknownState", "I", null, null));
		assertFalse(transformer.proves(requirement, emitted, lying(reader, Map.of(CARRIER + ".class", write(carrier)))),
				"unknown inherited state cannot be dismissed because the delegates still compile");
	}

	/** P4: {@code initInternal} IS the eager gather on this line; a version that no longer gathers must not certify. */
	@Test
	void anInitInternalThatStopsGatheringIsRefused() throws Exception {
		Function<String, byte[]> reader = reader();
		ForgeCapabilityCompositionTransformer transformer = new ForgeCapabilityCompositionTransformer(reader);
		byte[] emitted = transformer.transform(ENTITY.replace('/', '.'), require(reader, ENTITY + ".class"), null);
		AncestorComposition.Requirement requirement = requirement(reader, ENTITY);
		assertTrue(transformer.proves(requirement, emitted, reader));

		ClassNode asField = parse(require(reader, AS_FIELD + ".class"));
		for (MethodNode method : asField.methods) {
			if (!(method.name + method.desc).equals("initInternal()V")) continue;
			method.instructions.clear();
			method.instructions.add(new InsnNode(Opcodes.RETURN));
		}
		assertFalse(transformer.proves(requirement, emitted, lying(reader, Map.of(AS_FIELD + ".class", write(asField)))));
	}

	/** The composed definition is still the thing being certified: something that has lost the state is refused. */
	@Test
	void aDefinitionWithoutTheComposedStateIsStillRefused() throws Exception {
		Function<String, byte[]> reader = reader();
		RequiredAncestorCompositions contracts = new RequiredAncestorCompositions();
		contracts.register(new ForgeCapabilityCompositionTransformer(reader));
		for (String owner : List.of(ENTITY, LEVEL, BLOCK_ENTITY)) {
			ClassNode lost = parse(new ForgeCapabilityCompositionTransformer(reader).transform(
					owner.replace('/', '.'), require(reader, owner + ".class"), null));
			lost.fields.removeIf(f -> f.name.equals(COMPOSED_FIELD));
			assertThrows(LinkageError.class, () -> contracts.verify(owner.replace('/', '.'), write(lost), reader), owner);
		}
	}

	/**
	 * The dispatch half, which the previous round refused to port alone: the {@code CompoundTag} save/load seam, the
	 * constructor gather, and the per-root accessor. Asserted because a certificate over a class that answers nothing
	 * is exactly the failure mode this stage exists to avoid.
	 */
	@Test
	void theCompositionAlsoReconnectsThisLinesSaveLoadSeamAndPerRootAccessor() throws Exception {
		Function<String, byte[]> reader = reader();
		ForgeCapabilityCompositionTransformer transformer = new ForgeCapabilityCompositionTransformer(reader);

		ClassNode entity = parse(transformer.transform(ENTITY.replace('/', '.'), require(reader, ENTITY + ".class"), null));
		ClassNode level = parse(transformer.transform(LEVEL.replace('/', '.'), require(reader, LEVEL + ".class"), null));
		ClassNode blockEntity = parse(transformer.transform(BLOCK_ENTITY.replace('/', '.'), require(reader, BLOCK_ENTITY + ".class"), null));

		assertEquals(1, count(entity, "saveWithoutId", "(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;", "saveEntity"));
		assertEquals(1, count(entity, "load", "(Lnet/minecraft/nbt/CompoundTag;)V", "loadEntity"));
		assertEquals(1, count(blockEntity, "saveAdditional", "(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V", "saveBlockEntity"));
		assertEquals(1, count(blockEntity, "loadAdditional", "(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V", "loadBlockEntity"));
		// The value this line's ValueInput/ValueOutput overload would have carried does not exist here; what must NOT
		// happen is a delegate naming a class this line does not have.
		assertTrue(declared(blockEntity, "serializeCaps", "(Lnet/minecraft/world/level/storage/ValueOutput;)Lnet/minecraft/nbt/CompoundTag;") == null,
				"this line has no ValueOutput: the 26.2 overload must not be emitted");

		// Entity and BlockEntity gather in their own constructor on this line, exactly as on 26.2; Level does not —
		// Forge's level capabilities are gathered by ServerLevel.initCapabilities(), which is asserted below. The same
		// distinction the 26.2 composition test draws, for the same reason.
		for (ClassNode root : List.of(entity, blockEntity)) {
			for (MethodNode ctor : root.methods) {
				if (!ctor.name.equals("<init>")) continue;
				assertTrue(calls(ctor, root.name, "gatherCapabilities"),
						root.name + ": Forge's eager constructor gather must be restored from the carrier");
			}
		}
		assertTrue(hasConstructorGather(level) == false,
				"Level has no constructor gather on this line, so none may be invented");
		assertTrue(callsAccessor(entity, "entity") && callsAccessor(level, "level") && callsAccessor(blockEntity, "blockEntity"),
				"each root's accessor must build its own delegate: that difference IS the per-root key on this line");
	}

	/** Forge's {@code ServerLevel.initCapabilities()} went with the superclass; both halves are restored or nothing is. */
	@Test
	void serverLevelGathersAgainAndLevelChunkWritesForgesProvider() throws Exception {
		Function<String, byte[]> reader = reader();
		ForgeCapabilityCompositionTransformer transformer = new ForgeCapabilityCompositionTransformer(reader);

		ClassNode serverLevel = parse(transformer.transform("net.minecraft.server.level.ServerLevel",
				require(reader, "net/minecraft/server/level/ServerLevel.class"), null));
		MethodNode init = declared(serverLevel, "initCapabilities", "()V");
		assertNotNull(init, "initCapabilities() must be restored: the merge dropped it with the superclass");
		assertTrue(calls(init, serverLevel.name, "gatherCapabilities"));
		assertTrue(calls(init, serverLevel.name, "getDataStorage"));
		assertTrue(calls(init, serverLevel.name, "getCapabilities"));
		assertEquals(1, count(serverLevel, "<init>",
				"(Lnet/minecraft/server/MinecraftServer;Ljava/util/concurrent/Executor;"
						+ "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"
						+ "Lnet/minecraft/world/level/storage/ServerLevelData;Lnet/minecraft/resources/ResourceKey;"
						+ "Lnet/minecraft/world/level/dimension/LevelStem;Lnet/minecraft/server/level/progress/ChunkProgressListener;"
						+ "ZJLjava/util/List;ZLnet/minecraft/world/RandomSequences;)V", serverLevel.name, "initCapabilities"));

		ClassNode chunk = parse(transformer.transform("net.minecraft.world.level.chunk.LevelChunk",
				require(reader, "net/minecraft/world/level/chunk/LevelChunk.class"), null));
		int stores = 0, inits = 0;
		for (MethodNode method : chunk.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof org.objectweb.asm.tree.FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
						&& field.name.equals(CAP_PROVIDER)) stores++;
				if (insn instanceof MethodInsnNode call && call.owner.equals(AS_FIELD)
						&& call.name.equals("initInternal")) inits++;
			}
		}
		assertEquals(1, stores, "the final capProvider field survived the merge with nothing writing it");
		assertEquals(1, inits, "and Forge initialises it at the end of the constructor");
	}

	// ---- fixtures

	private static AncestorComposition.Requirement requirement(Function<String, byte[]> reader, String owner) throws IOException {
		for (AncestorComposition.Requirement requirement : manifest(reader)) {
			if (requirement.owner().equals(owner)) return requirement;
		}
		throw new AssertionError("the manifest has no row for " + owner);
	}

	private static List<AncestorComposition.Requirement> manifest(Function<String, byte[]> reader) {
		byte[] bytes = require(reader, MANIFEST);
		List<AncestorComposition.Requirement> rows = new ArrayList<>();
		for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
			if (line.isBlank() || line.startsWith("#")) continue;
			String[] parts = line.split("\t", -1);
			rows.add(new AncestorComposition.Requirement(parts[0], parts[1], parts[2]));
		}
		return rows;
	}

	/**
	 * Compiled 1.21.1 runtime classes first (they are the carriers being fingerprinted), then the merged base, then the
	 * Forge carrier — the same precedence the 26.2 test uses, for the same reason: the merged base is ~200 MB unpacked.
	 */
	private static Function<String, byte[]> reader() throws IOException {
		TestFixtures.requireFiles(Fixture.OPT_IN, "staged 1.21.1 merged base and carrier", MERGED, FORGE);
		TestFixtures.requireCompiledJava(Fixture.OPT_IN, "the 1.21.1 capability provider compiled in this line's own"
				+ " names (build/x11/compile-1211-runtime.sh)", RUNTIME_CLASSES);
		Map<String, byte[]> cache = new ConcurrentHashMap<>();
		return path -> {
			byte[] hit = cache.get(path);
			if (hit != null) return hit;
			byte[] found = read(path, RUNTIME_CLASSES, List.of(MERGED, FORGE));
			if (found != null) cache.put(path, found);
			return found;
		};
	}

	private static byte[] read(String path, Path classes, List<Path> jars) {
		try {
			Path file = classes.resolve(path);
			if (Files.isRegularFile(file)) return Files.readAllBytes(file);
			for (Path jar : jars) {
				try (ZipFile zip = new ZipFile(jar.toFile())) {
					ZipEntry entry = zip.getEntry(path);
					if (entry != null && !entry.isDirectory()) {
						try (InputStream in = zip.getInputStream(entry)) {
							return in.readAllBytes();
						}
					}
				}
			}
			return null;
		} catch (IOException unreadable) {
			throw new java.io.UncheckedIOException(unreadable);
		}
	}

	private static byte[] require(Function<String, byte[]> reader, String path) {
		byte[] bytes = reader.apply(path);
		assertNotNull(bytes, "the 1.21.1 fixture is missing " + path);
		return bytes;
	}

	private static Function<String, byte[]> lying(Function<String, byte[]> truth, Map<String, byte[]> lies) {
		return path -> lies.containsKey(path) ? lies.get(path) : truth.apply(path);
	}

	// ---- assertions

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] write(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static MethodNode declared(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) {
			if (method.name.equals(name) && method.desc.equals(desc)) return method;
		}
		return null;
	}

	private static boolean calls(MethodNode method, String owner, String name) {
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return true;
		}
		return false;
	}

	private static boolean hasConstructorGather(ClassNode node) {
		for (MethodNode ctor : node.methods) {
			if (ctor.name.equals("<init>") && calls(ctor, node.name, "gatherCapabilities")) return true;
		}
		return false;
	}

	private static boolean callsAccessor(ClassNode node, String helper) {
		MethodNode accessor = declared(node, ACCESSOR, ACCESSOR_DESC);
		return accessor != null && calls(accessor, RUNTIME, helper);
	}

	/** Calls to {@code RUNTIME.<helper>} inside one method of the emitted class. */
	private static int count(ClassNode node, String name, String desc, String helper) {
		return count(node, name, desc, RUNTIME, helper);
	}

	private static int count(ClassNode node, String name, String desc, String owner, String callee) {
		MethodNode method = declared(node, name, desc);
		assertNotNull(method, node.name + " has no " + name + desc + " to put a seam on");
		int found = 0;
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(callee)) found++;
		}
		return found;
	}

}
