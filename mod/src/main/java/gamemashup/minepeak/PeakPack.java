package gamemashup.minepeak;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import javax.imageio.ImageIO;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;

/**
 * PEAK's look for MinePeak's items, made on this PC: a resource pack in %APPDATA%/MinePeak/pack with each of PEAK's item
 * icons (from the player's own PEAK, as exported: items/*.png) as the texture of MinePeak's item, so it looks as PEAK
 * draws it in the hand, on the ground and in the slots. Always on (Minecraft's resource packs list it as "PEAK"); made
 * again when an export brings new icons. Until there's an export, MinePeak's items look like the Minecraft item nearest
 * to PEAK's. Nothing of PEAK's ships with MinePeak.
 */
public final class PeakPack {
	private PeakPack() { }

	static final Path DIR = IslandData.ROOT.getParent().resolve("pack");
	/** The pack's layout: a pack an older MinePeak made (built_from.txt without this) is made again. */
	private static final int VERSION = 2;
	private static long builtFrom = -1;

	public static final RepositorySource SOURCE = consumer -> {
		try {
			if (!build()) return;
			Pack pack = Pack.readMetaAndCreate(new PackLocationInfo("minepeak_peak", Component.literal("PEAK"), PackSource.BUILT_IN, Optional.empty()),
				new PathPackResources.PathResourcesSupplier(DIR), PackType.CLIENT_RESOURCES, new PackSelectionConfig(true, Pack.Position.TOP, false));
			if (pack != null) consumer.accept(pack);
		} catch (Exception | LinkageError e) {
			// (no pack: MinePeak's items look like the Minecraft items nearest to PEAK's, from the mod's own models)
			MinePeak.LOG.warn("MinePeak: couldn't make PEAK's resource pack", e);
		}
	};

	private static final String[][] KINDS = { { "models/item/peak", "textures/item/peak" }, { "models/item/badge", "textures/item/badge" } };

	/**
	 * Takes out the pack's item models whose picture isn't there (left by an older MinePeak, or a picture that couldn't
	 * be made), so those items fall back to the mod's own models (a Minecraft item's look) instead of the missing
	 * texture. How many it took out.
	 */
	static int prune() throws java.io.IOException {
		int gone = 0;
		for (String[] k : KINDS) {
			Path models = DIR.resolve("assets/minepeak").resolve(k[0]), tex = DIR.resolve("assets/minepeak").resolve(k[1]);
			if (!Files.isDirectory(models)) continue;
			try (var s = Files.list(models)) {
				for (Path m : (Iterable<Path>) s::iterator) {
					String name = m.getFileName().toString();
					if (!name.endsWith(".json")) continue;
					Path png = tex.resolve(name.substring(0, name.length() - 5) + ".png");
					if (Files.isRegularFile(png) && Files.size(png) > 0) continue;
					Files.deleteIfExists(m);
					gone++;
				}
			}
		}
		return gone;
	}

	/** How many item pictures the pack has. */
	static int pictures() {
		int n = 0;
		for (String[] k : KINDS) {
			Path tex = DIR.resolve("assets/minepeak").resolve(k[1]);
			if (!Files.isDirectory(tex)) continue;
			try (var s = Files.list(tex)) { n += (int) s.filter(p -> p.toString().endsWith(".png")).count(); } catch (Exception e) { }
		}
		return n;
	}

	static final Path BADGES = IslandData.ROOT.getParent().resolve("ui").resolve("badges");

	private static long itemsStamp() {
		try {
			Path f = PeakItemData.DIR.resolve("items.json"), b = BADGES.resolve("badges.json");
			long s = Files.isRegularFile(f) ? Files.getLastModifiedTime(f).toMillis() : 0;
			if (s != 0 && Files.isRegularFile(b)) s = Math.max(s, Files.getLastModifiedTime(b).toMillis());
			return s;
		} catch (Exception e) {
			return 0;
		}
	}

	/** Has an export brought new icons since the pack was made (time to reload Minecraft's resources)? */
	static boolean stale() { long s = itemsStamp(); return s != 0 && s != builtFrom; }

	/**
	 * Makes the pack (if PEAK's items are exported). True if there's one. Made again when an export brings new icons,
	 * when an older MinePeak made it, or when any of its models has lost its picture.
	 */
	static synchronized boolean build() throws Exception {
		long stamp = itemsStamp();
		if (stamp == 0) return false;
		if (stamp == builtFrom) return true;
		Path marker = DIR.resolve("built_from.txt");
		boolean made = Files.isRegularFile(DIR.resolve("pack.mcmeta"));
		if (made && Files.isRegularFile(marker) && Files.readString(marker).trim().equals(VERSION + " " + stamp)) {
			int gone = prune();
			if (gone == 0) {
				builtFrom = stamp;
				MinePeak.LOG.info("MinePeak: PEAK's resource pack ({} item pictures)", pictures());
				return true;
			}
			MinePeak.LOG.warn("MinePeak: PEAK's resource pack had {} item models without their pictures: making it again", gone);
		}
		if (PeakExporter.running()) {
			// (PEAK is writing its icons now: the pack is made once it's done, when the menus reload; until then, what's
			// there, without models whose picture is missing)
			if (!made) return false;
			prune();
			return true;
		}
		Path tex = DIR.resolve("assets/minepeak/textures/item/peak"), models = DIR.resolve("assets/minepeak/models/item/peak");
		Files.createDirectories(tex);
		Files.createDirectories(models);
		int n = 0, failed = 0;
		for (String[] e : PeakItemList.ITEMS) {
			var def = PeakItemData.get(e[0]);
			if (def == null || def.icon() == null) continue;
			Path icon = PeakItemData.DIR.resolve(def.icon());
			if (!Files.isRegularFile(icon)) continue;
			try {
				BufferedImage src = ImageIO.read(icon.toFile());
				if (src == null) { failed++; continue; }
				BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
				var g = out.createGraphics();
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
				g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
				g.drawImage(src, 0, 0, 64, 64, null);
				g.dispose();
				// (alpha either there or not: an item's outline in Minecraft is cut out)
				for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) { int c = out.getRGB(x, y); out.setRGB(x, y, (c >>> 24) >= 100 ? c | 0xFF000000 : 0); }
				// the model only once its picture is there: a model without one would draw the missing texture
				Path png = tex.resolve(e[1] + ".png");
				if (!ImageIO.write(out, "png", png.toFile()) || !Files.isRegularFile(png) || Files.size(png) == 0) { failed++; continue; }
				Files.writeString(models.resolve(e[1] + ".json"), "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"minepeak:item/peak/" + e[1] + "\"}}");
				n++;
			} catch (Exception ex) {
				if (failed++ == 0) MinePeak.LOG.warn("MinePeak: couldn't make the picture of {} from {}", e[1], icon, ex);
			}
		}
		// PEAK's badges: their pictures (the advancements' icons) and their names and words in the player's language
		int badges = 0;
		Path bj = BADGES.resolve("badges.json");
		if (Files.isRegularFile(bj)) {
			Path btex = DIR.resolve("assets/minepeak/textures/item/badge"), bmod = DIR.resolve("assets/minepeak/models/item/badge");
			Files.createDirectories(btex);
			Files.createDirectories(bmod);
			var lang = new com.google.gson.JsonObject();
			var all = com.google.gson.JsonParser.parseString(Files.readString(bj)).getAsJsonObject().getAsJsonObject("badges");
			for (var e : all.entrySet()) {
				String key = e.getKey().replaceAll("Badge$", "").toLowerCase(java.util.Locale.ROOT);
				var b = e.getValue().getAsJsonObject();
				if (b.has("name")) lang.addProperty("badge.minepeak." + key, b.get("name").getAsString());
				if (b.has("description")) lang.addProperty("badge.minepeak." + key + ".desc", b.get("description").getAsString());
				if (!b.has("file") || !Files.isRegularFile(BADGES.resolve(b.get("file").getAsString()))) continue;
				try {
					BufferedImage src = ImageIO.read(BADGES.resolve(b.get("file").getAsString()).toFile());
					if (src == null) { failed++; continue; }
					BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
					var g = out.createGraphics();
					g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
					g.drawImage(src, 0, 0, 64, 64, null);
					g.dispose();
					for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) { int c = out.getRGB(x, y); out.setRGB(x, y, (c >>> 24) >= 100 ? c | 0xFF000000 : 0); }
					Path png = btex.resolve(key + ".png");
					if (!ImageIO.write(out, "png", png.toFile()) || !Files.isRegularFile(png) || Files.size(png) == 0) { failed++; continue; }
					Files.writeString(bmod.resolve(key + ".json"), "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"minepeak:item/badge/" + key + "\"}}");
					badges++;
				} catch (Exception ex) {
					if (failed++ == 0) MinePeak.LOG.warn("MinePeak: couldn't make the picture of the badge {}", key, ex);
				}
			}
			Path langDir = DIR.resolve("assets/minepeak/lang");
			Files.createDirectories(langDir);
			Files.writeString(langDir.resolve("en_us.json"), lang.toString());
		}
		int gone = prune();
		int fmt = SharedConstants.RESOURCE_PACK_FORMAT_MAJOR;
		Files.writeString(DIR.resolve("pack.mcmeta"), "{\"pack\":{\"description\":\"PEAK's items, from your PEAK (MinePeak)\",\"min_format\":" + fmt + ",\"max_format\":" + fmt + "}}");
		Files.writeString(marker, VERSION + " " + stamp);
		builtFrom = stamp;
		MinePeak.LOG.info("MinePeak: PEAK's resource pack made ({} item pictures, {} badges) in {}", n, badges, DIR);
		if (failed > 0 || gone > 0)
			MinePeak.LOG.warn("MinePeak: {} of PEAK's pictures couldn't be made ({} old models without pictures taken out): those items look like Minecraft's", failed, gone);
		return true;
	}
}
