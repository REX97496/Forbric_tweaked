/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import net.forbric.kernel.util.ForbricLog;

/**
 * Reports how much of an atlas the merge actually handed to the stitcher.
 *
 * <p>An atlas whose log line reads {@code Created: 32x32} while its siblings carry their real extents
 * has been stitched from almost nothing, and every model that names a sprite in it then reports a missing
 * texture. Four counts narrow it down: the sources the atlas file parsed into, the suppliers those
 * sources produced, the sprites the stitcher was actually handed, and what the stitcher handed back.
 * Whichever count drops to nothing names the stage that lost them.
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
		ForbricLog.debug("[Forbric/Atlas] " + location + " parsed into " + count + " sprite source(s)");
		ForbricLog.debug("[Forbric/Atlas] " + location + " sources: " + sourceNames(spriteSourceList));
	}

	/**
	 * Names each parsed source, so an empty or partial atlas definition is attributed to a source that ran and
	 * found nothing rather than guessed at from the sprite count alone.
	 */
	private static String sourceNames(Object spriteSourceList) {
		if (spriteSourceList == null) return "none";
		try {
			// The runtime class, not Class.forName: the game's classes sit in a loader this jar is not in, and a
			// field looked up on the kernel's view of the class cannot read an instance from the other one.
			Field sources = spriteSourceList.getClass().getDeclaredField("sources");
			sources.setAccessible(true);
			if (!(sources.get(spriteSourceList) instanceof List<?> list)) return "not a list";
			StringBuilder names = new StringBuilder();
			for (Object source : list) {
				if (names.length() > 0) names.append(" | ");
				names.append(source == null ? "null" : source.getClass().getSimpleName() + " " + fields(source));
			}
			return list.size() + " [" + names + "]";
		} catch (Throwable unreadable) {
			return unreadable.getClass().getSimpleName() + ": " + unreadable.getMessage();
		}
	}

	/** Reads a source's own fields, because the merged classes do not always keep the record's toString. */
	private static String fields(Object source) {
		StringBuilder out = new StringBuilder("{");
		for (Class<?> type = source.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
			for (Field field : type.getDeclaredFields()) {
				if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
				try {
					field.setAccessible(true);
					if (out.length() > 1) out.append(", ");
					out.append(field.getName()).append('=').append(field.get(source));
				} catch (Throwable unreadable) {
					out.append(field.getName()).append("=?");
				}
			}
		}
		return out.append('}').toString();
	}

	/** Reports on the supplier list without touching the operand stack; see {@link #loaded} for why. */
	public static void listed(Object suppliers) {
		int count = suppliers instanceof List<?> list ? list.size() : -1;
		ForbricLog.debug("[Forbric/Atlas] a source list produced " + count + " sprite supplier(s)");
	}

	/**
	 * Reports what the resource manager can actually see, before any atlas parsing happens.
	 *
	 * <p>Called at the head of {@code SpriteSourceList.load}, so it answers two separate questions with one
	 * measurement: which packs contribute an {@code atlases/<name>.json}, and whether
	 * {@code textures/block} enumerates at all from a caller that is not the atlas pipeline. A blocks atlas
	 * that ends up with only the two mod-provided whites is either not being handed vanilla's own atlas file
	 * or not being handed the directory listing, and those two produce very different counts here.
	 */
	public static void loading(Object resourceManager, Object location) {
		Object file = atlasFile(location);
		ForbricLog.debug("[Forbric/Atlas] loading " + location + " reads " + file + " from "
				+ packIds(resourceManager, file));
		if (String.valueOf(location).endsWith(":blocks")) {
			ForbricLog.debug("[Forbric/Atlas] mounted packs: " + mountedPacks(resourceManager));
		}
	}

	/** Which pack implementations are mounted, and what each one is backed by. */
	private static String mountedPacks(Object resourceManager) {
		if (resourceManager == null) return "-";
		try {
			Object packs = resourceManager.getClass().getMethod("listPacks").invoke(resourceManager);
			if (!(packs instanceof java.util.stream.Stream<?> stream)) return "not a stream";
			StringBuilder out = new StringBuilder();
			for (Object pack : stream.toList()) {
				if (out.length() > 0) out.append(" | ");
				out.append(packId(pack)).append(' ').append(pack.getClass().getName())
						.append(' ').append(fields(pack));
			}
			return out.toString();
		} catch (Throwable unreadable) {
			return unreadable.getClass().getSimpleName() + ": " + unreadable.getMessage();
		}
	}

	/**
	 * Reproduces {@code ATLAS_INFO_CONVERTER.idToFile}: the atlas id {@code minecraft:blocks} names the file
	 * {@code minecraft:atlases/blocks.json}. Probing the id itself looks like a missing pack and says nothing.
	 */
	private static Object atlasFile(Object location) {
		if (location == null) return null;
		String id = String.valueOf(location);
		int colon = id.indexOf(':');
		if (colon < 0) return null;
		String file = id.substring(0, colon) + ":atlases/" + id.substring(colon + 1) + ".json";
		// The argument's own class, not Class.forName: the game's classes live in a loader this jar is not in, so a
		// ResourceLocation built from the kernel's loader is a different Class object and getMethod on it finds nothing.
		Class<?> resourceLocation = location.getClass();
		try {
			return resourceLocation.getMethod("parse", String.class).invoke(null, file);
		} catch (Throwable noParse) {
			try {
				return resourceLocation.getMethod("tryParse", String.class).invoke(null, file);
			} catch (Throwable noTryParse) {
				try {
					return resourceLocation.getMethod("of", String.class, char.class).invoke(null, file,
							Character.valueOf(':'));
				} catch (Throwable noOf) {
					return file;
				}
			}
		}
	}

	/** The primitive lookup the pipeline is built on: one resource, or none. */
	private static String single(Object resourceManager, Object location) {
		if (resourceManager == null || location == null) return "-";
		try {
			Method getResource = resourceManager.getClass().getMethod("getResource", location.getClass());
			Object found = getResource.invoke(resourceManager, location);
			if (!(found instanceof java.util.Optional<?> optional)) return String.valueOf(found);
			if (optional.isEmpty()) return "absent";
			return "present from " + packId(optional.get());
		} catch (Throwable unreadable) {
			return unreadable.getClass().getSimpleName() + ": " + unreadable.getMessage();
		}
	}

	private static String packIds(Object resourceManager, Object location) {
		if (resourceManager == null || location == null) return "-";
		try {
			Method stack = resourceManager.getClass().getMethod("getResourceStack", location.getClass());
			Object resources = stack.invoke(resourceManager, location);
			if (!(resources instanceof List<?> list)) return "not a list";
			StringBuilder ids = new StringBuilder(Integer.toString(list.size()) + " pack(s): ");
			for (Object resource : list) {
				if (ids.charAt(ids.length() - 1) != ' ') ids.append(" | ");
				ids.append(packId(resource)).append(' ').append(contents(resource));
			}
			return ids.toString();
		} catch (Throwable unreadable) {
			return unreadable.getClass().getSimpleName() + ": " + unreadable.getMessage();
		}
	}

	/**
	 * What the resource actually holds, read the same way {@code load} reads it.
	 *
	 * <p>The pack name alone cannot tell a vanilla atlas definition from a mod's override of the same path, and
	 * the two differ entirely in what they contribute.
	 */
	private static String contents(Object resource) {
		if (resource == null) return "{}";
		try {
			Object reader = resource.getClass().getMethod("openAsReader").invoke(resource);
			StringBuilder text = new StringBuilder();
			for (String line : ((java.io.BufferedReader) reader).lines().toList()) {
				text.append(line).append('\n');
				if (text.length() > 4000) break;
			}
			((java.io.BufferedReader) reader).close();
			String json = text.toString();
			return "{" + json.length() + "B directory=" + count(json, "\"directory\"")
					+ " single=" + count(json, "\"single\"")
					+ " paletted=" + count(json, "\"paletted_permutations\"")
					+ " first=" + firstResource(json) + "}";
		} catch (Throwable unreadable) {
			return "{" + unreadable.getClass().getSimpleName() + "}";
		}
	}

	private static int count(String text, String needle) {
		int found = 0;
		for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
			found++;
		}
		return found;
	}

	private static String firstResource(String json) {
		int at = json.indexOf("\"resource\"");
		if (at < 0) return "-";
		int open = json.indexOf('"', json.indexOf(':', at));
		int close = open < 0 ? -1 : json.indexOf('"', open + 1);
		return open < 0 || close < 0 ? "-" : json.substring(open + 1, close);
	}

	private static String packId(Object resource) {
		if (resource == null) return "null";
		try {
			return String.valueOf(resource.getClass().getMethod("sourcePackId").invoke(resource));
		} catch (Throwable unnamed) {
			return resource.getClass().getSimpleName();
		}
	}

	private static String countTextures(Object resourceManager, String directory) {
		if (resourceManager == null) return "-";
		try {
			Method listResources = resourceManager.getClass().getMethod("listResources", String.class,
					java.util.function.Predicate.class);
			Object found = listResources.invoke(resourceManager, directory, (java.util.function.Predicate<Object>) path -> true);
			if (!(found instanceof java.util.Map<?, ?> map)) return "not a map";
			StringBuilder sample = new StringBuilder();
			int shown = 0;
			for (Object key : map.keySet()) {
				if (shown++ >= 3) break;
				if (sample.length() > 0) sample.append(' ');
				sample.append(String.valueOf(key).replace("minecraft:", ""));
			}
			return map.size() + " [" + sample + "]";
		} catch (Throwable unreadable) {
			return unreadable.getClass().getSimpleName() + ": " + unreadable.getMessage();
		}
	}

	/**
	 * Reports what reached the stitcher — and, crucially, how big those sprites are.
	 *
	 * <p>The first sprite's own name identifies which atlas this is, because {@code SpriteLoader} keeps its
	 * location private and the stitch call itself is not handed one. A stitcher handed five hundred sprites
	 * that still produces a 32x32 atlas has been handed five hundred sprites of no extent, so the dimension
	 * summary is the measurement that separates "the sprites are missing" from "the sprites are empty".
	 */
	public static void stitching(Object contents) {
		if (!(contents instanceof List<?> list)) {
			ForbricLog.debug("[Forbric/Atlas] stitch was handed " + contents);
			return;
		}
		String first = "-";
		int zero = 0;
		int minW = Integer.MAX_VALUE;
		int minH = Integer.MAX_VALUE;
		int maxW = 0;
		int maxH = 0;
		long area = 0;
		for (Object sprite : list) {
			int w = dimension(sprite, "width");
			int h = dimension(sprite, "height");
			if (w <= 0 || h <= 0) {
				zero++;
				continue;
			}
			minW = Math.min(minW, w);
			minH = Math.min(minH, h);
			maxW = Math.max(maxW, w);
			maxH = Math.max(maxH, h);
			area += (long) w * h;
		}
		if (!list.isEmpty()) {
			Object sprite = list.get(0);
			try {
				first = String.valueOf(sprite.getClass().getMethod("name").invoke(sprite));
			} catch (Throwable unnamed) {
				first = sprite == null ? "null" : sprite.getClass().getName();
			}
		}
		// Which directories actually contributed. A blocks atlas built from three 16x16 singles and none of
		// the thousand files under textures/block is the whole defect, and only the names can say so.
		int blocks = 0;
		int items = 0;
		StringBuilder sample = new StringBuilder();
		for (Object sprite : list) {
			String name = nameOf(sprite);
			if (name.contains("/block/")) blocks++;
			if (name.contains("/item/")) items++;
			if (sample.length() < 240) {
				if (sample.length() > 0) sample.append(' ');
				sample.append(name.replace("minecraft:", ""));
			}
		}
		ForbricLog.debug("[Forbric/Atlas] stitch got " + list.size() + " sprite(s), first is " + first
				+ "; empty=" + zero + ", w " + (minW == Integer.MAX_VALUE ? "-" : minW) + ".." + maxW
				+ ", h " + (minH == Integer.MAX_VALUE ? "-" : minH) + ".." + maxH + ", area=" + area
				+ ", block/=" + blocks + ", item/=" + items + ", names: " + sample);
	}

	private static String nameOf(Object sprite) {
		if (sprite == null) return "null";
		try {
			return String.valueOf(sprite.getClass().getMethod("name").invoke(sprite));
		} catch (Throwable unnamed) {
			return sprite.getClass().getSimpleName();
		}
	}

	/**
	 * Reports what the stitcher handed back: the atlas extent, the mip level it settled on, and how many
	 * sprites ended up addressable. {@code Preparations} is a record, so its accessors are public.
	 */
	public static void stitched(Object preparations) {
		if (preparations == null) {
			ForbricLog.debug("[Forbric/Atlas] stitch returned null");
			return;
		}
		Object regions = call(preparations, "regions");
		int count = regions instanceof Map<?, ?> map ? map.size() : -1;
		ForbricLog.debug("[Forbric/Atlas] stitch produced " + call(preparations, "width") + "x"
				+ call(preparations, "height") + "x" + call(preparations, "mipLevel") + " with " + count
				+ " sprite(s)");
	}

	private static int dimension(Object sprite, String accessor) {
		Object value = call(sprite, accessor);
		return value instanceof Integer size ? size : -1;
	}

	private static Object call(Object target, String accessor) {
		if (target == null) return "-";
		try {
			Method method = target.getClass().getMethod(accessor);
			return method.invoke(target);
		} catch (Throwable unreadable) {
			return "-";
		}
	}
}
