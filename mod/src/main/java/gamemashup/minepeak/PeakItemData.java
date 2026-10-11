package gamemashup.minepeak;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What PEAK's items do, as the MinePeak Exporter read them from the player's own PEAK (%APPDATA%/MinePeak/items/items.json):
 * each item's uses, how long it takes to use, its weight, and its parts with their settings (PEAK's Action_RestoreHunger
 * with its restorationAmount, Action_ModifyStatus with its status and amount, ItemCooking...). Also PEAK's statuses' own
 * numbers (how fast each wears off). Read again when the file changes (a new export).
 */
final class PeakItemData {
	private PeakItemData() { }

	static final Path DIR = IslandData.ROOT.getParent().resolve("items");

	/** A part of an item (PEAK's component): its type and settings. */
	record Part(String type, JsonObject f) {
		boolean on(String event) { return f.has(event) && f.get(event).getAsBoolean(); }
		float num(String k, float def) { return f.has(k) && f.get(k).isJsonPrimitive() ? f.get(k).getAsFloat() : def; }
		int integer(String k, int def) { return f.has(k) && f.get(k).isJsonPrimitive() ? f.get(k).getAsInt() : def; }
		String str(String k, String def) { return f.has(k) && f.get(k).isJsonPrimitive() ? f.get(k).getAsString() : def; }
		boolean bool(String k, boolean def) { return f.has(k) && f.get(k).isJsonPrimitive() ? f.get(k).getAsBoolean() : def; }
		JsonObject obj(String k) { return f.has(k) && f.get(k).isJsonObject() ? f.getAsJsonObject(k) : null; }
		/** A reference to another of PEAK's objects (saved as "@name"), without the "@". */
		String ref(String k) { String s = str(k, ""); return s.startsWith("@") ? s.substring(1) : null; }
	}

	/** An item: PEAK's name on file, its name in the player's language, its icon's file, and what it is. */
	record Def(String item, String name, String icon, int totalUses, float useTime, int carryWeight, String tags, boolean canUseOnFriend,
			JsonObject ui, List<Part> parts) {
		boolean has(String type) { for (Part p : parts) if (p.type().equals(type)) return true; return false; }
		Part part(String type) { for (Part p : parts) if (p.type().equals(type)) return p; return null; }
		List<Part> all(String type) { List<Part> l = new ArrayList<>(); for (Part p : parts) if (p.type().equals(type)) l.add(p); return l; }
		boolean tagged(String tag) { return tags != null && tags.contains(tag); }
		boolean consumable() { return has("Action_Consume") || has("Action_ConsumeAndSpawn") || has("Action_ReduceUses"); }
		boolean edible() { return has("Action_RestoreHunger") || tagged("Berry") || tagged("Mushroom") || tagged("PackagedFood"); }
	}

	private static volatile Map<String, Def> defs = Map.of();
	private static volatile Map<String, List<String[]>> texts = Map.of();
	private static volatile JsonObject afflictions = new JsonObject(), bees = new JsonObject();
	private static volatile Map<String, String> secretly = Map.of();
	private static volatile List<Integer> cookColors = List.of();
	private static long stamp = -1;
	private static volatile long nextCheck;

	/**
	 * Up to date with the file (looked at every 2 s at most: it's used by every look at an item, from the server, the
	 * HUD and the world's generation, many times a tick; checking the file each time cost milliseconds a tick).
	 */
	private static void load() {
		if (System.currentTimeMillis() >= nextCheck) reload();
	}

	private static synchronized void reload() {
		long now = System.currentTimeMillis();
		if (now < nextCheck) return;
		nextCheck = now + 2000;
		Path f = DIR.resolve("items.json");
		long s;
		try { s = Files.isRegularFile(f) ? Files.getLastModifiedTime(f).toMillis() : 0; } catch (Exception e) { s = 0; }
		if (s == stamp) return;
		stamp = s;
		Map<String, Def> m = new HashMap<>();
		Map<String, List<String[]>> tx = new HashMap<>();
		Map<String, String> sec = new HashMap<>();
		if (s != 0) try {
			JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
			for (JsonElement e : root.getAsJsonArray("items")) {
				JsonObject o = e.getAsJsonObject();
				List<Part> parts = new ArrayList<>();
				for (JsonElement pe : o.getAsJsonArray("parts")) {
					JsonObject p = pe.getAsJsonObject();
					parts.add(new Part(p.get("type").getAsString(), p.has("fields") ? p.getAsJsonObject("fields") : new JsonObject()));
				}
				String item = o.get("item").getAsString();
				if (o.has("secretly")) sec.put(item, o.get("secretly").getAsString());
				if (o.has("texts")) {
					List<String[]> l = new ArrayList<>();
					for (JsonElement te : o.getAsJsonArray("texts")) l.add(new String[] { te.getAsJsonObject().get("path").getAsString(), te.getAsJsonObject().get("text").getAsString() });
					tx.put(item, l);
				}
				m.put(item, new Def(item, o.has("name") ? o.get("name").getAsString() : item, o.has("icon") ? o.get("icon").getAsString() : null,
					o.has("totalUses") ? o.get("totalUses").getAsInt() : -1, o.has("usingTimePrimary") ? o.get("usingTimePrimary").getAsFloat() : 0,
					o.has("carryWeight") ? o.get("carryWeight").getAsInt() : 1, o.has("tags") ? o.get("tags").getAsString() : "",
					o.has("canUseOnFriend") && o.get("canUseOnFriend").getAsBoolean(), o.has("ui") ? o.getAsJsonObject("ui") : new JsonObject(), parts));
			}
			afflictions = root.has("afflictions") ? root.getAsJsonObject("afflictions") : new JsonObject();
			bees = root.has("bees") ? root.getAsJsonObject("bees") : new JsonObject();
			List<Integer> cc = new ArrayList<>();
			if (root.has("cookColors")) for (JsonElement c : root.getAsJsonArray("cookColors")) cc.add(Integer.parseInt(c.getAsString().substring(1, 7), 16));
			cookColors = cc;
			MinePeak.LOG.info("MinePeak: {} of PEAK's items from {}", m.size(), f);
		} catch (Exception e) {
			MinePeak.LOG.warn("MinePeak: couldn't read PEAK's items ({})", f, e);
		}
		defs = m;
		texts = tx;
		secretly = sec;
	}

	/**
	 * What an item really is when it's picked up (PEAK's isSecretlyOtherItemPrefab: some of the medicinal roots are
	 * mandrakes), or null. Before an export that says so, PEAK's one: Mandrake_Hidden is a mandrake.
	 */
	static String secretly(String item) {
		load();
		String s = secretly.get(item);
		return s != null ? s : "Mandrake_Hidden".equals(item) ? "Mandrake" : null;
	}

	/** PEAK's bee swarm's numbers (BeeSwarm, its sting's StatusField as "stinger"); empty before an export has them. */
	static JsonObject bees() { load(); return bees; }

	/** The words on an item (a guidebook's pages: each its place in the item and its text). */
	static List<String[]> texts(String item) { load(); return texts.getOrDefault(item, List.of()); }

	/** The item named so on file in PEAK, or null (not exported yet). */
	static Def get(String item) { load(); return defs.get(item); }

	static boolean any() { load(); return !defs.isEmpty(); }

	/** PEAK's statuses' numbers (CharacterAfflictions): e.g. "poisonReductionPerSecond"; def when not exported. */
	static float affliction(String key, float def) {
		load();
		return afflictions.has(key) && afflictions.get(key).isJsonPrimitive() ? afflictions.get(key).getAsFloat() : def;
	}

	/** The tint of a cooked item (PEAK's ItemCooking.GetCookColor), as RGB. */
	static int cookColor(int cooked) {
		load();
		if (cooked <= 0) return 0xFFFFFF;
		if (cooked < cookColors.size()) return cookColors.get(cooked);
		return cooked == 1 ? 0xA87840 : cooked == 2 ? 0x543C20 : 0x0D0D1A;
	}

	static JsonArray array(JsonObject o, String k) { return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray(); }
}
