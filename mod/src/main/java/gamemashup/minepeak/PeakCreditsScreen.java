package gamemashup.minepeak;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.CreditsAndAttributionScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Who made what: PEAK (its makers), MinePeak, the fonts, and Minecraft's own credits. */
public final class PeakCreditsScreen extends Screen {
	private final Screen back;

	private static final String[] LINES = {
		"CREDITS",
		"",
		"PEAK is a game by Landfall and Aggro Crab.",
		"MinePeak builds PEAK's island of the day, its menus' pictures and its words",
		"from your own copy of PEAK, on your own computer. None of PEAK comes with it.",
		"",
		"MinePeak: made with Claude (Anthropic) for GameMashsup.",
		"",
		"Fonts, under the SIL Open Font License 1.1:",
		"Darumadrop One, by The Darumadrop Project Authors",
		"Londrina Solid, by The Londrina Solid Authors",
	};

	public PeakCreditsScreen(Screen back) {
		super(Component.literal("Credits"));
		this.back = back;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.literal("Minecraft's credits"), b -> minecraft.gui.setScreen(new CreditsAndAttributionScreen(this)))
			.bounds(width / 2 - 104, height - 52, 208, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(width / 2 - 104, height - 28, 208, 20).build());
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.fill(0, 0, width, height, 0xFF000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int y = 24;
		for (int i = 0; i < LINES.length; i++) {
			var t = PeakFonts.text(LINES[i], i == 0 ? PeakFonts.BIG : PeakFonts.MAIN);
			g.text(font, t, (width - font.width(t)) / 2, y, 0xFFFFFFFF, false);
			y += i == 0 ? 30 : 13;
		}
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	@Override
	public void onClose() { minecraft.gui.setScreen(back); }
}
