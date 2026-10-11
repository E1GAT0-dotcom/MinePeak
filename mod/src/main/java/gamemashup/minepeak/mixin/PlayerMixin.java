package gamemashup.minepeak.mixin;

import gamemashup.minepeak.PeakItems;
import gamemashup.minepeak.PeakStatus;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A passed-out scout lies down (PeakStatus); PEAK's binoculars look far, as Minecraft's spyglass does. */
@Mixin(Player.class)
abstract class PlayerMixin {
	@Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
	private void minepeak$lying(CallbackInfo ci) {
		Player self = (Player) (Object) this;
		if (PeakStatus.lying(self)) { self.setPose(Pose.SWIMMING); ci.cancel(); }
	}

	@Inject(method = "isScoping", at = @At("RETURN"), cancellable = true)
	private void minepeak$binoculars(CallbackInfoReturnable<Boolean> cir) {
		Player self = (Player) (Object) this;
		if (!cir.getReturnValueZ() && self.isUsingItem() && "Binoculars".equals(PeakItems.peakName(self.getUseItem()))) cir.setReturnValue(true);
	}
}
