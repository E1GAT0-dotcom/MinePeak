package gamemashup.minepeak;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;

/**
 * The scout's PEAK body on the client, as the server keeps it (PeakStatus.ScoutState): the statuses for the stamina bar
 * (PeakHud) and its most (PeakClimbClient), PEAK's screen effects as statuses go up (PeakScreenFx), and the
 * screen going dark as the scout passes out, black while it's out (with
 * PEAK's dying bar, and "hold sneak to give up"), and the ghost's words when it's dead.
 */
public final class PeakStatusClient {
	private PeakStatusClient() { }

	static volatile PeakStatus.ScoutState state;
	private static float dark;

	static void init() {
		ClientPlayNetworking.registerGlobalReceiver(PeakStatus.ScoutState.TYPE, (payload, context) -> state = payload);
		ClientPlayNetworking.registerGlobalReceiver(PeakStatus.Bonus.TYPE, (payload, context) -> {
			if (payload.amount() >= 0) PeakClimbClient.bonus = Math.min(1f, PeakClimbClient.bonus + payload.amount());
			else PeakClimbClient.bonus = Math.max(0, PeakClimbClient.bonus + payload.amount());
		});
		HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, MinePeak.id("passed_out"), PeakStatusClient::draw);
	}

	static float status(int i) { var s = state; return s != null ? s.status()[i] : 0; }

	static float sum() { var s = state; if (s == null) return 0; float t = 0; for (float v : s.status()) t += v; return t; }

	static boolean flag(int f) { var s = state; return s != null && (s.flags() & f) != 0; }

	static float climbMult() { var s = state; return s != null ? s.climbMult() : 1; }

	/** How many balloons the scout has tied on (the server keeps them). */
	static int balloons() { var s = state; return s != null ? s.balloons() : 0; }

	/** Is the local player to lie down (passed out, or carried)? Other players' poses come from the server. */
	public static boolean lying(Player p) {
		return p == Minecraft.getInstance().player && (flag(PeakStatus.F_OUT) || flag(PeakStatus.F_CARRIED));
	}

	static void tick(Minecraft mc) {
		if (mc.level == null) { state = null; PeakScreenFx.reset(); return; }
		var s = state;
		if (s != null) PeakScreenFx.statuses(s.status(), s.flags());
	}

	private static void draw(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		var s = state;
		if (s == null || mc.player == null) { dark = 0; return; }
		boolean out = flag(PeakStatus.F_OUT), dead = flag(PeakStatus.F_DEAD);
		if (!dead) PeakScreenFx.draw(g);
		float want = dead ? 0 : out ? 0.92f : s.passOut() * 0.7f;
		dark += (want - dark) * 0.08f;
		int w = g.guiWidth(), h = g.guiHeight();
		if (dark > 0.01f) g.fill(0, 0, w, h, (Math.round(dark * 255) << 24));
		var font = mc.font;
		if (out) {
			var title = PeakFonts.text(flag(PeakStatus.F_CARRIED) ? "BEING CARRIED" : "PASSED OUT", PeakFonts.BIG);
			g.text(font, title, (w - font.width(title)) / 2, h / 2 - 30, 0xFFE8E2D0, true);
			// PEAK's dying bar
			int bw = 160, bx = (w - bw) / 2, by = h / 2;
			g.fill(bx - 1, by - 1, bx + bw + 1, by + 7, 0xFF1A444D);
			g.fill(bx, by, bx + Math.round(bw * (1 - s.dying())), by + 6, 0xFFC4473A);
			var hint = PeakFonts.text(flag(PeakStatus.F_CARRIED) ? "A friend is carrying you" : "A friend can feed or heal you, or carry you to safety. Hold SNEAK to give up.", PeakFonts.MAIN);
			g.text(font, hint, (w - font.width(hint)) / 2, by + 14, 0xFFBDB6A4, true);
		} else if (dead) {
			var title = PeakFonts.text("YOU DIED", PeakFonts.BIG);
			g.text(font, title, (w - font.width(title)) / 2, 30, 0xFFE8E2D0, true);
			var hint = PeakFonts.text("A scout statue, or a scout effigy, can bring you back", PeakFonts.MAIN);
			g.text(font, hint, (w - font.width(hint)) / 2, 50, 0xFFBDB6A4, true);
		}
	}
}
