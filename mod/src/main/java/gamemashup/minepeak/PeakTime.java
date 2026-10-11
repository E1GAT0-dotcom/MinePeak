package gamemashup.minepeak;

import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.WorldClock;

/**
 * PEAK's day in Minecraft. PEAK's DayNightManager runs a day of 24 hours in dayLengthInMinutes (10), starting at 9 AM,
 * light from 5 AM to 9 PM; a new day starts at 5:30 AM. Minecraft's clock runs at PEAK's pace: faster than its own (a
 * Minecraft day is 20 minutes), and faster still by night, so Minecraft's sunrise (tick 23200) is PEAK's 5 AM and its
 * sunset (12800) PEAK's 9 PM. The clock's ticks give PEAK's hour and day back (for the title card).
 */
public final class PeakTime {
	private PeakTime() { }

	static final int SUNRISE = 23200, SUNSET = 12800, MC_DAY = 24000 - (SUNRISE - SUNSET), MC_NIGHT = SUNRISE - SUNSET;

	/** PEAK's day: its length in minutes, when it starts, and its light (hours). */
	record Day(double minutes, double start, double light, double dark) {
		static final Day PEAK = new Day(10, 9, 5, 21);

		static Day of(IslandData data) {
			if (data == null || !data.info.has("time")) return PEAK;
			JsonObject t = data.info.getAsJsonObject("time");
			return new Day(t.has("dayLengthInMinutes") ? t.get("dayLengthInMinutes").getAsDouble() : 10, t.has("startingTimeOfDay") ? t.get("startingTimeOfDay").getAsDouble() : 9,
				t.has("dayStart") ? t.get("dayStart").getAsDouble() : 5, t.has("dayEnd") ? t.get("dayEnd").getAsDouble() : 21);
		}

		double lightHours() { return dark - light; }
		double darkHours() { return 24 - lightHours(); }
		/** Game ticks to a PEAK hour. */
		double ticksPerHour() { return minutes * 60 * 20 / 24; }
	}

	/** PEAK's hour (0..24) at the clock's ticks. */
	static double hours(long ticks, Day d) {
		long t = Math.floorMod(ticks, 24000L);
		double h;
		if (t >= SUNSET && t < SUNRISE) h = d.dark() + (t - SUNSET) / (double) MC_NIGHT * d.darkHours();
		else h = d.light() + ((t < SUNSET ? t + 24000 : t) - SUNRISE) / (double) MC_DAY * d.lightHours();
		return h % 24;
	}

	/** The clock's ticks (within its day) at PEAK's hour h. */
	static long ticks(double h, Day d) {
		double t;
		if (h >= d.light() && h < d.dark()) t = SUNRISE + (h - d.light()) / d.lightHours() * MC_DAY;
		else t = SUNSET + ((h >= d.dark() ? h - d.dark() : h + 24 - d.dark()) / d.darkHours()) * MC_NIGHT;
		return Math.floorMod(Math.round(t), 24000L);
	}

	/** PEAK's day number at the clock's ticks (from the world's start at PEAK's starting hour on day one; a day starts at 5:30). */
	static int day(long ticks, Day d) {
		long dawn = ticks(5.5, d);
		return ticks < dawn ? 1 : 2 + (int) ((ticks - dawn) / 24000L);
	}

	/** PEAK's words for the time of day (DayNightManager.TimeOfDayString). */
	static String words(double h) {
		return h >= 23.5 ? "night" : h >= 17.5 ? "evening" : h >= 11.5 ? "afternoon" : h >= 5.5 ? "morning" : "night";
	}

	// ---- the server's clock ----

	private static float rate = -1;
	private static int lastDay = -1;

	/** A world starting: its clock's pace is set afresh. */
	static void reset() { rate = -1; lastDay = -1; }

	/** A new MinePeak world: its clock at PEAK's starting hour on day one. */
	static void start(MinecraftServer server, IslandData data) {
		Holder<WorldClock> clock = clock(server);
		if (clock == null) return;
		server.overworld().clockManager().setTotalTicks(clock, ticks(Day.of(data).start(), Day.of(data)));
		rate = -1;
	}

	/** Every second: the clock's pace for the time of day (day or night). */
	static void tick(MinecraftServer server, IslandData data) {
		if (server.getTickCount() % 20 != 0) return;
		Holder<WorldClock> clock = clock(server);
		if (clock == null) return;
		Day d = Day.of(data);
		// a new day (PEAK's starts at 5:30): its title card, "Day Two", for everyone on the island
		int today = day(server.overworld().getOverworldClockTime(), d);
		if (lastDay > 0 && today > lastDay)
			for (var p : server.getPlayerList().getPlayers()) if (PeakWorld.is(p.level())) HeroTitle.show(p);
		lastDay = today;
		long t = Math.floorMod(server.overworld().getOverworldClockTime(), 24000L);
		boolean night = t >= SUNSET && t < SUNRISE;
		float want = (float) (night ? MC_NIGHT / (d.darkHours() * d.ticksPerHour()) : MC_DAY / (d.lightHours() * d.ticksPerHour()));
		if (Math.abs(want - rate) > 1e-4) {
			server.overworld().clockManager().setRate(clock, want);
			rate = want;
		}
	}

	private static Holder<WorldClock> clock(MinecraftServer server) {
		ServerLevel level = server.overworld();
		return level.dimensionType().defaultClock().orElse(null);
	}
}
