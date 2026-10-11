package gamemashup.minepeak;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.Vec3;

/**
 * PEAK's sounds that go on while something lasts, from a place that may move (the bees' buzz, calm or angry, following
 * their swarm; an antlion's sand rumbling while it's awake): the server says, a few times a second, which each scout is
 * to hear and where; the client keeps each playing (PeakLoopsClient: PEAK's own loop, or Minecraft's), moves it, and
 * fades it out once it's no longer said.
 */
public final class PeakLoops {
	private PeakLoops() { }

	/** One: a name for it (the same while it lasts), PEAK's sound, Minecraft's in its place, where, how loud. */
	public record Loop(String key, String role, String fallback, double x, double y, double z, float volume) { }

	public record Loops(List<Loop> loops) implements CustomPacketPayload {
		public static final Type<Loops> TYPE = new Type<>(MinePeak.id("loops"));
		public static final StreamCodec<FriendlyByteBuf, Loops> CODEC = StreamCodec.of((b, s) -> {
			b.writeVarInt(s.loops.size());
			for (Loop l : s.loops) {
				b.writeUtf(l.key); b.writeUtf(l.role); b.writeUtf(l.fallback);
				b.writeDouble(l.x); b.writeDouble(l.y); b.writeDouble(l.z); b.writeFloat(l.volume);
			}
		}, b -> {
			int n = b.readVarInt();
			List<Loop> l = new ArrayList<>(n);
			for (int i = 0; i < n; i++) l.add(new Loop(b.readUtf(), b.readUtf(), b.readUtf(), b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat()));
			return new Loops(l);
		});
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	static void initCommon() { PayloadTypeRegistry.clientboundPlay().register(Loops.TYPE, Loops.CODEC); }

	private static final Map<UUID, Map<String, Loop>> WANT = new HashMap<>();
	private static final java.util.Set<UUID> HAD = new java.util.HashSet<>();

	/** A loop heard by each player within range of it (called every tick or every few, while it lasts). */
	static void near(ServerLevel level, String key, String role, SoundEvent fallback, Vec3 at, float volume, double range) {
		for (ServerPlayer p : level.players())
			if (p.position().distanceToSqr(at) < range * range)
				WANT.computeIfAbsent(p.getUUID(), u -> new LinkedHashMap<>())
					.put(key, new Loop(key, role, fallback != null ? fallback.location().toString() : "", at.x, at.y, at.z, volume));
	}

	static void stop() { WANT.clear(); HAD.clear(); }

	/** Every 4 ticks: each player's loops (and an empty list once, to a player who had some: they all fade). */
	static void tick(MinecraftServer server) {
		if (server.getTickCount() % 4 != 0) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Map<String, Loop> m = WANT.get(p.getUUID());
			if (m == null || m.isEmpty()) {
				if (HAD.remove(p.getUUID())) ServerPlayNetworking.send(p, new Loops(List.of()));
				continue;
			}
			ServerPlayNetworking.send(p, new Loops(new ArrayList<>(m.values())));
			HAD.add(p.getUUID());
		}
		WANT.clear();
	}
}
