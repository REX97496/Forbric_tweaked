package net.forbric.nsremap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.RecordComponentNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Remaps a set of fabric-api module jars from Fabric's intermediary namespace to Mojang's official names
 * (Mojmap), so that Mojmap-written sources can compile against them.
 *
 * <p>WHY THIS EXISTS. fabric-api publishes its modules intermediary-remapped: every Minecraft signature in them
 * names {@code net/minecraft/class_1799}, which does not exist in a Mojmap Minecraft jar. A Mojmap source set
 * therefore cannot read any fabric signature that mentions a Minecraft type — measured as 8 "cannot access
 * class_XXXX / class file for ... not found" errors plus the 3 ContainerStorage ones in the 1.21.1 kernel.
 * Renaming the type in the SOURCE only moves those errors, so the modules themselves are rewritten here.
 *
 * <p>THREE THINGS THIS DOES THAT A NAIVE {@code SimpleRemapper} DOES NOT:
 * <ul>
 *   <li>It walks the class hierarchy. A call site's owner is the static receiver type, not the declaring class
 *       ({@code class_1657.method_23317} is declared on {@code class_1297}), and a mixin that extends a
 *       Minecraft class must have its own overriding declarations renamed as well — 38 of 244 references in
 *       these modules resolve only through a supertype.</li>
 *   <li>It renames jar ENTRY names, not just the bytes, and inner classes come along because the remap is a
 *       name function: {@code class_2350$class_2351} becomes {@code Direction$Axis} in both places.</li>
 *   <li>It VERIFIES the result against a real Mojmap jar, and fails when a name does not resolve. A remapped
 *       jar that silently keeps one wrong name is indistinguishable from a correct one until something
 *       downstream dies on it.</li>
 * </ul>
 *
 * <p>WHAT IT DOES NOT REWRITE, and what therefore must not be relied on: string-valued mixin selectors
 * ({@code @Inject(method = "...")}, {@code @At(target = "...")}, {@code @Shadow} field names). fabric-api
 * writes those in yarn names and translates them at runtime through a refmap. They are inert for javac, and
 * these jars are compile-time only — nothing here is ever loaded into a game.
 */
public final class NamespaceRemapper {

    private static final PrintStream OUT = System.out;

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && args[0].equals("--selftest")) {
            selfTest();
            return;
        }
        Path table = null, base = null, in = null, out = null, report = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--table" -> table = Path.of(args[++i]);
                case "--base" -> base = Path.of(args[++i]);
                case "--in" -> in = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--report" -> report = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }
        if (table == null || base == null || in == null || out == null) {
            throw new IllegalArgumentException("usage: --table <tsv> --base <mojmap jar> --in <dir> --out <dir> "
                    + "[--report <txt>]");
        }

        Mapping mapping = Mapping.read(table);
        OUT.printf("[nsremap] table %s: C=%d M=%d F=%d%n", table.getFileName(), mapping.classes.size(),
                mapping.methods.size(), mapping.fields.size());

        BaseIndex baseIndex = BaseIndex.read(base);
        OUT.printf("[nsremap] base %s: %d classes%n", base.getFileName(), baseIndex.classes.size());

        List<Path> jars = new ArrayList<>();
        try (var stream = Files.list(in)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().forEach(jars::add);
        }
        if (jars.isEmpty()) {
            throw new IllegalStateException("no .jar under " + in);
        }

        // The hierarchy the remapper walks: Minecraft's, re-expressed in intermediary names by inverting the
        // class map, PLUS each module's own classes -- a mixin that extends a Minecraft class is the only way an
        // intermediary supertype is reachable from a fabric-owned declaration.
        Map<String, List<String>> parents = mapping.hierarchy(baseIndex);
        int linked = 0;
        for (Path jar : jars) {
            for (Map.Entry<String, String> e : readSupers(jar).entrySet()) {
                String sup = e.getValue();
                if (sup != null && !parents.containsKey(e.getKey())) {
                    parents.put(e.getKey(), List.of(sup));
                    linked++;
                }
            }
        }
        OUT.printf("[nsremap] hierarchy: %d intermediary entries + %d module classes%n",
                mapping.classes.size(), linked);

        Files.createDirectories(out);
        Manifest manifest = new Manifest();
        for (Path jar : jars) {
            remapJar(jar, out.resolve(jar.getFileName()), mapping, parents, manifest);
        }

        // Verify the OUTPUT, not the input: what matters is that the bytes we just wrote name things that
        // exist in the Mojmap jar.
        Verification verification = new Verification(baseIndex);
        for (Path jar : jars) {
            verification.scan(out.resolve(jar.getFileName()));
        }
        verification.print();

        if (report != null) {
            Files.createDirectories(report.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("nsremap report\n");
            sb.append("table ").append(table).append('\n');
            sb.append("base  ").append(base).append('\n');
            sb.append("in    ").append(in).append('\n');
            sb.append("out   ").append(out).append('\n');
            sb.append(manifest.describe());
            sb.append(verification.describe());
            Files.writeString(report, sb.toString(), StandardCharsets.UTF_8);
            OUT.println("[nsremap] report -> " + report);
        }
        if (!verification.unresolved.isEmpty()) {
            throw new IllegalStateException(verification.unresolved.size()
                    + " reference(s) in the remapped jars do not resolve in " + base + "; first: "
                    + verification.unresolved.get(0));
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // the table
    // ---------------------------------------------------------------------------------------------------

    record Mapping(Map<String, String> classes, Map<String, String> methods, Map<String, String> fields) {

        static Mapping read(Path path) throws IOException {
            Map<String, String> classes = new HashMap<>();
            Map<String, String> methods = new HashMap<>();
            Map<String, String> fields = new HashMap<>();
            for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] f = raw.split("\t");
                switch (f[0]) {
                    case "C" -> classes.put(f[1], f[2]);
                    case "M" -> methods.put(f[1] + '|' + f[2], f[3]);
                    case "F" -> fields.put(f[1] + '|' + f[2], f[3]);
                    default -> throw new IllegalStateException(path + ": unknown record type " + f[0]);
                }
            }
            return new Mapping(Map.copyOf(classes), Map.copyOf(methods), Map.copyOf(fields));
        }

        /** Intermediary hierarchy: the base's Mojmap headers, inverted through the class map. */
        Map<String, List<String>> hierarchy(BaseIndex base) {
            Map<String, String> inverse = new HashMap<>();
            classes.forEach((k, v) -> inverse.put(v, k));
            Map<String, List<String>> parents = new HashMap<>();
            for (String intermediary : classes.keySet()) {
                BaseIndex.ClassInfo info = base.classes.get(classes.get(intermediary));
                if (info == null) {
                    continue;
                }
                List<String> supers = new ArrayList<>();
                if (info.superName != null) {
                    supers.add(inverse.getOrDefault(info.superName, info.superName));
                }
                for (String itf : info.interfaces) {
                    supers.add(inverse.getOrDefault(itf, itf));
                }
                parents.put(intermediary, List.copyOf(supers));
            }
            return parents;
        }
    }

    /** Remapper over the table, with hierarchy resolution. */
    static final class HierarchyRemapper extends Remapper {
        private final Mapping mapping;
        private final Map<String, List<String>> parents;

        HierarchyRemapper(Mapping mapping, Map<String, List<String>> parents) {
            this.mapping = mapping;
            this.parents = parents;
        }

        @Override
        public String map(String internalName) {
            return mapping.classes.getOrDefault(internalName, internalName);
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            return resolve(owner, name, mapping.methods);
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            return resolve(owner, name, mapping.fields);
        }

        private String resolve(String owner, String name, Map<String, String> table) {
            // `<init>`/`<clinit>` and synthetic members are never renamed by the mapping -- they are not in it.
            List<String> frontier = List.of(owner);
            Set<String> seen = new HashSet<>();
            while (!frontier.isEmpty()) {
                List<String> next = new ArrayList<>();
                for (String c : frontier) {
                    if (!seen.add(c)) {
                        continue;
                    }
                    String hit = table.get(c + '|' + name);
                    if (hit != null) {
                        return hit;
                    }
                    next.addAll(parents.getOrDefault(c, List.of()));
                }
                frontier = next;
            }
            return name;
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // remapping one jar
    // ---------------------------------------------------------------------------------------------------

    static final class Manifest {
        final Map<String, int[]> perJar = new TreeMap<>();   // jar -> [entries, classes, renamedEntries, dropped]

        void record(String jar, int entries, int classes, int renamed, int dropped) {
            perJar.put(jar, new int[]{entries, classes, renamed, dropped});
        }

        String describe() {
            StringBuilder sb = new StringBuilder();
            perJar.forEach((jar, v) -> sb.append(String.format("jar %-46s entries=%-4d classes=%-4d "
                    + "entriesRenamed=%-4d signaturesDropped=%d%n", jar, v[0], v[1], v[2], v[3])));
            return sb.toString();
        }
    }

    private static void remapJar(Path jar, Path target, Mapping mapping, Map<String, List<String>> parents,
                                 Manifest manifest) throws IOException {
        HierarchyRemapper remapper = new HierarchyRemapper(mapping, parents);
        int entries = 0, classes = 0, renamed = 0, dropped = 0;
        try (ZipFile zip = new ZipFile(jar.toFile());
             OutputStream fos = Files.newOutputStream(target);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            List<? extends ZipEntry> list = zip.stream().toList();
            Set<String> written = new LinkedHashSet<>();
            for (ZipEntry entry : list) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                entries++;
                if (isSignature(name)) {
                    // A signature over bytes that no longer match them makes the jar unusable if it is ever
                    // loaded with verification on. Dropping it is the only honest thing to do with it.
                    dropped++;
                    continue;
                }
                byte[] bytes;
                String outName = name;
                try (InputStream is = zip.getInputStream(entry)) {
                    bytes = is.readAllBytes();
                }
                if (name.endsWith(".class")) {
                    ClassReader reader = new ClassReader(bytes);
                    String internalName = reader.getClassName();
                    ClassWriter writer = new ClassWriter(0);
                    reader.accept(new ClassRemapper(writer, remapper), 0);
                    bytes = writer.toByteArray();
                    String mapped = remapper.map(internalName);
                    if (!mapped.equals(internalName)) {
                        renamed++;
                    }
                    outName = mapped + ".class";
                    classes++;
                }
                if (!written.add(outName)) {
                    throw new IllegalStateException(jar.getFileName() + ": two entries map to " + outName);
                }
                ZipEntry out = new ZipEntry(outName);
                // Fixed timestamps: two runs over the same inputs must produce byte-identical jars, or the
                // build cannot tell "the inputs changed" from "the clock moved".
                out.setTime(0L);
                zos.putNextEntry(out);
                zos.write(bytes);
                zos.closeEntry();
            }
        }
        manifest.record(jar.getFileName().toString(), entries, classes, renamed, dropped);
        OUT.printf("[nsremap] %-46s entries=%-4d classes=%-4d entriesRenamed=%-4d signaturesDropped=%d%n",
                jar.getFileName(), entries, classes, renamed, dropped);
    }

    private static boolean isSignature(String name) {
        return name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA")
                || name.endsWith(".EC"));
    }

    private static Map<String, String> readSupers(Path jar) throws IOException {
        Map<String, String> out = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : zip.stream().toList()) {
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream is = zip.getInputStream(entry)) {
                    ClassReader reader = new ClassReader(is.readAllBytes());
                    out.put(reader.getClassName(), reader.getSuperName());
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------
    // the base jar, as a resolver
    // ---------------------------------------------------------------------------------------------------

    static final class BaseIndex {

        record ClassInfo(String name, String superName, List<String> interfaces, Set<String> methods,
                         Set<String> fields) {
        }

        final Map<String, ClassInfo> classes = new HashMap<>();

        static BaseIndex read(Path jar) throws IOException {
            BaseIndex index = new BaseIndex();
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                for (ZipEntry entry : zip.stream().toList()) {
                    if (!entry.getName().endsWith(".class")) {
                        continue;
                    }
                    ClassNode node = new ClassNode();
                    try (InputStream is = zip.getInputStream(entry)) {
                        new ClassReader(is.readAllBytes()).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG
                                | ClassReader.SKIP_FRAMES);
                    }
                    Set<String> methods = new HashSet<>();
                    for (MethodNode m : node.methods) {
                        methods.add(m.name + m.desc);
                    }
                    Set<String> fields = new HashSet<>();
                    for (FieldNode f : node.fields) {
                        fields.add(f.name + ' ' + f.desc);
                    }
                    index.classes.put(node.name, new ClassInfo(node.name, node.superName,
                            node.interfaces == null ? List.of() : List.copyOf(node.interfaces), methods, fields));
                }
            }
            return index;
        }

        boolean hasClass(String name) {
            return classes.containsKey(name);
        }

        /** Three-valued on purpose: "the walk left the index" is not the same answer as "the member is absent". */
        enum Found { YES, NO, UNKNOWN }

        /**
         * Does (owner, name, descriptor) exist on owner or any of its supertypes?
         *
         * <p>NO means every class the walk could reach was indexed and none declared it. UNKNOWN means the walk
         * ran into a class the base does not contain -- {@code java/lang/Object}, from which every class
         * inherits {@code toString}, {@code equals} and {@code hashCode}. Reporting UNKNOWN as NO would fail the
         * build on a correct remap; reporting it as YES would hide a genuinely wrong name.
         */
        Found hasMember(String owner, String name, String descriptor, boolean method) {
            List<String> frontier = List.of(owner);
            Set<String> seen = new HashSet<>();
            boolean leftIndex = false;
            while (!frontier.isEmpty()) {
                List<String> next = new ArrayList<>();
                for (String c : frontier) {
                    if (!seen.add(c)) {
                        continue;
                    }
                    ClassInfo info = classes.get(c);
                    if (info == null) {
                        leftIndex = true;
                        continue;
                    }
                    if (method ? info.methods.contains(name + descriptor) : info.fields.contains(name + ' ' + descriptor)) {
                        return Found.YES;
                    }
                    if (info.superName != null) {
                        next.add(info.superName);
                    }
                    next.addAll(info.interfaces);
                }
                frontier = next;
            }
            return leftIndex ? Found.UNKNOWN : Found.NO;
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // verification
    // ---------------------------------------------------------------------------------------------------

    static final class Verification {
        private static final String MC = "net/minecraft/";

        final BaseIndex base;
        final Set<String> remappedClasses = new HashSet<>();
        final Map<String, Integer> kinds = new TreeMap<>();
        final List<String> unresolved = new ArrayList<>();
        final Map<String, String> unresolvedSamples = new LinkedHashMap<>();
        final Set<String> externalOwners = new TreeSet<>();
        final Set<String> uncheckedWhy = new TreeSet<>();
        int unchecked;

        Verification(BaseIndex base) {
            this.base = base;
        }

        void scan(Path jar) throws IOException {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                for (ZipEntry entry : zip.stream().toList()) {
                    if (!entry.getName().endsWith(".class")) {
                        continue;
                    }
                    ClassNode node = new ClassNode();
                    try (InputStream is = zip.getInputStream(entry)) {
                        // SKIP_CODE is not enough: the references ARE in the code. Read everything.
                        new ClassReader(is.readAllBytes()).accept(node, 0);
                    }
                    remappedClasses.add(node.name);
                }
            }
            // A second pass, now that every class of every jar is known: an owner that is a module class
            // resolves within the set, and only Minecraft owners are checked against the base.
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                for (ZipEntry entry : zip.stream().toList()) {
                    if (!entry.getName().endsWith(".class")) {
                        continue;
                    }
                    ClassNode node = new ClassNode();
                    try (InputStream is = zip.getInputStream(entry)) {
                        new ClassReader(is.readAllBytes()).accept(node, 0);
                    }
                    check(node);
                }
            }
        }

        private void check(ClassNode node) {
            type(node.name);
            type(node.superName);
            node.interfaces.forEach(this::type);
            typeSig(node.signature);
            for (FieldNode f : node.fields) {
                type(f.desc);
                typeSig(f.signature);
                annotations(f.visibleAnnotations, f.invisibleAnnotations);
            }
            if (node.recordComponents != null) {
                for (RecordComponentNode r : node.recordComponents) {
                    type(r.descriptor);
                    typeSig(r.signature);
                }
            }
            if (node.innerClasses != null) {
                for (InnerClassNode ic : node.innerClasses) {
                    type(ic.name);
                    type(ic.outerName);
                }
            }
            type(node.outerClass);
            if (node.nestHostClass != null) {
                type(node.nestHostClass);
            }
            if (node.nestMembers != null) {
                node.nestMembers.forEach(this::type);
            }
            if (node.permittedSubclasses != null) {
                node.permittedSubclasses.forEach(this::type);
            }
            annotations(node.visibleAnnotations, node.invisibleAnnotations);
            for (MethodNode m : node.methods) {
                typeSig(m.signature);
                if (m.exceptions != null) {
                    m.exceptions.forEach(this::type);
                }
                annotations(m.visibleAnnotations, m.invisibleAnnotations);
                InsnList code = m.instructions;
                if (code == null) {
                    continue;
                }
                for (int i = 0; i < code.size(); i++) {
                    var insn = code.get(i);
                    if (insn instanceof FieldInsnNode f) {
                        member(f.owner, f.name, f.desc, false);
                    } else if (insn instanceof MethodInsnNode mi) {
                        member(mi.owner, mi.name, mi.desc, true);
                    } else if (insn instanceof TypeInsnNode t) {
                        type(t.desc);
                    } else if (insn instanceof MultiANewArrayInsnNode ma) {
                        type(ma.desc);
                    } else if (insn instanceof LdcInsnNode ldc) {
                        if (ldc.cst instanceof Type t) {
                            type(t.getSort() == Type.METHOD ? t.getDescriptor() : t.getInternalName());
                        }
                    } else if (insn instanceof InvokeDynamicInsnNode indy) {
                        type(indy.desc);
                        if (indy.bsmArgs != null) {
                            for (Object arg : indy.bsmArgs) {
                                if (arg instanceof Type t) {
                                    type(t.getSort() == Type.METHOD ? t.getDescriptor() : t.getInternalName());
                                }
                            }
                        }
                    }
                }
            }
        }

        private void annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
            for (List<AnnotationNode> list : List.of(visible == null ? List.<AnnotationNode>of() : visible,
                    invisible == null ? List.<AnnotationNode>of() : invisible)) {
                for (AnnotationNode a : list) {
                    typeSig(a.desc);
                }
            }
        }

        private void typeSig(String signature) {
            if (signature == null) {
                return;
            }
            // Signatures are not descriptors: a generic argument is `Lpkg/Class<...>;`, so the name runs until
            // one of `;<>`.
            int i = 0;
            while ((i = signature.indexOf('L', i)) >= 0) {
                int j = i + 1;
                while (j < signature.length() && ";<>".indexOf(signature.charAt(j)) < 0) {
                    j++;
                }
                type(signature.substring(i + 1, j));
                i = j;
            }
        }

        private void type(String internalName) {
            if (internalName == null) {
                return;
            }
            if (internalName.startsWith("[")) {
                typeSig(internalName);
                return;
            }
            if (!internalName.startsWith(MC)) {
                return;
            }
            kinds.merge("class", 1, Integer::sum);
            if (!base.hasClass(internalName)) {
                unresolved.add("class " + internalName);
                unresolvedSamples.putIfAbsent("class " + internalName, internalName);
            }
        }

        private void member(String owner, String name, String descriptor, boolean method) {
            if (owner == null || !owner.startsWith(MC)) {
                if (owner != null && !remappedClasses.contains(owner)) {
                    externalOwners.add(owner);
                }
                return;
            }
            kinds.merge(method ? "method" : "field", 1, Integer::sum);
            switch (base.hasMember(owner, name, descriptor, method)) {
                case YES -> {
                }
                case UNKNOWN -> {
                    unchecked++;
                    uncheckedWhy.add(owner);
                }
                case NO -> {
                    String key = (method ? "method " : "field ") + owner + '.' + name + descriptor;
                    unresolved.add(key);
                    unresolvedSamples.putIfAbsent(key, key);
                }
            }
        }

        void print() {
            OUT.printf("[nsremap] verify: %d classes, %s%n", remappedClasses.size(), kinds);
            OUT.printf("[nsremap] verify: unresolvable Minecraft references: %d%n", unresolved.size());
            if (!unresolved.isEmpty()) {
                unresolved.stream().limit(20).forEach(u -> OUT.println("    " + u));
            }
            OUT.printf("[nsremap] verify: %d member(s) unchecked (their owner chain leaves the base, i.e. "
                    + "inherited from a JDK class): %s%n", unchecked, uncheckedWhy.stream().limit(4).toList());
            if (!externalOwners.isEmpty()) {
                OUT.printf("[nsremap] verify: %d non-Minecraft owner(s) outside the module set, not checked "
                        + "against the base: %s%n", externalOwners.size(),
                        externalOwners.stream().limit(6).toList());
            }
        }

        String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("classes=%d references=%s unresolvable=%d uncheckedInherited=%d%n",
                    remappedClasses.size(), kinds, unresolved.size(), unchecked));
            unresolved.stream().limit(200).forEach(u -> sb.append("unresolved ").append(u).append('\n'));
            return sb.toString();
        }
    }

    private NamespaceRemapper() {
    }

    /**
     * Proves the ENTRY-RENAME path, which the real run does not exercise: not one class in the three fabric-api
     * modules is named after a Minecraft class, so every real entry name is unchanged and "we rename entries"
     * would otherwise be an untested claim. This builds a jar holding a Minecraft-named class and its inner
     * class, remaps it, and asserts both the entry names and the bytes moved.
     */
    private static void selfTest() throws IOException {
        Map<String, String> classes = Map.of(
                "net/minecraft/class_1799", "net/minecraft/world/item/ItemStack",
                "net/minecraft/class_1799$class_1", "net/minecraft/world/item/ItemStack$1",
                "net/minecraft/class_2350", "net/minecraft/core/Direction");
        Mapping mapping = new Mapping(classes,
                Map.of("net/minecraft/class_1799|method_7960", "copy",
                        "net/minecraft/class_1799|method_7956", "getMaxStackSize"), Map.of());
        Map<String, List<String>> parents = Map.of("net/minecraft/class_1799$class_1",
                List.of("net/minecraft/class_1799"));

        Path tmp = Files.createTempDirectory("nsremap-selftest");
        Path inDir = Files.createDirectories(tmp.resolve("in"));
        Path outDir = Files.createDirectories(tmp.resolve("out"));
        Path jar = inDir.resolve("selftest.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            putClass(zos, "net/minecraft/class_1799", simple(zos, false));
            putClass(zos, "net/minecraft/class_1799$class_1", simple(zos, true));
        }

        remapJar(jar, outDir.resolve("selftest.jar"), mapping, parents, new Manifest());

        Set<String> entries = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(outDir.resolve("selftest.jar").toFile())) {
            zip.stream().forEach(e -> entries.add(e.getName()));
            ClassNode node = new ClassNode();
            try (InputStream is = zip.getInputStream(zip.getEntry("net/minecraft/world/item/ItemStack.class"))) {
                new ClassReader(is.readAllBytes()).accept(node, 0);
            }
            List<String> problems = new ArrayList<>();
            if (!node.name.equals("net/minecraft/world/item/ItemStack")) {
                problems.add("class name in bytes is " + node.name);
            }
            if (!entries.contains("net/minecraft/world/item/ItemStack$1.class")) {
                problems.add("inner class entry not renamed: " + entries);
            }
            if (node.fields.isEmpty() || !node.fields.get(0).desc.equals("Lnet/minecraft/core/Direction;")) {
                problems.add("field descriptor not remapped: " + node.fields.get(0).desc);
            }
            Set<String> methodNames = new TreeSet<>();
            node.methods.forEach(m -> methodNames.add(m.name));
            if (!methodNames.contains("copy") || !methodNames.contains("getMaxStackSize")) {
                problems.add("method names not remapped: " + methodNames);
            }
            if (!problems.isEmpty()) {
                throw new IllegalStateException("SELFTEST FAIL: " + problems);
            }
        }
        OUT.printf("[nsremap] selftest PASS: entries %s%n", entries);
    }

    private static void putClass(ZipOutputStream zos, String name, byte[] bytes) throws IOException {
        zos.putNextEntry(new ZipEntry(name + ".class"));
        zos.write(bytes);
        zos.closeEntry();
    }

    /** A class that names a Minecraft class in its own name, super, field descriptor and two methods. */
    private static byte[] simple(ZipOutputStream unused, boolean inner) {
        ClassWriter cw = new ClassWriter(0);
        String superName = inner ? "net/minecraft/class_1799" : "java/lang/Object";
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, inner ? "net/minecraft/class_1799$class_1"
                : "net/minecraft/class_1799", null, superName, null);
        if (!inner) {
            cw.visitField(Opcodes.ACC_PUBLIC, "field_1", "Lnet/minecraft/class_2350;", null, null).visitEnd();
            cw.visitMethod(Opcodes.ACC_PUBLIC, "method_7960", "()Lnet/minecraft/class_1799;", null, null).visitEnd();
            cw.visitMethod(Opcodes.ACC_PUBLIC, "method_7956", "()I", null, null).visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
