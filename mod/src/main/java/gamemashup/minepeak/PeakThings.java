package gamemashup.minepeak;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * PEAK's luggage and creatures on the island (exported with each area: "luggage" and "creatures"), by the chunk
 * they're in, for the world's generation: the luggage as chests with PEAK's loot (PeakThings.luggage), the creatures as
 * Minecraft's mobs made over (PeakCreatures).
 */
final class PeakThings {
	private PeakThings() { }

	/** A suitcase: where, its kind (PEAK's class/object, e.g. "Luggage/LuggageAncient"), which way it faces (PEAK's yaw). */
	record Bag(int x, int y, int z, String kind, float yaw, String pool, int count) { }

	/** A creature or spawner: its kind (PEAK's class), where, which way. */
	record Creature(String kind, int x, int y, int z, float yaw) { }

	private static final Map<IslandData, Map<Long, List<Bag>>> BAGS = new java.util.WeakHashMap<>();
	private static final Map<IslandData, Map<Long, List<Creature>>> CREATURES = new java.util.WeakHashMap<>();

	static synchronized List<Bag> bags(IslandData data, ChunkPos chunk) {
		return BAGS.computeIfAbsent(data, PeakThings::readBags).getOrDefault(chunk.pack(), List.of());
	}

	static synchronized List<Creature> creatures(IslandData data, ChunkPos chunk) {
		return CREATURES.computeIfAbsent(data, PeakThings::readCreatures).getOrDefault(chunk.pack(), List.of());
	}

	private static Map<Long, List<Bag>> readBags(IslandData d) {
		Map<Long, List<Bag>> m = new HashMap<>();
		Set<Long> seen = new HashSet<>();   // (areas overlap: the same suitcase in two)
		for (JsonElement ae : d.info.getAsJsonArray("areas")) {
			JsonObject a = ae.getAsJsonObject();
			if (!a.has("luggage")) continue;
			for (JsonElement e : a.getAsJsonArray("luggage")) {
				JsonArray at;
				String kind = "Luggage/Luggage", pool = "None";
				float yaw = 0;
				int count = 2;
				if (e.isJsonArray()) at = e.getAsJsonArray();
				else {
					JsonObject o = e.getAsJsonObject();
					at = o.getAsJsonArray("at");
					if (o.has("kind")) kind = o.get("kind").getAsString();
					if (o.has("yaw")) yaw = o.get("yaw").getAsFloat();
					if (o.has("pool")) pool = o.get("pool").getAsString();
					if (o.has("count")) count = o.get("count").getAsInt();
				}
				int x = at.get(0).getAsInt(), y = at.get(1).getAsInt(), z = at.get(2).getAsInt();
				if (!seen.add(BlockPos.asLong(x, y, z))) continue;
				m.computeIfAbsent(ChunkPos.pack(x >> 4, z >> 4), k -> new ArrayList<>()).add(new Bag(x, y, z, kind, yaw, pool, count));
			}
		}
		return m;
	}

	private static Map<Long, List<Creature>> readCreatures(IslandData d) {
		Map<Long, List<Creature>> m = new HashMap<>();
		Set<Long> seen = new HashSet<>();
		for (JsonElement ae : d.info.getAsJsonArray("areas")) {
			JsonObject a = ae.getAsJsonObject();
			if (!a.has("creatures")) continue;
			for (JsonElement e : a.getAsJsonArray("creatures")) {
				JsonObject o = e.getAsJsonObject();
				JsonArray at = o.getAsJsonArray("at");
				int x = at.get(0).getAsInt(), y = at.get(1).getAsInt(), z = at.get(2).getAsInt();
				if (!seen.add(BlockPos.asLong(x, y, z) ^ o.get("kind").getAsString().hashCode())) continue;
				m.computeIfAbsent(ChunkPos.pack(x >> 4, z >> 4), k -> new ArrayList<>())
					.add(new Creature(o.get("kind").getAsString(), x, y, z, o.has("yaw") ? o.get("yaw").getAsFloat() : 0));
			}
		}
		return m;
	}

	/** Which way PEAK's yaw faces in Minecraft (PEAK's +z is Minecraft's -z). */
	static Direction facing(float yaw) {
		int q = Math.floorMod(Math.round(yaw / 90f), 4);
		return switch (q) { case 0 -> Direction.NORTH; case 1 -> Direction.EAST; case 2 -> Direction.SOUTH; default -> Direction.WEST; };
	}

	/** Where something of PEAK's stands: the first open block at its height or a little above, on something solid; or null. */
	static BlockPos stand(WorldGenLevel level, int x, int y, int z) {
		for (int dy = -2; dy <= 3; dy++) {
			BlockPos p = new BlockPos(x, y + dy, z);
			BlockState s = level.getBlockState(p), under = level.getBlockState(p.below());
			if ((s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty()) && under.isFaceSturdy(level, p.below(), Direction.UP)) return p;
		}
		return null;
	}

	/** Which stage z is in (Stages: from its start up to the next one's), as StageBiomeSource names them. */
	static String stage(IslandData data, int z) {
		String here = "shore";
		for (Stages.Stage s : Stages.of(data)) if (z <= s.z()) here = StageBiomeSource.stage(s.title(), s.biome()); else break;
		return here;
	}

	/**
	 * The chunk's suitcases, as chests (facing PEAK's way) that PEAK's loot pops out of when they're opened, drawn then
	 * (so it's new each run: PeakStatue.open): from the suitcase's own pool (the clowns', the climbers', the cursed one's)
	 * or its stage's, as many things as it has places for. The chest keeps which pool and how many (as a loot table's
	 * name: minepeak:luggage/pool/count). PEAK's scout statues (RespawnChest) are statues instead (PeakStatue).
	 */
	static void luggage(WorldGenLevel level, ChunkAccess chunk, IslandData data) {
		for (Bag b : bags(data, chunk.getPos())) {
			BlockPos p = stand(level, b.x(), b.y(), b.z());
			if (p == null) continue;
			if (b.kind().startsWith("RespawnChest")) { PeakStatue.place(level, p, facing(b.yaw())); continue; }
			level.setBlock(p, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing(b.yaw())), 2);
			if (!(level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest)) continue;
			String pool = b.pool().equals("None") || b.pool().isEmpty() ? PeakLoot.poolFor(stage(data, b.z())) : b.pool();
			chest.setLootTable(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE,
				MinePeak.id("luggage/" + pool.toLowerCase(java.util.Locale.ROOT) + "/" + Math.max(1, b.count()))));
		}
	}
}
