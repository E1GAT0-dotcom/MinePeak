package gamemashup.minepeak;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

/**
 * What comes out of PEAK's luggage (and grows on its bushes): PEAK's own loot pools (LootData, exported: each pool's
 * items and weights), drawn as PEAK draws them (by weight, no repeats), each item MinePeak's own of PEAK's (PeakItems).
 * (The Minecraft items below stand in for any PEAK item MinePeak doesn't have, from an export newer than MinePeak.)
 */
final class PeakLoot {
	private PeakLoot() { }

	private static ItemStack of(Item item, int count) { return new ItemStack(item, count); }

	private static ItemStack potion(Holder<Potion> p) {
		ItemStack s = new ItemStack(Items.POTION);
		s.set(DataComponents.POTION_CONTENTS, new PotionContents(p));
		return s;
	}

	/** PEAK's items (their names on file) as Minecraft's. */
	private static final Map<String, Supplier<ItemStack>> ITEMS = new HashMap<>();
	static {
		ITEMS.put("Airplane Food", () -> of(Items.COOKED_CHICKEN, 2));
		ITEMS.put("Bandages", () -> potion(Potions.HEALING));
		ITEMS.put("FirstAidKit", () -> potion(Potions.STRONG_HEALING));
		ITEMS.put("ChainShooter", () -> of(Items.SCAFFOLDING, 16));
		ITEMS.put("ClimbingSpike", () -> of(Items.LADDER, 6));
		ITEMS.put("RopeSpool", () -> of(Items.LADDER, 24));
		ITEMS.put("RopeShooter", () -> of(Items.LADDER, 32));
		ITEMS.put("Anti-Rope Spool", () -> of(Items.SCAFFOLDING, 24));
		ITEMS.put("RopeShooterAnti", () -> of(Items.SCAFFOLDING, 32));
		ITEMS.put("RescueHook", () -> of(Items.FISHING_ROD, 1));
		ITEMS.put("RescueHook_Infinite", () -> of(Items.FISHING_ROD, 1));
		ITEMS.put("Energy Drink", () -> potion(Potions.SWIFTNESS));
		ITEMS.put("Sports Drink", () -> potion(Potions.SWIFTNESS));
		ITEMS.put("Granola Bar", () -> of(Items.BREAD, 2));
		ITEMS.put("TrailMix", () -> of(Items.COOKIE, 4));
		ITEMS.put("ScoutCookies", () -> of(Items.COOKIE, 6));
		ITEMS.put("Lollipop", () -> of(Items.HONEY_BOTTLE, 1));
		ITEMS.put("Flare", () -> of(Items.FIREWORK_ROCKET, 2));
		ITEMS.put("Flag_Plantable_Checkpoint", () -> of(Items.BANNER.pick(net.minecraft.world.item.DyeColor.WHITE), 1));
		ITEMS.put("Pirate Compass", () -> of(Items.COMPASS, 1));
		ITEMS.put("HealingDart Variant", () -> of(Items.GLISTERING_MELON_SLICE, 3));
		ITEMS.put("Balloon", () -> potion(Potions.SLOW_FALLING));
		ITEMS.put("BalloonBunch", () -> potion(Potions.LONG_SLOW_FALLING));
		ITEMS.put("Parasol", () -> potion(Potions.SLOW_FALLING));
		ITEMS.put("Parasol_Roots Variant", () -> potion(Potions.SLOW_FALLING));
		ITEMS.put("ScoutCannonItem", () -> of(Items.WIND_CHARGE, 6));
		ITEMS.put("FortifiedMilk", () -> of(Items.MILK_BUCKET, 1));
		ITEMS.put("Fannypack", () -> of(Items.BUNDLE, 1));
		ITEMS.put("Jetpack", () -> of(Items.WIND_CHARGE, 12));
		ITEMS.put("Rocketpack", () -> of(Items.FIREWORK_ROCKET, 6));
		ITEMS.put("Glider", () -> of(Items.ELYTRA, 1));
		ITEMS.put("Antidote", () -> of(Items.HONEY_BOTTLE, 1));
		ITEMS.put("Lantern", () -> of(Items.LANTERN, 1));
		ITEMS.put("Lantern_Faerie", () -> of(Items.SOUL_LANTERN, 1));
		ITEMS.put("PortableStovetopItem", () -> of(Items.CAMPFIRE, 1));
		ITEMS.put("Heat Pack", () -> potion(Potions.FIRE_RESISTANCE));
		ITEMS.put("Sunscreen", () -> potion(Potions.FIRE_RESISTANCE));
		ITEMS.put("Dynamite", () -> of(Items.TNT, 1));
		ITEMS.put("Candle", () -> of(Items.CANDLE, 2));
		ITEMS.put("ScoutEffigy", () -> of(Items.TOTEM_OF_UNDYING, 1));
		ITEMS.put("AntiZooka", () -> of(Items.CROSSBOW, 1));
		ITEMS.put("Cure-All", () -> potion(Potions.STRONG_REGENERATION));
		ITEMS.put("Cursed Skull", () -> of(Items.WITHER_SKELETON_SKULL, 1));
		ITEMS.put("PandorasBox", () -> of(Items.SUSPICIOUS_STEW, 1));
		ITEMS.put("Bugle", () -> of(Items.GOAT_HORN, 1));
		ITEMS.put("Bugle_Magic", () -> of(Items.GOAT_HORN, 1));
		ITEMS.put("Bugle_Scoutmaster Variant", () -> of(Items.GOAT_HORN, 1));
		ITEMS.put("BookOfBones", () -> of(Items.BOOK, 1));
		ITEMS.put("RitualDagger", () -> of(Items.IRON_SWORD, 1));
		ITEMS.put("Berrynana Peel Yellow", () -> of(Items.SLIME_BALL, 1));
	}

	/** An item of PEAK's (by its name on file), named as PEAK names it; or nothing when MinePeak has none for it. */
	static ItemStack item(String file, String name) {
		ItemStack peak = PeakItems.stack(file);
		if (!peak.isEmpty()) return peak;   // (PEAK's own item: MinePeak has one for each)
		Supplier<ItemStack> s = ITEMS.get(file);
		if (s == null) return ItemStack.EMPTY;
		ItemStack stack = s.get();
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
		return stack;
	}

	record Entry(String file, String name, int weight) { }

	private static final Map<IslandData, Map<String, List<Entry>>> POOLS = new java.util.WeakHashMap<>();

	static synchronized List<Entry> pool(IslandData data, String pool) {
		return POOLS.computeIfAbsent(data, d -> {
			Map<String, List<Entry>> m = new HashMap<>();
			if (d.info.has("lootPools"))
				for (var e : d.info.getAsJsonObject("lootPools").entrySet()) {
					List<Entry> l = new ArrayList<>();
					for (JsonElement it : e.getValue().getAsJsonArray()) {
						JsonObject o = it.getAsJsonObject();
						l.add(new Entry(o.get("item").getAsString(), o.get("name").getAsString(), o.get("weight").getAsInt()));
					}
					m.put(e.getKey(), l);
				}
			return m;
		}).entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(pool)).map(Map.Entry::getValue).findFirst().orElse(List.of());
	}

	/** count things from the pool, by weight, no repeats (PEAK's LootData.GetRandomItems). */
	static List<ItemStack> draw(IslandData data, String pool, int count, RandomSource random) {
		List<Entry> left = new ArrayList<>(pool(data, pool));
		List<ItemStack> out = new ArrayList<>();
		while (out.size() < count && !left.isEmpty()) {
			int total = 0;
			for (Entry e : left) total += e.weight();
			int r = random.nextInt(Math.max(1, total));
			Entry pick = left.get(left.size() - 1);
			for (Entry e : left) { r -= e.weight(); if (r < 0) { pick = e; break; } }
			left.remove(pick);
			ItemStack s = item(pick.file(), pick.name());
			if (!s.isEmpty()) out.add(s);
		}
		return out;
	}

	/** The pool for a suitcase with none of its own (PEAK picks by where it is): its stage's luggage. */
	static String poolFor(String stage) {
		return switch (stage) {
			case "shore" -> "LuggageBeach"; case "tropics" -> "LuggageJungle"; case "roots" -> "LuggageRoots"; case "alpine" -> "LuggageTundra";
			case "mesa" -> "LuggageMesa"; default -> "LuggageCaldera";
		};
	}
}
