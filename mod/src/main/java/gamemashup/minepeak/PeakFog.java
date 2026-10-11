package gamemashup.minepeak;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/**
 * PEAK's fog (OrbFogHandler) and campfires, on the server.
 * - The fog is a sphere round each stage's fog point (exported: "fog"), all fog outside it. It waits, then closes in
 *   (0.4 blocks a second, down to 30): when everyone has climbed past the stage's mark, or after its wait (1000 s; never
 *   on the first stage), not counting the time everyone rests at a lit campfire. "THE FOG RISES" when it starts.
 * - Out in the fog you get cold (PEAK: +0.0105 a second, on PEAK's bar: PeakStatus), and Minecraft's frost shows it.
 *   From Ascent 5 the nights are cold too. A lit campfire near you warms you (PEAK: 0.05 a second within 4 blocks).
 * - A campfire is lit by a hand on it, when everyone is within 12 blocks of it (else it says who's missing): those there
 *   are healed a fifth, warmed, given PEAK's morale boost (bonus stamina: PeakClimbClient) and respawn there; and the fog
 *   goes on to the next stage's point, to wait there.
 * Each player's cold, and where the fog is, go to the client (FogState) for the stamina bar and the fog's look.
 */
public final class PeakFog {
	private PeakFog() { }

	// ---- to the client ----

	public record FogState(double x, double y, double z, float size, boolean active, float cold) implements CustomPacketPayload {
		public static final Type<FogState> TYPE = new Type<>(MinePeak.id("fog"));
		public static final StreamCodec<FriendlyByteBuf, FogState> CODEC = StreamCodec.of((b, s) -> {
			b.writeDouble(s.x); b.writeDouble(s.y); b.writeDouble(s.z); b.writeFloat(s.size); b.writeBoolean(s.active); b.writeFloat(s.cold);
		}, b -> new FogState(b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat(), b.readBoolean(), b.readFloat()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** A campfire's morale boost: this much bonus stamina. */
	public record Morale(float amount) implements CustomPacketPayload {
		public static final Type<Morale> TYPE = new Type<>(MinePeak.id("morale"));
		public static final StreamCodec<FriendlyByteBuf, Morale> CODEC = ByteBufCodecs.FLOAT.map(Morale::new, Morale::amount).cast();
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	static void initCommon() {
		PayloadTypeRegistry.clientboundPlay().register(FogState.TYPE, FogState.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Morale.TYPE, Morale.CODEC);
	}

	// ---- PEAK's numbers (exported, or PEAK's defaults) ----

	record Origin(Vec3 at, double size, double height, double forward, boolean off) { }

	static final double COLD_RATE = 0.0105, NIGHT_COLD = 0.002, WARM_RATE = 0.05, STOP_SIZE = 30;

	private static List<Origin> origins(IslandData d) {
		List<Origin> l = new ArrayList<>();
		if (!d.info.has("fog")) return l;
		for (JsonElement e : d.info.getAsJsonObject("fog").getAsJsonArray("origins")) {
			JsonObject o = e.getAsJsonObject();
			var at = o.getAsJsonArray("at");
			l.add(new Origin(new Vec3(at.get(0).getAsDouble() + 0.5, at.get(1).getAsDouble(), at.get(2).getAsDouble() + 0.5), o.get("size").getAsDouble(),
				o.get("moveOnHeight").getAsDouble(), o.get("moveOnForward").getAsDouble(), o.get("disabled").getAsBoolean()));
		}
		return l;
	}

	private static double num(IslandData d, String block, String key, double fallback) {
		return d.info.has(block) && d.info.getAsJsonObject(block).has(key) ? d.info.getAsJsonObject(block).get(key).getAsDouble() : fallback;
	}

	// ---- the state (kept in the world's folder) ----

	private static int id;
	private static double size, waited;
	private static boolean moving, arrived, loaded, resting;

	private static Path file(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("minepeak_fog.json"); }

	static void load(MinecraftServer server, IslandData data) {
		List<Origin> os = origins(data);
		id = 0; size = os.isEmpty() ? 0 : os.get(0).size(); waited = 0; moving = false; arrived = false;
		try {
			Path f = file(server);
			if (Files.isRegularFile(f)) {
				JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
				id = o.get("id").getAsInt(); size = o.get("size").getAsDouble(); waited = o.get("waited").getAsDouble();
				moving = o.get("moving").getAsBoolean(); arrived = o.get("arrived").getAsBoolean();
			}
		} catch (Exception e) {
			MinePeak.LOG.warn("MinePeak: couldn't read the fog's state", e);
		}
		loaded = true;
	}

	static void save(MinecraftServer server) {
		if (!loaded) return;
		JsonObject o = new JsonObject();
		o.addProperty("id", id); o.addProperty("size", size); o.addProperty("waited", waited); o.addProperty("moving", moving); o.addProperty("arrived", arrived);
		try { Files.writeString(file(server), o.toString()); } catch (Exception e) { MinePeak.LOG.warn("MinePeak: couldn't save the fog's state", e); }
	}

	static void stop(MinecraftServer server) { save(server); loaded = false; }

	// ---- every tick ----

	static void tick(MinecraftServer server, IslandData data) {
		if (!loaded) load(server, data);
		List<Origin> os = origins(data);
		boolean active = id < os.size() && !os.get(id).off() && PeakAscents.fog(server);
		Origin o = active ? os.get(id) : null;
		double speed = num(data, "fog", "speed", 0.4), maxWait = num(data, "fog", "maxWait", 1000);
		List<ServerPlayer> players = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) if (PeakWorld.scout(p) && p.isAlive() && p.level().dimension() == Level.OVERWORLD) players.add(p);   // (scouts only: creative plays by Minecraft's rules)
		if (server.getTickCount() % 20 == 0) {
			// (everyone resting at a lit campfire: the fog's wait stands still)
			resting = !players.isEmpty();
			for (ServerPlayer p : players) if (!nearLitFire(p, num(data, "campfire", "radius", 12))) { resting = false; break; }
		}
		if (active) {
			if (moving) {
				size -= speed / 20;
				if (size <= STOP_SIZE) { size = STOP_SIZE; moving = false; arrived = true; save(server); }
			} else if (!arrived) {
				if (!resting) waited += 1 / 20.0;
				boolean movedOn = !players.isEmpty();
				for (ServerPlayer p : players) if (p.getY() < o.height() || p.getZ() > o.forward()) { movedOn = false; break; }
				if (movedOn || waited > maxWait && id > 0) {
					moving = true;
					waited = 0;
					String text = HeroTitle.word(data, "THEFOGRISES", "THE FOG RISES");
					for (ServerPlayer p : players) HeroTitle.card(p, text);
					save(server);
				}
			}
		}
		if (server.getTickCount() % 10 != 0) return;
		IslandData d = data;
		for (ServerPlayer p : players) {
			if (!PeakWorld.scout(p)) continue;
			boolean outside = active && p.position().distanceTo(o.at()) > size;
			double h = PeakTime.hours(p.level().getOverworldClockTime(), PeakTime.Day.of(d));
			boolean coldNight = PeakAscents.coldNights(server) && (h >= 21 || h < 5) && !PeakThings.stage(d, p.getBlockZ()).matches("caldera|kiln|peak");
			if (nearLitFire(p, 4)) PeakStatus.sub(p, PeakStatus.COLD, (float) (WARM_RATE * 0.5));
			else if (outside) PeakStatus.add(p, PeakStatus.COLD, (float) (COLD_RATE * 0.5));
			else if (coldNight) PeakStatus.add(p, PeakStatus.COLD, 0.005f * 0.5f);
			float cold = PeakStatus.get(p, PeakStatus.COLD);
			// Minecraft's frost shows it
			if (cold > 0.05f) p.setTicksFrozen(Math.max(p.getTicksFrozen(), Math.round(Math.min(0.95f, cold) * p.getTicksRequiredToFreeze())));
		}
		if (server.getTickCount() % 20 == 0)
			for (ServerPlayer p : players)
				ServerPlayNetworking.send(p, new FogState(active ? o.at().x : 0, active ? o.at().y : 0, active ? o.at().z : 0, (float) size, active, PeakStatus.get(p, PeakStatus.COLD)));
	}

	/** Is a lit campfire within r blocks of the player? */
	static boolean nearLitFire(ServerPlayer p, double r) {
		BlockPos me = p.blockPosition();
		int ri = (int) Math.ceil(r);
		for (BlockPos q : BlockPos.betweenClosed(me.offset(-ri, -4, -ri), me.offset(ri, 4, ri))) {
			var s = p.level().getBlockState(q);
			if (s.is(Blocks.CAMPFIRE) && s.getValue(CampfireBlock.LIT) && q.distSqr(me) <= r * r) return true;
		}
		return false;
	}

	// ---- a campfire ----

	/**
	 * Lights PEAK's campfire at pos if everyone is near enough (else tells who isn't): the morale boost, healing, warmth,
	 * the respawn there, and the fog on to the next stage. True when it's lit.
	 */
	static boolean light(MinecraftServer server, BlockPos pos, ServerPlayer by) {
		IslandData data = MinePeakServer.island(server);
		double r = data != null ? num(data, "campfire", "radius", 12) : 12;
		List<String> far = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers())
			if (PeakWorld.scout(p) && p.isAlive() && Math.sqrt(p.blockPosition().distSqr(pos)) > r)
				far.add(p.getName().getString() + " (" + Math.round(Math.sqrt(p.blockPosition().distSqr(pos))) + " m)");
		if (!far.isEmpty()) {
			by.sendSystemMessage(Component.literal("Everyone has to be at the campfire to light it: " + String.join(", ", far)).withStyle(ChatFormatting.YELLOW));
			return false;
		}
		if (data == null) return true;
		float boost = (float) num(data, "campfire", "boost", 0.25), heal = (float) num(data, "campfire", "injury", 0.2);
		String text = HeroTitle.word(data, "MORALEBOOST", "MORALE BOOST");
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.isSpectator() || Math.sqrt(p.blockPosition().distSqr(pos)) > r) continue;
			PeakStatus.sub(p, PeakStatus.INJURY, heal);
			PeakStatus.sub(p, PeakStatus.PETRIFY, heal);
			PeakStatus.bonus(p, boost);
			PeakSfx.play(p.level(), p.position(), "morale_boost", null, 1f, 1f);
			if (++PeakStatus.of(p).fires >= 5) PeakBadges.award(p, "HappyCamper");
			HeroTitle.card(p, text);
			p.setRespawnPosition(new ServerPlayer.RespawnConfig(LevelData.RespawnData.of(Level.OVERWORLD, pos.south(2), 180f, 0f), true), false);
		}
		// the fog: on to the next stage's point (this campfire ends stage k: the next is k + 1)
		int k = 0;
		List<int[]> fires = new ArrayList<>(data.fires);
		fires.sort((a, b) -> Integer.compare(b[2], a[2]));
		double best = Double.MAX_VALUE;
		for (int i = 0; i < fires.size(); i++) {
			double dd = pos.distSqr(new BlockPos(fires.get(i)[0], fires.get(i)[1], fires.get(i)[2]));
			if (dd < best) { best = dd; k = i; }
		}
		List<Origin> os = origins(data);
		if (k + 1 > id) {
			id = k + 1;
			size = id < os.size() ? os.get(id).size() : 0;
			moving = false; arrived = false; waited = 0;
			save(server);
		}
		return true;
	}

	static float cold(ServerPlayer p) { return PeakStatus.get(p, PeakStatus.COLD); }
}
