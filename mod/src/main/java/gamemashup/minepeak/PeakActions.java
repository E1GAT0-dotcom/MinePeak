package gamemashup.minepeak;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * What PEAK's items do when used, read off each item's parts (PEAK's Action_* components, exported with their
 * settings): restore hunger, change a status, give bonus stamina, poison over time, PEAK's afflictions, clear every
 * status, infinite stamina, the island's mushrooms (their effects shuffled for each world, as PEAK shuffles them for each
 * run), thorns, numbness, a morale boost, a launch... Then the item is eaten (Action_Consume; a berrynana leaves its peel),
 * or one of its uses goes (Action_ReduceUses). Cooked food is worth more, then less, then poisons (PEAK's ItemCooking).
 */
final class PeakActions {
	private PeakActions() { }

	/**
	 * user uses stack on target (itself, or a friend: PEAK's feeding): the item's actions at the end of its use, and if
	 * it's eaten up, those when eaten. Returns what's left in the hand.
	 */
	static ItemStack cast(ServerPlayer user, ServerPlayer target, ItemStack stack, PeakItemData.Def def) {
		int cooked = PeakItems.cooked(stack);
		var cook = def.part("ItemCooking");
		boolean cookRules = cook == null || !cook.bool("ignoreDefaultCookBehavior", false);
		boolean consumed = false, spawnPeel = false;
		String spawn = null;
		boolean hadStamina = def.has("Action_GiveExtraStamina");
		for (var p : def.parts()) {
			if (!p.on("OnCastFinished")) continue;
			switch (p.type()) {
				case "Action_Consume" -> consumed = true;
				case "Action_ConsumeAndSpawn" -> { consumed = true; spawnPeel = true; spawn = p.ref("itemToSpawn"); }
				case "Action_ReduceUses" -> {
					var t = PeakItems.tag(stack);
					int uses = t.getIntOr("uses", Math.max(1, def.totalUses())) - 1;
					t.putInt("uses", uses);
					PeakItems.save(stack, t);
					if (uses <= 0 && p.bool("consumeOnFullyUsed", true)) consumed = true;
				}
				default -> run(user, target, p, cooked, cookRules, def);
			}
		}
		// cooked food gives bonus stamina even when it gave none (PEAK adds the action)
		if (cookRules && cooked >= 1 && cooked <= 2 && !hadStamina && def.edible()) PeakStatus.bonus(target, 0.1f);
		if (consumed) {
			for (var p : def.parts()) if (p.on("OnConsumed")) run(user, target, p, cooked, cookRules, def);
			if (cookRules && cooked >= 4) PeakStatus.add(target, PeakStatus.POISON, 0.1f * (cooked - 3));
			record(target, def, cooked);
			PeakSfx.play(target.level(), target.position(), PeakSfx.used(def), SoundEvents.PLAYER_BURP, 0.6f, 1f);
			stack.shrink(1);
			if (spawnPeel) {
				ItemStack peel = spawn != null ? PeakItems.stack(spawn) : ItemStack.EMPTY;
				if (!peel.isEmpty()) {
					if (stack.isEmpty()) return peel;
					if (!user.getInventory().add(peel)) user.drop(peel, false);
				}
			}
		}
		return stack;
	}

	/** One action of PEAK's on target. */
	static void run(ServerPlayer user, ServerPlayer target, PeakItemData.Part p, int cooked, boolean cookRules, PeakItemData.Def def) {
		switch (p.type()) {
			case "Action_RestoreHunger" -> {
				float amt = p.num("restorationAmount", 0);
				if (cookRules) for (int j = 1; j <= cooked; j++) { if (j == 1) amt *= 2; if (j >= 3) amt = Math.max(0, amt - 0.05f); }
				PeakStatus.sub(target, PeakStatus.HUNGER, amt);
			}
			case "Action_ModifyStatus" -> {
				int i = PeakStatus.index(p.str("statusType", ""));
				float amt = p.num("changeAmount", 0);
				if (amt < 0) PeakStatus.sub(target, i, -amt); else PeakStatus.add(target, i, amt);
			}
			case "Action_GiveExtraStamina" -> {
				float amt = p.num("amount", 0);
				if (cookRules && cooked >= 1) amt = cooked >= 3 ? 0 : Math.max(0.1f, amt * 1.5f);
				PeakStatus.bonus(target, amt);
			}
			case "Action_InflictPoison" -> {
				JsonObject a = new JsonObject();
				a.addProperty("$type", "Affliction_PoisonOverTime");
				a.addProperty("totalTime", p.num("inflictionTime", 10));
				a.addProperty("statusPerSecond", p.num("poisonPerSecond", 0.02f));
				a.addProperty("delayBeforeEffect", p.num("delay", 0));
				PeakStatus.affliction(target, a);
			}
			case "Action_ApplyAffliction" -> afflictions(target, p);
			case "Action_ApplyMassAffliction" -> {
				double r = p.num("radius", 5);
				for (ServerPlayer o : target.level().getServer().getPlayerList().getPlayers())
					if (PeakWorld.scout(o) && o.distanceTo(user) <= r && !(p.bool("ignoreCaster", false) && o == user)) afflictions(o, p);
			}
			case "Action_ClearAllStatus" -> PeakStatus.clear(PeakStatus.of(target), p.bool("excludeCurse", true));
			case "Action_ApplyInfiniteStamina" -> {
				JsonObject a = new JsonObject();
				a.addProperty("$type", "Affliction_InfiniteStamina");
				a.addProperty("totalTime", p.num("buffTime", 5));
				JsonObject d = new JsonObject();
				d.addProperty("$type", "Affliction_AdjustStatus");
				d.addProperty("statusType", "Drowsy");
				d.addProperty("statusAmount", p.num("drowsyAmount", 0.25f));
				a.add("drowsyAffliction", d);
				PeakStatus.affliction(target, a);
			}
			case "Action_RandomMushroomEffect" -> mushroom(target, p.integer("mushroomTypeIndex", 0));
			case "Action_Numb" -> PeakStatus.timed(target, "Numb", p.num("numbAmount", 60));
			case "Action_AddOrRemoveThorns" -> {
				int n = p.integer("thornCount", 0);
				if (n > 0) PeakStatus.add(target, PeakStatus.THORNS, 0.025f * n); else PeakStatus.sub(target, PeakStatus.THORNS, -0.025f * n);
			}
			case "Action_MoraleBoost" -> {
				double r = p.num("boostRadius", 10);
				List<ServerPlayer> near = new ArrayList<>();
				for (ServerPlayer o : target.level().getServer().getPlayerList().getPlayers()) if (PeakWorld.scout(o) && o.distanceTo(user) <= r) near.add(o);
				float boost = p.num("baselineStaminaBoost", 0.1f) + p.num("staminaBoostPerAdditionalScout", 0.05f) * Math.max(0, near.size() - 1);
				for (ServerPlayer o : near) { PeakStatus.bonus(o, boost); HeroTitle.card(o, "MORALE BOOST"); }
			}
			case "Action_LaunchPlayer" -> {
				Vec3 look = target.getLookAngle();
				target.setDeltaMovement(look.scale(Math.min(4.0, p.num("force", 30) / 20.0)).add(0, 0.4, 0));
				target.hurtMarked = true;
				target.fallDistance = 0;
			}
			case "Action_Die" -> { var sc = PeakStatus.of(target); sc.out = true; PeakStatus.die(target, sc); }
			case "Action_WarpToRandomPlayer" -> {
				List<ServerPlayer> others = new ArrayList<>();
				for (ServerPlayer o : target.level().getServer().getPlayerList().getPlayers())
					if (o != target && PeakWorld.scout(o) && o.distanceTo(target) >= p.num("minimumDistance", 12)) others.add(o);
				if (!others.isEmpty()) { ServerPlayer o = others.get(target.getRandom().nextInt(others.size())); target.teleportTo(o.getX(), o.getY(), o.getZ()); }
			}
			case "Action_WarpToBiome" -> PeakGear.warpToStage(target, p.str("segmentToWarpTo", "Beach"));
			case "Action_CallScoutmaster" -> PeakScoutmaster.call(target, p.num("forcedChaseTime", 60));
			case "Action_RaycastDart" -> PeakGear.dart(user, p);
			case "Action_HealingGem" -> {
				JsonObject a = p.obj("healingAffliction");
				if (a == null) { a = new JsonObject(); a.addProperty("$type", "Affliction_HealAll"); a.addProperty("maxHealing", 1f); }
				PeakStatus.affliction(target, a);
			}
			case "Action_Flare", "Action_Parasol", "Action_LightLantern", "Action_LightCandle", "Action_Balloon", "Action_Spawn", "Action_PlayAnimation",
				"Action_Guidebook", "Action_GuidebookScroll", "Action_TryHotDogAchievement", "Action_ShowBinocularOverlay" -> PeakGear.action(user, target, p, def);
			default -> { }
		}
	}

	private static void afflictions(ServerPlayer target, PeakItemData.Part p) {
		JsonObject a = p.obj("affliction");
		if (a != null) PeakStatus.affliction(target, a);
		for (JsonElement e : PeakItemData.array(p.f(), "extraAfflictions"))
			if (e.isJsonObject()) PeakStatus.affliction(target, e.getAsJsonObject());
	}

	/** For the badges and the end screen: what the scout ate. */
	private static void record(ServerPlayer p, PeakItemData.Def def, int cooked) {
		var sc = PeakStatus.of(p);
		sc.ateAny = true;
		if (def.tagged("PackagedFood")) sc.atePackaged = true;
		if (def.tagged("Berry")) sc.berries.add(def.item());
		if (def.tagged("Mushroom") && !def.has("Action_InflictPoison")) sc.mushrooms.add(def.item());
		if (cooked > 0) { sc.cooked++; sc.ateCooked = true; }
		PeakBadges.ate(p, def, cooked);
	}

	// ---- the island's mushrooms: ten kinds, their effects shuffled for each world (at least one good, one bad) ----

	private static int[] shuffled;
	private static float[] stam;
	private static long shuffledFor = Long.MIN_VALUE;

	static void mushroom(ServerPlayer p, int type) {
		long seed = p.level().getSeed();
		if (shuffled == null || shuffledFor != seed) {
			RandomSource r = RandomSource.create(seed ^ 0x5EEDL);
			int[] e;
			do {
				e = new int[10];
				for (int i = 0; i < 10; i++) e[i] = i;
				for (int i = 9; i > 0; i--) { int j = r.nextInt(i + 1); int t = e[i]; e[i] = e[j]; e[j] = t; }
			} while (!goodAndBad(e));
			stam = new float[10];
			for (int i = 0; i < 10; i++) stam[i] = r.nextInt(4);
			shuffled = e;
			shuffledFor = seed;
		}
		int k = Math.floorMod(type, 10);
		PeakStatus.bonus(p, stam[k] * 0.05f);
		int effect = shuffled[k];
		Runnable now = () -> {
			switch (effect) {
				case 0 -> PeakStatus.timed(p, "InfiniteStamina", 4);
				case 1 -> {
					JsonObject a = new JsonObject();
					a.addProperty("$type", "Affliction_FasterBoi"); a.addProperty("totalTime", 5); a.addProperty("moveSpeedMod", 0.5); a.addProperty("climbSpeedMod", 1.5);
					PeakStatus.affliction(p, a);
				}
				case 2 -> { JsonObject a = new JsonObject(); a.addProperty("$type", "Affliction_LowGravity"); a.addProperty("totalTime", 15); a.addProperty("lowGravAmount", 3); PeakStatus.affliction(p, a); }
				case 3 -> PeakStatus.timed(p, "Invincibility", 10);
				case 4 -> { PeakStatus.sub(p, PeakStatus.HUNGER, 0.15f); PeakStatus.sub(p, PeakStatus.INJURY, 0.15f); PeakStatus.sub(p, PeakStatus.POISON, 0.15f); }
				case 5 -> {
					p.setDeltaMovement(p.getDeltaMovement().add(0, 1.4, 0));
					p.hurtMarked = true;
					p.fallDistance = 0;
					p.level().sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, p.getX(), p.getY(), p.getZ(), 60, 0.6, 0.2, 0.6, 0.02);
					p.level().playSound(null, p.blockPosition(), SoundEvents.PUFFER_FISH_BLOW_UP, SoundSource.PLAYERS, 1f, 0.6f);
				}
				case 6 -> PeakStatus.timed(p, "Blind", 60);
				case 7 -> {
					p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 160, 6));
					p.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 160, 0));
				}
				case 8 -> PeakStatus.add(p, PeakStatus.SPORES, 0.25f);
				default -> PeakStatus.timed(p, "Numb", 60);
			}
		};
		if (effect == 3 || effect == 4 || effect == 8) now.run(); else later(p.level().getServer(), 60, now);
	}

	private static boolean goodAndBad(int[] e) {
		boolean good = false, bad = false;
		for (int i = 0; i < 10; i++) { if (e[i] <= 4) good = true; else bad = true; }
		return good && bad;
	}

	// ---- later ----

	private record Later(long at, Runnable what) { }
	private static final List<Later> LATER = new ArrayList<>();

	/** The world stopping: what was to happen later doesn't (it would happen in the next world, to its blocks). */
	static void stop() { LATER.clear(); shuffledFor = Long.MIN_VALUE; }

	static void later(MinecraftServer server, int ticks, Runnable what) { LATER.add(new Later(server.getTickCount() + ticks, what)); }

	static void tick(MinecraftServer server) {
		long now = server.getTickCount();
		List<Runnable> due = new ArrayList<>();
		for (Iterator<Later> it = LATER.iterator(); it.hasNext(); ) { Later l = it.next(); if (l.at <= now) { due.add(l.what); it.remove(); } }
		for (Runnable r : due) try { r.run(); } catch (Exception e) { MinePeak.LOG.warn("MinePeak: an item's effect failed", e); }
	}
}
