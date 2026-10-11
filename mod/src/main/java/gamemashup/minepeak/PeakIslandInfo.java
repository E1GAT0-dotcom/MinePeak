package gamemashup.minepeak;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * What a player's client needs to know of the world's island, from the server as they join (a friend's PC may have
 * another day's island exported, or none): the island, its summit (the compass points there), and PEAK's day on it (the
 * sunrise and sunset music). The client's own exports aren't looked at for these.
 */
public final class PeakIslandInfo {
	private PeakIslandInfo() { }

	public record Info(String island, boolean hasSummit, double sx, double sy, double sz, double minutes, double start, double light, double dark) implements CustomPacketPayload {
		public static final Type<Info> TYPE = new Type<>(MinePeak.id("island_info"));
		public static final StreamCodec<FriendlyByteBuf, Info> CODEC = StreamCodec.of((b, i) -> {
			b.writeUtf(i.island); b.writeBoolean(i.hasSummit); b.writeDouble(i.sx); b.writeDouble(i.sy); b.writeDouble(i.sz);
			b.writeDouble(i.minutes); b.writeDouble(i.start); b.writeDouble(i.light); b.writeDouble(i.dark);
		}, b -> new Info(b.readUtf(), b.readBoolean(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

		Vec3 summit() { return hasSummit ? new Vec3(sx, sy, sz) : null; }

		PeakTime.Day day() { return new PeakTime.Day(minutes, start, light, dark); }
	}

	static void initCommon() {
		PayloadTypeRegistry.clientboundPlay().register(Info.TYPE, Info.CODEC);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			IslandData data = MinePeakServer.island(server);
			if (data == null) return;
			Vec3 top = PeakEnding.summit(data);
			PeakTime.Day d = PeakTime.Day.of(data);
			ServerPlayNetworking.send(handler.player, new Info(data.name, top != null, top != null ? top.x : 0, top != null ? top.y : 0, top != null ? top.z : 0,
				d.minutes(), d.start(), d.light(), d.dark()));
		});
	}

	/** The client's: the island of the world it's in (null in none, or until the server says). */
	static volatile Info current;
}
