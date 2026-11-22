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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.kernel.util.ForbricLog;

/**
 * Gives the merged base's custom-payload codec the type provider fabric-networking-api-v1 expects it to carry.
 *
 * <p>Fabric installs that provider from {@code CustomPayloadC2SPacketMixin} / {@code CustomPayloadS2CPacketMixin}:
 * each wraps the call that builds a packet's codec, casts the answer to its own
 * {@code FabricCustomPayloadPacketCodec}, and calls {@code fabric_setPacketCodecProvider}. Both mixins anchor on
 * {@code CustomPacketPayload.codec(FallbackProvider, List)} — the shape vanilla and Forge call, measured in the
 * base's own untouched copies under {@code META-INF/forbric/native-reference/{FABRIC,FORGE}} — while the merged
 * base carries NeoForge's patch, which calls the four-argument
 * {@code codec(FallbackProvider, List, ConnectionProtocol, PacketFlow)} instead (the NEOFORGE copies say so). With
 * no call of the shape they name, the wrappers never attach, the provider is never installed, and every custom
 * payload a Fabric mod registers is a payload the codec cannot resolve.
 *
 * <p>The codec instance is not unreachable, though: the merge builds NeoForge's copy
 * ({@code CustomPacketPayload$1$forbricneo}), the kernel already appends it to the mixin's target list, and Mixin
 * therefore adds fabric's interface, field and setter to it. So the transformer calls {@link #install} right after
 * each four-argument call in the two packet {@code <clinit>}s, and this class supplies the provider those wrappers
 * would have supplied — the same decision they make: a {@code RegistryFriendlyByteBuf} means the play registry,
 * anything else the configuration one, and the flow is the one the enclosing packet class is.
 *
 * <p>Everything is reflective and takes {@code Object}, so this file needs no Minecraft import and no per-line
 * overlay. If fabric-api is absent, if the codec does not carry the setter (a line whose merge did not twin the
 * class), or if any reflection fails, the codec is returned unchanged — the packet still builds the codec it built
 * before, and the first such outcome is logged once. {@code -Dforbric.fabricPayloads=off} returns every codec by
 * identity.
 */
public final class KernelFabricPayloads {
	public static final String PROPERTY = "forbric.fabricPayloads";

	/** fabric's provider interface, not an implementation: only the one method is ever called. */
	private static final String PROVIDER = "net.fabricmc.fabric.impl.networking.CustomPayloadTypeProvider";
	/** fabric's four registries, keyed the way its own wrappers key them. */
	private static final String REGISTRIES = "net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl";
	private static final String SETTER = "fabric_setPacketCodecProvider";
	/** A buffer of this class is a play-phase buffer; configuration-phase traffic uses a plain one. */
	private static final String REGISTRY_BUFFER = "net.minecraft.network.RegistryFriendlyByteBuf";

	private static final AtomicBoolean SAID_NO_SETTER = new AtomicBoolean();
	private static final AtomicBoolean SAID_INSTALLED = new AtomicBoolean();
	private static volatile boolean failed;

	private KernelFabricPayloads() {
	}

	/**
	 * Site: the codec a custom-payload packet's {@code <clinit>} just built.
	 *
	 * <p>Inserted immediately after the four-argument {@code CustomPacketPayload.codec} call, where the codec is the
	 * only value on the stack: {@code serverbound} is a constant the transformer pushes, and the call consumes both
	 * slots and pushes one, so the stack shape after the seam is the one value it was before.
	 */
	public static Object install(Object codec, boolean serverbound) {
		if (codec == null || failed || Boolean.getBoolean(PROPERTY + ".off")) return codec;
		try {
			Method setter = setterOf(codec.getClass());
			if (setter == null) {
				if (SAID_NO_SETTER.compareAndSet(false, true)) {
					ForbricLog.info("[Forbric/Payloads] this codec (%s) does not carry fabric's "
							+ "fabric_setPacketCodecProvider, so a Fabric mod's own payload types cannot be resolved "
							+ "through it — the merge built no copy of the class fabric mixes", codec.getClass().getName());
				}
				return codec;
			}
			Object provider = provider(serverbound);
			if (provider == null) return codec;
			setter.invoke(codec, provider);
			if (SAID_INSTALLED.compareAndSet(false, true)) {
				ForbricLog.info("[Forbric/Payloads] the merged packet codec now asks fabric-networking-api-v1's "
						+ "payload registry for the ids vanilla's table does not carry — the two wrappers that would "
						+ "have installed it anchor on codec(FallbackProvider, List), and this base calls NeoForge's "
						+ "four-argument overload instead");
			}
			return codec;
		} catch (Throwable t) {
			failed = true;
			ForbricLog.warn("[Forbric/Payloads] could not give the custom-payload codec fabric's type provider; "
					+ "leaving it as the merge built it", t);
			return codec;
		}
	}

	/** The bridge's publishable state: whether anything was ever installed, and why not when it was not. */
	public static String describe() {
		return "no-setter=" + SAID_NO_SETTER.get() + ", installed=" + SAID_INSTALLED.get() + ", failed=" + failed;
	}

	private static Method setterOf(Class<?> codecClass) {
		for (Method method : codecClass.getMethods()) {
			if (SETTER.equals(method.getName()) && method.getParameterCount() == 1) {
				// The codec is an anonymous, package-private class inside the base's own package, so a public
				// method on it is still not invocable from here until reflection is told to allow it.
				method.setAccessible(true);
				return method;
			}
		}
		return null;
	}

	/**
	 * A provider that answers exactly the way fabric's two wrappers answer: the play registry for a registry-backed
	 * buffer, the configuration registry otherwise, and the flow of the packet class this codec belongs to.
	 */
	private static Object provider(boolean serverbound) throws ReflectiveOperationException {
		Class<?> providerType = Class.forName(PROVIDER);
		Class<?> buffer = Class.forName(REGISTRY_BUFFER);
		Method get = getter();
		Object play = registry(serverbound ? "PLAY_C2S" : "PLAY_S2C");
		Object configuration = registry(serverbound ? "CONFIGURATION_C2S" : "CONFIGURATION_S2C");
		if (get == null || play == null || configuration == null) return null;
		return Proxy.newProxyInstance(providerType.getClassLoader(), new Class<?>[] {providerType},
				(proxy, method, args) -> {
					if (!"get".equals(method.getName()) || args == null || args.length != 2) {
						return answerForObjectMethod(proxy, method, args);
					}
					Object registry = buffer.isInstance(args[0]) ? play : configuration;
					return get.invoke(registry, args[1]);
				});
	}

	private static Object answerForObjectMethod(Object proxy, Method method, Object[] args) {
		switch (method.getName()) {
			case "toString":
				return "forbric fabric payload type provider";
			case "hashCode":
				return System.identityHashCode(proxy);
			case "equals":
				return args != null && args.length == 1 && proxy == args[0];
			default:
				return null;
		}
	}

	private static Method getter() throws ReflectiveOperationException {
		Class<?> registries = Class.forName(REGISTRIES);
		Class<?> location = Class.forName("net.minecraft.resources.ResourceLocation");
		return registries.getMethod("get", location);
	}

	private static Object registry(String field) {
		try {
			Field handle = Class.forName(REGISTRIES).getField(field);
			return handle.get(null);
		} catch (ReflectiveOperationException | LinkageError e) {
			if (!failed) {
				failed = true;
				ForbricLog.warn("[Forbric/Payloads] fabric's payload registry %s is not reachable; leaving every "
						+ "codec as the merge built it", field, e);
			}
			return null;
		}
	}
}
