package gamemashup.minepeak.mixin.client;

import gamemashup.minepeak.PeakSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.Music;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No Minecraft music while PEAK's plays (its title screen's music, its stages' music). */
@Mixin(Minecraft.class)
abstract class MusicMixin {
	@Inject(method = "getSituationalMusic", at = @At("HEAD"), cancellable = true)
	private void minepeak$peakMusic(CallbackInfoReturnable<Music> cir) {
		if (PeakSounds.musicOwned()) cir.setReturnValue(null);
	}
}
