package gamemashup.minepeak;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * PEAK's HUD in place of Minecraft's (for scouts; creative keeps Minecraft's): its item slots at the bottom right (three
 * pockets and the backpack's, the one in hand bigger and filled, its name over it, a fuel bar for what has uses), and its
 * stamina bar at the bottom left (the green of the stamina in what's left of the bar, each status's piece after it in
 * its colour with its icon over it, the bonus stamina under it). Made of PEAK's own pictures and laid out where PEAK puts
 * them (exported from the player's PEAK: ui/hud.json, ui.json's "hud" pictures; plain shapes until there's an export).
 * Minecraft's hotbar, hearts, hunger, armour and experience bar don't show for scouts (PEAK's bar shows all that).
 */
public final class PeakHud {
	private PeakHud() { }

	private static final Identifier[] HIDDEN = { VanillaHudElements.HOTBAR, VanillaHudElements.HEALTH_BAR, VanillaHudElements.FOOD_BAR,
		VanillaHudElements.ARMOR_BAR, VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.INFO_BAR, VanillaHudElements.HELD_ITEM_TOOLTIP };

	static void init() {
		for (Identifier id : HIDDEN)
			HudElementRegistry.replaceElement(id, vanilla -> (g, delta) -> { if (!active()) vanilla.extractRenderState(g, delta); });
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, MinePeak.id("peak_hud"), PeakHud::draw);
	}

	static boolean active() {
		var p = Minecraft.getInstance().player;
		return p != null && PeakInventory.limited(p);
	}

	// ---- PEAK's pictures and colours ----

	private static final Map<String, String> FILES = new HashMap<>();
	private static final Map<String, Integer> COLORS = new HashMap<>();
	private static long stamp = -1;

	private static long checkedAt;

	private static void load() {
		long now = System.currentTimeMillis();   // (the file looked at every 2 s, not every frame)
		if (now - checkedAt < 2000) return;
		checkedAt = now;
		Path f = PeakArt.DIR.resolve("hud.json");
		long s;
		try { s = Files.isRegularFile(f) ? Files.getLastModifiedTime(f).toMillis() : 0; } catch (Exception e) { s = 0; }
		if (s == stamp) return;
		stamp = s;
		FILES.clear();
		COLORS.clear();
		if (s == 0) return;
		try {
			JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
			for (JsonElement e : o.getAsJsonArray("afflictions")) {
				JsonObject a = e.getAsJsonObject();
				if (a.has("petrify") && a.get("petrify").getAsBoolean()) continue;
				String t = a.get("type").getAsString();
				if (a.has("file")) FILES.put("icon." + t, strip(a.get("file").getAsString()));
				if (a.has("color")) COLORS.put(t, color(a.get("color").getAsString()));
			}
			JsonObject bar = o.getAsJsonObject("bar");
			for (String k : new String[] { "backing", "extraStaminaIcon", "shield", "campfire", "extraBarOutline" })
				if (bar.has(k) && bar.get(k).isJsonObject() && bar.getAsJsonObject(k).has("file")) FILES.put(k, strip(bar.getAsJsonObject(k).get("file").getAsString()));
			if (bar.has("defaultBackingColor")) COLORS.put("backing", color(bar.get("defaultBackingColor").getAsString()));
			if (bar.has("outOfStaminaBackingColor")) COLORS.put("out", color(bar.get("outOfStaminaBackingColor").getAsString()));
			JsonObject slot = o.getAsJsonArray("slots").get(0).getAsJsonObject();
			for (String k : new String[] { "outline", "fill" }) if (slot.has(k) && slot.getAsJsonObject(k).has("file")) FILES.put("slot." + k, strip(slot.getAsJsonObject(k).get("file").getAsString()));
			if (slot.has("defaultElementColor")) COLORS.put("element", color(slot.get("defaultElementColor").getAsString()));
			for (String k : new String[] { "backpackIcon", "carryingIcon" }) if (slot.has(k) && slot.getAsJsonObject(k).has("file")) FILES.put(k, strip(slot.getAsJsonObject(k).get("file").getAsString()));
			JsonObject pack = o.getAsJsonArray("slots").get(3).getAsJsonObject();
			if (pack.has("icon") && pack.getAsJsonObject("icon").has("file")) FILES.put("emptyBackpack", strip(pack.getAsJsonObject("icon").get("file").getAsString()));
		} catch (Exception e) {
			MinePeak.LOG.warn("MinePeak: couldn't read PEAK's HUD layout", e);
		}
		// the rest by PEAK's picture names (as MenuArt saves them)
		FILES.putIfAbsent("ring", "ui_blur_outlne");
		FILES.putIfAbsent("thick", "ui_blur_outlne_thick");
		FILES.putIfAbsent("blob", "ui_blur");
		FILES.putIfAbsent("stripes", "ditherstripes_sprite");
		FILES.putIfAbsent("cap", "ui_blur_outlneend");
	}

	private static String strip(String file) { return file.endsWith(".png") ? file.substring(0, file.length() - 4) : file; }

	private static int color(String hex) {
		String h = hex.startsWith("#") ? hex.substring(1) : hex;
		int rgb = Integer.parseInt(h.substring(0, 6), 16), a = h.length() >= 8 ? Integer.parseInt(h.substring(6, 8), 16) : 255;
		return a << 24 | rgb;
	}

	private static PeakArt.Tex art(String key) { String f = FILES.get(key); return f != null ? PeakArt.get(f) : null; }

	private static int col(String key, int def) { return COLORS.getOrDefault(key, def); }

	// ---- drawing ----

	private static final String[] ORDER = { "Weight", "Hunger", "Cold", "Injury", "Poison", "Crab", "Drowsy", "Hot", "Curse", "Thorns", "Spores", "Web", "Arrow", "FlyTrap" };
	private static final int[] DEFAULT_COLORS = { 0xFFA65A1C, 0xFFFFBD16, 0xFF00BCFF, 0xFFFF5300, 0xFFA139FF, 0xFFE13542, 0xFFFF5CA4, 0xFFC80918, 0xFF1B0043,
		0xFF768E00, 0xFFA65C63, 0xFFE6E6E7, 0xFFD1A072, 0xFF187B32 };
	private static float shownStamina = 1, selectedGrow;
	private static int lastSelected = -1;

	private static void draw(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!active()) return;
		load();
		float s = g.guiHeight() / 1080f;
		drawBar(g, mc, s);
		drawSlots(g, mc, s);
		PeakInventoryClient.drawPrompt(g, mc);
		PeakGearClient.drawCompass(g, mc);
	}

	/** PEAK's stamina bar, bottom left: 600 x 33 (in PEAK's 1080-high canvas), 70 from the left, 66 from the bottom. */
	private static void drawBar(GuiGraphicsExtractor g, Minecraft mc, float s) {
		int x = Math.round(70 * s), h = Math.max(4, Math.round(33 * s)), w = Math.round(600 * s);
		int y = g.guiHeight() - Math.round(66 * s) - h;
		float[] st = new float[ORDER.length];
		float sum = 0;
		boolean numb = PeakStatusClient.flag(PeakStatus.F_NUMB);
		for (int i = 0; i < ORDER.length; i++) { st[i] = numb ? 0 : PeakStatusClient.status(PeakStatus.index(ORDER[i])); sum += st[i]; }
		float max = Math.max(0, 1 - sum);
		shownStamina += (PeakClimbClient.stamina - shownStamina) * 0.3f;
		// the shadow, then the back of what's left of the bar
		roundRect(g, x - Math.round(5 * s), y - Math.round(4 * s), w + Math.round(10 * s), h + Math.round(12 * s), Math.round(18 * s), 0x46000000);
		int mw = Math.round(w * max);
		boolean outOf = PeakClimbClient.stamina <= 0.005f && (System.currentTimeMillis() / 250) % 2 == 0;
		blob(g, x, y, Math.max(mw, h), h, outOf ? col("out", 0xFF900202) : col("backing", 0xFF1A444D));
		int sw = Math.round(w * Math.min(max, shownStamina));
		if (sw > 2) blob(g, x, y, Math.max(sw, Math.min(h, mw)), h, PeakStatusClient.flag(PeakStatus.F_INFINITE) ? rainbow() : 0xFF59C800);
		// each status after it, in its colour, its icon over it
		int sx = x + mw;
		for (int i = 0; i < ORDER.length; i++) {
			if (st[i] < 0.01f) continue;
			int segW = Math.max(Math.round(15 * s), Math.round(w * Math.min(st[i], 2)));
			int c = col(ORDER[i], DEFAULT_COLORS[i]);
			roundRect(g, sx, y + Math.round(1 * s), segW, h - Math.round(2 * s), Math.round(8 * s), (c & 0x00FFFFFF) | 0x7D000000);
			stripes(g, sx + Math.round(3 * s), y + Math.round(5 * s), segW - Math.round(6 * s), h - Math.round(10 * s), c);
			ring(g, "thick", sx, y, segW, h, c);
			var icon = art("icon." + ORDER[i]);
			int is = Math.round(30 * s);
			if (icon != null && segW >= is / 2) PeakArt.draw(g, icon, sx + (segW - is) / 2, y - is - Math.round(8 * s), is, is, c);
			sx += segW;
		}
		// the outline round the whole bar
		int ow = Math.max(w, sx - x) + Math.round(14 * s);
		ring(g, "ring", x - Math.round(7 * s), y - Math.round(5 * s), ow, h + Math.round(11 * s), col("element", 0xFFDFDAC2));
		// bonus stamina (PEAK's extra stamina): a lime bar under it, the lightning by it
		float bonus = PeakClimbClient.bonus;
		if (bonus > 0.005f) {
			int by = y + h + Math.round(10 * s), bh = Math.max(3, Math.round(12 * s));
			blob(g, x + Math.round(24 * s), by, Math.max(bh, Math.round(w * bonus)), bh, 0xFFBFEC1C);
			var bolt = art("extraStaminaIcon");
			if (bolt != null) PeakArt.draw(g, bolt, x - Math.round(4 * s), by - Math.round(4 * s), Math.round(20 * s), Math.round(20 * s), 0xFFBFEC1B);
		}
		// invincible: PEAK's shield; by a lit campfire (no hunger): its campfire
		if (PeakStatusClient.flag(PeakStatus.F_INVINCIBLE)) { var sh = art("shield"); if (sh != null) PeakArt.draw(g, sh, x - Math.round(60 * s), y - Math.round(6 * s), Math.round(45 * s), Math.round(45 * s), 0xFFD48E00); }
		if (PeakStatusClient.flag(PeakStatus.F_NO_HUNGER)) { var cf = art("campfire"); if (cf != null) PeakArt.draw(g, cf, x + ow, y - Math.round(8 * s), Math.round(50 * s), Math.round(50 * s), 0xFFFF7C10); }
	}

	/** PEAK's slots, bottom right: three pockets (70, the one in hand 84) and the backpack's (100), 75 from the right. */
	private static void drawSlots(GuiGraphicsExtractor g, Minecraft mc, float s) {
		var inv = mc.player.getInventory();
		int sel = inv.getSelectedSlot();
		if (sel != lastSelected) { selectedGrow = 0; lastSelected = sel; }
		selectedGrow = Math.min(1, selectedGrow + 0.12f);
		float grow = 1 + 0.2f * elastic(selectedGrow);
		int bottom = g.guiHeight() - Math.round(57 * s);
		int right = g.guiWidth() - Math.round(75 * s);
		int elem = col("element", 0xFFDFDAC2);
		// the backpack's slot
		int bpw = Math.round(100 * s);
		int bx = right - bpw;
		slot(g, mc, s, bx, bottom - bpw, bpw, inv.getItem(PeakInventory.BACKPACK), sel == PeakInventory.BACKPACK, elem, true);
		int xr = bx - Math.round(30 * s);
		for (int i = PeakInventory.POCKETS - 1; i >= 0; i--) {
			int size = Math.round(70 * s * (i == sel ? grow : 1));
			int sx = xr - size;
			slot(g, mc, s, sx, bottom - size, size, inv.getItem(i), i == sel, elem, false);
			xr = sx - Math.round(10 * s);
		}
	}

	private static void slot(GuiGraphicsExtractor g, Minecraft mc, float s, int x, int y, int size, ItemStack stack, boolean selected, int elem, boolean backpack) {
		int inset = Math.max(1, size * 5 / 70);
		roundRect(g, x + inset, y + inset, size - 2 * inset, size - 2 * inset, Math.round(size * 0.3f), 0x58000000);
		if (selected && !stack.isEmpty()) roundRect(g, x + inset / 2 + 1, y + inset / 2 + 1, size - inset - 2, size - inset - 2, Math.round(size * 0.15f), (elem & 0x00FFFFFF) | 0xFF000000);
		var ring = art("slot.outline");
		if (ring != null) PeakArt.draw(g, ring, x, y, size, size, elem);
		else { g.fill(x, y, x + size, y + 1, elem); g.fill(x, y + size - 1, x + size, y + size, elem); g.fill(x, y, x + 1, y + size, elem); g.fill(x + size - 1, y, x + size, y + size, elem); }
		if (stack.isEmpty()) {
			if (backpack) { var bp = art("emptyBackpack"); if (bp != null) PeakArt.draw(g, bp, x - size / 10, y - size / 10, size * 12 / 10, size * 12 / 10, 0x80000000); }
			return;
		}
		// the item: PEAK's icon (tinted by its cooking), else Minecraft's
		var def = PeakItems.def(stack);
		PeakArt.Tex icon = def != null ? PeakArt.item(def.icon()) : null;
		if (icon != null) {
			int tint = 0xFF000000 | PeakItemData.cookColor(PeakItems.cooked(stack));
			PeakArt.draw(g, icon, x + size / 12, y + size / 12, size - size / 6, size - size / 6, tint);
		} else {
			float k = (size - size / 4f) / 16f;
			var pose = g.pose();
			pose.pushMatrix();
			pose.translate(x + size / 8f, y + size / 8f);
			pose.scale(k, k);
			g.item(stack, 0, 0);
			pose.popMatrix();
			if (stack.getCount() > 1) g.text(mc.font, Component.literal(Integer.toString(stack.getCount())), x + size - mc.font.width(Integer.toString(stack.getCount())) - 2, y + size - 9, 0xFFFFFFFF, true);
		}
		// its fuel or uses (PEAK's orange fuel bar on the slot's top edge)
		if (stack.getItem() instanceof PeakItem pi && pi.isBarVisible(stack)) {
			int fw = size * 51 / 70, fh = Math.max(2, size * 6 / 70), fx = x + (size - fw) / 2, fy = y - fh / 2;
			g.fill(fx - 1, fy - 1, fx + fw + 1, fy + fh + 1, elem);
			g.fill(fx, fy, fx + fw, fy + fh, 0xFF1A444D);
			g.fill(fx, fy, fx + Math.round(fw * PeakGear.left(stack, def)), fy + fh, 0xFFFF7200);
		}
		// its name over it, while it's in hand
		if (selected) {
			var name = PeakFonts.text(stack.getHoverName().getString(), PeakFonts.MAIN);
			int tw = mc.font.width(name);
			g.text(mc.font, name, x + (size - tw) / 2, y - Math.round(28 * s) - 6, elem, true);
		}
	}

	// ---- PEAK's shapes ----

	/** A rounded rectangle (PEAK's ProceduralImage). */
	static void roundRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int argb) {
		if (w <= 0 || h <= 0) return;
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		for (int i = 0; i < h; i++) {
			int inset = 0;
			if (i < r) inset = r - (int) Math.round(Math.sqrt(r * r - (r - i - 0.5) * (r - i - 0.5)));
			else if (i >= h - r) { int j = h - 1 - i; inset = r - (int) Math.round(Math.sqrt(r * r - (r - j - 0.5) * (r - j - 0.5))); }
			g.fill(x + inset, y + i, x + w - inset, y + i + 1, argb);
		}
	}

	/** PEAK's soft blob (ui_blur, made crisp) over (x, y, w, h), tinted; a rounded rectangle without it. */
	private static void blob(GuiGraphicsExtractor g, int x, int y, int w, int h, int argb) {
		var t = art("blob");
		if (t != null) PeakArt.draw3(g, t, x, y, w, h, argb, 0.45f, 0.45f);
		else roundRect(g, x, y, w, h, h / 2, argb);
	}

	/** One of PEAK's outlines (a soft ring, made crisp) round (x, y, w, h). */
	private static void ring(GuiGraphicsExtractor g, String which, int x, int y, int w, int h, int argb) {
		var t = art(which);
		if (t != null) PeakArt.draw3(g, t, x, y, w, h, argb, 0.48f, 0.48f);
		else { g.fill(x, y, x + w, y + 1, argb); g.fill(x, y + h - 1, x + w, y + h, argb); g.fill(x, y, x + 1, y + h, argb); g.fill(x + w - 1, y, x + w, y + h, argb); }
	}

	/** PEAK's diagonal stripes, tiled, in a status's colour. */
	private static void stripes(GuiGraphicsExtractor g, int x, int y, int w, int h, int argb) {
		if (w <= 0 || h <= 0) return;
		var t = art("stripes");
		if (t == null) { g.fill(x, y, x + w, y + h, argb); return; }
		int tile = Math.max(4, h * 2);
		for (int tx = 0; tx < w; tx += tile) {
			int cw = Math.min(tile, w - tx);
			g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, t.id(), x + tx, y, 0, 0, cw, h, Math.round(t.w() * cw / (float) tile), Math.round(t.h() * h / (float) tile), t.w(), t.h(), argb);
		}
	}

	private static float elastic(float t) {
		if (t >= 1) return 1;
		return (float) (Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * (2 * Math.PI) / 3) + 1);
	}

	private static int rainbow() {
		float hue = (System.currentTimeMillis() % 2000) / 2000f;
		return 0xFF000000 | java.awt.Color.HSBtoRGB(hue, 0.6f, 1f) & 0xFFFFFF;
	}
}
