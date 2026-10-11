package gamemashup.minepeak;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/** The loops the server says this player hears (PeakLoops): each kept playing where it is, faded out when it's gone. */
final class PeakLoopsClient {
	private PeakLoopsClient() { }

	/** A loop playing: PEAK's (PeakSound) or Minecraft's (Vanilla), and when it was last said. */
	private static final class Playing {
		PeakSound peak;
		Vanilla vanilla;
		String role;
		int seen;
	}

	private static final Map<String, Playing> PLAYING = new HashMap<>();
	private static int ticks;

	static void init() {
		ClientPlayNetworking.registerGlobalReceiver(PeakLoops.Loops.TYPE, (payload, context) -> context.client().execute(() -> heard(payload)));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			ticks++;
			if (mc.level == null) { stopAll(); return; }
			for (Iterator<Playing> it = PLAYING.values().iterator(); it.hasNext(); ) {
				Playing p = it.next();
				if (ticks - p.seen > 20) { fade(p, 0); it.remove(); }
			}
		});
	}

	private static void heard(PeakLoops.Loops loops) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		for (PeakLoops.Loop l : loops.loops()) {
			Vec3 at = new Vec3(l.x(), l.y(), l.z());
			Playing p = PLAYING.get(l.key());
			if (p != null && !l.role().equals(p.role)) { fade(p, 0); p = null; }   // (calm bees turned angry: the other loop)
			if (p == null) {
				p = new Playing();
				p.role = l.role();
				if (PeakSounds.has(l.role())) {
					p.peak = new PeakSound(l.role(), SoundSource.HOSTILE, true, l.volume(), at, 0.5f);
					mc.getSoundManager().play(p.peak);
				} else if (!l.fallback().isEmpty()) {
					SoundEvent ev = BuiltInRegistries.SOUND_EVENT.getValue(Identifier.parse(l.fallback()));
					if (ev != null) { p.vanilla = new Vanilla(ev, l.volume(), at); mc.getSoundManager().play(p.vanilla); }
				}
				PLAYING.put(l.key(), p);
			}
			p.seen = ticks;
			if (p.peak != null) { p.peak.moveTo(at); p.peak.fadeTo(l.volume()); }
			if (p.vanilla != null) { p.vanilla.moveTo(at); p.vanilla.target = l.volume(); }
		}
	}

	private static void fade(Playing p, float v) {
		if (p.peak != null) p.peak.fadeTo(v);
		if (p.vanilla != null) p.vanilla.target = v;
	}

	private static void stopAll() {
		for (Playing p : PLAYING.values()) fade(p, 0);
		PLAYING.clear();
	}

	/** Minecraft's own looping sound in PEAK's place (until an export has PEAK's), following its place. */
	private static final class Vanilla extends AbstractTickableSoundInstance {
		float target;

		Vanilla(SoundEvent ev, float volume, Vec3 at) {
			super(ev, SoundSource.HOSTILE, SoundInstance.createUnseededRandom());
			this.looping = true;
			this.delay = 0;
			this.volume = 0.01f;
			this.target = volume;
			moveTo(at);
		}

		void moveTo(Vec3 at) { this.x = at.x; this.y = at.y; this.z = at.z; }

		@Override public boolean canStartSilent() { return true; }

		@Override
		public void tick() {
			if (volume < target) volume = Math.min(target, volume + 0.05f);
			else if (volume > target) volume = Math.max(target, volume - 0.05f);
			if (target <= 0 && volume <= 0.01f) stop();
		}
	}
}
