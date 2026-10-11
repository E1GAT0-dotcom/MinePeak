package gamemashup.minepeak.mixin.client;

import com.mojang.blaze3d.audio.SoundBuffer;
import gamemashup.minepeak.PeakSounds;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * PEAK's sounds (minepeak:peak/...) come from the WAVs the MinePeak Exporter saved from the player's own PEAK, not from a
 * resource pack (nothing of PEAK's ships with MinePeak): Minecraft asks here for every sound it plays.
 */
@Mixin(SoundBufferLibrary.class)
abstract class SoundBufferLibraryMixin {
	@Inject(method = "getStream", at = @At("HEAD"), cancellable = true)
	private void minepeak$peakStream(Identifier location, boolean looping, CallbackInfoReturnable<CompletableFuture<AudioStream>> cir) {
		if (PeakSounds.handles(location)) cir.setReturnValue(PeakSounds.stream(location, looping));
	}

	@Inject(method = "getCompleteBuffer", at = @At("HEAD"), cancellable = true)
	private void minepeak$peakBuffer(Identifier location, CallbackInfoReturnable<CompletableFuture<SoundBuffer>> cir) {
		if (PeakSounds.handles(location)) cir.setReturnValue(PeakSounds.buffer(location));
	}
}
