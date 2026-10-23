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

package net.forbric.kernel.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.forbric.kernel.boot.KernelForgeModContext.Handle;
import net.minecraftforge.eventbus.api.BusBuilder;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.IConfigEvent;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.IModBusEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.javafmlmod.FMLModContainer;
import net.minecraftforge.fml.lowcodemod.LowCodeModContainer;
import net.minecraftforge.unsafe.UnsafeHacks;

/**
 * The game side of the kernel's TRADITIONAL-Forge loading context: an {@code IEventBus} + {@code FMLModContainer} +
 * {@code FMLJavaModLoadingContext} triple, manufactured without any FancyModLoader discovery, module layer or
 * sorting.
 *
 * <p>This is the 1.21.1 EDITION, and the bus is why it exists. On 26.2 the bus is EventBus 7's {@code BusGroup}: a
 * group of per-event-type buses, minted by the static {@code BusGroup.create(name, marker)}, each event class
 * reaching its own bus through a per-event static {@code getBus(group)}. On 1.21.1 the same role is EventBus 6's
 * single {@code IEventBus}, built by {@code BusBuilder} (an EXPRESSION, not a static factory), live from
 * {@code build()}, and posted to directly. The {@code FMLModContainer} field that must receive it changes name and
 * type with it — {@code eventBusGroup: BusGroup} on 26.2, {@code eventBus: IEventBus} on 1.21.1 (javap both
 * carriers) — and because that field is written by NAME through {@code UnsafeHacks}, the type half is a compile
 * error while the name half is a {@code NoSuchFieldException} the compiler cannot see.
 *
 * <p>The boot-side door is {@code KernelForgeModContext}, whose javadoc explains why traditional Forge needs its
 * own factory rather than reusing the NeoForge one: events here run on EventBus 6 ({@code IEventBus}), a
 * {@code @Mod} class is constructed with an {@code FMLJavaModLoadingContext} rather than
 * {@code (IEventBus, Dist, ModContainer)}, and {@code RegisterEvent} is the 3-arg
 * {@code (key, ForgeRegistry, Registry)} flavour.
 *
 * <h2>What is still reflective, and why</h2>
 *
 * <p>Both {@code FMLModContainer} and {@code FMLJavaModLoadingContext} declare only loader-facing constructors
 * (taking a {@code ModFileScanData} and a {@code ModuleLayer} the kernel deliberately does not have), so the
 * instances are allocated WITHOUT a constructor and the fields the mod-facing API reads are written directly.
 * Naming a field in order to write it is reflection by definition; what moving here buys is that every TYPE is
 * checked, so a renamed class is a build failure instead of a {@code ClassNotFoundException} during mod
 * construction. Field NAMES remain strings, and {@link #uset} fails loudly on each while
 * {@link #usetIfPresent} tolerates the ones a Forge revision may not have.
 *
 * <p>{@code ModLoadingContext.setActiveContainer} is likewise reflective: it is not public API, and the kernel is
 * standing exactly where the genuine loader would.
 */
public final class KernelForgeContainers {
	private KernelForgeContainers() {
	}

	/**
	 * Manufactures the {@code BusGroup} + container + context for {@code modId}. Identity only — it needs no
	 * {@code @Mod} class, which is what lets the kernel publish every MinecraftForge container into
	 * {@code ModList} before any constructor runs; {@link #constructMod} marries the class in afterwards.
	 *
	 * <p>It deliberately does NOT make the container active any more. It used to, and with creation and
	 * construction adjacent that read as "this is the mod loading right now" — which stopped being true the
	 * moment they were separated. Both callers set it explicitly around the constructor instead, which also
	 * clears it afterwards; the side effect never was.
	 */
	public static Handle create(String modId) throws Exception {
		IEventBus bus = modBus();
		FMLModContainer container = UnsafeHacks.newInstance(FMLModContainer.class);
		FMLJavaModLoadingContext jctx = UnsafeHacks.newInstance(FMLJavaModLoadingContext.class);

		uset(FMLJavaModLoadingContext.class, "container", jctx, container);
		uset(FMLModContainer.class, "eventBus", container, bus);
		// FMLModContainer.context backs its contextExtension supplier; genuine Forge sets it in the ctor we skipped.
		usetIfPresent(FMLModContainer.class, "context", container, jctx);
		uset(ModContainer.class, "modId", container, modId);
		uset(ModContainer.class, "namespace", container, modId);
		uset(ModContainer.class, "contextExtension", container, (Supplier<Object>) () -> jctx);
		// ModContainer's ctor (skipped by UnsafeHacks.newInstance) initialises these; addConfig /
		// registerExtensionPoint / the activity + dependency maps all NPE on a null.
		uset(ModContainer.class, "configs", container, new EnumMap<ModConfig.Type, Object>(ModConfig.Type.class));
		uset(ModContainer.class, "extensionPoints", container, new ConcurrentHashMap<>());
		usetIfPresent(ModContainer.class, "activityMap", container, new HashMap<>());
		usetIfPresent(ModContainer.class, "dependencies", container, new HashSet<>());
		// getModInfo() is null without this (the ctor arg we skipped); Forge's own config + display-test paths read it.
		usetIfPresent(ModContainer.class, "modInfo", container, new KernelForgeModInfo(modId));
		// 1.21.1 declares configHandler on ModContainer, its own (skipped) constructor writes Optional.empty() into
		// it, and dispatchConfigEvent reads it as configHandler.orElse(null) and dereferences the result (javap -c).
		// Empty is the value that constructor would have left, so this restores the carrier instead of inventing a
		// handler; 26.2 has no such field, which is why the shared file never had to write it.
		usetIfPresent(ModContainer.class, "configHandler", container, Optional.<Consumer<IConfigEvent>>empty());

		return new Handle(modId, bus, container, jctx);
	}

	/**
	 * The container MinecraftForge's {@code LowCodeModLanguageProvider} builds for a {@code lowcodefml} mod, built
	 * through its own public constructor.
	 *
	 * <p>Unlike {@link #create} nothing here has to be allocated around a constructor: this one takes only the mod's
	 * info, and ignores its scan data and module layer — which is also why it is safe to hand it neither. It has
	 * no bus group, as on MinecraftForge; the constructor itself drops the display test a data-only mod has no use
	 * for.
	 *
	 * @param jar the mod's own jar, which its info's owning file is read from
	 */
	public static Object lowCode(String modId, java.nio.file.Path jar) {
		return new LowCodeModContainer(new KernelForgeModInfo(modId, jar), null, null);
	}

	/**
	 * Makes {@code container} the active {@code ModLoadingContext} — what every {@code *.get()} reads.
	 *
	 * <p>{@code ModLoadingContext.get()} is marked deprecated-for-removal in this carrier. That is worth a line
	 * because it is a fact the reflective version could not have told us: {@code getMethod("get")} reports no
	 * deprecation, so the kernel was calling a method Forge intends to delete and nothing said so. There is no
	 * alternative here — the kernel is standing exactly where the genuine loader stands, and this is how the
	 * active container is set — so it is suppressed and written down rather than worked around. When the carrier
	 * is next re-pinned and the method goes, this becomes a build failure instead of a runtime one.
	 */
	@SuppressWarnings("removal")
	public static void setActiveContainer(Object container) throws Exception {
		ModLoadingContext mlc = ModLoadingContext.get();
		Method setActive = ModLoadingContext.class.getDeclaredMethod("setActiveContainer", ModContainer.class);
		setActive.setAccessible(true);
		setActive.invoke(mlc, container);
	}

	/**
	 * Constructs {@code modClassName} against {@code handle}, preferring the {@code (FMLJavaModLoadingContext)}
	 * constructor that traditional-Forge mods declare, and stores the instance on the container.
	 */
	public static Object constructMod(String modClassName, Handle handle) throws Exception {
		Class<?> modCls = Class.forName(modClassName, true, KernelForgeContainers.class.getClassLoader());
		Object mod;
		try {
			Constructor<?> c = modCls.getDeclaredConstructor(FMLJavaModLoadingContext.class);
			c.setAccessible(true);
			mod = c.newInstance(handle.jctx());
		} catch (NoSuchMethodException noCtxCtor) {
			Constructor<?> c = modCls.getDeclaredConstructor();
			c.setAccessible(true);
			mod = c.newInstance();
		}
		usetIfPresent(FMLModContainer.class, "modInstance", handle.container(), mod);
		usetIfPresent(FMLModContainer.class, "modClass", handle.container(), modCls);
		return mod;
	}

	/**
	 * Re-arms the mod bus: {@code IEventBus.start()} on this line, {@code BusGroup.startup()} on 26.2.
	 *
	 * <p>Neither is a fence around dispatch, and the javadoc this replaces said one was. On 26.2
	 * {@code BusGroupImpl.startup} iterates the group's buses into {@code AbstractEventBusImpl.startup}, whose body
	 * is {@code shutdownFlag.compareAndSet(true, false)} followed by an invoker re-arm; on 1.21.1
	 * {@code IEventBus.start()} writes {@code shutdown = false} and {@code post} answers false while that flag is
	 * set ({@code javap -c} on both). A freshly created bus of either generation dispatches as soon as it exists, so
	 * this call resurrects a shut-down bus and opens nothing.
	 */
	public static void startup(Object busGroup) {
		((IEventBus) busGroup).start();
	}

	/**
	 * The per-mod event bus, built the way this line's own carrier builds it.
	 *
	 * <p>Read out of 1.21.1's {@code FMLModContainer} constructor with {@code javap -c}: {@code BusBuilder.builder()}
	 * with {@code IModBusEvent} as the marker type and {@code setTrackPhases(false)}. Two builder calls from that
	 * disassembly are deliberately NOT reproduced, and both are named rather than quietly dropped:
	 *
	 * <ul>
	 *   <li>{@code setExceptionHandler(..)} — the carrier passes its own {@code onEventFailed}, which logs through
	 *       {@code FMLModContainer}'s markers and has no counterpart here. What is left is the bus's default handler
	 *       ({@code EventBus} implements {@code IEventExceptionHandler} itself), i.e. the behaviour of a builder
	 *       that sets none.</li>
	 *   <li>{@code useModLauncher()} — that flag selects {@code ModLauncherFactory}, whose {@code createWrapper}
	 *       ENQUEUES the generated listener class for modlauncher's transformer instead of defining it, and
	 *       {@code ModLauncherFactory.processWrapperClass} is the only thing that drains the queue. The kernel's
	 *       loader is not that pipeline, so the queue would never be drained and no listener would materialise; the
	 *       default {@code ClassLoaderFactory} defines the wrapper itself.</li>
	 * </ul>
	 *
	 * <p>What is lost with the name: 26.2's {@code BusGroup.create("modBusFor" + modId, ..)} names each group and the
	 * name is what its diagnostics print. {@code BusBuilder} takes no bus name, so on this line a mod bus is
	 * identifiable only by the kernel's own log lines.
	 */
	private static IEventBus modBus() {
		return BusBuilder.builder()
				.setTrackPhases(false)
				.markerType(IModBusEvent.class)
				.build();
	}

	/** Writes a field that MUST exist; a missing one is a real change in the carrier and has to be loud. */
	private static void uset(Class<?> owner, String fieldName, Object target, Object value) throws Exception {
		Field field = owner.getDeclaredField(fieldName);
		UnsafeHacks.setField(field, target, value);
	}

	/** Writes a field only some Forge revisions declare. */
	private static void usetIfPresent(Class<?> owner, String fieldName, Object target, Object value) {
		try {
			uset(owner, fieldName, target, value);
		} catch (Throwable absent) {
			net.forbric.kernel.util.ForbricLog.debug("[Forbric/ForgeCtx] %s has no %s field — skipped: %s",
					owner.getSimpleName(), fieldName, String.valueOf(absent));
		}
	}
}
