package gamemashup.minepeak;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Util;

/**
 * Draws PEAK's title card (HeroTitle) as PEAK times it (GUIManager.SetHeroTitle): a dark band fades in over half a
 * second, the stage's name types in a letter every 0.1 s, then (half a second later) the day line and the time of day a
 * letter every 0.066 s, it holds for 1.5 s, and fades out over 2. In PEAK's fonts (PeakFonts): the name in Londrina Solid.
 */
public final class HeroTitleHud {
	private HeroTitleHud() { }

	private static HeroTitle.Payload card;
	private static long startMs;

	static void init() {
		ClientPlayNetworking.registerGlobalReceiver(HeroTitle.Payload.TYPE, (payload, context) -> {
			card = payload;
			startMs = Util.getMillis();
			PeakSounds.onTitle(payload.title());   // (PEAK's music for the stage, with its title)
		});
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, MinePeak.id("hero_title"), HeroTitleHud::draw);
	}

	private static void draw(GuiGraphicsExtractor g, DeltaTracker delta) {
		HeroTitle.Payload c = card;
		if (c == null) return;
		double t = (Util.getMillis() - startMs) / 1000.0;
		double title0 = 0.5, day0 = title0 + c.title().length() * 0.1 + 0.5, time0 = day0 + c.day().length() * 0.066 + 0.5;
		double fade0 = time0 + c.time().length() * 0.066 + 1.5, end = fade0 + 2.0;
		if (t >= end) { card = null; return; }
		double alpha = t < fade0 ? 1 : 1 - (t - fade0) / 2.0;
		double band = Math.min(1, t / 0.5) * alpha;
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() != null && mc.gui.screen().isPauseScreen()) return;   // (not over the pause menu; it plays on behind it)
		Font font = mc.font;
		int w = g.guiWidth(), cy = g.guiHeight() / 3;
		// the dark band behind it, soft at its top and bottom
		int a = (int) (band * 0.5 * 255);
		g.fillGradient(0, cy - 44, w, cy - 14, 0, a << 24);
		g.fill(0, cy - 14, w, cy + 30, a << 24);
		g.fillGradient(0, cy + 30, w, cy + 60, a << 24, 0);
		text(g, font, typed(c.title(), t - title0, 0.1), PeakFonts.TITLE, w / 2, cy - 14, alpha, 0xFFFFFF);
		text(g, font, typed(c.day(), t - day0, 0.066), PeakFonts.TITLE_SMALL, w / 2, cy + 26, alpha, 0xFFFFFF);
		text(g, font, typed(c.time(), t - time0, 0.066), PeakFonts.MAIN, w / 2, cy + 42, alpha, 0xD8D8D8);
	}

	/** As much of s as has typed in after t seconds, a letter every step. */
	private static String typed(String s, double t, double step) {
		if (t <= 0) return "";
		return s.substring(0, Math.min(s.length(), (int) (t / step) + 1));
	}

	/** s in PEAK's font, centred on cx with its middle at y. */
	private static void text(GuiGraphicsExtractor g, Font font, String s, net.minecraft.network.chat.FontDescription f, int cx, int y, double alpha, int rgb) {
		if (s.isEmpty() || alpha <= 0.02) return;
		var c = PeakFonts.text(s, f);
		int argb = ((int) (alpha * 255) << 24) | rgb;
		g.text(font, c, cx - font.width(c) / 2, y - font.lineHeight / 2, argb, true);
	}
}
