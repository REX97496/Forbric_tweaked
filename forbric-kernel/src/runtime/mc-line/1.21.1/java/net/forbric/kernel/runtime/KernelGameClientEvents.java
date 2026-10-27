/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.event.ForgeEventFactoryClient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderBlockScreenEffectEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * MinecraftForge's client events the merged client posts only NeoForge's version of: chat, input, fog, field of view,
 * block overlays, the boss bar and screen drawing.
 *
 * <p>Each merged producer posts NeoForge's event and reads it back; no MinecraftForge hook is called, so Xaero's
 * shared-waypoint chat links went to the server as text, InventoryHUD's and packedup's hotkeys did nothing, Tombstone's
 * fog, overlay and field-of-view effects never applied, and Xaero's minimap never moved below the boss bars. Where a
 * MinecraftForge hook is a pure emitter it is called; where it is not — fog and fog colour run MinecraftForge's own
 * fluid fog first, the FOV hook recomputes from scratch, the screen hook draws the screen — the event is built and
 * posted directly. Answers come back one way, as in every bridge here.
 *
 * <p>Its own class: it names {@code net.neoforged.neoforge.client.event}, which a dedicated server must never resolve.
 */
public final class KernelGameClientEvents {
	private KernelGameClientEvents() {
	}

	/**
	 * Chat received. One listener on NeoForge's base event, branching on its kind: NeoForge's subclasses reach a base
	 * listener too, so three listeners would ask MinecraftForge twice. System messages (and the action bar) go to
	 * MinecraftForge's system-message event, as they do natively, not to its chat event.
	 */
	public static void installChatReceived(Object neoBus) {
		KernelGameEntityEvents.subscribe((IEventBus) neoBus, ClientChatReceivedEvent.class, "ClientChatReceivedEvent",
				"a MinecraftForge mod never sees chat arrive (waypoint links, chat commands) and cannot change or hide it",
				event -> {
					Component forge;
					if (event instanceof ClientChatReceivedEvent.System system) {
						forge = ForgeHooksClient.onClientSystemMessage(event.getMessage(), system.isOverlay());
					} else if (event instanceof ClientChatReceivedEvent.Player player) {
						forge = ForgeHooksClient.onClientPlayerChat(event.getBoundChatType(), event.getMessage(),
								player.getPlayerChatMessage(), event.getSender());
					} else {
						forge = ForgeHooksClient.onClientChat(event.getBoundChatType(), event.getMessage(), event.getSender());
					}
					if (forge == null) return true;
					if (forge != event.getMessage()) event.setMessage(forge);
					return false;
				});
	}

	/** Chat about to be sent: MinecraftForge's hook answers "" for a cancel. */
	public static void installChatSend(Object neoBus) {
		KernelGameEntityEvents.subscribe((IEventBus) neoBus, ClientChatEvent.class, "ClientChatEvent",
				"a MinecraftForge mod cannot see, change or keep back what the player sends",
				event -> {
					String forge = ForgeHooksClient.onClientSendMessage(event.getMessage());
					if (forge.isEmpty()) return true;
					if (!forge.equals(event.getMessage())) event.setMessage(forge);
					return false;
				});
	}

	public static void installKey(Object neoBus) {
		KernelGameClientNetworkEvents.forward((IEventBus) neoBus, InputEvent.Key.class, "InputEvent.Key",
				event -> // 1.21.1 has no InputEvent$Key#getKeyEvent(): the packed KeyEvent arrived in 26.2 while both lines keep
				// the raw key/scanCode/action/modifiers ints, and ForgeHooksClient.onKeyInput here takes those four.
				ForgeHooksClient.onKeyInput(event.getKey(), event.getScanCode(), event.getAction(), event.getModifiers()));
	}

	/** Screen.init/rebuildWidgets only posts NeoForge's events on the merged base. Share the live widget callbacks. */
	public static void installScreenInitPre(Object neoBus) {
		KernelGameEntityEvents.subscribe((IEventBus) neoBus, ScreenEvent.Init.Pre.class, "ScreenEvent.Init.Pre",
				"a MinecraftForge mod cannot customize or cancel screen initialization",
				event -> net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
						new net.minecraftforge.client.event.ScreenEvent.Init.Pre(event.getScreen(), event.getListenersList(),
								event::addListener, event::removeListener)));
	}

	/** IPN installs its inventory buttons here; receiving ticks and render events alone never creates them. */
	public static void installScreenInitPost(Object neoBus) {
		KernelGameClientNetworkEvents.forward((IEventBus) neoBus, ScreenEvent.Init.Post.class, "ScreenEvent.Init.Post",
				event -> net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
						new net.minecraftforge.client.event.ScreenEvent.Init.Post(event.getScreen(), event.getListenersList(),
								event::addListener, event::removeListener)));
	}

	/** Key events inside a screen are separate from InputEvent.Key, which libIPN only consumes outside screens. */
	public static void installScreenKeyPressedPre(Object neoBus) {
		KernelGameEntityEvents.subscribe((IEventBus) neoBus, ScreenEvent.KeyPressed.Pre.class, "ScreenEvent.KeyPressed.Pre",
				"a MinecraftForge inventory hotkey cannot see or consume a key press",
				event -> // 1.21.1's onScreenKey*Pre/Post take (Screen, keyCode, scanCode, modifiers) -- three ints, no action,
				// because 'Pressed'/'Released' is already in the method name. 26.2 replaced them with a KeyEvent.
				ForgeEventFactoryClient.onScreenKeyPressedPre(event.getScreen(), event.getKeyCode(), event.getScanCode(), event.getModifiers()));
	}

	public static void installScreenKeyPressedPost(Object neoBus) {
		KernelGameEntityEvents.subscribe((IEventBus) neoBus, ScreenEvent.KeyPressed.Post.class, "ScreenEvent.KeyPressed.Post",
				"a MinecraftForge inventory hotkey cannot consume an otherwise unhandled key press",
				event -> // 1.21.1's onScreenKey*Pre/Post take (Screen, keyCode, scanCode, modifiers) -- three ints, no action,
				// because 'Pressed'/'Released' is already in the method name. 26.2 replaced them with a KeyEvent.
				ForgeEventFactoryClient.onScreenKeyPressedPost(event.getScreen(), event.getKeyCode(), event.getScanCode(), event.getModifiers()));
	}

	public static void installScreenKeyReleasedPre(Object neoBus) {
		KernelGameEntityEvents.subscribe((IEventBus) neoBus, ScreenEvent.KeyReleased.Pre.class, "ScreenEvent.KeyReleased.Pre",
				"a MinecraftForge inventory hotkey stays held because its release is missing",
				event -> // 1.21.1's onScreenKey*Pre/Post take (Screen, keyCode, scanCode, modifiers) -- three ints, no action,
				// because 'Pressed'/'Released' is already in the method name. 26.2 replaced them with a KeyEvent.
				ForgeEventFactoryClient.onScreenKeyReleasedPre(event.getScreen(), event.getKeyCode(), event.getScanCode(), event.getModifiers()));
	}

	public static void installScreenKeyReleasedPost(Object neoBus) {
		KernelGameEntityEvents.subscribe((IEventBus) neoBus, ScreenEvent.KeyReleased.Post.class, "ScreenEvent.KeyReleased.Post",
				"a MinecraftForge inventory hotkey cannot see an otherwise unhandled key release",
				event -> // 1.21.1's onScreenKey*Pre/Post take (Screen, keyCode, scanCode, modifiers) -- three ints, no action,
				// because 'Pressed'/'Released' is already in the method name. 26.2 replaced them with a KeyEvent.
				ForgeEventFactoryClient.onScreenKeyReleasedPost(event.getScreen(), event.getKeyCode(), event.getScanCode(), event.getModifiers()));
	}

	/** Only the Pre half: the merged mouse handler reaches Post after a screen took the click, which MinecraftForge did not. */
	public static void installMouseButtonPre(Object neoBus) {
		KernelGameEntityEvents.subscribe((IEventBus) neoBus, InputEvent.MouseButton.Pre.class, "InputEvent.MouseButton.Pre",
				"a MinecraftForge mod never sees a mouse button outside a screen, and cannot take the click",
				event -> // 1.21.1's onMouseButtonPre takes (button, action, modifiers); 26.2 packs the first two into a
				// MouseButtonInfo. InputEvent$MouseButton still exposes the raw ints on this line.
				ForgeEventFactoryClient.onMouseButtonPre(event.getButton(), event.getAction(), event.getModifiers()));
	}

	/** Attack, use and pick-block keys: built directly, since MinecraftForge's own builder is inline in the lost call sites. */
	public static void installInteractionKey(Object neoBus) {
		KernelGameEntityEvents.subscribe((IEventBus) neoBus, InputEvent.InteractionKeyMappingTriggered.class,
				"InputEvent.InteractionKeyMappingTriggered",
				"a MinecraftForge mod cannot intercept attacking, using or picking a block",
				event -> {
					int button = event.isAttack() ? 0 : event.isUseItem() ? 1 : 2;
					var forge = new net.minecraftforge.client.event.InputEvent.InteractionKeyMappingTriggered(button,
							event.getKeyMapping(), event.getHand());
					forge.setSwingHand(event.shouldSwingHand());
					boolean cancelled = net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(forge);
					if (forge.shouldSwingHand() != event.shouldSwingHand()) event.setSwingHand(forge.shouldSwingHand());
					return cancelled;
				});
	}

	/**
	 * Fog. MinecraftForge's own setupFog would apply its fluid fog again on top of NeoForge's, so the event is posted
	 * directly, on NeoForge's fog data (shared, so changes land), with MinecraftForge's real-time partial tick.
	 */
	public static void installRenderFog(Object neoBus) {
		KernelGameClientNetworkEvents.forward((IEventBus) neoBus, ViewportEvent.RenderFog.class, "ViewportEvent.RenderFog",
				event -> {
// 26.2 packs fog into a FogData record and takes (type, camera, partialTick, data, colour). 1.21.1
					// splits it: the Forge event takes (mode, type, camera, near, far, partialTick, shape), and the
					// colour is a separate ComputeFogColor event which installFogColor already forwards. Same
					// question asked of listeners, assembled from the members this line actually has.
					var forge = new net.minecraftforge.client.event.ViewportEvent.RenderFog(event.getMode(), event.getType(),
							event.getCamera(), event.getNearPlaneDistance(), event.getFarPlaneDistance(),
							Minecraft.getInstance().getTimer().getRealtimeDeltaTicks(), event.getFogShape());
					net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(forge);
				});
	}

	/** Fog colour, posted directly for the same reason as fog; a changed channel is written back. */
	public static void installFogColor(Object neoBus) {
		KernelGameClientNetworkEvents.forward((IEventBus) neoBus, ViewportEvent.ComputeFogColor.class, "ViewportEvent.ComputeFogColor",
				event -> {
					var forge = new net.minecraftforge.client.event.ViewportEvent.ComputeFogColor(event.getCamera(),
							(float) event.getPartialTick(), event.getRed(), event.getGreen(), event.getBlue());
					net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(forge);
					if (forge.getRed() != event.getRed()) event.setRed(forge.getRed());
					if (forge.getGreen() != event.getGreen()) event.setGreen(forge.getGreen());
					if (forge.getBlue() != event.getBlue()) event.setBlue(forge.getBlue());
				});
	}

	/** The FOV modifier, starting from NeoForge's answer rather than MinecraftForge's recomputation. */
	public static void installFovModifier(Object neoBus) {
		KernelGameClientNetworkEvents.forward((IEventBus) neoBus, ComputeFovModifierEvent.class, "ComputeFovModifierEvent",
				event -> {
// 1.21.1's ComputeFovModifierEvent takes (player, fovModifier) and has no scale: getFovScale() is 26.x.
					// The scale is therefore not carried, and a Forge listener cannot read it -- but that event has
					// no accessor for it on this line either, so nothing that runs here loses anything it could use.
					var forge = new net.minecraftforge.client.event.ComputeFovModifierEvent(event.getPlayer(), event.getFovModifier());
					forge.setNewFovModifier(event.getNewFovModifier());
					net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(forge);
					if (forge.getNewFovModifier() != event.getNewFovModifier()) event.setNewFovModifier(forge.getNewFovModifier());
				});
	}

	/** In-block, water and fire overlays: MinecraftForge's hook is a pure emitter, and the overlay kinds share names. */
	public static void installBlockOverlay(Object neoBus) {
		KernelGameEntityEvents.subscribe((IEventBus) neoBus, RenderBlockScreenEffectEvent.class, "RenderBlockScreenEffectEvent",
				"a MinecraftForge mod cannot hide the in-block, water or fire overlay",
				event -> ForgeHooksClient.renderBlockOverlay(event.getPlayer(), event.getPoseStack(),
						net.minecraftforge.client.event.RenderBlockScreenEffectEvent.OverlayType.valueOf(event.getOverlayType().name()),
						event.getBlockState(), event.getBlockPos()));
	}

	/** One boss bar: a cancel hides it, a changed increment moves the next one. */
	public static void installBossEventProgress(Object neoBus) {
		KernelGameEntityEvents.subscribe((IEventBus) neoBus, CustomizeGuiOverlayEvent.BossEventProgress.class,
				"CustomizeGuiOverlayEvent.BossEventProgress",
				"a MinecraftForge mod cannot move or hide a boss bar, so HUD elements placed below them overlap",
				event -> {
					var forge = ForgeHooksClient.onCustomizeBossEventProgress(event.getGuiGraphics(), event.getWindow(),
							event.getBossEvent(), event.getX(), event.getY(), event.getIncrement());
					if (forge == null) return true;
					if (forge.getIncrement() != event.getIncrement()) event.setIncrement(forge.getIncrement());
					return false;
				});
	}

	/** Before a screen draws: posted directly, since MinecraftForge's own path draws the screen itself. */
	public static void installScreenRenderPre(Object neoBus) {
		KernelGameEntityEvents.subscribe((IEventBus) neoBus, ScreenEvent.Render.Pre.class, "ScreenEvent.Render.Pre",
				"a MinecraftForge mod cannot draw under a screen or stop it drawing",
				event -> net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
						new net.minecraftforge.client.event.ScreenEvent.Render.Pre(event.getScreen(), event.getGuiGraphics(),
								event.getMouseX(), event.getMouseY(), event.getPartialTick())));
	}

	/** After a screen draws: MinecraftForge mods draw their overlays on it here. */
	public static void installScreenRenderPost(Object neoBus) {
		KernelGameClientNetworkEvents.forward((IEventBus) neoBus, ScreenEvent.Render.Post.class, "ScreenEvent.Render.Post",
				event -> net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
						new net.minecraftforge.client.event.ScreenEvent.Render.Post(event.getScreen(), event.getGuiGraphics(),
								event.getMouseX(), event.getMouseY(), event.getPartialTick())));
	}
}
