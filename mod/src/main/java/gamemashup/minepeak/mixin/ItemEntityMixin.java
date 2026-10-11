package gamemashup.minepeak.mixin;

import gamemashup.minepeak.PeakInventory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A scout picks things up as in PEAK, looked at and used (PeakInventory.Pickup), not by walking over them. And a thing
 * resting where PEAK put it (no gravity, not moving: the island's forage, about a thousand of them) isn't moved each
 * tick: Minecraft runs an item's movement every tick unless it's on the ground, and these hang where PEAK left them,
 * so they never are. Pushed (an explosion, a hand), it moves again as usual.
 */
@Mixin(ItemEntity.class)
abstract class ItemEntityMixin {
	@Shadow private int age;
	@Shadow private int pickupDelay;

	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void minepeak$noWalkingPickup(Player player, CallbackInfo ci) {
		if (PeakInventory.limited(player)) ci.cancel();
	}

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void minepeak$resting(CallbackInfo ci) {
		ItemEntity self = (ItemEntity) (Object) this;
		if (!self.isNoGravity() || self.getDeltaMovement().lengthSqr() > 1.0E-10 || self.getItem().isEmpty()) return;
		// what tick does besides moving it: the pickup delay counting down, and its age (the client spins it by that)
		if (pickupDelay > 0 && pickupDelay != Short.MAX_VALUE) pickupDelay--;
		if (age != Short.MIN_VALUE) age++;
		ci.cancel();
	}
}
