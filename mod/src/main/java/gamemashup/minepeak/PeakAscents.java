package gamemashup.minepeak;

import net.minecraft.server.MinecraftServer;

/**
 * PEAK's Ascents (its difficulty, -1 to 8, each adding to the ones before; PEAK's Ascents class), set when the world
 * is made (Create World's World tab):
 * -1 easier: hunger x0.7, climbing x0.7 stamina, no fog, no mushroom zombies, no Scoutmaster;
 *  1 falls hurt twice as much; 2 hunger x1.6; 3 every item weighs one more; 4 no flare waits at the peak;
 *  5 the nights are cold; 6 climbing takes x1.4 stamina; 7 you start a little cursed, and revivals curse more;
 *  8 the flare won't call the rescue.
 */
public final class PeakAscents {
	private PeakAscents() { }

	static int of(MinecraftServer server) {
		var g = server != null ? MinePeakServer.generator(server) : null;
		return g != null ? g.ascent() : 0;
	}

	static String title(int a) {
		return a < 0 ? "Ascent: easier (-1)" : a == 0 ? "Ascent: none (PEAK as it comes)" : "Ascent " + a;
	}

	static String describe(int a) {
		if (a < 0) return "Easier than PEAK: less hunger, climbing costs less, no fog, no mushroom zombies, no Scoutmaster.";
		if (a == 0) return "PEAK as it comes.";
		String[] each = { "", "Falls hurt twice as much.", "You get hungry faster.", "Everything you carry weighs more.",
			"No flare waits at the peak: bring your own.", "The nights are cold.", "Climbing takes more stamina.",
			"You start a little cursed; coming back costs more curse.", "The flare won't call the rescue." };
		StringBuilder b = new StringBuilder("PEAK, harder. Each ascent adds to the ones before:");
		for (int i = 1; i <= Math.min(8, a); i++) b.append("\n").append(i).append(": ").append(each[i]);
		return b.toString();
	}

	static float damageMultiplier(MinecraftServer s) { return 1f; }
	static float fallMultiplier(MinecraftServer s) { return of(s) >= 1 ? 2f : 1f; }
	static float hungerMultiplier(MinecraftServer s) { int a = of(s); return a < 0 ? 0.7f : a >= 2 ? 1.6f : 1f; }
	static float climbMultiplier(MinecraftServer s) { int a = of(s); return a < 0 ? 0.7f : a >= 6 ? 1.4f : 1f; }
	static int extraWeight(MinecraftServer s) { return of(s) >= 3 ? 1 : 0; }
	static boolean flareAtPeak(MinecraftServer s) { return of(s) < 4; }
	static boolean coldNights(MinecraftServer s) { return of(s) >= 5; }
	static float startCurse(MinecraftServer s) { return of(s) >= 7 ? 0.1f : 0f; }
	static float revivalCurse(MinecraftServer s) { return of(s) >= 7 ? 0.15f : 0.05f; }
	static boolean rescue(MinecraftServer s) { return of(s) < 8; }
	static boolean fog(MinecraftServer s) { return of(s) >= 0; }
	static boolean zombies(MinecraftServer s) { return of(s) >= 0; }
	static boolean scoutmaster(MinecraftServer s) { return of(s) >= 0; }
}
