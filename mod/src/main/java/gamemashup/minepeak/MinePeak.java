package gamemashup.minepeak;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MinePeak: PEAK inside Minecraft. The world type "MinePeak Island" (data/minepeak/worldgen/world_preset/island.json)
 * builds PEAK's island of the day from the export the MinePeak Exporter made in PEAK on this PC, about 1,400 blocks
 * tall, with PEAK's airport in a dimension of its own.
 */
public class MinePeak implements ModInitializer {
	public static final String ID = "minepeak";
	public static final Logger LOG = LoggerFactory.getLogger("MinePeak");

	public static Identifier id(String path) { return Identifier.fromNamespaceAndPath(ID, path); }

	@Override
	public void onInitialize() {
		MinePeakBlocks.init();
		PeakGearBlocks.init();
		PeakItems.init();
		BackpackMenu.init();
		Registry.register(BuiltInRegistries.CHUNK_GENERATOR, id("island"), IslandChunkGenerator.CODEC);
		Registry.register(BuiltInRegistries.BIOME_SOURCE, id("stages"), StageBiomeSource.CODEC);
		MinePeakServer.init();
		PeakClimb.initCommon();
		PeakFog.initCommon();
		PeakStatus.initCommon();
		PeakInventory.initCommon();
		PeakEnding.initCommon();
		PeakHazards.initCommon();
		PeakSfx.initCommon();
		PeakLoops.initCommon();
		PeakIslandInfo.initCommon();
		HeroTitle.init();
		LOG.info("MinePeak loaded (islands from {})", IslandData.ROOT);
	}
}
