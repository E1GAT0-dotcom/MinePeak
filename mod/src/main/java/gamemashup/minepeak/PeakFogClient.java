package gamemashup.minepeak;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * PEAK's fog on the client (PeakFog sends where it is, and the player's cold): out in it, the screen goes white the
 * deeper you are; near its edge, a wall of fog drifts (clouds on the sphere round you). The morale boost of a campfire
 * comes here too (PeakClimbClient's bonus stamina).
 */
public final class PeakFogClient {
	private PeakFogClient() { }

	static volatile PeakFog.FogState state;
	private static float shown;
	private static final RandomSource RANDOM = RandomSource.create();

	static void init() {
		ClientPlayNetworking.registerGlobalReceiver(PeakFog.FogState.TYPE, (payload, context) -> state = payload);
		ClientPlayNetworking.registerGlobalReceiver(PeakFog.Morale.TYPE, (payload, context) -> PeakClimbClient.bonus = Math.min(1f, PeakClimbClient.bonus + payload.amount()));
		HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, MinePeak.id("fog"), PeakFogClient::draw);
	}

	/** The player's cold (0..1+), as the server last said. */
	static float cold() { var s = state; return s != null ? s.cold() : 0; }

	/** How far out in the fog the player is (blocks past its edge; below 0: inside). */
	static double depth(Minecraft mc) {
		var s = state;
		if (s == null || !s.active() || mc.player == null) return -1e9;
		return mc.player.getEyePosition().distanceTo(new Vec3(s.x(), s.y(), s.z())) - s.size();
	}

	static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null) { state = null; return; }
		var s = state;
		if (s == null || !s.active() || !PeakSounds.inPeakWorld(mc)) return;
		// the fog's wall near the player: clouds on the sphere, within 32 blocks
		Vec3 c = new Vec3(s.x(), s.y(), s.z()), eye = mc.player.getEyePosition();
		double d = eye.distanceTo(c) - s.size();
		if (Math.abs(d) > 40) return;
		for (int i = 0; i < 24; i++) {
			Vec3 p = eye.add((RANDOM.nextDouble() - 0.5) * 64, (RANDOM.nextDouble() - 0.5) * 32, (RANDOM.nextDouble() - 0.5) * 64);
			Vec3 on = c.add(p.subtract(c).normalize().scale(s.size()));
			if (on.distanceTo(eye) > 32) continue;
			mc.level.addParticle(ParticleTypes.CLOUD, on.x, on.y, on.z, 0, 0.01, 0);
		}
	}

	/** Out in the fog: PEAK's white, thicker the deeper you are (over the world, under the hotbar). */
	private static void draw(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		double d = depth(mc);
		float want = d <= 0 ? 0 : (float) Math.min(0.85, 0.25 + d / 30.0);
		shown += (want - shown) * 0.05f;
		if (shown < 0.01f) return;
		int a = Math.round(shown * 255);
		g.fill(0, 0, g.guiWidth(), g.guiHeight(), (a << 24) | 0xDCE3E6);
	}
}
