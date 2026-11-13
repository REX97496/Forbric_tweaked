package net.forbric.kernel.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;

/**
 * Puts the integrated server's thread into MinecraftForge's own SERVER thread group, so Forge's
 * {@code EffectiveSide.get()} answers {@code SERVER} on it instead of falling back to {@code CLIENT}.
 *
 * <p>Measured on the 1.21.1 line on 2026-11-13, from three facts that only line up together.
 *
 * <p>First, {@code javap -c net.minecraft.server.MinecraftServer} shows {@code spin} building the thread as
 * {@code new Thread(net/neoforged/fml/util/thread/SidedThreadGroups.SERVER, runnable, "Server thread")} — the merged base
 * carries NeoForge's factory. Second, {@code javap -c net.minecraftforge.fml.util.thread.EffectiveSide.get()} is
 * {@code Thread.getThreadGroup() instanceof net.minecraftforge.fml.util.thread.SidedThreadGroup ? ((...).getSide())
 * : LogicalSide.CLIENT} — it recognises only its OWN class, so a NeoForge group falls through to the {@code CLIENT}
 * default. Third, {@code ForgeHooks.onCustomPayload(CustomPayloadEvent)} computes the side a packet arrived on as
 * {@code getReceiving() == CLIENTBOUND ? CLIENT : SERVER} and, when that differs from {@code EffectiveSide.get()},
 * calls {@code connection.disconnect("Illegal packet received, terminating connection")}.
 *
 * <p>The three together mean every serverbound custom payload dies on this base: on the server thread the packet is
 * {@code SERVERBOUND} (so the expected side is {@code SERVER}), the thread group is NeoForge's (so the answered side is
 * {@code CLIENT}), and the mismatch disconnects the player. The first observed case was curios: clicking its button in
 * the inventory sent its payload and the log said {@code rex888888888 lost connection: Illegal packet received,
 * terminating connection}.
 *
 * <p>The fix cannot be a bytecode edit: {@code net.minecraftforge.*} classes never reach this kernel's transformer chain
 * (measured the same day on {@code RegistryManager}), so {@code EffectiveSide} cannot be rewritten. What is left is the
 * thread the check reads. Swapping the running server thread's group for Forge's makes the existing check answer
 * correctly, and it costs nothing on the NeoForge side: {@code javap} over the merged base shows the only users of
 * {@code net.neoforged...EffectiveSide} are none, and the only users of NeoForge's group are three thread factories
 * ({@code MinecraftServer.spin}, {@code ServerConnectionListener}'s two event-loop factories,
 * {@code ServerLoginPacketListenerImpl}), which read the field when a thread is created rather than checking the group
 * of the one they run on.
 *
 * <p>{@code Thread.group} has no setter, so it is written through {@code sun.misc.Unsafe}'s field offset. The read is
 * verified afterwards ({@code Thread.getThreadGroup() == group}) and the method returns whether it took effect, so a JVM
 * that refuses the write reports a miss instead of silently leaving Forge's check broken.
 */
public final class KernelServerThreads {

	/** One paragraph per JVM: the first world adopts the group, every later world is a no-op. */
	private static final AtomicBoolean SAID = new AtomicBoolean();

	private static final AtomicBoolean WARNED = new AtomicBoolean();

	/** One paragraph per JVM about the Netty factories, which are asked for once when the listener is built. */
	private static final AtomicBoolean NETTY_SAID = new AtomicBoolean();

	private KernelServerThreads() {
	}

	/**
	 * Makes the calling thread belong to MinecraftForge's SERVER thread group. Must run on the thread whose side
	 * Forge will ask about — that is the server thread, so the call site is the about-to-start hook.
	 *
	 * @return true when the thread now sits in Forge's group (or already did)
	 */
	public static boolean adoptForgeServerThreadGroup() {
		Thread thread = Thread.currentThread();
		try {
			Object forgeServer = forgeServerGroup();
			if (forgeServer == null) {
				return false;
			}
			ThreadGroup current = thread.getThreadGroup();
			if (current == forgeServer) {
				return true;
			}
			if (!(forgeServer instanceof ThreadGroup)) {
				return false;
			}
			if (!replaceGroup(thread, (ThreadGroup) forgeServer)) {
				return false;
			}
			if (SAID.compareAndSet(false, true)) {
				ForbricLog.info("[Forbric/Threads] the server thread now belongs to MinecraftForge's SERVER thread group "
						+ "('%s'), so EffectiveSide.get() answers SERVER here; without it ForgeHooks.onCustomPayload read "
						+ "every serverbound custom payload as the wrong side and disconnected the player",
						String.valueOf(forgeServer));
			}
			return true;
		} catch (Throwable t) {
			if (WARNED.compareAndSet(false, true)) {
				ForbricLog.warn("[Forbric/Threads] could not move the server thread into MinecraftForge's SERVER thread "
						+ "group; Forge's own custom-payload check will keep reading this thread as the client and drop "
						+ "the connection on the first serverbound payload a mod sends", Reflect.unwrap(t));
			}
			return false;
		}
	}

	/**
	 * The factory a server-side Netty event loop should create its threads with, so they belong to MinecraftForge's
	 * SERVER thread group as well.
	 *
	 * <p>Moving only the server thread was not enough: the payload check that disconnects the player does not run there.
	 * A probe inserted at the head of {@code ServerGamePacketListenerImpl.handleCustomPayload} printed
	 * {@code thread='Netty Server IO #1' group=net.neoforged.fml.util.thread.SidedThreadGroup receiving=SERVERBOUND
	 * effectiveSide=CLIENT} (measured 2026-11-13, 20:31), so the thread that asks Forge which side it is on is a Netty
	 * IO thread, and {@code javap -c ServerConnectionListener} shows why: both event-loop factories build their
	 * {@code ThreadFactoryBuilder} with {@code getstatic net/neoforged/fml/util/thread/SidedThreadGroups.SERVER}.
	 *
	 * <p>MinecraftForge's {@code SidedThreadGroup} is itself a {@code ThreadFactory} ({@code javap} on
	 * {@code forge-runtime-1.21.1.jar}: {@code extends java.lang.ThreadGroup implements java.util.concurrent.ThreadFactory}),
	 * so handing it to Netty creates the threads directly in the group Forge recognises — no group surgery after the fact.
	 * On a line without MinecraftForge the NeoForge group is returned unchanged, which is what the class asked for.
	 */
	public static ThreadFactory sharedServerThreadFactory() {
		Object forge;
		try {
			forge = forgeServerGroup();
		} catch (Throwable t) {
			forge = null;
		}
		if (forge instanceof ThreadFactory) {
			if (NETTY_SAID.compareAndSet(false, true)) {
				ForbricLog.info("[Forbric/Threads] the server's Netty IO threads are now created in MinecraftForge's "
						+ "SERVER thread group ('%s'); the custom-payload side check runs on those threads, not on the "
						+ "server thread, so moving only the server thread left EffectiveSide answering CLIENT there",
						String.valueOf(forge));
			}
			return (ThreadFactory) forge;
		}
		try {
			Object neo = Class.forName("net.neoforged.fml.util.thread.SidedThreadGroups").getField("SERVER").get(null);
			if (neo instanceof ThreadFactory) {
				return (ThreadFactory) neo;
			}
		} catch (Throwable absent) {
			// No NeoForge either: fall through to a plain thread, which is what any Netty default would do.
		}
		return Thread::new;
	}

	/** MinecraftForge's {@code SidedThreadGroups.SERVER}, or null when this line has no MinecraftForge. */
	private static Object forgeServerGroup() throws Exception {
		Class<?> groups;
		try {
			groups = Class.forName("net.minecraftforge.fml.util.thread.SidedThreadGroups");
		} catch (ClassNotFoundException absent) {
			return null;
		}
		return groups.getField("SERVER").get(null);
	}

	/**
	 * Writes the thread's group through {@code sun.misc.Unsafe}. There is no setter and the slot is final; a field offset
	 * write is the only way to change it on a thread that already started.
	 *
	 * <p>Where the slot lives depends on the JDK. Up to 17 it is {@code Thread.group}. From 18 (JEP 425's rework) a
	 * platform thread keeps it in {@code Thread.holder}, a {@code Thread$FieldHolder}, as {@code FieldHolder.group} —
	 * {@code javap -p java.lang.Thread} on the JDK 21 this line runs on shows no {@code group} field at all, and
	 * {@code getThreadGroup()} loads {@code this.holder.group}, which is why the first attempt failed with
	 * {@code NoSuchFieldException: group} (measured, 20:22:33, logged from this method). Both shapes are handled so the
	 * same code is correct on either JDK.
	 */
	private static boolean replaceGroup(Thread thread, ThreadGroup group) {
		try {
			Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
			Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
			theUnsafe.setAccessible(true);
			Object unsafe = theUnsafe.get(null);
			Method objectFieldOffset = unsafeClass.getMethod("objectFieldOffset", Field.class);
			Method putObject = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);
			Method getObject = unsafeClass.getMethod("getObject", Object.class, long.class);

			Field direct = declaredField(Thread.class, "group");
			if (direct != null) {
				long offset = ((Number) objectFieldOffset.invoke(unsafe, direct)).longValue();
				putObject.invoke(unsafe, thread, offset, group);
			} else {
				Field holderSlot = Thread.class.getDeclaredField("holder");
				long holderOffset = ((Number) objectFieldOffset.invoke(unsafe, holderSlot)).longValue();
				Object holder = getObject.invoke(unsafe, thread, holderOffset);
				if (holder == null) {
					return false;
				}
				Field holderGroup = holder.getClass().getDeclaredField("group");
				long groupOffset = ((Number) objectFieldOffset.invoke(unsafe, holderGroup)).longValue();
				putObject.invoke(unsafe, holder, groupOffset, group);
			}
			return thread.getThreadGroup() == group;
		} catch (Throwable t) {
			if (WARNED.compareAndSet(false, true)) {
				ForbricLog.warn("[Forbric/Threads] sun.misc.Unsafe could not write the thread's group slot; the server "
						+ "thread keeps NeoForge's group and MinecraftForge's EffectiveSide keeps answering CLIENT on it",
						Reflect.unwrap(t));
			}
			return false;
		}
	}

	/** {@code getDeclaredField}, or null when this JDK does not have that slot. */
	private static Field declaredField(Class<?> owner, String name) {
		try {
			return owner.getDeclaredField(name);
		} catch (NoSuchFieldException absent) {
			return null;
		}
	}
}
