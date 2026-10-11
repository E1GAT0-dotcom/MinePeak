package gamemashup.minepeak.mixin.client;

import gamemashup.minepeak.PeakScreenFx;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** PEAK's camera shake (GamefeelHandler's PerlinShake: a knock, a curse, poison, the cold...), on the view's turn. */
@Mixin(Camera.class)
abstract class CameraMixin {
	@ModifyVariable(method = "setRotation", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private float minepeak$shakeYaw(float yRot) { return yRot + PeakScreenFx.shakeYaw(); }

	@ModifyVariable(method = "setRotation", at = @At("HEAD"), argsOnly = true, ordinal = 1)
	private float minepeak$shakePitch(float xRot) { return xRot + PeakScreenFx.shakePitch(); }
}
