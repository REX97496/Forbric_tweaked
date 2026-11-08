/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;

/**
 * Routes vanilla's own default stream lookup through a Fabric sound instance's own override.
 *
 * <h2>Measured failure</h2>
 *
 * <p>{@code SoundInstance.getStream(SoundBufferLibrary, Sound, boolean)} is a default method on the interface. Vanilla
 * answers it with {@code library.getStream(sound.getPath(), looping)} and never asks the instance anything else, so a
 * mod that overrides Fabric's {@code FabricSoundInstance.getAudioStream(...)} has its audio ignored: the mod's sound
 * plays the vanilla file, or plays nothing when the mod never shipped one.
 *
 * <p>Fabric API closes this with {@code fabric-sound-api-v1.mixins.json}:SoundInstanceMixin (adds the interface) plus
 * SoundSystemMixin (calls it). On the merged 1.21.1 base the SoundSystem half resolves no anchor and is suppressed —
 * measured as {@code auto-suppressing guest mixin fabric-sound-api-v1 … SoundSystemMixin — UNFIT on the merged base
 * (no anchor resolves (@Inject target SoundEngine.play …))} — which leaves the interface added and the method it
 * promises never consulted.
 *
 * <h2>What this does</h2>
 *
 * <p>Asks the instance first and falls back to vanilla's own resolution when it has nothing to say. The interface is
 * looked up reflectively because the runtime compiles without Fabric API on its classpath, and the lookup is cached:
 * this method runs once per sound start.
 */
public final class KernelSounds {

	/** Fabric's client sound extension point; the only thing this class needs from that jar. */
	private static final String API = "net.fabricmc.fabric.api.client.sound.v1.FabricSoundInstance";

	private static volatile Method audioStream;
	private static volatile boolean looked;

	private KernelSounds() {}

	/**
	 * What {@code SoundInstance.getStream} should answer once the Fabric contract exists on this line.
	 *
	 * @param self     the instance whose override is being asked
	 * @param library  the buffer library vanilla would have used
	 * @param sound    the sound vanilla would have streamed
	 * @param looping  whether the sound loops
	 * @return the instance's own stream when it supplies one, otherwise vanilla's
	 */
	public static CompletableFuture<AudioStream> stream(SoundInstance self, SoundBufferLibrary library, Sound sound,
			boolean looping) {
		Method override = audioStream(self);
		if (override != null) {
			try {
				@SuppressWarnings("unchecked")
				CompletableFuture<AudioStream> supplied =
						(CompletableFuture<AudioStream>) override.invoke(self, library, sound.getPath(), looping);
				// Fabric's own default answers an empty future rather than null, so a null here is a broken override.
				if (supplied != null) return supplied;
			} catch (Throwable unreadable) {
				// Fall through: a mod's broken override must not silence every sound in the game.
			}
		}
		return library.getStream(sound.getPath(), looping);
	}

	/** The Fabric override, or null when this line has no such interface or no instance implements it. */
	private static Method audioStream(Object self) {
		if (looked) return audioStream;
		looked = true;
		try {
			Class<?> api = Class.forName(API, false, self.getClass().getClassLoader());
			if (!api.isInstance(self)) return null;
			audioStream = api.getMethod("getAudioStream", SoundBufferLibrary.class, Identifier.class, boolean.class);
		} catch (Throwable absent) {
			return null;
		}
		return audioStream;
	}
}
