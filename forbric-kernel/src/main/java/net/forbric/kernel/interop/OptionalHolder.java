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

package net.forbric.kernel.interop;

import java.util.Optional;

/**
 * A value that arrived wrapped in an {@link Optional} where the caller's descriptor promised the bare type.
 *
 * <p>The merged base splices two patched Minecraft bases together, and a lookup that one of them expressed as a
 * {@code Holder} carrying the value directly can come out of the other one carrying an {@code Optional}. The
 * call site still says "give me the DimensionType", so the {@code CHECKCAST} that follows an untyped
 * {@code Holder.value()} throws {@code ClassCastException} the moment a player logs into a world.
 *
 * <p>{@link #unwrap(Object)} removes exactly one {@code Optional} layer and is the identity for everything else,
 * so it can be inserted in front of any such cast without changing the meaning of a value that was never wrapped.
 */
public final class OptionalHolder {
	private OptionalHolder() {
	}

	/**
	 * Returns {@code value} itself, or the value an {@link Optional} holds, or null for an empty one.
	 *
	 * <p>A null result leaves the caller's cast to fail exactly as it would have, rather than inventing a
	 * substitute, so an unexpectedly empty holder still reports itself instead of turning into a silent default.
	 */
	public static Object unwrap(Object value) {
		if (value instanceof Optional) {
			return ((Optional<?>) value).orElse(null);
		}

		return value;
	}
}
