package gamemashup.minepeak;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.CubeMapTexture;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * The menus' panorama is PEAK's airport, seen from the middle of its hall: six pictures PEAK takes of it while it
 * exports the island (%APPDATA%/MinePeak/panorama/panorama_0..5.png: ahead, right, behind, left, up, down, as
 * Minecraft's own). Until there are any, Minecraft's own panorama.
 */
public final class AirportPanorama extends CubeMapTexture {
	/** Minecraft's panorama (its GUI renderer draws whatever is registered under this). */
	static final Identifier ID = Identifier.withDefaultNamespace("textures/gui/title/background/panorama");
	static final Path DIR = IslandData.ROOT.getParent().resolve("panorama");
	// the order Minecraft stacks the sides in (CubeMapTexture's)
	private static final int[] ORDER = { 1, 3, 5, 4, 0, 2 };
	private static long installed = -1;

	private AirportPanorama() { super(ID); }

	static Path side(int i) { return DIR.resolve("panorama_" + i + ".png"); }

	/** When the pictures were last changed (0 if any is missing). */
	static long stamp() {
		long t = 0;
		try {
			for (int i = 0; i < 6; i++) {
				if (!Files.isRegularFile(side(i))) return 0;
				t = Math.max(t, Files.getLastModifiedTime(side(i)).toMillis());
			}
		} catch (IOException e) {
			return 0;
		}
		return t;
	}

	@Override
	public TextureContents loadContents(ResourceManager resources) throws IOException {
		if (stamp() == 0) return super.loadContents(resources);
		NativeImage stacked = null;
		int w = 0, h = 0;
		try {
			for (int k = 0; k < 6; k++) {
				try (InputStream in = Files.newInputStream(side(ORDER[k])); NativeImage img = NativeImage.read(in)) {
					if (stacked == null) {
						w = img.getWidth(); h = img.getHeight();
						stacked = new NativeImage(w, h * 6, false);
					} else if (img.getWidth() != w || img.getHeight() != h) {
						throw new IOException("the airport's panorama pictures aren't all the same size");
					}
					img.copyRect(stacked, 0, 0, 0, k * h, w, h, false, true);
				}
			}
		} catch (IOException | RuntimeException e) {
			if (stacked != null) stacked.close();
			MinePeak.LOG.warn("Couldn't read the airport's panorama in {}: Minecraft's own", DIR, e);
			return super.loadContents(resources);
		}
		return new TextureContents(stacked, new TextureMetadataSection(true, false, MipmapStrategy.MEAN, 0.0F));
	}

	/** In place of Minecraft's, once there are pictures (again when they change: PEAK takes them as it exports). Render thread. */
	static void update(Minecraft mc) {
		long t = stamp();
		if (t == installed || (t == 0 && installed == -1)) return;
		installed = t;
		mc.getTextureManager().registerAndLoad(ID, new AirportPanorama());
		if (t != 0) MinePeak.LOG.info("MinePeak: the menus' panorama is PEAK's airport ({})", DIR);
	}
}
