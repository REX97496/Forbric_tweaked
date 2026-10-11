/*
 * Copyright 2016 FabricMC
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

package net.fabricmc.loader.api;

/**
 * Represents an exception that arises when obtaining entrypoints.
 *
 * <p>Reproduced from Fabric Loader (Apache-2.0, Copyright FabricMC) because it is part of the mod-facing ABI:
 * a mod that writes {@code catch (EntrypointException e)} around
 * {@link FabricLoader#getEntrypointContainers(String, Class)} has that type in its constant pool, and the JVM
 * resolves it when the method is verified — so an ABSENT class is a {@code NoClassDefFoundError} in a mod that
 * never called anything unusual.
 *
 * <h2>Why this class was missing, and why that mattered more than it looks</h2>
 *
 * <p>The kernel reproduces the Fabric Loader API surface for mods that compile against it, and this one class was
 * not among the ones it reproduced — 9 of the 10 types in this package were here. Nothing in the kernel referenced
 * it, so nothing failed: the omission only shows up in a mod that catches it, which is exactly what a mod doing
 * entrypoint work does, and JEI is one.
 *
 * <p>It used to be survivable by accident. A first-generation jar on the launch list carried a copy of this class,
 * so the search for it found something and the mod started. That jar is gone — the first-generation directory was
 * removed — which turned a missing API class from a cosmetic gap that a stray jar papered over into a hard failure
 * with no fallback. A dependency on a class that happened to be lying around in an obsolete artifact was never a
 * thing to rely on, and now it cannot be.
 *
 * <p>Every constructor is deprecated upstream; they are kept because a mod may construct one, and a missing
 * constructor is the same linkage failure as a missing class.
 */
public class EntrypointException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	private final String key;

	/**
	 * @deprecated For internal use only, to be removed!
	 */
	@Deprecated
	public EntrypointException(String key, Throwable cause) {
		super("Exception while loading entrypoint \"" + key + "\"", cause);
		this.key = key;
	}

	/**
	 * @deprecated For internal use only, use regular exceptions!
	 */
	@Deprecated
	public EntrypointException(String key, String causingMod, Throwable cause) {
		super("Exception while loading entrypoint \"" + key + "\" of mod \"" + causingMod + "\"", cause);
		this.key = key;
	}

	/**
	 * @deprecated For internal use only, to be removed!
	 */
	@Deprecated
	public EntrypointException(String s) {
		super(s);
		this.key = null;
	}

	/**
	 * @deprecated For internal use only, to be removed!
	 */
	@Deprecated
	public EntrypointException(Throwable t) {
		super(t);
		this.key = null;
	}

	/** Returns the key of entrypoint in which the exception arose. */
	public String getKey() {
		return key;
	}
}
