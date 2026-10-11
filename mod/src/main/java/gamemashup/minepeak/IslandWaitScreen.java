package gamemashup.minepeak;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * Shown instead of loading a MinePeak world that still needs today's PEAK island while it's being exported (in the
 * background, by a PEAK with no window): its progress, and a Cancel button. Minecraft carries on as usual meanwhile
 * (closing the window just quits). When the island is ready, the world loads; when the export fails, it says why,
 * with Try again, Use the older island (if there is one) and Back.
 */
public final class IslandWaitScreen extends Screen {
	private static final long MAX_MILLIS = 20 * 60_000L;
	private static final int YELLOW = 0xFFFFFF55, RED = 0xFFFF5555, GREY = 0xFFA0A0A0, WHITE = 0xFFFFFFFF;

	/** Set while MinePeak hands the world back to Minecraft, so it isn't stopped again. */
	static boolean resuming;

	private final LevelStorageSource.LevelStorageAccess storage;
	private final PackRepository packs;
	private final WorldStem stem;
	private final Optional<GameRules> rules;
	private final boolean newWorld;
	private long started = System.currentTimeMillis();
	private boolean failed, done;

	/** Should Minecraft wait before loading this world (a MinePeak world with no island of its own yet)? */
	static boolean needed(LevelStorageSource.LevelStorageAccess storage, WorldStem stem) {
		if (resuming) { resuming = false; return false; }
		if (!(stem.worldDataAndGenSettings().genSettings().dimensions().overworld() instanceof IslandChunkGenerator g)) return false;
		if (Files.exists(storage.getLevelPath(LevelResource.ROOT).resolve("minepeak.json"))) return false;   // (tied to its island)
		if (!"latest".equals(g.island())) return false;   // (an older island, picked in Create World)
		if (PeakExporter.todayExported()) return false;
		PeakExporter.startIfNeeded("a MinePeak world");
		return true;
	}

	IslandWaitScreen(LevelStorageSource.LevelStorageAccess storage, PackRepository packs, WorldStem stem, Optional<GameRules> rules, boolean newWorld) {
		super(Component.literal("MinePeak"));
		this.storage = storage;
		this.packs = packs;
		this.stem = stem;
		this.rules = rules;
		this.newWorld = newWorld;
		failed = !PeakExporter.running() && !PeakExporter.todayExported();
	}

	public boolean failed() { return failed; }

	@Override
	protected void init() {
		int x = width / 2 - 100, y = height / 2 + 40;
		if (!failed) {
			addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> back()).bounds(x, y, 200, 20).build());
			return;
		}
		addRenderableWidget(Button.builder(Component.literal("Try again"), b -> tryAgain()).bounds(x, y, 200, 20).build());
		Path older = olderIsland();
		if (older != null)
			addRenderableWidget(Button.builder(Component.literal("Use the island of " + older.getFileName().toString().substring(0, 10)), b -> load())
				.bounds(x, y + 24, 200, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> back()).bounds(x, y + (older != null ? 48 : 24), 200, 20).build());
	}

	static Path olderIsland() {
		try { return IslandData.latest(); } catch (Exception e) { return null; }
	}

	@Override
	public void tick() {
		if (done || failed) return;
		boolean late = System.currentTimeMillis() - started > MAX_MILLIS;
		if (PeakExporter.running() && !late) return;
		if (PeakExporter.result(MAX_MILLIS)) load();
		else { failed = true; rebuildWidgets(); }
	}

	void tryAgain() {
		if (PeakExporter.todayExported() || PeakExporter.startIfNeeded("Try again")) {
			failed = false;
			started = System.currentTimeMillis();
		}
		rebuildWidgets();
	}

	/**
	 * Loads the world: with today's island, or (after a failure) the newest one there is. Not straight away: from
	 * Minecraft's task queue, outside this screen's tick (loading a world replaces the screen, and the screen events
	 * round its tick then find none).
	 */
	void load() {
		if (done) return;
		done = true;
		minecraft.schedule(() -> {
			resuming = true;
			minecraft.doWorldLoad(storage, packs, stem, rules, newWorld);
		});
	}

	/** Back to the title screen without the world (a new one, made just now and still empty, is removed again). */
	public void back() {
		if (done) return;
		release();
		minecraft.gui.setScreen(new TitleScreen());
	}

	private void release() {
		done = true;
		stem.close();
		try {
			if (newWorld && !storage.hasWorldData()) storage.deleteLevel();
		} catch (Exception e) {
			MinePeak.LOG.warn("Couldn't remove the unfinished world {}", storage.getLevelId(), e);
		}
		storage.safeClose();
	}

	@Override public void onClose() { back(); }

	@Override public void removed() { if (!done) release(); }   // (Minecraft closing meanwhile)

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		int cx = width / 2, y = height / 2 - 70;
		g.centeredText(font, Component.literal("MinePeak"), cx, y, WHITE);
		if (failed) {
			g.centeredText(font, Component.literal("Today's PEAK island couldn't be made"), cx, y + 18, RED);
			String why = PeakExporter.lastError() != null ? PeakExporter.lastError() : "PEAK stopped before it was done.";
			g.textWithWordWrap(font, Component.literal(why), cx - 150, y + 34, 300, RED);
			return;
		}
		var s = PeakExporter.status();
		int percent = s != null ? s.percent() : 0;
		g.centeredText(font, Component.literal("Getting today's PEAK island from PEAK..."), cx, y + 18, YELLOW);
		g.centeredText(font, Component.literal(s != null && !s.message().isEmpty() ? s.message() : "Starting PEAK"), cx, y + 34, GREY);
		int bx = cx - 100, by = y + 52;
		g.fill(bx - 1, by - 1, bx + 201, by + 9, 0xFF606060);
		g.fill(bx, by, bx + 200, by + 8, 0xFF202020);
		g.fill(bx, by, bx + 2 * Math.max(0, Math.min(100, percent)), by + 8, 0xFF55C040);
		g.centeredText(font, Component.literal(percent + "%"), cx, by + 12, WHITE);
		g.centeredText(font, Component.literal("Once a day, when PEAK's island changes. The world opens when it's ready."), cx, height - 30, GREY);
	}
}
