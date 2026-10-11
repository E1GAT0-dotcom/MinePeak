package gamemashup.minepeak;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;

/**
 * PEAK's items in Minecraft: every item of PEAK's (PeakItemList: 150 or so, all but its chess pieces and props), each a
 * MinePeak item of its own, one to a slot as in PEAK, named as PEAK names it and looking as PEAK draws it (its icon,
 * from the player's PEAK: PeakPack; a Minecraft item's look until then). What each does is PEAK's (PeakItemData,
 * PeakActions, PeakGear). On each stack: its uses or fuel left, and how cooked it is (custom data), its cook's tint
 * (custom model data's first colour).
 */
public final class PeakItems {
	private PeakItems() { }

	/** MinePeak's item for each of PEAK's (by PEAK's name on file), and back. */
	static final Map<String, PeakItem> BY_PEAK = new LinkedHashMap<>();
	static final Map<String, String> ENGLISH = new HashMap<>();
	/** The creative tab of PEAK's items. */
	static CreativeModeTab TAB;

	static void init() {
		for (String[] e : PeakItemList.ITEMS) {
			ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, MinePeak.id(e[1]));
			PeakItem item = new PeakItem(new Item.Properties().setId(key).stacksTo(1), e[0]);
			Registry.register(BuiltInRegistries.ITEM, key, item);
			BY_PEAK.put(e[0], item);
			ENGLISH.put(e[0], e[2]);
		}
		ResourceKey<CreativeModeTab> tab = ResourceKey.create(Registries.CREATIVE_MODE_TAB, MinePeak.id("peak"));
		TAB = Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, tab, FabricCreativeModeTab.builder()
			.title(Component.literal("PEAK"))
			.icon(() -> stack("RopeSpool"))
			.displayItems((params, out) -> { for (String n : BY_PEAK.keySet()) out.accept(stack(n)); })
			.build());
	}

	/** A stack of PEAK's item (by its name on file), as PEAK spawns it: its uses and fuel full. Empty if MinePeak has none. */
	static ItemStack stack(String peakName) {
		PeakItem item = BY_PEAK.get(peakName);
		if (item == null) return ItemStack.EMPTY;
		ItemStack s = new ItemStack(item);
		prepare(s);
		return s;
	}

	/** Sets a stack up from PEAK's item (once): its uses, its fuel, how it's used. */
	static void prepare(ItemStack s) {
		if (!(s.getItem() instanceof PeakItem item)) return;
		var def = PeakItemData.get(item.peak);
		CompoundTag t = tag(s);
		if (!t.contains("ready") && def != null) {
			if (def.totalUses() > 1) t.putInt("uses", def.totalUses());
			float fuel = startingFuel(def);
			if (fuel > 0) { t.putFloat("fuel", fuel); t.putFloat("fuelMax", fuel); }
			var cook = def.part("ItemCooking");
			if (cook != null && cook.integer("preCooked", 0) > 0) t.putInt("cooked", cook.integer("preCooked", 0));
			t.putBoolean("ready", true);
			save(s, t);
		}
		if (def != null && !s.has(DataComponents.CONSUMABLE) && castable(def) && def.useTime() > 0 && !PeakGear.handles(def)) {
			String anim = "";
			var fb = def.part("ItemUseFeedback");
			if (fb != null) anim = fb.str("useAnimation", "");
			boolean drink = anim.equalsIgnoreCase("Drink");
			s.set(DataComponents.CONSUMABLE, Consumable.builder().consumeSeconds(def.useTime())
				.animation(drink ? ItemUseAnimation.DRINK : anim.equalsIgnoreCase("Heal") ? ItemUseAnimation.BRUSH : ItemUseAnimation.EAT)
				.sound(drink ? SoundEvents.GENERIC_DRINK : SoundEvents.GENERIC_EAT).hasConsumeParticles(!anim.equalsIgnoreCase("Heal")).build());
		}
		if (def != null && def.has("Glider") && !s.has(DataComponents.GLIDER)) {
			s.set(DataComponents.GLIDER, net.minecraft.util.Unit.INSTANCE);
			s.set(DataComponents.EQUIPPABLE, net.minecraft.world.item.equipment.Equippable.builder(net.minecraft.world.entity.EquipmentSlot.MAINHAND).setSwappable(false).build());
		}
		tint(s);
	}

	/** Does the item do something when used (an action at the end of its use)? */
	static boolean castable(PeakItemData.Def def) {
		for (var p : def.parts()) if (p.type().startsWith("Action_") && !p.type().equals("Action_PlayAnimation") && (p.on("OnCastFinished") || p.on("OnConsumed"))) return true;
		return false;
	}

	static float startingFuel(PeakItemData.Def def) {
		var rope = def.part("RopeSpool");
		if (rope != null) return rope.num("ropeStartFuel", 60);
		var lantern = def.part("Lantern");
		if (lantern != null) return lantern.num("startingFuel", 120);
		var candle = def.part("Candle");
		if (candle != null) return candle.num("startingFuel", 120);
		var jet = def.part("JetpackItem");
		if (jet != null) return jet.num("startingFuel", 100);
		var shooter = def.part("RopeShooter");
		if (shooter != null) return shooter.num("startAmmo", 1);
		var magic = def.part("MagicBugle");
		if (magic != null) return magic.num("totalTootTime", 10);
		return 0;
	}

	// ---- a stack's own data ----

	static CompoundTag tag(ItemStack s) {
		CustomData d = s.get(DataComponents.CUSTOM_DATA);
		return d != null ? d.copyTag() : new CompoundTag();
	}

	static void save(ItemStack s, CompoundTag t) { s.set(DataComponents.CUSTOM_DATA, CustomData.of(t)); }

	static int cooked(ItemStack s) { return tag(s).getIntOr("cooked", 0); }

	static void cook(ItemStack s) {
		CompoundTag t = tag(s);
		t.putInt("cooked", Math.min(12, t.getIntOr("cooked", 0) + 1));
		save(s, t);
		tint(s);
	}

	/** The cook's tint on the item's look (custom model data's first colour: the item models tint by it). */
	static void tint(ItemStack s) {
		int c = cooked(s);
		if (c <= 0) { s.remove(DataComponents.CUSTOM_MODEL_DATA); return; }
		s.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(PeakItemData.cookColor(c) | 0xFF000000)));
	}

	/** PEAK's name on file of a stack's item, or null when it isn't one of PEAK's. */
	static String peak(ItemStack s) { return s.getItem() instanceof PeakItem p ? p.peak : null; }

	public static String peakName(ItemStack s) { return peak(s); }

	static PeakItemData.Def def(ItemStack s) { String n = peak(s); return n != null ? PeakItemData.get(n) : null; }

	/** What the player carries weighs (PEAK's carry weight; everything else 1 a stack), for PEAK's Weight status. */
	static int carryWeight(Player p) {
		int w = 0, extra = PeakAscents.extraWeight(p.level().getServer());
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.isEmpty()) continue;
			var def = def(s);
			w += (def != null ? Math.max(0, def.carryWeight()) : 1) + extra;
			var inside = s.get(DataComponents.CONTAINER);
			if (inside != null)
				for (ItemStack in : (Iterable<ItemStack>) inside.nonEmptyItemCopyStream()::iterator) { var d = def(in); w += (d != null ? Math.max(0, d.carryWeight()) : 1) + extra; }
		}
		return w;
	}

	/** The words PEAK puts on a cooked item (ItemCooking: COOKED, WELL-DONE, BURNT, INCINERATED). */
	static String cookedWord(int c) {
		return c <= 0 ? "" : c == 1 ? "COOKED " : c == 2 ? "WELL-DONE " : c == 3 ? "BURNT " : "INCINERATED ";
	}
}
