package gamemashup.minepeak;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.animal.pig.PigVariants;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * PEAK's creatures, as Minecraft's mobs made over (each named as PEAK's, staying where it was put: no despawning), where
 * PEAK has them (exported: "creatures"):
 * - Scorpion: a cave spider (PEAK's sting poisons); Beetle: a silverfish; Antlion (the Mesa's sand pits): silverfish;
 * - Mushroom zombie (the Roots; a spawner makes one a third of the time, as PEAK's SPAWN_CHANCE): a zombie with a red
 *   mushroom for a head (it keeps it out of the sun too); its bite brings on PEAK's spores (nausea, poison);
 * - Beehive: a bee nest, and its swarm (PeakBees: PEAK's, not Minecraft's bees);
 * - Capybara (PEAK's sit in hot springs, doing nothing): a brown pig that stays put;
 * - Tongue frog (the Roots): a frog that, now and then, shoots its tongue at a scout in front of it within 12 blocks and
 *   pulls them in (PEAK's FrogTongue: once in 10 s each).
 */
final class PeakCreatures {
	private PeakCreatures() { }

	static final String TAG = "minepeak.creature", TONGUE = "minepeak.tongue", ZOMBIE = "minepeak.mushroom_zombie";

	/** The chunk's creatures, into the world as it's made. */
	static void spawn(WorldGenRegion region, IslandData data) {
		ChunkPos cp = region.getCenter();
		RandomSource random = RandomSource.create(cp.pack() * 977 + 13);
		for (PeakThings.Creature c : PeakThings.creatures(data, cp)) {
			switch (c.kind()) {
				case "Scorpion" -> mob(region, EntityTypes.CAVE_SPIDER, c, "Scorpion");
				case "Beetle" -> mob(region, EntityTypes.SILVERFISH, c, "Beetle");
				case "Antlion" -> {
					// at the bottom of its pit (dug as the chunk was decorated, before this)
					int ground = groundNear(region, c.x(), c.y(), c.z());
					var at = ground != Integer.MIN_VALUE ? new PeakThings.Creature(c.kind(), c.x(), ground + 1, c.z(), c.yaw()) : c;
					var a = mob(region, EntityTypes.SILVERFISH, at, "Antlion");
					if (a != null) a.setNoAi(true);
				}
				case "MushroomZombie" -> zombie(region, c);
				case "MushroomZombieSpawner" -> { if (random.nextFloat() < 1f / 3f) zombie(region, c); }
				case "Capybara" -> {
					var pig = mob(region, EntityTypes.PIG, c, "Capybara");
					if (pig != null) {
						pig.setNoAi(true);
						region.registryAccess().lookupOrThrow(Registries.PIG_VARIANT).get(PigVariants.COLD).ifPresent(v -> pig.setComponent(DataComponents.PIG_VARIANT, v));
					}
				}
				case "FrogTongue" -> { var f = mob(region, EntityTypes.FROG, c, "Tongue Frog"); if (f != null) f.addTag(TONGUE); }
				default -> { }
			}
		}
	}

	/**
	 * The creatures' blocks, as the chunk is decorated (the spawn step, where the creatures come, may not set any blocks:
	 * Minecraft refuses them there): the bee nests, and the antlions' pits (an antlion's pit reaches into the chunks
	 * round it: each chunk digs its own part).
	 */
	static void blocks(net.minecraft.world.level.WorldGenLevel level, net.minecraft.world.level.chunk.ChunkAccess chunk, IslandData data) {
		ChunkPos cp = chunk.getPos();
		for (int dx = -1; dx <= 1; dx++)
			for (int dz = -1; dz <= 1; dz++)
				for (PeakThings.Creature c : PeakThings.creatures(data, new ChunkPos(cp.x() + dx, cp.z() + dz))) {
					if (c.kind().equals("Antlion")) pit(level, cp, c);
					else if (c.kind().equals("Beehive") && dx == 0 && dz == 0) nest(level, c);
				}
	}

	/** The ground's top near y in a column: the first solid block with room above, looking down from y + 6 to y - 8. */
	static int groundNear(net.minecraft.world.level.LevelReader level, int x, int y, int z) {
		for (int yy = y + 6; yy >= y - 8; yy--) {
			BlockPos q = new BlockPos(x, yy, z);
			if (!level.getBlockState(q).canBeReplaced() && level.getBlockState(q.above()).canBeReplaced()) return yy;
		}
		return Integer.MIN_VALUE;
	}

	private static <T extends Mob> T mob(WorldGenRegion region, EntityType<T> type, PeakThings.Creature c, String name) {
		BlockPos p = PeakThings.stand(region, c.x(), c.y(), c.z());
		if (p == null) return null;
		T m = type.create(region.getLevel(), EntitySpawnReason.CHUNK_GENERATION);
		if (m == null) return null;
		m.snapTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 180f - c.yaw(), 0f);
		m.finalizeSpawn(region, region.getCurrentDifficultyAt(p), EntitySpawnReason.CHUNK_GENERATION, null);
		m.setPersistenceRequired();
		m.setCustomName(Component.literal(name));
		m.addTag(TAG);
		region.addFreshEntity(m);
		return m;
	}

	/**
	 * An antlion's pit: a cone of sand 16 across and 4 deep round it (PeakHazards' PIT_RADIUS, PIT_DEPTH), the part of
	 * it in this chunk.
	 */
	private static void pit(net.minecraft.world.level.WorldGenLevel level, ChunkPos cp, PeakThings.Creature c) {
		int R = PeakHazards.PIT_RADIUS, D = PeakHazards.PIT_DEPTH;
		for (int dx = -R; dx <= R; dx++)
			for (int dz = -R; dz <= R; dz++) {
				double r = Math.sqrt(dx * dx + dz * dz);
				if (r > R) continue;
				int x = c.x() + dx, z = c.z() + dz;
				if (x >> 4 != cp.x() || z >> 4 != cp.z()) continue;
				int top = groundNear(level, x, c.y(), z);   // (the ground at the antlion's height: it may be under an overhang)
				if (top == Integer.MIN_VALUE) continue;
				int depth = (int) Math.round(D * (1 - r / R));
				for (int y = top; y > top - depth; y--) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
				for (int y = top - depth; y >= top - depth - 1; y--) level.setBlock(new BlockPos(x, y, z), Blocks.SAND.defaultBlockState(), 2);
			}
	}

	/** (The dev test: an antlion's pit dug here, as the world digs them, and its antlion at the bottom.) */
	static net.minecraft.world.entity.monster.Silverfish testAntlion(net.minecraft.server.level.ServerLevel level, BlockPos at) {
		int R = PeakHazards.PIT_RADIUS, D = PeakHazards.PIT_DEPTH;
		BlockPos bottom = at;
		for (int dx = -R; dx <= R; dx++)
			for (int dz = -R; dz <= R; dz++) {
				double r = Math.sqrt(dx * dx + dz * dz);
				if (r > R) continue;
				int depth = (int) Math.round(D * (1 - r / R));
				for (int y = at.getY(); y > at.getY() - depth; y--) level.setBlock(new BlockPos(at.getX() + dx, y, at.getZ() + dz), Blocks.AIR.defaultBlockState(), 2);
				for (int y = at.getY() - depth; y >= at.getY() - depth - 1; y--) level.setBlock(new BlockPos(at.getX() + dx, y, at.getZ() + dz), Blocks.SAND.defaultBlockState(), 2);
				if (dx == 0 && dz == 0) bottom = new BlockPos(at.getX(), at.getY() - depth + 1, at.getZ());
			}
		var a = EntityTypes.SILVERFISH.create(level, EntitySpawnReason.TRIGGERED);
		if (a == null) return null;
		a.snapTo(bottom.getX() + 0.5, bottom.getY(), bottom.getZ() + 0.5, 0, 0);
		a.setPersistenceRequired();
		a.setCustomName(Component.literal("Antlion"));
		a.addTag(TAG);
		a.setNoAi(true);
		level.addFreshEntity(a);
		return a;
	}

	/** (The dev test: a mushroom zombie made here, asleep, as the world makes them.) */
	static net.minecraft.world.entity.monster.zombie.Zombie testZombie(net.minecraft.server.level.ServerLevel level, Vec3 at) {
		var z = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.TRIGGERED);
		if (z == null) return null;
		z.snapTo(at.x, at.y, at.z, 0, 0);
		z.setPersistenceRequired();
		z.setCustomName(Component.literal("Mushroom Zombie"));
		z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.RED_MUSHROOM_BLOCK));
		z.addTag(TAG); z.addTag(ZOMBIE); z.addTag(ZOMBIE + ".asleep");
		z.setNoAi(true);
		level.addFreshEntity(z);
		return z;
	}

	private static void zombie(WorldGenRegion region, PeakThings.Creature c) {
		var z = mob(region, EntityTypes.ZOMBIE, c, "Mushroom Zombie");
		if (z == null) return;
		z.setBaby(false);
		z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.RED_MUSHROOM_BLOCK));
		z.setDropChance(EquipmentSlot.HEAD, 0f);
		z.addTag(ZOMBIE);
		z.setNoAi(true);   // (asleep: PEAK's wake when a scout looks at them)
		z.addTag(ZOMBIE + ".asleep");
	}

	private static void nest(net.minecraft.world.level.WorldGenLevel level, PeakThings.Creature c) {
		// (PEAK's hang off the trees' branches: the nearest room within 2, air or leaves, as near its height as can be)
		BlockPos p = null;
		double best = Double.MAX_VALUE;
		for (int dx = -2; dx <= 2; dx++)
			for (int dz = -2; dz <= 2; dz++)
				for (int dy = -2; dy <= 3; dy++) {
					BlockPos q = new BlockPos(c.x() + dx, c.y() + dy, c.z() + dz);
					var s = level.getBlockState(q);
					if (!(s.isAir() || s.canBeReplaced() || s.is(net.minecraft.tags.BlockTags.LEAVES))) continue;
					double d = dx * dx + dz * dz + dy * dy * 2;
					if (d < best) { best = d; p = q; }
				}
		if (p == null) return;
		level.setBlock(p, Blocks.BEE_NEST.defaultBlockState().setValue(net.minecraft.world.level.block.BeehiveBlock.FACING, PeakThings.facing(c.yaw())), 2);   // (its bees: PEAK's swarm, PeakBees)
	}

	// ---- while the world runs ----

	private static final java.util.Map<java.util.UUID, Long> FROGGED = new java.util.HashMap<>();

	private static final java.util.Map<Integer, Long> WAKING = new java.util.HashMap<>(), LUNGED = new java.util.HashMap<>();
	private static final java.util.Map<String, Long> FLASHED = new java.util.HashMap<>();

	/** The mushroom zombies: asleep till a scout within 30 looks at them; 5 s waking; awake, a lunge from 10 off. */
	private static void zombies(MinecraftServer server) {
		long now = server.getTickCount();
		var level = server.overworld();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!PeakWorld.scout(p)) continue;
			for (var z : level.getEntitiesOfClass(net.minecraft.world.entity.monster.zombie.Zombie.class, p.getBoundingBox().inflate(30), e -> e.entityTags().contains(ZOMBIE))) {
				if (z.entityTags().contains(ZOMBIE + ".asleep")) {
					Vec3 to = z.getEyePosition().subtract(p.getEyePosition());
					if (!WAKING.containsKey(z.getId()) && p.getLookAngle().dot(to.normalize()) > Math.cos(Math.toRadians(30)) && p.hasLineOfSight(z)) {
						WAKING.put(z.getId(), now + 100);
						level.playSound(null, z.blockPosition(), SoundEvents.ZOMBIE_AMBIENT, SoundSource.HOSTILE, 1.2f, 0.6f);
					}
					Long at = WAKING.get(z.getId());
					if (at != null && now >= at) { z.removeTag(ZOMBIE + ".asleep"); z.setNoAi(false); z.setTarget(p); WAKING.remove(z.getId()); }
					continue;
				}
				// the lunge: within 10, on the ground, every 6 s
				double d = z.distanceTo(p);
				if (z.getTarget() == p && d < 10 && d > 2.5 && z.onGround() && now - LUNGED.getOrDefault(z.getId(), -1000L) > 120) {
					Vec3 dir = p.position().subtract(z.position()).normalize();
					z.setDeltaMovement(dir.x * 1.1, 0.45, dir.z * 1.1);
					LUNGED.put(z.getId(), now);
				}
			}
		}
	}

	/** The Alpine's flash plants: come within 2.5 and one flashes, blinding you (PEAK: up to 45 s, less further off). */
	private static void flashPlants(MinecraftServer server, IslandData data) {
		long now = server.getTickCount();
		var level = server.overworld();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!PeakWorld.scout(p)) continue;
			for (BlockPos q : BlockPos.betweenClosed(p.blockPosition().offset(-2, -1, -2), p.blockPosition().offset(2, 2, 2))) {
				if (!level.getBlockState(q).is(Blocks.TORCHFLOWER) || now - FLASHED.getOrDefault(q.toShortString(), -10000L) < 600) continue;
				FLASHED.put(q.toShortString(), now);
				double d = p.position().distanceTo(Vec3.atCenterOf(q));
				float factor = (float) Math.pow(Math.max(0, 1 - d / 4.0), 0.3);
				level.sendParticles(net.minecraft.core.particles.ColorParticleOption.create(net.minecraft.core.particles.ParticleTypes.FLASH, 0xFFFFFFFF), q.getX() + 0.5, q.getY() + 0.6, q.getZ() + 0.5, 1, 0, 0, 0, 0);
				level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD, q.getX() + 0.5, q.getY() + 0.6, q.getZ() + 0.5, 30, 0.4, 0.4, 0.4, 0.2);
				level.playSound(null, q, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.BLOCKS, 1.5f, 1.4f);
				for (ServerPlayer o : server.getPlayerList().getPlayers())
					if (PeakWorld.scout(o) && o.position().distanceTo(Vec3.atCenterOf(q)) < 4) PeakStatus.timed(o, "Blind", Math.max(3, 45 * factor));
				break;
			}
		}
	}

	/** A mandrake screaming, or waiting to: who has it (a scout's hand, or one in the air), and its timings. */
	private static final class Scream {
		float waitBefore, time, screaming, aoe;
		long seen;
	}

	private static final java.util.Map<String, Scream> SCREAMS = new java.util.HashMap<>();

	/**
	 * PEAK's mandrake (Mandrake): held, or flying through the air, near a scout who's up (10), it screams: the first time
	 * after 2 s (screamWaitMax), then after 0.75 to 2 s each time; each scream lasts 3 s, and every 0.25 s of it (its AOE's
	 * TimeEvent) everyone within 10 grows drowsier (0.05, less further off: PEAK's AOE factor). A cooked one is quiet.
	 * One that's lying on the ground is quiet too: pick a medicinal root that's really a mandrake (PeakInventory) and it
	 * starts.
	 */
	private static void mandrakes(MinecraftServer server) {
		var level = server.overworld();
		long now = server.getTickCount();
		java.util.List<ServerPlayer> scouts = new java.util.ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) if (PeakWorld.scout(p)) scouts.add(p);
		if (scouts.isEmpty()) return;
		java.util.Map<String, Vec3> sources = new java.util.HashMap<>();
		java.util.Map<String, ItemStack> stacks = new java.util.HashMap<>();
		for (ServerPlayer p : scouts) {
			ItemStack held = p.getMainHandItem();
			if (screamer(held) && !p.isSpectator()) { sources.put("p" + p.getUUID(), p.getEyePosition()); stacks.put("p" + p.getUUID(), held); }
			for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, p.getBoundingBox().inflate(20), e -> !e.onGround() && screamer(e.getItem()))) {
				sources.put("e" + e.getId(), e.position()); stacks.put("e" + e.getId(), e.getItem());
			}
		}
		for (var en : sources.entrySet()) {
			ItemStack st = stacks.get(en.getKey());
			var def = PeakItems.def(st);
			var m = def != null ? def.part("Mandrake") : null;
			var aoe = def != null ? def.part("AOE") : null;
			var te = def != null ? def.part("TimeEvent") : null;
			float waitMin = m != null ? m.num("screamWaitMin", 0.75f) : 0.75f, waitMax = m != null ? m.num("screamWaitMax", 2f) : 2f;
			float near = m != null ? m.num("nearPlayersDistance", 10f) : 10f;
			Vec3 at = en.getValue();
			Scream sc = SCREAMS.get(en.getKey());
			if (sc == null) {
				sc = new Scream();
				var t = PeakItems.tag(st);
				sc.waitBefore = t.getBooleanOr("used", false) ? waitMin + level.getRandom().nextFloat() * (waitMax - waitMin) : waitMax;
				if (!t.getBooleanOr("used", false)) { t.putBoolean("used", true); PeakItems.save(st, t); }
				SCREAMS.put(en.getKey(), sc);
			}
			sc.seen = now;
			boolean close = false;
			for (ServerPlayer o : scouts) if (!o.isSpectator() && !PeakStatus.out(o) && o.position().add(0, 0.9, 0).distanceTo(at) < near) close = true;
			if (sc.screaming > 0) {
				sc.screaming -= 0.05f;
				sc.aoe += 0.05f;
				float rate = te != null ? te.num("rate", 0.25f) : 0.25f;
				if (sc.aoe >= rate) {
					sc.aoe -= rate;
					float range = aoe != null ? aoe.num("range", 10f) : 10f, amount = aoe != null ? aoe.num("statusAmount", 0.05f) : 0.05f;
					float minFactor = aoe != null ? aoe.num("minFactor", 0.2f) : 0.2f, pow = aoe != null ? aoe.num("factorPow", 0.5f) : 0.5f;
					int status = aoe != null ? PeakStatus.index(aoe.str("statusType", "Drowsy")) : PeakStatus.DROWSY;
					for (ServerPlayer o : scouts) {
						if (o.isSpectator() || PeakStatus.carried(o)) continue;
						double d = o.position().add(0, 0.9, 0).distanceTo(at);
						if (d >= range) continue;
						float factor = (float) Math.pow(1 - d / range, pow);
						if (factor < minFactor) continue;
						PeakStatus.add(o, status < 0 ? PeakStatus.DROWSY : status, amount * factor);
					}
					level.sendParticles(new net.minecraft.core.particles.ShriekParticleOption(0), at.x, at.y + 0.3, at.z, 1, 0, 0, 0, 0);
				}
				if (sc.screaming <= 0) { sc.time = 0; sc.waitBefore = waitMin + level.getRandom().nextFloat() * (waitMax - waitMin); }
			} else if (close) {
				sc.time += 0.05f;
				if (sc.time > sc.waitBefore) {
					sc.screaming = 3f;
					sc.aoe = 0;
					PeakSfx.play(level, at, "mandrake_scream", SoundEvents.GHAST_SCREAM, 1.2f, 1f);
				}
			}
		}
		SCREAMS.values().removeIf(sc -> now - sc.seen > 40);
	}

	/** An uncooked mandrake (PEAK's: cooked, its scream's scripts are off). */
	private static boolean screamer(ItemStack s) {
		var d = PeakItems.def(s);
		return d != null && d.has("Mandrake") && PeakItems.cooked(s) == 0;
	}

	/** (The dev test: is a mandrake held by p screaming?) */
	static boolean screaming(ServerPlayer p) { Scream s = SCREAMS.get("p" + p.getUUID()); return s != null && s.screaming > 0; }

	/** The world stopping: the timings forgotten (they count the server's ticks, which start again at 0). */
	static void stop() { FROGGED.clear(); WAKING.clear(); LUNGED.clear(); FLASHED.clear(); SCREAMS.clear(); }

	/** Every tick: the zombies, the mandrakes; every second: the tongue frogs' licks, flash plants. */
	static void tick(MinecraftServer server) {
		zombies(server);
		mandrakes(server);
		if (server.getTickCount() % 20 != 0) return;
		IslandData data = MinePeakServer.island(server);
		if (data != null) flashPlants(server, data);
		long now = server.getTickCount();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.isSpectator() || p.isCreative() || now - FROGGED.getOrDefault(p.getUUID(), -10_000L) < 200) continue;
			var frogs = p.level().getEntitiesOfClass(Frog.class, new AABB(p.blockPosition()).inflate(12), f -> f.entityTags().contains(TONGUE) && f.hasLineOfSight(p));
			for (Frog f : frogs) {
				Vec3 to = p.position().subtract(f.position());
				Vec3 facing = Vec3.directionFromRotation(0, f.getYRot());
				if (to.length() < 3 || to.normalize().dot(facing) < 0.3 || p.getRandom().nextInt(4) != 0) continue;
				// the tongue: pulled in toward the frog, lifted a little (PEAK: off any handhold, a moment on the ground)
				Vec3 pull = to.normalize().scale(-1.3).add(0, 0.45, 0);
				p.setDeltaMovement(pull);
				p.hurtMarked = true;
				p.level().playSound(null, f.blockPosition(), SoundEvents.FROG_TONGUE, SoundSource.HOSTILE, 1.5f, 1f);
				FROGGED.put(p.getUUID(), now);
				break;
			}
		}
	}

	/**
	 * A creature's bite, PEAK's way: a mushroom zombie's (spores now and over time, and a stun), a scorpion's (PEAK's
	 * poison: at least half a bar, or enough to fill it), on the scout's bar (PeakStatus).
	 */
	static void bitten(LivingEntity victim, LivingEntity by) {
		if (by == null || !(victim instanceof net.minecraft.server.level.ServerPlayer p) || !PeakWorld.scout(p)) return;
		if (by.entityTags().contains(ZOMBIE)) {
			PeakStatus.add(p, PeakStatus.SPORES, 0.15f);
			com.google.gson.JsonObject a = new com.google.gson.JsonObject();
			a.addProperty("$type", "Affliction_SporesOverTime"); a.addProperty("totalTime", 12); a.addProperty("statusPerSecond", 0.01); a.addProperty("delayBeforeEffect", 2);
			PeakStatus.affliction(p, a);
			p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 4));
			p.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 100));
			PeakStatus.of(p).zombieBit = true;
		} else if ("Scorpion".equals(by.getCustomName() == null ? "" : by.getCustomName().getString())) {
			p.removeEffect(MobEffects.POISON);
			float x = Math.max(0.5f, 1 - PeakStatus.sum(PeakStatus.of(p)) + 0.05f);
			PeakStatus.add(p, PeakStatus.POISON, 0.025f);
			com.google.gson.JsonObject a = new com.google.gson.JsonObject();
			a.addProperty("$type", "Affliction_PoisonOverTime"); a.addProperty("totalTime", 10); a.addProperty("statusPerSecond", x / 10f); a.addProperty("delayBeforeEffect", 0);
			PeakStatus.affliction(p, a);
		}
	}

	/** A beetle's charge (PEAK's Beetle): it bowls you over, a long way, and doesn't hurt. False: no Minecraft damage. */
	static boolean beetle(LivingEntity victim, net.minecraft.world.damagesource.DamageSource source) {
		if (!(victim instanceof net.minecraft.server.level.ServerPlayer p) || !PeakWorld.scout(p) || !(source.getEntity() instanceof LivingEntity by)) return true;
		if (!"Beetle".equals(by.getCustomName() == null ? "" : by.getCustomName().getString())) return true;
		Vec3 dir = p.position().subtract(by.position()).multiply(1, 0, 1).normalize();
		p.setDeltaMovement(dir.x * 1.6, 0.8, dir.z * 1.6);
		p.hurtMarked = true;
		p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 4));
		p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.HOSTILE, 1.2f, 0.7f);
		return false;
	}
}
