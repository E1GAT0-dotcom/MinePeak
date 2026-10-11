package gamemashup.minepeak;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * PEAK's body, on the server: the statuses that fill the stamina bar from its right (PEAK's CharacterAfflictions:
 * Injury, Hunger, Cold, Poison, Crab, Curse, Drowsy, Weight, Hot, Thorns, Spores, Web, Arrow, Petrify, FlyTrap; 1 is the
 * whole bar), with PEAK's own rates (exported with the items: how fast poison, drowsiness, heat, spores and thorns wear
 * off). Minecraft's health and hunger stand still for scouts; what hurts gives Injury instead (falls by PEAK's own sum).
 * - Hunger grows 0.0005 a second while awake (not by a lit campfire).
 * - The statuses' sum past 0.99: after 5 s the scout passes out (drops what it holds, lies down, can't move). It wakes
 *   when the sum is back under 1 (a friend feeds or heals it: an item used on it), or dies after 60 s (10 s when nobody
 *   else is up; being carried stops the clock; holding sneak gives up). A friend picks it up with an empty hand.
 * - Dead, a scout leaves its bones and watches as a ghost (spectator) till a statue brings it back (PeakStatue); when
 *   every scout is dead, the run ends and all start again at their last campfire.
 * - Timed effects (PEAK's afflictions): poison over time, cold or drowsiness over time, infinite stamina, faster,
 *   low gravity, invincible, no hunger, sunscreen, numb, blind, climbing chalk.
 * Each scout's state goes to its client (ScoutState) for the stamina bar.
 */
public final class PeakStatus {
	private PeakStatus() { }

	static final int INJURY = 0, HUNGER = 1, COLD = 2, POISON = 3, CRAB = 4, CURSE = 5, DROWSY = 6, WEIGHT = 7, HOT = 8, THORNS = 9,
		SPORES = 10, WEB = 11, ARROW = 12, PETRIFY = 13, FLYTRAP = 14, N = 15;
	static final String[] NAMES = { "Injury", "Hunger", "Cold", "Poison", "Crab", "Curse", "Drowsy", "Weight", "Hot", "Thorns", "Spores", "Web", "Arrow", "Petrify", "FlyTrap" };

	static int index(String name) {
		for (int i = 0; i < N; i++) if (NAMES[i].equalsIgnoreCase(name)) return i;
		return name != null && name.equalsIgnoreCase("Sleepy") ? DROWSY : name != null && name.equalsIgnoreCase("Heat") ? HOT : -1;
	}

	// flags sent to the client
	static final int F_OUT = 1, F_DEAD = 2, F_CARRIED = 4, F_INFINITE = 8, F_FASTER = 16, F_INVINCIBLE = 32, F_NUMB = 64, F_NO_HUNGER = 128,
		F_LOW_GRAVITY = 256, F_CARRYING = 512, F_SUNSCREEN = 1024, F_WARMING = 2048;

	public record ScoutState(float[] status, float passOut, float dying, int flags, float climbMult, float staminaDrain, int balloons) implements CustomPacketPayload {
		public static final Type<ScoutState> TYPE = new Type<>(MinePeak.id("scout"));
		public static final StreamCodec<FriendlyByteBuf, ScoutState> CODEC = StreamCodec.of((b, s) -> {
			for (int i = 0; i < N; i++) b.writeFloat(s.status[i]);
			b.writeFloat(s.passOut); b.writeFloat(s.dying); b.writeInt(s.flags); b.writeFloat(s.climbMult); b.writeFloat(s.staminaDrain); b.writeVarInt(s.balloons);
		}, b -> {
			float[] st = new float[N];
			for (int i = 0; i < N; i++) st[i] = b.readFloat();
			return new ScoutState(st, b.readFloat(), b.readFloat(), b.readInt(), b.readFloat(), b.readFloat(), b.readVarInt());
		});
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Bonus stamina (PEAK's extra stamina: the morale boost, food...), or a drain of it (negative), to the client. */
	public record Bonus(float amount) implements CustomPacketPayload {
		public static final Type<Bonus> TYPE = new Type<>(MinePeak.id("bonus"));
		public static final StreamCodec<FriendlyByteBuf, Bonus> CODEC = StreamCodec.of((b, s) -> b.writeFloat(s.amount), b -> new Bonus(b.readFloat()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** A timed effect (PEAK's Affliction_*): its kind, time left, and numbers. */
	static final class Timed {
		final String type; float left, elapsed; final float total; final float a, b, c; final JsonObject extra;
		Timed(String type, float total, float a, float b, float c, JsonObject extra) { this.type = type; this.total = total; this.left = total; this.a = a; this.b = b; this.c = c; this.extra = extra; }
	}

	/** A scout: its statuses and everything PEAK keeps on it. */
	static final class Scout {
		final float[] s = new float[N];
		final double[] lastAdded = new double[N];
		final List<Timed> timed = new ArrayList<>();
		float passOut, dying;
		boolean out, dead, byFire;
		float windGrab = 1;
		double outAt, nowS, lastSound;
		UUID carrier, carrying;
		BlockPos flag; float[] flagStatus;
		// the run's record (the end screen, the badges)
		boolean passedOutEver, fellHurt, ateCooked, ateAny, atePackaged, zombieBit, startCursed;
		int revives, luggage, clownLuggage, pitons, rope, cooked, fires;
		float maxHeat, maxCold, maxSpores, highest;
		long started;
		final java.util.Set<String> berries = new java.util.HashSet<>(), mushrooms = new java.util.HashSet<>();
		int sent;
	}

	private static final Map<UUID, Scout> SCOUTS = new HashMap<>();
	private static boolean loaded;
	private static final java.util.Set<UUID> DYING = new java.util.HashSet<>();   // (dying by PEAK's rules: let Minecraft kill them)

	static Scout of(Player p) { return SCOUTS.computeIfAbsent(p.getUUID(), u -> { Scout s = new Scout(); s.started = System.currentTimeMillis(); return s; }); }

	static float get(Player p, int i) { return of(p).s[i]; }

	static float sum(Scout sc) { float t = 0; for (float v : sc.s) t += v; return t; }

	static boolean out(Player p) { Scout s = SCOUTS.get(p.getUUID()); return s != null && (s.out || s.carrier != null); }

	static boolean has(Scout sc, String type) { for (Timed t : sc.timed) if (t.type.equals(type)) return true; return false; }

	// ---- statuses, PEAK's way ----

	/** PEAK's AddStatus: Hot and Cold cancel; invincible scouts take nothing but curse; Injury at most 1, the sum at most 2. */
	static void add(Player p, int i, float amount) {
		if (amount <= 0 || i < 0 || !PeakWorld.is(p.level())) return;
		Scout sc = of(p);
		if (sc.dead || sc.carrier != null && i != CURSE) return;
		if (has(sc, "Invincibility") && i != CURSE && i != PETRIFY) return;
		if (i == HOT && sc.s[COLD] > 0) { float c = Math.min(sc.s[COLD], amount); sc.s[COLD] -= c; amount -= c; }
		if (i == COLD && sc.s[HOT] > 0) { float c = Math.min(sc.s[HOT], amount); sc.s[HOT] -= c; amount -= c; }
		if (i == HOT && has(sc, "Sunscreen")) return;
		float room = 2f - sum(sc);
		amount = Math.min(amount, Math.max(0, room));
		if (i == INJURY) amount = Math.min(amount, 1f - sc.s[INJURY]);
		if (amount <= 0) return;
		sc.s[i] += amount;
		sc.lastAdded[i] = sc.nowS;
		if (amount >= 0.04f && p instanceof ServerPlayer sp && sc.nowS - sc.lastSound > 0.5) {
			String role = switch (i) {
				case INJURY -> amount > 0.75f ? "injury_heavy" : amount > 0.4f ? "injury_mid" : "injury_small";
				case COLD -> "injury_ice"; case HOT -> "injury_fire"; case POISON -> "injury_poison"; case HUNGER -> "injury_hunger";
				case THORNS -> "injury_thorns"; case SPORES -> "injury_spore"; default -> null;
			};
			if (role != null) { sc.lastSound = sc.nowS; PeakSfx.play(sp.level(), sp.position(), role, null, 1f, 1f); }
		}
		if (i == HOT) sc.maxHeat = Math.max(sc.maxHeat, sc.s[HOT]);
		if (i == COLD) sc.maxCold = Math.max(sc.maxCold, sc.s[COLD]);
		if (i == SPORES) sc.maxSpores = Math.max(sc.maxSpores, sc.s[SPORES]);
	}

	/** PEAK's SubtractStatus (curing poison cures spores too). */
	static void sub(Player p, int i, float amount) {
		if (amount <= 0 || i < 0) return;
		Scout sc = of(p);
		sc.s[i] = Math.max(0, sc.s[i] - amount);
		if (i == POISON) {
			sc.s[SPORES] = Math.max(0, sc.s[SPORES] - amount);
			sc.timed.removeIf(t -> t.type.equals("PoisonOverTime"));
		}
	}

	static void clear(Scout sc, boolean keepCurse) {
		for (int i = 0; i < N; i++) if (!(keepCurse && i == CURSE) && i != WEIGHT) sc.s[i] = 0;
	}

	static void bonus(ServerPlayer p, float amount) {
		if (amount != 0) ServerPlayNetworking.send(p, new Bonus(amount));
	}

	// ---- PEAK's timed effects ----

	/**
	 * One of PEAK's afflictions (its settings as exported: "$type" Affliction_PoisonOverTime and so on), on the scout.
	 * Instant ones happen at once.
	 */
	static void affliction(ServerPlayer p, JsonObject a) {
		if (a == null || !a.has("$type")) return;
		Scout sc = of(p);
		String type = a.get("$type").getAsString().replace("Affliction_", "");
		float total = f(a, "totalTime", 0);
		switch (type) {
			case "AdjustStatus" -> {
				int i = index(s(a, "statusType"));
				float amt = f(a, "statusAmount", 0);
				if (amt >= 0) add(p, i, amt); else sub(p, i, -amt);
			}
			case "AddBonusStamina" -> bonus(p, f(a, "staminaAmount", 0));
			case "ClearAllStatus" -> clear(sc, !a.has("excludeCurse") || a.get("excludeCurse").getAsBoolean());
			case "HealAll" -> {
				float left = f(a, "maxHealing", 1);
				for (int i : new int[] { INJURY, SPORES, POISON, COLD, HOT, DROWSY }) { float c = Math.min(left, sc.s[i]); sc.s[i] -= c; left -= c; }
			}
			case "Chaos" -> {
				var r = p.getRandom();
				for (int i : new int[] { INJURY, HUNGER, COLD, POISON, DROWSY, HOT, SPORES }) {
					float v = (float) (f(a, "statusAmountAverage", 0.1f) + r.nextGaussian() * f(a, "statusAmountStandardDeviation", 0.05f));
					if (v > 0) add(p, i, v); else sub(p, i, -v);
				}
				bonus(p, Math.max(0, (float) (f(a, "averageBonusStamina", 0.2f) + r.nextGaussian() * f(a, "bonusStaminaStandardDeviation", 0.1f))));
			}
			case "FasterBoi" -> {
				sub(p, DROWSY, 0.5f);
				sc.timed.add(new Timed(type, total, f(a, "moveSpeedMod", 0.5f), f(a, "climbSpeedMod", 1f), f(a, "drowsyOnEnd", 0), a));
			}
			case "PoisonOverTime", "SporesOverTime" -> sc.timed.add(new Timed(type, total + f(a, "delayBeforeEffect", 0), f(a, "statusPerSecond", 0.02f), f(a, "delayBeforeEffect", 0), 0, a));
			case "AdjustColdOverTime", "AdjustDrowsyOverTime" -> sc.timed.add(new Timed(type, total, f(a, "statusPerSecond", 0), 0, 0, a));
			case "InfiniteStamina" -> sc.timed.add(new Timed(type, total, f(a, "climbDelay", 0), 0, 0, a));
			case "LowGravity" -> sc.timed.add(new Timed(type, total, f(a, "lowGravAmount", 3), 0, 0, a));
			case "ClimbingChalk" -> sc.timed.add(new Timed(type, total, f(a, "climbStaminaMultiplier", 0.5f), 0, 0, a));
			case "Exhaustion" -> sc.timed.add(new Timed(type, total, f(a, "drainAmount", 0.5f), 0, 0, a));
			case "Blind" -> p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, Math.round(total * 20)));
			case "Glowing" -> p.addEffect(new MobEffectInstance(MobEffects.GLOWING, Math.round(total * 20)));
			default -> { if (total > 0) sc.timed.add(new Timed(type, total, 0, 0, 0, a)); }   // Sunscreen, NoHunger, Invincibility, Numb, PreventPoisonHealing...
		}
	}

	static void timed(ServerPlayer p, String type, float seconds) {
		JsonObject o = new JsonObject();
		o.addProperty("$type", "Affliction_" + type);
		o.addProperty("totalTime", seconds);
		affliction(p, o);
	}

	static float f(JsonObject o, String k, float d) { return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsFloat() : d; }
	static String s(JsonObject o, String k) { return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : ""; }

	// ---- setup ----

	static void initCommon() {
		PayloadTypeRegistry.clientboundPlay().register(ScoutState.TYPE, ScoutState.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Bonus.TYPE, Bonus.CODEC);
		ServerLivingEntityEvents.ALLOW_DAMAGE.register(PeakStatus::allowDamage);
		ServerLivingEntityEvents.AFTER_DAMAGE.register((e, source, base, taken, blocked) -> {
			if (e instanceof ServerPlayer p && PeakWorld.scout(p) && !DYING.contains(p.getUUID())) {
				add(p, INJURY, taken / 20f * PeakAscents.damageMultiplier(p.level().getServer()));
				p.setHealth(p.getMaxHealth());
			}
		});
		ServerLivingEntityEvents.ALLOW_DEATH.register((e, source, amount) -> {
			if (!(e instanceof ServerPlayer p) || !PeakWorld.scout(p) || DYING.contains(p.getUUID()) || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return true;
			add(p, INJURY, amount / 20f);
			p.setHealth(p.getMaxHealth());
			return false;
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((old, now, alive) -> {
			Scout sc = SCOUTS.get(now.getUUID());
			if (sc != null && !alive) { clear(sc, true); sc.out = false; sc.dead = false; sc.passOut = 0; sc.dying = 0; sc.timed.clear(); }
			DYING.remove(now.getUUID());
		});
		// picking up a passed-out friend (an empty hand), or putting them down
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer carrier) || !(entity instanceof ServerPlayer target)) return InteractionResult.PASS;
			if (!PeakWorld.scout(carrier) || !carrier.getMainHandItem().isEmpty()) return InteractionResult.PASS;
			return carry(carrier, target) ? InteractionResult.SUCCESS : InteractionResult.PASS;
		});
	}

	/** carrier picks target up (a scout that passed out, not carried yet): it rides on carrier's back. */
	static boolean carry(ServerPlayer carrier, ServerPlayer target) {
		Scout t = SCOUTS.get(target.getUUID()), c = of(carrier);
		if (t == null || !t.out || t.dead || c.carrying != null || c.out || t.carrier != null) return false;
		t.carrier = carrier.getUUID();
		c.carrying = target.getUUID();
		carrier.level().playSound(null, carrier.blockPosition(), SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.PLAYERS, 1f, 0.8f);
		return true;
	}

	static boolean carried(Player p) { Scout s = SCOUTS.get(p.getUUID()); return s != null && s.carrier != null; }

	/** Damage, PEAK's way: a fall is PEAK's fall (by how long and how fast), the rest becomes Injury (AFTER_DAMAGE). */
	private static boolean allowDamage(LivingEntity e, DamageSource source, float amount) {
		if (!(e instanceof ServerPlayer p) || !PeakWorld.scout(p) || DYING.contains(p.getUUID())) return true;
		Scout sc = of(p);
		if (sc.dead) return false;
		if (!PeakCreatures.beetle(p, source)) return false;
		if (has(sc, "Invincibility") && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
		if (source.is(DamageTypes.FALL)) {
			if (PeakGear.noFall(p)) return false;
			float injury = fallInjury(p.fallDistance) * PeakAscents.fallMultiplier(p.level().getServer());
			if (injury >= 0.025f) { add(p, INJURY, injury); sc.fellHurt = true; }
			return false;
		}
		if (source.is(DamageTypes.STARVE) || source.is(DamageTypes.FREEZE)) return false;   // (PEAK's hunger and cold are on the bar)
		if (source.is(DamageTypes.LAVA) || source.is(DamageTypes.IN_FIRE) || source.is(DamageTypes.ON_FIRE)) { add(p, HOT, amount / 40f); }
		if (source.is(DamageTypes.MAGIC) || source.is(DamageTypes.INDIRECT_MAGIC)) { add(p, POISON, amount / 40f); return false; }
		return true;
	}

	/**
	 * PEAK's fall (CharacterMovement.CheckFallDamage), from how far the scout fell (as if in PEAK's gravity: its fall time
	 * and landing speed): nothing under 1.5 s of falling (about 13 blocks), all of the bar at 3 s (about 48).
	 */
	static float fallInjury(double h) {
		if (h <= 0) return 0;
		double t = Math.sqrt(2 * h / 9.81) - 0.125, v = Math.sqrt(2 * 9.81 * h);
		if (t <= 1.5) return 0;
		double a = Math.max(Math.pow(clamp01((t - 1.5) / 1.5), 1.5), 0.05);
		a = Math.min(a, Math.pow(clamp01((v - 10) / 10), 1.5));
		return (float) a;
	}

	static double clamp01(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

	// ---- every tick ----

	private static final Identifier SPEED_ID = MinePeak.id("passed_out_speed"), JUMP_ID = MinePeak.id("passed_out_jump"), FAST_ID = MinePeak.id("faster"), GRAV_ID = MinePeak.id("low_gravity");

	static void tick(MinecraftServer server) {
		if (!loaded) load(server);
		float dt = 1f / 20f;
		List<ServerPlayer> all = server.getPlayerList().getPlayers();
		int up = 0, scouts = 0, alive = 0;
		for (ServerPlayer p : all) {
			if (!PeakWorld.is(p.level())) continue;
			Scout sc = SCOUTS.get(p.getUUID());
			if (p.isCreative() || sc == null && p.isSpectator()) continue;
			scouts++;
			if (sc == null || !sc.dead && !sc.out) up++;
			if (sc == null || !sc.dead) alive++;
		}
		for (ServerPlayer p : all) {
			if (!PeakWorld.is(p.level())) continue;
			Scout sc = of(p);
			sc.nowS += dt;
			if (p.isCreative() || p.isSpectator() && !sc.dead) { clear(sc, false); sc.out = false; sc.passOut = 0; unmodify(p); send(p, sc, true); continue; }
			if (sc.dead) { send(p, sc, false); continue; }
			// Minecraft's own body stands still: full health and food (PEAK's are on the bar)
			if (p.getHealth() < p.getMaxHealth()) p.setHealth(p.getMaxHealth());
			p.getFoodData().setFoodLevel(20);
			p.getFoodData().setSaturation(5f);
			sc.highest = Math.max(sc.highest, (float) p.getY());
			if (!sc.startCursed) { sc.startCursed = true; add(p, CURSE, PeakAscents.startCurse(server)); }
			if (sc.zombieBit && sc.s[SPORES] <= 0) { sc.zombieBit = false; PeakBadges.award(p, "UndeadEncounter"); }
			// hunger (awake, not by a lit campfire, not under PEAK's NoHunger)
			if (p.tickCount % 20 == 0) sc.byFire = PeakFog.nearLitFire(p, 6);
			if (!sc.out && !sc.byFire && !has(sc, "NoHunger"))
				addQuiet(p, sc, HUNGER, PeakItemData.affliction("hungerPerSecond", 0.0005f) * dt * PeakAscents.hungerMultiplier(server));
			// poison, drowsiness, heat, spores and thorns wear off, a while after the last
			wearOff(sc, POISON, "poison", 0.004f, 30f, dt);
			wearOff(sc, DROWSY, "drowsy", 0.025f, 6f, dt);
			wearOff(sc, HOT, "hot", 0.013f, 8f, dt);
			wearOff(sc, SPORES, "spores", 0.005f, 45f, dt);
			wearOff(sc, THORNS, "thorns", 0.0025f, 30f, dt);
			// weight: what the scout carries (PEAK: 0.025 each point of carry weight; a scout on its back is 8)
			sc.s[WEIGHT] = Math.min(1f, 0.025f * (PeakItems.carryWeight(p) + (sc.carrying != null ? 8 : 0)));
			// timed effects
			tickTimed(p, sc, dt);
			// warmth from a friend close by (PEAK's CharacterHeatEmission)
			if (p.tickCount % 10 == 0 && sc.s[COLD] > 0)
				for (ServerPlayer o : all) if (o != p && PeakWorld.scout(o) && o.distanceTo(p) < 1.5f) { sub(p, COLD, 0.05f); break; }
			// passing out, PEAK's way
			float total = sum(sc);
			if (!sc.out) {
				if (total > 0.99f) { sc.passOut = Math.min(1f, sc.passOut + dt / 5f); if (sc.passOut >= 1f) passOut(p, sc); }
				else sc.passOut = Math.max(0, sc.passOut - dt / 2f);
			} else {
				if (total < 1f && sc.nowS - sc.outAt >= 3) wake(p, sc);
				else {
					boolean carried = sc.carrier != null;
					boolean alone = up == 0;
					if (p.isShiftKeyDown() && !carried) sc.dying += dt * 0.33f;
					else if (!carried) sc.dying += dt / (alone && !tempStatus(sc) ? 10f : 60f);
					if (sc.dying >= 1f) die(p, sc);
				}
			}
			// carried: on the carrier's back
			if (sc.carrier != null) {
				ServerPlayer c = server.getPlayerList().getPlayer(sc.carrier);
				Scout cs = c != null ? SCOUTS.get(c.getUUID()) : null;
				if (c == null || cs == null || cs.out || cs.dead || !sc.out || c.isShiftKeyDown() && c.tickCount % 2 == 0 && p.distanceTo(c) < 3) {
					sc.carrier = null;
					if (cs != null) cs.carrying = null;
				} else {
					Vec3 back = c.position().add(Vec3.directionFromRotation(0, c.getYRot()).scale(-0.45)).add(0, 0.9, 0);
					p.teleportTo(back.x, back.y, back.z);
					p.setDeltaMovement(Vec3.ZERO);
					p.fallDistance = 0;
				}
			}
			if (sc.carrying != null) {
				ServerPlayer t = server.getPlayerList().getPlayer(sc.carrying);
				Scout ts = t != null ? SCOUTS.get(t.getUUID()) : null;
				if (ts == null || !p.getUUID().equals(ts.carrier)) sc.carrying = null;
			}
			send(p, sc, false);
		}
		if (scouts > 0 && alive == 0) runOver(server);   // (PEAK: the run ends when every scout is dead, not just down)
		else overAt = -1;
		if (server.getTickCount() % 1200 == 0) save(server);
	}

	private static void addQuiet(ServerPlayer p, Scout sc, int i, float amount) {
		float before = sc.lastAdded[i] == 0 ? 0 : (float) sc.lastAdded[i];
		add(p, i, amount);
		sc.lastAdded[i] = before;   // (hunger growing doesn't count as an add)
	}

	private static void wearOff(Scout sc, int i, String key, float rate, float cooldown, float dt) {
		if (sc.s[i] <= 0) return;
		if (i == POISON && has(sc, "PreventPoisonHealing")) return;
		if (sc.nowS - sc.lastAdded[i] < PeakItemData.affliction(key + "ReductionCooldown", cooldown)) return;
		sc.s[i] = Math.max(0, sc.s[i] - PeakItemData.affliction(key + "ReductionPerSecond", rate) * dt);
	}

	private static boolean tempStatus(Scout sc) { return sc.s[POISON] > 0 || sc.s[DROWSY] > 0 || sc.s[HOT] > 0 || sc.s[COLD] > 0 || sc.s[SPORES] > 0; }

	private static void tickTimed(ServerPlayer p, Scout sc, float dt) {
		boolean fast = false, low = false;
		float lowAmount = 0;
		for (Iterator<Timed> it = sc.timed.iterator(); it.hasNext(); ) {
			Timed t = it.next();
			t.left -= dt; t.elapsed += dt;
			switch (t.type) {
				case "PoisonOverTime" -> { if (t.elapsed >= t.b) { add(p, POISON, t.a * dt); sc.lastAdded[POISON] = sc.nowS; } }
				case "SporesOverTime" -> { if (t.elapsed >= t.b) { add(p, SPORES, t.a * dt); sc.lastAdded[SPORES] = sc.nowS; } }
				case "AdjustColdOverTime" -> { if (t.a >= 0) add(p, COLD, t.a * dt); else sub(p, COLD, -t.a * dt); }
				case "AdjustDrowsyOverTime" -> { if (t.a >= 0) add(p, DROWSY, t.a * dt); else sub(p, DROWSY, -t.a * dt); }
				case "FasterBoi" -> { fast = true; sub(p, DROWSY, dt); }
				case "LowGravity" -> { low = true; lowAmount = Math.max(lowAmount, t.a); }
				default -> { }
			}
			if (t.left <= 0) {
				it.remove();
				if (t.type.equals("FasterBoi") && t.c > 0) add(p, DROWSY, t.c);
				if (t.type.equals("InfiniteStamina") && t.extra != null && t.extra.has("drowsyAffliction") && t.extra.get("drowsyAffliction").isJsonObject())
					affliction(p, t.extra.getAsJsonObject("drowsyAffliction"));
			}
		}
		var speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) {
			if (fast) speed.addOrUpdateTransientModifier(new AttributeModifier(FAST_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			else speed.removeModifier(FAST_ID);
		}
		var grav = p.getAttribute(Attributes.GRAVITY);
		if (grav != null) {
			int balloons = PeakGear.balloons(p);
			double g = (low ? -Math.min(0.9, lowAmount * 0.25) : 0) - 0.1 * balloons;
			if (g != 0) grav.addOrUpdateTransientModifier(new AttributeModifier(GRAV_ID, Math.max(-1.05, g), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			else grav.removeModifier(GRAV_ID);
		}
	}

	private static void send(ServerPlayer p, Scout sc, boolean now) {
		if (!now && (p.tickCount + p.getId()) % 5 != 0) return;
		int flags = 0;
		if (sc.out) flags |= F_OUT;
		if (sc.dead) flags |= F_DEAD;
		if (sc.carrier != null) flags |= F_CARRIED;
		if (sc.carrying != null) flags |= F_CARRYING;
		float climb = 1f, drain = 0;
		for (Timed t : sc.timed)
			switch (t.type) {
				case "InfiniteStamina" -> flags |= F_INFINITE;
				case "FasterBoi" -> flags |= F_FASTER;
				case "Invincibility" -> flags |= F_INVINCIBLE;
				case "Numb" -> flags |= F_NUMB;
				case "NoHunger" -> flags |= F_NO_HUNGER;
				case "LowGravity" -> flags |= F_LOW_GRAVITY;
				case "Sunscreen" -> flags |= F_SUNSCREEN;
				case "AdjustColdOverTime" -> { if (t.a < 0) flags |= F_WARMING; }   // (PEAK's StartHeat: a heat pack's warmth on the screen)
				case "ClimbingChalk" -> climb *= Math.max(0.1f, t.a);
				case "Exhaustion" -> drain += t.a / Math.max(1f, t.total);
				default -> { }
			}
		climb *= PeakAscents.climbMultiplier(p.level().getServer()) * Math.max(1f, sc.windGrab);
		ServerPlayNetworking.send(p, new ScoutState(sc.s.clone(), sc.passOut, sc.dying, flags, climb, drain, PeakGear.balloons(p)));
	}

	// ---- passing out, dying, waking ----

	private static void passOut(ServerPlayer p, Scout sc) {
		sc.out = true;
		sc.outAt = sc.nowS;
		sc.dying = 0;
		sc.passedOutEver = true;
		// PEAK drops what the scout holds (not its backpack)
		for (int i = 0; i < 9; i++) {
			if (i == PeakInventory.BACKPACK) continue;
			ItemStack st = p.getInventory().getItem(i);
			if (!st.isEmpty()) { p.drop(st.copy(), true, false); p.getInventory().setItem(i, ItemStack.EMPTY); }
		}
		var speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) speed.addOrUpdateTransientModifier(new AttributeModifier(SPEED_ID, -1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		var jump = p.getAttribute(Attributes.JUMP_STRENGTH);
		if (jump != null) jump.addOrUpdateTransientModifier(new AttributeModifier(JUMP_ID, -1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1f, 0.6f);
		send(p, sc, true);
	}

	static void wake(ServerPlayer p, Scout sc) {
		sc.out = false;
		sc.passOut = 0;
		sc.dying = 0;
		if (sc.carrier != null) {
			Scout c = SCOUTS.get(sc.carrier);
			if (c != null) c.carrying = null;
			sc.carrier = null;
		}
		unmodify(p);
		send(p, sc, true);
	}

	private static void unmodify(ServerPlayer p) {
		var speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) speed.removeModifier(SPEED_ID);
		var jump = p.getAttribute(Attributes.JUMP_STRENGTH);
		if (jump != null) jump.removeModifier(JUMP_ID);
	}

	/** Dead, PEAK's way: back at the checkpoint flag if one's planted, else bones left behind and a ghost that watches. */
	static void die(ServerPlayer p, Scout sc) {
		if (sc.flag != null) {
			BlockPos f = sc.flag;
			sc.flag = null;
			wake(p, sc);
			if (sc.flagStatus != null) System.arraycopy(sc.flagStatus, 0, sc.s, 0, N);
			p.teleportTo(f.getX() + 0.5, f.getY() + 1, f.getZ() + 0.5);
			HeroTitle.card(p, "CHECKPOINT");
			return;
		}
		wake(p, sc);
		sc.dead = true;
		ServerLevel level = p.level();
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (!st.isEmpty()) { p.drop(st.copy(), true, false); p.getInventory().setItem(i, ItemStack.EMPTY); }
		}
		var bones = EntityTypes.SKELETON.create(level, EntitySpawnReason.TRIGGERED);
		if (bones != null) {
			bones.snapTo(p.getX(), p.getY(), p.getZ(), p.getYRot(), 0);
			bones.setNoAi(true);
			bones.setSilent(true);
			bones.setInvulnerable(true);
			bones.setPersistenceRequired();
			bones.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
			bones.setCustomName(Component.literal(p.getName().getString()));
			bones.addTag(BONES);
			bones.addTag(BONES + "." + p.getUUID());
			level.addFreshEntity(bones);
		}
		PeakSfx.play(level, p.position(), "death", SoundEvents.SKELETON_DEATH, 1f, 1f);
		p.setGameMode(GameType.SPECTATOR);
		HeroTitle.card(p, "YOU DIED");
		for (ServerPlayer o : level.getServer().getPlayerList().getPlayers())
			if (o != p) o.sendSystemMessage(Component.literal(p.getName().getString() + " died. A statue can bring them back."));
		save(level.getServer());
	}

	static final String BONES = "minepeak.bones";

	/** Back from the dead or from passing out (a statue, an effigy): at pos, the statuses gone, then PEAK's price (curse, hunger). */
	static void revive(ServerPlayer p, Vec3 pos, boolean price) {
		Scout sc = of(p);
		if (sc.dead) {
			for (var e : p.level().getEntities((net.minecraft.world.entity.Entity) null, p.getBoundingBox().inflate(4096), e -> e.entityTags().contains(BONES + "." + p.getUUID()))) e.discard();
			p.setGameMode(GameType.SURVIVAL);
		}
		sc.dead = false;
		wake(p, sc);
		clear(sc, true);
		sc.timed.clear();
		p.teleportTo(pos.x, pos.y, pos.z);
		p.setDeltaMovement(Vec3.ZERO);
		p.fallDistance = 0;
		PeakSfx.play(p.level(), pos, "revive", SoundEvents.TOTEM_USE, 1f, 1f);
		if (price) {
			add(p, CURSE, PeakAscents.revivalCurse(p.level().getServer()));
			add(p, HUNGER, 0.3f);
		}
		sc.revives++;
		send(p, sc, true);
	}

	static boolean dead(Player p) { Scout s = SCOUTS.get(p.getUUID()); return s != null && s.dead; }

	static List<ServerPlayer> deadOrDown(MinecraftServer server) {
		List<ServerPlayer> l = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) { Scout s = SCOUTS.get(p.getUUID()); if (s != null && (s.dead || s.out)) l.add(p); }
		return l;
	}

	private static long overAt = -1;

	/** Every scout dead: PEAK's run is over. Here everyone starts again at their last campfire, the statuses gone. */
	private static void runOver(MinecraftServer server) {
		long now = server.getTickCount();
		if (overAt < 0) {
			overAt = now;
			for (ServerPlayer p : server.getPlayerList().getPlayers()) if (PeakWorld.is(p.level())) HeroTitle.card(p, "THE RUN IS OVER");
			return;
		}
		if (now - overAt < 100) return;
		overAt = -1;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Scout sc = SCOUTS.get(p.getUUID());
			if (sc == null || !(sc.dead || sc.out)) continue;
			var rc = p.getRespawnConfig();
			BlockPos at = rc != null ? rc.respawnData().pos() : p.level().getRespawnData().pos();
			revive(p, Vec3.atBottomCenterOf(at), false);
		}
	}

	// ---- the checkpoint flag ----

	static void plantFlag(ServerPlayer p, BlockPos at) {
		Scout sc = of(p);
		sc.flag = at;
		sc.flagStatus = sc.s.clone();
	}

	// ---- kept with the world ----

	private static Path file(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("minepeak_scouts.json"); }

	static void load(MinecraftServer server) {
		SCOUTS.clear();
		loaded = true;
		try {
			Path f = file(server);
			if (!Files.isRegularFile(f)) return;
			JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
			for (var e : o.entrySet()) {
				JsonObject j = e.getValue().getAsJsonObject();
				Scout sc = new Scout();
				JsonArray a = j.getAsJsonArray("s");
				for (int i = 0; i < N && i < a.size(); i++) sc.s[i] = a.get(i).getAsFloat();
				sc.dead = j.has("dead") && j.get("dead").getAsBoolean();
				sc.started = j.has("started") ? j.get("started").getAsLong() : System.currentTimeMillis();
				if (j.has("flag")) { JsonArray fl = j.getAsJsonArray("flag"); sc.flag = new BlockPos(fl.get(0).getAsInt(), fl.get(1).getAsInt(), fl.get(2).getAsInt()); }
				for (String k : new String[] { "revives", "luggage", "clownLuggage", "pitons", "rope", "cooked" })
					if (j.has(k)) setInt(sc, k, j.get(k).getAsInt());
				sc.passedOutEver = j.has("passedOutEver") && j.get("passedOutEver").getAsBoolean();
				sc.fellHurt = j.has("fellHurt") && j.get("fellHurt").getAsBoolean();
				sc.atePackaged = j.has("atePackaged") && j.get("atePackaged").getAsBoolean();
				sc.startCursed = true;
				if (j.has("berries")) for (JsonElement b : j.getAsJsonArray("berries")) sc.berries.add(b.getAsString());
				if (j.has("mushrooms")) for (JsonElement b : j.getAsJsonArray("mushrooms")) sc.mushrooms.add(b.getAsString());
				SCOUTS.put(UUID.fromString(e.getKey()), sc);
			}
		} catch (Exception ex) {
			MinePeak.LOG.warn("MinePeak: couldn't read the scouts' state", ex);
		}
	}

	private static void setInt(Scout sc, String k, int v) {
		switch (k) {
			case "revives" -> sc.revives = v; case "luggage" -> sc.luggage = v; case "clownLuggage" -> sc.clownLuggage = v;
			case "pitons" -> sc.pitons = v; case "rope" -> sc.rope = v; case "cooked" -> sc.cooked = v; default -> { }
		}
	}

	static void save(MinecraftServer server) {
		if (!loaded) return;
		JsonObject o = new JsonObject();
		for (var e : SCOUTS.entrySet()) {
			Scout sc = e.getValue();
			JsonObject j = new JsonObject();
			JsonArray a = new JsonArray();
			for (float v : sc.s) a.add(v);
			j.add("s", a);
			j.addProperty("dead", sc.dead);
			j.addProperty("started", sc.started);
			if (sc.flag != null) { JsonArray fl = new JsonArray(); fl.add(sc.flag.getX()); fl.add(sc.flag.getY()); fl.add(sc.flag.getZ()); j.add("flag", fl); }
			j.addProperty("revives", sc.revives); j.addProperty("luggage", sc.luggage); j.addProperty("clownLuggage", sc.clownLuggage);
			j.addProperty("pitons", sc.pitons); j.addProperty("rope", sc.rope); j.addProperty("cooked", sc.cooked);
			j.addProperty("passedOutEver", sc.passedOutEver); j.addProperty("fellHurt", sc.fellHurt); j.addProperty("atePackaged", sc.atePackaged);
			JsonArray b = new JsonArray(); sc.berries.forEach(b::add); j.add("berries", b);
			JsonArray m = new JsonArray(); sc.mushrooms.forEach(m::add); j.add("mushrooms", m);
			o.add(e.getKey().toString(), j);
		}
		try { Files.writeString(file(server), o.toString()); } catch (Exception ex) { MinePeak.LOG.warn("MinePeak: couldn't save the scouts' state", ex); }
	}

	static void stop(MinecraftServer server) { save(server); loaded = false; SCOUTS.clear(); DYING.clear(); overAt = -1; }

	/** Is a passed-out or carried scout to lie down (Player.updatePlayerPose: PlayerPoseMixin)? */
	public static boolean lying(Player p) {
		if (p.level().isClientSide()) return PeakStatusClient.lying(p);
		Scout s = SCOUTS.get(p.getUUID());
		return s != null && s.out;
	}

	static Level level(ServerPlayer p) { return p.level(); }

	static AABB around(Player p, double r) { return p.getBoundingBox().inflate(r); }
}
