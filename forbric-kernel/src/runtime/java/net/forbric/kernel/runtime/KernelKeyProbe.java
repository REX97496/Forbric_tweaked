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
			ForbricLog.info("{} key={} action={}", PREFIX + " entered", key, action);
			Class<?> type = handler.getClass();
			Object minecraft = read(type, handler, "minecraft");
			Object screen = minecraft == null ? null : read(minecraft.getClass(), minecraft, "screen");
			Object handled = read(type, handler, "handledDebugKey");
			String where = screen == null ? "no-screen" : screen.getClass().getName();
			String signature = key + "|" + action + "|" + where + "|" + handled;
			if (!SEEN.add(signature)) return;
			if (LINES.incrementAndGet() > LIMIT) return;
			ForbricLog.info("{} key={} action={} screen={} handledDebugKey={}", PREFIX, key, action, where, handled);
		} catch (Throwable unreadable) {
			ForbricLog.warn("{} could not read the handler's state", PREFIX, unreadable);
		}
	}

	private static Object read(Class<?> type, Object instance, String name) throws ReflectiveOperationException {
		Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(instance);
	}
}
