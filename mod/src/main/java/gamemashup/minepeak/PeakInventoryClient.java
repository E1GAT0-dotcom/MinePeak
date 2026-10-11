package gamemashup.minepeak;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * PEAK's pockets on the client: keys 1 to 3 are the pockets and 4 the backpack (5 to 9 do nothing), the wheel goes round
 * those four (MouseHandlerMixin); Minecraft's inventory screen and the off-hand swap are closed to scouts (creative keeps
 * them). A thing on the ground that the player looks at, close by, is named under the crosshair with PEAK's prompt, and
 * using (right click) picks it up (PeakInventory.Pickup).
 */
public final class PeakInventoryClient {
	private PeakInventoryClient() { }

	static ItemEntity target;

	static void init() {
		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			if (mc.player == null || !PeakInventory.limited(mc.player)) return;
			for (int i = PeakInventory.BACKPACK + 1; i < 9; i++) while (mc.options.keyHotbarSlots[i].consumeClick()) { }
			while (mc.options.keyInventory.consumeClick()) { }
			while (mc.options.keySwapOffhand.consumeClick()) { }
			if (mc.gui.screen() instanceof InventoryScreen) mc.gui.setScreen(null);
		});
	}

	static void tick(Minecraft mc) {
		target = null;
		var p = mc.player;
		if (p == null || mc.level == null || !PeakInventory.limited(p)) return;
		Vec3 eye = p.getEyePosition(), look = p.getViewVector(1f), end = eye.add(look.scale(3.5));
		double block = mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK ? mc.hitResult.getLocation().distanceTo(eye) : 99;
		double best = Math.min(block + 0.6, 3.5);
		for (ItemEntity e : mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(eye, end).inflate(1))) {
			var hit = e.getBoundingBox().inflate(0.3).clip(eye, end);
			if (hit.isEmpty()) continue;
			double d = hit.get().distanceTo(eye);
			if (d < best) { best = d; target = e; }
		}
	}

	/** Using (right click) at a thing on the ground: pick it up. True if it did (Minecraft's own use doesn't happen). */
	public static boolean pickUp(Minecraft mc) {
		if (target == null || mc.player == null || !PeakInventory.limited(mc.player) || !target.isAlive()) return false;
		ClientPlayNetworking.send(new PeakInventory.Pickup(target.getId()));
		mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
		target = null;
		return true;
	}

	/** Under the crosshair: the thing looked at, and PEAK's prompt for it. */
	static void drawPrompt(GuiGraphicsExtractor g, Minecraft mc) {
		var t = target;
		if (t == null || !t.isAlive()) return;
		var name = PeakFonts.text(t.getItem().getHoverName().getString(), PeakFonts.MID);
		boolean full = PeakInventory.freeSlot(mc.player.getInventory(), t.getItem()) < 0;
		var prompt = PeakFonts.text("[" + mc.options.keyUse.getTranslatedKeyMessage().getString() + "] " + (full ? "SWAP" : "PICK UP"), PeakFonts.MAIN);
		int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
		g.text(mc.font, name, cx - mc.font.width(name) / 2, cy + 12, 0xFFDFDAC2, true);
		g.text(mc.font, prompt, cx - mc.font.width(prompt) / 2, cy + 26, 0xFFBDB6A4, true);
	}
}
