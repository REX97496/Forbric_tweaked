/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import java.lang.reflect.Field;
import java.util.List;
import net.forbric.kernel.util.ForbricLog;

/**
 * Reports how much of an atlas the merge actually handed to the stitcher.
 *
 * <p>An atlas whose log line reads {@code Created: 32x32} while its siblings carry their real extents
 * has been stitched from almost nothing, and every model that names a sprite in it then reports a missing
 * texture. The count distinguishes the two causes that look identical from the outside: a source list that
 * never parsed, and a source list that parsed but produced no suppliers.
 */
public final class AtlasProbe {
	private static final Field SOURCES = sources();

	private AtlasProbe() { }

	private static Field sources() {
		try {
			Field field = Class.forName("net.minecraft.client.renderer.texture.atlas.SpriteSourceList", false,
					AtlasProbe.class.getClassLoader()).getDeclaredField("sources");
			field.setAccessible(true);
			return field;
		} catch (Throwable absent) {
			return null;
		}
	}

	/**
	 * Reports on the list without touching the operand stack.
	 *
	 * <p>This returns {@code void} on purpose. The call site is spliced as {@code DUP; ALOAD n;
	 * INVOKESTATIC}, and a helper declared to return {@code Object} would leave a {@code java/lang/Object}
	 * on the stack where the enclosing method's return type wants its own type — a {@code VerifyError} that
	 * rejects the whole class and takes the resource reload with it. Consuming the duplicated reference and
	 * returning nothing leaves the original one exactly where it was.
	 */
	public static void loaded(Object spriteSourceList, Object location) {
		int count = -1;
		if (SOURCES != null && spriteSourceList != null) {
			try {
				Object value = SOURCES.get(spriteSourceList);
				if (value instanceof List<?> list) count = list.size();
			} catch (Throwable unreadable) {
				count = -2;
			}
		}
		ForbricLog.warn("[Forbric/Atlas] " + location + " parsed into " + count + " sprite source(s)");
	}

	/** Reports on the supplier list without touching the operand stack; see {@link #loaded} for why. */
	public static void listed(Object suppliers) {
		int count = suppliers instanceof List<?> list ? list.size() : -1;
		ForbricLog.warn("[Forbric/Atlas] a source list produced " + count + " sprite supplier(s)");
	}
}