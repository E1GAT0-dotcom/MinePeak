package gamemashup.minepeak;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * A PEAK island exported by the MinePeak Exporter (a PEAK plugin) on this PC:
 * %APPDATA%/MinePeak/islands/&lt;date&gt;_&lt;scene&gt;/island.json and one .bin of block columns per area.
 * Each column is a list of runs (its top block's y, its length down from there, a palette index).
 */
public final class IslandData {
	public static final Path ROOT = Path.of(System.getenv().getOrDefault("APPDATA", "."), "MinePeak", "islands");

	public static final class Area {
		public final int x0, z0, w, d;
		final int[] start;   // per column: its first run's index in runs (w*d + 1 entries)
		final int[] runs;    // y, length, palette; y, length, palette; ...

		Area(int x0, int z0, int w, int d, int[] start, int[] runs) {
			this.x0 = x0; this.z0 = z0; this.w = w; this.d = d; this.start = start; this.runs = runs;
		}

		public boolean contains(int x, int z) { return x >= x0 && z >= z0 && x < x0 + w && z < z0 + d; }
	}

	public final String name;
	public final JsonObject info;
	public final BlockState[] palette;
	public final List<Area> areas;
	public final int minY;
	/** Its plants (palms, bushes, ferns...), built as the world generates. */
	IslandPlants plants = IslandPlants.NONE;

	private IslandData(String name, JsonObject info, BlockState[] palette, List<Area> areas) {
		this.name = name; this.info = info; this.palette = palette; this.areas = areas;
		this.minY = info.get("minY").getAsInt();
	}

	private static final Map<String, IslandData> CACHE = new HashMap<>();

	private record Hit(String island, String part, IslandData data) { }
	private static volatile Hit last;

	/**
	 * The island (a folder name, or "latest") and which part of it: "island" (its segments) or "airport". The last one
	 * asked for is answered without the lock (the world's generation asks thousands of times a chunk, from many threads).
	 */
	public static IslandData get(String island, String part) {
		Hit h = last;
		if (h != null && h.island.equals(island) && h.part.equals(part)) return h.data;
		IslandData data = lookUp(island, part);
		if (data != null && !"latest".equals(island)) last = new Hit(island, part, data);
		return data;
	}

	private static synchronized IslandData lookUp(String island, String part) {
		IslandData data = CACHE.get(island + "|" + part);   // (by folder name: "latest" is looked up each time)
		if (data != null) return data;
		try {
			Path dir = "latest".equals(island) ? latestCached() : ROOT.resolve(island);
			if (dir == null || !Files.isRegularFile(dir.resolve("island.json"))) {
				if (MISSING.add(island)) MinePeak.LOG.warn("No MinePeak island export {} in {}", island, ROOT);
				return null;   // (not kept: it may be exported while Minecraft runs)
			}
			String key = dir.getFileName() + "|" + part;
			data = CACHE.get(key);
			if (data == null) CACHE.put(key, data = load(dir, part));
		} catch (Exception e) {
			MinePeak.LOG.error("Couldn't read the MinePeak island {}", island, e);
		}
		return data;
	}

	private static final java.util.Set<String> MISSING = new java.util.HashSet<>();

	private static Path latestSeen;
	private static long latestAt;

	/** The newest finished export, looked through every 5 s at most (for lookups many times a second). */
	private static synchronized Path latestCached() throws IOException {
		long now = System.currentTimeMillis();
		if (now - latestAt < 5000) return latestSeen;
		latestAt = now;
		return latestSeen = latest();
	}

	/** The newest finished export. */
	public static Path latest() throws IOException {
		if (!Files.isDirectory(ROOT)) return null;
		try (Stream<Path> s = Files.list(ROOT)) {
			return s.filter(p -> Files.isRegularFile(p.resolve("island.json")) && !p.getFileName().toString().startsWith("exporting_"))
				.max(Comparator.comparingLong(p -> p.toFile().lastModified())).orElse(null);
		}
	}

	private static IslandData load(Path dir, String part) throws IOException {
		JsonObject info = JsonParser.parseString(Files.readString(dir.resolve("island.json"))).getAsJsonObject();
		JsonArray pal = info.getAsJsonArray("palette");
		BlockState[] palette = new BlockState[pal.size()];
		for (int i = 0; i < palette.length; i++) palette[i] = parseState(pal.get(i).getAsString());
		List<JsonObject> wanted = new ArrayList<>();
		if ("airport".equals(part)) { if (info.has("airport")) wanted.add(info.getAsJsonObject("airport")); }
		else for (JsonElement e : info.getAsJsonArray("areas")) wanted.add(e.getAsJsonObject());
		List<Area> areas = new ArrayList<>();
		long columns = 0;
		for (JsonObject a : wanted) {
			Area area = readArea(dir.resolve(a.get("file").getAsString()));
			areas.add(area);
			columns += (long) area.w * area.d;
		}
		MinePeak.LOG.info("MinePeak island {} ({}): {} areas, {} columns, {} kinds of block", dir.getFileName(), part, areas.size(), columns, palette.length);
		IslandData data = new IslandData(dir.getFileName().toString(), info, palette, areas);
		for (BlockState st : palette) if (st.getBlock() instanceof net.minecraft.world.level.block.CrossCollisionBlock) data.hasCross = true;
		for (BlockState st : palette) if (st.is(Blocks.WATER)) data.hasWater = true;
		if ("island".equals(part)) {
			data.findEdges();
			data.plants = IslandPlants.of(info);
			for (JsonObject a : data.campfires()) {
				JsonArray c = a.getAsJsonArray("campfire");
				data.fires.add(new int[] { c.get(0).getAsInt(), c.get(1).getAsInt(), c.get(2).getAsInt() });
			}
			data.planNotches();
			data.planWalls();
			data.planFalls();
		}
		return data;
	}

	// ---- PEAK's waterfalls ----
	// Drawn only in PEAK (a sheet of falling water, no collider): the export says which columns each one falls through,
	// from where to where. MinePeak puts falling water there.

	private final Map<Long, int[]> falls = new HashMap<>();

	private void planFalls() {
		if (!info.has("waterfalls")) return;
		for (JsonElement e : info.getAsJsonArray("waterfalls")) {
			JsonArray f = e.getAsJsonArray();
			long k = pack(f.get(0).getAsInt(), f.get(1).getAsInt());
			int top = f.get(2).getAsInt(), bottom = f.get(3).getAsInt();
			int[] had = falls.get(k);
			falls.put(k, had == null ? new int[] { top, bottom } : new int[] { Math.max(had[0], top), Math.min(had[1], bottom) });
		}
	}

	/** A waterfall's water in column (x, z): {top y, bottom y}, or null. */
	public int[] fall(int x, int z) { return falls.get(pack(x, z)); }

	// ---- the island's edges ----
	// The island's sides are straight cuts, hundreds of blocks tall. On its edge stands an invisible wall of barrier
	// blocks (the generator's "edges" setting), with the sea going on for ever past it. The edge, row by row (z): the x
	// range of the island's solid ground and buildings, floating ones too (the Citadel at the top stands on nothing); not
	// trees, planks, bars, roots or lava (a bridge or the volcano's sheet of lava over the sea is left out past the edge).

	/** source(): no island there, the open sea. */
	public static final long SEA = Long.MIN_VALUE;
	private int ez0, ez1 = -1;   // the rows with ground
	private int[] rowMin, rowMax;

	/** Solid rock, earth or building (what makes the island's outline, and its ground's height past the edge). */
	private static boolean outline(BlockState s) {
		if (!s.isSolidRender() || !s.getFluidState().isEmpty()) return false;
		String n = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
		return !(n.contains("log") || n.contains("wood") || n.contains("planks") || n.contains("leaves") || n.contains("roots") || n.contains("bars")
			|| n.contains("magma"));
	}

	private void findEdges() {
		int z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
		for (Area a : areas) { z0 = Math.min(z0, a.z0); z1 = Math.max(z1, a.z0 + a.d - 1); }
		if (z0 > z1) return;
		boolean[] counts = new boolean[palette.length];
		for (int i = 0; i < palette.length; i++) counts[i] = outline(palette[i]);
		int n = z1 - z0 + 1;
		int[] lo = new int[n], hi = new int[n];
		java.util.Arrays.fill(lo, Integer.MAX_VALUE);
		java.util.Arrays.fill(hi, Integer.MIN_VALUE);
		for (Area a : areas)
			for (int dz = 0; dz < a.d; dz++)
				for (int dx = 0; dx < a.w; dx++) {
					int c = dz * a.w + dx;
					for (int i = a.start[c]; i < a.start[c + 1]; i += 3)
						if (counts[a.runs[i + 2]]) {
							int r = a.z0 + dz - z0, x = a.x0 + dx;
							lo[r] = Math.min(lo[r], x);
							hi[r] = Math.max(hi[r], x);
							break;
						}
				}
		int first = 0, last = n - 1;
		while (first < n && lo[first] > hi[first]) first++;
		while (last > first && lo[last] > hi[last]) last--;
		if (first >= n) return;
		// a row with no ground between two with (a gap in the export) takes the row before's: not a wall across the island
		for (int r = first + 1; r < last; r++)
			if (lo[r] > hi[r]) { lo[r] = lo[r - 1]; hi[r] = hi[r - 1]; }
		ez0 = z0 + first;
		ez1 = z0 + last;
		rowMin = java.util.Arrays.copyOfRange(lo, first, last + 1);
		rowMax = java.util.Arrays.copyOfRange(hi, first, last + 1);
		MinePeak.LOG.info("MinePeak island {}: edges z {}..{}", name, ez0, ez1);
		long t0 = System.currentTimeMillis();
		planOutside();
		MinePeak.LOG.info("MinePeak island {}: its sides past the edges planned ({} ms)", name, System.currentTimeMillis() - t0);
	}

	// ---- past the edges ----
	// The island's sides are cut straight down, and its upper stages stand hundreds of blocks over the sea (PEAK hides
	// theirs in its haze, and so does MinePeak: the island biome's fog). Past its edge, under the sea, the ground goes
	// steeply down from the island's own ground (about 2.6 blocks down for each one out), never above the water. Worked out on a grid of 4 by 4 columns: each
	// cell of the island has the middle height of its ground (so a lone pillar, a thorn or a tree doesn't stand a cone of
	// rock past the edge), no higher than PEAK's invisible walls go (the Citadel stands over it); from there the grid's
	// cells past the edge take the highest of (a cell's height - the slope x the distance). Under the sea, where the
	// island's seabed is deeper than the open sea's floor, the floor rises gently (0.35 a block) to meet it.

	/** The ground past the edge at a column: its top's y, and its top and side blocks. */
	public record Outside(int height, BlockState top, BlockState side) { }

	private static final int CELL = 4, REACH = 420;
	private static final double SLOPE = 2.6, RISE = 0.35;
	private int gx0, gz0, gw, gd;
	private float[] skirt, floorUp;
	private int[] skirtFrom;
	private BlockState[] cellTop, cellSide;

	/** The highest solid ground at (x, z) (Integer.MIN_VALUE: none). */
	private int groundTop(int x, int z) {
		int[] best = { Integer.MIN_VALUE };
		forColumn(x, z, (t, len, s) -> { if (t > best[0] && outline(s) && natural(s)) best[0] = t; });
		return best[0];
	}

	private void planOutside() {
		int xLo = Integer.MAX_VALUE, xHi = Integer.MIN_VALUE;
		for (int r = 0; r < rowMin.length; r++) { xLo = Math.min(xLo, rowMin[r]); xHi = Math.max(xHi, rowMax[r]); }
		gx0 = Math.floorDiv(xLo - REACH, CELL); gz0 = Math.floorDiv(ez0 - REACH, CELL);
		gw = Math.floorDiv(xHi + REACH, CELL) - gx0 + 1; gd = Math.floorDiv(ez1 + REACH, CELL) - gz0 + 1;
		int n = gw * gd;
		skirt = new float[n]; floorUp = new float[n]; skirtFrom = new int[n];
		cellTop = new BlockState[n]; cellSide = new BlockState[n];
		java.util.Arrays.fill(skirt, Float.NEGATIVE_INFINITY);
		java.util.Arrays.fill(floorUp, Float.POSITIVE_INFINITY);
		java.util.Arrays.fill(skirtFrom, -1);
		int cap = Integer.MAX_VALUE;
		int wallsTop = Integer.MIN_VALUE;
		if (info.has("walls")) for (JsonElement e : info.getAsJsonArray("walls")) wallsTop = Math.max(wallsTop, e.getAsJsonObject().get("y1").getAsInt());
		if (wallsTop != Integer.MIN_VALUE) cap = wallsTop - 30;
		long[] tops = new long[CELL * CELL];
		int sea = IslandChunkGenerator.SEA_FLOOR;
		for (int cz = 0; cz < gd; cz++) {
			int z0 = (gz0 + cz) * CELL;
			if (z0 + CELL - 1 < ez0 || z0 > ez1) continue;
			for (int cx = 0; cx < gw; cx++) {
				int x0 = (gx0 + cx) * CELL, k = 0;
				for (int dz = 0; dz < CELL; dz++)
					for (int dx = 0; dx < CELL; dx++) {
						if (!inside(x0 + dx, z0 + dz)) continue;
						int t = groundTop(x0 + dx, z0 + dz);
						if (t != Integer.MIN_VALUE) tops[k++] = ((long) (t + 4096) << 8) | (dz * CELL + dx);
					}
				if (k < CELL * CELL / 4) continue;   // (a quarter of the cell at least)
				java.util.Arrays.sort(tops, 0, k);
				long mid = tops[k / 2];
				int t = (int) (mid >> 8) - 4096, x = x0 + (int) (mid & 0xFF) % CELL, z = z0 + (int) (mid & 0xFF) / CELL;
				int c = cz * gw + cx;
				skirt[c] = Math.min(t, cap);
				skirtFrom[c] = c;
				if (t < sea) floorUp[c] = t;
				BlockState top = blockAt(x, t, z), side = blockAt(x, t - 4, z);
				cellTop[c] = top.isSolidRender() ? top : Blocks.STONE.defaultBlockState();
				cellSide[c] = outline(side) ? side : cellTop[c];
			}
		}
		// two passes over the grid (a chamfer: each cell from its neighbours already done), the second back the other way
		float s1 = (float) (SLOPE * CELL), s2 = (float) (SLOPE * CELL * Math.sqrt(2)), r1 = (float) (RISE * CELL), r2 = (float) (RISE * CELL * Math.sqrt(2));
		int[][] fwd = { { -1, 0 }, { -1, -1 }, { 0, -1 }, { 1, -1 } }, back = { { 1, 0 }, { 1, 1 }, { 0, 1 }, { -1, 1 } };
		for (int pass = 0; pass < 2; pass++) {
			int[][] nbs = pass == 0 ? fwd : back;
			for (int i = 0; i < gd; i++) {
				int cz = pass == 0 ? i : gd - 1 - i;
				for (int j = 0; j < gw; j++) {
					int cx = pass == 0 ? j : gw - 1 - j, c = cz * gw + cx;
					for (int[] nb : nbs) {
						int nx = cx + nb[0], nz = cz + nb[1];
						if (nx < 0 || nz < 0 || nx >= gw || nz >= gd) continue;
						int o = nz * gw + nx;
						boolean diag = nb[0] != 0 && nb[1] != 0;
						float sk = skirt[o] - (diag ? s2 : s1);
						if (sk > skirt[c]) { skirt[c] = sk; skirtFrom[c] = skirtFrom[o]; }
						float fl = floorUp[o] + (diag ? r2 : r1);
						if (fl < floorUp[c]) floorUp[c] = fl;
					}
				}
			}
		}
	}

	/** The ground past the edge at (x, z) (outside the island), or null where it's just the sea floor. */
	public Outside outside(int x, int z) {
		if (skirt == null) return null;
		double fx = (x + 0.5) / CELL - gx0 - 0.5, fz = (z + 0.5) / CELL - gz0 - 0.5;   // (in cells, from cell centres)
		int ix = (int) Math.floor(fx), iz = (int) Math.floor(fz);
		if (ix < 0 || iz < 0 || ix + 1 >= gw || iz + 1 >= gd) return null;
		double tx = fx - ix, tz = fz - iz;
		int c00 = iz * gw + ix, c10 = c00 + 1, c01 = c00 + gw, c11 = c01 + 1;
		double s = lerp2(low(skirt[c00]), low(skirt[c10]), low(skirt[c01]), low(skirt[c11]), tx, tz);
		int sea = IslandChunkGenerator.SEA_FLOOR;
		double f = lerp2(Math.min(sea, floorUp[c00]), Math.min(sea, floorUp[c10]), Math.min(sea, floorUp[c01]), Math.min(sea, floorUp[c11]), tx, tz);
		int floor = (int) Math.floor(f);
		// (only under the sea: above it, beside the upper stages, it read as huge smooth walls; PEAK's haze hides their sides)
		double g = Math.min(IslandChunkGenerator.SEA_LEVEL - 4, s + (noise(x, z) - 0.5) * 8 + (noise(x * 3 + 7, z * 3 + 11) - 0.5) * 3);
		if (g <= floor) return floor >= sea ? null : new Outside(floor, Blocks.SAND.defaultBlockState(), Blocks.STONE.defaultBlockState());
		// the blocks of the cell its height comes from
		int best = c00;
		for (int c : new int[] { c10, c01, c11 }) if (skirt[c] > skirt[best]) best = c;
		int from = skirtFrom[best];
		if (from < 0 || cellTop[from] == null) return new Outside((int) Math.floor(g), Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState());
		return new Outside((int) Math.floor(g), cellTop[from], cellSide[from]);
	}

	private static double low(float v) { return v == Float.NEGATIVE_INFINITY ? -9999 : v; }

	private static double lerp2(double a, double b, double c, double d, double tx, double tz) {
		double ab = a + (b - a) * tx, cd = c + (d - c) * tx;
		return ab + (cd - ab) * tz;
	}

	private static double hash(int x, int z) {
		int h = x * 73856093 ^ z * 19349663 ^ 0x5ca1ab1e;
		h ^= h >>> 13; h *= 0x5bd1e995; h ^= h >>> 15;
		return (h & 0xFFFF) / 65536.0;
	}

	/** Smooth noise in 0..1 over 24-block cells. */
	private static double noise(int x, int z) {
		double gx = (x + 0.5) / 24, gz = (z + 0.5) / 24;
		int ix = (int) Math.floor(gx), iz = (int) Math.floor(gz);
		double fx = gx - ix, fz = gz - iz;
		fx = fx * fx * (3 - 2 * fx); fz = fz * fz * (3 - 2 * fz);
		double a = hash(ix, iz), b = hash(ix + 1, iz), c = hash(ix, iz + 1), d = hash(ix + 1, iz + 1);
		double ab = a + (b - a) * fx, cd = c + (d - c) * fx;
		return ab + (cd - ab) * fz;
	}

	/** Is (x, z) on the island (inside its edges)? Everywhere is, for an island with no edges found (the airport). */
	public boolean inside(int x, int z) {
		if (rowMin == null) return true;
		if (z < ez0 || z > ez1) return false;
		return x >= rowMin[z - ez0] && x <= rowMax[z - ez0];
	}

	/** The island's x range in row z ({min, max}), or null outside it (for the dev test's views of the edges). */
	public int[] row(int z) {
		return rowMin == null || z < ez0 || z > ez1 ? null : new int[] { rowMin[z - ez0], rowMax[z - ez0] };
	}

	/** On the edge: just outside the island, next to it. Here stands the invisible wall. */
	public boolean edge(int x, int z) {
		return rowMin != null && !inside(x, z) && (inside(x - 1, z) || inside(x + 1, z) || inside(x, z - 1) || inside(x, z + 1));
	}

	/** The column of the island to build at (x, z), packed by pack(): itself on the island, SEA past its edges. */
	public long source(int x, int z) {
		return inside(x, z) ? pack(x, z) : SEA;
	}

	public static long pack(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }
	public static int packedX(long p) { return (int) (p >> 32); }
	public static int packedZ(long p) { return (int) p; }

	/** The campfires on the way up, in order (only those in their own segment: some exports have one at 0, 0, 0). */
	public List<JsonObject> campfires() {
		List<JsonObject> fires = new ArrayList<>();
		for (JsonElement e : info.getAsJsonArray("areas")) {
			JsonObject a = e.getAsJsonObject();
			if (!a.has("campfire")) continue;
			JsonArray c = a.getAsJsonArray("campfire");
			int x = c.get(0).getAsInt(), z = c.get(2).getAsInt();
			int x0 = a.get("x0").getAsInt(), z0 = a.get("z0").getAsInt();
			if (x >= x0 && z >= z0 && x < x0 + a.get("width").getAsInt() && z < z0 + a.get("depth").getAsInt()) fires.add(a);
		}
		return fires;
	}

	private static Area readArea(Path file) throws IOException {
		try (InputStream raw = new BufferedInputStream(Files.newInputStream(file))) {
			byte[] head = raw.readNBytes(20);
			if (head.length < 20 || head[0] != 'M' || head[1] != 'P' || head[2] != 'K' || head[3] != '1')
				throw new IOException("not a MinePeak area file: " + file);
			ByteBuffer b = ByteBuffer.wrap(head, 4, 16).order(ByteOrder.LITTLE_ENDIAN);
			int x0 = b.getInt(), z0 = b.getInt(), w = b.getInt(), d = b.getInt();
			DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw), 1 << 16));
			int[] start = new int[w * d + 1];
			int[] runs = new int[Math.max(16, w * d * 6)];
			int n = 0;
			for (int c = 0; c < w * d; c++) {
				start[c] = n;
				int count = varint(in);
				for (int k = 0; k < count; k++) {
					if (n + 3 > runs.length) runs = java.util.Arrays.copyOf(runs, runs.length * 2);
					runs[n++] = varint(in);   // (top y - minY; made absolute by the reader below)
					runs[n++] = varint(in);
					runs[n++] = varint(in);
				}
			}
			start[w * d] = n;
			return new Area(x0, z0, w, d, start, java.util.Arrays.copyOf(runs, n));
		}
	}

	private static int varint(DataInputStream in) throws IOException {
		int r = 0, s = 0;
		while (true) {
			int b = in.readUnsignedByte();
			r |= (b & 0x7F) << s;
			if (b < 0x80) return r;
			s += 7;
		}
	}

	/** "minecraft:oak_leaves[persistent=true]" as a block state (air if unknown). */
	static BlockState parseState(String text) {
		String id = text, props = null;
		int br = text.indexOf('[');
		if (br >= 0) { id = text.substring(0, br); props = text.substring(br + 1, text.length() - 1); }
		var block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
		if (block == null) return Blocks.AIR.defaultBlockState();
		BlockState state = block.defaultBlockState();
		if (props != null)
			for (String kv : props.split(",")) {
				String[] p = kv.split("=");
				if (p.length != 2) continue;
				Property<?> prop = block.getStateDefinition().getProperty(p[0].trim());
				if (prop != null) state = with(state, prop, p[1].trim());
			}
		return state;
	}

	private static <T extends Comparable<T>> BlockState with(BlockState s, Property<T> p, String v) {
		return p.getValue(v).map(val -> s.setValue(p, val)).orElse(s);
	}

	/** Its campfires (x, y, z), each with open sky over it. */
	final List<int[]> fires = new ArrayList<>();
	/** Campfires buried in rock: per column (pack(x, z)) the lowest y cut away over it, its campfire's y, and its index. */
	private final Map<Long, int[]> notch = new HashMap<>();
	private final Map<Integer, BlockState> notchFloor = new HashMap<>();
	private static final double NOTCH_HALF = 4.5, NOTCH_SLOPE = 0.6;
	private static final int NOTCH_HIGH = 80;

	/**
	 * Over each campfire, open to the sky. PEAK only draws its rocks' outsides, and some overlap its campfires (from the
	 * campfire you see through them), while the export made them solid. Done here, after the areas are put together
	 * (they overlap, and one area's rocks would fill another's clearing back in).
	 * - A campfire walled in by rock all round (the Mesa's, in the edge of a plateau): a notch cut from it out to the
	 *   nearest ground at its level, 9 blocks wide at the bottom, its sides sloping out, its floor that ground's block.
	 * - Any other: 30 blocks of air over it, about 4 round.
	 */
	void clearCampfires(int x, int z, int minY, BlockState[] col) {
		int[] n = notch.get(pack(x, z));
		if (n != null) {
			int i0 = Math.max(0, n[0] - minY), i1 = Math.min(col.length - 1, n[1] + NOTCH_HIGH - minY);
			boolean cut = false;
			for (int i = i0; i <= i1; i++) if (!col[i].isAir()) { col[i] = Blocks.AIR.defaultBlockState(); cut = true; }
			int floor = n[1] - minY;
			if (cut && n[0] == n[1] + 1 && floor >= 0 && floor < col.length && natural(col[floor])) col[floor] = notchFloor.get(n[2]);
		}
		for (int k = 0; k < fires.size(); k++) {
			if (notchFloor.containsKey(k)) continue;
			int[] f = fires.get(k);
			double dx = x - f[0], dz = (z - f[2] - 1) * 0.8;
			if (dx * dx + dz * dz > 22) continue;
			// (the ground just over the campfire stays, up to 3 blocks: PEAK sinks some campfires into the snow)
			int from = f[1] + 1;
			while (from <= f[1] + 3 && from - minY >= 0 && from - minY < col.length && natural(col[from - minY])) from++;
			for (int y = from; y <= f[1] + 30; y++) {
				int i = y - minY;
				if (i >= 0 && i < col.length) col[i] = Blocks.AIR.defaultBlockState();
			}
		}
	}

	/**
	 * Where PEAK's campfire in column (x, z) stands, if one does: the open block on the ground nearest its height, from 8
	 * below to 6 above (PEAK's spot can be a few blocks off the ground under it), after clearCampfires; or Integer.MIN_VALUE.
	 */
	int fireY(int x, int z, int minY, BlockState[] col) {
		for (int[] f : fires) {
			if (f[0] != x || f[2] != z) continue;
			int best = Integer.MIN_VALUE;
			for (int y = f[1] - 8; y <= f[1] + 6; y++) {
				int i = y - minY;
				if (i > 0 && i < col.length && col[i].isAir() && !col[i - 1].isAir() && col[i - 1].getFluidState().isEmpty()
					&& (best == Integer.MIN_VALUE || Math.abs(y - f[1]) < Math.abs(best - f[1]))) best = y;
			}
			if (best != Integer.MIN_VALUE) return best;
		}
		return Integer.MIN_VALUE;
	}

	/** Rock or earth (not bricks, wood, metal or glass: a temple's walls round a campfire are meant to be there). */
	private static boolean natural(BlockState s) {
		if (s.isAir() || !s.getFluidState().isEmpty() || s.is(Blocks.BARRIER)) return false;
		String n = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
		return !(n.contains("brick") || n.contains("planks") || n.contains("log") || n.contains("wood") || n.contains("glass") || n.contains("iron")
			|| n.contains("bars") || n.contains("quartz") || n.contains("concrete") || n.contains("tile") || n.contains("leaves"));
	}

	/** The highest solid block of column (x, z) no higher than top, or Integer.MIN_VALUE. */
	private int surface(int x, int z, int top) {
		int[] best = { Integer.MIN_VALUE };
		forColumn(x, z, (t, len, s) -> {
			if (s.isAir() || !s.getFluidState().isEmpty()) return;
			int y = Math.min(t, top);
			if (y > t - len && y > best[0]) best[0] = y;
		});
		return best[0];
	}

	/** Finds the campfires walled in by rock, and the notch out for each (clearCampfires). */
	private void planNotches() {
		for (int k = 0; k < fires.size(); k++) {
			int fx = fires.get(k)[0], fy = fires.get(k)[1], fz = fires.get(k)[2];
			// walled in: at head height, rock all round (most of a ring 5 to 8 blocks out)
			int ring = 0, walled = 0;
			for (int a = 0; a < 32; a++)
				for (int r = 5; r <= 8; r += 3) {
					ring++;
					if (natural(blockAt(fx + (int) Math.round(Math.cos(a * Math.PI / 16) * r), fy + 3, fz + (int) Math.round(Math.sin(a * Math.PI / 16) * r)))) walled++;
				}
			if (walled < ring * 0.7) continue;
			// the way out: the nearest ground no more than 3 over the campfire's (and not far under it), 4 blocks of it in a row
			int bestA = -1, bestR = Integer.MAX_VALUE;
			for (int a = 0; a < 32; a++) {
				int open = 0;
				for (int r = 7; r <= 40 && r - open < bestR; r++) {   // (from 7: older exports have a shaft cut over their campfires)
					int x = fx + (int) Math.round(Math.cos(a * Math.PI / 16) * r), z = fz + (int) Math.round(Math.sin(a * Math.PI / 16) * r);
					if (!inside(x, z)) break;
					int s = surface(x, z, fy + NOTCH_HIGH);
					open = s <= fy + 3 && s >= fy - 8 ? open + 1 : 0;
					if (open == 4) { bestA = a; bestR = r - 3; break; }
				}
			}
			if (bestA < 0) { MinePeak.LOG.info("MinePeak: campfire {} {} {} is walled in, with no way out found", fx, fy, fz); continue; }
			double ax = fx + 0.5, az = fz + 0.5;
			double bx = ax + Math.cos(bestA * Math.PI / 16) * (bestR + 2), bz = az + Math.sin(bestA * Math.PI / 16) * (bestR + 2);
			// its floor: the open ground's top block (the campfire's own ground is the rock inside the plateau)
			int ex = fx + (int) Math.round(Math.cos(bestA * Math.PI / 16) * (bestR + 2)), ez = fz + (int) Math.round(Math.sin(bestA * Math.PI / 16) * (bestR + 2));
			BlockState open = blockAt(ex, surface(ex, ez, fy + NOTCH_HIGH), ez);
			notchFloor.put(k, natural(open) ? open : blockAt(fx, fy, fz));
			double far = NOTCH_HALF + NOTCH_SLOPE * NOTCH_HIGH;
			int cells = 0;
			for (int x = (int) Math.floor(Math.min(ax, bx) - far); x <= (int) Math.ceil(Math.max(ax, bx) + far); x++)
				for (int z = (int) Math.floor(Math.min(az, bz) - far); z <= (int) Math.ceil(Math.max(az, bz) + far); z++) {
					// distance from the column to the line from the campfire out
					double px = x + 0.5 - ax, pz = z + 0.5 - az, lx = bx - ax, lz = bz - az;
					double t = Math.max(0, Math.min(1, (px * lx + pz * lz) / (lx * lx + lz * lz)));
					double d = Math.hypot(px - t * lx, pz - t * lz);
					if (d > far) continue;
					int from = fy + 1 + (int) Math.ceil(Math.max(0, d - NOTCH_HALF) / NOTCH_SLOPE);
					int[] old = notch.get(pack(x, z));
					if (old == null || from < old[0]) { notch.put(pack(x, z), new int[] { from, fy, k }); cells++; }
				}
			MinePeak.LOG.info("MinePeak: campfire {} {} {} is walled in by rock: a notch out to the open {} blocks away ({} columns)", fx, fy, fz, bestR, cells);
		}
	}

	// ---- PEAK's invisible walls ----
	// PEAK keeps its scouts in a strip down the island between two invisible walls (Misc/EdgeWalls: boxes along both sides,
	// at x = +-141 to +-211 on today's island, and one across the sea behind the beach); all past them (the beach's far
	// ends, the swamp's outer rocks, the Mesa's outer cliffs) is scenery. MinePeak stands barriers round each wall's
	// footprint, as high as PEAK's walls go.

	/** Wall columns (pack(x, z)): {lowest y, highest y} of the barrier there. */
	private final Map<Long, int[]> wallColumns = new HashMap<>();

	private void planWalls() {
		if (!info.has("walls")) return;
		for (JsonElement e : info.getAsJsonArray("walls")) {
			JsonObject w = e.getAsJsonObject();
			JsonArray cs = w.getAsJsonArray("corners");
			double[] px = new double[cs.size()], pz = new double[cs.size()];
			double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
			for (int i = 0; i < cs.size(); i++) {
				px[i] = cs.get(i).getAsJsonArray().get(0).getAsDouble(); pz[i] = cs.get(i).getAsJsonArray().get(1).getAsDouble();
				minX = Math.min(minX, px[i]); maxX = Math.max(maxX, px[i]); minZ = Math.min(minZ, pz[i]); maxZ = Math.max(maxZ, pz[i]);
			}
			int y0 = w.get("y0").getAsInt(), y1 = w.get("y1").getAsInt(), n = 0;
			// the footprint's rim: columns inside it with a neighbour outside
			for (int x = (int) Math.floor(minX) - 1; x <= (int) Math.ceil(maxX) + 1; x++)
				for (int z = (int) Math.floor(minZ) - 1; z <= (int) Math.ceil(maxZ) + 1; z++) {
					if (!inPolygon(x + 0.5, z + 0.5, px, pz)) continue;
					if (inPolygon(x - 0.5, z + 0.5, px, pz) && inPolygon(x + 1.5, z + 0.5, px, pz) && inPolygon(x + 0.5, z - 0.5, px, pz) && inPolygon(x + 0.5, z + 1.5, px, pz)) continue;
					wallColumns.merge(pack(x, z), new int[] { y0, y1 }, (a, b) -> new int[] { Math.min(a[0], b[0]), Math.max(a[1], b[1]) });
					n++;
				}
			MinePeak.LOG.info("MinePeak: PEAK's wall {} ({}..{}, {}..{}, y {}..{}): barriers round it ({} columns)", w.get("path").getAsString(),
				(int) minX, (int) maxX, (int) minZ, (int) maxZ, y0, y1, n);
		}
	}

	private static boolean inPolygon(double x, double z, double[] px, double[] pz) {
		boolean in = false;
		for (int i = 0, j = px.length - 1; i < px.length; j = i++)
			if ((pz[i] > z) != (pz[j] > z) && x < (px[j] - px[i]) * (z - pz[i]) / (pz[j] - pz[i]) + px[i]) in = !in;
		return in;
	}

	/** The barrier's heights in column (x, z) if one of PEAK's walls stands there ({lowest y, highest y}), or null. */
	public int[] wall(int x, int z) { return wallColumns.get(pack(x, z)); }

	/** Does the island have water in it (the swamp's film, pools)? */
	boolean hasWater;

	/** Would water at (x, y, z) pour out sideways: an open block beside it with nothing under that? (Past the edge stands the barrier.) */
	boolean spills(int x, int y, int z) {
		for (int k = 0; k < 4; k++) {
			int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = z + (k == 2 ? 1 : k == 3 ? -1 : 0);
			if (!inside(nx, nz)) continue;
			if (blockAt(nx, y, nz).isAir() && blockAt(nx, y - 1, nz).isAir()) return true;
		}
		return false;
	}

	/** Does the island have iron bars, panes or fences (to join to their neighbours as it's built)? */
	boolean hasCross;

	/** The island's block at (x, y, z), from its export (air where it has none). */
	BlockState blockAt(int x, int y, int z) {
		BlockState[] found = { Blocks.AIR.defaultBlockState() };
		forColumn(x, z, (top, len, state) -> { if (y <= top && y > top - len && !state.isAir()) found[0] = state; });
		return found[0];
	}

	/** Runs every area holds for this column (x, z), as calls (top y, length, state). */
	public void forColumn(int x, int z, RunConsumer out) {
		for (Area a : areas) {
			if (!a.contains(x, z)) continue;
			int c = (z - a.z0) * a.w + (x - a.x0);
			for (int i = a.start[c]; i < a.start[c + 1]; i += 3)
				out.run(a.runs[i] + minY, a.runs[i + 1], palette[a.runs[i + 2]]);
		}
	}

	@FunctionalInterface
	public interface RunConsumer { void run(int topY, int length, BlockState state); }
}
