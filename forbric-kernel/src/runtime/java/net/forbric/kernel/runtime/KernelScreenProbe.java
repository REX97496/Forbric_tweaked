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
			Object renderables = field(screen, "renderables");
			if (renderables instanceof List<?> drawn) {
				line.append(" | renderables=").append(drawn.size()).append(':');
				for (Object each : drawn) line.append(' ').append(each.getClass().getSimpleName());
			}
			String text = line.toString();
			String signature = name + "|" + children.size() + "|" + text.length();
			if (!SEEN.add(signature)) return;
			LINES.incrementAndGet();
			say(text);
		} catch (Throwable unreadable) {
			say("unreadable " + unreadable);
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