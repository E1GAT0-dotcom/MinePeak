package gamemashup.minepeak;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * PEAK's pockets: a scout holds three things (the hotbar's first three slots, one thing to a slot) and a backpack (the
 * 4th: only a backpack, fanny pack, jetpack or rocketpack goes there). Nothing else goes anywhere else: Minecraft's
 * inventory screen is closed to scouts (creative still has it), and whatever ends up past the four slots (or in the
 * off hand) is dropped. Things are picked up as in PEAK, looked at and used (right click: Pickup), not walked over;
 * with the pockets full, the thing in hand is swapped for it. Some things are only ever held, never pocketed (PEAK's
 * canPocket: a beehive, a mandrake, dynamite, a basketball...): put away (another slot chosen), they're dropped, and
 * they can't be picked up while climbing. Some things aren't what they seem (PEAK's isSecretlyOtherItemPrefab): a
 * medicinal root that's really a mandrake turns into one when it's picked up.
 */
public final class PeakInventory {
	private PeakInventory() { }

	public static final int POCKETS = 3, BACKPACK = 3;

	/** Does this player's inventory follow PEAK's pockets? */
	public static boolean limited(Player p) { return PeakWorld.scout(p); }

	public static boolean isBackpack(ItemStack s) {
		var def = PeakItems.def(s);
		return def != null && (def.has("BackpackStabilizer") || def.item().equals("Backpack"));
	}

	/** The first empty pocket (or the backpack slot, for a backpack), or -1. */
	/** Can it go in a pocket (PEAK's canPocket), or is it only ever held? */
	public static boolean pocketable(ItemStack s) {
		var def = PeakItems.def(s);
		return def == null || !def.ui().has("canPocket") || def.ui().get("canPocket").getAsBoolean();
	}

	public static int freeSlot(Inventory inv, ItemStack forThis) {
		if (forThis != null && isBackpack(forThis)) return inv.getItem(BACKPACK).isEmpty() ? BACKPACK : -1;
		for (int i = 0; i < POCKETS; i++) if (inv.getItem(i).isEmpty()) return i;
		return -1;
	}

	// ---- picking up ----

	public record Pickup(int entity) implements CustomPacketPayload {
		public static final Type<Pickup> TYPE = new Type<>(MinePeak.id("pickup"));
		public static final StreamCodec<FriendlyByteBuf, Pickup> CODEC = ByteBufCodecs.VAR_INT.map(Pickup::new, Pickup::entity).cast();
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	static void initCommon() {
		PayloadTypeRegistry.serverboundPlay().register(Pickup.TYPE, Pickup.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Pickup.TYPE, (payload, ctx) -> pickUp(ctx.player(), payload.entity()));
	}

	static void pickUp(ServerPlayer p, int id) {
		if (!(p.level().getEntity(id) instanceof ItemEntity e) || !e.isAlive() || p.distanceTo(e) > 5 || PeakStatus.out(p)) return;
		ItemStack st = e.getItem();
		// what it really is (a mandrake passing for a medicinal root)
		String secret = PeakItemData.secretly(PeakItems.peak(st));
		if (secret != null) {
			ItemStack real = PeakItems.stack(secret);
			if (!real.isEmpty()) {
				real.setCount(st.getCount());
				st = real;
				e.setItem(st);
				if (!pocketable(st) && PeakClimb.onWall(p)) { e.setDeltaMovement(0, 0.1, 0); return; }   // (PEAK: climbing, it drops from the hand)
			}
		}
		if (!pocketable(st) && PeakClimb.onWall(p)) return;   // (only held things can't be taken while climbing)
		int slot = freeSlot(p.getInventory(), st);
		if (slot < 0) {
			// a stack of the same thing takes more of it (Minecraft's own blocks and the like)
			for (int i = 0; i < POCKETS && slot < 0; i++) {
				ItemStack have = p.getInventory().getItem(i);
				if (ItemStack.isSameItemSameComponents(have, st) && have.getCount() < have.getMaxStackSize()) slot = i;
			}
		}
		if (slot < 0) {
			// pockets full: swap with what's in hand (it's left where the new thing was)
			int sel = p.getInventory().getSelectedSlot();
			int swap = isBackpack(st) ? BACKPACK : sel < POCKETS ? sel : 0;
			ItemStack held = p.getInventory().getItem(swap);
			if (!held.isEmpty()) {
				ItemEntity left = new ItemEntity(p.level(), e.getX(), e.getY(), e.getZ(), held.copy());
				left.setPickUpDelay(10);
				left.setUnlimitedLifetime();
				p.level().addFreshEntity(left);
			}
			p.getInventory().setItem(swap, st.copy());
			p.take(e, st.getCount());
			e.discard();
			if (swap < POCKETS) p.getInventory().setSelectedSlot(swap);
			p.level().playSound(null, p.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.3f, 1.2f);
			PeakItems.prepare(p.getInventory().getItem(swap));
			return;
		}
		int count = st.getCount();
		ItemStack have = p.getInventory().getItem(slot);
		if (have.isEmpty()) p.getInventory().setItem(slot, st.copy());
		else { int take = Math.min(count, have.getMaxStackSize() - have.getCount()); have.grow(take); st.shrink(take); count = take; }
		p.take(e, count);
		if (have.isEmpty() || st.isEmpty()) e.discard(); else e.setItem(st);
		if (slot < POCKETS) p.getInventory().setSelectedSlot(slot);
		p.level().playSound(null, p.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.3f, 1.2f);
		PeakItems.prepare(p.getInventory().getItem(slot));
	}

	// ---- every tick: nothing past the pockets ----

	static void tick(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!PeakWorld.scout(p)) continue;
			Inventory inv = p.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				if (i < POCKETS || i >= 36 && i < 40) continue;   // (the pockets, and the armour: a glider or the like is worn)
				ItemStack st = inv.getItem(i);
				if (st.isEmpty()) continue;
				if (i == BACKPACK && isBackpack(st)) continue;
				inv.setItem(i, ItemStack.EMPTY);
				int to = freeSlot(inv, st);
				if (to >= 0 && to != i) inv.setItem(to, st);
				else p.drop(st, false, false);
			}
			if (inv.getSelectedSlot() > BACKPACK) inv.setSelectedSlot(0);
			// what's only ever held is dropped once something else is chosen
			for (int i = 0; i < POCKETS; i++) {
				ItemStack st = inv.getItem(i);
				if (i == inv.getSelectedSlot() || st.isEmpty() || pocketable(st) || p.isCreative()) continue;
				inv.setItem(i, ItemStack.EMPTY);
				var e = p.drop(st, false, false);
				if (e != null) { e.setUnlimitedLifetime(); e.setPickUpDelay(10); }
			}
		}
	}
}
