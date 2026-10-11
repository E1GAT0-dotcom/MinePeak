package gamemashup.minepeak;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * PEAK's bees (BeeSwarm): not Minecraft's bees but a cloud of them round their hive, drifting after a scout who comes
 * near, stinging (poison, more when they're angry) whoever's in it. Take their hive (PEAK's beehive is a thing you can
 * pick up: right click the nest) and they're angry for 8 s and after you, as long as they can see it; break it (it
 * breaks when it lands hard, into four honeycombs) and they're angry for 12 s. With nothing to chase and their hive gone
 * (or too far), they scatter after 12 s. PEAK's own numbers (exported: items.json "bees") and PEAK's buzz (calm and angry,
 * PeakLoops).
 */
final class PeakBees {
	private PeakBees() { }

	/** A swarm: where it is and how it's moving (it flies through anything), its hive, whom it's after, how angry. */
	private static final class Swarm {
		final long nest;
		Vec3 pos, vel = Vec3.ZERO, lastSaw;
		UUID target;
		float danger, dispersal;
		boolean destroyed;
		int fading = -1;
		final java.util.Set<UUID> inside = new java.util.HashSet<>();
		final Map<UUID, Long> stung = new HashMap<>();

		Swarm(long nest, Vec3 at) { this.nest = nest; this.pos = at; this.lastSaw = at; }
	}

	private static final Map<Long, Swarm> SWARMS = new HashMap<>();

	// PEAK's numbers (BeeSwarm), or near them until an export has them
	private static float n(String k, float d) { return PeakStatus.f(PeakItemData.bees(), k, d); }
	private static float stinger(String k, float d) {
		JsonObject s = PeakItemData.bees().has("stinger") ? PeakItemData.bees().getAsJsonObject("stinger") : null;
		return PeakStatus.f(s, k, d);
	}

	/** PEAK's doNotApplyIfStatusesMaxed (a switch, not a number). */
	private static boolean maxedStops() {
		JsonObject s = PeakItemData.bees().has("stinger") ? PeakItemData.bees().getAsJsonObject("stinger") : null;
		return s == null || !s.has("doNotApplyIfStatusesMaxed") || s.get("doNotApplyIfStatusesMaxed").getAsBoolean();
	}

	static final String HIVE = "hive";

	/** Is this PEAK's beehive (the item)? */
	static boolean hive(ItemStack s) { var d = PeakItems.def(s); return d != null && d.has("Beehive"); }

	/** The nest a beehive came from (its swarm's), or 0. */
	private static long nestOf(ItemStack s) { return PeakItems.tag(s).getLongOr(HIVE, 0L); }

	/** The world stopping: the swarms are let go (the next world lets its own out). */
	static void stop() { SWARMS.clear(); FALLING.clear(); }

	/** A scout takes a nest: it comes away as PEAK's beehive, in hand. */
	static boolean take(ServerPlayer p, BlockPos nest) {
		ServerLevel level = (ServerLevel) p.level();
		if (!level.getBlockState(nest).is(Blocks.BEE_NEST)) return false;
		ItemStack hive = PeakItems.stack("Beehive");
		if (hive.isEmpty()) return false;
		CompoundTag t = PeakItems.tag(hive);
		t.putLong(HIVE, nest.asLong());
		PeakItems.save(hive, t);
		swarm(level, nest);   // (its bees, if they weren't out yet)
		level.setBlock(nest, Blocks.AIR.defaultBlockState(), 3);
		ItemEntity e = new ItemEntity(level, nest.getX() + 0.5, nest.getY() + 0.2, nest.getZ() + 0.5, hive);
		e.setDeltaMovement(Vec3.ZERO);
		e.setUnlimitedLifetime();
		level.addFreshEntity(e);
		PeakInventory.pickUp(p, e.getId());
		return true;
	}

	private static Swarm swarm(ServerLevel level, BlockPos nest) {
		return SWARMS.computeIfAbsent(nest.asLong(), k -> new Swarm(k, Vec3.atCenterOf(nest)));
	}

	/** Each tick: PEAK's nests near scouts let their swarms out; each swarm goes after someone, stings, buzzes. */
	static void tick(MinecraftServer server) {
		ServerLevel level = server.overworld();
		List<ServerPlayer> scouts = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) if (PeakWorld.scout(p)) scouts.add(p);
		if (scouts.isEmpty()) return;
		long now = server.getTickCount();
		if (now % 20 == 0) {
			IslandData data = MinePeakServer.island(server);
			for (ServerPlayer p : scouts) {
				// (Minecraft's own bees, from nests made before PEAK's swarms: gone)
				for (Bee b : level.getEntitiesOfClass(Bee.class, p.getBoundingBox().inflate(48))) b.discard();
				if (data == null) continue;
				ChunkPos c = p.chunkPosition();
				for (int dx = -2; dx <= 2; dx++)
					for (int dz = -2; dz <= 2; dz++)
						for (PeakThings.Creature cr : PeakThings.creatures(data, new ChunkPos(c.x() + dx, c.z() + dz))) {
							if (!"Beehive".equals(cr.kind())) continue;
							for (int dy = 0; dy <= 3; dy++) {
								BlockPos q = new BlockPos(cr.x(), cr.y() + dy, cr.z());
								if (!level.isLoaded(q) || !level.getBlockState(q).is(Blocks.BEE_NEST)) continue;
								if (q.distToCenterSqr(p.position()) < 40 * 40) swarm(level, q);
								break;
							}
						}
			}
		}
		float dt = 0.05f;
		for (Iterator<Swarm> it = SWARMS.values().iterator(); it.hasNext(); ) {
			Swarm s = it.next();
			if (s.fading >= 0) {
				if (++s.fading > 20) { it.remove(); continue; }
				show(level, s, 1f - s.fading / 20f);
				continue;
			}
			boolean near = false;
			for (ServerPlayer p : scouts) if (p.position().distanceToSqr(s.pos) < 64 * 64) near = true;
			if (!near) continue;   // (far off: it waits)
			step(level, s, scouts, dt);
			show(level, s, 1f);
		}
		hives(level, scouts);
	}

	/** Where the swarm's hive is now (its nest, the scout holding it, or lying about), and who holds it. */
	private record Hive(Vec3 at, ServerPlayer holder) { }

	private static Hive hiveOf(ServerLevel level, Swarm s, List<ServerPlayer> scouts) {
		BlockPos nest = BlockPos.of(s.nest);
		if (level.isLoaded(nest) && level.getBlockState(nest).is(Blocks.BEE_NEST)) return new Hive(Vec3.atCenterOf(nest), null);
		for (ServerPlayer p : scouts) {
			ItemStack held = p.getMainHandItem();
			if (hive(held) && nestOf(held) == s.nest) return new Hive(p.position().add(0, 1.0, 0), p);
		}
		double r = n("maxHiveDistance", 20);
		for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(s.lastSaw, s.lastSaw).inflate(r), e -> hive(e.getItem()) && nestOf(e.getItem()) == s.nest))
			return new Hive(e.position(), null);
		return null;
	}

	/** PEAK's BeeSwarm.Update/UpdateAggro/UpdateBeehavior, one tick. */
	private static void step(ServerLevel level, Swarm s, List<ServerPlayer> scouts, float dt) {
		float maxHive = n("maxHiveDistance", 20), hiveAggro = n("hiveAggroDistance", 7), deAggro = n("deAggroDistance", 6), aggro = n("defaultAggroDistance", 5);
		boolean angry = s.danger > 0;
		Hive hive = hiveOf(level, s, scouts);
		boolean canSeeHive = hive != null && hive.at().distanceTo(s.pos) <= maxHive;
		ServerPlayer target = s.target == null ? null : level.getServer().getPlayerList().getPlayer(s.target);
		// TryDeAggro
		if (target != null) {
			if (!conscious(target)) target = null;
			else if (!s.destroyed && s.lastSaw.distanceTo(s.pos) > maxHive) target = null;
			else if (center(target).distanceTo(s.pos) > (angry ? hiveAggro : deAggro)) target = null;
		}
		boolean picked = false;
		if (canSeeHive) {
			s.lastSaw = hive.at();
			if (hive.holder() != null) { s.danger = n("beesAngerTimeHiveStolen", 8); target = hive.holder(); picked = true; }
		}
		if (!picked && target == null && !(hive != null && s.lastSaw.distanceTo(s.pos) > maxHive - hiveAggro)) {
			double best = angry ? hiveAggro : aggro;
			for (ServerPlayer p : scouts) {
				double d = center(p).distanceTo(s.pos);
				if (conscious(p) && d < best) { best = d; target = p; }
			}
		}
		s.target = target != null ? target.getUUID() : null;
		angry = s.danger > 0;
		if (angry) s.danger = Math.max(0, s.danger - dt);
		// the pull: toward whom it's after, or back to where it last saw its hive (PEAK: a force times the fixed step)
		Vec3 to = (target != null ? center(target) : s.lastSaw).subtract(s.pos);
		float force = target != null && angry ? n("movementForceAngry", 280) : n("movementForce", 250);
		if (to.lengthSqr() > 1e-4) s.vel = s.vel.add(to.normalize().scale(force * 0.02f * dt));
		s.vel = s.vel.scale(1f / (1f + n("drag", 1f) * dt));
		s.pos = s.pos.add(s.vel.scale(dt));
		// scattering: nothing to chase and no hive in sight
		if (target == null && !canSeeHive) {
			s.dispersal += dt;
			if (s.dispersal >= n("beesDispersalTime", 12)) { s.fading = 0; return; }
		} else s.dispersal = 0;
		// the sting (PEAK's StatusField round the swarm)
		// (PEAK's StatusField: a sting on coming in, at most once a second, then poison while in it; none past a full bar)
		float r = stinger("radius", 1.3f), rate = angry ? n("poisonOverTimeAngry", 0.06f) : n("poisonOverTime", 0.03f);
		long now = level.getServer().getTickCount();
		for (ServerPlayer p : scouts) {
			boolean in = !p.isSpectator() && center(p).distanceTo(s.pos) <= r;
			boolean was = s.inside.contains(p.getUUID());
			if (!in) { if (was) s.inside.remove(p.getUUID()); continue; }
			if (maxedStops() && PeakStatus.sum(PeakStatus.of(p)) >= 1f) continue;
			if (!was) {
				s.inside.add(p.getUUID());
				Long last = s.stung.get(p.getUUID());
				if (last == null || now - last > stinger("entryCooldown", 1) * 20) { PeakStatus.add(p, PeakStatus.POISON, stinger("statusAmountOnEntry", 0.05f)); s.stung.put(p.getUUID(), now); }
			}
			PeakStatus.add(p, PeakStatus.POISON, rate * dt);
		}
		PeakLoops.near(level, "bees:" + s.nest, angry ? "bees_angry" : "bees_idle", angry ? SoundEvents.BEE_LOOP_AGGRESSIVE : SoundEvents.BEE_LOOP, s.pos, angry ? 0.9f : 0.6f, 32);
	}

	/** The swarm drawn: specks of yellow and black round where it is (fewer as it scatters). */
	private static void show(ServerLevel level, Swarm s, float strength) {
		int n = Math.max(1, Math.round(12 * strength));
		boolean angry = s.danger > 0;
		level.sendParticles(new DustParticleOptions(angry ? 0xFFB000 : 0xE8C33A, 0.6f), s.pos.x, s.pos.y, s.pos.z, n, 0.55, 0.4, 0.55, 0);
		level.sendParticles(new DustParticleOptions(0x1A1408, 0.5f), s.pos.x, s.pos.y, s.pos.z, Math.max(1, n * 3 / 5), 0.55, 0.4, 0.55, 0);
	}

	private static Vec3 center(ServerPlayer p) { return p.position().add(0, 0.9, 0); }

	private static boolean conscious(ServerPlayer p) { return p.isAlive() && !p.isSpectator() && !PeakStatus.out(p) && !PeakStatus.dead(p); }

	// ---- the beehive as a thing ----

	private static final Map<Integer, Double> FALLING = new HashMap<>();

	/** A beehive lying about breaks if it lands hard (PEAK: 15 m/s), into four honeycombs, and its bees are angry for 20 s. */
	private static void hives(ServerLevel level, List<ServerPlayer> scouts) {
		java.util.Set<ItemEntity> all = new java.util.LinkedHashSet<>();
		for (ServerPlayer p : scouts) all.addAll(level.getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(32), e -> hive(e.getItem())));
		for (ItemEntity e : all) {
				double vy = e.getDeltaMovement().length();
				Double was = FALLING.put(e.getId(), vy);
				if (was == null || !e.onGround() || was < 15 / 20.0) continue;
				FALLING.remove(e.getId());
				long nest = nestOf(e.getItem());
				Vec3 at = e.position();
				e.discard();
				for (int i = 0; i < 4; i++) {
					ItemStack comb = PeakItems.stack("Item_Honeycomb");
					if (comb.isEmpty()) break;
					ItemEntity c = new ItemEntity(level, at.x, at.y + 0.2, at.z, comb);
					c.setDeltaMovement((level.getRandom().nextFloat() - 0.5) * 0.3, 0.25, (level.getRandom().nextFloat() - 0.5) * 0.3);
					c.setUnlimitedLifetime();
					level.addFreshEntity(c);
				}
				level.playSound(null, BlockPos.containing(at), SoundEvents.BEEHIVE_SHEAR, net.minecraft.sounds.SoundSource.BLOCKS, 1.2f, 0.7f);
				Swarm s = SWARMS.get(nest);
				if (s != null && s.pos.distanceTo(at) <= n("hiveAggroDistance", 7)) { s.destroyed = true; s.lastSaw = at; s.danger = n("beesAngerTimeHiveDestroyed", 12); }
		}
		if (level.getServer().getTickCount() % 200 == 0) FALLING.keySet().removeIf(id -> level.getEntity(id) == null);
	}

	/** (The dev test: a nest's swarm let out now.) */
	static void debugSwarm(ServerLevel level, BlockPos nest) { swarm(level, nest); }

	/** (The dev test: the swarm at this nest — where, whom it's after, angry — or null.) */
	static String debug(BlockPos nest) {
		Swarm s = SWARMS.get(nest.asLong());
		if (s == null) return null;
		return String.format("at %.1f %.1f %.1f, after %s, angry %s", s.pos.x, s.pos.y, s.pos.z, s.target == null ? "nobody" : s.target.toString().substring(0, 8), s.danger > 0);
	}

	static Vec3 debugPos(BlockPos nest) { Swarm s = SWARMS.get(nest.asLong()); return s == null ? null : s.pos; }
}
