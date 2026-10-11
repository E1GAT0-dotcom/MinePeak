package gamemashup.minepeak;

import net.minecraft.client.Minecraft;

/** When an export brings PEAK's item icons while Minecraft is open, its resources are reloaded once (on the menus) to show them. */
final class PeakPackReload {
	private PeakPackReload() { }

	private static int ticks;
	private static boolean reloading;

	static void tick(Minecraft mc) {
		if (++ticks % 100 != 0 || reloading || mc.level != null || mc.gui.overlay() != null || PeakExporter.running()) return;
		if (!PeakPack.stale()) return;
		reloading = true;
		MinePeak.LOG.info("MinePeak: PEAK's item icons are new: reloading resources");
		mc.reloadResourcePacks().whenComplete((v, t) -> reloading = false);
	}
}
