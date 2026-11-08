package net.forbric.kernel.remap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.kernel.fabric.KernelMappingResolver;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The access wideners a Fabric guest ships, applied to the classes it asks us to widen.
 *
 * <p>Fabric mods do not {@code @Shadow} members of classes they do not target — they ship an
 * {@code accessWidener} file and rely on the loader widening the named member before the first read. Without this
 * layer the guest's injected code compiles to a direct {@code GETFIELD} and dies with IllegalAccessError the first
 * time it runs, which is what happened to {@code fabric-resource-loader-v0}: its handler read
 * {@code FolderRepositorySource.packSource}, a private field, and the client stopped while
 * {@code PackRepository.<init>} was still running.
 *
 * <p>Only the {@code accessible} op is honoured: that is the only one the jars in hand use, and widening the rest
 * would claim more than this has measured.
 */
public final class AccessWideners {

	private static final Pattern DECLARED = Pattern.compile("\"accessWidener\"\\s*:\\s*\"([^\"]+)\"");
	private static final List<Rule> RULES = new ArrayList<>();

	private AccessWideners() {
	}

	/** Where one rule points after the guest's namespace has been carried onto this line's names. */
	private record Rule(String owner, String name, boolean method) {
	}

	/**
	 * Reads every guest jar's {@code fabric.mod.json}, follows the access widener it names, and keeps the rules this
	 * layer can apply. Returns how many rules were kept.
	 */
	public static synchronized int install(List<Path> jars) {
		RULES.clear();
		for (Path jar : jars) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				String declared = declaredWidener(zip);
				if (declared == null) continue;
				ZipEntry entry = zip.getEntry(declared);
				if (entry == null) continue;
				String text;
				try (InputStream in = zip.getInputStream(entry)) {
					text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
				}
				parse(text, jar.getFileName().toString());
			} catch (IOException | RuntimeException unreadable) {
				ForbricLog.debug("[Forbric/AW] " + jar.getFileName() + " could not be read: " + unreadable);
			}
		}
		for (int i = 0; i < RULES.size() && i < 8; i++) ForbricLog.info("[Forbric/AW] rule " + RULES.get(i));
		if (!RULES.isEmpty()) {
			ForbricLog.info("[Forbric/AW] " + RULES.size() + " member(s) are opened for the guests that declared them,"
					+ " so an injected read of a private vanilla field is not an IllegalAccessError at runtime");
		}
		return RULES.size();
	}

	private static String declaredWidener(ZipFile zip) throws IOException {
		ZipEntry manifest = zip.getEntry("fabric.mod.json");
		if (manifest == null) return null;
		String text;
		try (InputStream in = zip.getInputStream(manifest)) {
			text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		Matcher matcher = DECLARED.matcher(text);
		return matcher.find() ? matcher.group(1) : null;
	}

	private static void parse(String text, String from) {
		String namespace = "intermediary";
		for (String raw : text.split("\\R")) {
			String line = raw.trim();
			if (line.isEmpty() || line.startsWith("#")) continue;
			String[] parts = line.split("\\s+");
			if (parts[0].equals("accessWidener")) {
				if (parts.length >= 3) namespace = parts[2];
				continue;
			}
			if (!parts[0].equals("accessible")) continue;
			try {
				if (parts.length == 3 && parts[1].equals("class")) {
					RULES.add(new Rule(internal(namespace, parts[2]), null, false));
				} else if (parts.length >= 4 && parts[1].equals("field")) {
					// The member lookup is keyed by the SOURCE owner (the mapping table stores owner#name with the
					// intermediary owner), so parts[2] goes in — mapping it first looked up an official owner that
					// no row uses and quietly kept the intermediary name, which matched nothing at load time.
					String owner = internal(namespace, parts[2]);
					RULES.add(new Rule(owner, member(namespace, parts[2], parts[3], false), false));
				} else if (parts.length >= 4 && parts[1].equals("method")) {
					String owner = internal(namespace, parts[2]);
					String name = member(namespace, parts[2], parts[3], true);
					if (name.startsWith("<")) continue;
					RULES.add(new Rule(owner, name, true));
				}
			} catch (RuntimeException unresolved) {
				ForbricLog.debug("[Forbric/AW] " + from + " names " + line + ", which this line cannot resolve: "
						+ unresolved);
			}
		}
	}

	private static String internal(String namespace, String owner) {
		String mapped = KernelMappingResolver.INSTANCE.mapClassName(namespace, owner);
		if (mapped == null || mapped.isEmpty()) return owner;
		return mapped.indexOf('.') < 0 ? mapped : mapped.replace('.', '/');
	}

	private static String member(String namespace, String owner, String name, boolean method) {
		String mapped = method
				? KernelMappingResolver.INSTANCE.mapMethodName(namespace, owner, name, "")
				: KernelMappingResolver.INSTANCE.mapFieldName(namespace, owner, name, "");
		return mapped == null || mapped.isEmpty() ? name : mapped;
	}

	/**
	 * Widens the members {@code node} declares that a guest asked to reach. Returns whether anything changed.
	 */
	public static boolean widen(ClassNode node) {
		if (RULES.isEmpty()) return false;
		boolean changed = false;
		for (Rule rule : RULES) {
			if (!rule.owner().equals(node.name)) continue;
			if (rule.name() == null) {
				node.access = opened(node.access);
				changed = true;
				continue;
			}
			if (rule.method()) {
				for (MethodNode method : node.methods) {
					if (!method.name.equals(rule.name())) continue;
					method.access = opened(method.access);
					changed = true;
				}
			} else {
				for (FieldNode field : node.fields) {
					if (!field.name.equals(rule.name())) continue;
					field.access = opened(field.access);
					changed = true;
				}
			}
		}
		return changed;
	}

	private static int opened(int access) {
		return (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
	}
}
