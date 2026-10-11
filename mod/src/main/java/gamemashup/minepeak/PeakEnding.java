package gamemashup.minepeak;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * PEAK's ending (PeakSequence). A lit flare at the peak calls the rescue (not from Ascent 8): after 5 s it comes (a happy
 * ghast, as the helicopter) and lowers a rope; then PEAK's count of 30 (1.5 s each; down to 5 when every scout still up
 * is at the top or on the rope). At its end, whoever's at the top or on the rope wins, and every scout gets PEAK's end
 * screen: the run's time, each scout's height, and the badges the run earned.
 */
public final class PeakEnding {
	private PeakEnding() { }

	public record EndRun(boolean won, int seconds, List<String> names, List<Integer> heights, List<Boolean> winners, List<String> badges) implements CustomPacketPayload {
		public static final Type<EndRun> TYPE = new Type<>(MinePeak.id("end_run"));
		public static final StreamCodec<FriendlyByteBuf, EndRun> CODEC = StreamCodec.of((b, e) -> {
			b.writeBoolean(e.won); b.writeVarInt(e.seconds);
			b.writeVarInt(e.names.size());
			for (int i = 0; i < e.names.size(); i++) { b.writeUtf(e.names.get(i)); b.writeVarInt(e.heights.get(i)); b.writeBoolean(e.winners.get(i)); }
			b.writeVarInt(e.badges.size());
			for (String s : e.badges) b.writeUtf(s);
		}, b -> {
			boolean won = b.readBoolean(); int secs = b.readVarInt();
			int n = b.readVarInt();
			List<String> names = new ArrayList<>(); List<Integer> hs = new ArrayList<>(); List<Boolean> ws = new ArrayList<>();
			for (int i = 0; i < n; i++) { names.add(b.readUtf()); hs.add(b.readVarInt()); ws.add(b.readBoolean()); }
			int m = b.readVarInt();
			List<String> badges = new ArrayList<>();
			for (int i = 0; i < m; i++) badges.add(b.readUtf());
			return new EndRun(won, secs, names, hs, ws, badges);
		});
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	static void initCommon() {
		PayloadTypeRegistry.clientboundPlay().register(EndRun.TYPE, EndRun.CODEC);
	}

	/** The top: where PEAK's end flares wait (EndgameFlareSpawner), or the last stage's mark. */
	static Vec3 summit(IslandData data) {
		double x = 0, y = 0, z = 0;
		int n = 0;
		for (JsonElement ae : data.info.getAsJsonArray("areas")) {
			JsonObject a = ae.getAsJsonObject();
			for (JsonElement se : PeakItemData.array(a, "spawners")) {
				JsonObject s = se.getAsJsonObject();
				if (!s.get("kind").getAsString().startsWith("EndgameFlareSpawner")) continue;
				var at = s.getAsJsonArray("at");
				x += at.get(0).getAsDouble(); y += at.get(1).getAsDouble(); z += at.get(2).getAsDouble(); n++;
			}
		}
		if (n > 0) return new Vec3(x / n, y / n, z / n);
		var stages = Stages.of(data);
		if (!stages.isEmpty() && data.info.has("progress")) {
			for (JsonElement e : data.info.getAsJsonArray("progress")) {
				var at = e.getAsJsonObject().getAsJsonArray("at");
				if (at.get(2).getAsInt() == stages.get(stages.size() - 1).z()) return new Vec3(at.get(0).getAsDouble(), at.get(1).getAsDouble(), at.get(2).getAsDouble());
			}
		}
		return null;
	}

	static boolean atPeak(ServerPlayer p) {
		IslandData data = MinePeakServer.island(p.level().getServer());
		Vec3 top = data != null ? summit(data) : null;
		return top != null && Math.abs(p.getX() - top.x) < 40 && Math.abs(p.getZ() - top.z) < 40 && p.getY() > top.y - 20;
	}

	// ---- the rescue ----

	private static long calledAt = -1;
	private static int count;
	private static Mob rescue;
	private static final List<BlockPos> ROPE = new ArrayList<>();

	/** The world stopping: a rescue on its way is called off (its ghast and rope taken away). */
	static void stop(MinecraftServer server) {
		ServerLevel level = server.overworld();
		for (BlockPos b : ROPE) if (level.getBlockState(b).is(PeakGearBlocks.ROPE)) level.setBlock(b, Blocks.AIR.defaultBlockState(), 3);
		ROPE.clear();
		if (rescue != null) { rescue.discard(); rescue = null; }
		calledAt = -1; count = 0;
	}

	/** A flare lit: at the top, the rescue comes. */
	static void flare(ServerPlayer p) {
		if (!atPeak(p) || calledAt >= 0) return;
		MinecraftServer server = p.level().getServer();
		if (!PeakAscents.rescue(server)) { p.sendSystemMessage(Component.literal("The flare goes up... and nobody comes. (Ascent 8)")); return; }
		calledAt = server.getTickCount();
		count = 30;
		for (ServerPlayer o : server.getPlayerList().getPlayers()) o.sendSystemMessage(Component.literal(p.getName().getString() + " fired a flare from the top. The rescue is coming!"));
	}

	static void tick(MinecraftServer server) {
		if (calledAt < 0) return;
		long t = server.getTickCount() - calledAt;
		ServerLevel level = server.overworld();
		IslandData data = MinePeakServer.island(server);
		Vec3 top = data != null ? summit(data) : null;
		if (top == null) { calledAt = -1; return; }
		if (t == 100) arrive(level, top);
		if (t < 100 || (t - 100) % 30 != 0) return;
		// the count: 30 of PEAK's 1.5 s, down to 5 if everyone still up is there
		List<ServerPlayer> up = new ArrayList<>(), there = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!PeakWorld.scout(p) || PeakStatus.out(p)) continue;
			up.add(p);
			if (atPeak(p) || onRope(p)) there.add(p);
		}
		if (there.isEmpty()) count = 30;
		else if (there.size() == up.size()) count = Math.min(count, 5);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) p.sendOverlayMessage(Component.literal("THE RESCUE LEAVES IN " + count));
		if (--count < 0) end(server, there);
	}

	/** (The dev test: the count's end now.) */
	static void debugHurry() { count = 0; }

	private static boolean onRope(ServerPlayer p) {
		for (BlockPos b : ROPE) if (p.blockPosition().closerThan(b, 1.5)) return true;
		return false;
	}

	private static void arrive(ServerLevel level, Vec3 top) {
		BlockPos ground = BlockPos.containing(top.x, top.y + 2, top.z);
		BlockPos at = ground.above(18);
		var ghast = EntityTypes.HAPPY_GHAST.create(level, EntitySpawnReason.TRIGGERED);
		if (ghast != null) {
			ghast.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
			ghast.setNoAi(true);
			ghast.setInvulnerable(true);
			ghast.setPersistenceRequired();
			ghast.setCustomName(Component.literal("Rescue"));
			level.addFreshEntity(ghast);
			rescue = ghast;
		}
		ROPE.clear();
		for (BlockPos q = at.below(); q.getY() > ground.getY() - 4 && level.getBlockState(q).canBeReplaced(); q = q.below()) {
			level.setBlock(q, PeakGearBlocks.ROPE.defaultBlockState(), 3);
			ROPE.add(q);
		}
		level.playSound(null, at, SoundEvents.HAPPY_GHAST_AMBIENT, SoundSource.NEUTRAL, 4f, 0.8f);
		for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) HeroTitle.card(p, "THE RESCUE IS HERE");
	}

	private static void end(MinecraftServer server, List<ServerPlayer> winners) {
		calledAt = -1;
		ServerLevel level = server.overworld();
		for (BlockPos b : ROPE) if (level.getBlockState(b).is(PeakGearBlocks.ROPE)) level.setBlock(b, Blocks.AIR.defaultBlockState(), 3);
		ROPE.clear();
		if (rescue != null) { rescue.discard(); rescue = null; }
		List<ServerPlayer> all = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) if (PeakWorld.is(p.level()) && !p.isCreative()) all.add(p);
		List<String> names = new ArrayList<>(); List<Integer> heights = new ArrayList<>(); List<Boolean> won = new ArrayList<>();
		for (ServerPlayer p : all) {
			names.add(p.getName().getString());
			heights.add((int) Math.round(Math.max(0, PeakStatus.of(p).highest - 63) * 1.6));
			won.add(winners.contains(p));
		}
		int ascent = PeakAscents.of(server);
		for (ServerPlayer p : all) {
			var sc = PeakStatus.of(p);
			boolean w = winners.contains(p);
			List<String> badges = new ArrayList<>();
			int secs = (int) ((System.currentTimeMillis() - sc.started) / 1000);
			if (w) {
				badges.add("Peak");
				if (all.size() == 1) badges.add("LoneWolf");
				if (!sc.fellHurt) badges.add("Balloon");
				if (!sc.atePackaged) badges.add("Naturalist");
				if (!sc.passedOutEver) badges.add("Survivalist");
				if (secs <= 3600) badges.add("SpeedClimber");
				for (int a = 1; a <= ascent; a++) badges.add("Ascent" + a);
			} else if (!winners.isEmpty()) badges.add("TriedYourBest");
			for (String b : badges) PeakBadges.award(p, b);
			ServerPlayNetworking.send(p, new EndRun(w, secs, names, heights, won, badges));
		}
	}
}
