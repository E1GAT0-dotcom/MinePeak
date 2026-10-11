package gamemashup.minepeak.mixin;

import gamemashup.minepeak.PeakInventory;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** PEAK's pockets (PeakInventory): for a scout, nothing goes past the three pockets (a backpack into the 4th slot). */
@Mixin(Inventory.class)
abstract class InventoryMixin {
	@Shadow @Final public Player player;

	@Shadow public abstract ItemStack getItem(int slot);

	@Inject(method = "getFreeSlot", at = @At("HEAD"), cancellable = true)
	private void minepeak$pockets(CallbackInfoReturnable<Integer> cir) {
		if (PeakInventory.limited(player)) cir.setReturnValue(PeakInventory.freeSlot((Inventory) (Object) this, null));
	}

	@Inject(method = "getSlotWithRemainingSpace", at = @At("HEAD"), cancellable = true)
	private void minepeak$stackInPockets(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
		if (!PeakInventory.limited(player)) return;
		for (int i = 0; i < PeakInventory.POCKETS; i++) {
			ItemStack have = getItem(i);
			if (!have.isEmpty() && ItemStack.isSameItemSameComponents(have, stack) && have.isStackable() && have.getCount() < have.getMaxStackSize()) { cir.setReturnValue(i); return; }
		}
		cir.setReturnValue(-1);
	}

	@Inject(method = "getSuitableHotbarSlot", at = @At("HEAD"), cancellable = true)
	private void minepeak$suitable(CallbackInfoReturnable<Integer> cir) {
		if (!PeakInventory.limited(player)) return;
		int free = PeakInventory.freeSlot((Inventory) (Object) this, null);
		cir.setReturnValue(free >= 0 ? free : Math.min(((Inventory) (Object) this).getSelectedSlot(), PeakInventory.POCKETS - 1));
	}

	@Inject(method = "add(ILnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
	private void minepeak$backpack(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (slot != -1 || stack.isEmpty() || !PeakInventory.limited(player) || !PeakInventory.isBackpack(stack)) return;
		Inventory inv = (Inventory) (Object) this;
		if (inv.getItem(PeakInventory.BACKPACK).isEmpty()) { inv.setItem(PeakInventory.BACKPACK, stack.copyAndClear()); cir.setReturnValue(true); }
		else cir.setReturnValue(false);
	}
}
