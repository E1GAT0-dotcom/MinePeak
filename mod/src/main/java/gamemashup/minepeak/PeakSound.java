package gamemashup.minepeak;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.phys.Vec3;

/**
 * One of PEAK's sounds playing (PeakSounds): streamed from its WAV (no sounds.json entry: it's its own sound), fading in
 * and out, everywhere at once or from a place (a campfire).
 */
final class PeakSound extends AbstractSoundInstance implements TickableSoundInstance {
	final String role;
	private float target;
	private final float step;
	private boolean stopped;

	/** A sound by its role (its WAV's name), on Minecraft's volume slider for source, fading over fade seconds. */
	PeakSound(String role, SoundSource source, boolean loop, float volume, Vec3 at, float fade) {
		super(MinePeak.id("peak/" + role), source, SoundInstance.createUnseededRandom());
		this.role = role;
		this.looping = loop;
		this.target = volume;
		this.step = fade <= 0 ? 1f : 1f / (fade * 20f);
		this.volume = fade <= 0 ? volume : 0.001f;
		if (at == null) {
			this.relative = true;
			this.attenuation = SoundInstance.Attenuation.NONE;
		} else {
			this.x = at.x; this.y = at.y; this.z = at.z;
			this.attenuation = SoundInstance.Attenuation.LINEAR;
		}
	}

	@Override
	public WeighedSoundEvents resolve(SoundManager soundManager) {
		this.sound = new Sound(identifier, ConstantFloat.of(1f), ConstantFloat.of(1f), 1, Sound.Type.FILE, true, false, 20);
		WeighedSoundEvents events = new WeighedSoundEvents(identifier, null);
		events.addSound(this.sound);
		return events;
	}

	/** At a pitch (PEAK's bugle follows the look). */
	PeakSound pitch(float p) { this.pitch = p; return this; }

	/** Heard from somewhere else now (a swarm of bees moving). */
	void moveTo(net.minecraft.world.phys.Vec3 at) { this.x = at.x; this.y = at.y; this.z = at.z; }

	/** Fades to v (0: out, and then it stops). */
	void fadeTo(float v) { target = v; }

	float target() { return target; }

	@Override public boolean canStartSilent() { return true; }

	@Override public boolean isStopped() { return stopped; }

	@Override
	public void tick() {
		if (volume < target) volume = Math.min(target, volume + step);
		else if (volume > target) volume = Math.max(target, volume - step);
		if (target <= 0 && volume <= 0.001f) stopped = true;
	}
}
