package gamemashup.minepeak;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * PEAK's scout badges, as Minecraft advancements (data/minepeak/advancement/badges: their own tab, PEAK's items for
 * icons; the pause menu's ACCOLADES button opens them). Earned as in PEAK: getting past each stage, getting to the top
 * (alone, without falls, without packaged food, without passing out, within an hour, at an Ascent), opening suitcases,
 * putting down rope and pitons, eating berries, mushrooms and cooked food, playing a bugle to a capybara, bringing a
 * friend round, and so on.
 */
final class PeakBadges {
	private PeakBadges() { }

	/** The world stopping: what each scout's done so far is forgotten (the next world starts it again). */
	static void stop() { HOT_DOGS.clear(); STAGE.clear(); GROUND.clear(); }

	static void award(ServerPlayer p, String key) {
		MinecraftServer server = p.level().getServer();
		var root = server.getAdvancements().get(MinePeak.id("badges/root"));
		if (root != null) p.getAdvancements().award(root, "earned");
		var adv = server.getAdvancements().get(MinePeak.id("badges/" + key.toLowerCase(java.util.Locale.ROOT)));
		if (adv != null) p.getAdvancements().award(adv, "earned");
	}

	private static final Map<UUID, List<Long>> HOT_DOGS = new HashMap<>();

	/** After eating: the badges for what (and how much) the scout has eaten. */
	static void ate(ServerPlayer p, PeakItemData.Def def, int cooked) {
		var sc = PeakStatus.of(p);
		if (sc.berries.size() >= 5) award(p, "Foraging");
		if (sc.mushrooms.size() >= 4) award(p, "Mycology");
		if (sc.cooked >= 20) award(p, "Cooking");
		if (def.item().startsWith("Glizzy")) {
			long now = p.level().getServer().getTickCount();
			List<Long> l = HOT_DOGS.computeIfAbsent(p.getUUID(), u -> new ArrayList<>());
			l.add(now);
			l.removeIf(t -> now - t > 100);
			if (l.size() >= 3) award(p, "CompetitiveEating");
		}
	}

	/** Each stage's furthest scout so far, its stage on entering, and the most it's suffered in it. */
	private static final Map<UUID, String> STAGE = new HashMap<>();
	private static final Map<UUID, Double> GROUND = new HashMap<>();

	static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) return;
		IslandData data = MinePeakServer.island(server);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!PeakWorld.scout(p) || data == null) continue;
			String stage = PeakThings.stage(data, p.getBlockZ());
			String was = STAGE.put(p.getUUID(), stage);
			var sc = PeakStatus.of(p);
			if (was != null && !was.equals(stage) && order(stage) > order(was)) {
				switch (was) {
					case "shore" -> award(p, "Beachcomber");
					case "tropics" -> award(p, "Trailblazer");
					case "roots" -> { award(p, "Forestry"); if (sc.maxSpores <= 0.25f) award(p, "TreadLightly"); }
					case "alpine" -> { award(p, "Alpinist"); if (sc.maxCold <= 0.2f) award(p, "BundledUp"); }
					case "mesa" -> { award(p, "Nomad"); if (sc.maxHeat <= 0.1f) award(p, "CoolCucumber"); }
					case "caldera" -> award(p, "Volcanology");
					default -> { }
				}
				sc.maxCold = 0; sc.maxHeat = 0; sc.maxSpores = 0;
			}
			// a long climb without touching the ground
			if (p.onGround() || !PeakClimb.onWall(p) && p.getDeltaMovement().y > 1.5) GROUND.put(p.getUUID(), p.getY());   // (a teleport or a launch isn't a climb)
			else if (PeakClimb.onWall(p) && p.getY() - GROUND.getOrDefault(p.getUUID(), p.getY()) >= 50) award(p, "Endurance");
			// something mystical in the pockets
			for (int i = 0; i < 9; i++) { var d = PeakItems.def(p.getInventory().getItem(i)); if (d != null && d.tagged("Mystical")) { award(p, "Esoterica"); break; } }
			if (sc.revives >= 3) award(p, "Clutch");
		}
	}

	static int order(String stage) {
		return switch (stage) { case "shore" -> 0; case "tropics", "roots" -> 1; case "alpine", "mesa" -> 2; case "caldera" -> 3; case "kiln" -> 4; case "peak" -> 5; default -> 0; };
	}
}
