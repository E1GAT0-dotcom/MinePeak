package gamemashup.minepeak;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * PEAK's climbing and stamina on the client, with PEAK's controls: hold the climb button (left click) at a wall to take
 * hold of it, or jump at one in the air (PEAK's "jump to climb"); on it, forward goes up, nothing
 * pressed hangs on, back slides down; let go of the button, or jump, to let go. Climbing uses stamina, more going up; so
 * do sprinting and jumping. At
 * none you lose your grip (and can't sprint), and fall. Standing on the ground, or hanging by a piton, it comes back after
 * a moment. Its most is what PEAK's statuses leave of the bar (PeakStatusClient); climbing costs more in PEAK's wind and at
 * the harder Ascents, less with climbing chalk, nothing with infinite stamina. PeakHud draws PEAK's bar.
 */
public final class PeakClimbClient {
	private PeakClimbClient() { }

	// PEAK's numbers (per second, of a full bar of 1): climbing 0.2 at full push (0.02 just holding on), sprinting
	// 0.025, a jump (PEAK's is set on its prefab: MinePeak's guess), back at 0.2 a second on the ground after 1 s (2 s
	// when it ran out)
	static final float CLIMB_UP = 0.2f, CLIMB_HOLD = 0.02f, SPRINT = 0.025f, JUMP = 0.05f, REGEN = 0.2f, REGEN_DELAY = 1.0f, REGEN_DELAY_OUT = 2.0f;

	static float stamina = 1f, bonus = 0f;
	private static boolean ranOut;
	private static float sinceUse, shown = 1f;
	private static boolean climbing, wasOnGround, hanging;
	private static boolean byJump, jumpHeld, grabHeld;   // (taken hold of by a jump, not the button; the keys last tick)
	private static int mantle;

	static void init() { }

	/** Stamina spent on something else (opening a glider): from the bar, then the bonus. */
	static void spend(float amount) {
		float fromBar = Math.min(stamina, amount);
		stamina -= fromBar;
		bonus = Math.max(0, bonus - (amount - fromBar));
		sinceUse = 0;
	}

	/** Is the player by a piton (one in reach of its hands)? Hanging there, stamina comes back and the wind can't push. */
	static boolean onPiton(Player p) {
		var level = p.level();
		var b = net.minecraft.core.BlockPos.containing(p.getX(), p.getY() + 1, p.getZ());
		for (var q : net.minecraft.core.BlockPos.betweenClosed(b.offset(-1, -1, -1), b.offset(1, 1, 1))) {
			var s = level.getBlockState(q);
			if (s.is(PeakGearBlocks.PITON) || s.is(PeakGearBlocks.RUSTY_PITON)) return true;
		}
		return false;
	}

	/**
	 * Is a left click PEAK's climb button here, and nothing else (no swing, no breaking: a scout doesn't break PEAK's
	 * island)? For a scout on a wall, or with a block (not a creature) under the crosshair.
	 */
	public static boolean leftClickIsClimb(Minecraft mc) {
		var p = mc.player;
		if (p == null || !PeakWorld.scout(p) || !PeakSounds.inPeakWorld(mc)) return false;
		if (climbing) return true;
		return mc.hitResult != null && mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK;
	}

	/** Is the local player on a wall? */
	public static boolean climbing() { return climbing; }

	/** Is this (the local) player on a wall? */
	static boolean onWall(Player p) { return p == Minecraft.getInstance().player && climbing; }

	/** On a wall and not sliding down it (back not pressed): it hangs on, as on a ladder with sneak held. */
	public static boolean hanging(Player p) { return onWall(p) && hanging; }

	static void tick(Minecraft mc) {
		var p = mc.player;
		if (p == null) return;
		boolean was = climbing;
		float dt = 1f / 20f;
		if (!PeakSounds.inPeakWorld(mc) || p.isCreative() || p.isSpectator() || PeakStatusClient.flag(PeakStatus.F_OUT)) {
			climbing = false;
			stamina = 1f;
		} else {
			var keys = mc.options;
			Vec3 look = Vec3.directionFromRotation(0, p.getYRot());
			// (a wall to take hold of reaches the chest: a step a jump clears isn't climbed; holding on, a wall anywhere from
			// the knees up will do)
			boolean wall = (climbing ? PeakClimb.touching(p, null) : PeakClimb.touching(p, look, 1.0)) && !p.isInWater() && !p.isPassenger() && mantle <= 0;
			boolean was0 = climbing;
			// PEAK's climbing: the climb button (left click) held takes hold of a wall; so does a jump in the air at one;
			// letting go of the button (or a jump, on the wall) lets go. (PEAK's needs empty hands, its left click being
			// its items' use too; here items are used with the right, and there's no slot of empty hands)
			boolean grab = keys.keyAttack.isDown() && mc.gui.screen() == null;
			boolean jumped = keys.keyJump.isDown() && !jumpHeld, released = grabHeld && !keys.keyAttack.isDown();
			jumpHeld = keys.keyJump.isDown();
			grabHeld = keys.keyAttack.isDown();
			if (!climbing) {
				climbing = wall && stamina + bonus > 0 && (grab || !p.onGround() && jumped);
				byJump = climbing && !grab;
			} else {
				boolean letGo = released || jumped || !byJump && !keys.keyAttack.isDown();
				climbing = wall && stamina + bonus > 0 && !letGo;
			}
			// at the top of the wall (the head past its edge): up over it, onto the ledge, as PEAK's scouts do
			if (was0 && !climbing && !keys.keyShift.isDown() && !PeakClimb.touching(p, null, 1.0) && PeakClimb.touching(p, null, 0.0)
				&& (keys.keyUp.isDown() || keys.keyJump.isDown())) {
				Vec3 ahead = Vec3.directionFromRotation(0, p.getYRot());
				p.setDeltaMovement(ahead.x * 0.22, 0.46, ahead.z * 0.22);
				mantle = 8;
			}
			if (mantle > 0) mantle--;
			hanging = !keys.keyDown.isDown();
			float use = 0;
			boolean piton = climbing && onPiton(p);
			if (climbing && !p.onGround() && !piton) use += (keys.keyUp.isDown() ? CLIMB_UP : CLIMB_HOLD) * PeakStatusClient.climbMult();
			if (p.isSprinting() && !climbing) use += SPRINT;
			if (wasOnGround && !p.onGround() && !climbing && p.getDeltaMovement().y > 0.3) use += JUMP / dt;   // (a jump)
			if (PeakStatusClient.flag(PeakStatus.F_INFINITE)) use = 0;   // (PEAK's infinite stamina)
			var st = PeakStatusClient.state;
			if (st != null && st.staminaDrain() > 0) use += st.staminaDrain();
			if (use > 0) {
				float need = use * dt;
				float fromBar = Math.min(stamina, need);
				stamina -= fromBar;
				bonus = Math.max(0, bonus - (need - fromBar));   // (the morale boost's bonus stamina, once the bar's out)
				sinceUse = 0;
				if (stamina <= 0.005f) { if (!ranOut) PeakSounds.once("no_stamina", null, 0.8f, 1f); ranOut = true; }
			} else if (p.onGround() || piton) {
				sinceUse += dt;
				if (sinceUse >= (ranOut ? REGEN_DELAY_OUT : REGEN_DELAY)) { stamina += REGEN * dt; ranOut = false; }
			}
			stamina = Math.max(0, Math.min(max(p), stamina));
			if (stamina <= 0 && bonus <= 0) { climbing = false; if (p.isSprinting()) p.setSprinting(false); }
			wasOnGround = p.onGround();
		}
		if (climbing && !was) PeakSounds.once("climb_grab", p.position(), 0.6f, 1f);
		if (climbing != was && ClientPlayNetworking.canSend(PeakClimb.Climbing.TYPE)) ClientPlayNetworking.send(new PeakClimb.Climbing(climbing));
	}

	/** The most stamina the player can have now: what PEAK's statuses leave of the bar (at least a little). */
	static float max(Player p) {
		return Math.max(0.05f, 1f - PeakStatusClient.sum());
	}
}
