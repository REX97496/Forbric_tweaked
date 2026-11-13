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

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.forbric.kernel.util.ForbricLog;

/**
 * Lists the widgets a container screen ended up with, so a widget that exists in the heap can be told apart from a
 * widget that was never added and from a widget that is added and drawn somewhere invisible.
 *
 * <p>The curios button is the case that motivated this. Its object is created - a heap histogram shows exactly one
 * {@code CuriosButton} after the inventory opens - and the screen-init event that is supposed to add it is posted
 * with a working add consumer, yet nothing appears where the offset arithmetic says it should. Class counts cannot
 * answer that: they do not say whether the widget reached {@code children}, and they do not say where it thinks it
 * is. This reads both back from the live screen right after {@code Screen.init(Minecraft,int,int)} returns, which
 * is after {@code ScreenEvent.Init.Post} has run.
 *
 * <p>It only prints container screens (inventory, creative inventory, chests), once per screen class plus widget
 * count, and only while {@code -Dforbric.probeScreens=true}; nothing here runs in a shipped client.
 */
public final class KernelScreenProbe {
	private static final String PREFIX = "[Forbric/Probe] screen";
	private static final int LIMIT = 40;
	private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
	private static final AtomicInteger LINES = new AtomicInteger();
	/** One synthetic click per process: a second inventory open must not click again. */
	private static final java.util.concurrent.atomic.AtomicBoolean CLICKED = new java.util.concurrent.atomic.AtomicBoolean();

	private KernelScreenProbe() {
	}

	/** Called before every return of {@code Screen.init(Minecraft,int,int)}; never throws into the client. */
	public static void dump(net.minecraft.client.gui.screens.Screen screen) {
		if (screen == null) return;
		try {
			String name = screen.getClass().getName();
			if (!isContainer(screen)) return;
			if (LINES.get() >= LIMIT) return;
			@SuppressWarnings("unchecked")
			List<Object> children = (List<Object>) screen.getClass().getMethod("children").invoke(screen);
			StringBuilder line = new StringBuilder("children of ").append(name).append(" = ")
					.append(children.size()).append(':');
			for (Object child : children) {
				line.append(' ').append(describe(child));
			}
line.append(" | guiLeft=").append(call(screen, "getGuiLeft")).append(" guiTop=").append(call(screen, "getGuiTop"));
			line.append(" | menu=").append(describeMenu(screen));
			if (name.startsWith("top.theillusivec4.curios")) line.append(" | ").append(curiosState());
			Object renderables = field(screen, "renderables");
			if (renderables instanceof List<?> drawn) {
				line.append(" | renderables=").append(drawn.size()).append(':');
				for (Object each : drawn) line.append(' ').append(each.getClass().getSimpleName());
			}
			String text = line.toString();
			String signature = name + "|" + children.size() + "|" + text.length();
			if (SEEN.add(signature)) {
				LINES.incrementAndGet();
				say(text);
			}
			clickNamedChild(screen, children);
		} catch (Throwable unreadable) {
			say("unreadable " + unreadable);
		}
	}

	/**
	 * Clicks the widget whose class name contains {@code -Dforbric.probeClick}, and says what happened.
	 *
	 * <p>A synthetic mouse click at absolute screen coordinates turned out to be unusable on this machine: the desktop
	 * is 3840x2160 at 200% while the game's framebuffer is 854x480 inside a 502x311 window, so every hand-computed
	 * pixel lands somewhere else, and focusing the window to make the click land pauses singleplayer. Clicking the
	 * widget directly answers the question the pixels were for - does this button open anything - without either
	 * problem: the widget's own {@code mouseClicked}, at its own centre, once per process.
	 */
	private static void clickNamedChild(net.minecraft.client.gui.screens.Screen screen, List<Object> children) {
		String wanted = System.getProperty("forbric.probeClick");
		if (wanted == null || wanted.isBlank() || CLICKED.get()) return;
		for (Object child : children) {
			if (!child.getClass().getSimpleName().contains(wanted)) continue;
			if (!CLICKED.compareAndSet(false, true)) return;
			Integer x = call(child, "getX");
			Integer y = call(child, "getY");
			Integer w = call(child, "getWidth");
			Integer h = call(child, "getHeight");
			double at = (x == null ? 0 : x) + (w == null ? 0 : w) / 2.0;
			double ay = (y == null ? 0 : y) + (h == null ? 0 : h) / 2.0;
			Object accepted = "?";
			try {
				Method click = child.getClass().getMethod("mouseClicked", double.class, double.class, int.class);
				click.setAccessible(true);
				accepted = String.valueOf(click.invoke(child, at, ay, 0));
			} catch (Throwable refused) {
				accepted = refused.getClass().getSimpleName() + ": " + refused.getMessage();
			}
			say("clicked " + child.getClass().getSimpleName() + " at (" + at + "," + ay + ") -> " + accepted
					+ "; the screen now is " + screenNow(screen));
			return;
		}
	}

	/**
	 * What the screen's menu actually holds: the container class, how many slots it has, and what the first few are.
	 *
	 * <p>Motivated by curios: its screen opens now, and the panel beside the inventory stays empty. "The datapack was
	 * never loaded" and "the container has no slots" look identical on screen, and only the slot list separates them.
	 */
	private static String describeMenu(Object screen) {
		Object menu = field(screen, "menu");
		if (menu == null) return "none";
		Object slots = field(menu, "slots");
		if (!(slots instanceof List<?> list)) return menu.getClass().getName() + " slots=unreadable";
		StringBuilder line = new StringBuilder(menu.getClass().getName()).append(" slots=").append(list.size());
		int shown = 0;
		for (Object slot : list) {
			if (shown++ >= 12) break;
			line.append(' ').append(slot == null ? "null" : slot.getClass().getSimpleName());
		}
		return line.toString();
	}

	/**
	 * Why curios' panel is empty: the slot manager, the entity manager and the player's inventory capability, all three
	 * at the moment the screen opens.
	 *
	 * <p>curios' inventory capability is a NeoForge {@code EntityCapability} whose provider returns null unless
	 * {@code CuriosApi.getEntitySlots(entity)} is non-empty, and that map comes from the client's
	 * {@code CuriosEntityManager}, which is filled from the login sync packet. So an empty panel can be "no slot
	 * definitions", "no entity→slot mapping" or "no capability instance", and the screen alone cannot tell them apart.
	 * Each is printed separately, and a failure in one is not a failure of the others.
	 */
	private static String curiosState() {
		StringBuilder line = new StringBuilder();
		line.append("curiosSlots=").append(managerSize("top.theillusivec4.curios.common.data.CuriosSlotManager"));
		line.append(" curiosEntities=").append(entitySlots());
		line.append(" curiosInventory=").append(inventory());
		return line.toString();
	}

	/** A curios {@code *Manager}'s CLIENT and SERVER slot counts, as {@code client/server}. */
	private static String managerSize(String className) {
		try {
			Class<?> type = Class.forName(className, false, KernelScreenProbe.class.getClassLoader());
			return size(type, "CLIENT") + "/" + size(type, "SERVER");
		} catch (Throwable absent) {
			return "?";
		}
	}

	private static String size(Class<?> type, String fieldName) {
		try {
			java.lang.reflect.Field manager = type.getField(fieldName);
			Object instance = manager.get(null);
			if (instance == null) return "-";
			Object slots = instance.getClass().getMethod("getSlots").invoke(instance);
			return slots instanceof java.util.Map<?, ?> map ? Integer.toString(map.size()) : "?";
		} catch (Throwable absent) {
			return "?";
		}
	}

	/** How many slots the client's entity manager gives the local player's type: {@code client/server}. */
	private static String entitySlots() {
		Object player = localPlayer();
		if (player == null) return "no-player";
		try {
			Object entityType = player.getClass().getMethod("getType").invoke(player);
			Class<?> manager = Class.forName("top.theillusivec4.curios.common.data.CuriosEntityManager", false,
					KernelScreenProbe.class.getClassLoader());
			java.lang.reflect.Method slots = null;
			for (java.lang.reflect.Method candidate : manager.getMethods()) {
				Class<?>[] parameters = candidate.getParameterTypes();
				if ("getEntitySlots".equals(candidate.getName()) && parameters.length == 1
						&& parameters[0].isInstance(entityType)) {
					slots = candidate;
				}
			}
			if (slots == null) return "no-ask";
			StringBuilder line = new StringBuilder();
			for (String side : new String[] {"CLIENT", "SERVER"}) {
				Object instance = manager.getField(side).get(null);
				if (instance == null) {
					line.append('-');
				} else {
					Object answer = slots.invoke(instance, entityType);
					line.append(answer instanceof java.util.Map<?, ?> map ? map.size() : "?");
				}
				if ("CLIENT".equals(side)) line.append('/');
			}
			return line.toString();
		} catch (Throwable absent) {
			return absent.getClass().getSimpleName();
		}
	}

	/** Whether {@code CuriosApi.getCuriosInventory(player)} answers, and what it is when it does. */
	private static String inventory() {
		Object player = localPlayer();
		if (player == null) return "no-player";
		try {
			ClassLoader loader = KernelScreenProbe.class.getClassLoader();
			Class<?> capability = Class.forName("top.theillusivec4.curios.api.CuriosCapability", false, loader);
			Object inventoryCapability = capability.getField("INVENTORY").get(null);
			java.lang.reflect.Method ask = null;
			for (java.lang.reflect.Method candidate : player.getClass().getMethods()) {
				if (!"getCapability".equals(candidate.getName())) continue;
				Class<?>[] parameters = candidate.getParameterTypes();
				if (parameters.length == 1 && parameters[0].isInstance(inventoryCapability)) ask = candidate;
			}
			if (ask == null) return "no-ask";
			Object handler = ask.invoke(player, inventoryCapability);
			if (handler == null) return "null";
			Object slots = handler.getClass().getMethod("getSlots").invoke(handler);
			return slots + " " + handler.getClass().getSimpleName();
		} catch (Throwable absent) {
			return absent.getClass().getSimpleName() + ": " + absent.getMessage();
		}
	}

	/** The client's own player, or null outside a world. */
	private static Object localPlayer() {
		try {
			Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false,
					KernelScreenProbe.class.getClassLoader());
			Object instance = minecraft.getMethod("getInstance").invoke(null);
			if (instance == null) return null;
			return field(instance, "player");
		} catch (Throwable absent) {
			return null;
		}
	}

	private static String screenNow(net.minecraft.client.gui.screens.Screen screen) {		Object minecraft = field(screen, "minecraft");
		if (minecraft == null) return "?";
		try {
			Object shown = minecraft.getClass().getMethod("getScreen").invoke(minecraft);
			return shown == null ? "null" : shown.getClass().getName();
		} catch (Throwable absent) {
			return "?";
		}
	}

	private static boolean isContainer(Object screen) {
		for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
			String name = type.getName();
			if ("net.minecraft.client.gui.screens.inventory.AbstractContainerScreen".equals(name)) return true;
		}
		return false;
	}

	private static String describe(Object widget) {
		String simple = widget.getClass().getSimpleName();
		Integer x = call(widget, "getX");
		Integer y = call(widget, "getY");
		Integer w = call(widget, "getWidth");
		Integer h = call(widget, "getHeight");
		if (x == null) return simple;
		// A widget that reached the list and sits inside the panel can still be invisible: AbstractWidget decides
		// whether anything is drawn from its own visible flag, and a mod that forgets to set it renders nothing
		// while every other measurement looks correct.
		Object visible = field(widget, "visible");
		String flag = Boolean.FALSE.equals(visible) ? " INVISIBLE" : "";
		Object active = field(widget, "active");
		if (Boolean.FALSE.equals(active)) flag += " INACTIVE";
		return simple + "(" + x + "," + y + "," + w + "x" + h + ")" + flag;
	}

	private static Object field(Object target, String name) {
		for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
			try {
				var declared = type.getDeclaredField(name);
				declared.setAccessible(true);
				return declared.get(target);
			} catch (Throwable absent) {
				// Try the parent.
			}
		}
		return null;
	}

	private static Integer call(Object target, String name) {
		try {
			Method method = target.getClass().getMethod(name);
			Object value = method.invoke(target);
			return value instanceof Integer number ? number : null;
		} catch (Throwable absent) {
			return null;
		}
	}

	/** Same sink as the key probe: a file when one is named, the log otherwise. */
	private static void say(String line) {
		String path = System.getProperty("forbric.probeFile");
		String text = PREFIX + " " + line;
		if (path != null && !path.isBlank()) {
			try {
				java.nio.file.Files.writeString(java.nio.file.Path.of(path), text + System.lineSeparator(),
						java.nio.charset.StandardCharsets.UTF_8,
						java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
				return;
			} catch (Throwable unwritable) {
				// Fall through to the log.
			}
		}
		ForbricLog.info(text);
	}
}