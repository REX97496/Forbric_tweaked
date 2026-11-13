package net.forbric.kernel.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reports where a serverbound custom payload is handled and what the two sides of Forge's own check read there,
 * when {@code -Dforbric.probePayloads=true}.
 *
 * <p>The question it answers is narrow. {@code ForgeHooks.onCustomPayload} disconnects the player when the side a packet
 * arrived on differs from {@code EffectiveSide.get()}, and after the server thread was moved into MinecraftForge's SERVER
 * thread group the disconnect still happened — so either the check does not run on the server thread at all, or one of
 * the two values is not what the name suggests. Guessing between those is what this probe replaces: it prints the thread
 * name, the thread group's class, the connection's {@code getReceiving()}, and the value {@code EffectiveSide.get()}
 * really returns, on the exact call that decides.
 */
public final class KernelNetProbe {

	/** Property naming a file to append to; without it the lines go to the kernel log. */
	public static final String FILE_PROPERTY = "forbric.probeFile";

	private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

	private KernelNetProbe() {
	}

	/**
	 * Called at the head of {@code ServerGamePacketListenerImpl.handleCustomPayload} with the listener and its packet.
	 *
	 * @param listener the packet listener, untyped so this class needs no Minecraft types
	 * @param packet the payload packet, untyped for the same reason
	 */
	public static void see(Object listener, Object packet) {
		try {
			Thread thread = Thread.currentThread();
			ThreadGroup group = thread.getThreadGroup();
			Object connection = field(listener, "connection");
			Object receiving = call(connection, "getReceiving");
			Object effective = forgeEffectiveSide();
			String payloadKind = packet == null ? "null" : packet.getClass().getSimpleName();
			String line = "[Forbric/Net] handleCustomPayload payload=" + payloadKind
					+ " thread='" + thread.getName() + "'"
					+ " group=" + (group == null ? "null" : group.getClass().getName())
					+ " receiving=" + receiving
					+ " effectiveSide=" + effective;
			if (SEEN.add(line)) {
				say(line);
			}
		} catch (Throwable t) {
			say("[Forbric/Net] handleCustomPayload probe could not read the site: " + t);
		}
	}

	private static Object forgeEffectiveSide() {
		try {
			Class<?> type = Class.forName("net.minecraftforge.fml.util.thread.EffectiveSide");
			Method get = type.getMethod("get");
			return get.invoke(null);
		} catch (Throwable absent) {
			return "no-forge";
		}
	}

	private static Object field(Object target, String name) throws Exception {
		for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
			try {
				Field field = type.getDeclaredField(name);
				field.setAccessible(true);
				return field.get(target);
			} catch (NoSuchFieldException next) {
				// keep walking up
			}
		}
		return null;
	}

	private static Object call(Object target, String name) {
		if (target == null) {
			return null;
		}
		try {
			Method method = target.getClass().getMethod(name);
			method.setAccessible(true);
			return method.invoke(target);
		} catch (Throwable t) {
			return "?" + t.getClass().getSimpleName();
		}
	}

	private static void say(String line) {
		String file = System.getProperty(FILE_PROPERTY);
		if (file != null && !file.isEmpty()) {
			try {
				Path path = Paths.get(file);
				Files.writeString(path, line + System.lineSeparator(), StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				return;
			} catch (Throwable t) {
				net.forbric.kernel.util.ForbricLog.info("%s (probe file failed: %s)", line, t);
				return;
			}
		}
		net.forbric.kernel.util.ForbricLog.info("%s", line);
	}
}
