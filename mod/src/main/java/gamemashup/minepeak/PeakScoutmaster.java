package gamemashup.minepeak;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * PEAK's Scoutmaster (a vindicator in his place): with two or more scouts up, every 30 s there's a 10% chance he comes
 * for the highest, when they're 100 blocks above the next and below PEAK's 825 (Minecraft 888): he waits till they climb
 * 10 more, then follows, out of sight (50 to 70 behind them when 80 away). Within 3 he grabs them, holds them 3.2 s
 * staring at him, and throws them toward the deepest drop near (PEAK's +0.3 injury). Looked at, he's gone. He gives up
 * when they aren't highest any more, are back within 80 of the next, have a friend within 15, or get above 888. The
 * Scoutmaster's bugle (or eating some things) calls him to whoever used it.
 */
final class PeakScoutmaster {
	private PeakScoutmaster() { }

	private static Mob him;
	private static ServerPlayer target;
	private static double startY;
	private static long forcedUntil, grabbedAt = -1, lastLook;
	private static final double MAX_Y = 825 + 63;

	/** (The dev test: where he is.) */
	static String debug() {
		if (target == null) return "not hunting";
		if (him == null) return "hunting " + target.getName().getString() + ", not come yet";
		return String.format(java.util.Locale.ROOT, "hunting %s, %.0f blocks off%s", target.getName().getString(), him.distanceTo(target), grabbedAt >= 0 ? ", holding them" : "");
	}

	/** The world stopping: he goes, and the timings are forgotten. */
	static void stop(MinecraftServer server) {
		if (him != null) him.discard();
		him = null; target = null; forcedUntil = 0; grabbedAt = -1; lastLook = -1000;
	}

	static void call(ServerPlayer p, float seconds) {
		target = p;
		startY = p.getY() - 10;
		forcedUntil = p.level().getServer().getTickCount() + Math.round(seconds * 20);
	}

	static void tick(MinecraftServer server) {
		if (!PeakAscents.scoutmaster(server)) return;
		long now = server.getTickCount();
		List<ServerPlayer> up = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) if (PeakWorld.scout(p) && !PeakStatus.out(p)) up.add(p);
		boolean forced = now < forcedUntil && target != null && !target.isRemoved();
		if (target == null && now % 600 == 0 && up.size() >= 2 && server.overworld().getRandom().nextFloat() < 0.1f) {
			up.sort(Comparator.comparingDouble(p -> -p.getY()));
			if (up.get(0).getY() - up.get(1).getY() > 100 && up.get(0).getY() < MAX_Y) { target = up.get(0); startY = target.getY(); }
		}
		if (target == null) return;
		if (!forced && (target.isRemoved() || !PeakWorld.scout(target) || giveUp(up))) { leave(); return; }
		if (him == null && target.getY() < startY + 10) return;
		ServerLevel level = target.level();
		if (him == null || him.isRemoved()) { him = spawn(level, behind(level, target, 60)); if (him == null) return; PeakBadges.award(target, "Mentorship"); }
		// looked at: gone
		if (grabbedAt < 0 && now - lastLook > 20)
			for (ServerPlayer p : up)
				if (p.distanceTo(him) < 80 && p.hasLineOfSight(him) && p.getLookAngle().dot(him.getEyePosition().subtract(p.getEyePosition()).normalize()) > Math.cos(Math.toRadians(40))) {
					lastLook = now;
					him.discard();
					him = null;
					return;
				}
		double d = him.distanceTo(target);
		if (grabbedAt >= 0) {
			// holding them, staring at him
			Vec3 face = him.getEyePosition().subtract(target.getEyePosition());
			float yaw = (float) (Math.toDegrees(Math.atan2(-face.x, face.z))), pitch = (float) (-Math.toDegrees(Math.atan2(face.y, Math.hypot(face.x, face.z))));
			target.teleportTo(level, target.getX(), target.getY(), target.getZ(), Set.of(), yaw, pitch, false);
			if (now - grabbedAt >= 64) throwThem(level);
			return;
		}
		if (d > 80 && now % 100 == 0) { him.teleportTo(behind(level, target, 60).x, behind(level, target, 60).y, behind(level, target, 60).z); return; }
		// follow
		Vec3 to = target.position().subtract(him.position());
		Vec3 step = to.normalize().scale(Math.min(0.35, to.length()));
		Vec3 next = him.position().add(step.x, 0, step.z);
		int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(next.x), (int) Math.floor(next.z));
		double y = Math.abs(gy - him.getY()) < 3 ? gy : Math.abs(target.getY() - him.getY()) > 2 ? him.getY() + Math.signum(target.getY() - him.getY()) * 0.3 : him.getY();
		him.snapTo(next.x, y, next.z, (float) Math.toDegrees(Math.atan2(-to.x, to.z)), 0);
		if (d < 3) { grabbedAt = now; level.playSound(null, target.blockPosition(), SoundEvents.VINDICATOR_CELEBRATE, SoundSource.HOSTILE, 1.5f, 0.7f); }
	}

	private static boolean giveUp(List<ServerPlayer> up) {
		if (target.getY() > MAX_Y) return true;
		for (ServerPlayer p : up) {
			if (p == target) continue;
			if (p.getY() > target.getY()) return true;
			if (target.getY() - p.getY() < 80 || p.distanceTo(target) < 15) return true;
		}
		return false;
	}

	private static void throwThem(ServerLevel level) {
		// toward the deepest drop near
		Direction best = Direction.NORTH;
		int low = Integer.MAX_VALUE;
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			BlockPos q = target.blockPosition().relative(dir, 6);
			int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, q.getX(), q.getZ());
			if (h < low) { low = h; best = dir; }
		}
		target.setDeltaMovement(new Vec3(best.getStepX() * 2.2, 0.6, best.getStepZ() * 2.2));
		target.hurtMarked = true;
		PeakStatus.add(target, PeakStatus.INJURY, 0.3f);
		level.playSound(null, target.blockPosition(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.HOSTILE, 1.5f, 0.8f);
		leave();
	}

	private static void leave() {
		if (him != null) him.discard();
		him = null;
		target = null;
		grabbedAt = -1;
		forcedUntil = 0;
	}

	private static Vec3 behind(ServerLevel level, ServerPlayer p, double dist) {
		Vec3 back = p.getLookAngle().multiply(1, 0, 1).normalize().scale(-dist);
		double x = p.getX() + back.x, z = p.getZ() + back.z;
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
		return new Vec3(x, Math.min(y, p.getY() + 20), z);
	}

	private static Mob spawn(ServerLevel level, Vec3 at) {
		var m = EntityTypes.VINDICATOR.create(level, EntitySpawnReason.TRIGGERED);
		if (m == null) return null;
		m.snapTo(at.x, at.y, at.z, 0, 0);
		m.setNoAi(true);
		m.setInvulnerable(true);
		m.setSilent(true);
		m.setCustomName(Component.literal("Scoutmaster"));
		m.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, net.minecraft.world.item.ItemStack.EMPTY);
		level.addFreshEntity(m);
		return m;
	}
}
