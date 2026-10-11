package gamemashup.minepeak;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** An open backpack, PEAK's colours: its four places over the scout's three pockets, and nothing else. */
public class BackpackScreen extends AbstractContainerScreen<BackpackMenu> {
	private static final int TEAL = 0xF01A444D, CREAM = 0xFFDFDAC2, DIM = 0x58000000;

	public BackpackScreen(BackpackMenu menu, Inventory inv, Component title) {
		super(menu, inv, title, 140, 96);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractBackground(g, mouseX, mouseY, a);
		int x = (width - imageWidth) / 2, y = (height - imageHeight) / 2;
		PeakHud.roundRect(g, x - 2, y - 2, imageWidth + 4, imageHeight + 4, 10, CREAM);
		PeakHud.roundRect(g, x, y, imageWidth, imageHeight, 9, TEAL);
		for (int[] at : BackpackMenu.PACK_AT) slot(g, x + at[0], y + at[1]);
		for (int[] at : BackpackMenu.POCKET_AT) slot(g, x + at[0], y + at[1]);
	}

	private static void slot(GuiGraphicsExtractor g, int x, int y) {
		PeakHud.roundRect(g, x - 3, y - 3, 22, 22, 6, CREAM);
		PeakHud.roundRect(g, x - 2, y - 2, 20, 20, 5, 0xFF12343B);
		PeakHud.roundRect(g, x - 1, y - 1, 18, 18, 4, DIM);
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor g, int xm, int ym) {
		var t = PeakFonts.text(title.getString().toUpperCase(), PeakFonts.MID);
		g.text(font, t, (imageWidth - font.width(t)) / 2, 7, CREAM, false);
		var p = PeakFonts.text("POCKETS", PeakFonts.MAIN);
		g.text(font, p, (imageWidth - font.width(p)) / 2, 56, 0xFFBDB6A4, false);
	}
}
