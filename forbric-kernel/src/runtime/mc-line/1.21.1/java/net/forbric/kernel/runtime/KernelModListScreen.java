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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;

/**
 * The Mods screen for an instance that runs three loaders.
 *
 * <p>THE SAME SCREEN AS THE SHARED FILE, and this is the 1.21.1 edition of it. It is an overlay rather than a table
 * row because what moved is the RENDERING MODEL, not a set of names: this line draws immediately into a
 * {@link GuiGraphics}, while 26.2 records draw commands into a {@code GuiGraphicsExtractor} that the frame is
 * replayed from. The extractor, {@code Screen.extractRenderState}/{@code extractBackground} and the whole
 * {@code net.minecraft.client.input} package do not exist here (0 classes, measured), and no table can carry a
 * difference of that shape.
 *
 * <p>WHY THE CLASS CANNOT SIMPLY BE LEFT OUT, which is what makes this edition a repair rather than a preference:
 * {@code ModsButtonRedirector} is a MARKER-SCANNED transform registered unconditionally in
 * {@code KernelBoot.launch}, and on this line it fires — driven against this line's own bytes it rewrites
 * {@code PauseScreen} and {@code TitleScreen} from {@code new net/neoforged/neoforge/client/gui/ModListScreen(Screen)}
 * (the pre-26.2.0.88 spelling, which is this line's only spelling) to {@code new KernelModListScreen(Screen)}. A class
 * that does not compile cannot be in the runtime jar, so the button would throw {@code NoClassDefFoundError} the
 * first time a player pressed it. The same class is reached by {@code KernelClientSmoke}'s reflection and by
 * {@code src/modmenuApi}'s direct call to {@link #create}.
 *
 * <h2>The four differences, each read out of the carriers with javap</h2>
 *
 * <table>
 *   <tr><th></th><th>26.2 (the shared file)</th><th>1.21.1 (here)</th></tr>
 *   <tr><td>drawing</td>
 *       <td>{@code GuiGraphicsExtractor}; {@code text}/{@code textWithWordWrap}/{@code fill}</td>
 *       <td>{@link GuiGraphics}; {@code drawString}/{@code drawWordWrap}/{@code fill} — same descriptors</td></tr>
 *   <tr><td>the two passes</td>
 *       <td>{@code extractBackground}, then {@code extractRenderState} after the widget pass</td>
 *       <td>{@code renderBackground}, then {@code render}; this line's {@code Screen.render} calls
 *           {@code renderBackground} and then the renderables, so the order is the same</td></tr>
 *   <tr><td>a row's rectangle</td>
 *       <td>state the list sets ({@code setX/setY/setWidth/setHeight}); {@code getContentX = getX()+2},
 *           {@code getContentWidth = getWidth()-4} (bytecode)</td>
 *       <td>parameters of {@code Entry.render(g, index, top, left, width, height, mouseX, mouseY, hovering,
 *           partialTick)}; see {@link Row#render} for the arithmetic that yields the same pixels</td></tr>
 *   <tr><td>a second click</td>
 *       <td>{@code Entry.mouseClicked(MouseButtonEvent, boolean doubled)}</td>
 *       <td>{@code Entry.mouseClicked(double, double, int)} and NOTHING that marks a double click — see below</td></tr>
 * </table>
 *
 * <p><b>The double-click shortcut has no substrate on this line and is not drawn.</b> 26.2 delivers a {@code doubled}
 * flag to the row; this line delivers the button and nothing else. That is measured, not assumed:
 * {@code doubleClick}/{@code DoubleClick} occurs in 0 classes of this line's merged base and 0 in its two runtime
 * carriers, against 50 in 26.2's merged base and 7 in its {@code neoforge-runtime} (where
 * {@code ScreenEvent$MouseButtonPressed} keeps the flag). {@code Screen} here has
 * {@code mouseClicked/mouseReleased/mouseDragged/mouseScrolled} and no state to tell a second click from a first. So
 * a click selects a row and the Config button is how a mod is configured here — and the "double-click to configure"
 * hint is NOT drawn, because a hint for a gesture the line cannot report is the same lie this class exists to avoid.
 * {@code KernelClientSmoke.doubleClickARowInTheModList} builds a {@code net.minecraft.client.input.MouseButtonEvent}
 * and calls {@code mouseClicked(event, true)}; on this line that class does not exist, so the lookup throws and the
 * smoke logs "could not double-click a row" — a warning under a catch, and now a true statement about this line
 * rather than a probe that failed.
 *
 * <p>Nothing else changes: the class still reads {@link ModCatalog}, still draws a list plus a detail pane, and still
 * reports {@link #framesDrawn()} and {@link #rowsBuilt()} to the smoke harness — which is the only thing that can
 * prove a screen belonging to this class renders at all.
 *
 * <p>It replaces NeoForge's, which is reached from the pause menu's mods button. NeoForge's screen is not broken —
 * it lists {@code ModList.get()}, which is every mod NeoForge loaded, and that is the right answer on a NeoForge
 * instance. Here it is three mods out of sixteen, and the same is true of every other family's screen: each is
 * complete for its own loader and none of them can answer for the instance. So the button now opens the one list
 * that can, {@link ModCatalog}.
 *
 * <p>Drawn rather than composed out of a vanilla layout on purpose: what a player needs from this screen is which
 * mods are here and which loader each came from, and the second half has no vanilla widget because no vanilla
 * screen has ever had to say it. Each row carries its ecosystem as a coloured tag.
 */
public final class KernelModListScreen extends Screen {
	private static final int TAG_FABRIC = 0xFFDBB03B;
	private static final int TAG_NEOFORGE = 0xFFE5834C;
	private static final int TAG_FORGE = 0xFF8B9BC4;
	private static final int DIM = 0xFFA0A0A0;
	private static final int BRIGHT = 0xFFFFFFFF;
	/** Panel wash, so mod names stay legible over whatever the world happens to look like behind them. */
	private static final int PANEL = 0xC0000000;
	private static final int SELECTED = 0x60FFFFFF;
	/**
	 * Rows for mods something went wrong with.
	 *
	 * <p>The marker is a character rather than colour alone: colour alone is not readable to everyone, and this
	 * row is the one carrying the information a player most needs.
	 */
	private static final int BROKEN = 0xFFE06C6C;
	private static final String BROKEN_MARK = "! ";
	/** How many unconfirmed notes the detail pane names for one mod before pointing at the machine report. */
	private static final int SUSPECTED_NOTES = 3;
	private static final int PAD = 6;
	private static final int SEARCH_Y = 32;
	private static final int LIST_TOP = 54;
	/**
	 * 26.2's row inset, carried over rather than guessed.
	 *
	 * <p>26.2 keeps it inside the row: {@code AbstractSelectionList.Entry.getContentX()} is {@code getX()+2} and
	 * {@code getContentWidth()} is {@code getWidth()-4} (both read out of the bytecode). This line has no such
	 * accessors, and its two axes arrive differently inset, so the arithmetic below uses this number on the axis
	 * that needs it rather than on both: measured with {@code javap -c}, 1.21.1's {@code getRowLeft()} adds the two
	 * pixels itself where 26.2's does not, and the {@code height} parameter already arrives four short of
	 * {@code itemHeight}. Applying it twice would move this screen's rows two pixels right of where they are on
	 * 26.2; applying it on the wrong axis would draw the highlight off by two vertically.
	 */
	private static final int CONTENT_PADDING = 2;

	private final Screen parent;
	private ModList list;
	private EditBox search;
	private Button config;

	/**
	 * Frames actually drawn, and rows actually built — read by the client smoke harness.
	 *
	 * <p>A screen is the one thing in this repo that no unit test can prove works: its init and its draw run only
	 * when a player clicks, and a mistake in either is a crash in the middle of a frame. The harness opens it on a
	 * real client, holds it, and reads these two numbers back, so "it renders" is a measurement rather than a
	 * hope. A frame count of zero with a live screen means it was constructed and never drawn.
	 */
	private static volatile int framesDrawn;
	private static volatile int rowsBuilt;

	/** Frames this screen has drawn since the process started. */
	public static int framesDrawn() {
		return framesDrawn;
	}

	/** Rows the last build produced. */
	public static int rowsBuilt() {
		return rowsBuilt;
	}

	public KernelModListScreen(Screen parent) {
		super(Component.literal("Mods"));
		this.parent = parent;
	}

	/**
	 * The static-factory shape a family's own mod list may be opened through.
	 *
	 * <p>NeoForge 26.2.0.88's mods button calls {@code ModListScreen.create(screen)} rather than constructing it,
	 * so {@code ModsButtonRedirector} re-points that call here. The kernel's stand-in for Mod Menu's API answers
	 * {@code ModMenuApi.createModsScreen} with it too. Nothing in the kernel calls this itself.
	 */
	public static KernelModListScreen create(Screen parent) {
		return new KernelModListScreen(parent);
	}

	@Override
	protected void init() {
		int listWidth = Math.max(150, Math.min(280, this.width / 3));
		this.search = new EditBox(this.font, PAD, SEARCH_Y, listWidth - PAD, 18, Component.literal("Search"));
		this.search.setHint(Component.literal("Search"));
		this.search.setResponder(this::rebuild);
		addRenderableWidget(this.search);

		this.list = new ModList(this.minecraft, listWidth, this.height - LIST_TOP - 34, LIST_TOP, 26);
		this.list.setX(0);
		addRenderableWidget(this.list);

		this.config = addRenderableWidget(Button.builder(Component.literal("Config"), b -> openConfig())
				.bounds(this.width - 110, this.height - 26, 100, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(this.width / 2 - 100, this.height - 26, 200, 20).build());

		rebuild(this.search.getValue());
	}

	private void rebuild(String query) {
		if (this.list == null) return;
		String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		List<Row> rows = new ArrayList<>();
		for (ModCatalog.Entry e : ModCatalog.all()) {
			if (!needle.isEmpty() && !matches(e, needle)) continue;
			rows.add(new Row(e));
		}
		this.list.show(rows);
		rowsBuilt = rows.size();
		if (!rows.isEmpty() && this.list.getSelected() == null) this.list.setSelected(rows.get(0));
		selectionChanged();
	}

	private static boolean matches(ModCatalog.Entry e, String needle) {
		if (e.status() != ModCatalog.Status.OK
				&& ("broken".contains(needle) || "failed".contains(needle) || needle.startsWith("!"))) {
			// So that a player who can SEE something is wrong can also filter down to it.
			return true;
		}
		return e.name().toLowerCase(Locale.ROOT).contains(needle)
				|| e.modId().toLowerCase(Locale.ROOT).contains(needle)
				|| label(e.ecosystem()).toLowerCase(Locale.ROOT).contains(needle);
	}

	/**
	 * The two panels, drawn BEFORE the widgets.
	 *
	 * <p>On 26.2 this is {@code extractBackground}, which runs before the widget pass; here it is
	 * {@code renderBackground}, which this line's {@code Screen.render} calls immediately before it walks the
	 * renderables — so a wash painted here still sits behind the list it is supposed to sit behind. This screen is
	 * opened over a live world and the mod names are the whole point of it.
	 */
	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.renderBackground(g, mouseX, mouseY, partialTick);
		if (this.list == null) return;
		g.fill(0, 0, this.list.getWidth(), this.height, PANEL);
		g.fill(this.list.getWidth() + 1, 0, this.width, this.height, PANEL);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		framesDrawn++;
		g.drawString(this.font, this.title.copy().append(Component.literal(" (" + ModCatalog.all().size() + ")")),
				PAD, 8, BRIGHT);
		g.drawString(this.font, Component.literal(summary()), PAD, 20, DIM);
		ModCatalog.Entry selected = this.list == null || this.list.getSelected() == null
				? null : this.list.getSelected().entry;
		if (selected != null) detail(g, selected);
	}

	/** The right-hand pane. Word-wrapped so a long description does not run off the edge of the screen. */
	private void detail(GuiGraphics g, ModCatalog.Entry e) {
		int x = this.list.getWidth() + 12;
		int wrap = Math.max(80, this.width - x - 10);
		// Below the header band, not level with it: the counts line is as wide as it needs to be and runs past
		// the list column, and the first measurement of this screen had it drawn straight through the mod's name.
		int y = LIST_TOP - 8;
		g.drawString(this.font, Component.literal(e.name()), x, y, BRIGHT);
		y += 12;
		g.drawString(this.font, Component.literal(e.modId() + (e.version().isEmpty() ? "" : "  " + e.version())),
				x, y, DIM);
		y += 12;
		g.drawString(this.font, Component.literal(label(e.ecosystem())), x, y, tag(e.ecosystem()));
		y += 14;
		if (e.status() != ModCatalog.Status.OK) {
			// The wording is the careful part. A withdrawn mod's classes are loaded and its mixins applied, so
			// "not running" would be untrue and would send someone to reinstall what is already there.
			String what = e.status() == ModCatalog.Status.FAILED
					? "This mod did not finish loading"
					: "Part of this mod did not run";
			String detail = e.statusDetail().isEmpty() ? "" : " \u2014 " + e.statusDetail();
			g.drawWordWrap(this.font, FormattedText.of(what + detail), x, y, wrap, BROKEN);
			y += 12 * (1 + this.font.split(FormattedText.of(what + detail), wrap).size());
			g.drawString(this.font, Component.literal("see .forbric-kernel/load-report.txt"), x, y, DIM);
			y += 14;
		}
		// Suspicions about this mod, dimmed and said to be unconfirmed: they change no status, and a player
		// troubleshooting the mod should still be able to see what was noticed. The rest are pointed at the machine
		// report, which always lists them all -- load-report.txt exists only when something failed, and a boot
		// whose only findings are suspicions writes none.
		List<CompatibilityFinding> notes = CompatibilityFindings.suspected().stream()
				.filter(f -> f.modId().equals(e.modId())).toList();
		for (int i = 0; i < Math.min(notes.size(), SUSPECTED_NOTES); i++) {
			CompatibilityFinding f = notes.get(i);
			String note = "possibly (not confirmed): " + f.feature() + " \u2014 " + f.detail();
			g.drawWordWrap(this.font, FormattedText.of(note), x, y, wrap, DIM);
			y += 12 * this.font.split(FormattedText.of(note), wrap).size() + 2;
		}
		if (notes.size() > SUSPECTED_NOTES) {
			g.drawString(this.font, Component.literal("and " + (notes.size() - SUSPECTED_NOTES)
					+ " more in .forbric-kernel/compatibility-report.json"), x, y, DIM);
			y += 12;
		}
		if (!notes.isEmpty()) y += 4;
		if (!e.authors().isEmpty()) {
			g.drawString(this.font, Component.literal("by " + String.join(", ", e.authors())), x, y, DIM);
			y += 12;
		}
		if (!e.jar().isEmpty()) {
			g.drawString(this.font, Component.literal(e.jar()), x, y, DIM);
			y += 14;
		}
		if (!e.description().isEmpty()) {
			g.drawWordWrap(this.font, FormattedText.of(e.description()), x, y, wrap, BRIGHT);
			y += 12 * (1 + this.font.split(FormattedText.of(e.description()), wrap).size());
		}
		// What this jar carries inside itself. Not listed as mods of their own -- they are not what anyone
		// installed -- but a player looking for "why is Kotlin here" should find the answer on the mod that
		// brought it, not have it missing from the screen entirely.
		int bundled = ModCatalog.bundledBy(e.modId()).size();
		if (bundled > 0) {
			g.drawString(this.font, Component.literal("bundles " + bundled + " jar(s)"), x, y + 4, DIM);
		}
		// NO "double-click to configure" HINT HERE, and its absence is the decision rather than an omission: this
		// line reports a click's button and nothing else, so a second click cannot be told from a first and the
		// shortcut does not exist. A hint for a gesture this line cannot see would be the same lie the redirect
		// avoids when a relabelled button still opened one family's list. The Config button above is the way in.
	}

	/**
	 * Shows the Config button only when the selected mod's own ecosystem has a factory registered for it.
	 *
	 * <p>Asked on selection, not per frame: the answer needs a registry lookup in one of three places, and a
	 * button that dims and brightens as the highlight moves is worth one lookup per move, not sixty per second.
	 */
	private void selectionChanged() {
		if (this.config == null) return;
		Row row = this.list == null ? null : this.list.getSelected();
		this.config.visible = row != null && KernelModConfigScreens.has(row.entry);
	}

	private void openConfig() {
		Row row = this.list == null ? null : this.list.getSelected();
		if (row == null) return;
		Screen screen = KernelModConfigScreens.open(row.entry, this);
		// Null here means the factory changed its mind between the probe and the press, or threw. Staying put is
		// the only sane answer: setScreen(null) would drop the player into the world with the menu gone.
		// `Minecraft.setScreen` rather than 26.2's `Minecraft.gui.setScreen`: `Minecraft.gui` exists on both lines
		// and is `net.minecraft.client.gui.Gui` on both, but THIS line's Gui has no setScreen -- 26.2 moved it there.
		if (screen != null) this.minecraft.setScreen(screen);
	}

	private static String summary() {
		String counts = ModCatalog.count(Ecosystem.FABRIC) + " Fabric   "
				+ ModCatalog.count(Ecosystem.NEOFORGE) + " NeoForge   "
				+ ModCatalog.count(Ecosystem.FORGE) + " MinecraftForge";
		int broken = ModCatalog.failures().size();
		// "did not finish loading", never "not running": a withdrawn mod's classes ARE loaded and its mixins ARE
		// applied. Telling a player it is absent sends them to reinstall something that is already there.
		String summary = broken == 0 ? counts : counts + "   \u00a7c" + broken + " did not finish loading";
		// Confirmed losses no row can carry -- the kernel's own, or a config no single mod claims. The prompt that
		// sent the player here counted them, so the screen has to say they exist and where they are written.
		int unowned = CompatibilityFindings.unattributed().size();
		return unowned == 0 ? summary
				: summary + "   \u00a7c" + unowned + " not tied to one mod (see .forbric-kernel/load-report.txt)";
	}

	private static String label(Ecosystem ecosystem) {
		return switch (ecosystem) {
			case FABRIC -> "Fabric";
			case NEOFORGE -> "NeoForge";
			case FORGE -> "MinecraftForge";
		};
	}

	private static int tag(Ecosystem ecosystem) {
		return switch (ecosystem) {
			case FABRIC -> TAG_FABRIC;
			case NEOFORGE -> TAG_NEOFORGE;
			case FORGE -> TAG_FORGE;
		};
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(this.parent);
	}

	/** The scrolling list. Vanilla's widget, so scrolling, selection and keyboard navigation are not reinvented. */
	private static final class ModList extends ObjectSelectionList<Row> {
		ModList(Minecraft minecraft, int width, int height, int y, int entryHeight) {
			super(minecraft, width, height, y, entryHeight);
		}

		/**
		 * Replaces the rows, from inside the subclass.
		 *
		 * <p>26.2's {@code AbstractSelectionList.replaceEntries(Collection)} is public and this line's is
		 * {@code protected} (javap: the same method, one access level down), so the screen cannot call it on the
		 * widget it holds. A subclass may, and that is the whole of the difference — no clearing-and-adding, which
		 * would drop the selection and fire the responder once per row instead of once.
		 */
		void show(List<Row> rows) {
			replaceEntries(rows);
		}

		/**
		 * Vanilla centres a fixed-width row inside the list, which for a narrow column puts the row's left edge
		 * at a NEGATIVE x — the first measurement of this screen showed every mod name with its first few
		 * characters cut off the left of the window. The rows are this column.
		 */
		@Override
		public int getRowWidth() {
			return getWidth() - PAD * 2;
		}
	}

	/** One mod: its name, and under it the loader it came from — which is the half no vanilla row has. */
	private final class Row extends ObjectSelectionList.Entry<Row> {
		private final ModCatalog.Entry entry;

		Row(ModCatalog.Entry entry) {
			this.entry = entry;
		}

		/**
		 * Draws one row, and the same pixels as the shared file.
		 *
		 * <p>26.2 hands a row its rectangle as STATE the list sets before it draws ({@code setX/setY/setWidth/
		 * setHeight}, then {@code extractContent(g, mouseX, mouseY, hovered, partialTick)}), and the four numbers
		 * this method used there came from {@code getContentX/Y/Width/Height()}, which are that rectangle inset by
		 * {@link #CONTENT_PADDING} (bytecode: {@code getContentX = getX()+2}, {@code getContentWidth =
		 * getWidth()-4}). This line hands the SAME rectangle as parameters, and — measured with {@code javap -c},
		 * this is not symmetry one can assume — it arrives half-inset already:
		 *
		 * <pre>
		 *   26.2      getRowLeft()            = X + W/2 - RW/2          getContentX()      = that + 2
		 *   1.21.1    getRowLeft()            = X + W/2 - RW/2 + 2      `left` parameter  = that
		 *   26.2      setHeight(itemHeight)   getContentHeight() = itemHeight - 4
		 *   1.21.1    `height` parameter       = itemHeight - 4
		 * </pre>
		 *
		 * <p>so {@code left} and {@code height} are already the content values and {@code top} and {@code width}
		 * are the row values. Applying {@link #CONTENT_PADDING} on all four would move every row two pixels right of
		 * where the shared file puts it; applying it on none would put the second line of text two pixels high.
		 */
		@Override
		public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX,
				int mouseY, boolean hovered, float partialTick) {
			int x = left;
			int y = top + CONTENT_PADDING;
			int contentWidth = width - CONTENT_PADDING * 2;
			if (KernelModListScreen.this.list.getSelected() == this) {
				// The shared file's own expression, kept verbatim: with the four numbers above it lands on exactly
				// the rectangle 26.2 highlights ([rowLeft, rowTop] to [rowLeft+rowWidth, rowTop+itemHeight-1]).
				g.fill(x - 2, y - 2, x + contentWidth + 2, y + height + 1, SELECTED);
			}
			boolean broken = this.entry.status() != ModCatalog.Status.OK;
			String name = broken ? BROKEN_MARK + this.entry.name() : this.entry.name();
			g.drawString(KernelModListScreen.this.font, trim(name, contentWidth), x, y, broken ? BROKEN : BRIGHT);
			g.drawString(KernelModListScreen.this.font, label(this.entry.ecosystem()), x, y + 11,
					tag(this.entry.ecosystem()));
		}

		/** Names are arbitrary user data and the column is narrow; an ellipsis beats drawing over the next pane. */
		private String trim(String text, int width) {
			if (KernelModListScreen.this.font.width(text) <= width) return text;
			String cut = KernelModListScreen.this.font.plainSubstrByWidth(text, width - 6);
			return cut + "…";
		}

		@Override
		public Component getNarration() {
			return Component.literal(this.entry.name() + ", " + label(this.entry.ecosystem()));
		}

		/**
		 * Selects the row on a click — and only selects, because this line cannot report a second click.
		 *
		 * <p>26.2's entry handler is {@code mouseClicked(MouseButtonEvent, boolean doubled)}; this line's is
		 * {@code mouseClicked(double, double, int)} and carries nothing but the button. That is measured rather
		 * than assumed: {@code doubleClick} occurs in 0 classes of this line's merged base and 0 in its two runtime
		 * carriers, against 50 in 26.2's merged base and 7 in {@code neoforge-runtime} (where NeoForge's
		 * {@code ScreenEvent$MouseButtonPressed} keeps the flag). So the double-click shortcut to a mod's config
		 * — which the shared file has, and which {@code KernelClientSmoke.doubleClickARowInTheModList} drives — does
		 * not exist here, and nothing pretends it does: the "double-click to configure" hint is not drawn either.
		 * The Config button is the way to configure a mod on this line, and it says whether the mod HAS one.
		 *
		 * <p>Selection itself is still the row's own job on this line: {@code AbstractSelectionList.mouseClicked}
		 * only marks an entry selected when the entry answers {@code true} (bytecode), so returning without the
		 * {@code setSelected} would leave the list unselectable.
		 */
		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			KernelModListScreen.this.list.setSelected(this);
			KernelModListScreen.this.selectionChanged();
			return true;
		}
	}
}
