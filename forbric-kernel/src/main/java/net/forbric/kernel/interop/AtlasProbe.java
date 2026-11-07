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
 * texture. Three counts narrow it down: the sources the atlas file parsed into, the suppliers those
 * sources produced, and the sprites the stitcher was actually handed. Whichever count drops to nothing
 * names the stage that lost them.
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
	 * Reports on the parsed source list without touching the operand stack.
	 *
	 * <p>This returns {@code void} on purpose. The call site is spliced as {@code DUP; ALOAD n;
	 * INVOKESTATIC}, and a helper declared to return {@code Object} would leave a {@code java/lang/Object}
	 * on the stack where the enclosing method's return type wants its own type — a {@code VerifyError} that
	 * rejects the whole class and takes the resource reload with it. Consuming the duplicated reference and
	 * returning nothing leaves the original one exactly where it was.
	 */
	public static void loaded(Object spriteSourceList, Object location) {
		String count = "unreadable";
		if (spriteSourceList != null) {
			if (SOURCES == null) {
				count = "no sources field";
			} else {
				try {
					Object value = SOURCES.get(spriteSourceList);
					count = value instanceof List<?> list ? Integer.toString(list.size()) : "not a list";
				} catch (Throwable unreadable) {
					count = unreadable.getClass().getSimpleName() + ": " + unreadable.getMessage();
				}
			}
		}
		ForbricLog.warn("[Forbric/Atlas] " + location + " parsed into " + count + " sprite source(s)");
	}

	/** Reports on the supplier list without touching the operand stack; see {@link #loaded} for why. */
	public static void listed(Object suppliers) {
		int count = suppliers instanceof List<?> list ? list.size() : -1;
		ForbricLog.warn("[Forbric/Atlas] a source list produced " + count + " sprite supplier(s)");
	}

	/**
	 * Reports what reached the stitcher — the stage after the suppliers ran.
	 *
	 * <p>The first sprite's own name identifies which atlas this is, because {@code SpriteLoader} keeps its
	 * location private and the stitch call itself is not handed one.
	 */
	public static void stitching(Object contents) {
		if (!(contents instanceof List<?> list)) {
			ForbricLog.warn("[Forbric/Atlas] stitch was handed " + contents);
			return;
		}
		String first = "-";
		if (!list.isEmpty()) {
			Object sprite = list.get(0);
			try {
				first = String.valueOf(sprite.getClass().getMethod("name").invoke(sprite));
			} catch (Throwable unnamed) {
				first = sprite == null ? "null" : sprite.getClass().getName();
			}
		}
		ForbricLog.warn("[Forbric/Atlas] stitch got " + list.size() + " sprite(s), first is " + first);
	}
}
