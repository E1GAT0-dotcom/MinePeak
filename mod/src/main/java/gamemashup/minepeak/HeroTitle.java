package gamemashup.minepeak;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * PEAK's title card (GUIManager.SetHeroTitle): walking past one of PEAK's progress points up the mountain (exported with
 * the island: where each stands, and its title in English) shows the stage's name big, typed in a letter at a time, with
 * the day and the time of day under it (HeroTitleHud draws it). As in PEAK (MountainProgressHandler): only for the
 * furthest point a player has reached, only points of biomes on today's island, and the stage they're in again 2 seconds
 * after they join.
 */
public final class HeroTitle {
	private HeroTitle() { }

	/** A title card: the stage's name, the day line, the time of day. */
	public record Payload(String title, String day, String time) implements CustomPacketPayload {
		public static final Type<Payload> TYPE = new Type<>(MinePeak.id("hero_title"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, Payload::title, ByteBufCodecs.STRING_UTF8, Payload::day, ByteBufCodecs.STRING_UTF8, Payload::time, Payload::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	private record Point(String text, int z) { }

	private static final Map<UUID, Integer> REACHED = new HashMap<>();
	private static final Map<UUID, Integer> SHOW_AT = new HashMap<>();
	private static final Map<IslandData, List<Point>> POINTS = new WeakHashMap<>();

	static void init() {
		PayloadTypeRegistry.clientboundPlay().register(Payload.TYPE, Payload.CODEC);
		ServerTickEvents.END_SERVER_TICK.register(HeroTitle::tick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			REACHED.remove(handler.player.getUUID());
			SHOW_AT.put(handler.player.getUUID(), server.getTickCount() + 40);   // (PEAK: the stage's card 2 seconds after spawning)
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			REACHED.remove(handler.player.getUUID());
			SHOW_AT.remove(handler.player.getUUID());
		});
	}

	/** The island's stages, from the bottom of the mountain up (Minecraft z going down): where each starts (Stages). */
	private static List<Point> points(IslandData data) {
		return POINTS.computeIfAbsent(data, d -> {
			List<Point> list = new ArrayList<>();
			for (Stages.Stage st : Stages.of(d)) list.add(new Point(st.text(), st.z()));
			return list;
		});
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % 10 != 0) return;
		IslandData data = MinePeakServer.island(server);
		if (data == null) return;
		List<Point> points = points(data);
		if (points.isEmpty()) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.level() != server.overworld() || player.isSpectator()) continue;
			UUID id = player.getUUID();
			// the furthest point passed: up the mountain is towards smaller z
			int k = -1;
			for (int i = 0; i < points.size(); i++) if (player.getZ() < points.get(i).z()) k = i;
			Integer at = SHOW_AT.get(id), was = REACHED.get(id);
			if (at != null) {
				if (server.getTickCount() < at) continue;
				SHOW_AT.remove(id);
				REACHED.put(id, k);
				if (k >= 0) send(server, player, points.get(k).text(), data);
			} else if (was == null) REACHED.put(id, k);
			else if (k > was) {
				REACHED.put(id, k);
				send(server, player, points.get(k).text(), data);
			}
		}
	}

	/** The title card of the stage the player is in now (/minepeak title). */
	static boolean show(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		IslandData data = MinePeakServer.island(server);
		if (data == null || points(data).isEmpty()) return false;
		List<Point> points = points(data);
		int k = 0;
		for (int i = 0; i < points.size(); i++) if (player.getZ() < points.get(i).z()) k = i;
		send(server, player, points.get(k).text(), data);
		return true;
	}

	private static void send(MinecraftServer server, ServerPlayer player, String title, IslandData data) {
		long ticks = server.overworld().getOverworldClockTime();
		// (PEAK's DayNightManager: TimeOfDayString, and DayCountString with its number words up to ten; PEAK's day: PeakTime)
		PeakTime.Day d = PeakTime.Day.of(data);
		int day = PeakTime.day(ticks, d);
		String time = PeakTime.words(PeakTime.hours(ticks, d));
		ServerPlayNetworking.send(player, new Payload(title, dayLine(data, day), time));
	}

	private static final String[] NUMBERS = { "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten" };

	/** One of PEAK's words (exported "texts", in English), in capitals; fallback when it isn't there. */
	static String word(IslandData data, String key, String fallback) {
		JsonObject texts = data != null && data.info.has("texts") ? data.info.getAsJsonObject("texts") : new JsonObject();
		return text(texts, key, fallback).toUpperCase(java.util.Locale.ROOT);
	}

	/** A card of PEAK's with just a line (THE FOG RISES, MORALE BOOST), as the title card shows it. */
	static void card(ServerPlayer player, String text) {
		ServerPlayNetworking.send(player, new Payload(text, "", ""));
	}

	private static String dayLine(IslandData data, int day) {
		JsonObject texts = data.info.has("texts") ? data.info.getAsJsonObject("texts") : new JsonObject();
		String line = text(texts, "DAY", "DAY #");
		String n = day >= 1 && day <= 10 ? text(texts, NUMBERS[day - 1], NUMBERS[day - 1]) : Integer.toString(day);
		return line.replace("#", n);   // (as PEAK writes it: "Day One")
	}

	private static String text(JsonObject texts, String key, String fallback) {
		if (!texts.has(key)) return fallback;
		String s = texts.get(key).getAsString();
		return s.isEmpty() || s.startsWith("LOC:") ? fallback : s;
	}

}
