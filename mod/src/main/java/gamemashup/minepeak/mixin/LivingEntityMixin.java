package gamemashup.minepeak.mixin;

import gamemashup.minepeak.PeakClimb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** PEAK's climbing: a wall the player holds on to is a ladder to Minecraft (PeakClimb). */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
	@Inject(method = "onClimbable", at = @At("RETURN"), cancellable = true)
	private void minepeak$wall(CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && (Object) this instanceof Player p && PeakClimb.onWall(p)) cir.setReturnValue(true);
	}
}
