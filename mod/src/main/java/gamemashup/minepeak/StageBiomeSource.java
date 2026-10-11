package gamemashup.minepeak;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/**
 * A biome for each of PEAK's stages, so each has PEAK's air: its haze's colour and how thick it is, the light's tint,
 * the sky, and what drifts in it (snow on the Alpine, ash on the volcano). A stage starts where Stages says (at the
 * campfire before PEAK's progress point for it) and goes on to the next one up the mountain; the sea round the island
 * is the shore's. Minecraft blends the air from one to the next as you climb.
 * Stages by name: shore, tropics, roots, alpine, mesa, caldera, kiln, peak (and "sea" for one MinePeak doesn't know).
 */
public final class StageBiomeSource extends BiomeSource {
	public static final MapCodec<StageBiomeSource> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		Biome.CODEC.fieldOf("sea").forGetter(s -> s.sea),
		Codec.unboundedMap(Codec.STRING, Biome.CODEC).fieldOf("stages").forGetter(s -> s.stages)
	).apply(i, StageBiomeSource::new));

	private final Holder<Biome> sea;
	private final Map<String, Holder<Biome>> stages;

	/** Where each stage starts (Minecraft z, going down the list as the mountain goes up), for the island it's for. */
	private record Start(int z, Holder<Biome> biome) { }
	private volatile IslandData forIsland;
	private volatile List<Start> starts = List.of();

	public StageBiomeSource(Holder<Biome> sea, Map<String, Holder<Biome>> stages) {
		this.sea = sea;
		this.stages = stages;
	}

	@Override protected MapCodec<? extends BiomeSource> codec() { return CODEC; }

	@Override
	protected Stream<Holder<Biome>> collectPossibleBiomes() {
		return Stream.concat(Stream.of(sea), stages.values().stream()).distinct();
	}

	@Override
	public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
		List<Start> list = starts();
		int z = quartZ << 2;
		Holder<Biome> here = list.isEmpty() ? sea : stages.getOrDefault("shore", sea);
		for (Start s : list) if (z <= s.z()) here = s.biome(); else break;
		return here;
	}

	private List<Start> starts() {
		IslandData data = IslandData.get(IslandChunkGenerator.pinned != null ? IslandChunkGenerator.pinned : "latest", "island");
		if (data == null) return List.of();
		if (data != forIsland) {
			List<Start> list = new ArrayList<>();
			for (Stages.Stage st : Stages.of(data)) list.add(new Start(st.z(), stages.getOrDefault(stage(st.title(), st.biome()), sea)));
			starts = list;
			forIsland = data;
		}
		return starts;
	}

	/** Which stage a progress point starts: by PEAK's title for it, or its biome. */
	static String stage(String title, String biome) {
		String t = (title + " " + biome).toUpperCase(Locale.ROOT);
		if (t.contains("KILN")) return "kiln";
		if (t.contains("CALDERA")) return "caldera";
		if (t.startsWith("PEAK") || biome.equalsIgnoreCase("Peak")) return "peak";
		if (t.contains("SHORE") || t.contains("BEACH")) return "shore";
		if (t.contains("ROOT")) return "roots";
		if (t.contains("TROPIC") || t.contains("JUNGLE")) return "tropics";
		if (t.contains("ALPINE") || t.contains("SNOW")) return "alpine";
		if (t.contains("MESA") || t.contains("DESERT")) return "mesa";
		if (t.contains("VOLCANO")) return "caldera";
		return "sea";
	}
}
