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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.forbric.kernel.util.ForbricLog;

/**
 * Reports what the client's key handler is actually given, so a key that "does nothing" can be told apart from a
 * key that never arrived.
 *
 * <p>F3 was measured to produce no debug overlay on the 1.21.1 line while F2 in the same burst wrote a screenshot.
 * Both are handled inside {@code KeyboardHandler.keyPress}, and by the bytecode that method can only take the
 * screenshot branch and skip the debug-toggle branch when a screen is open (the screenshot bind is checked before
 * the {@code screen == null} gate). The game was then measured with no {@code Screen} instance alive at all - the
 * histogram held none - and F3 still did nothing. Two candidates survive that: the toggle branch is never reached,
 * or it is reached with {@code handledDebugKey} stuck true, in which case the first press only clears the flag.
 *
 * <p>Pixels cannot separate those two; state can. This records, once per distinct combination, the key, the
 * action, the screen in hand and the flag, from the handler instance itself - read reflectively so that neither
 * the field names nor the screen type have to be fixed here. It is only installed when
 * {@code -Dforbric.probeKeys=true}, and it stops after {@link #LIMIT} distinct lines so a held key cannot fill the
 * log.
 */
public final class KernelKeyProbe {
	private static final String PREFIX = "[Forbric/Probe] keyPress";
	private static final int LIMIT = 60;
	private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
	private static final AtomicInteger LINES = new AtomicInteger();

	private KernelKeyProbe() {
	}

	/** Called at the head of {@code KeyboardHandler.keyPress}; never throws into the client. */
	public static void record(Object handler, int key, int action) {
		try {
			if (LINES.get() >= LIMIT) return;
			if (!SEEN.add("entered|" + key + "|" + action)) return;
			say("entered key=" + key + " action=" + action);
			Class<?> type = handler.getClass();
			Object minecraft = read(type, handler, "minecraft");
			Object screen = minecraft == null ? null : read(minecraft.getClass(), minecraft, "screen");
			Object handled = read(type, handler, "handledDebugKey");
			String where = screen == null ? "no-screen" : screen.getClass().getName();
			String signature = key + "|" + action + "|" + where + "|" + handled;
			if (!SEEN.add(signature)) return;
			if (LINES.incrementAndGet() > LIMIT) return;
			say("state key=" + key + " action=" + action + " screen=" + where + " handledDebugKey=" + handled);
		} catch (Throwable unreadable) {
			say("unreadable " + unreadable);
		}
	}

	/**
	 * Writes one line where a launcher cannot swallow it.
	 *
	 * <p>{@code ForbricLog} routes to log4j when it can see it and to {@code System.err} otherwise - and a stock
	 * launcher does not put the game's stderr into {@code latest.log}, so a probe that only had the log said
	 * nothing at all and looked like it never ran. When {@code -Dforbric.probeFile} names a file, every line is
	 * appended there instead, which does not depend on any logging configuration.
	 */
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
				// Fall through to the log; a missing file must not hide the measurement.
			}
		}
		ForbricLog.info(text);
	}

	private static Object read(Class<?> type, Object instance, String name) throws ReflectiveOperationException {
		Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(instance);
	}
}
