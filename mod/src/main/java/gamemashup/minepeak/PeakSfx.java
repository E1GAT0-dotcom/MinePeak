package gamemashup.minepeak;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.Vec3;

/**
 * PEAK's sound effects, from the server: each client near plays PEAK's own (exported from the player's PEAK: an item's
 * use sound, a suitcase opening, a piton hammered, a bugle's note, getting hurt...; PeakSounds.once), or, until there's
 * an export, Minecraft's nearest sound.
 */
public final class PeakSfx {
	private PeakSfx() { }

	public record Sfx(String role, double x, double y, double z, float volume, float pitch, String fallback) implements CustomPacketPayload {
		public static final Type<Sfx> TYPE = new Type<>(MinePeak.id("sfx"));
		public static final StreamCodec<FriendlyByteBuf, Sfx> CODEC = StreamCodec.of((b, s) -> {
			b.writeUtf(s.role); b.writeDouble(s.x); b.writeDouble(s.y); b.writeDouble(s.z); b.writeFloat(s.volume); b.writeFloat(s.pitch); b.writeUtf(s.fallback);
		}, b -> new Sfx(b.readUtf(), b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat(), b.readFloat(), b.readUtf()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	static void initCommon() { PayloadTypeRegistry.clientboundPlay().register(Sfx.TYPE, Sfx.CODEC); }

	/** PEAK's sound role at a place, heard within 48 blocks (Minecraft's fallback where there's none of PEAK's). */
	static void play(ServerLevel level, Vec3 at, String role, SoundEvent fallback, float volume, float pitch) {
		// (an item with no sound of its own has no role: an empty one, not null, which can't be sent to other players)
		Sfx s = new Sfx(role != null ? role : "", at.x, at.y, at.z, volume, pitch, fallback != null ? fallback.location().toString() : "");
		for (ServerPlayer p : level.players()) if (p.position().distanceToSqr(at) < 48 * 48) ServerPlayNetworking.send(p, s);
	}

	static void play(ServerLevel level, BlockPos at, String role, SoundEvent fallback, float volume, float pitch) {
		play(level, net.minecraft.world.phys.Vec3.atCenterOf(at), role, fallback, volume, pitch);
	}

	/** The role of an item's own sound when used (PEAK's ItemUseFeedback.sfxUsed), or null. */
	static String used(PeakItemData.Def def) {
		var fb = def.part("ItemUseFeedback");
		String s = fb != null ? fb.ref("sfxUsed") : null;
		return s != null ? "sfx_" + safe(s) : null;
	}

	static String safe(String s) {
		StringBuilder o = new StringBuilder();
		for (char c : s.toCharArray()) o.append(Character.isLetterOrDigit(c) ? Character.toLowerCase(c) : '_');
		return o.toString();
	}
}
