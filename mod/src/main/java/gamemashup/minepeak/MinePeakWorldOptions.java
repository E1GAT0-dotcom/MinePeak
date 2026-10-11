package gamemashup.minepeak;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.network.chat.Component;

/**
 * MinePeak's settings in Create World's World tab, shown when the world type is MinePeak Island, in place of the seed
 * and the structures and bonus chest switches (which don't apply to it): where everyone starts (the bottom, by default,
 * as in PEAK), which island (today's, or an older one exported before), and the island's edges. They're kept in the
 * world's generator settings (IslandChunkGenerator), as Superflat keeps its own.
 */
public final class MinePeakWorldOptions {
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);

	private MinePeakWorldOptions() { }

	/** Called as the World tab is made (CreateWorldTabMixin). Its world type button and customize (its Customize button) stay. */
	public static void add(CreateWorldScreen screen, GridLayout layout, AbstractWidget customize) {
		WorldCreationUiState ui = screen.getUiState();
		List<AbstractWidget> vanilla = new ArrayList<>();
		layout.visitWidgets(w -> {
			if (w != customize && !(w instanceof CycleButton<?> c && c.getValue() instanceof WorldCreationUiState.WorldTypeEntry)) vanilla.add(w);
		});
		JsonObject latest = latestInfo();

		List<String> starts = new ArrayList<>(List.of("bottom"));
		int fires = latest != null ? campfireNames(latest).size() : 3;
		for (int i = 1; i <= Math.max(1, fires); i++) starts.add("campfire" + i);
		CycleButton<String> start = CycleButton.builder((String v) -> Component.literal(startName(v, latest)), "bottom")
			.withValues(starts)
			.withTooltip(v -> Tooltip.create(Component.literal("Where everyone starts. PEAK starts at the bottom, on the beach.")))
			.create(0, 0, 310, 20, Component.literal("Start"), (b, v) -> change(ui, g -> g.with(g.island(), v, g.edges())));

		List<String> islands = new ArrayList<>(List.of("latest"));
		islands.addAll(olderIslands());
		CycleButton<String> island = CycleButton.builder((String v) -> Component.literal(islandName(v)), "latest")
			.withValues(islands)
			.withTooltip(v -> Tooltip.create(Component.literal("PEAK's island changes every day. Today's is exported from your PEAK; the ones from"
				+ " days before stay on this PC (" + IslandData.ROOT + ").")))
			.create(0, 0, 310, 20, Component.literal("Island"), (b, v) -> change(ui, g -> g.with(v, g.start(), g.edges())));

		CycleButton<String> edges = CycleButton.builder((String v) -> Component.literal("open".equals(v) ? "Open (swim away if you like)" : "Invisible wall on the island's edge"), "barrier")
			.withValues(List.of("barrier", "open"))
			.withTooltip(v -> Tooltip.create(Component.literal("open".equals(v)
				? "Nothing stops you at the island's sides: the sea goes on for ever all round it."
				: "As in PEAK, you can't leave the island: an invisible wall stands on its edge. The sea goes on for ever past it.")))
			.create(0, 0, 310, 20, Component.literal("Edges"), (b, v) -> change(ui, g -> g.with(g.island(), g.start(), v)));

		List<Integer> ascents = new ArrayList<>();
		for (int a = -1; a <= 8; a++) ascents.add(a);
		CycleButton<Integer> ascent = CycleButton.builder((Integer v) -> Component.literal(PeakAscents.title(v)), 0)
			.withValues(ascents)
			.withTooltip(v -> Tooltip.create(Component.literal(PeakAscents.describe(v))))
			.create(0, 0, 310, 20, Component.literal("Ascent"), (b, v) -> change(ui, g -> g.withAscent(v)));

		// in the seed's row and the switches' row (one shows at a time)
		LinearLayout two = LinearLayout.vertical().spacing(4);
		two.addChild(start);
		two.addChild(island);
		layout.addChild(two, 1, 0, 1, 2);
		LinearLayout three = LinearLayout.vertical().spacing(4);
		three.addChild(edges);
		three.addChild(ascent);
		layout.addChild(three, 2, 0, 1, 2);
		List<AbstractWidget> ours = List.of(start, island, edges, ascent);

		java.util.function.Consumer<WorldCreationUiState> refresh = state -> {
			var gen = state.getSettings().selectedDimensions().overworld();
			boolean mine = gen instanceof IslandChunkGenerator;
			for (AbstractWidget w : vanilla) w.visible = !mine;
			for (AbstractWidget w : ours) w.visible = mine;
			if (gen instanceof IslandChunkGenerator g) {
				start.setValue(starts.contains(g.start()) ? g.start() : "bottom");
				island.setValue(islands.contains(g.island()) ? g.island() : "latest");
				edges.setValue("open".equals(g.edges()) ? "open" : "barrier");
				ascent.setValue(Math.max(-1, Math.min(8, g.ascent())));
			}
		};
		ui.addListener(refresh);
		refresh.accept(ui);
	}

	private static void change(WorldCreationUiState ui, UnaryOperator<IslandChunkGenerator> how) {
		ui.updateDimensions((registries, dims) -> dims.overworld() instanceof IslandChunkGenerator g ? dims.replaceOverworldGenerator(registries, how.apply(g)) : dims);
	}

	static String startName(String v, JsonObject latest) {
		if ("bottom".equals(v)) return "The bottom (the beach)";
		int n = Integer.parseInt(v.substring("campfire".length()));
		List<String> names = latest != null ? campfireNames(latest) : List.of();
		return "Campfire " + n + (n <= names.size() ? ", after the " + names.get(n - 1) : "");
	}

	static String islandName(String v) {
		if ("latest".equals(v)) {
			LocalDate day = LocalDate.parse(PeakExporter.peakDay());
			return "Today's (" + day.format(DAY) + (PeakExporter.todayExported() ? ")" : ", from PEAK first)");
		}
		// 2026-10-06_Level_17
		try {
			return LocalDate.parse(v.substring(0, 10)).format(DAY) + " (" + v.substring(11).replace('_', ' ') + ")";
		} catch (Exception e) {
			return v;
		}
	}

	/** The exported islands of days before today, newest first (folder names). */
	static List<String> olderIslands() {
		String today = PeakExporter.peakDay();
		List<String> out = new ArrayList<>();
		if (!Files.isDirectory(IslandData.ROOT)) return out;
		try (Stream<Path> s = Files.list(IslandData.ROOT)) {
			s.map(p -> p.getFileName().toString())
				.filter(n -> n.matches("\\d{4}-\\d{2}-\\d{2}_.+") && !n.startsWith(today) && Files.isRegularFile(IslandData.ROOT.resolve(n).resolve("island.json")))
				.sorted(java.util.Comparator.reverseOrder()).forEach(out::add);
		} catch (Exception e) {
			MinePeak.LOG.warn("Couldn't list {}", IslandData.ROOT, e);
		}
		return out;
	}

	/** The newest export's island.json (just its info: no blocks), or null. */
	static JsonObject latestInfo() {
		try {
			Path dir = IslandData.latest();
			return dir != null ? JsonParser.parseString(Files.readString(dir.resolve("island.json"))).getAsJsonObject() : null;
		} catch (Exception e) {
			return null;
		}
	}

	/** The biome each campfire is at the top of (as IslandData.campfires counts them). */
	static List<String> campfireNames(JsonObject info) {
		List<String> names = new ArrayList<>();
		for (var e : info.getAsJsonArray("areas")) {
			JsonObject a = e.getAsJsonObject();
			if (!a.has("campfire")) continue;
			var c = a.getAsJsonArray("campfire");
			int x = c.get(0).getAsInt(), z = c.get(2).getAsInt(), x0 = a.get("x0").getAsInt(), z0 = a.get("z0").getAsInt();
			if (x >= x0 && z >= z0 && x < x0 + a.get("width").getAsInt() && z < z0 + a.get("depth").getAsInt())
				names.add(a.has("biome") ? a.get("biome").getAsString() : a.get("segment").getAsString());
		}
		return names;
	}
}
