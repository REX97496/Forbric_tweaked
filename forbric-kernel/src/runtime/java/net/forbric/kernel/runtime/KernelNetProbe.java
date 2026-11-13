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

	/**
	 * Called at the head of the client's {@code ClientPacketListener.handleCustomPayload}.
	 *
	 * <p>Answers a different question from {@link #see}: a curios inventory opens with no curio slots while the server's
	 * container has the same 46 slots as vanilla. Curios fills the client container from a server-to-client payload, so
	 * the first thing to know is whether the client ever sees that payload at all — printing the type id of every
	 * clientbound custom payload says so, and names the ones that do arrive.
	 *
	 * @param listener the client packet listener, untyped so this class needs no Minecraft types
	 * @param payload the custom payload itself, not the packet: the merged base's client entry point takes
	 *     {@code CustomPacketPayload} directly, untyped here for the same reason
	 */
	public static void seeClient(Object listener, Object payload) {
		try {
			String line = "[Forbric/Net] client handleCustomPayload id=" + payloadId(payload)
					+ " class=" + (payload == null ? "null" : payload.getClass().getSimpleName());
			if (SEEN.add(line)) {
				say(line);
			}
		} catch (Throwable t) {
			say("[Forbric/Net] client handleCustomPayload probe could not read the site: " + t);
		}
	}

	/**
	 * Called at the head of {@code ClientCommonPacketListenerImpl.handleCustomPayload(ClientboundCustomPayloadPacket)}.
	 *
	 * <p>That is the packet's own entry point, one step before the payload reaches {@code ClientPacketListener}. If this
	 * site prints and {@link #seeClient} does not, the payload arrived and the dispatch inside the base lost it; if
	 * neither prints, nothing was sent — and then the server side is where the answer is, not the client.
	 *
	 * @param listener the common packet listener, untyped so this class needs no Minecraft types
	 * @param packet the clientbound payload packet, untyped for the same reason
	 */
	public static void seeClientPacket(Object listener, Object packet) {
		try {
			String line = "[Forbric/Net] client packet handleCustomPayload id=" + payloadId(call(packet, "payload"))
					+ " class=" + (packet == null ? "null" : packet.getClass().getSimpleName());
			if (SEEN.add(line)) {
				say(line);
			}
		} catch (Throwable t) {
			say("[Forbric/Net] client packet handleCustomPayload probe could not read the site: " + t);
		}
	}

	/** The payload's registered type id, which is what a mod names when it registers one. */
	private static String payloadId(Object payload) {
		Object type = call(payload, "type");
		Object id = call(type, "id");
		return String.valueOf(id);
	}

	/**
	 * Called at the head of the client's {@code handleUnknownCustomPayload}.
	 *
	 * <p>The client printed no {@code handleCustomPayload} line at all while the server answered one serverbound payload,
	 * so the next question is whether curios' payload never arrived or arrived and was thrown away as an unknown type.
	 * The two look identical from outside the connection: this site is where the client discards one, and the id it
	 * prints names the payload a mod registered that this client could not resolve.
	 *
	 * @param listener the client packet listener, untyped so this class needs no Minecraft types
	 * @param payload the payload the client could not resolve, untyped for the same reason
	 */
	public static void seeUnknown(Object listener, Object payload) {
		try {
			String line = "[Forbric/Net] client handleUnknownCustomPayload id=" + payloadId(payload)
					+ " class=" + (payload == null ? "null" : payload.getClass().getSimpleName());
			if (SEEN.add(line)) {
				say(line);
			}
		} catch (Throwable t) {
			say("[Forbric/Net] handleUnknownCustomPayload probe could not read the site: " + t);
		}
	}

	private static Object forgeEffectiveSide() {		try {
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
