package gamemashup.minepeak;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Runs the MinePeak Exporter (a plugin in a PEAK copy on this PC) in the background, with no window and no sound, to
 * export PEAK's island of the day when it hasn't been yet. It reports through %APPDATA%/MinePeak/export_status.json.
 * Which PEAK: config/minepeak.json {"peakExe": "...\\PEAK.exe", "headless": true}.
 */
public final class PeakExporter {
	public record Status(String state, String step, int percent, String message, String island, long pid) {
		boolean active() { return ("starting".equals(state) || "exporting".equals(state)) && pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false); }
	}

	public static final Path STATUS = IslandData.ROOT.getParent().resolve("export_status.json");
	private static Process process;
	private static volatile String lastError;

	/** What went wrong with the last export, in words people can act on (or null). */
	public static String lastError() { return lastError; }

	static void error(String message) {
		lastError = message;
		MinePeak.LOG.error("MinePeak: {}", message);
	}

	static boolean steamRunning() {
		return ProcessHandle.allProcesses().anyMatch(p -> p.info().command().map(c -> c.toLowerCase(java.util.Locale.ROOT).endsWith("steam.exe")).orElse(false));
	}

	private PeakExporter() { }

	/** The settings, made on first use (no PEAK set: the MinePeak Exporter's installer's copy is found by itself). */
	static JsonObject config() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("minepeak.json");
		try {
			if (Files.exists(file)) return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			JsonObject o = new JsonObject();
			o.addProperty("peakExe", "");
			o.addProperty("headless", true);
			Files.writeString(file, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(o));
			return o;
		} catch (Exception e) {
			MinePeak.LOG.warn("Couldn't read {}", file, e);
			return new JsonObject();
		}
	}

	/** The settings file, as people see it from the Minecraft folder (config\minepeak.json). */
	static Path configFile() {
		var fl = FabricLoader.getInstance();
		Path file = fl.getConfigDir().resolve("minepeak.json");
		return file.startsWith(fl.getGameDir()) ? fl.getGameDir().relativize(file) : file;
	}

	/**
	 * The PEAK copy to export with: "peakExe" in the settings when it's set and there; otherwise the one the MinePeak
	 * Exporter's installer made (it writes where to %APPDATA%/MinePeak/peak_copy.txt; by default
	 * %LOCALAPPDATA%/MinePeak/PEAK).
	 */
	static Path peakExe() {
		var c = config();
		String p = c.has("peakExe") ? c.get("peakExe").getAsString() : "";
		if (!p.isEmpty() && Files.exists(Path.of(p))) return Path.of(p);
		try {
			Path told = IslandData.ROOT.getParent().resolve("peak_copy.txt");
			if (Files.isRegularFile(told)) {
				Path exe = Path.of(Files.readString(told).strip().replace("﻿", ""));
				if (Files.exists(exe)) return exe;
			}
			String local = System.getenv("LOCALAPPDATA");
			if (local != null && Files.exists(Path.of(local, "MinePeak", "PEAK", "PEAK.exe"))) return Path.of(local, "MinePeak", "PEAK", "PEAK.exe");
		} catch (Exception e) {
			MinePeak.LOG.warn("MinePeak: couldn't read where the PEAK copy is", e);
		}
		return p.isEmpty() ? null : Path.of(p);
	}

	/**
	 * PEAK's day: its island changes every 24 hours at 17:00 UTC (NextLevelService), not at midnight, so this is the
	 * UTC date that the current island came in on. The exporter names its folders with the same date.
	 */
	public static String peakDay() {
		return LocalDate.ofInstant(java.time.Instant.now().minus(java.time.Duration.ofHours(17)), java.time.ZoneOffset.UTC).toString();
	}

	/** Has today's island been exported (a finished export folder named with PEAK's day)? */
	public static boolean todayExported() {
		String today = peakDay();
		if (!Files.isDirectory(IslandData.ROOT)) return false;
		try (Stream<Path> s = Files.list(IslandData.ROOT)) {
			return s.anyMatch(p -> p.getFileName().toString().startsWith(today) && Files.isRegularFile(p.resolve("island.json")) && format(p.resolve("island.json")) >= FORMAT);
		} catch (Exception e) {
			return false;
		}
	}

	/** The export's format MinePeak needs (2: PEAK's items, HUD, spawners and hazards too): an older one is exported again. */
	static final int FORMAT = 3;

	static int format(Path island) {
		try (var in = Files.newBufferedReader(island)) {
			char[] head = new char[200];
			int n = in.read(head);
			var m = java.util.regex.Pattern.compile("\"format\"\\s*:\\s*(\\d+)").matcher(new String(head, 0, Math.max(0, n)));
			return m.find() ? Integer.parseInt(m.group(1)) : 1;
		} catch (Exception e) {
			return 1;
		}
	}

	private static Status statusSeen;
	private static long statusAt;

	/** The export's status (its file read twice a second at most: the menus show it every frame). */
	public static synchronized Status status() {
		long now = System.currentTimeMillis();
		if (now - statusAt < 500) return statusSeen;
		statusAt = now;
		return statusSeen = statusNow();
	}

	private static Status statusNow() {
		try {
			if (!Files.exists(STATUS)) return null;
			JsonObject o = JsonParser.parseString(Files.readString(STATUS)).getAsJsonObject();
			return new Status(str(o, "state"), str(o, "step"), o.has("percent") ? o.get("percent").getAsInt() : 0, str(o, "message"),
				str(o, "island"), o.has("pid") ? o.get("pid").getAsLong() : -1);
		} catch (Exception e) {
			return null;
		}
	}

	private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }

	/** Is an export running (ours, or one another Minecraft started)? */
	public static synchronized boolean running() {
		if (process != null && process.isAlive()) return true;
		Status s = status();
		return s != null && s.active();
	}

	/** Starts the export when today's island is missing and none is running. True if one is running now. */
	public static synchronized boolean startIfNeeded(String why) {
		if (running()) return true;
		if (todayExported()) return false;
		Path exe = peakExe();
		if (exe == null || !Files.exists(exe)) {
			error("Can't find PEAK to export today's island. Set \"peakExe\" in " + configFile() + " to the PEAK.exe that has the MinePeak Exporter.");
			return false;
		}
		if (!Files.exists(exe.getParent().resolve("BepInEx").resolve("plugins").resolve("MinePeakExporter").resolve("MinePeakExporter.dll"))) {
			error("The PEAK at " + exe.getParent() + " doesn't have the MinePeak Exporter (BepInEx\\plugins\\MinePeakExporter). Install it there, or set \"peakExe\" in " + configFile() + ".");
			return false;
		}
		if (!steamRunning()) {
			error("Steam isn't running, so PEAK can't start to export today's island. Start Steam, then load the world again.");
			return false;
		}
		lastError = null;
		try {
			boolean headless = !config().has("headless") || config().get("headless").getAsBoolean();
			var cmd = new java.util.ArrayList<>(List.of(exe.toString(), "-minepeak-export", "-minepeak-quit",
				"-logFile", exe.getParent().resolve("Player-export.log").toString()));
			if (headless) cmd.add("-batchmode");   // (no window; not -nographics: PEAK takes pictures of its airport for the menus' panorama, which needs the GPU)
			Files.deleteIfExists(STATUS);
			statusAt = 0;   // (the old status forgotten)
			process = new ProcessBuilder(cmd).directory(exe.getParent().toFile()).redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
			MinePeak.LOG.info("Exporting today's PEAK island in the background ({}), PEAK pid {}", why, process.pid());
			return true;
		} catch (Exception e) {
			error("Couldn't start PEAK for the island export: " + e.getMessage());
			return false;
		}
	}

	/** Waits (on a server thread) until the export finishes, reporting as it goes. False if it failed or timed out. */
	public static boolean waitUntilDone(long maxMillis, Consumer<Status> progress) {
		long until = System.currentTimeMillis() + maxMillis;
		int last = -1;
		while (running() && System.currentTimeMillis() < until) {
			Status s = status();
			if (s != null && s.percent() != last) { last = s.percent(); progress.accept(s); }
			try { Thread.sleep(500); } catch (InterruptedException e) { return false; }
		}
		return result(maxMillis);
	}

	/** Once the export has stopped (or after maxMillis): true if today's island is ready, else lastError says why not. */
	public static boolean result(long maxMillis) {
		Status s = status();
		if (s != null && "done".equals(s.state())) return true;
		Path exe = peakExe();
		String log = exe != null ? " (logs in " + exe.getParent() + ": Player-export.log and BepInEx\\MinePeakData\\export.log)" : "";
		if (s != null && "error".equals(s.state())) error(s.message() + log + ".");
		else if (running()) error("The PEAK island export is taking too long (over " + maxMillis / 60000 + " minutes)" + log + ".");
		else error("PEAK closed before today's island was exported" + (s != null && !s.step().isEmpty() ? ", at: " + s.step() : "") + log + ".");
		return false;
	}
}
