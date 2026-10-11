package gamemashup.minepeak;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;

/**
 * A button drawn as PEAK draws its menus' buttons: one of its soft shapes, tinted, three times over (a shadow, a darker
 * edge, the face), with the label in PEAK's font. A plank of the main menu's signpost (an arrow, or a double arrow,
 * tilted a little, with hiker icons) or a ribbon of the pause menu (with dotted lines along it).
 */
final class PeakButton extends AbstractButton {
	enum Shape { ARROW, DOUBLE_ARROW, RIBBON }

	private final Shape shape;
	private final int face, edge, text, icons;
	private final float tilt;
	private final boolean flip;
	private final Runnable action;

	PeakButton(int x, int y, int w, int h, String label, Shape shape, int face, int edge, int text, float tilt, boolean flip, int icons, Runnable action) {
		super(x, y, w, h, Component.literal(label));
		this.shape = shape; this.face = face; this.edge = edge; this.text = text; this.tilt = tilt; this.flip = flip; this.icons = icons; this.action = action;
	}

	@Override public void onPress(InputWithModifiers input) { action.run(); }

	@Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		boolean hot = isHoveredOrFocused();
		int x = getX(), y = getY(), w = getWidth(), h = getHeight();
		g.pose().pushMatrix();
		g.pose().translate(x + w / 2f, y + h / 2f);
		g.pose().rotate((float) Math.toRadians(tilt + (hot && shape != Shape.RIBBON ? -1.5f : 0)));
		if (hot) g.pose().scale(1.05f, 1.05f);
		int lx = -w / 2, ly = -h / 2;
		PeakArt.Tex t = PeakArt.get(shape == Shape.ARROW ? "ui_blur_arrow" : shape == Shape.DOUBLE_ARROW ? "ui_blur_doublearrow" : "ui_banner");
		int faceNow = hot ? lighter(face) : face;
		if (t != null) {
			float capL = shape == Shape.ARROW ? 0.22f : shape == Shape.DOUBLE_ARROW ? 0.36f : 0.3f;
			float capR = shape == Shape.ARROW ? 0.45f : shape == Shape.DOUBLE_ARROW ? 0.36f : 0.3f;
			PeakArt.draw3(g, t, lx, ly + 2, w, h, shape == Shape.RIBBON ? 0x44000000 : 0x87000000, capL, capR, flip);
			PeakArt.draw3(g, t, lx, ly + 1, w, h, edge, capL, capR, flip);
			PeakArt.draw3(g, t, lx, ly, w, h, faceNow, capL, capR, flip);
		} else {
			g.fill(lx, ly + 2, lx + w, ly + h + 2, 0x87000000);
			g.fill(lx, ly, lx + w, ly + h, faceNow);
		}
		var font = Minecraft.getInstance().font;
		// the ribbon's dotted lines, along its top and bottom (inside it, as PEAK's)
		if (shape == Shape.RIBBON && h >= 14) {
			int top = ly + Math.round(h * 0.2f), bottom = ly + h - 1 - Math.round(h * 0.2f);
			for (int px = lx + w / 8; px < lx + w - w / 8 - 2; px += 5) {
				g.fill(px, top, px + 3, top + 1, edge);
				g.fill(px, bottom, px + 3, bottom + 1, edge);
			}
		}
		// the label: PEAK's font as big as the shape takes (PEAK's text is about half a plank high, less on a ribbon),
		// smaller, then squeezed, if it's too long for it
		PeakArt.Tex hiker = icons > 0 ? PeakArt.get("scouthiker") : null;
		int ih = Math.max(6, h * 5 / 9), iw = hiker != null ? ih * hiker.w() / hiker.h() : 0;
		int room = shape == Shape.RIBBON ? w - 2 * Math.max(6, h * 3 / 4) : shape == Shape.DOUBLE_ARROW ? (int) (w * 0.62f)
			: (int) (w * 0.66f) - (hiker != null ? icons * (iw + 1) + 6 : 0);
		FontDescription f = PeakFonts.forHeight(shape == Shape.RIBBON ? h * 0.42f : h * 0.58f);
		var label = PeakFonts.text(getMessage(), f);
		while (font.width(label) > room && f != PeakFonts.MAIN) { f = PeakFonts.smaller(f); label = PeakFonts.text(getMessage(), f); }
		int tw = font.width(label);
		float fit = Math.min(1f, room / (float) Math.max(1, tw));
		int tx = shape == Shape.RIBBON || shape == Shape.DOUBLE_ARROW ? -Math.round(tw * fit / 2) : flip ? -w / 2 + (int) (w * 0.6f) - Math.round(tw * fit / 2) : lx + Math.max(4, h / 3);
		g.pose().pushMatrix();
		g.pose().translate(tx, PeakFonts.middle(f) * fit);
		g.pose().scale(fit, fit);
		g.text(font, label, 0, 0, text, shape == Shape.RIBBON);
		g.pose().popMatrix();
		// hikers on the sign (as many as the game takes, as PEAK's)
		if (hiker != null)
			for (int i = 0; i < icons; i++) PeakArt.draw(g, hiker, tx + Math.round(tw * fit) + 6 + i * (iw + 1), -ih / 2, iw, ih, text);
		g.pose().popMatrix();
	}

	private static int lighter(int argb) {
		int r = Math.min(255, ((argb >> 16) & 255) + 22), gg = Math.min(255, ((argb >> 8) & 255) + 22), b = Math.min(255, (argb & 255) + 22);
		return (argb & 0xFF000000) | (r << 16) | (gg << 8) | b;
	}
}
