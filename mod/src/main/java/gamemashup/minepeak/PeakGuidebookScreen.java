package gamemashup.minepeak;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * PEAK's guidebook as PEAK draws it: each spread a picture (exported from the player's PEAK: items/guidebook), turned
 * with the arrow keys, A/D, or a click on either half. A torn page has just its one.
 */
public final class PeakGuidebookScreen extends Screen {
	private final List<String> pages;
	private int at;

	private PeakGuidebookScreen(List<String> pages) {
		super(Component.literal("Guidebook"));
		this.pages = pages;
	}

	/** The pictures of a guidebook (PEAK's name for it), or none (not exported). */
	static List<String> pictures(String item) {
		List<String> l = new ArrayList<>();
		try {
			var f = PeakItemData.DIR.resolve("guidebook").resolve("guidebook.json");
			if (!Files.isRegularFile(f)) return l;
			var books = JsonParser.parseString(Files.readString(f)).getAsJsonObject().getAsJsonObject("books");
			if (books.has(item)) for (var e : books.getAsJsonArray(item)) l.add("guidebook/" + e.getAsString());
		} catch (Exception e) {
			MinePeak.LOG.warn("MinePeak: couldn't read the guidebook's pages", e);
		}
		return l;
	}

	/** Opens a guidebook: PEAK's pictures when there are any (true), else nothing (false: the words are shown instead). */
	static boolean open(String item) {
		var l = pictures(item);
		if (l.isEmpty()) return false;
		net.minecraft.client.Minecraft.getInstance().gui.setScreen(new PeakGuidebookScreen(l));
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.fill(0, 0, width, height, 0xC0000000);
		var t = PeakArt.item(pages.get(at));
		if (t != null) {
			float s = Math.min(width * 0.92f / t.w(), height * 0.85f / t.h());
			int w = Math.round(t.w() * s), h = Math.round(t.h() * s);
			PeakArt.draw(g, t, (width - w) / 2, (height - h) / 2 - 6, w, h, 0xFFFFFFFF);
		}
		var line = PeakFonts.text((at + 1) + " / " + pages.size() + (pages.size() > 1 ? "     < A  D >" : ""), PeakFonts.MAIN);
		g.text(font, line, (width - font.width(line)) / 2, height - 14, 0xFFDFDAC2, true);
	}

	private void turn(int by) { at = Math.max(0, Math.min(pages.size() - 1, at + by)); }

	@Override
	public boolean keyPressed(KeyEvent e) {
		int k = e.key();
		if (k == 262 || k == 68) { turn(1); return true; }   // (right, D)
		if (k == 263 || k == 65) { turn(-1); return true; }   // (left, A)
		return super.keyPressed(e);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		turn(event.x() > width / 2.0 ? 1 : -1);
		return true;
	}

	@Override
	public boolean isPauseScreen() { return false; }
}
