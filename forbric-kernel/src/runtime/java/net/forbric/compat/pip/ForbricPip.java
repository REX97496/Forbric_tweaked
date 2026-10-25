package net.forbric.compat.pip;

import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableMap;

/**
 * The 1.21.1 editions of the 26.x picture-in-picture types, as nested types in one file.
 *
 * <pre>
 *   net.minecraft.client.gui.render.pip.PictureInPictureRenderer                            abstract class
 *   net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState                 interface
 *   net.neoforged.neoforge.client.gui.PictureInPictureRendererRegistration                   record
 *   net.minecraftforge.client.event.RegisterPictureInPictureRendererEvent                    final class + BUS
 * </pre>
 *
 * <p>WHAT THE KERNEL ACTUALLY USES, measured over all 31 error sites in KernelForgePipRenderers (156
 * lines). This is why the phase is small: the whole file uses three members and two type positions.
 *
 * <pre>
 *   renderer.getRenderStateClass()   -&gt; Class&lt;? extends RenderState&gt;     one call site
 *   renderer.close()                 -&gt; void                            one call site
 *   event BUS                        static field, .post(event)          one call site
 *   RegisterState                    type parameter only, zero members used
 *   Registration                     instanceof and collection element only, zero members used
 * </pre>
 *
 * <p>SO RenderState AND Registration ARE DELIBERATELY MEMBERLESS. That is not a stub standing in for
 * something: the kernel asks instanceof and Class&lt;? extends RenderState&gt; for identity, and identity is
 * what these give. Nothing in the file reads a field or calls a method on either one. The same reasoning as
 * phase C's empty appender order, which was also the correct answer rather than a missing one.
 *
 * <p>WHAT IS NOT HERE. There is no pip render pipeline on 1.21.1 -- render/pip has zero entries -- so
 * nothing in this file draws anything. The registry it backs is a map of state class to renderer that the
 * merged base fills and walks; whether a renderer instance actually paints a region is that renderer's job,
 * and no 1.21.1 renderer of that shape exists yet. This class supplies the names and the registry, not the
 * painting.
 */
public final class ForbricPip {

	private ForbricPip() {
	}

	public static final class RegisterEvent extends net.neoforged.bus.api.Event {

		public static final net.neoforged.bus.api.IEventBus BUS =
				net.neoforged.neoforge.common.NeoForge.EVENT_BUS;

		private final List<Renderer<?>> created;
		private final ImmutableMap.Builder<Class<? extends RenderState>, Renderer<?>> byState;

		public RegisterEvent(List<Renderer<?>> created,
				ImmutableMap.Builder<Class<? extends RenderState>, Renderer<?>> byState) {
			this.created = created;
			this.byState = byState;
		}

		public List<Renderer<?>> created() {
			return created;
		}

		public ImmutableMap.Builder<Class<? extends RenderState>, Renderer<?>> byState() {
			return byState;
		}

		public RegisterEvent register(Class<? extends RenderState> state, Renderer<?> renderer) {
			byState.put(state, renderer);
			created.add(renderer);
			return this;
		}
	}

	/** Marker for a render state. Zero members are used by the kernel; see the class note. */
	public interface RenderState {
	}

	/**
	 * A renderer keyed by the state class it can draw. The 26.2 type is abstract with three private fields;
	 * the kernel reaches none of them, so this one carries the two members it does reach.
	 */
	public abstract static class Renderer<S extends RenderState> {

		public abstract Class<S> getRenderStateClass();

		public void close() {
		}

		public void render(S state, net.minecraft.client.gui.GuiGraphics graphics, float partialTick) {
			// no pip pipeline exists on 1.21.1; a subclass that paints does so itself
		}
	}

	/** A registration pairs a state class with a renderer. Zero members are used by the kernel. */
	public static final class Registration<S extends RenderState> {

		private final Class<S> state;
		private final Renderer<S> renderer;

		public Registration(Class<S> state, Renderer<S> renderer) {
			this.state = state;
			this.renderer = renderer;
		}

		public Class<S> state() {
			return state;
		}

		public Renderer<S> renderer() {
			return renderer;
		}
	}

	/** Convenience for the map type the kernel threads through, so call sites stay readable. */
	public static ImmutableMap.Builder<Class<? extends RenderState>, Renderer<?>> newByState() {
		return ImmutableMap.builder();
	}

	/** Unused-parameter guard for Map-typed helpers the kernel passes through. */
	static Map<Class<? extends RenderState>, Renderer<?>> emptyByState() {
		return Map.of();
	}
}
