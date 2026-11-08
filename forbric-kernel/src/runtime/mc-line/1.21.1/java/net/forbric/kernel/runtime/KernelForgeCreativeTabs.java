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

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraftforge.common.ForgeHooks;
import net.neoforged.neoforge.event.EventHooks;

/**
 * Composes the carriers' own generators, event dispatch and visibility merging.
 *
 * <p><b>1.21.1 line.</b> On 26.2 both carriers' {@code onCreativeModeTabBuildContents} take
 * {@code (CreativeModeTab, DisplayItemsGenerator, ItemDisplayParameters, Output)}; on 1.21.1 both take the
 * tab's registry key as the second argument and the generator third (javap, both carriers):
 *
 * <pre>
 *   26.2    EventHooks.onCreativeModeTabBuildContents(CreativeModeTab, DisplayItemsGenerator,
 *                                                     ItemDisplayParameters, Output)
 *   1.21.1  EventHooks.onCreativeModeTabBuildContents(CreativeModeTab, ResourceKey&lt;CreativeModeTab&gt;,
 *                                                     DisplayItemsGenerator, ItemDisplayParameters, Output)
 * </pre>
 *
 * <p>The key is not carried by anything this class receives, so it is resolved the way the game's own patched
 * {@code CreativeModeTab.buildContents} resolves it -- {@code BuiltInRegistries.CREATIVE_MODE_TAB
 * .getResourceKey(tab).orElseThrow(...)} (bytecode of 1.21.1's {@code buildContents}: {@code getstatic
 * BuiltInRegistries.CREATIVE_MODE_TAB}, {@code Registry.getResourceKey}, {@code Optional.orElseThrow}, then the
 * five-argument call). The throw is not an invention: 1.21.1's own method already throws it for a tab that is
 * not registered, and {@code buildContents} only ever runs on a registered tab.
 *
 * <p>{@code ForgeCreativeTabsInjector} redirects the merged {@code CreativeModeTab.buildContents} call site to
 * this class and keeps that call's descriptor. On this line the call carries the tab's registry key, so the
 * five-argument shape is the one the injector lands on; the four-argument shape stays for call sites with no
 * key and delegates to it. Both were needed: the injector rewrote the owner of a five-argument call to a class
 * that only had four, and opening the creative inventory died with {@code NoSuchMethodError}.
 */
public final class KernelForgeCreativeTabs {
	private KernelForgeCreativeTabs() { }

	/** Same descriptor as both carriers' onCreativeModeTabBuildContents on this line. */
	public static void buildContents(CreativeModeTab tab, ResourceKey<CreativeModeTab> key,
			CreativeModeTab.DisplayItemsGenerator generator, CreativeModeTab.ItemDisplayParameters parameters,
			CreativeModeTab.Output output) {
		if ("off".equalsIgnoreCase(System.getProperty("forbric.forgeCreativeTabs", "on"))) {
			EventHooks.onCreativeModeTabBuildContents(tab, key, generator, parameters, output);
			return;
		}
		// Neo emits parent and search entries separately. Forge's native merge lambda combines matching stacks
		// into PARENT_AND_SEARCH_TABS, then its event can amend the result. Keep the Neo generator inside it:
		// its existing empty-stack tolerance still runs before anything reaches Forge's collector.
		ForgeHooks.onCreativeModeTabBuildContents(tab, key,
				(p, o) -> EventHooks.onCreativeModeTabBuildContents(tab, key, generator, p, o), parameters, output);
	}

	/**
	 * The four-argument shape, for a call site that has no key to hand. It resolves the key the way the game's
	 * own patched {@code CreativeModeTab.buildContents} does, then takes the five-argument path above, so both
	 * shapes dispatch exactly the same events.
	 */
	public static void buildContents(CreativeModeTab tab, CreativeModeTab.DisplayItemsGenerator generator,
			CreativeModeTab.ItemDisplayParameters parameters, CreativeModeTab.Output output) {
		ResourceKey<CreativeModeTab> key = BuiltInRegistries.CREATIVE_MODE_TAB.getResourceKey(tab)
				.orElseThrow(() -> new IllegalStateException("Creative mode tab not registered: " + tab));
		buildContents(tab, key, generator, parameters, output);
	}
}
