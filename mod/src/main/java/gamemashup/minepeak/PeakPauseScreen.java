package gamemashup.minepeak;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;

/**
 * MinePeak's pause menu, as PEAK's: the game dimmed behind, the PEAK logo, and ribbons in PEAK's colours. BACK (to the
 * game), ACCOLADES (Minecraft's advancements), SETTINGS, REBIND CONTROLS (the key binds), LEAVE GAME (saved, to the
 * title screen).
 */
public final class PeakPauseScreen extends Screen {
	public PeakPauseScreen() { super(Component.literal("Paused")); }

	@Override
	protected void init() {
		// label, the ribbon's colour, its dotted lines' colour (PEAK's), what it does
		int i = 0;
		ribbon(i++, "BACK", 0xFFA8470F, 0xFFE06F00, this::onClose);
		// (as PEAK's: friends invited. Here, Minecraft's own pause menu: its Open to LAN, and the buttons of mods that let
		// friends join a single-player world, e.g. Essential's or World Host's; e4mc works through Open to LAN)
		if (minecraft.hasSingleplayerServer()) ribbon(i++, "INVITE FRIENDS", 0xFF2F7F3F, 0xFF3FBF4F, this::minecraftsMenu);
		ribbon(i++, "ACCOLADES", 0xFFC58E00, 0xFFF1C100, () -> {
			if (minecraft.player != null) minecraft.gui.setScreen(new AdvancementsScreen(minecraft.player.connection.getAdvancements(), this));
		});
		ribbon(i++, "SETTINGS", 0xFF2F649F, 0xFF008F98, () -> minecraft.gui.setScreen(new OptionsScreen(this, minecraft.options, true)));
		ribbon(i++, "REBIND CONTROLS", 0xFF642F9F, 0xFF8E20D9, () -> minecraft.gui.setScreen(new KeyBindsScreen(this, minecraft.options)));
		ribbon(i++, "LEAVE GAME", 0xFF84212C, 0xFFBE3400, () -> minecraft.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE));
	}

	/** Minecraft's own pause menu, as it is (GuiScreenMixin lets this one through, and its screens' way back to it). */
	private static net.minecraft.client.gui.screens.PauseScreen minecrafts;

	private void minecraftsMenu() {
		minecrafts = new net.minecraft.client.gui.screens.PauseScreen(true);
		minecraft.gui.setScreen(minecrafts);
	}

	/** Is this Minecraft's pause menu opened from INVITE FRIENDS (to be left as it is)? */
	public static boolean isMinecrafts(net.minecraft.client.gui.screens.Screen s) { return s != null && s == minecrafts; }

	/** (The dev test: INVITE FRIENDS pressed.) */
	void inviteFriends() { minecraftsMenu(); }

	/**
	 * A ribbon, as PEAK's: 0.046 of the screen high, 0.061 apart (from the middle of the screen down), all as wide as the
	 * longest label needs and at least 0.15 of the screen.
	 */
	private void ribbon(int i, String label, int face, int dots, Runnable action) {
		int h = Math.max(14, (int) (0.046 * height)), gap = Math.max(h + 4, (int) (0.061 * height));
		int longest = 0;
		for (String l : LABELS) longest = Math.max(longest, font.width(PeakFonts.text(l, PeakFonts.forHeight(h * 0.42f))));
		int w = Math.max((int) (0.15 * width), longest + 2 * Math.max(6, h * 3 / 4) + 6);
		int x = (width - w) / 2, y = (int) (0.482 * height) + i * gap;
		addRenderableWidget(new PeakButton(x, y, w, h, label, PeakButton.Shape.RIBBON, face, dots, 0xFFFFFFFF, 0f, false, 0, action));
	}

	private static final String[] LABELS = { "BACK", "INVITE FRIENDS", "ACCOLADES", "SETTINGS", "REBIND CONTROLS", "LEAVE GAME" };

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.fill(0, 0, width, height, 0xC0050505);   // (PEAK dims the game nearly to black)
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		PeakArt.Tex logo = PeakArt.get("logo");
		int lw = (int) (0.186 * width), ly = (int) (0.30 * height);
		if (logo != null) PeakArt.draw(g, logo, (width - lw) / 2, ly, lw, lw * logo.h() / logo.w(), 0xFFDFDAC2);
		else {
			var t = PeakFonts.text("PEAK", PeakFonts.TITLE);
			g.text(font, t, (width - font.width(t)) / 2, ly + 10, 0xFFDFDAC2, true);
		}
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	@Override
	public boolean isPauseScreen() { return true; }
}
