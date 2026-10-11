package gamemashup.minepeak;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.audio.SoundBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * PEAK's sounds in Minecraft, as PEAK plays them (its AmbienceAudio, its campfires, its menu), from the WAVs the MinePeak
 * Exporter saved from the player's own PEAK (%APPDATA%/MinePeak/sounds; sounds.json says what each is):
 * - the main menu's music on MinePeak's title screen (Minecraft's menu music off);
 * - a stage's music (its "climb stinger") as you reach it, with its title card; the start's on joining;
 * - at sunrise (5:30), sunset (7:30 PM) and night (9:12 PM) PEAK's music for it (its stage's own when it has one), unless
 *   a stage's music played in the last two minutes;
 * - the ambience loops, faded in and out with the stage, the time of day and the height (ambience());
 * - a lit campfire's crackle, from the campfire; its lighting.
 * Minecraft's own music is off in MinePeak's worlds (the island's dimension has none) and on its title screen.
 */
public final class PeakSounds {
	private PeakSounds() { }

	static final Path DIR = IslandData.ROOT.getParent().resolve("sounds");

	record Clip(String role, Path file, double length, boolean loop) { }

	private static volatile Map<String, Clip> clips = Map.of();
	private static long stamp = -1;
	private static volatile long nextCheck;

	/** What there is (read again when sounds.json changes: an export saves new ones; looked at every 2 s at most). */
	static Map<String, Clip> clips() {
		if (System.currentTimeMillis() < nextCheck) return clips;
		return reloadClips();
	}

	private static synchronized Map<String, Clip> reloadClips() {
		long now = System.currentTimeMillis();
		if (now < nextCheck) return clips;
		nextCheck = now + 2000;
		try {
			Path index = DIR.resolve("sounds.json");
			long s = Files.isRegularFile(index) ? Files.getLastModifiedTime(index).toMillis() : 0;
			if (s == stamp) return clips;
			stamp = s;
			Map<String, Clip> m = new HashMap<>();
			if (s != 0) {
				JsonObject all = JsonParser.parseString(Files.readString(index)).getAsJsonObject().getAsJsonObject("sounds");
				for (var e : all.entrySet()) {
					JsonObject o = e.getValue().getAsJsonObject();
					Path f = DIR.resolve(o.get("file").getAsString());
					if (Files.isRegularFile(f)) m.put(e.getKey(), new Clip(e.getKey(), f, o.get("length").getAsDouble(), o.has("loop") && o.get("loop").getAsBoolean()));
				}
			}
			clips = m;
		} catch (Exception e) {
			MinePeak.LOG.warn("MinePeak: couldn't read PEAK's sounds", e);
		}
		return clips;
	}

	static boolean has(String role) { return clips().containsKey(role); }

	// ---- for Minecraft's sound engine (SoundBufferLibraryMixin) ----

	public static boolean handles(Identifier location) {
		return location.getNamespace().equals(MinePeak.ID) && location.getPath().startsWith("sounds/peak/");
	}

	private static String role(Identifier location) {
		String p = location.getPath().substring("sounds/peak/".length());
		return p.endsWith(".ogg") ? p.substring(0, p.length() - 4) : p;
	}

	/** Heard from a place: made mono (Minecraft places only mono sounds). */
	private static boolean placed(String role) { return role.startsWith("campfire") || role.startsWith("bees_") || role.startsWith("antlion_") || role.startsWith("mandrake_"); }

	public static CompletableFuture<AudioStream> stream(Identifier location, boolean looping) {
		return CompletableFuture.supplyAsync(() -> {
			String role = role(location);
			Clip c = clips().get(role);
			try {
				if (c == null) throw new java.io.FileNotFoundException("no PEAK sound " + role);
				return new WavStream(c.file(), looping, placed(role));
			} catch (Exception e) {
				MinePeak.LOG.warn("MinePeak: couldn't play PEAK's sound {}", role, e);
				throw new CompletionException(e);
			}
		}, Util.nonCriticalIoPool());
	}

	public static CompletableFuture<SoundBuffer> buffer(Identifier location) {
		return CompletableFuture.supplyAsync(() -> {
			String role = role(location);
			Clip c = clips().get(role);
			try (WavStream w = c != null ? new WavStream(c.file(), false, placed(role)) : null) {
				if (w == null) throw new java.io.FileNotFoundException("no PEAK sound " + role);
				return new SoundBuffer(w.readAll(), w.getFormat());
			} catch (Exception e) {
				throw new CompletionException(e);
			}
		}, Util.nonCriticalIoPool());
	}

	// ---- what plays ----

	private static PeakSound menu, music, fire;
	private static final Map<String, PeakSound> loops = new HashMap<>();
	private static BlockPos fireAt;
	private static int dayState, ticks;
	private static long musicUntil, joinedAt;

	/** Is Minecraft's music to stay off (PEAK's playing in its place)? */
	public static boolean musicOwned() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return mc.gui.screen() instanceof PeakTitleScreen && has("menu_music");
		return stage(mc) != null && !clips().isEmpty();
	}

	/** Is the player in a MinePeak world (on its island's stages)? */
	static boolean inPeakWorld(Minecraft mc) { return stage(mc) != null; }

	/** The stage the player is in (its biome, minepeak:shore...), or null outside a MinePeak world. */
	private static String stage(Minecraft mc) {
		if (mc.level == null || mc.player == null) return null;
		return mc.level.getBiome(mc.player.blockPosition()).unwrapKey()
			.filter(k -> k.identifier().getNamespace().equals(MinePeak.ID)).map(k -> k.identifier().getPath()).orElse(null);
	}

	private static final java.util.Set<String> PLAYED = new java.util.HashSet<>();

	private static final java.util.Random PICK = new java.util.Random();

	/**
	 * A sound effect of PEAK's, once: role itself, or one of role_0, role_1... (an SFX_Instance's clips: PEAK picks one),
	 * at a place (null: everywhere), at a pitch. False when PEAK's export has none (the caller plays Minecraft's).
	 */
	static boolean once(String role, Vec3 at, float volume, float pitch) {
		if (role == null || role.isEmpty()) return false;
		var c = clips();
		java.util.List<String> options = new java.util.ArrayList<>();
		if (c.containsKey(role)) options.add(role);
		int most = switch (role) { case "piton_hammer" -> 4; case "climb_grab", "climb_let_go", "flare", "revive" -> 1; case "rope_end" -> 6; case "balloon_tie" -> 2; case "rope_grab" -> 4; default -> 99; };
		for (int i = 0; i < most && c.containsKey(role + "_" + i); i++) options.add(role + "_" + i);
		if (options.isEmpty()) return false;
		String pick = options.get(PICK.nextInt(options.size()));
		if (PLAYED.add(pick)) MinePeak.LOG.info("MinePeak: PEAK's sound {}", pick);
		Minecraft.getInstance().getSoundManager().play(new PeakSound(pick, SoundSource.PLAYERS, false, volume, at, 0).pitch(pitch));
		return true;
	}

	private static PeakSound play(String role, SoundSource source, boolean loop, float volume, Vec3 at, float fade) {
		if (!has(role)) return null;
		if (PLAYED.add(role)) MinePeak.LOG.info("MinePeak: PEAK's sound {} ({})", role, source.getName());
		PeakSound s = new PeakSound(role, source, loop, volume, at, fade);
		Minecraft.getInstance().getSoundManager().play(s);
		return s;
	}

	private static void stop(PeakSound s) {
		if (s != null) s.fadeTo(0);
	}

	private static boolean playing(PeakSound s) {
		return s != null && !s.isStopped() && Minecraft.getInstance().getSoundManager().isActive(s);
	}

	/** A random one of role_0, role_1... */
	private static String any(String role) {
		List<String> all = new ArrayList<>();
		for (String r : clips().keySet()) if (r.startsWith(role + "_")) all.add(r);
		return all.isEmpty() ? null : all.get((int) (Math.random() * all.size()));
	}

	/** Music (a stage's, the start's, the time of day's): one at a time, the last one fading out. */
	private static void music(String role, float volume) {
		if (role == null || !has(role)) return;
		stop(music);
		Minecraft.getInstance().getMusicManager().stopPlaying();
		music = play(role, SoundSource.MUSIC, false, volume, null, 0);
	}

	/** The title card for a stage has come up: its music (PEAK's climb stinger), or the start's when the player has just joined. */
	static void onTitle(String title) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		long now = Util.getMillis();
		if (now - joinedAt < 15_000) {
			music(any("startstinger"), 0.6f);
		} else {
			String t = title.toUpperCase(Locale.ROOT);
			String role = t.contains("KILN") ? "climbstingerkiln" : t.contains("CALDERA") ? "climbstingercaldera" : t.startsWith("PEAK") ? "climbstingerpeak"
				: t.contains("ROOT") ? "climbstingerroots" : t.contains("TROPIC") ? "climbstingertropics" : t.contains("ALPINE") ? "climbstingeralpine"
				: t.contains("MESA") ? "climbstingermesa" : t.contains("SHORE") || t.contains("BEACH") ? "climbstingerbeach" : null;
			float v = role == null ? 0.5f : switch (role) {
				case "climbstingerbeach" -> 0.35f; case "climbstingeralpine", "climbstingermesa" -> 0.4f; case "climbstingercaldera" -> 0.75f;
				case "climbstingerkiln" -> 0.6f; case "climbstingerpeak" -> 1f; default -> 0.5f; };
			music(role, v);
		}
		musicUntil = now + 120_000;   // (PEAK's priorityMusicTimer: no time-of-day music for two minutes)
	}

	static void joined() {
		joinedAt = Util.getMillis();
		dayState = 0;
	}

	static void tick(Minecraft mc) {
		ticks++;
		// the title screen's music
		boolean onMenu = mc.level == null && mc.gui.screen() instanceof PeakTitleScreen;
		if (onMenu && !playing(menu) && has("menu_music")) {
			mc.getMusicManager().stopPlaying();
			menu = play("menu_music", SoundSource.MUSIC, true, 0.8f, null, 1.5f);
		} else if (!onMenu && menu != null) { stop(menu); menu = null; }
		String stage = stage(mc);
		if (stage == null) {
			loops.values().forEach(PeakSounds::stop);
			loops.clear();
			stop(fire); fire = null; fireAt = null;
			return;
		}
		var info = PeakIslandInfo.current;   // (PEAK's day on the world's island, as its server says)
		double hour = PeakTime.hours(mc.level.getOverworldClockTime(), info != null ? info.day() : PeakTime.Day.PEAK);
		if (ticks % 10 == 0) {
			dayMusic(stage, hour);
			ambience(stage, hour, mc.player.getY());
		}
		if (ticks % 20 == 0) campfire(mc);
	}

	/** PEAK's sunrise, sunset and night music (AmbienceAudio: 5.5-6.5, 19.5-20, from 21.2). */
	private static void dayMusic(String stage, double h) {
		int want = h > 5.5 && h < 6.5 ? 1 : h > 19.5 && h < 20 ? 2 : h > 21.2 || h < 2 ? 3 : 0;
		if (want == 0 || want == dayState) return;
		boolean first = dayState == 0;
		dayState = want;
		if (first || Util.getMillis() < musicUntil) return;   // (not on joining in the middle of one; not over a stage's music)
		String own = switch (stage) { case "tropics" -> "tropics"; case "roots" -> "roots"; case "alpine" -> "alpine"; case "mesa" -> "desert"; default -> null; };
		String role = want == 1 ? (own != null && has(own + "sunrise") ? own + "sunrise" : any("sunrisestinger"))
			: want == 2 ? (own != null && has(own + "sunset") ? own + "sunset" : any("sunsetstinger")) : any("nightstinger");
		music(role, 0.35f);
	}

	/**
	 * The ambience loops: how loud each should be here, now. PEAK's Animator plays them by height, time of day and where you
	 * are; by name (the scout's ambience objects): the forest by day and the night's low down, wind getting stronger
	 * higher up, the desert's wind on the Mesa, high-altitude birds and the blizzard's rough wind on the Alpine, wind at
	 * the peak, the cavern's echo in the Kiln. (The tomb's, the statue room's and the rain's aren't used.)
	 */
	private static void ambience(String stage, double h, double y) {
		boolean night = h >= 21 || h < 5, low = stage.equals("shore") || stage.equals("tropics") || stage.equals("roots") || stage.equals("island");
		for (String role : clips().keySet()) {
			if (!role.startsWith("ambience_")) continue;
			float want = switch (role) {
				case "ambience_day___low" -> !night && low ? 0.5f : 0;
				case "ambience_night___low" -> night && !stage.equals("caldera") && !stage.equals("kiln") ? 0.5f : 0;
				case "ambience_wind_low" -> y < 350 && !stage.equals("alpine") ? 0.3f : 0;
				case "ambience_wind_mid" -> y >= 350 && y < 750 && !stage.equals("alpine") && !stage.equals("mesa") ? 0.35f : 0;
				case "ambience_wind_high" -> y >= 750 || stage.equals("peak") ? 0.45f : 0;
				case "ambience_wind_desert" -> stage.equals("mesa") ? 0.6f : 0;
				case "ambience_birds_high" -> !night && (stage.equals("alpine") || stage.equals("mesa")) ? 0.3f : 0;
				case "ambience_storm_loop" -> stage.equals("alpine") ? 0.8f : 0;
				case "ambience_windloop" -> stage.equals("peak") ? 0.5f : stage.equals("caldera") ? 0.3f : 0;
				case "ambience_cave" -> stage.equals("kiln") ? 0.5f : 0;
				default -> 0;
			};
			PeakSound s = loops.get(role);
			if (want > 0 && !playing(s)) loops.put(role, play(role, SoundSource.AMBIENT, true, want, null, 3f));
			else if (s != null) s.fadeTo(want);
			if (s != null && s.isStopped()) loops.remove(role);
		}
	}

	private static final Map<BlockPos, Boolean> FIRES = new HashMap<>();

	/** The campfires within 16 blocks: the nearest lit one's crackle, from it; a campfire's lighting, as it's lit. */
	private static void campfire(Minecraft mc) {
		BlockPos me = mc.player.blockPosition(), best = null;
		double bestD = Double.MAX_VALUE;
		Map<BlockPos, Boolean> now = new HashMap<>();
		for (BlockPos p : BlockPos.betweenClosed(me.offset(-16, -8, -16), me.offset(16, 8, 16))) {
			BlockState s = mc.level.getBlockState(p);
			if (!s.is(Blocks.CAMPFIRE)) continue;
			boolean lit = s.getValue(CampfireBlock.LIT);
			BlockPos at = p.immutable();
			now.put(at, lit);
			if (lit && Boolean.FALSE.equals(FIRES.get(at))) { String light = any("campfire_light"); if (light != null) play(light, SoundSource.BLOCKS, false, 1f, Vec3.atCenterOf(at), 0); }
			if (lit && at.distSqr(me) < bestD) { bestD = at.distSqr(me); best = at; }
		}
		FIRES.clear();
		FIRES.putAll(now);
		if (best == null) { stop(fire); fire = null; fireAt = null; return; }
		if (!best.equals(fireAt) || !playing(fire)) {
			stop(fire);
			fire = play("campfire_loop", SoundSource.BLOCKS, true, 1f, Vec3.atCenterOf(best), 1f);
			fireAt = best;
		}
	}
}
