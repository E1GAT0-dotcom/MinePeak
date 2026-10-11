package gamemashup.minepeak;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * PEAK's plants, as Minecraft builds them: palms (a curving jungle trunk, a crown of drooping fronds), bushes, beach
 * weeds, ferns, the redwoods' crowns (layered spruce cones on the trunks the export made), mushroom trees, flowers,
 * small cacti, hanging vines (cave vines, now and then with glow berries) and chains, jungle trees, monsteras,
 * mangroves, willows, cattails (sugar cane), Venus flytraps (pitcher plants) and red beach weeds. From island.json (each area's "plants": where, how big; the MinePeak Exporter writes them). Built a
 * column at a time as the world generates, into air only (never over the ground, the sea or the invisible wall).
 */
final class IslandPlants {
	/** k: kind; b: foot; c: crown's middle; r: crown's radius; cb, t: crown's bottom and top; w: trunk width. */
	record Plant(String k, double bx, double by, double bz, double cx, double cy, double cz, double r, double cb, double t, double w, int seed,
		int x0, int z0, int x1, int z1) { }

	private static final int CELL = 32;
	final List<Plant> plants = new ArrayList<>();
	private final Map<Long, int[]> grid = new HashMap<>();

	static final IslandPlants NONE = new IslandPlants();

	static IslandPlants of(JsonObject info) {
		IslandPlants p = new IslandPlants();
		if (!info.has("areas")) return p;
		Map<Long, List<Integer>> cells = new HashMap<>();
		for (JsonElement ae : info.getAsJsonArray("areas")) {
			JsonObject a = ae.getAsJsonObject();
			if (!a.has("plants")) continue;
			for (JsonElement pe : a.getAsJsonArray("plants")) {
				JsonObject o = pe.getAsJsonObject();
				JsonArray b = o.getAsJsonArray("b"), c = o.getAsJsonArray("c");
				double bx = b.get(0).getAsDouble(), by = b.get(1).getAsDouble(), bz = b.get(2).getAsDouble();
				double cx = c.get(0).getAsDouble(), cy = c.get(1).getAsDouble(), cz = c.get(2).getAsDouble();
				double r = o.get("r").getAsDouble();
				String k = o.get("k").getAsString();
				if (k.equals("vine") && a.has("biome") && a.get("biome").getAsString().equals("Volcano")) k = "rootvine";   // (no lush cave vines in a volcano)
				double reach = switch (k) { case "palm" -> Math.min(r, 9) + 1; case "redwood" -> Math.min(r, 40); case "weed", "fern", "redweed", "blueweed" -> Math.min(r, 3.5);
					case "tree" -> Math.min(r, 12) + 1; case "mangrove", "willow" -> Math.min(r, 14) + 1; case "pine" -> Math.min(r, 9) + 1;
					case "deadtree" -> 6; default -> r + 1; };
				int x0 = (int) Math.floor(Math.min(bx, cx) - reach), x1 = (int) Math.floor(Math.max(bx, cx) + reach);
				int z0 = (int) Math.floor(Math.min(bz, cz) - reach), z1 = (int) Math.floor(Math.max(bz, cz) + reach);
				int seed = (int) (Double.doubleToLongBits(bx * 31 + bz * 17 + by) ^ (Double.doubleToLongBits(bx * 31 + bz * 17 + by) >>> 32));
				int index = p.plants.size();
				p.plants.add(new Plant(k, bx, by, bz, cx, cy, cz, r, o.get("cb").getAsDouble(), o.get("t").getAsDouble(), o.get("w").getAsDouble(), seed, x0, z0, x1, z1));
				for (int gx = Math.floorDiv(x0, CELL); gx <= Math.floorDiv(x1, CELL); gx++)
					for (int gz = Math.floorDiv(z0, CELL); gz <= Math.floorDiv(z1, CELL); gz++)
						cells.computeIfAbsent(key(gx, gz), q -> new ArrayList<>()).add(index);
			}
		}
		cells.forEach((kk, v) -> p.grid.put(kk, v.stream().mapToInt(Integer::intValue).toArray()));
		if (!p.plants.isEmpty()) {
			Map<String, Integer> count = new java.util.TreeMap<>();
			for (Plant pl : p.plants) count.merge(pl.k(), 1, Integer::sum);
			MinePeak.LOG.info("MinePeak plants: {}", count);
		}
		return p;
	}

	private static long key(int gx, int gz) { return ((long) gx << 32) | (gz & 0xFFFFFFFFL); }

	// ---- the blocks ----
	private static final BlockState AIR = Blocks.AIR.defaultBlockState();
	private static final BlockState PALM_LOG = Blocks.STRIPPED_JUNGLE_LOG.defaultBlockState();   // (PEAK's palms are pale)
	private static final BlockState PALM_LEAVES = leaves(Blocks.JUNGLE_LEAVES.defaultBlockState());
	private static final BlockState BUSH = leaves(Blocks.OAK_LEAVES.defaultBlockState()), AZALEA = leaves(Blocks.AZALEA_LEAVES.defaultBlockState()),
		FLOWERING = leaves(Blocks.FLOWERING_AZALEA_LEAVES.defaultBlockState()), PINE = leaves(Blocks.SPRUCE_LEAVES.defaultBlockState());
	private static final BlockState STEM = Blocks.MUSHROOM_STEM.defaultBlockState(), CAP = Blocks.RED_MUSHROOM_BLOCK.defaultBlockState();
	private static final BlockState GRASS = Blocks.SHORT_GRASS.defaultBlockState(), FERN = Blocks.FERN.defaultBlockState();
	private static final BlockState[] TALL_GRASS = tall(Blocks.TALL_GRASS.defaultBlockState()), LARGE_FERN = tall(Blocks.LARGE_FERN.defaultBlockState());
	private static final BlockState[] FLOWERS = { Blocks.POPPY.defaultBlockState(), Blocks.RED_TULIP.defaultBlockState(), Blocks.PINK_TULIP.defaultBlockState(), Blocks.ALLIUM.defaultBlockState() };
	private static final BlockState CACTUS = Blocks.CACTUS.defaultBlockState();
	private static final BlockState JUNGLE_LOG = Blocks.JUNGLE_LOG.defaultBlockState(), JUNGLE_LEAVES = leaves(Blocks.JUNGLE_LEAVES.defaultBlockState());
	private static final BlockState MANGROVE_LOG = Blocks.MANGROVE_LOG.defaultBlockState(), MANGROVE_ROOTS = Blocks.MANGROVE_ROOTS.defaultBlockState(),
		MANGROVE_LEAVES = leaves(Blocks.MANGROVE_LEAVES.defaultBlockState()), WILLOW = leaves(Blocks.CHERRY_LEAVES.defaultBlockState());
	private static final BlockState CANE = Blocks.SUGAR_CANE.defaultBlockState(), CHAIN = Blocks.IRON_CHAIN.defaultBlockState(),
		DRY_GRASS = Blocks.SHORT_DRY_GRASS.defaultBlockState(), TALL_DRY_GRASS = Blocks.TALL_DRY_GRASS.defaultBlockState(), DEAD_BUSH = Blocks.DEAD_BUSH.defaultBlockState(),
		BLUE_GRASS = MinePeakBlocks.BLUE_BEACH_GRASS.defaultBlockState(), TALL_BLUE_GRASS = MinePeakBlocks.TALL_BLUE_BEACH_GRASS.defaultBlockState();
	private static final BlockState[] PITCHER = tall(Blocks.PITCHER_PLANT.defaultBlockState());
	private static final BlockState ROOTS = Blocks.HANGING_ROOTS.defaultBlockState();
	private static final BlockState SPRUCE_LOG = Blocks.SPRUCE_LOG.defaultBlockState(), SPRUCE_WOOD = Blocks.SPRUCE_WOOD.defaultBlockState(),
		SNOW = Blocks.SNOW.defaultBlockState();
	private static final BlockState VINE = Blocks.CAVE_VINES_PLANT.defaultBlockState(), VINE_TIP = Blocks.CAVE_VINES.defaultBlockState(),
		VINE_BERRIES = Blocks.CAVE_VINES_PLANT.defaultBlockState().setValue(net.minecraft.world.level.block.CaveVines.BERRIES, true);

	private static BlockState leaves(BlockState s) { return s.setValue(LeavesBlock.PERSISTENT, true); }
	private static BlockState[] tall(BlockState s) {
		return new BlockState[] { s.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER), s.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER) };
	}

	/** Adds the plants over column (x, z) into col (from minY up). */
	void addTo(int x, int z, int minY, BlockState[] col) {
		int[] here = grid.get(key(Math.floorDiv(x, CELL), Math.floorDiv(z, CELL)));
		if (here == null) return;
		for (int i : here) {
			Plant p = plants.get(i);
			if (x < p.x0() || x > p.x1() || z < p.z0() || z > p.z1()) continue;
			switch (p.k()) {
				case "palm" -> palm(p, x, z, minY, col);
				case "bush" -> bush(p, x, z, minY, col);
				case "weed" -> weed(p, x, z, minY, col);
				case "fern" -> fern(p, x, z, minY, col);
				case "redwood" -> redwood(p, x, z, minY, col);
				case "pine" -> pine(p, x, z, minY, col);
				case "deadtree" -> deadtree(p, x, z, minY, col);
				case "pinebush" -> pinebush(p, x, z, minY, col);
				case "mushroom" -> mushroom(p, x, z, minY, col);
				case "flower" -> { if (x == (int) Math.floor(p.bx()) && z == (int) Math.floor(p.bz())) onGround(col, minY, p.by(), FLOWERS[Math.floorMod(p.seed(), FLOWERS.length)]); }
				case "cactus" -> cactus(p, x, z, minY, col);
				case "vine" -> vine(p, x, z, minY, col, false);
				case "rootvine" -> vine(p, x, z, minY, col, true);
				case "tree" -> tree(p, x, z, minY, col);
				case "mangrove" -> mangrove(p, x, z, minY, col);
				case "willow" -> willow(p, x, z, minY, col);
				case "monstera" -> { if (at(p, x, z)) { if (hash(x, z, p.seed()) < 0.6) onGroundTall(col, minY, p.by(), LARGE_FERN); else onGround(col, minY, p.by(), FERN); } }
				case "cattail" -> { if (at(p, x, z)) { int g = ground(col, minY, p.by()); if (g != Integer.MIN_VALUE) { set(col, minY, g + 1, CANE); set(col, minY, g + 2, CANE); } } }
				case "flytrap" -> { if (at(p, x, z)) onGroundTall(col, minY, p.by(), PITCHER); }
				case "redweed" -> redweed(p, x, z, minY, col);
				case "blueweed" -> blueweed(p, x, z, minY, col);
				case "chain" -> chain(p, x, z, minY, col);
				default -> { }
			}
		}
	}

	private static void set(BlockState[] col, int minY, int y, BlockState s) {
		int i = y - minY;
		if (i >= 0 && i < col.length && col[i].isAir()) col[i] = s;
	}

	private static double hash(int x, int z, int salt) {
		int h = x * 73856093 ^ z * 19349663 ^ salt * 83492791;
		h ^= h >>> 13; h *= 0x5bd1e995; h ^= h >>> 15;
		return (h & 0xFFFF) / 65536.0;
	}

	/** The top of the ground near y in this column (a solid block with air above, not a plant or leaves), or Integer.MIN_VALUE. */
	private static int ground(BlockState[] col, int minY, double near) {
		int n = (int) Math.floor(near);
		for (int y = n + 4; y >= n - 6; y--) {
			int i = y - minY;
			if (i < 0 || i + 1 >= col.length) continue;
			BlockState s = col[i];
			if (s.isSolidRender() && s.getFluidState().isEmpty() && !s.is(Blocks.BARRIER) && col[i + 1].isAir()) return y;
		}
		return Integer.MIN_VALUE;
	}

	private static void onGround(BlockState[] col, int minY, double near, BlockState s) {
		int g = ground(col, minY, near);
		if (g != Integer.MIN_VALUE) set(col, minY, g + 1, s);
	}

	private static void onGroundTall(BlockState[] col, int minY, double near, BlockState[] s) {
		int g = ground(col, minY, near);
		if (g == Integer.MIN_VALUE || g + 2 - minY >= col.length || !col[g + 2 - minY].isAir()) return;
		set(col, minY, g + 1, s[0]);
		set(col, minY, g + 2, s[1]);
	}

	/** A trunk curving from the foot up to the crown (leaning more towards the top), and fronds drooping all round. */
	private static void palm(Plant p, int x, int z, int minY, BlockState[] col) {
		double h = p.cy() - p.by();
		if (h > 1) {
			int steps = (int) Math.ceil(h * 3);
			for (int s = 0; s <= steps; s++) {
				double t = (double) s / steps, lean = t * t;
				double px = p.bx() + (p.cx() - p.bx()) * lean, pz = p.bz() + (p.cz() - p.bz()) * lean, py = p.by() + h * t;
				if ((int) Math.floor(px) == x && (int) Math.floor(pz) == z) set(col, minY, (int) Math.floor(py), PALM_LOG);
			}
		}
		double reach = Math.max(3, Math.min(p.r(), 9));
		int fronds = 7 + Math.floorMod(p.seed(), 3);
		double turn = hash(p.seed(), 0, 5) * Math.PI;
		for (int f = 0; f < fronds; f++) {
			double a = turn + f * 2 * Math.PI / fronds;
			for (double s = 0; s <= reach; s += 0.4) {
				double px = p.cx() + Math.cos(a) * s, pz = p.cz() + Math.sin(a) * s;
				if ((int) Math.floor(px) != x || (int) Math.floor(pz) != z) continue;
				double droop = (s / reach) * (s / reach) * reach * 0.55;
				set(col, minY, (int) Math.floor(p.cy() + 1 - droop), PALM_LEAVES);
			}
		}
		if (Math.abs(x + 0.5 - p.cx()) <= 1.2 && Math.abs(z + 0.5 - p.cz()) <= 1.2) set(col, minY, (int) Math.floor(p.cy() + 1), PALM_LEAVES);
	}

	/** A rounded bush of leaves (some azalea, now and then flowering). */
	private static void bush(Plant p, int x, int z, int minY, BlockState[] col) {
		double rx = Math.max(1, p.r()), ry = Math.max(1, (p.t() - p.cb()) / 2), cy = (p.t() + p.cb()) / 2;
		double dx = (x + 0.5 - p.cx()) / rx, dz = (z + 0.5 - p.cz()) / rx, d2 = dx * dx + dz * dz;
		if (d2 > 1) return;
		double half = ry * Math.sqrt(1 - d2);
		double pick = hash(x, z, p.seed());
		BlockState s = pick < 0.12 ? FLOWERING : pick < 0.4 ? AZALEA : BUSH;
		for (int y = (int) Math.floor(cy - half); y <= (int) Math.floor(cy + half); y++) set(col, minY, y, s);
	}

	/** A patch of beach grass: short and tall grass, and some ferns. */
	private static void weed(Plant p, int x, int z, int minY, BlockState[] col) {
		double r = Math.max(1, Math.min(p.r(), 3.5)), dx = x + 0.5 - p.cx(), dz = z + 0.5 - p.cz();
		if (dx * dx + dz * dz > r * r || hash(x, z, p.seed()) > 0.6) return;
		double pick = hash(z, x, p.seed() + 1);
		int g = ground(col, minY, p.by());
		if (g != Integer.MIN_VALUE && col[g - minY].is(Blocks.SNOW_BLOCK)) {   // (frosted, on snow: as PEAK's)
			set(col, minY, g + 1, pick < 0.5 ? TALL_DRY_GRASS : DRY_GRASS);
			return;
		}
		if (r > 2 && pick < 0.45) onGroundTall(col, minY, p.by(), TALL_GRASS);
		else onGround(col, minY, p.by(), pick < 0.85 ? GRASS : FERN);
	}

	/** A clump of ferns: large ones in the middle. */
	private static void fern(Plant p, int x, int z, int minY, BlockState[] col) {
		double r = Math.max(1, Math.min(p.r(), 3)), dx = x + 0.5 - p.cx(), dz = z + 0.5 - p.cz(), d2 = dx * dx + dz * dz;
		if (d2 > r * r || hash(x, z, p.seed()) > 0.7) return;
		if (d2 < 2.5) onGroundTall(col, minY, p.by(), LARGE_FERN);
		else onGround(col, minY, p.by(), FERN);
	}

	/**
	 * A redwood's crown: tiers of spruce leaves, each wide at its bottom and smaller going up (the trunk is the export's).
	 * Each tier a width of its own and lobed, its rim ragged and drooping a block (perfect round tiers looked made).
	 */
	private static void redwood(Plant p, int x, int z, int minY, BlockState[] col) {
		double bottom = p.cb(), top = p.t(), radius = Math.min(p.r(), 40);
		if (top - bottom < 2) return;
		double dx = x + 0.5 - p.cx(), dz = z + 0.5 - p.cz(), d = Math.sqrt(dx * dx + dz * dz), ang = Math.atan2(dz, dx);
		int layer = (int) Math.max(4, Math.min(8, (top - bottom) / 6));
		for (int y = (int) Math.floor(bottom); y <= (int) Math.floor(top); y++) {
			double f = (y - bottom) / (top - bottom);
			int tier = Math.floorDiv(y - (int) Math.floor(bottom), layer), in = Math.floorMod(y - (int) Math.floor(bottom), layer);
			double width = 0.82 + 0.32 * hash(tier, 3, p.seed());
			double lobes = 1 + 0.15 * Math.sin(ang * 3 + hash(tier, 5, p.seed()) * 6.283) + 0.08 * Math.sin(ang * 7 + tier * 1.7);
			double r = radius * (1 - 0.92 * f) * (1 - 0.5 * in / (double) layer) * width * lobes;
			if (d > Math.max(0.6, r)) continue;
			boolean rim = r > 3 && d > r - 1.6;
			if (rim && hash(x * 31 + y, z, p.seed()) < 0.35) continue;   // (ragged at the edge)
			set(col, minY, rim && in == 0 ? y - 1 : y, PINE);
		}
	}

	/**
	 * One of the Alpine's pines (PEAK's Ice_Pine: a whole tree, foot to tip): a spruce trunk, and from a fifth of the way
	 * up, tiers of spruce leaves narrowing to the tip, each its own width, ragged at the rim, with snow on them.
	 */
	private static void pine(Plant p, int x, int z, int minY, BlockState[] col) {
		int bx = (int) Math.floor(p.bx()), bz = (int) Math.floor(p.bz()), base = (int) Math.floor(p.by()), top = (int) Math.floor(p.t());
		if (x == bx && z == bz) for (int y = base; y < top - 1; y++) set(col, minY, y, SPRUCE_LOG);
		double crown0 = p.by() + (p.t() - p.by()) * 0.2, radius = Math.max(1.5, Math.min(p.r() * 0.85, 9));
		double dx = x + 0.5 - (bx + 0.5), dz = z + 0.5 - (bz + 0.5), d = Math.sqrt(dx * dx + dz * dz), ang = Math.atan2(dz, dx);
		int highest = Integer.MIN_VALUE;
		for (int y = (int) Math.floor(crown0); y <= top; y++) {
			double f = (y - crown0) / Math.max(1, p.t() - crown0);
			int tier = Math.floorDiv(y - (int) Math.floor(crown0), 3), in = Math.floorMod(y - (int) Math.floor(crown0), 3);
			double r = radius * (1 - f) * (1 - 0.35 * in / 3.0) * (0.88 + 0.24 * hash(tier, 3, p.seed())) * (1 + 0.12 * Math.sin(ang * 3 + tier * 1.3));
			if (d > Math.max(0.5, r)) continue;
			if (r > 2 && d > r - 1.2 && hash(x * 31 + y, z, p.seed()) < 0.3) continue;
			set(col, minY, y, PINE);
			highest = y;
		}
		if (highest != Integer.MIN_VALUE) set(col, minY, highest + 1, SNOW);
	}

	/** One of the Alpine's dead trees: a bare spruce trunk with a few branches reaching out and up. */
	private static void deadtree(Plant p, int x, int z, int minY, BlockState[] col) {
		int bx = (int) Math.floor(p.bx()), bz = (int) Math.floor(p.bz()), base = (int) Math.floor(p.by());
		int h = (int) Math.max(4, Math.min(18, (p.t() - p.by()) * 0.85));
		if (x == bx && z == bz) for (int y = base; y < base + h; y++) set(col, minY, y, SPRUCE_LOG);
		int branches = 3 + Math.floorMod(p.seed(), 3);
		for (int b = 0; b < branches; b++) {
			double a = hash(p.seed(), b, 11) * 2 * Math.PI, len = 2 + hash(b, p.seed(), 13) * 3;
			int y0 = base + h / 2 + (int) (hash(b, 7, p.seed()) * h / 2);
			for (double s = 1; s <= len; s += 0.5) {
				int px = (int) Math.floor(bx + 0.5 + Math.cos(a) * s), pz = (int) Math.floor(bz + 0.5 + Math.sin(a) * s);
				if (px == x && pz == z) set(col, minY, y0 + (int) (s / 2), SPRUCE_WOOD);
			}
		}
	}

	/** A snowy pine shrub: a round heap of spruce leaves, snow on it. */
	private static void pinebush(Plant p, int x, int z, int minY, BlockState[] col) {
		double rx = Math.max(1, Math.min(p.r(), 3.5)), ry = Math.max(1, (p.t() - p.cb()) / 2), cy = (p.t() + p.cb()) / 2;
		double dx = (x + 0.5 - p.cx()) / rx, dz = (z + 0.5 - p.cz()) / rx, d2 = dx * dx + dz * dz;
		if (d2 > 1) return;
		double half = ry * Math.sqrt(1 - d2);
		int topY = (int) Math.floor(cy + half);
		for (int y = (int) Math.floor(cy - half); y <= topY; y++) set(col, minY, y, PINE);
		set(col, minY, topY + 1, SNOW);
	}

	/** A tall mushroom stem with a flat red cap. */
	private static void mushroom(Plant p, int x, int z, int minY, BlockState[] col) {
		int sx = (int) Math.floor(p.bx()), sz = (int) Math.floor(p.bz());
		int top = (int) Math.floor(p.t());
		if (x == sx && z == sz) for (int y = (int) Math.floor(p.by()); y < top; y++) set(col, minY, y, STEM);
		double cap = Math.max(2, Math.min(p.r() + 1, 6)), dx = x + 0.5 - (sx + 0.5), dz = z + 0.5 - (sz + 0.5), d = Math.sqrt(dx * dx + dz * dz);
		if (d <= cap) set(col, minY, top, CAP);
		if (d <= cap && d > cap - 1.2) set(col, minY, top - 1, CAP);
	}

	/**
	 * A curtain of hanging vines: strands here and there over its width, each hanging from the rock or branch above its
	 * top (none where there's nothing to hang from), most of the way down.
	 */
	private static void vine(Plant p, int x, int z, int minY, BlockState[] col, boolean dry) {
		double dx = x + 0.5 - p.cx(), dz = z + 0.5 - p.cz(), r = Math.max(1, p.r());
		if (dx * dx + dz * dz > r * r || hash(x, z, p.seed()) > 0.12) return;
		// what it hangs from: the first solid block at or above its top, with air under it
		int top = (int) Math.floor(p.t()), from = Integer.MIN_VALUE;
		for (int y = top - 3; y <= top + 6; y++) {
			int i = y - minY;
			if (i <= 0 || i >= col.length) continue;
			if (!col[i].isAir() && col[i].getFluidState().isEmpty() && col[i - 1].isAir()) { from = y - 1; break; }
		}
		if (from == Integer.MIN_VALUE) return;
		int length = (int) Math.max(3, (p.t() - p.cb()) * (0.5 + 0.5 * hash(z, x, p.seed())));
		int y = from;
		for (int n = 0; n < length; n++, y--) {
			int i = y - minY;
			if (i <= 0 || !col[i].isAir() || !col[i - 1].isAir()) break;
			col[i] = dry ? ROOTS : hash(x, y, p.seed()) < 0.08 ? VINE_BERRIES : VINE;
		}
		int tip = y + 1 - minY;
		if (!dry && tip < col.length && tip > 0 && col[tip].is(Blocks.CAVE_VINES_PLANT)) col[tip] = VINE_TIP;
	}

	private static boolean at(Plant p, int x, int z) { return x == (int) Math.floor(p.bx()) && z == (int) Math.floor(p.bz()); }

	/** A jungle tree: a straight trunk (two by two when it's thick) up into a rounded crown of jungle leaves. */
	private static void tree(Plant p, int x, int z, int minY, BlockState[] col) {
		int bx = (int) Math.floor(p.bx()), bz = (int) Math.floor(p.bz());
		boolean thick = p.w() >= 1.5;
		if ((x == bx || thick && x == bx + 1) && (z == bz || thick && z == bz + 1))
			for (int y = (int) Math.floor(p.by()); y < (int) Math.floor((p.cb() + p.t()) / 2); y++) set(col, minY, y, JUNGLE_LOG);
		double rx = Math.max(2, Math.min(p.r(), 12)), ry = Math.max(2, (p.t() - p.cb()) / 2), cy = (p.t() + p.cb()) / 2;
		double dx = (x + 0.5 - p.cx()) / rx, dz = (z + 0.5 - p.cz()) / rx, d2 = dx * dx + dz * dz;
		if (d2 > 1) return;
		double half = ry * Math.sqrt(1 - d2);
		for (int y = (int) Math.floor(cy - half * 0.7); y <= (int) Math.floor(cy + half); y++)
			if (hash(x, y * 31 + z, p.seed()) < 0.92) set(col, minY, y, JUNGLE_LEAVES);
	}

	/** A mangrove: roots arching down into the swamp, a short trunk, a wide flat crown. */
	private static void mangrove(Plant p, int x, int z, int minY, BlockState[] col) {
		int bx = (int) Math.floor(p.bx()), bz = (int) Math.floor(p.bz()), base = (int) Math.floor(p.by());
		int crownBottom = (int) Math.floor(p.t() - Math.max(3, (p.t() - p.by()) * 0.35));
		if (x == bx && z == bz) for (int y = base + 2; y < crownBottom; y++) set(col, minY, y, MANGROVE_LOG);
		// roots: from the trunk 3 up, out and down to the ground in six directions
		for (int a = 0; a < 6; a++) {
			double ang = a * Math.PI / 3 + hash(p.seed(), a, 7);
			for (double s = 0; s <= 3.5; s += 0.5) {
				int rx = (int) Math.floor(bx + 0.5 + Math.cos(ang) * s), rz = (int) Math.floor(bz + 0.5 + Math.sin(ang) * s);
				if (rx == x && rz == z) set(col, minY, (int) Math.floor(base + 3 - s), MANGROVE_ROOTS);
			}
		}
		double r = Math.max(3, Math.min(p.r() * 0.8, 14)), dx = x + 0.5 - p.cx(), dz = z + 0.5 - p.cz(), d = Math.sqrt(dx * dx + dz * dz);
		if (d > r) return;
		int thick = d < r * 0.6 ? 3 : 2;
		for (int y = crownBottom; y < crownBottom + thick; y++) if (hash(x, y * 17 + z, p.seed()) < 0.9) set(col, minY, y, MANGROVE_LEAVES);
	}

	/**
	 * The swamp's blossom (PEAK's "Willow Leaf Bundle", lilac, hanging like wisteria from the mangroves' branches): a ragged
	 * dome of cherry leaves, with strands of them hanging here and there, longer towards its middle.
	 */
	private static void willow(Plant p, int x, int z, int minY, BlockState[] col) {
		double r = Math.max(2, Math.min(p.r() * 0.8, 12)), dx = x + 0.5 - p.cx(), dz = z + 0.5 - p.cz(), d = Math.sqrt(dx * dx + dz * dz);
		if (d > r || hash(x, z, p.seed() + 3) < 0.15) return;
		int top = (int) Math.floor(p.t());
		int dome = top - (int) Math.floor(4 * (d / r) * (d / r));
		set(col, minY, dome, WILLOW);
		if (hash(z, x, p.seed() + 5) < 0.7) set(col, minY, dome - 1, WILLOW);
		if (hash(x, z, p.seed()) < 0.3) {
			int length = (int) Math.max(2, (p.t() - p.cb()) * (0.15 + 0.45 * hash(z, x, p.seed())) * (1.2 - d / r));
			int y = dome - 2;
			for (int n = 0; n < length; n++, y--) {
				int i = y - minY;
				if (i <= 0 || !col[i].isAir()) break;
				col[i] = WILLOW;
			}
		}
	}

	/** A clump of the Red Beach's weeds (PEAK's "Generic_Red": pale dry grass): tall dry grass, short round its edge, a dead bush now and then. */
	private static void redweed(Plant p, int x, int z, int minY, BlockState[] col) {
		double r = Math.max(1, Math.min(p.r(), 3.5)), dx = x + 0.5 - p.cx(), dz = z + 0.5 - p.cz(), d2 = dx * dx + dz * dz;
		if (d2 > r * r || hash(x, z, p.seed()) > 0.5) return;
		double pick = hash(z, x, p.seed() + 1);
		onGround(col, minY, p.by(), pick < 0.04 ? DEAD_BUSH : d2 < r * r * 0.5 && pick < 0.8 ? TALL_DRY_GRASS : DRY_GRASS);
	}

	/** The Blue Beach's weeds: MinePeak's blue grass, short and tall. */
	private static void blueweed(Plant p, int x, int z, int minY, BlockState[] col) {
		double r = Math.max(1, Math.min(p.r(), 3.5)), dx = x + 0.5 - p.cx(), dz = z + 0.5 - p.cz(), d2 = dx * dx + dz * dz;
		if (d2 > r * r || hash(x, z, p.seed()) > 0.55) return;
		double pick = hash(z, x, p.seed() + 1);
		onGround(col, minY, p.by(), d2 < r * r * 0.5 && pick < 0.7 ? TALL_BLUE_GRASS : BLUE_GRASS);
	}

	/** A chain hanging straight down (the temple's). */
	private static void chain(Plant p, int x, int z, int minY, BlockState[] col) {
		if (x != (int) Math.floor(p.cx()) || z != (int) Math.floor(p.cz())) return;
		for (int y = (int) Math.floor(p.t()); y >= (int) Math.floor(p.cb()); y--) {
			int i = y - minY;
			if (i <= 0 || i >= col.length || !col[i].isAir()) break;
			col[i] = CHAIN;
		}
	}

	/** A small cactus, one to three blocks, on sand. */
	private static void cactus(Plant p, int x, int z, int minY, BlockState[] col) {
		if (x != (int) Math.floor(p.bx()) || z != (int) Math.floor(p.bz())) return;
		int g = ground(col, minY, p.by());
		if (g == Integer.MIN_VALUE) return;
		BlockState under = col[g - minY];
		if (!under.is(net.minecraft.tags.BlockTags.SAND)) return;
		int h = (int) Math.max(1, Math.min(3, Math.round(p.t() - p.by())));
		for (int y = 1; y <= h; y++) set(col, minY, g + y, CACTUS);
	}
}
