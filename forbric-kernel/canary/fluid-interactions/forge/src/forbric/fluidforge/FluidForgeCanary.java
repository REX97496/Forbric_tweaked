package forbric.fluidforge;

import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.fluids.FluidInteractionRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * A MinecraftForge mod's fluid interactions, registered at common setup as mods do: lava next to a lapis block or an
 * emerald block becomes shroomlight, and water next to a lapis block becomes a sponge. Uses MinecraftForge's API only.
 * Fixture of fluid-parity-gate.py --mods.
 */
@Mod("forbricfluidforge")
public final class FluidForgeCanary {
	public FluidForgeCanary(FMLJavaModLoadingContext context) {
		FMLCommonSetupEvent.getBus(context.getModBusGroup()).addListener(event -> event.enqueueWork(() -> {
			FluidInteractionRegistry.addInteraction(ForgeMod.LAVA_TYPE.get(), new FluidInteractionRegistry.InteractionInformation(
					(level, current, relative, state) -> level.getBlockState(relative).is(Blocks.LAPIS_BLOCK)
							|| level.getBlockState(relative).is(Blocks.EMERALD_BLOCK),
					(level, current, relative, state) -> {
						level.setBlockAndUpdate(current, Blocks.SHROOMLIGHT.defaultBlockState());
						System.out.println("[FluidCanary] minecraftforge lava rule fired at " + current.toShortString());
					}));
			FluidInteractionRegistry.addInteraction(ForgeMod.WATER_TYPE.get(), new FluidInteractionRegistry.InteractionInformation(
					(level, current, relative, state) -> level.getBlockState(relative).is(Blocks.LAPIS_BLOCK),
					(level, current, relative, state) -> {
						level.setBlockAndUpdate(current, Blocks.SPONGE.defaultBlockState());
						System.out.println("[FluidCanary] minecraftforge water rule fired at " + current.toShortString());
					}));
			System.out.println("[FluidCanary] minecraftforge rules registered");
		}));
	}
}
