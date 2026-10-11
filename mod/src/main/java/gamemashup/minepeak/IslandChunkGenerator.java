package gamemashup.minepeak;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.BarrierBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * Builds the world from a PEAK island export: every block of PEAK's island of the day and its plants, and round the
 * island the sea (PEAK's sea level is y 63) going on for ever over a sandy floor, with coral reefs, seagrass and kelp.
 * Nothing of Minecraft's own: no caves, no ores, no structures.
 * Settings (chosen in Create World, kept in the world): which island ("latest": today's, tied to the world when it first
 * starts), where everyone starts ("bottom": the plane wreck on the beach, or "campfire1".."campfire4"), and the edges
 * ("barrier": an invisible wall on the island's edge; "open": none).
 * ("area" is "island", or "airport" in the airport dimension of worlds made before the airport was dropped.)
 */
public final class IslandChunkGenerator extends ChunkGenerator {
	public static final MapCodec<IslandChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
		Codec.STRING.optionalFieldOf("island", "latest").forGetter(g -> g.island),
		Codec.STRING.optionalFieldOf("area", "island").forGetter(g -> g.area),
		Codec.STRING.optionalFieldOf("start", "bottom").forGetter(g -> g.start),
		Codec.STRING.optionalFieldOf("edges", "barrier").forGetter(g -> g.edges),
		Codec.INT.optionalFieldOf("ascent", 0).forGetter(g -> g.ascent)
	).apply(i, IslandChunkGenerator::new));

	public static final int SEA_LEVEL = 63, SEA_FLOOR = 40;

	final String island, area, start, edges;
	/** PEAK's Ascent (its difficulty: -1 to 8; PeakAscents). */
	final int ascent;

	public IslandChunkGenerator(BiomeSource biomes, String island, String area, String start, String edges, int ascent) {
		super(biomes);
		this.ascent = ascent;
		this.island = island;
		this.area = area;
		this.start = start;
		this.edges = edges;
	}

	public String island() { return island; }
	public String start() { return start; }
	public String edges() { return edges; }
	public int ascent() { return ascent; }

	/** The same, with other settings (Create World's MinePeak settings). */
	public IslandChunkGenerator with(String island, String start, String edges) {
		return new IslandChunkGenerator(biomeSource, island, area, start, edges, ascent);
	}

	public IslandChunkGenerator withAscent(int ascent) {
		return new IslandChunkGenerator(biomeSource, island, area, start, edges, ascent);
	}

	/** The island this world is tied to (set before it loads by MinePeakServer), used in place of "latest". */
	public static volatile String pinned;

	IslandData data() { return IslandData.get("latest".equals(island) && pinned != null ? pinned : island, area); }

	boolean sea() { return "island".equals(area); }

	/** Does the island carry on past its edges, behind barriers? */
	boolean walls() { return sea() && !"open".equals(edges); }

	@Override protected MapCodec<? extends ChunkGenerator> codec() { return CODEC; }

	@Override public void applyCarvers(WorldGenRegion region, long seed, RandomState random, BiomeManager biomes, StructureManager structures, ChunkAccess chunk) { }

	@Override public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState random, ChunkAccess chunk) { }

	// PEAK's creatures, as Minecraft's mobs made over (PeakCreatures)
	@Override public void spawnOriginalMobs(WorldGenRegion region) {
		var data = data();
		if (data != null && sea()) { PeakCreatures.spawn(region, data); PeakForage.spawn(region, data); }
	}

	// nothing of Minecraft's own: no ores, granite or dirt blobs (they took PEAK's diorite ground for stone), no
	// flowers, trees or lakes, no villages; PEAK's luggage as chests (PeakThings)
	@Override public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures) {
		var data = data();
		if (data != null && sea()) { PeakThings.luggage(level, chunk, data); PeakForage.fixtures(level, chunk, data); PeakForage.vines(level, chunk, data); PeakCreatures.blocks(level, chunk, data); }
	}

	@Override public void createStructures(RegistryAccess registries, ChunkGeneratorStructureState state, StructureManager structures, ChunkAccess chunk,
		StructureTemplateManager templates, ResourceKey<Level> dimension) { }

	@Override public void createReferences(WorldGenLevel level, StructureManager structures, ChunkAccess chunk) { }

	@Override public int getGenDepth() { return 1408; }

	@Override public int getSeaLevel() { return sea() ? SEA_LEVEL : -64; }

	@Override public int getMinY() { return -64; }

	@Override
	public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState random, StructureManager structures, ChunkAccess chunk) {
		var data = data();
		int minY = chunk.getMinY(), height = chunk.getHeight();
		int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
		LevelChunkSection[] sections = chunk.getSections();
		for (LevelChunkSection s : sections) s.acquire();
		try {
			BlockState[] col = new BlockState[height];
			for (int lx = 0; lx < 16; lx++)
				for (int lz = 0; lz < 16; lz++) {
					column(data, bx + lx, bz + lz, minY, col);
					for (int i = 0; i < height; i++)
						if (!col[i].isAir()) sections[chunk.getSectionIndex(minY + i)].setBlockState(lx, (minY + i) & 15, lz, col[i], false);
				}
		} finally {
			for (LevelChunkSection s : sections) s.release();
		}
		Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
		return CompletableFuture.completedFuture(chunk);
	}

	private static final BlockState AIR = Blocks.AIR.defaultBlockState(), WATER = Blocks.WATER.defaultBlockState(), SAND = Blocks.SAND.defaultBlockState(),
		STONE = Blocks.STONE.defaultBlockState(), BEDROCK = Blocks.BEDROCK.defaultBlockState(), BARRIER = Blocks.BARRIER.defaultBlockState(),
		WET_BARRIER = BARRIER.setValue(BarrierBlock.WATERLOGGED, true),
		FALLING_WATER = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 8),
		CAMPFIRE = Blocks.CAMPFIRE.defaultBlockState().setValue(net.minecraft.world.level.block.CampfireBlock.LIT, false);

	/**
	 * The Mesa's cliffs (red sandstone in the export; PEAK's are orange red with darker streaks): bands of terracotta one to
	 * three blocks thick in the red sandstone, the same at each height all over, as in Minecraft's badlands. (Made here, not
	 * in the export: as runs of blocks, the bands took the Mesa from a million runs to 63 million.)
	 */
	private static final BlockState RED_SANDSTONE = Blocks.RED_SANDSTONE.defaultBlockState();
	private static final BlockState[] BANDS = bands();

	private static BlockState[] bands() {
		BlockState[] b = new BlockState[64];
		BlockState prev = RED_SANDSTONE;
		for (int i = 0; i < 64; i++) {
			double r = bandRand(i, 0), keep = bandRand(i, 1);
			BlockState s = r < 0.55 ? RED_SANDSTONE : IslandData.parseState(r < 0.7 ? "minecraft:orange_terracotta" : r < 0.84 ? "minecraft:terracotta"
				: r < 0.93 ? "minecraft:white_terracotta" : "minecraft:brown_terracotta");
			b[i] = prev = i > 0 && keep < 0.5 ? prev : s;
		}
		return b;
	}

	private static double bandRand(int i, int salt) {
		int h = i * 73856093 ^ salt * 19349663 ^ 4242 * 83492791;
		h ^= h >>> 13; h *= 0x5bd1e995; h ^= h >>> 15;
		return (h & 0xFFFF) / 65536.0;
	}

	/** The column's blocks (x, z), from the bottom of the world (minY) up, into col. */
	void column(IslandData data, int x, int z, int minY, BlockState[] col) {
		int height = col.length, maxY = minY + height - 1;
		Arrays.fill(col, AIR);
		// is the island here (inside its edges), and is this its edge?
		boolean island = data != null, edge = false;
		if (island && walls()) {
			island = data.source(x, z) != IslandData.SEA;
			edge = data.edge(x, z);
		}
		boolean[] any = { false };
		if (island) {
			data.forColumn(x, z, (top, len, state) -> {
				if (state.isAir()) return;
				boolean banded = state == RED_SANDSTONE;
				for (int y = Math.max(minY, top - len + 1); y <= Math.min(maxY, top); y++) col[y - minY] = banded ? BANDS[y & 63] : state;
				any[0] = true;
			});
		}
		if (island && !data.fires.isEmpty()) {
			data.clearCampfires(x, z, minY, col);
			// PEAK's campfire itself, unlit (lit as PEAK's are: a hand on it; MinePeakServer)
			int fy = data.fireY(x, z, minY, col);
			if (fy != Integer.MIN_VALUE) col[fy - minY] = CAMPFIRE;
		}
		// water over the ground (the swamp's film) with a drop beside it would pour over the edge: none there
		if (island && data.hasWater)
			for (int y = Math.max(minY, SEA_LEVEL); y <= maxY; y++)
				if (col[y - minY].is(Blocks.WATER) && data.spills(x, y, z)) col[y - minY] = AIR;
		if (sea()) {
			// round the island: a sandy sea floor (gravel in patches); everywhere: the sea up to its level, bedrock at the bottom
			if (!any[0]) {
				// past the island's edge: its side going steeply down into the sea, then the sea floor (which meets the
				// island's own seabed where that's deeper)
				IslandData.Outside o = data != null && walls() ? data.outside(x, z) : null;
				int g = o != null ? o.height() : SEA_FLOOR;
				boolean dry = o != null && g >= SEA_LEVEL - 1;
				for (int y = minY; y <= Math.min(g, maxY); y++) {
					BlockState s;
					if (dry) s = y == g ? o.top() : o.side() == RED_SANDSTONE ? BANDS[y & 63] : o.side();
					else s = y > g - 4 ? (noise(x, z, 9, 7) > 0.7 ? GRAVEL : SAND) : STONE;
					col[y - minY] = s;
				}
			}
			for (int y = minY; y < SEA_LEVEL && y <= maxY; y++) if (col[y - minY].isAir()) col[y - minY] = edge ? WET_BARRIER : WATER;
			col[0] = BEDROCK;
			if (!edge) seaLife(x, z, minY, col);
		}
		if (island && !edge) data.plants.addTo(x, z, minY, col);
		// PEAK's waterfalls: falling water through the open air of their sheet, on down to what's under it, a source at the top
		int[] fall = island && !edge ? data.fall(x, z) : null;
		if (fall != null) {
			boolean source = true;
			for (int y = Math.min(maxY, fall[0]); y >= minY; y--) {
				if (!col[y - minY].isAir()) { if (y < fall[1]) break; continue; }   // (over a ledge in the sheet; below it, the pool)
				col[y - minY] = source ? WATER : FALLING_WATER;
				source = false;
			}
		}
		if (island && data.hasCross) connect(data, x, z, minY, col);
		// the invisible wall: every open block of the edge, up to the top of the world; and where PEAK's walls stand, as
		// high as they go (in the sea too)
		if (edge) for (int i = 0; i < height; i++) if (col[i].isAir()) col[i] = BARRIER;
		int[] wall = data != null && walls() ? data.wall(x, z) : null;
		if (wall != null)
			for (int y = Math.max(minY, wall[0]); y <= Math.min(maxY, wall[1]); y++) {
				BlockState s = col[y - minY];
				if (s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty()) col[y - minY] = BARRIER;
				else if (s.is(Blocks.WATER)) col[y - minY] = y < SEA_LEVEL ? WET_BARRIER : BARRIER;   // (a waterlogged barrier spreads its water)
			}
	}

	/**
	 * Iron bars, panes and fences joined to their neighbours (bars and panes to bars, panes and solid blocks; fences to
	 * fences and solid blocks), as Minecraft would when they're placed. Minecraft's north is -z.
	 */
	private static void connect(IslandData data, int x, int z, int minY, BlockState[] col) {
		for (int i = 0; i < col.length; i++) {
			if (!(col[i].getBlock() instanceof CrossCollisionBlock)) continue;
			int y = minY + i;
			BlockState s = col[i];
			s = s.setValue(CrossCollisionBlock.NORTH, joins(s, data.blockAt(x, y, z - 1)))
				.setValue(CrossCollisionBlock.SOUTH, joins(s, data.blockAt(x, y, z + 1)))
				.setValue(CrossCollisionBlock.EAST, joins(s, data.blockAt(x + 1, y, z)))
				.setValue(CrossCollisionBlock.WEST, joins(s, data.blockAt(x - 1, y, z)));
			col[i] = s;
		}
	}

	private static boolean joins(BlockState s, BlockState n) {
		if (n.isAir()) return false;
		if (s.getBlock() instanceof net.minecraft.world.level.block.FenceBlock) return n.getBlock() instanceof net.minecraft.world.level.block.FenceBlock || n.canOcclude();
		return n.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock || n.canOcclude();
	}

	// ---- life in the sea ----
	private static final BlockState GRAVEL = Blocks.GRAVEL.defaultBlockState(), SEAGRASS = Blocks.SEAGRASS.defaultBlockState(),
		KELP = Blocks.KELP.defaultBlockState(), KELP_PLANT = Blocks.KELP_PLANT.defaultBlockState();
	private static final BlockState[] TALL_SEAGRASS = {
		Blocks.TALL_SEAGRASS.defaultBlockState().setValue(net.minecraft.world.level.block.TallSeagrassBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER),
		Blocks.TALL_SEAGRASS.defaultBlockState().setValue(net.minecraft.world.level.block.TallSeagrassBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER) };
	private static final BlockState[] CORAL_BLOCK = { Blocks.TUBE_CORAL_BLOCK.defaultBlockState(), Blocks.BRAIN_CORAL_BLOCK.defaultBlockState(),
		Blocks.BUBBLE_CORAL_BLOCK.defaultBlockState(), Blocks.FIRE_CORAL_BLOCK.defaultBlockState(), Blocks.HORN_CORAL_BLOCK.defaultBlockState() };
	private static final BlockState[] CORAL = { Blocks.TUBE_CORAL.defaultBlockState(), Blocks.BRAIN_CORAL.defaultBlockState(),
		Blocks.BUBBLE_CORAL.defaultBlockState(), Blocks.FIRE_CORAL.defaultBlockState(), Blocks.HORN_CORAL.defaultBlockState() };
	private static final BlockState[] CORAL_FAN = { Blocks.TUBE_CORAL_FAN.defaultBlockState(), Blocks.BRAIN_CORAL_FAN.defaultBlockState(),
		Blocks.BUBBLE_CORAL_FAN.defaultBlockState(), Blocks.FIRE_CORAL_FAN.defaultBlockState(), Blocks.HORN_CORAL_FAN.defaultBlockState() };

	/**
	 * On the sea floor (round the island, and on its own sea bed): coral reefs in patches, in clusters of colours, with
	 * coral, fans and sea pickles on them; elsewhere seagrass, tall seagrass and now and then kelp. The fish come from the
	 * MinePeak biome (minepeak:island: only fish, no other mobs).
	 */
	private static void seaLife(int x, int z, int minY, BlockState[] col) {
		int top = SEA_LEVEL - 1 - minY;   // (the sea's top block)
		if (top >= col.length || !col[top].is(Blocks.WATER)) return;
		int floor = top;
		while (floor > 0 && col[floor].is(Blocks.WATER)) floor--;
		BlockState f = col[floor];
		if (!f.isSolidRender()) return;   // (any rock or sand: the island's own seabed too)
		int depth = top - floor;   // (water above the floor)
		if (depth < 3) return;
		double r = hash(x, z, 1);
		if (noise(x, z, 22, 3) > 0.58 && depth >= 5) {
			// a reef: coral blocks, a colour to a cluster
			int colour = (int) (noise(x, z, 7, 11) * 4.999);
			col[floor] = CORAL_BLOCK[colour];
			int at = floor + 1;
			if (r < 0.3 && depth >= 7) { col[at] = CORAL_BLOCK[(colour + 1) % 5]; at++; }
			double t = hash(x, z, 2);
			if (t < 0.4) col[at] = CORAL[colour];
			else if (t < 0.7) col[at] = CORAL_FAN[(colour + 2) % 5];
			else if (t < 0.76) col[at] = Blocks.SEA_PICKLE.defaultBlockState().setValue(net.minecraft.world.level.block.SeaPickleBlock.PICKLES, 1 + (int) (hash(x, z, 3) * 3.999));
			return;
		}
		if (r < 0.12) col[floor + 1] = SEAGRASS;
		else if (r < 0.16) { col[floor + 1] = TALL_SEAGRASS[0]; col[floor + 2] = TALL_SEAGRASS[1]; }
		else if (r < 0.17 && depth >= 6) {
			int h = 3 + (int) (hash(x, z, 4) * (depth - 4));
			for (int i = 1; i < h; i++) col[floor + i] = KELP_PLANT;
			col[floor + h] = KELP;
		}
	}

	private static double hash(int x, int z, int salt) {
		int h = x * 73856093 ^ z * 19349663 ^ salt * 83492791;
		h ^= h >>> 13; h *= 0x5bd1e995; h ^= h >>> 15;
		return (h & 0xFFFF) / 65536.0;
	}

	/** Smooth value noise in 0..1 over cells of the given size. */
	private static double noise(int x, int z, int size, int salt) {
		double gx = (x + 0.5) / size, gz = (z + 0.5) / size;
		int ix = (int) Math.floor(gx), iz = (int) Math.floor(gz);
		double fx = gx - ix, fz = gz - iz;
		fx = fx * fx * (3 - 2 * fx); fz = fz * fz * (3 - 2 * fz);
		double a = hash(ix, iz, salt), b = hash(ix + 1, iz, salt), c = hash(ix, iz + 1, salt), d = hash(ix + 1, iz + 1, salt);
		double ab = a + (b - a) * fx, cd = c + (d - c) * fx;
		return ab + (cd - ab) * fz;
	}

	/** The column's blocks, from the bottom of the world up. */
	BlockState[] column(int x, int z, int minY, int height) {
		BlockState[] col = new BlockState[height];
		column(data(), x, z, minY, col);
		return col;
	}

	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
		BlockState[] col = column(x, z, level.getMinY(), level.getHeight());
		for (int i = col.length - 1; i >= 0; i--)
			if (type.isOpaque().test(col[i])) return level.getMinY() + i + 1;
		return level.getMinY();
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
		return new NoiseColumn(level.getMinY(), column(x, z, level.getMinY(), level.getHeight()));
	}

	@Override
	public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
		var data = data();
		info.add("MinePeak: " + (data != null ? data.name : "no island export") + " (" + area + ")");
	}
}
