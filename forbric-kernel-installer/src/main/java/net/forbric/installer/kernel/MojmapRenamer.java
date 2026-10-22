/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.installer.kernel;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Rewrites a jar from obfuscated names to Mojang's (Mojmap) names.
 *
 * <p>This is the step {@code PatchedMcBuilder} was missing. Forge's binpatches are built against the
 * <em>deobfuscated</em> jar, so applying them to the obfuscated one fails the per-class checksum. 26.x hid the gap
 * because it ships unobfuscated, where "obfuscated" and "Mojmap" are the same bytes; 1.21.1 stops at the first patch.
 *
 * <h2>Why ASM's {@code ClassRemapper}</h2>
 *
 * <p>Renaming a class is not a string replace. Internal names appear in {@code this_class}, the superclass and
 * interface list, every field and method descriptor, every generic {@code Signature}, annotation values,
 * {@code InnerClasses}/{@code NestHost}/{@code NestMembers}, {@code LDC} type constants, and the array element types
 * inside stack-map frames. {@code ClassRemapper} covers all of them and derives inner-class simple names from the
 * remapped outer name; a hand-written visitor would get the easy 80% and fail the checksum on the rest.
 *
 * <p>It is driven reflectively on a child {@link URLClassLoader} for the same reason
 * {@link ForgeTool#applyAccessTransformers} is: the installer carries no ASM of its own, and an ASM-based rewriter
 * must use the same ASM the tools do.
 *
 * <h2>The two things {@code ClassRemapper} does not do</h2>
 *
 * <ol>
 *   <li><b>Inherited member names.</b> A reference in a subclass names the member through the subclass
 *       ({@code invokevirtual Sub.a()V} where {@code a} is declared by {@code Super}), but the mapping file records
 *       the rename under the declaring class. {@code ClassRemapper} asks about the owner that is written in the
 *       constant pool, misses, and leaves the obfuscated name. So the member map is first expanded along the
 *       superclass/interface graph read out of the jar itself — this is the same job FART's {@code --lib}
 *       inheritance pass does, and without it the base keeps obfuscated member names and no checksum matches.</li>
 *   <li><b>The {@code SourceFile} attribute.</b> The obfuscated jar carries the literal {@code "SourceFile"} (Mojang
 *       replaces it everywhere) while the deobfuscated jar Forge patched against carries {@code "Foo.java"}, taken
 *       from the {@code sourceFile} annotation in the mapping file. The renamer's delegate is therefore a
 *       {@code ClassNode}, not a {@code ClassWriter}, so that one field can be set before serialization.</li>
 * </ol>
 */
final class MojmapRenamer {

	private MojmapRenamer() {
	}

	static final class Result {
		final int classes, renamedEntries, changedBytes, sourceFixed;

		Result(int classes, int renamedEntries, int changedBytes, int sourceFixed) {
			this.classes = classes;
			this.renamedEntries = renamedEntries;
			this.changedBytes = changedBytes;
			this.sourceFixed = sourceFixed;
		}
	}

	/**
	 * Rename every {@code .class} entry of {@code inJar} into {@code outJar}; every other entry is copied verbatim.
	 *
	 * @param asmClasspath jars providing {@code org.objectweb.asm} and {@code org.objectweb.asm.commons}
	 * @param mappings     the parse of Mojang's {@code client.txt}
	 */
	static Result rename(List<Path> asmClasspath, Path inJar, Path outJar, MojmapMappings.Mappings mappings,
	                     Consumer<String> log) throws IOException {
		URL[] urls = new URL[asmClasspath.size()];
		for (int i = 0; i < asmClasspath.size(); i++) urls[i] = asmClasspath.get(i).toUri().toURL();
		try (URLClassLoader cl = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
			Class<?> readerCls = cl.loadClass("org.objectweb.asm.ClassReader");
			Class<?> writerCls = cl.loadClass("org.objectweb.asm.ClassWriter");
			Class<?> visitorCls = cl.loadClass("org.objectweb.asm.ClassVisitor");
			Class<?> nodeCls = cl.loadClass("org.objectweb.asm.tree.ClassNode");
			Class<?> remapperCls = cl.loadClass("org.objectweb.asm.commons.Remapper");
			Class<?> simpleCls = cl.loadClass("org.objectweb.asm.commons.SimpleRemapper");
			Class<?> classRemapperCls = cl.loadClass("org.objectweb.asm.commons.ClassRemapper");

			Constructor<?> readerOfBytes = readerCls.getConstructor(byte[].class);
			Constructor<?> simpleOfMap = simpleCls.getConstructor(Map.class);
			// ClassRemapper(ClassVisitor, Remapper) is the public overload; the (int, ClassVisitor, Remapper) one
			// is protected and would need setAccessible, which the child loader does not grant for a foreign jar.
			Constructor<?> classRemapper = classRemapperCls.getConstructor(visitorCls, remapperCls);
			Constructor<?> writerCtor = writerCls.getConstructor(int.class);
			Constructor<?> nodeCtor = nodeCls.getConstructor();
			Method readerAccept = readerCls.getMethod("accept", visitorCls, int.class);
			Method nodeAccept = nodeCls.getMethod("accept", visitorCls);
			Method toByteArray = writerCls.getMethod("toByteArray");
			Method getClassName = readerCls.getMethod("getClassName");
			Method getSuperName = readerCls.getMethod("getSuperName");
			Method getInterfaces = readerCls.getMethod("getInterfaces");
			Field sourceFileField = nodeCls.getField("sourceFile");

			LinkedHashMap<String, byte[]> entries = Zips.readAll(inJar);

			// ---- pass 1: read the hierarchy so inherited member names can be resolved ----
			Map<String, List<String>> parents = new HashMap<>();
			int classCount = 0;
			for (Map.Entry<String, byte[]> e : entries.entrySet()) {
				if (!e.getKey().endsWith(".class")) continue;
				classCount++;
				Object reader = readerOfBytes.newInstance((Object) e.getValue());
				List<String> p = new ArrayList<>();
				String superName = (String) getSuperName.invoke(reader);
				if (superName != null) p.add(superName);
				Collections.addAll(p, (String[]) getInterfaces.invoke(reader));
				parents.put((String) getClassName.invoke(reader), p);
			}

			// ---- expand the member map along the hierarchy ----
			Map<String, Map<String, String>> byOwner = new HashMap<>();
			for (Map.Entry<String, String> e : mappings.members.entrySet()) {
				String key = e.getKey();
				int dot = key.indexOf('.');
				if (dot < 0) continue;
				byOwner.computeIfAbsent(key.substring(0, dot), k -> new HashMap<>())
						.put(key.substring(dot + 1), e.getValue());
			}
			Map<String, String> keys = new HashMap<>(mappings.classNames);
			keys.putAll(mappings.members);
			int expanded = 0;
			Deque<String> queue = new ArrayDeque<>();
			Set<String> seen = new HashSet<>();
			for (String cls : parents.keySet()) {
				queue.clear();
				seen.clear();
				queue.addAll(parents.get(cls));
				while (!queue.isEmpty()) {
					String ancestor = queue.poll();
					if (!seen.add(ancestor)) continue;
					Map<String, String> inherited = byOwner.get(ancestor);
					if (inherited != null) {
						for (Map.Entry<String, String> e : inherited.entrySet()) {
							if (keys.putIfAbsent(cls + "." + e.getKey(), e.getValue()) == null) expanded++;
						}
					}
					List<String> grand = parents.get(ancestor);
					if (grand != null) queue.addAll(grand);
				}
			}
			if (expanded > 0) {
				log.accept("[patched] resolved " + expanded + " inherited member name(s) through the class hierarchy");
			}

			Object remapper = simpleOfMap.newInstance(new KeyedMap(keys));

			// ---- pass 2: rewrite ----
			LinkedHashMap<String, byte[]> out = new LinkedHashMap<>();
			Set<String> written = new HashSet<>();
			int renamedEntries = 0, changedBytes = 0, sourceFixed = 0;
			for (Map.Entry<String, byte[]> e : entries.entrySet()) {
				String name = e.getKey();
				if (!name.endsWith(".class")) {
					out.put(name, e.getValue());
					continue;
				}
				String internal = name.substring(0, name.length() - 6);
				Object node = nodeCtor.newInstance();
				Object reader = readerOfBytes.newInstance((Object) e.getValue());
				readerAccept.invoke(reader, classRemapper.newInstance(node, remapper), 0);

				String renamed = mappings.classNames.getOrDefault(internal, internal);
				String sourceFile = mappings.sourceFiles.get(internal);
				if (sourceFile != null) {
					sourceFileField.set(node, sourceFile);
					sourceFixed++;
				}
				Object writer = writerCtor.newInstance(0);
				nodeAccept.invoke(node, writer);
				byte[] bytes = (byte[]) toByteArray.invoke(writer);

				String outName = renamed + ".class";
				if (!written.add(outName)) throw new IOException("two classes would be renamed to " + outName);
				out.put(outName, bytes);
				if (!renamed.equals(internal)) renamedEntries++;
				if (!java.util.Arrays.equals(bytes, e.getValue())) changedBytes++;
			}
			Zips.writeJar(outJar, out);
			return new Result(classCount, renamedEntries, changedBytes, sourceFixed);
		} catch (IOException e) {
			throw e;
		} catch (ReflectiveOperationException e) {
			throw new IOException("deobfuscation step failed: " + e, e);
		}
	}

	/**
	 * The {@code Map} {@code SimpleRemapper} reads. It has to answer two different questions with one signature, so
	 * the key shape is what tells them apart:
	 * <ul>
	 *   <li>no {@code '.'} — a class internal name. Answer the mapping, or the name itself when unmapped, because
	 *       {@code SimpleRemapper.map} returning {@code null} propagates into {@code mapType} and would put a
	 *       literal {@code null} in the rewritten constant pool.</li>
	 *   <li>contains {@code '.'} — a member key ({@code owner.name+descriptor}), an annotation attribute key
	 *       ({@code descriptor.name}) or an invokedynamic name key ({@code .name+descriptor}). {@code null} there
	 *       means "no rename", which each of {@code SimpleRemapper}'s overrides turns back into the original name.</li>
	 * </ul>
	 * {@link MojmapMappings#parse} rejects a dotted class name, so the discrimination cannot be fooled.
	 */
	private static final class KeyedMap extends java.util.AbstractMap<String, String> {
		private final Map<String, String> keys;

		KeyedMap(Map<String, String> keys) {
			this.keys = keys;
		}

		@Override
		public String get(Object key) {
			if (!(key instanceof String)) return null;
			String k = (String) key;
			String mapped = keys.get(k);
			if (mapped != null) return mapped;
			return k.indexOf('.') >= 0 ? null : k;
		}

		@Override
		public Set<Entry<String, String>> entrySet() {
			return Collections.emptySet();
		}
	}
}
