package gamemashup.minepeak;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * What grows and lies about on PEAK's island (its spawners, exported with each area): berries on the bushes and vines,
 * coconuts in the palms, mushrooms in their clusters, eggs in nests, winterberries, marshmallows and hot dogs by the
 * campfires, guidebook pages, the backpacks, bugles, lanterns and the like PEAK leaves about, and the flares at the
 * top. Each spawner as PEAK's does it (Spawner, BerryBush, BerryVine, GroundPlaceSpawner, SingleItemSpawner): its pool or
 * item, how many (a bush: one kind of berry, a few of it), at its spots; drawn as the world is made, so each world (each
 * run) has its own. They stay where PEAK puts them (hanging on the bush, in the tree), till a scout picks them up.
 */
final class PeakForage {
	private PeakForage() { }

	record Spot(double x, double y, double z) { }
	record One(String kind, String mode, String pool, String item, float chance, float min, float max, List<Spot> spots, JsonArray list) { }

	private static final Map<IslandData, Map<Long, List<One>>> BY_CHUNK = new java.util.WeakHashMap<>();

	private static synchronized List<One> in(IslandData data, ChunkPos cp) {
		return BY_CHUNK.computeIfAbsent(data, PeakForage::read).getOrDefault(cp.pack(), List.of());
	}

	private static Map<Long, List<One>> read(IslandData d) {
		Map<Long, List<One>> m = new HashMap<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (JsonElement ae : d.info.getAsJsonArray("areas")) {
			for (JsonElement se : PeakItemData.array(ae.getAsJsonObject(), "spawners")) {
				JsonObject s = se.getAsJsonObject();
				var at = s.getAsJsonArray("at");
				if (!seen.add(s.get("kind").getAsString() + at)) continue;
				List<Spot> spots = new ArrayList<>();
				var sp = PeakItemData.array(s, "spots");
				if (sp.size() == 3 && sp.get(0).isJsonPrimitive()) spots.add(new Spot(sp.get(0).getAsDouble(), sp.get(1).getAsDouble(), sp.get(2).getAsDouble()));   // (one spot, saved flat)
				else for (JsonElement p : sp) if (p.isJsonArray()) { var a = p.getAsJsonArray(); spots.add(new Spot(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble())); }
				if (spots.isEmpty()) spots.add(new Spot(at.get(0).getAsDouble(), at.get(1).getAsDouble(), at.get(2).getAsDouble()));
				var count = PeakItemData.array(s, "count");
				One o = new One(s.get("kind").getAsString(), str(s, "mode"), str(s, "pool"), str(s, "item"), s.has("chance") ? s.get("chance").getAsFloat() : 1f,
					count.size() == 2 ? count.get(0).getAsFloat() : -1, count.size() == 2 ? count.get(1).getAsFloat() : -1, spots, PeakItemData.array(s, "list"));
				// (filed under every chunk one of its spots is in: each chunk puts down only its own spots, World generation
				// can't reach past it, and the draw is the spawner's own, so the chunks agree)
				java.util.Set<Long> keys = new java.util.HashSet<>();
				for (Spot sp2 : spots) keys.add(ChunkPos.pack(((int) Math.floor(sp2.x)) >> 4, ((int) Math.floor(sp2.z)) >> 4));
				for (long key : keys) m.computeIfAbsent(key, k -> new ArrayList<>()).add(o);
			}
		}
		return m;
	}

	private static String str(JsonObject o, String k) { return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : ""; }

	static void spawn(WorldGenRegion region, IslandData data) {
		ChunkPos cp = region.getCenter();
		boolean flares = PeakAscents.flareAtPeak(region.getLevel().getServer());
		for (One o : in(data, cp)) {
			RandomSource r = RandomSource.create(region.getSeed() ^ (long) o.spots.get(0).x * 7919L ^ (long) o.spots.get(0).z * 6151L ^ (long) o.spots.get(0).y * 31L);
			if (o.kind.startsWith("EndgameFlareSpawner") && !flares) continue;
			if (o.chance < 1 && r.nextFloat() > o.chance) continue;
			List<ItemStack> things = new ArrayList<>();
			List<Spot> where = new ArrayList<>(o.spots);
			java.util.Collections.shuffle(where, new java.util.Random(r.nextLong()));
			if (o.mode.equals("SingleItem") && !o.item.isEmpty()) {
				for (int i = 0; i < where.size(); i++) things.add(PeakItems.stack(o.item));
			} else if (o.kind.startsWith("BerryBush") || o.kind.startsWith("BerryVine")) {
				// one kind, a few of it (PEAK: round(lerp(min, max, rand)))
				int n = o.min >= 0 ? Math.round(o.min + (o.max - o.min) * r.nextFloat()) : 1;
				List<ItemStack> one = pick(data, o, 1, r);
				if (!one.isEmpty()) for (int i = 0; i < Math.min(n, where.size()); i++) things.add(one.get(0).copy());
			} else {
				int n = o.min >= 0 ? Math.round(o.min + (o.max - o.min) * r.nextFloat()) : where.size();
				things.addAll(pick(data, o, Math.min(n, where.size()), r));
			}
			for (int i = 0; i < things.size() && i < where.size(); i++) {
				ItemStack st = things.get(i);
				if (st.isEmpty()) continue;
				Spot s = where.get(i);
				if (((int) Math.floor(s.x)) >> 4 != cp.x() || ((int) Math.floor(s.z)) >> 4 != cp.z()) continue;   // (another chunk's)
				ItemEntity e = new ItemEntity(region.getLevel(), s.x, s.y, s.z, st);
				e.setDeltaMovement(Vec3.ZERO);
				e.setNoGravity(true);
				e.setUnlimitedLifetime();
				e.addTag("minepeak.forage");
				region.addFreshEntity(e);
			}
		}
	}

	/**
	 * The island's fixtures that go into blocks as the world is made, by the chunk they're in (worked out once per island,
	 * not looked for among all of the island's hazards in every chunk): the vines' and rope bridges' blocks along their
	 * curves, the flash plants, the rusty pitons with the way out of their rock.
	 */
	private record Mark(net.minecraft.core.BlockPos pos, int kind, net.minecraft.core.Direction out) { }

	private static final int VINE = 0, BRIDGE = 1, FLASH_PLANT = 2, RUSTY_PITON = 3;
	private static final Map<IslandData, Map<Long, List<Mark>>> MARKS = new java.util.WeakHashMap<>();

	private static synchronized List<Mark> marks(IslandData data, ChunkPos cp) {
		return MARKS.computeIfAbsent(data, PeakForage::readMarks).getOrDefault(cp.pack(), List.of());
	}

	private static Map<Long, List<Mark>> readMarks(IslandData data) {
		Map<Long, List<Mark>> m = new HashMap<>();
		java.util.Set<Long> seen = new java.util.HashSet<>();
		for (JsonElement ae : data.info.getAsJsonArray("areas"))
			for (JsonElement he : PeakItemData.array(ae.getAsJsonObject(), "hazards")) {
				JsonObject h = he.getAsJsonObject();
				String kind = h.has("kind") ? h.get("kind").getAsString() : "";
				if (kind.equals("JungleVine") && h.has("points")) {
					var pts = h.getAsJsonArray("points");
					if (pts.size() < 2) continue;
					boolean bridge = h.has("bridge") && h.get("bridge").getAsBoolean();
					for (int i = 1; i < pts.size(); i++) {
						var a = pts.get(i - 1).getAsJsonArray(); var b = pts.get(i).getAsJsonArray();
						Vec3 from = new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()), to = new Vec3(b.get(0).getAsDouble(), b.get(1).getAsDouble(), b.get(2).getAsDouble());
						int steps = Math.max(1, (int) Math.ceil(from.distanceTo(to) * 3));
						for (int k = 0; k <= steps; k++) {
							Vec3 v = from.lerp(to, k / (double) steps);
							var q = net.minecraft.core.BlockPos.containing(v.x, bridge ? v.y - 0.5 : v.y, v.z);
							if (seen.add(q.asLong() * 4 + (bridge ? BRIDGE : VINE))) add(m, new Mark(q, bridge ? BRIDGE : VINE, null));
						}
					}
				} else if (kind.equals("AOE") && h.has("path") && h.get("path").getAsString().contains("FlashPlant")) {
					var at = h.getAsJsonArray("at");
					add(m, new Mark(net.minecraft.core.BlockPos.containing(at.get(0).getAsDouble(), at.get(1).getAsDouble(), at.get(2).getAsDouble()), FLASH_PLANT, null));
				} else if (kind.equals("ShittyPiton")) {
					var at = h.getAsJsonArray("at");
					var kids = h.has("children") ? h.getAsJsonObject("children") : new JsonObject();
					net.minecraft.core.Direction out = net.minecraft.core.Direction.NORTH;
					if (kids.has("ClimbingSpike")) {
						var sp = kids.getAsJsonArray("ClimbingSpike");
						out = net.minecraft.core.Direction.getApproximateNearest(at.get(0).getAsDouble() - sp.get(0).getAsDouble(), 0, at.get(2).getAsDouble() - sp.get(2).getAsDouble());
					}
					add(m, new Mark(net.minecraft.core.BlockPos.containing(at.get(0).getAsDouble(), at.get(1).getAsDouble(), at.get(2).getAsDouble()), RUSTY_PITON, out));
				}
			}
		return m;
	}

	private static void add(Map<Long, List<Mark>> m, Mark k) {
		m.computeIfAbsent(ChunkPos.pack(k.pos.getX() >> 4, k.pos.getZ() >> 4), c -> new ArrayList<>()).add(k);
	}

	/**
	 * The island's own fixtures, as the world is made: the Alpine's rusty pitons (PEAK's ShittyPiton), each where PEAK
	 * hammered it, sticking out of its rock (its spike's in the wall).
	 */
	static void fixtures(net.minecraft.world.level.WorldGenLevel level, net.minecraft.world.level.chunk.ChunkAccess chunk, IslandData data) {
		for (Mark k : marks(data, chunk.getPos())) {
			if (k.kind != RUSTY_PITON) continue;
			var pos = k.pos;
			var out = k.out;
			// out of the rock: the first open block along it, with rock behind
			for (int i = 0; i < 3 && !level.getBlockState(pos).canBeReplaced(); i++) pos = pos.relative(out);
			if (!level.getBlockState(pos).canBeReplaced()) continue;
			if (level.getBlockState(pos.relative(out.getOpposite())).canBeReplaced())
				for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) if (!level.getBlockState(pos.relative(d)).canBeReplaced()) { out = d.getOpposite(); break; }
			level.setBlock(pos, PeakGearBlocks.RUSTY_PITON.defaultBlockState().setValue(PeakGearBlocks.PitonBlock.FACING, out), 2);
		}
	}

	/**
	 * PEAK's vines (JungleVine: the climbable ones in the Roots, the Tropics and the Caldera, each a curve PEAK built
	 * between two ledges: its points, exported) as vine you can climb, and its rope bridges as a walkway of planks along
	 * theirs; and the Alpine's flash plants (PEAK's FlashPlant: it flashes when you come close, and blinds you), a
	 * torchflower each. Only the blocks in this chunk (the rest come with theirs).
	 */
	static void vines(net.minecraft.world.level.WorldGenLevel level, net.minecraft.world.level.chunk.ChunkAccess chunk, IslandData data) {
		var vine = PeakGearBlocks.VINE_ROPE.defaultBlockState();
		var plank = net.minecraft.world.level.block.Blocks.SPRUCE_SLAB.defaultBlockState();
		for (Mark k : marks(data, chunk.getPos())) {
			switch (k.kind) {
				case VINE, BRIDGE -> {
					if (level.getBlockState(k.pos).canBeReplaced() && level.getFluidState(k.pos).isEmpty()) level.setBlock(k.pos, k.kind == BRIDGE ? plank : vine, 2);
				}
				case FLASH_PLANT -> {
					for (int dy = -2; dy <= 2; dy++) {
						var s = k.pos.above(dy);
						if (level.getBlockState(s).canBeReplaced() && !level.getBlockState(s.below()).canBeReplaced()) { level.setBlock(s, net.minecraft.world.level.block.Blocks.TORCHFLOWER.defaultBlockState(), 2); break; }
					}
				}
				default -> { }
			}
		}
	}

	private static List<ItemStack> pick(IslandData data, One o, int n, RandomSource r) {
		if (!o.pool.isEmpty() && !o.pool.equals("None")) return PeakLoot.draw(data, o.pool, n, r);
		// the spawner's own list (prefab and weight)
		List<String> names = new ArrayList<>();
		List<Integer> weights = new ArrayList<>();
		for (JsonElement e : o.list) {
			if (!e.isJsonObject()) continue;
			JsonObject j = e.getAsJsonObject();
			String prefab = null;
			for (var kv : j.entrySet()) if (kv.getValue().isJsonPrimitive() && kv.getValue().getAsString().startsWith("@")) { prefab = kv.getValue().getAsString().substring(1); break; }
			int w = 1;
			for (var kv : j.entrySet()) if (kv.getKey().toLowerCase().contains("weight") && kv.getValue().isJsonPrimitive()) w = Math.max(1, kv.getValue().getAsInt());
			if (prefab != null) { names.add(prefab); weights.add(w); }
		}
		List<ItemStack> out = new ArrayList<>();
		if (names.isEmpty()) return out;
		int total = weights.stream().mapToInt(Integer::intValue).sum();
		for (int i = 0; i < n; i++) {
			int x = r.nextInt(total);
			for (int j = 0; j < names.size(); j++) { x -= weights.get(j); if (x < 0) { ItemStack st = PeakItems.stack(names.get(j)); if (!st.isEmpty()) out.add(st); break; } }
		}
		return out;
	}
}
