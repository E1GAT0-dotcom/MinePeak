package gamemashup.minepeak;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;

/**
 * MinePeak's title screen, as PEAK's main menu: its scene behind (the plane wrecked on the beach, taken from the player's
 * own PEAK), the PEAK logo, a signpost of planks, and the time till PEAK's next island. Where things sit and their colours
 * are PEAK's (its menu, photographed at 1600 x 900), the planks a little bigger.
 * - PLAY OFFLINE (in HOST GAME's place: Minecraft's worlds; one can be opened to others from the pause menu),
 *   JOIN GAME (Minecraft's servers), SETTINGS (Minecraft's options), CREDITS (who made what), QUIT.
 */
public final class PeakTitleScreen extends Screen {
	private static final int SIGN = 0xFFC8A379, SIGN_EDGE = 0xFF6F5343, INK = 0xFF5B453F, POST = 0xFF907354, POST_EDGE = 0xFF735A3F;

	public PeakTitleScreen() { super(Component.literal("MinePeak")); }

	/** When this screen first showed (for how long it waits for PEAK's pictures), and whether it has its signs yet. */
	private long shownAt;
	private boolean signs;

	/**
	 * Waiting for the menu: PEAK's pictures aren't loaded yet (Minecraft still loading, or PEAK saving them for the first
	 * time). Up to 20 s, or for as long as PEAK is exporting when there are none at all: then the plain menu.
	 */
	private boolean waiting() {
		if (PeakArt.ready()) return false;
		if (minecraft.gui.overlay() != null) return true;
		long waited = System.currentTimeMillis() - shownAt;
		return waited < 20_000 || PeakExporter.running() && waited < 300_000;
	}

	@Override
	public void tick() {
		super.tick();
		if (!signs && !waiting()) rebuildWidgets();
	}

	@Override
	protected void init() {
		if (shownAt == 0) shownAt = System.currentTimeMillis();
		signs = !waiting();
		if (!signs) return;
		// the signs: label, left, right, middle (fractions of the screen), height, tilt, shape, flipped, hikers, what it does
		sign("PLAY OFFLINE", 0.120, 0.445, 0.505, 0.088, -2f, PeakButton.Shape.ARROW, false, 1, () -> minecraft.gui.setScreen(new SelectWorldScreen(this)));
		sign("JOIN GAME", 0.105, 0.365, 0.615, 0.078, 2f, PeakButton.Shape.ARROW, false, 3, () -> minecraft.gui.setScreen(new JoinMultiplayerScreen(this)));
		sign("SETTINGS", 0.150, 0.330, 0.712, 0.064, 2f, PeakButton.Shape.DOUBLE_ARROW, false, 0, () -> minecraft.gui.setScreen(new OptionsScreen(this, minecraft.options, false)));
		sign("CREDITS", 0.170, 0.330, 0.787, 0.060, -2f, PeakButton.Shape.DOUBLE_ARROW, false, 0, () -> minecraft.gui.setScreen(new PeakCreditsScreen(this)));
		sign("QUIT", 0.180, 0.290, 0.905, 0.060, 3f, PeakButton.Shape.ARROW, true, 0, () -> minecraft.stop());
	}

	private void sign(String label, double x0, double x1, double yc, double h, float tilt, PeakButton.Shape shape, boolean flip, int icons, Runnable action) {
		int px = (int) (x0 * width), pw = (int) ((x1 - x0) * width), ph = Math.max(12, (int) (h * height)), py = (int) (yc * height) - ph / 2;
		addRenderableWidget(new PeakButton(px, py, pw, ph, label, shape, SIGN, SIGN_EDGE, INK, tilt, flip, icons, action));
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		if (!signs) return;
		PeakArt.Tex bg = PeakArt.get("menu_background");
		if (bg == null) { super.extractBackground(g, mouseX, mouseY, a); return; }
		// PEAK's scene over the whole screen (cropped to fit)
		float s = Math.max(width / (float) bg.w(), height / (float) bg.h());
		int w = Math.round(bg.w() * s), h = Math.round(bg.h() * s);
		PeakArt.draw(g, bg, (width - w) / 2, (height - h) / 2, w, h, 0xFFFFFFFF);
	}

	@Override
	protected void extractMenuBackground(GuiGraphicsExtractor g) { }

	@Override
	protected void extractBlurredBackground(GuiGraphicsExtractor g) { }

	@Override
	public boolean isPauseScreen() { return false; }

	@Override
	public boolean shouldCloseOnEsc() { return false; }

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		if (!signs) { loading(g); return; }
		under(g);
		super.extractRenderState(g, mouseX, mouseY, a);   // (the signs)
	}

	/** While the menu waits: PEAK's loading screen (dark, PEAK, and what MinePeak is doing). */
	private void loading(GuiGraphicsExtractor g) {
		g.fill(0, 0, width, height, 0xFF0C0B0A);
		var peak = PeakFonts.text("PEAK", PeakFonts.TITLE);
		g.text(font, peak, (width - font.width(peak)) / 2, height / 2 - 30, 0xFFDFDAC2, false);
		long dots = (System.currentTimeMillis() / 400) % 4;
		var what = PeakFonts.text("LOADING" + ".".repeat((int) dots), PeakFonts.MID);
		g.text(font, what, (width - font.width(PeakFonts.text("LOADING...", PeakFonts.MID))) / 2, height / 2 + 14, 0xFFDFDAC2, false);
		var s = PeakExporter.running() ? PeakExporter.status() : null;
		if (s != null) {
			var line = PeakFonts.text((s.message().isEmpty() ? "Getting today's PEAK island" : s.message()) + " (" + s.percent() + "%)", PeakFonts.MAIN);
			g.text(font, line, (width - font.width(line)) / 2, height / 2 + 34, 0xFF9C968A, false);
		}
	}

	/** The post the signs hang on, the PEAK logo, the next island's time. */
	private void under(GuiGraphicsExtractor g) {
		// the post the signs hang on
		PeakArt.Tex post = PeakArt.get("ui_blur");
		int pw = Math.max(4, (int) (0.011 * width)), px = (int) (0.254 * width), py = (int) (0.465 * height);
		if (post != null) {
			PeakArt.draw3v(g, post, px + 1, py, pw, height - py + 10, POST_EDGE, 0.3f);
			PeakArt.draw3v(g, post, px, py, pw, height - py + 10, POST, 0.3f);
		} else g.fill(px, py, px + pw, height, POST);
		// the PEAK logo (cream, with its soft shadow)
		PeakArt.Tex logo = PeakArt.get("logo"), blur = PeakArt.get("logo_blurred");
		int lw = (int) (0.40 * width), lx = (int) (0.13 * width), ly = (int) (0.11 * height);
		if (logo != null) {
			int lh = lw * logo.h() / logo.w();
			if (blur != null) PeakArt.draw(g, blur, lx + 2, ly + 4, lw, lh, 0x55000000);
			PeakArt.draw(g, logo, lx, ly, lw, lh, 0xFFDFDAC2);
		} else {
			var big = PeakFonts.text("PEAK", PeakFonts.TITLE);
			g.text(font, big, lx, ly + 20, 0xFFDFDAC2, true);
		}
		// the time till PEAK's next island (a new one every day at 17:00 UTC), on a sign at the right
		ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC), next = now.withHour(17).withMinute(0).withSecond(0).withNano(0);
		if (!next.isAfter(now)) next = next.plusDays(1);
		Duration left = Duration.between(now, next);
		String when = String.format("%dh %02dm %02ds", left.toHours(), left.toMinutesPart(), left.toSecondsPart());
		int sh = Math.max(18, (int) (0.08 * height)), sy = (int) (0.225 * height);
		var top = PeakFonts.text("NEW ISLAND IN", PeakFonts.MAIN);
		var time = PeakFonts.text(when, PeakFonts.forHeight(sh * 0.4f));
		PeakArt.Tex arrow = PeakArt.get("ui_blur_arrow");
		// (as wide as its words need, past the arrow's point)
		int point = arrow != null ? Math.round(arrow.w() * 0.45f * sh / (float) arrow.h() * 0.6f) : 0;
		int sw = Math.max((int) (0.15 * width), point + Math.max(font.width(top), font.width(time)) + 16), sx = width - sw - 2;
		if (arrow != null) {
			PeakArt.draw3(g, arrow, sx, sy + 1, sw, sh, 0xFF735C4E, 0.22f, 0.45f, true);
			PeakArt.draw3(g, arrow, sx, sy, sw, sh, SIGN, 0.22f, 0.45f, true);
		} else g.fill(sx, sy, sx + sw, sy + sh, SIGN);
		int cx = sx + point + (sw - point) / 2;
		g.text(font, top, cx - font.width(top) / 2, sy + sh * 3 / 10 + PeakFonts.middle(PeakFonts.MAIN), INK, false);
		g.text(font, time, cx - font.width(time) / 2, sy + sh * 13 / 20 + PeakFonts.middle(PeakFonts.forHeight(sh * 0.4f)), INK, false);
		// whose this is, small, in the corner
		g.text(font, PeakFonts.text("MinePeak: PEAK's island in Minecraft", PeakFonts.MAIN), 4, height - 11, 0xC0FFFFFF, true);
	}
}
