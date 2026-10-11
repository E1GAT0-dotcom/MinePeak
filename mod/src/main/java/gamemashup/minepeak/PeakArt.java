package gamemashup.minepeak;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * PEAK's menu pictures, as the MinePeak Exporter saves them from the player's own PEAK (%APPDATA%/MinePeak/ui: the main
 * menu's scene, the PEAK logo, the soft shapes PEAK tints into its signs and ribbons...), loaded as textures for MinePeak's
 * menus. Until there are any (before the first export), the menus draw plain shapes instead.
 */
final class PeakArt {
	private PeakArt() { }

	static final Path DIR = IslandData.ROOT.getParent().resolve("ui");

	/** A picture: its texture and its size in pixels. */
	record Tex(Identifier id, int w, int h) { }

	private static final Map<String, Optional<Tex>> LOADED = new HashMap<>();
	private static final Map<String, Long> MISSED = new HashMap<>();
	private static long stamp = -1;

	/**
	 * The picture named name: exactly that file (with ".png"), or a sprite saved as name_xxxx.png (PEAK's sprite name and a
	 * number). Null if there's none (yet: not while Minecraft is still loading, and one that isn't there is looked for
	 * again every few seconds. It was looked for once, during Minecraft's loading, and the menu stayed plain until PEAK
	 * saved its pictures again).
	 */
	static Tex get(String name) {
		long s = stamp();
		if (s != stamp) { stamp = s; LOADED.clear(); MISSED.clear(); }
		Optional<Tex> had = LOADED.get(name);
		long now = System.currentTimeMillis();
		if (had != null && (had.isPresent() || now - MISSED.getOrDefault(name, 0L) < 3000)) return had.orElse(null);
		if (Minecraft.getInstance().gui.overlay() != null) return null;   // (still loading)
		Optional<Tex> t = load(name);
		LOADED.put(name, t);
		if (t.isEmpty()) MISSED.put(name, now);
		return t.orElse(null);
	}

	/** Are the menus' main pictures loaded (the scene, the logo, the planks)? */
	static boolean ready() {
		return get("menu_background") != null && get("logo") != null && get("ui_blur_arrow") != null;
	}

	private static long stampSeen, stampAt;

	private static long stamp() {
		long now = System.currentTimeMillis();   // (the file looked at every 2 s, not at each of the HUD's pictures each frame)
		if (now - stampAt < 2000) return stampSeen;
		stampAt = now;
		return stampSeen = stampNow();
	}

	private static long stampNow() {
		try {
			Path f = DIR.resolve("ui.json");
			return Files.isRegularFile(f) ? Files.getLastModifiedTime(f).toMillis() : 0;
		} catch (Exception e) {
			return 0;
		}
	}

	private static final Map<String, Optional<Tex>> ITEMS = new HashMap<>();

	/** One of PEAK's item icons (items/<file>), or null. */
	static Tex item(String file) {
		if (file == null) return null;
		Optional<Tex> had = ITEMS.get(file);
		if (had != null) return had.orElse(null);
		if (Minecraft.getInstance().gui.overlay() != null) return null;
		Optional<Tex> t = Optional.empty();
		try {
			Path f = PeakItemData.DIR.resolve(file);
			if (Files.isRegularFile(f)) {
				NativeImage img;
				try (InputStream in = Files.newInputStream(f)) { img = NativeImage.read(in); }
				Identifier id = MinePeak.id("item_icon/" + file.replaceAll("[^a-z0-9_]", "_"));
				Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "MinePeak " + file, img));
				t = Optional.of(new Tex(id, img.getWidth(), img.getHeight()));
			}
		} catch (Exception e) {
			MinePeak.LOG.warn("MinePeak: couldn't load PEAK's icon {}", file, e);
		}
		ITEMS.put(file, t);
		return t.orElse(null);
	}

	private static Optional<Tex> load(String name) {
		try {
			Path file = DIR.resolve(name + ".png");
			if (!Files.isRegularFile(file)) {
				Pattern p = Pattern.compile(Pattern.quote(name) + "_[0-9a-f]{4}\\.png");
				try (Stream<Path> files = Files.list(DIR)) {
					file = files.filter(f -> p.matcher(f.getFileName().toString()).matches()).findFirst().orElse(null);
				}
			}
			if (file == null) return Optional.empty();
			NativeImage img;
			try (InputStream in = Files.newInputStream(file)) { img = NativeImage.read(in); }
			if (name.startsWith("ui_blur") || name.startsWith("ui_banner") || name.startsWith("ic_")) crisp(img);
			Identifier id = MinePeak.id("ui/" + name.replaceAll("[^a-z0-9_]", "_") + "_" + (stamp & 0xFFFF));
			int w = img.getWidth(), h = img.getHeight();
			Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "MinePeak " + name, img));
			return Optional.of(new Tex(id, w, h));
		} catch (Exception e) {
			MinePeak.LOG.warn("MinePeak: couldn't load the menu picture {}", name, e);
			return Optional.empty();
		}
	}

	/**
	 * One of PEAK's soft shapes made crisp, as PEAK draws them: they're saved blurred, and PEAK's UI shader cuts them off
	 * at half their alpha (with a soft edge a few pixels wide). Their outermost row and column go: the GPU copy leaves a
	 * stray line there (it showed as dashes along the planks).
	 */
	static void crisp(NativeImage img) {
		int w = img.getWidth(), h = img.getHeight();
		for (int y = 0; y < h; y++)
			for (int x = 0; x < w; x++) {
				int c = img.getPixel(x, y), a = (c >>> 24) & 255;
				int na = x == 0 || y == 0 || x == w - 1 || y == h - 1 ? 0 : Math.max(0, Math.min(255, (a - 110) * 255 / 36));
				img.setPixel(x, y, (c & 0x00FFFFFF) | (na << 24));
			}
	}

	/** t stretched over (x, y, w, h), tinted. */
	static void draw(GuiGraphicsExtractor g, Tex t, int x, int y, int w, int h, int argb) {
		if (w <= 0 || h <= 0) return;
		g.blit(RenderPipelines.GUI_TEXTURED, t.id(), x, y, 0, 0, w, h, t.w(), t.h(), t.w(), t.h(), argb);
	}

	/**
	 * t over (x, y, w, h) in three parts across: its left capL and right capR (fractions of its width) keep their shape
	 * (scaled to the height), its middle stretches (PEAK's signs and ribbons are drawn so).
	 */
	static void draw3(GuiGraphicsExtractor g, Tex t, int x, int y, int w, int h, int argb, float capL, float capR) {
		draw3(g, t, x, y, w, h, argb, capL, capR, false);
	}

	/**
	 * The same, mirrored left to right when flip (by the picture's coordinates: a shape drawn back to front with a
	 * negative scale isn't drawn at all).
	 */
	static void draw3(GuiGraphicsExtractor g, Tex t, int x, int y, int w, int h, int argb, float capL, float capR, boolean flip) {
		if (w <= 0 || h <= 0) return;
		if (flip) { float c = capL; capL = capR; capR = c; }
		float scale = h / (float) t.h();
		int lw = Math.round(t.w() * capL * scale), rw = Math.round(t.w() * capR * scale);
		if (lw + rw > w) { float f = w / (float) (lw + rw); lw = Math.round(lw * f); rw = w - lw; }
		int mw = w - lw - rw;
		int lu = Math.round(t.w() * capL), ru = Math.round(t.w() * capR), mu = t.w() - lu - ru;
		if (!flip) {
			g.blit(RenderPipelines.GUI_TEXTURED, t.id(), x, y, 0, 0, lw, h, lu, t.h(), t.w(), t.h(), argb);
			if (mw > 0) g.blit(RenderPipelines.GUI_TEXTURED, t.id(), x + lw, y, lu, 0, mw, h, mu, t.h(), t.w(), t.h(), argb);
			g.blit(RenderPipelines.GUI_TEXTURED, t.id(), x + lw + mw, y, lu + mu, 0, rw, h, ru, t.h(), t.w(), t.h(), argb);
		} else {
			// (each part read right to left: its u from its right edge, a negative width across)
			g.blit(RenderPipelines.GUI_TEXTURED, t.id(), x, y, t.w(), 0, lw, h, -lu, t.h(), t.w(), t.h(), argb);
			if (mw > 0) g.blit(RenderPipelines.GUI_TEXTURED, t.id(), x + lw, y, t.w() - lu, 0, mw, h, -mu, t.h(), t.w(), t.h(), argb);
			g.blit(RenderPipelines.GUI_TEXTURED, t.id(), x + lw + mw, y, ru, 0, rw, h, -ru, t.h(), t.w(), t.h(), argb);
		}
	}

	/** The same, in three parts down (the signpost's post). */
	static void draw3v(GuiGraphicsExtractor g, Tex t, int x, int y, int w, int h, int argb, float cap) {
		if (w <= 0 || h <= 0) return;
		float scale = w / (float) t.w();
		int ch = Math.min(h / 2, Math.round(t.h() * cap * scale)), cv = Math.round(t.h() * cap);
		g.blit(RenderPipelines.GUI_TEXTURED, t.id(), x, y, 0, 0, w, ch, t.w(), cv, t.w(), t.h(), argb);
		g.blit(RenderPipelines.GUI_TEXTURED, t.id(), x, y + ch, 0, cv, w, h - 2 * ch, t.w(), t.h() - 2 * cv, t.w(), t.h(), argb);
		g.blit(RenderPipelines.GUI_TEXTURED, t.id(), x, y + h - ch, 0, t.h() - cv, w, ch, t.w(), cv, t.w(), t.h(), argb);
	}
}
