package gamemashup.minepeak;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * PEAK's hazards, with PEAK's numbers (and the island's own, as exported):
 * - lava's heat: within 10 blocks above it, Hot grows (PEAK: (1 - height/10) x 0.03 each half second);
 * - the Kiln's rising lava: once a scout is in the Kiln, after PEAK's wait (3 minutes) "THE LAVA RISES" and it climbs
 *   from its level to the top over PEAK's travel time (20 minutes), filling the Kiln as it goes (kept with the world);
 * - the Caldera's eruptions: a lava bomb every few seconds (PEAK: after 10 s, then every -5 to 15) arcing over it; where
 *   one lands, Injury and Hot for scouts close by;
 * - PEAK's wind (WindChillZone: the Roots' windstorm, the Alpine's snowstorm): by turns (PEAK's on and off times),
 *   pushing scouts in the open (PeakGearClient; not on a piton), making climbing harder, the snowstorm chilling them;
 * - places that warm, cool or heat you (PEAK's StatusEmitter: the hot spring, geysers, waterfalls, magma rocks);
 * - the Alpine's geysers: steam, then a blast that throws you up; its rusty pitons crack and break under you;
 * - the Mesa's sun: Hot in the open by day (a parasol, a glider or sunscreen keep it off), and its tornadoes;
 * - antlions (PEAK's Antlion): awake, their pit's sand slides you toward them; their bite (+0.2 injury) throws you;
 * - geysers and the like (PEAK's AOE that goes off every so often): a blast upward.
 */
final class PeakHazards {
	private PeakHazards() { }

	/** The wind, to the client (PeakGearClient pushes the player; the snow blows). */
	public record Wind(boolean on, float x, float z, float strength) implements CustomPacketPayload {
		public static final Type<Wind> TYPE = new Type<>(MinePeak.id("wind"));
		public static final StreamCodec<FriendlyByteBuf, Wind> CODEC = StreamCodec.of((b, w) -> { b.writeBoolean(w.on); b.writeFloat(w.x); b.writeFloat(w.z); b.writeFloat(w.strength); },
			b -> new Wind(b.readBoolean(), b.readFloat(), b.readFloat(), b.readFloat()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	static void initCommon() { PayloadTypeRegistry.clientboundPlay().register(Wind.TYPE, Wind.CODEC); }

	static void tick(MinecraftServer server) {
		IslandData data = MinePeakServer.island(server);
		if (data == null) return;
		ServerLevel level = server.overworld();
		long now = server.getTickCount();
		List<ServerPlayer> scouts = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) if (PeakWorld.scout(p) && !PeakStatus.out(p)) scouts.add(p);
		if (!loaded) load(server, data);
		lava(server, level, data, scouts, now);
		eruptions(level, data, scouts, now);
		wind(level, data, scouts, now);
		emitters(data, scouts, now);
		if (now % 10 == 0) for (ServerPlayer p : scouts) heat(level, p, data);
		antlions(level, scouts, now);
		geysers(level, data, scouts, now);
		rustyPitons(level, data, scouts, now);
		tornado(level, data, scouts, now);
	}

	static String stage(IslandData data, ServerPlayer p) { return PeakThings.stage(data, p.getBlockZ()); }

	// ---- heat: lava below, the Mesa's sun ----

	private static void heat(ServerLevel level, ServerPlayer p, IslandData data) {
		String st = stage(data, p);
		if (st.equals("caldera") || st.equals("kiln") || st.equals("peak")) {
			BlockPos feet = p.blockPosition();
			for (int dy = 0; dy <= 10; dy++) {
				var s = level.getBlockState(feet.below(dy));
				if (s.is(Blocks.LAVA) || s.is(Blocks.MAGMA_BLOCK) && dy <= 1) {
					PeakStatus.add(p, PeakStatus.HOT, (1 - dy / 10f) * 0.02f * 1.5f);
					break;
				}
				if (!s.isAir() && !s.is(Blocks.LAVA)) break;
			}
		}
		if (st.equals("mesa") && level.isBrightOutside() && level.canSeeSky(p.blockPosition().above())
			&& !PeakGear.holding(p, "Parasol") && !PeakGear.holding(p, "Glider") && !PeakStatus.has(PeakStatus.of(p), "Sunscreen"))
			PeakStatus.add(p, PeakStatus.HOT, 0.05f);
	}

	// ---- the Kiln's rising lava ----

	private static boolean loaded, rising;
	private static double lavaY = Double.NaN, waited;
	private static int placedY = Integer.MIN_VALUE;

	private static JsonObject kilnLava(IslandData data) {
		if (!data.info.has("lava")) return null;
		for (JsonElement e : data.info.getAsJsonArray("lava"))
			if (e.getAsJsonObject().get("segment").getAsString().equals("TheKiln")) return e.getAsJsonObject();
		return null;
	}

	private static JsonObject area(IslandData data, String file) {
		for (JsonElement e : data.info.getAsJsonArray("areas")) if (e.getAsJsonObject().get("file").getAsString().equals(file)) return e.getAsJsonObject();
		return null;
	}

	private static void lava(MinecraftServer server, ServerLevel level, IslandData data, List<ServerPlayer> scouts, long now) {
		JsonObject k = kilnLava(data);
		if (k == null || !PeakAscents.fog(server)) return;
		double from = k.get("level").getAsDouble(), top = k.get("top").getAsDouble(), wait = k.get("wait").getAsDouble(), travel = Math.max(60, k.get("travel").getAsDouble());
		if (Double.isNaN(lavaY)) lavaY = from;
		boolean inKiln = false;
		for (ServerPlayer p : scouts) if (stage(data, p).equals("kiln") || stage(data, p).equals("peak")) inKiln = true;
		if (!rising) {
			if (!inKiln) return;
			waited += 1 / 20.0;
			if (waited < wait) return;
			rising = true;
			String text = HeroTitle.word(data, "THELAVARISES", "THE LAVA RISES");
			for (ServerPlayer p : scouts) HeroTitle.card(p, text);
			save(server);
		}
		if (lavaY >= top) return;
		lavaY = Math.min(top, lavaY + (top - from) / travel / 20.0);
		int y = (int) Math.floor(lavaY);
		if (y > placedY) {
			for (int yy = Math.max(placedY + 1, (int) from); yy <= y; yy++) fill(level, data, yy);
			placedY = y;
			if (now % 200 == 0) save(server);
		}
	}

	/**
	 * Lava at height y inside the Kiln: the air over the lava below, and all the air joined to it at that height within the
	 * Kiln's area (a layer that would spread out of the volcano, too big, isn't filled).
	 */
	private static void fill(ServerLevel level, IslandData data, int y) {
		JsonObject a = area(data, "thekiln.bin");
		if (a == null) return;
		int x0 = a.get("x0").getAsInt(), z0 = a.get("z0").getAsInt(), w = a.get("width").getAsInt(), d = a.get("depth").getAsInt();
		ArrayDeque<BlockPos> todo = new ArrayDeque<>();
		Set<Long> seen = new HashSet<>();
		for (int x = x0; x < x0 + w; x++)
			for (int z = z0; z < z0 + d; z++) {
				BlockPos q = new BlockPos(x, y, z);
				if (!level.isLoaded(q)) continue;
				if (level.getBlockState(q.below()).is(Blocks.LAVA) && level.getBlockState(q).isAir() && seen.add(q.asLong())) todo.add(q);
			}
		List<BlockPos> layer = new ArrayList<>();
		while (!todo.isEmpty() && layer.size() < 40000) {
			BlockPos q = todo.poll();
			layer.add(q);
			for (var dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
				BlockPos n = q.relative(dir);
				if (n.getX() < x0 || n.getZ() < z0 || n.getX() >= x0 + w || n.getZ() >= z0 + d || !level.isLoaded(n)) continue;
				if (level.getBlockState(n).isAir() && seen.add(n.asLong())) todo.add(n);
			}
		}
		if (layer.size() >= 40000) return;
		for (BlockPos q : layer) level.setBlock(q, Blocks.LAVA.defaultBlockState(), 2);
	}

	private static Path file(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("minepeak_lava.json"); }

	private static void load(MinecraftServer server, IslandData data) {
		loaded = true;
		rising = false; waited = 0; lavaY = Double.NaN; placedY = Integer.MIN_VALUE;
		try {
			Path f = file(server);
			if (!Files.isRegularFile(f)) return;
			JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
			rising = o.get("rising").getAsBoolean(); waited = o.get("waited").getAsDouble(); lavaY = o.get("y").getAsDouble(); placedY = o.get("placed").getAsInt();
		} catch (Exception e) { MinePeak.LOG.warn("MinePeak: couldn't read the lava's state", e); }
	}

	static void save(MinecraftServer server) {
		if (!loaded) return;
		JsonObject o = new JsonObject();
		o.addProperty("rising", rising); o.addProperty("waited", waited); o.addProperty("y", Double.isNaN(lavaY) ? 0 : lavaY); o.addProperty("placed", placedY);
		try { Files.writeString(file(server), o.toString()); } catch (Exception e) { MinePeak.LOG.warn("MinePeak: couldn't save the lava's state", e); }
	}

	static void stop(MinecraftServer server) {
		save(server); loaded = false;
		// (the timings count the server's ticks, which start again at 0 with the next world)
		zones = null; emitters = null; geysers = null;
		BOMBS.clear(); nextEruption = -1;
		twister = null; twisterTo = null; twisterUntil = -1; nextTwister = -1;
		ANTLIONS.clear(); BITTEN.clear(); HANGING.clear();
	}

	/** (The dev test: the Kiln's lava starts rising now.) */
	static void debugRise() { waited = 1e9; rising = true; }

	static double lavaY() { return lavaY; }

	// ---- the Caldera's eruptions ----

	private record Bomb(Vec3[] p, Vec3[] v) { }
	private static final List<Bomb> BOMBS = new ArrayList<>();
	private static long nextEruption = -1;

	private static void eruptions(ServerLevel level, IslandData data, List<ServerPlayer> scouts, long now) {
		JsonObject a = area(data, "caldera.bin");
		boolean anyone = false;
		for (ServerPlayer p : scouts) if (stage(data, p).equals("caldera")) anyone = true;
		if (a != null && anyone) {
			if (nextEruption < 0) nextEruption = now + 200;
			if (now >= nextEruption) {
				nextEruption = now + Math.max(0, Math.round((-5 + level.getRandom().nextFloat() * 20) * 20));
				double cx = a.get("x0").getAsInt() + a.get("width").getAsInt() / 2.0, cz = a.get("z0").getAsInt() + a.get("depth").getAsInt() / 2.0;
				// from somewhere on the lava, up and out
				double sx = cx + (level.getRandom().nextDouble() - 0.5) * 120, sz = cz + (level.getRandom().nextDouble() - 0.5) * 110;
				int sy = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) sx, (int) sz);
				Vec3 v = new Vec3((level.getRandom().nextDouble() - 0.5) * 1.2, 1.6 + level.getRandom().nextDouble() * 0.8, (level.getRandom().nextDouble() - 0.5) * 1.2);
				BOMBS.add(new Bomb(new Vec3[] { new Vec3(sx, sy + 1, sz) }, new Vec3[] { v }));
				level.playSound(null, BlockPos.containing(sx, sy, sz), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.AMBIENT, 6f, 0.5f);
			}
		} else nextEruption = -1;
		for (Iterator<Bomb> it = BOMBS.iterator(); it.hasNext(); ) {
			Bomb b = it.next();
			Vec3 p = b.p[0], v = b.v[0];
			Vec3 q = p.add(v);
			b.v[0] = v.add(0, -0.06, 0).scale(0.99);
			level.sendParticles(ParticleTypes.LAVA, q.x, q.y, q.z, 2, 0.2, 0.2, 0.2, 0);
			level.sendParticles(ParticleTypes.LARGE_SMOKE, q.x, q.y, q.z, 1, 0.1, 0.1, 0.1, 0.01);
			level.sendParticles(ParticleTypes.FLAME, q.x, q.y, q.z, 3, 0.25, 0.25, 0.25, 0.01);
			BlockPos bq = BlockPos.containing(q);
			if (!level.isLoaded(bq) || q.y < level.getMinY()) { it.remove(); continue; }
			if (!level.getBlockState(bq).isAir() && !level.getBlockState(bq).is(Blocks.LAVA) || level.getBlockState(bq).is(Blocks.LAVA) && v.y < 0) {
				it.remove();
				level.sendParticles(ParticleTypes.EXPLOSION, q.x, q.y, q.z, 2, 0.5, 0.5, 0.5, 0);
				level.sendParticles(ParticleTypes.LAVA, q.x, q.y, q.z, 20, 1, 0.5, 1, 0);
				level.playSound(null, bq, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.AMBIENT, 2f, 0.8f);
				for (ServerPlayer s : scouts)
					if (s.position().distanceTo(q) < 4) { PeakStatus.add(s, PeakStatus.INJURY, 0.25f); PeakStatus.add(s, PeakStatus.HOT, 0.25f); s.setDeltaMovement(s.position().subtract(q).normalize().scale(0.8).add(0, 0.5, 0)); s.hurtMarked = true; }
				continue;
			}
			b.p[0] = q;
		}
	}

	// ---- PEAK's wind zones (the Roots' windstorm, the Alpine's snowstorm: WindChillZone) ----

	/** A wind zone: its stage, PEAK's numbers, and whether it's blowing now. */
	private static final class Zone {
		String stage; float[] on, off; float force, cold, grab; boolean blowing; long until = -1; float x = 1, z = 0;
	}
	private static List<Zone> zones;
	private static IslandData zonesFor;

	private static List<Zone> zones(IslandData data) {
		if (zones != null && zonesFor == data) return zones;
		List<Zone> l = new ArrayList<>();
		for (JsonElement ae : data.info.getAsJsonArray("areas"))
			for (JsonElement he : PeakItemData.array(ae.getAsJsonObject(), "hazards")) {
				JsonObject h = he.getAsJsonObject();
				if (!h.get("kind").getAsString().equals("WindChillZone")) continue;
				Zone z = new Zone();
				z.stage = PeakThings.stage(data, h.getAsJsonArray("at").get(2).getAsInt());
				if (l.stream().anyMatch(o -> o.stage.equals(z.stage))) continue;
				z.on = range(h, "windTimeRangeOn", 15, 25); z.off = range(h, "windTimeRangeOff", 20, 90);
				JsonObject f = h.has("fields") ? h.getAsJsonObject("fields") : new JsonObject();
				z.force = f.has("windForce") ? f.get("windForce").getAsFloat() : 4;
				z.cold = f.has("statusApplicationPerSecond") ? f.get("statusApplicationPerSecond").getAsFloat() : 0.01f;
				z.grab = f.has("grabStaminaMultiplierDuringWind") ? f.get("grabStaminaMultiplierDuringWind").getAsFloat() : 1;
				l.add(z);
			}
		zones = l;
		zonesFor = data;
		return l;
	}

	private static void wind(ServerLevel level, IslandData data, List<ServerPlayer> scouts, long now) {
		for (Zone z : zones(data)) {
			if (z.until < 0) z.until = now + Math.round(z.off[0] * 20);
			if (now >= z.until) {
				z.blowing = !z.blowing;
				float[] r = z.blowing ? z.on : z.off;
				z.until = now + Math.round((r[0] + level.getRandom().nextFloat() * (r[1] - r[0])) * 20);
				// PEAK: along x, either way, a fifth toward PEAK's +z (Minecraft's -z)
				Vec3 d = new Vec3(level.getRandom().nextBoolean() ? 1 : -1, 0, 0).lerp(new Vec3(0, 0, -1), 0.2).normalize();
				z.x = (float) d.x; z.z = (float) d.z;
			}
		}
		if (now % 10 != 0) return;
		for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
			Zone here = null;
			if (PeakWorld.is(p.level())) { String st = stage(data, p); for (Zone z : zones(data)) if (z.blowing && z.stage.equals(st)) here = z; }
			float exposure = here != null ? exposure(level, p, here) : 0;
			ServerPlayNetworking.send(p, new Wind(here != null, here != null ? here.x : 0, here != null ? here.z : 0, exposure * (here != null ? here.force : 0)));
			if (here != null && exposure > 0 && PeakWorld.scout(p) && here.cold > 0) PeakStatus.add(p, PeakStatus.COLD, here.cold * exposure * 0.5f);
			PeakStatus.of(p).windGrab = here != null ? here.grab : 1;
		}
	}

	/** How open to the wind the player is (0 sheltered .. 1): the sky above, nothing upwind close by. */
	private static float exposure(ServerLevel level, ServerPlayer p, Zone z) {
		if (!level.canSeeSky(p.blockPosition().above())) return 0.15f;
		int blocked = 0;
		for (int i = 1; i <= 4; i++) {
			BlockPos q = BlockPos.containing(p.getX() - z.x * i, p.getY() + 1, p.getZ() - z.z * i);
			if (!level.getBlockState(q).isAir()) blocked++;
		}
		return 1f - blocked * 0.22f;
	}

	// ---- places that give a status (PEAK's StatusEmitter: hot springs, geysers' warmth, waterfalls' cool, magma's heat) ----

	private record Emitter(Vec3 at, int status, float amount, float radius, float fade) { }
	private static List<Emitter> emitters;
	private static IslandData emittersFor;

	private static void emitters(IslandData data, List<ServerPlayer> scouts, long now) {
		if (now % 10 != 0) return;
		if (emitters == null || emittersFor != data) {
			List<Emitter> l = new ArrayList<>();
			java.util.Set<String> seen = new java.util.HashSet<>();
			for (JsonElement ae : data.info.getAsJsonArray("areas"))
				for (JsonElement he : PeakItemData.array(ae.getAsJsonObject(), "hazards")) {
					JsonObject h = he.getAsJsonObject();
					String path = h.get("path").getAsString();
					if (!h.get("kind").getAsString().equals("StatusEmitter") || path.contains("EnableWhenLit") || !seen.add(path + h.get("at"))) continue;
					JsonObject f = h.has("fields") ? h.getAsJsonObject("fields") : new JsonObject();
					var at = h.getAsJsonArray("at");
					l.add(new Emitter(new Vec3(at.get(0).getAsDouble(), at.get(1).getAsDouble(), at.get(2).getAsDouble()), PeakStatus.index(f.has("statusType") ? f.get("statusType").getAsString() : "Hot"),
						f.has("amount") ? f.get("amount").getAsFloat() : 0, f.has("radius") ? f.get("radius").getAsFloat() : 2, f.has("outerFade") ? f.get("outerFade").getAsFloat() : 0));
				}
			emitters = l;
			emittersFor = data;
		}
		for (ServerPlayer p : scouts)
			for (Emitter e : emitters) {
				double d = p.position().distanceTo(e.at);
				if (d > e.radius + e.fade) continue;
				float k = d <= e.radius ? 1 : (float) (1 - (d - e.radius) / Math.max(0.01, e.fade));
				float amt = e.amount * k * 0.5f;
				if (amt > 0) PeakStatus.add(p, e.status, amt); else PeakStatus.sub(p, e.status, -amt);
			}
	}

	static JsonObject hazard(IslandData data, String kind) {
		for (JsonElement ae : data.info.getAsJsonArray("areas"))
			for (JsonElement h : PeakItemData.array(ae.getAsJsonObject(), "hazards"))
				if (h.getAsJsonObject().get("kind").getAsString().equals(kind)) return h.getAsJsonObject();
		return null;
	}

	private static float[] range(JsonObject h, String key, float a, float b) {
		if (h != null && h.has("fields") && h.getAsJsonObject("fields").has(key)) {
			var arr = h.getAsJsonObject("fields").getAsJsonArray(key);
			if (arr != null && arr.size() == 2) return new float[] { arr.get(0).getAsFloat(), arr.get(1).getAsFloat() };
		}
		return new float[] { a, b };
	}

	// ---- antlions ----

	private static final java.util.Map<java.util.UUID, Long> BITTEN = new java.util.HashMap<>();

	/** An antlion's state: how far its pit's sand has started sliding (PEAK's str, 0..1), since its last bite, whom it's after. */
	private static final class Antlion { float str, sinceBite = 1; java.util.UUID target; }

	private static final java.util.Map<Integer, Antlion> ANTLIONS = new java.util.HashMap<>();

	/**
	 * PEAK's Antlion: a scout within 12 of it (16 once it's after one) wakes it; its pit's sand starts sliding (over a
	 * second), and then no one can stand in it: the ground carries you down toward it, and you climb or are thrown out.
	 * Within 5 it bites: +0.2 injury, and you're thrown up and away. No falls hurt near it (PEAK clamps the time since
	 * grounded). The sand rumbles while it's awake. Bitten and got away (out of its reach, up): PEAK's Megaentomology.
	 */
	private static void antlions(ServerLevel level, List<ServerPlayer> scouts, long now) {
		java.util.Set<Silverfish> all = new java.util.LinkedHashSet<>();
		for (ServerPlayer p : scouts)
			all.addAll(level.getEntitiesOfClass(Silverfish.class, new AABB(p.blockPosition()).inflate(20), s -> "Antlion".equals(s.getCustomName() == null ? "" : s.getCustomName().getString())));
		for (Silverfish a : all) {
			if (!a.isNoAi()) a.setNoAi(true);   // (it stays at the bottom of its pit)
			Antlion st = ANTLIONS.computeIfAbsent(a.getId(), k -> new Antlion());
			Vec3 at = a.position();
			ServerPlayer target = null;
			double best = st.target != null ? 16 : 12;
			for (ServerPlayer p : scouts) {
				if (p.isSpectator()) continue;
				double d = p.position().add(0, 0.9, 0).distanceTo(at);
				if (d < 12) { PeakGear.noFall(p, 5); p.fallDistance = 0; }
				if (d < best) { best = d; target = p; }
			}
			st.target = target != null ? target.getUUID() : null;
			st.str = Math.max(0, Math.min(1, st.str + (target != null ? 0.05f : -0.05f)));
			st.sinceBite += 0.05f;
			if (st.str <= 0) continue;
			PeakLoops.near(level, "antlion:" + a.getId(), "antlion_rumble", null, at, 0.3f * st.str + 0.05f, 24);
			if (now % 4 == 0)
				level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.FALLING_DUST, net.minecraft.world.level.block.Blocks.SAND.defaultBlockState()),
					at.x, at.y + 1.5, at.z, Math.round(6 * st.str), 4, 0.8, 4, 0);
			for (ServerPlayer p : scouts) {
				if (p.isSpectator() || PeakStatus.out(p)) continue;
				Vec3 to = at.subtract(p.position());
				double flat = Math.sqrt(to.x * to.x + to.z * to.z);
				// the sand slides: within the pit, the ground carries you down toward it
				if (st.str > 0.95f && flat < PIT_RADIUS + 0.5 && Math.abs(to.y) < 6 && p.onGround() && flat > 0.6) {
					p.setDeltaMovement(p.getDeltaMovement().add(to.x / flat * 0.075, -0.02, to.z / flat * 0.075));
					p.hurtMarked = true;
				}
				// the bite
				if (p.position().add(0, 0.9, 0).distanceTo(at) < 5 && st.sinceBite > 0.5f) {
					st.sinceBite = 0;
					BITTEN.put(p.getUUID(), now);
					PeakStatus.add(p, PeakStatus.INJURY, 0.2f);
					Vec3 away = p.position().subtract(at).multiply(1, 0, 1);
					away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
					p.setDeltaMovement(away.x * 1.0, 0.95, away.z * 1.0);
					p.hurtMarked = true;
					a.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
					level.playSound(null, a.blockPosition(), SoundEvents.EVOKER_FANGS_ATTACK, SoundSource.HOSTILE, 1.3f, 0.6f);
				}
			}
		}
		if (now % 200 == 0) ANTLIONS.keySet().removeIf(id -> level.getEntity(id) == null);
		for (ServerPlayer p : scouts) {
			Long b = BITTEN.get(p.getUUID());
			if (b == null || now - b < 40 || PeakStatus.out(p)) continue;
			boolean near = !level.getEntitiesOfClass(Silverfish.class, new AABB(p.blockPosition()).inflate(ESCAPE), s -> "Antlion".equals(s.getCustomName() == null ? "" : s.getCustomName().getString())).isEmpty();
			if (!near) { BITTEN.remove(p.getUUID()); PeakBadges.award(p, "Megaentomology"); }
		}
	}

	/** An antlion's pit (PeakCreatures digs it), and how far one bitten must get from it for PEAK's Megaentomology. */
	static final int PIT_RADIUS = 8, PIT_DEPTH = 4, ESCAPE = 14;

	/** (The dev test: the antlion's pit sliding, 0..1, and whom it's after.) */
	static String debugAntlion(int id) { Antlion a = ANTLIONS.get(id); return a == null ? null : String.format("sand %.2f, after %s", a.str, a.target == null ? "nobody" : "a scout"); }

	// ---- geysers (the Alpine's: PEAK's TimeEvent "Geyser", its steam then its blast) ----

	private static List<Vec3> geysers;
	private static IslandData geysersFor;

	private static void geysers(ServerLevel level, IslandData data, List<ServerPlayer> scouts, long now) {
		if (geysers == null || geysersFor != data) {
			List<Vec3> l = new ArrayList<>();
			for (JsonElement ae : data.info.getAsJsonArray("areas"))
				for (JsonElement he : PeakItemData.array(ae.getAsJsonObject(), "hazards")) {
					JsonObject h = he.getAsJsonObject();
					if (!h.get("kind").getAsString().equals("TimeEvent") || !h.get("path").getAsString().contains("Geyser")) continue;
					var kids = h.has("children") ? h.getAsJsonObject("children") : null;
					var at = kids != null && kids.has("Explosion") ? kids.getAsJsonArray("Explosion") : h.getAsJsonArray("at");
					Vec3 v = new Vec3(at.get(0).getAsDouble(), at.get(1).getAsDouble(), at.get(2).getAsDouble());
					if (l.stream().noneMatch(o -> o.distanceTo(v) < 1)) l.add(v);
				}
			geysers = l;
			geysersFor = data;
		}
		for (int i = 0; i < geysers.size(); i++) {
			Vec3 c = geysers.get(i);
			boolean near = false;
			for (ServerPlayer p : scouts) if (p.position().distanceTo(c) < 48) near = true;
			if (!near) continue;
			long phase = (now + i * 37L) % 200;   // (each on its own beat: steam, then the blast)
			if (phase >= 140 && phase < 180 && now % 4 == 0) level.sendParticles(ParticleTypes.CLOUD, c.x, c.y + 0.5, c.z, 3, 0.2, 0.3, 0.2, 0.02);
			if (phase == 180) {
				level.sendParticles(ParticleTypes.CLOUD, c.x, c.y + 1, c.z, 80, 0.5, 3, 0.5, 0.2);
				level.sendParticles(ParticleTypes.SPLASH, c.x, c.y + 1, c.z, 80, 0.5, 2, 0.5, 0.3);
				level.playSound(null, BlockPos.containing(c), SoundEvents.FIRECHARGE_USE, SoundSource.AMBIENT, 2f, 0.5f);
				for (ServerPlayer p : scouts) {
					double dx = p.getX() - c.x, dz = p.getZ() - c.z, dy = p.getY() - c.y;
					if (dx * dx + dz * dz < 2.5 * 2.5 && dy > -1.5 && dy < 4) { p.setDeltaMovement(p.getDeltaMovement().add(0, 2.0, 0)); p.hurtMarked = true; PeakGear.noFall(p, 80); }
				}
			}
		}
	}

	// ---- the island's own pitons (the Alpine's rusty ones: they crack after a while, then break) ----

	private static final java.util.Map<java.util.UUID, long[]> HANGING = new java.util.HashMap<>();

	private static void rustyPitons(ServerLevel level, IslandData data, List<ServerPlayer> scouts, long now) {
		for (ServerPlayer p : scouts) {
			BlockPos on = null;
			if (PeakClimb.onWall(p))
				for (BlockPos q : BlockPos.betweenClosed(p.blockPosition().offset(-1, 0, -1), p.blockPosition().offset(1, 2, 1)))
					if (level.getBlockState(q).is(PeakGearBlocks.RUSTY_PITON)) { on = q.immutable(); break; }
			if (on == null) { HANGING.remove(p.getUUID()); continue; }
			final BlockPos at = on;
			long[] t = HANGING.computeIfAbsent(p.getUUID(), u -> new long[] { now + 20 + level.getRandom().nextInt(80), 0, at.asLong() });
			if (t[2] != on.asLong()) { t[0] = now + 20 + level.getRandom().nextInt(80); t[1] = 0; t[2] = on.asLong(); }
			if (now >= t[0]) {
				t[1]++;
				t[0] = now + 30;
				PeakSfx.play(level, on, t[1] >= 4 ? "piton_break" : "piton_crack", SoundEvents.ANVIL_LAND, 0.8f, 1.8f);
				level.sendParticles(ParticleTypes.CRIT, on.getX() + 0.5, on.getY() + 0.5, on.getZ() + 0.5, 6, 0.1, 0.1, 0.1, 0.05);
				if (t[1] >= 4) { level.destroyBlock(on, false); HANGING.remove(p.getUUID()); }
			}
		}
	}

	// ---- the Mesa's tornado ----

	private static Vec3 twister, twisterTo;
	private static long twisterUntil = -1, nextTwister = -1;

	private static void tornado(ServerLevel level, IslandData data, List<ServerPlayer> scouts, long now) {
		boolean mesa = false;
		ServerPlayer one = null;
		for (ServerPlayer p : scouts) if (stage(data, p).equals("mesa")) { mesa = true; one = p; }
		if (!mesa) { twister = null; return; }
		if (twister == null) {
			if (nextTwister < 0) nextTwister = now + Math.round((30 + level.getRandom().nextFloat() * 270) * 20);
			if (now < nextTwister) return;
			double a = level.getRandom().nextDouble() * Math.PI * 2;
			twister = one.position().add(Math.cos(a) * 60, 0, Math.sin(a) * 60);
			twisterUntil = now + Math.round((30 + level.getRandom().nextFloat() * 90) * 20);
			twisterTo = one.position();
			nextTwister = -1;
		}
		if (now >= twisterUntil) { twister = null; return; }
		if (now % 100 == 0 || twister.distanceTo(twisterTo) < 10) twisterTo = one.position().add((level.getRandom().nextDouble() - 0.5) * 60, 0, (level.getRandom().nextDouble() - 0.5) * 60);
		Vec3 step = twisterTo.subtract(twister).multiply(1, 0, 1);
		if (step.length() > 0.01) twister = twister.add(step.normalize().scale(15 / 20.0));
		int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) twister.x, (int) twister.z);
		twister = new Vec3(twister.x, gy, twister.z);
		if (now % 2 == 0)
			for (int i = 0; i < 12; i++) {
				double h = level.getRandom().nextDouble() * 40, ang = level.getRandom().nextDouble() * Math.PI * 2, rad = 2 + h * 0.25;
				level.sendParticles(ParticleTypes.CLOUD, twister.x + Math.cos(ang) * rad, gy + h, twister.z + Math.sin(ang) * rad, 1, 0, 0, 0, 0.02);
			}
		for (ServerPlayer p : scouts) {
			Vec3 to = twister.subtract(p.position()).multiply(1, 0, 1);
			double d = to.length();
			if (d > 25) continue;
			Vec3 tangent = new Vec3(-to.z, 0, to.x).normalize();
			Vec3 push = d < 10 ? tangent.scale(0.6).add(to.normalize().scale(0.2)).add(0, 0.35, 0) : to.normalize().scale(0.12 * (p.isShiftKeyDown() ? 0.25 : 1));
			p.setDeltaMovement(p.getDeltaMovement().add(push));
			p.hurtMarked = true;
			PeakGear.noFall(p, 100);
		}
	}
}
