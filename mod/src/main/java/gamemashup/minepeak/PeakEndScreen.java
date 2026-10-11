package gamemashup.minepeak;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * PEAK's end screen (EndScreen), after the rescue: PEAK (or not), the run's time, each scout and how high they got (in
 * PEAK's metres), and the badges the run earned. Any key or click goes back to the game.
 */
public final class PeakEndScreen extends Screen {
	private final PeakEnding.EndRun run;
	private final long opened = System.currentTimeMillis();

	PeakEndScreen(PeakEnding.EndRun run) {
		super(Component.literal("PEAK"));
		this.run = run;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, width, height, 0xE00E2329);
		int cy = height / 6;
		var title = PeakFonts.text(run.won() ? "PEAK" : "YOU DIDN'T MAKE IT", PeakFonts.TITLE);
		g.text(font, title, (width - font.width(title)) / 2, cy, run.won() ? 0xFFF2C14A : 0xFFDFDAC2, true);
		int s = run.seconds();
		var time = PeakFonts.text(String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60), PeakFonts.BIG);
		g.text(font, time, (width - font.width(time)) / 2, cy + 34, 0xFFDFDAC2, true);
		int y = cy + 64;
		for (int i = 0; i < run.names().size(); i++) {
			var line = PeakFonts.text(run.names().get(i) + "   " + run.heights().get(i) + " M" + (run.winners().get(i) ? "   RESCUED" : ""), PeakFonts.MID);
			g.text(font, line, (width - font.width(line)) / 2, y, run.winners().get(i) ? 0xFF59C800 : 0xFFBDB6A4, true);
			y += 16;
		}
		if (!run.badges().isEmpty()) {
			y += 10;
			var head = PeakFonts.text("BADGES", PeakFonts.MID);
			g.text(font, head, (width - font.width(head)) / 2, y, 0xFFF2C14A, true);
			y += 16;
			for (String b : run.badges()) {
				var line = PeakFonts.text(b.replaceAll("([a-z])([A-Z0-9])", "$1 $2").toUpperCase(), PeakFonts.MAIN);
				g.text(font, line, (width - font.width(line)) / 2, y, 0xFFDFDAC2, true);
				y += 12;
			}
		}
		if (System.currentTimeMillis() - opened > 2000) {
			var hint = PeakFonts.text("Click to go on", PeakFonts.MAIN);
			g.text(font, hint, (width - font.width(hint)) / 2, height - 24, 0xFF8FA0A4, true);
		}
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		if (System.currentTimeMillis() - opened > 2000) onClose();
		return true;
	}

	@Override
	public boolean isPauseScreen() { return false; }
}
