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

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.CapabilityDispatcher;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityProvider;
import net.minecraftforge.common.capabilities.ICapabilityProviderImpl;

/**
 * The composed MinecraftForge capability provider for the three root types the merge put under NeoForge's
 * {@code AttachmentHolder} instead of Forge's {@code CapabilityProvider}. <b>This is the 1.21.1 edition</b> of
 * {@code src/runtime/java/net/forbric/kernel/runtime/KernelForgeCapabilities.java}; the 26.2 file stays the default
 * and is unchanged.
 *
 * <p><b>What is different here, and it is one thing:</b> this line's {@code CapabilityProvider} is the state carrier
 * itself and the roots extend it directly ({@code Entity extends CapabilityProvider<Entity>}, likewise {@code Level}
 * and {@code BlockEntity}), so the class the merge removed is {@code CapabilityProvider} for all three — one class,
 * not three. The per-root {@code CapabilityProvider$Entities/$BlockEntities/$Levels} subclasses do not exist on this
 * carrier, and neither do the two {@code protected abstract} hooks they exist to implement
 * ({@code fireAttachCapabilitiesEvent}, {@code shouldFireAttachCapabilitiesEvent}); {@code gatherCapabilities()} is
 * {@code protected final} here, so a subclass could not replace the gather even if there were a place to put it.
 *
 * <p><b>Where the per-root key went.</b> Into the {@code Class} argument. {@code CapabilityProvider.doGatherCapabilities}
 * reads its own {@code baseClass} field and calls
 * {@code ForgeEventFactory.gatherCapabilities(Class, getProvider(), parent)}, which builds
 * {@code new AttachCapabilitiesEvent<>(baseClass, provider)} and posts it on this line's single generic bus. So the
 * three delegates below differ in exactly one thing — the {@code Class} literal they hand to {@code super} — and that
 * is not a simplification, it is the whole distinction that remains. Two roots sharing one delegate would post a
 * {@code Level} to an {@code AttachCapabilitiesEvent<Entity>} and no listener would ever see it.
 *
 * <p><b>{@code Inert} is a state, not an override.</b> With {@code isLazy == false} — which
 * {@code AsField(Class, B)} always produces, because it reaches {@code CapabilityProvider(Class, false)} and
 * {@code isLazy = SUPPORTS_LAZY_CAPABILITIES && eager} — {@code getCapability} asks {@code getCapabilities()}, which
 * cannot gather, and {@code capabilities} stays {@code null} until {@code initInternal()} is called. So a provider
 * that is never initialised answers {@code LazyOptional.empty()} forever, saves nothing, and revives nothing; that is
 * the same end state 26.2 reaches by marking the provider initialised with a null dispatcher. The factory below is
 * therefore two functions, not two classes: {@link #create} initialises, {@link #inert} does not.
 *
 * <p>Everything else — gathering, dispatching, {@code LazyOptional} invalidation, NBT (de)serialisation — is Forge's
 * own {@code CapabilityProvider}/{@code CapabilityDispatcher} code. The transformer restores the constructor gather
 * boundary Forge had, so attach events keep that timing.
 *
 * <p>{@code -Dforbric.forgeCapabilities=off} switches off dispatch, not the composition the roots' merged definition
 * requires: their accessor then calls {@link #inert}, whose provider never fires {@code AttachCapabilitiesEvent}.
 */
public final class KernelForgeCapabilities {
	public static final String PROPERTY = "forbric.forgeCapabilities";

	private KernelForgeCapabilities() {
	}

	/**
	 * Exposes the one protected-final member a delegate on the owner needs, and forwards the per-root
	 * {@code Class} — the event key on this line — to {@code AsField}.
	 */
	abstract static class Composed extends CapabilityProvider.AsField {
		@SuppressWarnings({ "rawtypes", "unchecked" })
		Composed(Class<?> baseClass, Object owner) {
			super((Class) baseClass, (ICapabilityProviderImpl) owner);
		}

		public CapabilityDispatcher dispatcher() {
			return getCapabilities();
		}
	}

	/** {@code new AttachCapabilitiesEvent<>(Entity.class, owner)} — the discriminator is the literal, nowhere else. */
	@SuppressWarnings("rawtypes")
	static final class Entities extends Composed {
		Entities(Object owner) {
			super(Entity.class, owner);
		}
	}

	@SuppressWarnings("rawtypes")
	static final class BlockEntities extends Composed {
		BlockEntities(Object owner) {
			super(BlockEntity.class, owner);
		}
	}

	@SuppressWarnings("rawtypes")
	static final class Levels extends Composed {
		Levels(Object owner) {
			super(Level.class, owner);
		}
	}

	/**
	 * Dispatch off: the composed state, never initialised. There is no {@code shouldFireAttachCapabilitiesEvent()} on
	 * this line to answer false from, so the inert state is produced by not calling {@code initInternal()} at all.
	 */
	@SuppressWarnings("rawtypes")
	static final class Inert extends Composed {
		Inert(Class<?> baseClass, Object owner) {
			super(baseClass, owner);
		}
	}

	// ---- the accessor's one branch, held here so the synthesised forbric$caps() has none

	@SuppressWarnings("rawtypes")
	public static CapabilityProvider.AsField entity(CapabilityProvider.AsField existing, Object owner) {
		return existing != null ? existing : create(new Entities(owner));
	}

	@SuppressWarnings("rawtypes")
	public static CapabilityProvider.AsField blockEntity(CapabilityProvider.AsField existing, Object owner) {
		return existing != null ? existing : create(new BlockEntities(owner));
	}

	@SuppressWarnings("rawtypes")
	public static CapabilityProvider.AsField level(CapabilityProvider.AsField existing, Object owner) {
		return existing != null ? existing : create(new Levels(owner));
	}

	/** The accessor's factory for every root when {@code -Dforbric.forgeCapabilities=off}: never gathers. */
	@SuppressWarnings("rawtypes")
	public static CapabilityProvider.AsField inert(CapabilityProvider.AsField existing, Object owner) {
		if (existing != null) return existing;
		Class<?> baseClass = owner instanceof BlockEntity ? BlockEntity.class
				: owner instanceof Level ? Level.class : Entity.class;
		return new Inert(baseClass, owner);
	}

	/** Dispatch on: Forge's own eager gather, through the only public entry point this line offers ({@code 3} insns). */
	@SuppressWarnings("rawtypes")
	private static CapabilityProvider.AsField create(CapabilityProvider.AsField field) {
		field.initInternal();
		return field;
	}

	// ---- read-without-creating helpers: invalidating or saving a never-gathered provider is a no-op on Forge too

	@SuppressWarnings("rawtypes")
	public static void invalidate(CapabilityProvider.AsField field) {
		if (field != null) field.invalidateCaps();
	}

	@SuppressWarnings("rawtypes")
	public static void revive(CapabilityProvider.AsField field) {
		if (field != null) field.reviveCaps();
	}

	@SuppressWarnings("rawtypes")
	public static CapabilityDispatcher dispatcher(CapabilityProvider.AsField field) {
		return field == null ? null : ((Composed) field).dispatcher();
	}

	@SuppressWarnings("rawtypes")
	public static CompoundTag serialize(CapabilityProvider.AsField field, HolderLookup.Provider lookup) {
		return field == null ? null : field.serializeInternal(lookup);
	}

	// ---- the CompoundTag save/load seam: this line has no ValueInput/ValueOutput at all

	/** Forge's own save shape: {@code tag.put("ForgeCaps", serializeCaps(lookup))} when the dispatcher exists. */
	@SuppressWarnings("rawtypes")
	public static void saveBlockEntity(CapabilityProvider.AsField field, CompoundTag tag, HolderLookup.Provider lookup) {
		if (field == null) return;
		try {
			CompoundTag caps = field.serializeInternal(lookup);
			if (caps != null) tag.put("ForgeCaps", caps);
		} catch (Throwable t) {
			reportOnce("saving a block entity's MinecraftForge capabilities", t);
		}
	}

	/** {@code Entity.saveWithoutId} carries no registry lookup of its own; Forge reads it from the entity. */
	@SuppressWarnings("rawtypes")
	public static void saveEntity(CapabilityProvider.AsField field, CompoundTag tag, Object owner) {
		if (field == null) return;
		try {
			CompoundTag caps = field.serializeInternal(((Entity) owner).registryAccess());
			if (caps != null) tag.put("ForgeCaps", caps);
		} catch (Throwable t) {
			reportOnce("saving an entity's MinecraftForge capabilities", t);
		}
	}

	@SuppressWarnings("rawtypes")
	public static void loadBlockEntity(CapabilityProvider.AsField field, CompoundTag tag, HolderLookup.Provider lookup) {
		try {
			field.deserializeInternal(lookup, tag);
		} catch (Throwable t) {
			reportOnce("loading MinecraftForge capabilities from ForgeCaps", t);
		}
	}

	@SuppressWarnings("rawtypes")
	public static void loadEntity(CapabilityProvider.AsField field, CompoundTag tag, Object owner) {
		try {
			field.deserializeInternal(((Entity) owner).registryAccess(), tag);
		} catch (Throwable t) {
			reportOnce("loading MinecraftForge capabilities from ForgeCaps", t);
		}
	}

	/** The registry access a level-less block entity's own merged save path uses. */
	@SuppressWarnings("unused")
	private static HolderLookup.Provider lookupOf(Object blockEntity) {
		Level level = blockEntity instanceof BlockEntity be ? be.getLevel() : null;
		return level != null ? level.registryAccess() : RegistryAccess.EMPTY;
	}

	private static volatile boolean reported;

	private static void reportOnce(String what, Throwable t) {
		if (reported) return;
		reported = true;
		ForbricLog.warn("[Forbric/Capabilities] " + what + " threw — that data is skipped; later failures of this "
				+ "kind are not repeated", Reflect.unwrap(t));
	}

	// ---- registration stage

	/**
	 * Forge's own {@code INJECT_CAPABILITIES} stage. On this line it is an instance API —
	 * {@code CapabilityManager.injectCapabilities(ModList)} — and so is {@code getAllScanData()} (the same change
	 * stage 2 made in {@code KernelForgeDeclarationReaders}). Advisory on this base: {@code isRegistered()} is read
	 * only by Forge's own manager, so the count is returned rather than acted on.
	 */
	public static int injectCapabilities() {
		CapabilityManager.injectCapabilities(net.minecraftforge.fml.ModList.get());
		int annotated = 0;
		try {
			for (net.minecraftforge.forgespi.language.ModFileScanData scan
					: net.minecraftforge.fml.ModList.get().getAllScanData()) {
				for (net.minecraftforge.forgespi.language.ModFileScanData.AnnotationData annotation : scan.getAnnotations()) {
					if ("Lnet/minecraftforge/common/capabilities/AutoRegisterCapability;".equals(annotation.annotationType().getDescriptor())) annotated++;
				}
			}
		} catch (Throwable t) {
			return -1;
		}
		return annotated;
	}
}
