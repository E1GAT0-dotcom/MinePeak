package gamemashup.minepeak;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * An open backpack, as in PEAK: its four places and the scout's three pockets, nothing else (BackpackScreen). A
 * backpack doesn't go into a backpack, nor does anything PEAK keeps out of one (its canBackpack); the open backpack
 * itself stays where it is.
 */
public class BackpackMenu extends AbstractContainerMenu {
	public static final int SIZE = 4;
	public static final MenuType<BackpackMenu> TYPE = Registry.register(BuiltInRegistries.MENU, MinePeak.id("backpack"),
		new MenuType<>(BackpackMenu::new, FeatureFlags.VANILLA_SET));

	/** Where things sit in BackpackScreen (the item's top left). */
	public static final int[][] PACK_AT = { { 31, 26 }, { 53, 26 }, { 75, 26 }, { 97, 26 } };
	public static final int[][] POCKET_AT = { { 42, 72 }, { 64, 72 }, { 86, 72 } };

	private final Container box;
	private final int open;
	private final ItemStack pack;   // (the backpack open: on the server; the client's menu has none)

	public BackpackMenu(int id, Inventory inv) { this(id, inv, new SimpleContainer(SIZE), -1, null); }

	BackpackMenu(int id, Inventory inv, Container box, int open, ItemStack pack) {
		super(TYPE, id);
		this.box = box;
		this.open = open;
		this.pack = pack;
		for (int i = 0; i < SIZE; i++)
			addSlot(new Slot(box, i, PACK_AT[i][0], PACK_AT[i][1]) {
				@Override public boolean mayPlace(ItemStack s) { return fits(s); }
				@Override public int getMaxStackSize() { return 64; }
			});
		for (int i = 0; i < PeakInventory.POCKETS; i++) {
			final int slot = i;
			addSlot(new Slot(inv, i, POCKET_AT[i][0], POCKET_AT[i][1]) {
				@Override public boolean mayPickup(Player p) { return slot != open; }
				@Override public boolean mayPlace(ItemStack s) { return slot != open && !PeakInventory.isBackpack(s); }
			});
		}
	}

	static void init() { }

	/** May this go into a backpack? Not a backpack, nor what PEAK keeps out of one. */
	public static boolean fits(ItemStack s) {
		if (s.isEmpty()) return true;
		if (PeakInventory.isBackpack(s)) return false;
		var def = PeakItems.def(s);
		return def == null || !def.ui().has("canBackpack") || def.ui().get("canBackpack").getAsBoolean();
	}

	@Override
	public boolean stillValid(Player player) {
		// (closed once that backpack isn't where it was opened from any more, or its scout passes out: what's put in
		// a backpack that's gone would be lost, or doubled)
		if (pack == null) return true;
		return open >= 0 && player.getInventory().getItem(open) == pack && !PeakStatus.out(player);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = slots.get(index);
		if (slot == null || !slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY;
		ItemStack stack = slot.getItem(), was = stack.copy();
		boolean moved = index < SIZE ? moveItemStackTo(stack, SIZE, slots.size(), false) : moveItemStackTo(stack, 0, SIZE, false);
		if (!moved) return ItemStack.EMPTY;
		if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
		return was;
	}
}
