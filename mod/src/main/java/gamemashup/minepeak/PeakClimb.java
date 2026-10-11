package gamemashup.minepeak;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * PEAK's climbing, the server's part and what both sides share (the client's: PeakClimbClient). Any wall the player
 * faces can be climbed, as a ladder is in Minecraft (LivingEntityMixin). The client keeps the stamina (the player's
 * movement is the client's) and tells the server when the player is on a wall, so the server takes it as a ladder too
 * (a fall along a wall you're holding doesn't count).
 */
public final class PeakClimb {
	private PeakClimb() { }

	// ---- the server's side: who is on a wall (as the client says) ----

	public record Climbing(boolean on) implements CustomPacketPayload {
		public static final Type<Climbing> TYPE = new Type<>(MinePeak.id("climbing"));
		public static final StreamCodec<FriendlyByteBuf, Climbing> CODEC = ByteBufCodecs.BOOL.map(Climbing::new, Climbing::on).cast();
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	private static final Set<UUID> ON_WALL = ConcurrentHashMap.newKeySet();

	/** A player gone (or the world stopping): not on a wall any more (a new client wouldn't say so). */
	static void left(UUID player) { ON_WALL.remove(player); }

	static void stop() { ON_WALL.clear(); }

	static void initCommon() {
		PayloadTypeRegistry.serverboundPlay().register(Climbing.TYPE, Climbing.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Climbing.TYPE, (payload, context) -> {
			if (payload.on()) ON_WALL.add(context.player().getUUID()); else ON_WALL.remove(context.player().getUUID());
		});
	}

	/** Does this player hold on to a wall (LivingEntityMixin: then it's a ladder to Minecraft)? */
	public static boolean onWall(Player p) {
		if (p.isSpectator() || p.getAbilities().flying) return false;
		if (p.level().isClientSide()) return PeakClimbClient.onWall(p);
		return ON_WALL.contains(p.getUUID()) && touching(p, null);
	}

	/**
	 * Is there something solid beside the player, from its knees up (a wall, not the floor)? With a look (the way the
	 * player faces, flat), only a wall it faces (so brushing past one in a passage isn't climbing it).
	 */
	static boolean touching(Player p, net.minecraft.world.phys.Vec3 look) { return touching(p, look, 0.3); }

	/** The same, from `from` above the feet up (1.0: a wall at least chest high, not a step a jump clears). */
	static boolean touching(Player p, net.minecraft.world.phys.Vec3 look, double from) {
		AABB b = p.getBoundingBox();
		double y0 = b.minY + from, y1 = b.maxY - 0.1, e = 0.08;
		AABB[] sides = { new AABB(b.maxX, y0, b.minZ, b.maxX + e, y1, b.maxZ), new AABB(b.minX - e, y0, b.minZ, b.minX, y1, b.maxZ),
			new AABB(b.minX, y0, b.maxZ, b.maxX, y1, b.maxZ + e), new AABB(b.minX, y0, b.minZ - e, b.maxX, y1, b.minZ) };
		double[][] dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
		for (int i = 0; i < 4; i++) {
			if (look != null && dirs[i][0] * look.x + dirs[i][1] * look.z < 0.55) continue;
			if (!p.level().noCollision(p, sides[i])) return true;
		}
		return false;
	}

}
