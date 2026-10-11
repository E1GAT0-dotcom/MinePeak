package gamemashup.minepeak;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * PEAK's statuses on the screen, as PEAK plays them (GUIManager.AddStatusFX): each time a status goes up (by PEAK's
 * steps of 0.025), its effect flashes over the screen's edges and fades (ScreenVFX: full for its delay, then gone over
 * its duration), and the view shakes (GamefeelHandler's PerlinShake). A knock's dark red, poison's purple swirls, the
 * spores', thorns' green, the heat's orange flames, drowsiness's pink, the curse's dark, the cold's blue (its Volume's
 * "Danger" edge and frosted lens). Lasting ones while they last: an energy drink's (speed lines), a heat pack's warmth.
 * Each effect's picture is made here from PEAK's own (its shader's texture, colour and edge settings, exported to
 * ui/screenfx); before an export, plain edges in PEAK's colours.
 */
public final class PeakScreenFx {
	private PeakScreenFx() { }

	static final Path DIR = PeakArt.DIR.resolve("screenfx");
	private static final int W = 320, H = 180;

	private record Fx(Identifier id, float delay, float duration) { }

	private static final Map<String, Optional<Fx>> BUILT = new HashMap<>();
	private static JsonObject index;
	private static long stamp = -1;

	/** PEAK's colour for an effect, for the plain edges before an export. */
	private static int plain(String name) {
		return switch (name) {
			case "injurySVFX" -> 0x7D0A00; case "poisonSVFX" -> 0xF200FF; case "hotSVFX" -> 0xFF7900; case "drowsyFX" -> 0xFF00FF;
			case "heatSVFX" -> 0xFF1E00; case "sporesSVFX" -> 0x80304B; case "thornsSVFX" -> 0xADE400; case "cold" -> 0x00A4FF;
			case "energySVFX" -> 0x70FF87; case "sunscreenSVFX" -> 0xFFFFFF; default -> 0x000000;
		};
	}

	private static long checked;

	private static synchronized JsonObject index() {
		long t = System.currentTimeMillis();
		if (t - checked < 2000) return index;   // (the file looked at every 2 s, not every frame)
		checked = t;
		Path f = DIR.resolve("screenfx.json");
		long s;
		try { s = Files.isRegularFile(f) ? Files.getLastModifiedTime(f).toMillis() : 0; } catch (Exception e) { s = 0; }
		if (s != stamp) {
			stamp = s;
			BUILT.clear();
			index = null;
			if (s != 0) try { index = JsonParser.parseString(Files.readString(f)).getAsJsonObject().getAsJsonObject("fx"); } catch (Exception e) { MinePeak.LOG.warn("MinePeak: couldn't read PEAK's screen effects", e); }
		}
		return index;
	}

	/** An effect's picture (made once from PEAK's), or null before an export. */
	private static Fx fx(String name) {
		JsonObject all = index();
		if (all == null) return null;
		Optional<Fx> had = BUILT.get(name);
		if (had != null) return had.orElse(null);
		if (Minecraft.getInstance().gui.overlay() != null) return null;
		Optional<Fx> made = Optional.empty();
		try { made = Optional.ofNullable(build(name, all)); } catch (Exception e) { MinePeak.LOG.warn("MinePeak: couldn't make PEAK's screen effect {}", name, e); }
		BUILT.put(name, made);
		return made.orElse(null);
	}

	private static float num(JsonObject o, String k, float d) { return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsFloat() : d; }

	private static String str(JsonObject o, String k) { return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null; }

	private static int colour(String hex, int d) {
		if (hex == null || !hex.startsWith("#") || hex.length() < 7) return d;
		int rgb = Integer.parseInt(hex.substring(1, 7), 16);
		int a = hex.length() >= 9 ? Integer.parseInt(hex.substring(7, 9), 16) : 255;
		return a << 24 | rgb;
	}

	/**
	 * PEAK's NickPost edge shaders, drawn once into a picture: the screen's edges in the effect's colour, broken up by
	 * its texture (the further from the middle, the more of it), shaded by the texture.
	 */
	private static Fx build(String name, JsonObject all) throws Exception {
		JsonObject m, o;
		int col;
		String tex;
		float edge, add, ts, delay, duration;
		boolean wide = false;
		if (name.equals("cold")) {
			JsonObject v = all.getAsJsonObject("coldVolume");
			JsonObject danger = v != null && v.has("effects") ? v.getAsJsonObject("effects").getAsJsonObject("Danger") : null;
			JsonObject bloom = v != null && v.has("effects") ? v.getAsJsonObject("effects").getAsJsonObject("Bloom") : null;
			col = colour(str(danger, "color"), 0xFF00A4FF);
			tex = str(bloom, "dirtTexture");
			edge = 1.0f; add = 0.1f; ts = 1.2f; delay = 0.25f; duration = 0.45f;
			wide = true;
		} else {
			o = all.has(name) ? all.getAsJsonObject(name) : null;
			if (o == null) return null;
			m = o.has("material") ? o.getAsJsonObject("material") : new JsonObject();
			delay = num(o, "delay", 0.1f); duration = num(o, "duration", 0.4f);
			if (name.equals("energySVFX")) { col = colour(str(m, "_LineColor"), 0xFF70FF87); tex = str(m, "_SpeedLines"); }
			else if (name.equals("curseSVFX")) { col = 0xFF14001C; tex = str(all.getAsJsonObject("injurySVFX").getAsJsonObject("material"), "_Texture"); }
			else { col = colour(str(m, "_Color"), 0xFF000000 | plain(name)); tex = str(m, "_Texture"); }
			edge = num(m, "_Edge", 1f); add = num(m, "_EdgeAdd", 0f);
			ts = Math.min(1.5f, num(m, "_TextureStrength", num(m, "_TexStr", 1f)));
		}
		NativeImage noise = null;
		if (tex != null && Files.isRegularFile(DIR.resolve(tex))) try (InputStream in = Files.newInputStream(DIR.resolve(tex))) { noise = NativeImage.read(in); }
		// (each texture's own range, stretched to 0..1: the cold's lens dirt is mostly dark)
		int lo = 255, hi = 0;
		if (noise != null)
			for (int y = 0; y < noise.getHeight(); y += 7)
				for (int x = 0; x < noise.getWidth(); x += 7) { int v = noise.getPixel(x, y) >> 8 & 255; lo = Math.min(lo, v); hi = Math.max(hi, v); }
		float range = Math.max(1, hi - lo);
		NativeImage img = new NativeImage(W, H, false);
		float ca = (col >>> 24) / 255f;
		int cr = col >> 16 & 255, cg = col >> 8 & 255, cb = col & 255;
		for (int y = 0; y < H; y++)
			for (int x = 0; x < W; x++) {
				float n = 0.5f;
				if (noise != null) {
					int tx = wide ? x * noise.getWidth() / W : (x % H) * noise.getWidth() / H, ty = y * noise.getHeight() / H;
					n = ((noise.getPixel(tx, ty) >> 8 & 255) - lo) / range;
				}
				float u = (x + 0.5f) / W * 2 - 1, v = (y + 0.5f) / H * 2 - 1;
				float e = (float) Math.sqrt(0.75f * (u * u + v * v));
				float a = (e - 0.62f + add * 0.4f) * (1f + edge) + (n - 0.5f) * ts * 1.2f;
				a = Math.max(0, Math.min(1, a));
				a = a * a * (3 - 2 * a) * ca * 0.85f;
				float sh = 0.65f + 0.35f * n;
				int r = Math.min(255, Math.round(cr * sh)), g = Math.min(255, Math.round(cg * sh)), b = Math.min(255, Math.round(cb * sh));
				img.setPixel(x, y, Math.round(a * 255) << 24 | r << 16 | g << 8 | b);
			}
		if (noise != null) noise.close();
		Identifier id = MinePeak.id("screenfx/" + name.toLowerCase(java.util.Locale.ROOT) + "_" + (stamp & 0xFFFF));
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "MinePeak " + name, img));
		return new Fx(id, delay, duration);
	}

	// ---- what's showing ----

	/** A flash: when it began, and (for the cold's) its own rise, hold and fall. */
	private record Flash(String name, long start) { }

	private static final List<Flash> FLASHES = new ArrayList<>();
	private static final Map<String, float[]> HELD = new HashMap<>();   // name -> {strength now, wanted}

	/** PEAK's ScreenVFX.Play: the effect at full at once, for its delay, then gone over its duration. */
	static void flash(String name) {
		FLASHES.removeIf(f -> f.name.equals(name));
		FLASHES.add(new Flash(name, System.currentTimeMillis()));
	}

	/** PEAK's StartFX/EndFX: an effect that lasts while something does (fading in and out over its duration). */
	static void hold(String name, boolean on) {
		float[] h = HELD.computeIfAbsent(name, k -> new float[2]);
		h[1] = on ? 1 : 0;
	}

	static void clear() { FLASHES.clear(); HELD.clear(); SHAKES.clear(); }

	private static float strength(Flash f, long now) {
		float t = (now - f.start) / 1000f;
		if (f.name.equals("cold")) {   // (PEAK's PlayFXSequence on the cold Volume: up in 0.06 s, 0.25 s, down over 0.45 s)
			if (t < 0.06f) return t / 0.06f;
			if (t < 0.31f) return 1;
			return Math.max(0, 1 - (t - 0.31f) / 0.45f);
		}
		Fx fx = fx(f.name);
		float delay = fx != null ? fx.delay : 0.1f, duration = fx != null ? fx.duration : 0.4f;
		if (t < delay) return 1;
		return Math.max(0, 1 - (t - delay) / Math.max(0.05f, duration));
	}

	static void draw(GuiGraphicsExtractor g) {
		long now = System.currentTimeMillis();
		for (var e : HELD.entrySet()) {
			float[] h = e.getValue();
			Fx fx = fx(e.getKey());
			float step = 1f / (Math.max(0.1f, fx != null ? fx.duration : 0.5f) * 60f);
			h[0] = h[1] > h[0] ? Math.min(h[1], h[0] + step) : Math.max(h[1], h[0] - step);
			if (h[0] > 0.005f) show(g, e.getKey(), h[0] * (e.getKey().equals("energySVFX") ? 0.6f : 0.8f));
		}
		for (Iterator<Flash> it = FLASHES.iterator(); it.hasNext(); ) {
			Flash f = it.next();
			float s = strength(f, now);
			if (s <= 0 && now - f.start > 300) { it.remove(); continue; }
			show(g, f.name, s);
		}
	}

	private static void show(GuiGraphicsExtractor g, String name, float s) {
		if (s <= 0.004f) return;
		int w = g.guiWidth(), h = g.guiHeight();
		Fx fx = fx(name);
		if (fx != null) {
			int a = Math.round(Math.min(1, s) * 255);
			g.blit(RenderPipelines.GUI_TEXTURED, fx.id, 0, 0, 0, 0, w, h, W, H, W, H, a << 24 | 0xFFFFFF);
		} else edges(g, plain(name), s * 0.7f);
	}

	/** The screen's edges tinted (strength 0..1), fading in from them: before an export has PEAK's pictures. */
	static void edges(GuiGraphicsExtractor g, int rgb, float strength) {
		if (strength < 0.01f) return;
		int w = g.guiWidth(), h = g.guiHeight(), bands = 12, band = Math.max(1, Math.min(w, h) / 40);
		for (int i = 0; i < bands; i++) {
			int a = Math.round(strength * 255 * (1 - i / (float) bands) * 0.35f);
			if (a <= 0) continue;
			int c = (a << 24) | (rgb & 0xFFFFFF), o = i * band;
			g.fill(o, o, w - o, o + band, c);
			g.fill(o, h - o - band, w - o, h - o, c);
			g.fill(o, o + band, o + band, h - o - band, c);
			g.fill(w - o - band, o + band, w - o, h - o - band, c);
		}
	}

	// ---- PEAK's camera shake (PerlinShake: each shake's amount in degrees, fading over its time) ----

	private record Shake(float amount, float duration, float scale, long start, float seed) { }

	private static final List<Shake> SHAKES = new ArrayList<>();
	private static final long EPOCH = System.currentTimeMillis();

	/** PEAK's GamefeelHandler.AddPerlinShake(amount, duration, scale). */
	static void shake(float amount, float duration, float scale) {
		if (SHAKES.size() > 16) SHAKES.remove(0);
		SHAKES.add(new Shake(amount, duration, scale, System.currentTimeMillis(), (float) (Math.random() * 100)));
	}

	public static float shakePitch() { return shakeAxis(0); }

	public static float shakeYaw() { return shakeAxis(57.3f); }

	private static float shakeAxis(float offset) {
		if (SHAKES.isEmpty()) return 0;
		long now = System.currentTimeMillis();
		float t = ((now - EPOCH) % 1_000_000L) / 1000f, sum = 0;   // (seconds since start: small enough for a float)
		for (Iterator<Shake> it = SHAKES.iterator(); it.hasNext(); ) {
			Shake s = it.next();
			float left = s.duration - (now - s.start) / 1000f;
			if (left <= 0) { if (offset == 0) it.remove(); continue; }
			sum += (perlin((t * s.scale + s.seed + offset) % 10000f) - 0.5f) * s.amount * (left / s.duration);
		}
		return Float.isFinite(sum) ? Math.max(-30f, Math.min(30f, sum)) : 0;
	}

	/** Smooth noise in 0..1 (as Unity's PerlinNoise along one line). */
	private static float perlin(float x) {
		int i = (int) Math.floor(x);
		float f = x - i, a = grad(i) * f, b = grad(i + 1) * (f - 1);
		float s = f * f * f * (f * (f * 6 - 15) + 10);
		return 0.5f + (a + (b - a) * s);
	}

	private static float grad(int i) {
		int h = i * 0x27d4eb2d;
		h ^= h >>> 15;
		h *= 0x85ebca6b;
		h ^= h >>> 13;
		return ((h & 0xFFFF) / 65535f) * 2 - 1;
	}

	// ---- from the scout's statuses (PeakStatusClient) ----

	private static float[] base;
	private static boolean sunscreen;

	/**
	 * PEAK's AddStatusFX for each status that's gone up by one of PEAK's steps (0.025) since the last: its effect, its
	 * shake. Hunger and weight show nothing (as in PEAK).
	 */
	static void statuses(float[] st, int flags) {
		if (base == null || base.length != st.length) { base = st.clone(); return; }
		boolean awake = (flags & (PeakStatus.F_OUT | PeakStatus.F_DEAD)) == 0;
		for (int i = 0; i < st.length; i++) {
			float up = st[i] - base[i];
			if (up < 0) { base[i] = st[i]; continue; }
			if (up < 0.025f) continue;
			base[i] = st[i];
			if (!awake) continue;
			switch (i) {
				case PeakStatus.INJURY, PeakStatus.CRAB, PeakStatus.ARROW -> { shake((up + 1) * 5, 0.3f, 15); flash("injurySVFX"); }
				case PeakStatus.CURSE -> { shake((up + 1) * 30, 0.3f, 15); flash("curseSVFX"); }
				case PeakStatus.DROWSY -> { shake(5, 0.3f, 15); flash("drowsyFX"); }
				case PeakStatus.POISON -> { shake(2.5f, 0.3f, 15); flash("poisonSVFX"); }
				case PeakStatus.THORNS -> { shake(2.5f, 0.3f, 15); flash("thornsSVFX"); }
				case PeakStatus.SPORES -> { shake(5, 0.3f, 15); flash("sporesSVFX"); }
				case PeakStatus.COLD -> { shake(2, 1, 30); flash("cold"); }
				case PeakStatus.HOT -> { shake(2, 1, 30); flash("hotSVFX"); }
				default -> { }
			}
		}
		hold("energySVFX", awake && (flags & PeakStatus.F_FASTER) != 0);
		hold("heatSVFX", awake && (flags & PeakStatus.F_WARMING) != 0);
		boolean sun = (flags & PeakStatus.F_SUNSCREEN) != 0;
		if (sun && !sunscreen && awake) flash("sunscreenSVFX");
		sunscreen = sun;
	}

	static void reset() { base = null; sunscreen = false; clear(); }

	/** (The dev test: what's showing, whether PEAK's pictures were made, and the shake now.) */
	static String debug() {
		StringBuilder b = new StringBuilder();
		for (Flash f : FLASHES) b.append(f.name).append(fx(f.name) != null ? " (PEAK's picture)" : " (plain)").append(' ');
		for (var e : HELD.entrySet()) if (e.getValue()[1] > 0) b.append(e.getKey()).append(" held ");
		return String.format(java.util.Locale.ROOT, "%sshake %.2f/%.2f deg", b.length() == 0 ? "nothing " : b, shakePitch(), shakeYaw());
	}
}
