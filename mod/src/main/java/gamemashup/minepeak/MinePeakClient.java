package gamemashup.minepeak;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.Component;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * MinePeak on the client:
 * - when Minecraft starts, today's PEAK island is exported in the background if it isn't yet;
 * - a MinePeak world that needs it waits for it on MinePeak's own screen (IslandWaitScreen), with Cancel;
 * - on the menus and loading screens: the export's progress, or what went wrong, so people can tell (and fix it);
 *   on the title screen, when all's well, that it's on and the island is ready;
 * - Create World starts on the world type MinePeak Island (it can still be changed), with MinePeak's settings in its
 *   World tab (MinePeakWorldOptions);
 * - the menus' panorama is PEAK's airport (AirportPanorama).
 */
public class MinePeakClient implements ClientModInitializer {
	private static final int YELLOW = 0xFFFFFF55, RED = 0xFFFF5555, GREEN = 0xFF7CFC7C;
	private static final ResourceKey<WorldPreset> ISLAND = ResourceKey.create(Registries.WORLD_PRESET, MinePeak.id("island"));
	/** Create World screens already set to MinePeak Island (once each: the player may change it). */
	private static final Set<Screen> CHOSEN = Collections.newSetFromMap(new WeakHashMap<>());
	/** Creative's inventory, opened in a MinePeak world: on PEAK's items (their tab, on Fabric's second page) the first time. */
	private static final Set<Screen> CREATIVE = Collections.newSetFromMap(new WeakHashMap<>());
	private static int ticks;

	@Override
	public void onInitializeClient() {
		HeroTitleHud.init();
		PeakClimbClient.init();
		PeakFogClient.init();
		PeakStatusClient.init();
		PeakHud.init();
		PeakInventoryClient.init();
		PeakGearClient.init();
		PeakLoopsClient.init();
		net.minecraft.client.gui.screens.MenuScreens.register(BackpackMenu.TYPE, BackpackScreen::new);
		ClientLifecycleEvents.CLIENT_STARTED.register(mc ->
			new Thread(() -> PeakExporter.startIfNeeded("Minecraft started"), "MinePeak export check").start());
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (++ticks % 40 == 0 && mc.level == null && mc.gui.overlay() == null) AirportPanorama.update(mc);   // (on the menus, every 2 s)
			PeakWater.tick(mc);
			PeakSounds.tick(mc);
			PeakClimbClient.tick(mc);
			PeakFogClient.tick(mc);
			PeakStatusClient.tick(mc);
			PeakInventoryClient.tick(mc);
			PeakGearClient.tick(mc);
			PeakPackReload.tick(mc);
		});
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			ScreenEvents.afterExtract(screen).register(MinePeakClient::drawStatus);
			if (screen instanceof CreateWorldScreen create && CHOSEN.add(create)) chooseIsland(create);
			if (screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen creative && PeakItems.TAB != null && mc.player != null && PeakWorld.is(mc.player.level()) && CREATIVE.add(creative))
				((net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen) creative).setSelectedTab(PeakItems.TAB);
		});
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> PeakSounds.joined());
		// the development test (-Dminepeak.devtest=true; gradlew runClient -Pdevtest): looked up by name, so a release
		// build can leave it out (gradlew build -Prelease)
		if (Boolean.getBoolean("minepeak.devtest"))
			try { Class.forName("gamemashup.minepeak.DevTest").getMethod("init").invoke(null); }
			catch (ReflectiveOperationException e) { MinePeak.LOG.warn("MinePeak: no development test in this build", e); }
	}

	/** Called as Minecraft starts loading a world: true when MinePeak shows its waiting screen instead (for now). */
	public static boolean waitForIsland(Minecraft mc, LevelStorageSource.LevelStorageAccess storage, PackRepository packs, WorldStem stem,
			Optional<GameRules> rules, boolean newWorld) {
		if (!IslandWaitScreen.needed(storage, stem)) return false;
		mc.gui.setScreen(new IslandWaitScreen(storage, packs, stem, rules, newWorld));
		return true;
	}

	/** Create World on MinePeak Island (it's what MinePeak is for; Default is still one click away). */
	static void chooseIsland(CreateWorldScreen screen) {
		var ui = screen.getUiState();
		for (var entry : ui.getNormalPresetList())
			if (entry.preset() != null && entry.preset().is(ISLAND)) { ui.setWorldType(entry); return; }
		MinePeak.LOG.warn("MinePeak Island isn't among the world types");
	}

	private static final SystemToast.SystemToastId STATUS_TOAST = new SystemToast.SystemToastId(6000L);
	private static String toastText = "";
	private static long toastAt;

	/** The status as a toast (top right; clicks go through it), renewed when it changes and every few seconds. */
	private static void toast(Minecraft mc, String title, String text) {
		long now = System.currentTimeMillis();
		if (text.equals(toastText) && now - toastAt < 4000) return;
		toastText = text;
		toastAt = now;
		SystemToast.addOrUpdate(mc.gui.toastManager(), STATUS_TOAST, Component.literal(title), Component.literal(text));
	}

	private static void drawStatus(Screen screen, GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null || screen instanceof IslandWaitScreen) return;   // (menus and loading screens only; that one says it itself)
		String text;
		int color;
		if (PeakExporter.running()) {
			var s = PeakExporter.status();
			text = "MinePeak: " + (s != null && !s.message().isEmpty() ? s.message() : "exporting today's PEAK island")
				+ (s != null ? " (" + s.percent() + "%)" : "");
			color = YELLOW;
		} else if (PeakExporter.lastError() != null) {
			text = "MinePeak: " + PeakExporter.lastError();
			color = RED;
		} else if (screen instanceof TitleScreen && PeakExporter.todayExported()) {
			text = "MinePeak is on: today's PEAK island is ready. New worlds are MinePeak Island (Singleplayer > Create New World).";
			color = GREEN;
		} else return;
		// off the title screen, a toast in the corner (a strip at the bottom covered the menus' buttons)
		if (!(screen instanceof TitleScreen)) { if (color == GREEN) return; toast(mc, color == RED ? "MinePeak: something went wrong" : "MinePeak", text.replaceFirst("^MinePeak: ", "")); return; }
		var lines = mc.font.split(Component.literal(text), Math.max(120, screen.width - 40));
		int lineH = mc.font.lineHeight + 1, h = lines.size() * lineH;
		// on a dark strip, over the title screen's logo (not its buttons), or under the other screens
		int y = screen instanceof TitleScreen ? 4 : screen.height - 12 - h;
		int w = 0;
		for (FormattedCharSequence line : lines) w = Math.max(w, mc.font.width(line));
		g.fill((screen.width - w) / 2 - 4, y - 3, (screen.width + w) / 2 + 4, y + h + 2, 0xC0000000);
		for (FormattedCharSequence line : lines) {
			g.text(mc.font, line, (screen.width - mc.font.width(line)) / 2, y, color, true);
			y += lineH;
		}
	}
}
