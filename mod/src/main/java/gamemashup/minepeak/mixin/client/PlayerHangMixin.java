package gamemashup.minepeak.mixin.client;

import gamemashup.minepeak.PeakClimbClient;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** On a wall, letting go of the keys hangs on (as sneak does on a ladder), unless back is pressed (PeakClimbClient). */
@Mixin(LocalPlayer.class)
abstract class PlayerHangMixin {
	@Inject(method = "isSuppressingSlidingDownLadder", at = @At("RETURN"), cancellable = true)
	private void minepeak$hang(CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && PeakClimbClient.hanging((LocalPlayer) (Object) this)) cir.setReturnValue(true);
	}
}
