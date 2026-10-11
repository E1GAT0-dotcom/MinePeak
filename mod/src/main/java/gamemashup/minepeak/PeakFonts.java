package gamemashup.minepeak;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;

/**
 * PEAK's fonts in Minecraft (assets/minepeak/font: TrueType fonts, each a Minecraft font of its own size). PEAK's text is
 * DarumaDrop One (its menus and most of what it writes) and Londrina Solid Black (the stage's title card), both under
 * the SIL Open Font License (the OFL files beside them).
 */
public final class PeakFonts {
	private PeakFonts() { }

	/** Menus, buttons, most text. */
	public static final FontDescription MAIN = new FontDescription.Resource(MinePeak.id("peak"));
	/** Bigger buttons (the signpost's planks, the pause menu's ribbons). */
	public static final FontDescription MID = new FontDescription.Resource(MinePeak.id("peak_mid"));
	/** Headings. */
	public static final FontDescription BIG = new FontDescription.Resource(MinePeak.id("peak_big"));
	/** The title card's stage name. */
	public static final FontDescription TITLE = new FontDescription.Resource(MinePeak.id("peak_title"));
	/** The title card's day line. */
	public static final FontDescription TITLE_SMALL = new FontDescription.Resource(MinePeak.id("peak_title_small"));

	/** The biggest of PEAK's Darumadrop sizes whose letters are about h high (MAIN at the least). */
	public static FontDescription forHeight(float h) {
		return h >= 15 ? BIG : h >= 10 ? MID : MAIN;
	}

	/** The next size down (MAIN stays MAIN). */
	public static FontDescription smaller(FontDescription f) {
		return f == BIG ? MID : MAIN;
	}

	/** Where to draw a line of f from for it to sit in the middle of y 0 (its letters' middle). */
	public static int middle(FontDescription f) {
		return f == BIG ? -9 : f == MID ? -6 : -4;
	}

	public static MutableComponent text(String s, FontDescription font) {
		return Component.literal(s).withStyle(st -> st.withFont(font));
	}

	public static MutableComponent text(Component c, FontDescription font) {
		return c.copy().withStyle(st -> st.withFont(font));
	}
}
