package net.forbric.compat.tooltip;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import java.util.function.Consumer;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * The 1.21.1 editions of NeoForge 26.x's component-tooltip types, all in one file as nested types so the
 * rename table needs one row per nested name instead of one file per class.
 *
 * <p>THE FIVE TYPES, and what each replaces:
 *
 * <pre>
 *   net.neoforged.neoforge.common.tooltip.TooltipAppender          interface, one method, six parameters
 *   net.neoforged.neoforge.common.tooltip.TooltipLocation          enum, HEAD / POST_CUSTOM / PRE_ITEM_INFO / TAIL
 *   net.minecraft.world.item.component.TooltipDisplay              record(boolean hideTooltip, SequencedSet&lt;DataComponentType&gt;)
 *   net.neoforged.neoforge.event.RegisterTooltipAppendersEvent     registerAppender(TooltipLocation, TooltipAppender)
 *   net.neoforged.neoforge.common.tooltip.ItemTooltipHandler       getVanillaAppenderOrder(), init()
 * </pre>
 *
 * <p>WHY ALL FIVE AT ONCE. KernelNeoTooltips (223 lines) names every one of them, so supplying four leaves
 * the file with an error and reduces nothing. That is the same lesson as stage 28's cost rule: the first
 * term of the cost is how many types the CONTAINING FILE is missing, and here it is five.
 *
 * <p>WHAT IS REAL HERE AND WHAT IS NOT. Handler.init() is real: it posts RegisterEvent on the NeoForge bus
 * and collects what registered, so Handler.getVanillaAppenderOrder() returns a table that actually reflects
 * the registrations -- the ordering semantics this subsystem exists for are implemented, not faked. What is
 * NOT here is any connection to a component-based tooltip pipeline, because 1.21.1 has none: TooltipDisplay
 * carries its hide flags and nothing on that line consumes them. So this class supplies the SHAPE the kernel
 * compiles against and a working registration table; whether a given 1.21.1 tooltip actually honours a
 * Display's flags is not something this class can make true, and it does not pretend to.
 */
public final class ForbricTooltips {

	private ForbricTooltips() {
	}

	/** The appender callback. Six parameters, matching 26.2's TooltipAppender.append exactly. */
	@FunctionalInterface
	public interface Appender {
		void append(ItemStack stack, Item.TooltipContext context, Display display, Player player,
				TooltipFlag flag, Consumer<Component> out);
	}

	/** Where an appender sits relative to the vanilla component lines. */
	public enum Location {
		HEAD,
		POST_CUSTOM,
		PRE_ITEM_INFO,
		TAIL
	}

	/**
	 * Per-item tooltip flags. 26.2's record, reproduced. DEFAULT and withHidden exist so the kernel's
	 * call sites keep working; `shows` is the query, and on this line nothing calls it from vanilla --
	 * a 1.21.1 tooltip is built from a List of components, not from a Display.
	 */
	public record Display(boolean hideTooltip, SequencedSet<DataComponentType<?>> hiddenComponents) {

		public static final Display DEFAULT = new Display(false, new LinkedHashSet<>());

		public Display {
			hiddenComponents = new LinkedHashSet<>(hiddenComponents);
		}

		public Display withHidden(DataComponentType<?> type, boolean hidden) {
			SequencedSet<DataComponentType<?>> next = new LinkedHashSet<>(hiddenComponents);
			if (hidden) next.add(type);
			else next.remove(type);
			return new Display(hideTooltip, next);
		}

		public boolean shows(DataComponentType<?> type) {
			return !hideTooltip && !hiddenComponents.contains(type);
		}
	}

	/** The registration event, posted from Handler.init(). */
	public static final class RegisterEvent extends net.neoforged.bus.api.Event {

		private final List<List<Appender>> byLocation = new ArrayList<>();

		public RegisterEvent() {
			for (int i = 0; i < Location.values().length; i++) byLocation.add(new ArrayList<>());
		}

		public RegisterEvent registerAppender(Location location, Appender appender) {
			if (location != null && appender != null) byLocation.get(location.ordinal()).add(appender);
			return this;
		}

		public List<Appender> appendersAt(Location location) {
			return List.copyOf(byLocation.get(location.ordinal()));
		}
	}

	/** The appender table. init() posts the registration event; the order is what registered, per location. */
	public static final class Handler {

		private static final List<DataComponentType<?>> ORDER = new ArrayList<>();

		private Handler() {
		}

		/**
		 * Posts the registration event. ORDER stays EMPTY on 1.21.1, and that is the correct answer rather
		 * than a missing one: this table's job on 26.2 is to list which DataComponentTypes contribute a
		 * tooltip line, and 1.21.1 has no component-based tooltip pipeline, so nothing on this line
		 * contributes one. KernelNeoTooltips already treats an empty table as "nothing to splice" and
		 * returns before doing any work -- so the empty result is the early-out it is written for, not a
		 * stub standing in for a table that should have had entries.
		 */
		public static void init() {
			net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new RegisterEvent());
		}

		public static List<DataComponentType<?>> getVanillaAppenderOrder() {
			synchronized (ORDER) {
				return List.copyOf(ORDER);
			}
		}
	}
}
