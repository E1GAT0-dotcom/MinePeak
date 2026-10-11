package gamemashup.minepeak;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * PEAK's gear, on the server (its numbers from each item's parts, as exported):
 * - rope spool: aimed at a cliff (or the ground by an edge), a rope hangs from there down to the ground (up to PEAK's 30);
 *   the anti-rope spool's goes up. The rope cannon fires one 30 away; the chain launcher a vine from you to where it hits.
 * - piton: into a wall; hang by it and your stamina comes back (PeakClimbClient).
 * - rescue claw: a scout it hits is dragged to you; a wall it hits pulls you there.
 * - balloons: each takes a tenth off your weight (Minecraft's gravity) and puts a quarter on your jump, for 10 s.
 * - lantern, torch, candle: lit (use), they light your way and warm you, while their fuel lasts.
 * - flare: lit at the peak, it calls the rescue (PeakEnding). Dynamite: lit and thrown, it goes off after its fuse.
 * - bugles: played (held), they sound; capybaras listen. The magic one boosts friends; the Scoutmaster's calls him.
 * - checkpoint flag, portable stove, scout effigy, scout cannon: put down (used on the ground).
 * - shelf, bounce and cloud fungus: thrown, a ledge, a bounce pad, a cloud to stand on for a while.
 * - backpack, fanny pack: carried in the backpack slot (the 4th), opened when held.
 * - jetpack (in the backpack slot or held): jump held in the air flies you up while its fuel lasts (PeakGearClient).
 * - coconut: cracked open. Magic bean: planted, a vine grows up.
 * Parasols and gliders float you down (PeakGearClient: the player's own movement).
 */
final class PeakGear {
	private PeakGear() { }

	private static final String[] GEAR = { "RopeSpool", "RopeShooter", "VineShooter", "ClimbingSpikeComponent", "RescueHook", "Balloon", "Lantern", "Candle",
		"Flare", "Dynamite", "BugleSFX", "Constructable", "CheckpointConstructable", "ScoutEffigy", "ShelfShroom", "CloudFungus", "MagicBean",
		"Rocketpack", "BackpackStabilizer", "Actions_Binoculars", "GuidebookSpread", "Snowball", "Frisbee", "Basketball" };

	/** Is the item PEAK gear that MinePeak works by hand (not just eaten or drunk)? */
	static boolean handles(PeakItemData.Def def) {
		if (def == null) return false;
		for (String g : GEAR) if (def.has(g)) return true;
		return def.item().startsWith("Guidebook") || def.item().equals("Item_Coconut") || def.item().equals("BookOfBones") || def.item().equals("Stone")
			|| def.item().equals("CactusBall") || def.item().equals("Megaphone");
	}

	static boolean is(PeakItemData.Def def, String part) { return def != null && def.has(part); }

	/** Uses or fuel left, 0..1 (the item's bar). */
	static float left(ItemStack stack, PeakItemData.Def def) {
		var t = PeakItems.tag(stack);
		if (t.contains("fuelMax")) return Math.max(0, t.getFloatOr("fuel", 0) / Math.max(0.001f, t.getFloatOr("fuelMax", 1)));
		if (def != null && def.totalUses() > 1) return t.getIntOr("uses", def.totalUses()) / (float) def.totalUses();
		return 1;
	}

	// ---- using it (right click) ----

	static InteractionResult use(Level level, Player player, InteractionHand hand, ItemStack stack, PeakItemData.Def def) {
		if (!handles(def)) return null;
		String n = def.item();
		if (is(def, "BugleSFX") || is(def, "Actions_Binoculars") || n.equals("Megaphone")) { player.startUsingItem(hand); return InteractionResult.CONSUME; }
		if (n.startsWith("Guidebook")) return InteractionResult.SUCCESS;   // (read on the client: PeakGearClient)
		if (!(player instanceof ServerPlayer p) || !(level instanceof ServerLevel sl)) return InteractionResult.SUCCESS;
		if (is(def, "RopeShooter")) {
			BlockHitResult hit = ray(p, 30);
			if (hit == null) return InteractionResult.FAIL;
			int placed = hang(sl, p, hit.getBlockPos(), hit.getDirection(), def.item().contains("Anti"), 30, PeakGearBlocks.ROPE);
			if (placed > 0) { use1(p, hand, stack, def, "fuel"); sound(sl, p, SoundEvents.CROSSBOW_SHOOT); }
			return InteractionResult.SUCCESS;
		}
		if (is(def, "VineShooter")) {
			BlockHitResult hit = ray(p, 40);
			if (hit == null) return InteractionResult.FAIL;
			Vec3 from = p.position().add(0, 1.5, 0), to = Vec3.atCenterOf(hit.getBlockPos().relative(hit.getDirection()));
			int placed = line(sl, from, to, PeakGearBlocks.VINE_ROPE);
			if (placed > 0) { consume(p, hand, stack); sound(sl, p, SoundEvents.CROSSBOW_SHOOT); PeakStatus.of(p).rope += placed; }
			return InteractionResult.SUCCESS;
		}
		if (is(def, "RescueHook")) { claw(sl, p, hand, stack, def); return InteractionResult.SUCCESS; }
		if (is(def, "Balloon")) {
			int n2 = def.part("Balloon").bool("isBunch", false) ? 3 : 1;
			for (int i = 0; i < n2; i++) {
				BALLOONS.computeIfAbsent(p.getUUID(), u -> new ArrayList<>()).add(sl.getServer().getTickCount() + 200L);
				balloonOver(sl, p, PeakItems.stack("Balloon"));
			}
			if (balloons(p) >= 6 && !p.onGround()) PeakBadges.award(p, "Aeronautics");
			PeakSfx.play(sl, p.position(), "balloon_tie", SoundEvents.ITEM_PICKUP, 1f, 1f);
			consume(p, hand, stack);
			return InteractionResult.SUCCESS;
		}
		if (is(def, "Lantern") || is(def, "Candle")) {
			var t = PeakItems.tag(stack);
			boolean lit = !t.getBooleanOr("lit", false) && t.getFloatOr("fuel", 1) > 0;
			t.putBoolean("lit", lit);
			PeakItems.save(stack, t);
			sound(sl, p, lit ? SoundEvents.FLINTANDSTEEL_USE : SoundEvents.CANDLE_EXTINGUISH);
			return InteractionResult.SUCCESS;
		}
		if (is(def, "Flare")) {
			FLARES.add(new Flare(p.getUUID(), sl.getServer().getTickCount() + 600L));
			PeakSfx.play(sl, p.position(), "flare", SoundEvents.FIREWORK_ROCKET_LAUNCH, 1f, 1f);
			sl.sendParticles(ParticleTypes.FIREWORK, p.getX(), p.getEyeY(), p.getZ(), 30, 0.2, 0.4, 0.2, 0.05);
			PeakEnding.flare(p);
			consume(p, hand, stack);
			return InteractionResult.SUCCESS;
		}
		if (is(def, "Dynamite")) {
			ItemStack lit = stack.copyWithCount(1);
			ItemEntity e = new ItemEntity(sl, p.getX(), p.getEyeY() - 0.2, p.getZ(), lit);
			e.setDeltaMovement(p.getLookAngle().scale(0.9).add(0, 0.2, 0));
			e.setNeverPickUp();
			sl.addFreshEntity(e);
			float fuse = def.part("Dynamite").num("startingFuseTime", 3f);
			FUSES.add(new Fuse(e.getUUID(), sl.getServer().getTickCount() + Math.round(fuse * 20)));
			sound(sl, p, SoundEvents.TNT_PRIMED);
			consume(p, hand, stack);
			return InteractionResult.SUCCESS;
		}
		if (is(def, "Rocketpack")) {
			p.setDeltaMovement(p.getLookAngle().scale(1.2).add(0, 1.6, 0));
			p.hurtMarked = true;
			noFall(p, 100);
			sl.sendParticles(ParticleTypes.FLAME, p.getX(), p.getY(), p.getZ(), 40, 0.2, 0.1, 0.2, 0.05);
			sound(sl, p, SoundEvents.FIREWORK_ROCKET_LAUNCH);
			consume(p, hand, stack);
			return InteractionResult.SUCCESS;
		}
		if (is(def, "BackpackStabilizer") && !is(def, "JetpackItem")) { openPack(p, stack, def); return InteractionResult.SUCCESS; }
		if (is(def, "ShelfShroom") || is(def, "CloudFungus")) {
			BlockHitResult hit = ray(p, 20);
			if (hit == null) return InteractionResult.FAIL;
			fungus(sl, p, hit, def);
			consume(p, hand, stack);
			return InteractionResult.SUCCESS;
		}
		if (n.equals("Item_Coconut")) {
			sound(sl, p, SoundEvents.WOOD_BREAK);
			consume(p, hand, stack);
			ItemStack half = PeakItems.stack("Item_Coconut_half");
			if (p.getItemInHand(hand).isEmpty()) p.setItemInHand(hand, half); else if (!p.getInventory().add(half)) p.drop(half, false);
			ItemStack other = PeakItems.stack("Item_Coconut_half");
			if (!p.getInventory().add(other)) p.drop(other, false);
			return InteractionResult.SUCCESS;
		}
		if (n.equals("BookOfBones")) {
			for (var e : sl.getEntities((net.minecraft.world.entity.Entity) null, p.getBoundingBox().inflate(4), e -> e.entityTags().contains(PeakStatus.BONES)))
				for (ServerPlayer d : sl.getServer().getPlayerList().getPlayers())
					if (e.entityTags().contains(PeakStatus.BONES + "." + d.getUUID()) && PeakStatus.dead(d)) {
						PeakStatus.revive(d, e.position(), true);
						PeakStatus.add(d, PeakStatus.CURSE, 0.2f);
						PeakBadges.award(p, "AppliedEsoterica");
						sound(sl, p, SoundEvents.ZOMBIE_VILLAGER_CURE);
						return InteractionResult.SUCCESS;
					}
			p.sendSystemMessage(Component.literal("There are no bones here to bring back."));
			return InteractionResult.FAIL;
		}
		if (is(def, "Snowball") || is(def, "Frisbee") || is(def, "Basketball") || n.equals("Stone") || n.equals("CactusBall")) {
			ItemStack one = stack.copyWithCount(1);
			ItemEntity e = new ItemEntity(sl, p.getX(), p.getEyeY() - 0.2, p.getZ(), one);
			e.setDeltaMovement(p.getLookAngle().scale(1.4).add(0, 0.15, 0));
			e.setPickUpDelay(20);
			sl.addFreshEntity(e);
			THROWN.add(new Thrown(e.getUUID(), p.getUUID(), n, sl.getServer().getTickCount() + 60L));
			sound(sl, p, SoundEvents.SNOWBALL_THROW);
			consume(p, hand, stack);
			return InteractionResult.SUCCESS;
		}
		return null;
	}

	/** Used on a block (right click on it): what's put down or hammered in, and the rope spool. */
	static InteractionResult useOn(UseOnContext ctx, PeakItemData.Def def) {
		if (!(ctx.getPlayer() instanceof ServerPlayer p) || !(ctx.getLevel() instanceof ServerLevel sl)) return handles(def) && placeable(def) ? InteractionResult.SUCCESS : null;
		BlockPos at = ctx.getClickedPos();
		Direction face = ctx.getClickedFace();
		ItemStack stack = ctx.getItemInHand();
		InteractionHand hand = ctx.getHand();
		if (is(def, "RopeSpool")) {
			var spool = def.part("RopeSpool");
			int max = Math.min(30, Math.round(Math.min(40, PeakItems.tag(stack).getFloatOr("fuel", spool.num("ropeStartFuel", 60))) * 0.75f));
			int placed = hang(sl, p, at, face, spool.bool("isAntiRope", false), max, PeakGearBlocks.ROPE);
			if (placed == 0) return InteractionResult.FAIL;
			PeakStatus.of(p).rope += placed;
			if (PeakStatus.of(p).rope >= 100) PeakBadges.award(p, "KnotTying");
			PeakSfx.play(sl, at, "rope_end", SoundEvents.WOOL_PLACE, 1f, 0.8f);
			consume(p, hand, stack);
			return InteractionResult.SUCCESS;
		}
		if (is(def, "ClimbingSpikeComponent")) {
			if (face.getAxis().isVertical()) return InteractionResult.FAIL;
			BlockPos q = at.relative(face);
			if (!sl.getBlockState(q).canBeReplaced()) return InteractionResult.FAIL;
			sl.setBlock(q, PeakGearBlocks.PITON.defaultBlockState().setValue(PeakGearBlocks.PitonBlock.FACING, face), 3);
			PeakSfx.play(sl, q, "piton_hammer", SoundEvents.ANVIL_PLACE, 0.8f, 1.6f);
			if (++PeakStatus.of(p).pitons >= 10) PeakBadges.award(p, "Bouldering");
			consume(p, hand, stack);
			return InteractionResult.SUCCESS;
		}
		if (is(def, "MagicBean") && face == Direction.UP) {
			BlockPos base = at.above();
			consume(p, hand, stack);
			sl.playSound(null, base, SoundEvents.BIG_DRIPLEAF_PLACE, SoundSource.PLAYERS, 1f, 0.6f);
			PeakActions.later(sl.getServer(), 40, () -> {
				for (int i = 0; i < 30 && sl.getBlockState(base.above(i)).canBeReplaced(); i++) sl.setBlock(base.above(i), PeakGearBlocks.VINE_ROPE.defaultBlockState(), 3);
				sl.sendParticles(ParticleTypes.HAPPY_VILLAGER, base.getX() + 0.5, base.getY() + 1, base.getZ() + 0.5, 30, 0.3, 4, 0.3, 0);
			});
			return InteractionResult.SUCCESS;
		}
		if (face != Direction.UP) return null;
		BlockPos on = at.above();
		if (!sl.getBlockState(on).canBeReplaced()) return null;
		if (is(def, "CheckpointConstructable")) {
			sl.setBlock(on, Blocks.BANNER.pick(net.minecraft.world.item.DyeColor.WHITE).defaultBlockState(), 3);
			PeakStatus.plantFlag(p, on);
			HeroTitle.card(p, "CHECKPOINT SET");
			consume(p, hand, stack);
			return InteractionResult.SUCCESS;
		}
		if (is(def, "ScoutEffigy")) {
			List<ServerPlayer> dead = new ArrayList<>();
			for (ServerPlayer d : sl.getServer().getPlayerList().getPlayers()) if (PeakStatus.dead(d)) dead.add(d);
			if (dead.isEmpty()) { p.sendSystemMessage(Component.literal("Nobody to bring back.")); return InteractionResult.FAIL; }
			ServerPlayer d = dead.get(p.getRandom().nextInt(dead.size()));
			PeakStatus.revive(d, Vec3.atBottomCenterOf(on).add(0, 1, 0), false);
			sl.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, on.getX() + 0.5, on.getY() + 1, on.getZ() + 0.5, 60, 0.4, 0.8, 0.4, 0.3);
			sl.playSound(null, on, SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1f, 1f);
			consume(p, hand, stack);
			return InteractionResult.SUCCESS;
		}
		if (def.item().equals("PortableStovetopItem")) {
			sl.setBlock(on, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true), 3);
			consume(p, hand, stack);
			return InteractionResult.SUCCESS;
		}
		if (def.item().equals("ScoutCannonItem")) {
			Direction d = Direction.getApproximateNearest(p.getLookAngle());
			if (d == Direction.DOWN) d = Direction.UP;
			sl.setBlock(on, PeakGearBlocks.SCOUT_CANNON.defaultBlockState().setValue(PeakGearBlocks.CannonBlock.FACING, d), 3);
			consume(p, hand, stack);
			return InteractionResult.SUCCESS;
		}
		return null;
	}

	static boolean placeable(PeakItemData.Def def) {
		return is(def, "RopeSpool") || is(def, "ClimbingSpikeComponent") || is(def, "CheckpointConstructable") || is(def, "ScoutEffigy") || is(def, "Constructable") || is(def, "MagicBean");
	}

	// ---- held over time ----

	static int useTicks(ItemStack stack, PeakItemData.Def def) {
		return is(def, "BugleSFX") || is(def, "Actions_Binoculars") || def.item().equals("Megaphone") ? 72000 : 0;
	}

	static ItemUseAnimation animation(PeakItemData.Def def) {
		if (is(def, "BugleSFX") || def.item().equals("Megaphone")) return ItemUseAnimation.TOOT_HORN;
		if (is(def, "Actions_Binoculars")) return ItemUseAnimation.SPYGLASS;
		return ItemUseAnimation.NONE;
	}

	static ItemStack finish(ServerPlayer p, ItemStack stack, PeakItemData.Def def) { return stack; }

	static boolean release(ServerPlayer p, ItemStack stack, PeakItemData.Def def, int remaining) { return false; }

	/** While a bugle is played: its notes, and what they do. */
	static void useTick(LivingEntity e, ItemStack stack, PeakItemData.Def def, int remaining) {
		if (!(e instanceof ServerPlayer p) || !(p.level() instanceof ServerLevel sl)) return;
		int held = 72000 - remaining;
		if (is(def, "BugleSFX") || def.item().equals("Megaphone")) {
			if (held % 16 == 0) {
				float pitch = (float) (0.7 + 0.6 * (1 - (p.getXRot() + 90) / 180.0));
				if (def.item().equals("Megaphone")) sl.playSound(null, p.blockPosition(), SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 2.5f, pitch);
				else if (held % 48 == 0) PeakSfx.play(sl, p.position(), "bugle_" + PeakSfx.safe(def.item()), SoundEvents.NOTE_BLOCK_DIDGERIDOO.value(), 2.5f, pitch);
				for (Pig pig : sl.getEntitiesOfClass(Pig.class, p.getBoundingBox().inflate(12), g -> g.entityTags().contains(PeakCreatures.TAG))) {
					pig.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, p.getEyePosition());
					sl.sendParticles(ParticleTypes.NOTE, pig.getX(), pig.getEyeY() + 0.5, pig.getZ(), 1, 0, 0, 0, 0);
					PeakBadges.award(p, "AnimalSerenading");
				}
			}
			var magic = def.part("MagicBugle");
			if (magic != null && held % 2 == 0) {
				var t = PeakItems.tag(stack);
				float fuel = t.getFloatOr("fuel", 0);
				if (fuel > 0) {
					t.putFloat("fuel", Math.max(0, fuel - 0.1f));
					PeakItems.save(stack, t);
					var mass = magic.obj("massAffliction");
					if (mass != null) for (ServerPlayer o : sl.getServer().getPlayerList().getPlayers())
						if (PeakWorld.scout(o) && o.distanceTo(p) < 10) PeakStatus.affliction(o, mass);
				}
			}
			if (held == 20 && def.item().startsWith("Bugle_Scoutmaster")) PeakScoutmaster.call(p, 60);
		}
	}

	/** Every tick an item is carried: a lit lantern's fuel, light and warmth; a jetpack's fuel. */
	static void carried(ServerPlayer p, ItemStack stack, PeakItemData.Def def, boolean held) {
		if (def == null) return;
		if ((is(def, "Lantern") || is(def, "Candle"))) {
			var t = PeakItems.tag(stack);
			if (!t.getBooleanOr("lit", false)) return;
			float fuel = t.getFloatOr("fuel", 0) - 1f / 20f;
			if (fuel <= 0) { t.putBoolean("lit", false); fuel = 0; }
			t.putFloat("fuel", fuel);
			if (p.tickCount % 20 == 0 || fuel == 0) PeakItems.save(stack, t);
			if (!held) return;
			LIT.put(p.getUUID(), p.level().getServer().getTickCount());
			if (p.tickCount % 10 == 0)
				for (var part : def.all("StatusField")) {
					int i = PeakStatus.index(part.str("statusType", "Cold"));
					float per = part.num("statusAmountPerSecond", -0.01f), r = part.num("radius", 3f);
					for (ServerPlayer o : p.level().getServer().getPlayerList().getPlayers())
						if (PeakWorld.scout(o) && o.distanceTo(p) <= r) { if (per < 0) PeakStatus.sub(o, i, -per * 0.5f); else PeakStatus.add(o, i, per * 0.5f); }
				}
		}
		if (is(def, "JetpackItem") && !p.onGround() && p.getLastClientInput().jump()) {
			var t = PeakItems.tag(stack);
			float fuel = t.getFloatOr("fuel", 0);
			if (fuel > 0) {
				t.putFloat("fuel", Math.max(0, fuel - 1f));
				PeakItems.save(stack, t);
				noFall(p, 40);
				((ServerLevel) p.level()).sendParticles(ParticleTypes.FLAME, p.getX(), p.getY(), p.getZ(), 2, 0.1, 0, 0.1, 0.01);
			}
		}
	}

	// ---- in the world over time ----

	private static final Map<UUID, List<Long>> BALLOONS = new HashMap<>();
	private static final Map<UUID, Long> NO_FALL = new HashMap<>();
	private static final Map<UUID, Integer> LIT = new HashMap<>();
	private static final Map<UUID, BlockPos> LIGHTS = new HashMap<>();
	private record Fuse(UUID item, long at) { }
	private static final List<Fuse> FUSES = new ArrayList<>();
	private record Flare(UUID player, long until) { }
	private static final List<Flare> FLARES = new ArrayList<>();
	private record Cloud(BlockPos pos, long until) { }
	private static final List<Cloud> CLOUDS = new ArrayList<>();
	private record Thrown(UUID item, UUID by, String kind, long until) { }
	private static final List<Thrown> THROWN = new ArrayList<>();

	static int balloons(Player p) { List<Long> l = BALLOONS.get(p.getUUID()); return l == null ? 0 : l.size(); }

	/**
	 * The world stopping: what's put in it for a while is taken out (a lantern's light, the clouds, the balloons shown),
	 * and the timings forgotten (they count the server's ticks, which start again at 0 with the next world).
	 */
	static void stop(MinecraftServer server) {
		ServerLevel level = server.overworld();
		for (BlockPos b : LIGHTS.values()) if (level.getBlockState(b).is(Blocks.LIGHT)) level.setBlock(b, Blocks.AIR.defaultBlockState(), 3);
		for (Cloud c : CLOUDS) if (level.getBlockState(c.pos).is(Blocks.WOOL.pick(net.minecraft.world.item.DyeColor.WHITE))) level.setBlock(c.pos, Blocks.AIR.defaultBlockState(), 3);
		for (var l : SHOWN.values()) for (var d : l) if (d != null) d.discard();
		BALLOONS.clear(); NO_FALL.clear(); LIT.clear(); LIGHTS.clear(); FUSES.clear(); FLARES.clear(); CLOUDS.clear(); THROWN.clear(); SHOWN.clear();
	}

	/** A player gone: their lantern's light out, their balloons gone. */
	static void left(ServerPlayer p) {
		BlockPos had = LIGHTS.remove(p.getUUID());
		if (had != null && p.level().getBlockState(had).is(Blocks.LIGHT)) p.level().setBlock(had, Blocks.AIR.defaultBlockState(), 3);
		LIT.remove(p.getUUID()); NO_FALL.remove(p.getUUID()); BALLOONS.remove(p.getUUID());
		var shown = SHOWN.remove(p.getUUID());
		if (shown != null) for (var d : shown) if (d != null) d.discard();
	}

	private static final Map<UUID, List<net.minecraft.world.entity.Display.ItemDisplay>> SHOWN = new HashMap<>();

	/** A balloon over the scout's head (PEAK's, drawn as its icon, facing the camera), riding along with it. */
	private static void balloonOver(ServerLevel level, ServerPlayer p, ItemStack balloon) {
		var d = net.minecraft.world.entity.EntityTypes.ITEM_DISPLAY.create(level, net.minecraft.world.entity.EntitySpawnReason.TRIGGERED);
		if (d == null) return;
		var shown = SHOWN.computeIfAbsent(p.getUUID(), u -> new ArrayList<>());
		shown.removeIf(x -> x == null || x.isRemoved());
		int i = shown.size();
		d.setItemStack(balloon);
		d.setBillboardConstraints(net.minecraft.world.entity.Display.BillboardConstraints.CENTER);
		d.setTransformation(new com.mojang.math.Transformation(new org.joml.Vector3f((i % 3 - 1) * 0.45f, 1.4f + (i / 3) * 0.5f, 0), null, new org.joml.Vector3f(1.1f, 1.1f, 1.1f), null));
		d.snapTo(p.getX(), p.getY() + 2, p.getZ(), 0, 0);
		d.addTag("minepeak.balloon");
		level.addFreshEntity(d);
		d.startRiding(p, true, false);
		shown.add(d);
	}

	static void noFall(ServerPlayer p, int ticks) { NO_FALL.put(p.getUUID(), (long) p.level().getServer().getTickCount() + ticks); p.fallDistance = 0; }

	static boolean noFall(Player p) { Long t = NO_FALL.get(p.getUUID()); return t != null && p.level().getServer() != null && t > p.level().getServer().getTickCount(); }

	static void tick(MinecraftServer server) {
		long now = server.getTickCount();
		ServerLevel level = server.overworld();
		// balloons pop after 10 s
		for (var e : BALLOONS.entrySet()) {
			int before = e.getValue().size();
			e.getValue().removeIf(t -> t <= now);
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p != null && e.getValue().size() < before) {
				PeakSfx.play(level, p.position().add(0, 2, 0), "balloon_pop", SoundEvents.BUBBLE_POP, 1.5f, 0.8f);
				var shown = SHOWN.getOrDefault(e.getKey(), new ArrayList<>());
				while (shown.size() > e.getValue().size()) { var d = shown.remove(shown.size() - 1); if (d != null) d.discard(); }
			}
			if (p != null && !e.getValue().isEmpty() && p.fallDistance > 2) p.fallDistance = 2;
		}
		// lit lanterns: a light that moves with whoever holds one
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			boolean lit = LIT.getOrDefault(p.getUUID(), -10) >= now - 2;
			BlockPos had = LIGHTS.get(p.getUUID());
			BlockPos want = lit ? BlockPos.containing(p.getEyePosition()) : null;
			if (had != null && !had.equals(want)) { if (p.level().getBlockState(had).is(Blocks.LIGHT)) p.level().setBlock(had, Blocks.AIR.defaultBlockState(), 3); LIGHTS.remove(p.getUUID()); }
			if (want != null && !want.equals(had) && p.level().getBlockState(want).isAir()) {
				p.level().setBlock(want, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 14), 3);
				LIGHTS.put(p.getUUID(), want);
			}
			if (NO_FALL.containsKey(p.getUUID()) && noFall(p)) p.fallDistance = 0;
		}
		// dynamite
		for (Iterator<Fuse> it = FUSES.iterator(); it.hasNext(); ) {
			Fuse f = it.next();
			var e = level.getEntity(f.item);
			if (e == null) { it.remove(); continue; }
			if (now % 4 == 0) level.sendParticles(ParticleTypes.SMOKE, e.getX(), e.getY() + 0.3, e.getZ(), 2, 0.02, 0.02, 0.02, 0.01);
			if (f.at <= now) {
				it.remove();
				level.explode(null, e.getX(), e.getY(), e.getZ(), 3f, Level.ExplosionInteraction.NONE);
				PeakSfx.play(level, e.position(), "dynamite_explode", null, 2f, 1f);
				e.discard();
			}
		}
		// lit flares: red smoke
		FLARES.removeIf(f -> f.until <= now);
		for (Flare f : FLARES) {
			ServerPlayer p = server.getPlayerList().getPlayer(f.player);
			if (p != null && now % 3 == 0)
				level.sendParticles(new DustParticleOptions(0xFF2A1A, 2f), p.getX(), p.getEyeY() + 0.4, p.getZ(), 3, 0.1, 0.2, 0.1, 0);
		}
		// clouds fade
		for (Iterator<Cloud> it = CLOUDS.iterator(); it.hasNext(); ) {
			Cloud c = it.next();
			if (c.until <= now) { if (level.getBlockState(c.pos).is(Blocks.WOOL.pick(net.minecraft.world.item.DyeColor.WHITE))) level.setBlock(c.pos, Blocks.AIR.defaultBlockState(), 3); it.remove(); }
		}
		// thrown things that hit a scout
		for (Iterator<Thrown> it = THROWN.iterator(); it.hasNext(); ) {
			Thrown t = it.next();
			var e = level.getEntity(t.item);
			if (e == null || t.until <= now || e.onGround()) { it.remove(); continue; }
			for (ServerPlayer o : level.getEntitiesOfClass(ServerPlayer.class, e.getBoundingBox().inflate(0.3), o -> !o.getUUID().equals(t.by) && PeakWorld.scout(o))) {
				switch (t.kind) {
					case "Snowball" -> PeakStatus.add(o, PeakStatus.COLD, 0.05f);
					case "CactusBall" -> PeakStatus.add(o, PeakStatus.THORNS, 0.075f);
					default -> PeakStatus.add(o, PeakStatus.INJURY, 0.05f);
				}
				level.playSound(null, o.blockPosition(), SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1f, 1.2f);
				e.setDeltaMovement(Vec3.ZERO);
				it.remove();
				break;
			}
		}
	}

	// ---- the pieces ----

	/** A ray from the player's eyes: the block it hits within range, or null. */
	static BlockHitResult ray(ServerPlayer p, double range) {
		Vec3 from = p.getEyePosition(), to = from.add(p.getLookAngle().scale(range));
		BlockHitResult hit = p.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		return hit.getType() == HitResult.Type.BLOCK ? hit : null;
	}

	/**
	 * A rope from where it's fixed (at, on face): along a wall, it hangs from the air beside it; on the ground, from the
	 * nearest edge the player faces (or right there, as a ladder down a hole); up instead for anti-rope. Placed down
	 * (or up) through the air till something's in the way. How much was placed.
	 */
	static int hang(ServerLevel level, ServerPlayer p, BlockPos at, Direction face, boolean up, int max, net.minecraft.world.level.block.Block rope) {
		BlockPos start = at.relative(face);
		if (face == Direction.UP && !up) {
			Direction ahead = p.getDirection();
			BlockPos q = start;
			for (int i = 0; i < 4; i++) {
				if (level.getBlockState(q.below()).canBeReplaced()) { start = q; break; }
				q = q.relative(ahead);
				if (!level.getBlockState(q).canBeReplaced()) break;
			}
		}
		int n = 0;
		BlockPos q = start;
		while (n < max && level.getBlockState(q).canBeReplaced() && level.getFluidState(q).isEmpty() && level.isInWorldBounds(q)) {
			level.setBlock(q, rope.defaultBlockState(), 3);
			n++;
			q = up ? q.above() : q.below();
		}
		return n;
	}

	/** A line of rope blocks through the air from one point to another (the chain launcher's sagging vine). */
	static int line(ServerLevel level, Vec3 from, Vec3 to) { return line(level, from, to, PeakGearBlocks.VINE_ROPE); }

	static int line(ServerLevel level, Vec3 from, Vec3 to, net.minecraft.world.level.block.Block block) {
		double len = from.distanceTo(to);
		int steps = (int) Math.ceil(len * 2), n = 0;
		BlockPos last = null;
		for (int i = 0; i <= steps; i++) {
			double t = i / (double) steps;
			Vec3 v = from.lerp(to, t).add(0, -Math.sin(Math.PI * t) * len * 0.08, 0);   // (it sags)
			BlockPos q = BlockPos.containing(v);
			if (q.equals(last)) continue;
			last = q;
			if (level.getBlockState(q).canBeReplaced() && level.getFluidState(q).isEmpty()) { level.setBlock(q, block.defaultBlockState(), 3); n++; }
		}
		return n;
	}

	private static void claw(ServerLevel level, ServerPlayer p, InteractionHand hand, ItemStack stack, PeakItemData.Def def) {
		var hook = def.part("RescueHook");
		double range = hook.num("range", 30), down = hook.num("rangeDownward", 40);
		double r = p.getXRot() > 30 ? down : range;
		Vec3 from = p.getEyePosition(), to = from.add(p.getLookAngle().scale(r));
		BlockHitResult block = p.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		double blockDist = block.getType() == HitResult.Type.BLOCK ? block.getLocation().distanceTo(from) : r;
		ServerPlayer target = null;
		double best = blockDist;
		for (ServerPlayer o : level.getServer().getPlayerList().getPlayers()) {
			if (o == p || o.isSpectator()) continue;
			var c = o.getBoundingBox().inflate(0.4).clip(from, to);
			if (c.isPresent() && c.get().distanceTo(from) < best) { best = c.get().distanceTo(from); target = o; }
		}
		sound(level, p, SoundEvents.FISHING_BOBBER_THROW);
		if (target != null) {
			Vec3 pull = p.position().subtract(target.position());
			double d = pull.length();
			target.setDeltaMovement(pull.normalize().scale(Math.min(3.0, 0.6 + d * 0.08)).add(0, 0.5, 0));
			target.hurtMarked = true;
			noFall(target, 80);
			if (PeakStatus.out(target) && d > 30) PeakBadges.award(p, "DisasterResponse");
		} else if (block.getType() == HitResult.Type.BLOCK && blockDist >= 5) {
			Vec3 pull = block.getLocation().subtract(p.position());
			p.setDeltaMovement(pull.normalize().scale(Math.min(3.0, 0.6 + pull.length() * 0.08)).add(0, 0.4, 0));
			p.hurtMarked = true;
			noFall(p, 80);
		} else return;
		if (def.totalUses() > 0) use1(p, hand, stack, def, "uses");
	}

	private static void fungus(ServerLevel level, ServerPlayer p, BlockHitResult hit, PeakItemData.Def def) {
		BlockPos at = hit.getBlockPos().relative(hit.getDirection());
		String n = def.item();
		if (n.equals("BounceShroom")) {
			if (level.getBlockState(at).canBeReplaced()) level.setBlock(at, Blocks.SLIME_BLOCK.defaultBlockState(), 3);
		} else if (n.equals("HealingPuffShroom")) {
			level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 40, 1.5, 1, 1.5, 0);
			for (ServerPlayer o : level.getServer().getPlayerList().getPlayers())
				if (PeakWorld.scout(o) && o.position().distanceTo(Vec3.atCenterOf(at)) < 4) { PeakStatus.sub(o, PeakStatus.INJURY, 0.2f); PeakStatus.sub(o, PeakStatus.POISON, 0.2f); }
		} else if (n.equals("CloudFungus")) {
			BlockPos c = hit.getDirection() == Direction.UP ? at : at.below();
			for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
				BlockPos q = c.offset(dx, 0, dz);
				if (level.getBlockState(q).canBeReplaced()) { level.setBlock(q, Blocks.WOOL.pick(net.minecraft.world.item.DyeColor.WHITE).defaultBlockState(), 3); CLOUDS.add(new Cloud(q, level.getServer().getTickCount() + 300L)); }
			}
			level.sendParticles(ParticleTypes.CLOUD, c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5, 40, 1.2, 0.3, 1.2, 0.02);
		} else {
			// a shelf fungus: a ledge sticking out of the wall, three wide
			Direction out = hit.getDirection().getAxis().isHorizontal() ? hit.getDirection() : p.getDirection().getOpposite();
			Direction side = out.getClockWise();
			for (int i = -1; i <= 1; i++) {
				BlockPos q = at.relative(side, i);
				if (level.getBlockState(q).canBeReplaced()) level.setBlock(q, Blocks.BROWN_MUSHROOM_BLOCK.defaultBlockState(), 3);
			}
		}
		level.playSound(null, at, SoundEvents.FUNGUS_PLACE, SoundSource.PLAYERS, 1f, 1f);
	}

	/** A backpack (or fanny pack) opened: its four places (PEAK's) and the pockets (BackpackMenu), kept in the item. */
	static void openPack(ServerPlayer p, ItemStack stack, PeakItemData.Def def) {
		int size = BackpackMenu.SIZE;
		SimpleContainer box = new SimpleContainer(size) {
			@Override
			public void setChanged() {
				super.setChanged();
				List<ItemStack> l = new ArrayList<>();
				for (int i = 0; i < size; i++) l.add(getItem(i).copy());
				stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(l));
			}
		};
		var contents = stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
		NonNullList<ItemStack> list = NonNullList.withSize(size, ItemStack.EMPTY);
		contents.copyInto(list);
		for (int i = 0; i < size; i++) box.setItem(i, list.get(i));
		int open = p.getInventory().getSelectedSlot();
		p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new BackpackMenu(id, inv, box, open, stack), Component.literal(def.name())));
	}

	/** The scout cannon fires whoever stands at its mouth (PEAK's launch, then no fall damage for a while). */
	static void fireCannon(ServerLevel level, BlockPos pos, ServerPlayer p) {
		var s = level.getBlockState(pos);
		if (!s.is(PeakGearBlocks.SCOUT_CANNON) || p.position().distanceTo(Vec3.atCenterOf(pos)) > 3) return;
		Direction d = s.getValue(PeakGearBlocks.CannonBlock.FACING);
		Vec3 dir = new Vec3(d.getStepX(), d.getStepY() + 0.6, d.getStepZ()).normalize();
		p.setDeltaMovement(dir.scale(3.2));
		p.hurtMarked = true;
		noFall(p, 100);
		level.sendParticles(ParticleTypes.EXPLOSION, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, 1, 0, 0, 0, 0);
		level.playSound(null, pos, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 1f, 1.2f);
		PeakBadges.award(p, "Daredevil");
	}

	/** A blowgun's dart: the scout it hits gets its effects. */
	static void dart(ServerPlayer user, PeakItemData.Part p) {
		double r = p.num("maxDistance", 30);
		Vec3 from = user.getEyePosition(), to = from.add(user.getLookAngle().scale(r));
		for (ServerPlayer o : user.level().getServer().getPlayerList().getPlayers()) {
			if (o == user || !PeakWorld.scout(o)) continue;
			if (o.getBoundingBox().inflate(p.num("dartCollisionSize", 0.3f)).clip(from, to).isPresent()) {
				for (JsonElement e : PeakItemData.array(p.f(), "afflictionsOnHit")) if (e.isJsonObject()) PeakStatus.affliction(o, e.getAsJsonObject());
				user.level().playSound(null, o.blockPosition(), SoundEvents.ARROW_HIT_PLAYER, SoundSource.PLAYERS, 1f, 1f);
				return;
			}
		}
	}

	/**
	 * PEAK's ritual dagger, used on a friend: they turn to stone (die, unless a checkpoint flag saves them), and everyone
	 * else nearby has every status cleared, half a bar of bonus stamina and 10 s of infinite stamina.
	 */
	static void dagger(ServerPlayer user, ServerPlayer victim, PeakItemData.Def def) {
		var part = def.part("RitualDaggerFeedBehavior");
		var sc = PeakStatus.of(victim);
		sc.out = true;
		PeakStatus.die(victim, sc);
		for (ServerPlayer o : user.level().getServer().getPlayerList().getPlayers()) {
			if (o == victim || !PeakWorld.scout(o) || o.distanceTo(user) > 20) continue;
			if (PeakStatus.sum(PeakStatus.of(o)) >= 0.75f) PeakBadges.award(o, "LastResort");
			PeakStatus.clear(PeakStatus.of(o), true);
			PeakStatus.bonus(o, part != null ? part.num("bonusStamina", 0.5f) : 0.5f);
			PeakStatus.timed(o, "InfiniteStamina", part != null ? part.num("infiniteStaminaTime", 10) : 10);
		}
		user.level().playSound(null, victim.blockPosition(), SoundEvents.WITHER_SPAWN, SoundSource.PLAYERS, 0.6f, 1.4f);
		consume(user, InteractionHand.MAIN_HAND, user.getMainHandItem());
	}

	/** Other actions of PEAK's items that MinePeak does here. */
	static void action(ServerPlayer user, ServerPlayer target, PeakItemData.Part p, PeakItemData.Def def) {
		if (p.type().equals("Action_Spawn") && def.item().equals("PandorasBox")) {
			IslandData data = MinePeakServer.island(user.level().getServer());
			if (data == null) return;
			String[] pools = { "LuggageBeach", "LuggageJungle", "LuggageTundra", "LuggageCaldera", "LuggageMesa", "LuggageRoots" };
			for (ItemStack s : PeakLoot.draw(data, pools[user.getRandom().nextInt(pools.length)], 3, user.getRandom()))
				if (!user.getInventory().add(s)) user.drop(s, false);
		}
	}

	/** To the start of one of PEAK's stages (its segment's name: Beach, Tropics, Alpine, Caldera, TheKiln). */
	static void warpToStage(ServerPlayer p, String segment) {
		IslandData data = MinePeakServer.island(p.level().getServer());
		if (data == null) return;
		List<int[]> fires = new ArrayList<>(data.fires);
		fires.sort((a, b) -> Integer.compare(b[2], a[2]));
		int i = switch (segment) { case "Tropics" -> 1; case "Alpine" -> 2; case "Caldera" -> 3; case "TheKiln", "Peak" -> 4; default -> 0; };
		if (i == 0 || fires.isEmpty()) { var r = p.level().getRespawnData().pos(); p.teleportTo(r.getX() + 0.5, r.getY(), r.getZ() + 0.5); return; }
		int[] f = fires.get(Math.min(i - 1, fires.size() - 1));
		p.teleportTo(f[0] + 0.5, f[1] + 1, f[2] + 2.5);
	}

	private static void use1(ServerPlayer p, InteractionHand hand, ItemStack stack, PeakItemData.Def def, String what) {
		var t = PeakItems.tag(stack);
		if (what.equals("fuel") && t.contains("fuel")) {
			float f = t.getFloatOr("fuel", 1) - 1;
			if (f <= 0) { consume(p, hand, stack); return; }
			t.putFloat("fuel", f);
		} else {
			int u = t.getIntOr("uses", Math.max(1, def.totalUses())) - 1;
			if (u <= 0) { consume(p, hand, stack); return; }
			t.putInt("uses", u);
		}
		PeakItems.save(stack, t);
	}

	private static void consume(ServerPlayer p, InteractionHand hand, ItemStack stack) {
		stack.shrink(1);
		if (stack.isEmpty()) p.setItemInHand(hand, ItemStack.EMPTY);
	}

	private static void sound(ServerLevel l, ServerPlayer p, net.minecraft.sounds.SoundEvent s) {
		l.playSound(null, p.blockPosition(), s, SoundSource.PLAYERS, 1f, 1f);
	}

	static boolean holding(Player p, String part) {
		var def = PeakItems.def(p.getMainHandItem());
		return def != null && def.has(part);
	}

	static AABB box(BlockPos p, double r) { return new AABB(p).inflate(r); }
	static BlockState air() { return Blocks.AIR.defaultBlockState(); }
	static EquipmentSlot main() { return EquipmentSlot.MAINHAND; }
}
