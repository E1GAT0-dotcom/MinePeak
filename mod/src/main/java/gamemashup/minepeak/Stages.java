package gamemashup.minepeak;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * PEAK's stages on an island, from the bottom of the mountain up (Minecraft z going down): each one's name and where it
 * starts. PEAK names a stage at its progress point, which is well into it (ROOTS 100 blocks past the beach's campfire,
 * THE KILN at the far end of the tunnel); MinePeak starts each at the campfire before its point, where the stage begins
 * (the title card and the stage's air change there), or at the point itself when no campfire comes before it (the first
 * stage, PEAK).
 */
final class Stages {
	private Stages() { }

	/** A stage: where it starts (Minecraft z), its title (PEAK's, in its words), and PEAK's names for it. */
	record Stage(int z, String text, String title, String biome) { }

	private static final Map<IslandData, List<Stage>> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

	static List<Stage> of(IslandData data) {
		return CACHE.computeIfAbsent(data, Stages::read);
	}

	private static List<Stage> read(IslandData d) {
		List<Stage> points = new ArrayList<>();
		if (d.info.has("progress"))
			for (JsonElement e : d.info.getAsJsonArray("progress")) {
				JsonObject p = e.getAsJsonObject();
				if (p.has("present") && !p.get("present").getAsBoolean()) continue;
				String title = p.has("title") ? p.get("title").getAsString() : "";
				String text = p.has("text") && !p.get("text").getAsString().startsWith("LOC:") ? p.get("text").getAsString() : title;
				points.add(new Stage(p.getAsJsonArray("at").get(2).getAsInt(), text.toUpperCase(Locale.ROOT), title, p.has("biome") ? p.get("biome").getAsString() : ""));
			}
		points.sort((a, b) -> Integer.compare(b.z(), a.z()));
		List<Integer> fires = new ArrayList<>();
		if (d.info.has("areas"))
			for (JsonElement e : d.info.getAsJsonArray("areas")) {
				JsonObject a = e.getAsJsonObject();
				if (a.has("campfire") && a.get("campfire").isJsonArray()) fires.add(a.getAsJsonArray("campfire").get(2).getAsInt());
			}
		List<Stage> stages = new ArrayList<>();
		for (int i = 0; i < points.size(); i++) {
			Stage p = points.get(i);
			int start = p.z();
			if (i > 0) {
				int before = points.get(i - 1).z();
				for (int f : fires) if (f < before && f > p.z()) start = Math.max(start, f);
			}
			stages.add(new Stage(start, p.text(), p.title(), p.biome()));
		}
		return List.copyOf(stages);
	}
}
