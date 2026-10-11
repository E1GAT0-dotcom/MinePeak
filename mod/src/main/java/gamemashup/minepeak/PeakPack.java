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
	private static long builtFrom = -1;

	public static final RepositorySource SOURCE = consumer -> {
		try {
			if (!build()) return;
			Pack pack = Pack.readMetaAndCreate(new PackLocationInfo("minepeak_peak", Component.literal("PEAK"), PackSource.BUILT_IN, Optional.empty()),
				new PathPackResources.PathResourcesSupplier(DIR), PackType.CLIENT_RESOURCES, new PackSelectionConfig(true, Pack.Position.TOP, false));
			if (pack != null) consumer.accept(pack);
		} catch (Exception e) {
			MinePeak.LOG.warn("MinePeak: couldn't make PEAK's resource pack", e);
		}
	};

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

	/** Makes the pack (if PEAK's items are exported). True if there's one. */
	static synchronized boolean build() throws Exception {
		long stamp = itemsStamp();
		if (stamp == 0) return false;
		Path marker = DIR.resolve("built_from.txt");
		if (stamp == builtFrom || Files.isRegularFile(marker) && Files.readString(marker).trim().equals(Long.toString(stamp)) && Files.isRegularFile(DIR.resolve("pack.mcmeta"))) {
			builtFrom = stamp;
			return true;
		}
		Path tex = DIR.resolve("assets/minepeak/textures/item/peak"), models = DIR.resolve("assets/minepeak/models/item/peak");
		Files.createDirectories(tex);
		Files.createDirectories(models);
		int n = 0;
		for (String[] e : PeakItemList.ITEMS) {
			var def = PeakItemData.get(e[0]);
			if (def == null || def.icon() == null) continue;
			Path icon = PeakItemData.DIR.resolve(def.icon());
			if (!Files.isRegularFile(icon)) continue;
			BufferedImage src = ImageIO.read(icon.toFile());
			if (src == null) continue;
			BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
			var g = out.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(src, 0, 0, 64, 64, null);
			g.dispose();
			// (alpha either there or not: an item's outline in Minecraft is cut out)
			for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) { int c = out.getRGB(x, y); out.setRGB(x, y, (c >>> 24) >= 100 ? c | 0xFF000000 : 0); }
			ImageIO.write(out, "png", tex.resolve(e[1] + ".png").toFile());
			Files.writeString(models.resolve(e[1] + ".json"), "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"minepeak:item/peak/" + e[1] + "\"}}");
			n++;
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
				BufferedImage src = ImageIO.read(BADGES.resolve(b.get("file").getAsString()).toFile());
				if (src == null) continue;
				BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
				var g = out.createGraphics();
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
				g.drawImage(src, 0, 0, 64, 64, null);
				g.dispose();
				for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) { int c = out.getRGB(x, y); out.setRGB(x, y, (c >>> 24) >= 100 ? c | 0xFF000000 : 0); }
				ImageIO.write(out, "png", btex.resolve(key + ".png").toFile());
				Files.writeString(bmod.resolve(key + ".json"), "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"minepeak:item/badge/" + key + "\"}}");
				badges++;
			}
			Path langDir = DIR.resolve("assets/minepeak/lang");
			Files.createDirectories(langDir);
			Files.writeString(langDir.resolve("en_us.json"), lang.toString());
		}
		int fmt = SharedConstants.RESOURCE_PACK_FORMAT_MAJOR;
		Files.writeString(DIR.resolve("pack.mcmeta"), "{\"pack\":{\"description\":\"PEAK's items, from your PEAK (MinePeak)\",\"min_format\":" + fmt + ",\"max_format\":" + fmt + "}}");
		Files.writeString(marker, Long.toString(stamp));
		builtFrom = stamp;
		MinePeak.LOG.info("MinePeak: PEAK's resource pack made ({} item icons, {} badges) in {}", n, badges, DIR);
		return true;
	}
}
