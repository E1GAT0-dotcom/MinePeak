package gamemashup.minepeak.mixin.client;

import gamemashup.minepeak.PeakInventory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** The scroll wheel goes round PEAK's four slots (three pockets and the backpack) for a scout. */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@ModifyArg(method = "onScroll", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/ScrollWheelHandler;getNextScrollWheelSelection(DII)I"), index = 2)
	private int minepeak$fourSlots(int limit) {
		var p = Minecraft.getInstance().player;
		return p != null && PeakInventory.limited(p) ? PeakInventory.BACKPACK + 1 : limit;
	}
}
