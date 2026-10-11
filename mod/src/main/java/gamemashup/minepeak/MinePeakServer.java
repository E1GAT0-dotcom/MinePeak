package gamemashup.minepeak;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The server side of a MinePeak world:
 * - before it loads: a new world waits for today's PEAK island (exported in the background if it isn't yet) and is tied
 *   to it (minepeak.json in the world folder), so its land never changes; a world made before keeps its own island, and
 *   one made with an older island picked in Create World takes that one;
 * - on its first start, no patrols, wandering traders or phantoms (MinePeak's biome spawns only fish), and everyone's
 *   spawn is the start picked in Create World: the bottom (the beach, as in PEAK) or a campfire; a player's first join, and a respawn with no bed of their own, put them there exactly
 *   (Minecraft's own spawn finder would scatter them over the mountain);
 * - anyone still in the airport of a world made before it was dropped is brought to the start;
 * - the island's edge (its invisible wall) can't be dug through, except in creative;
 * - a player joining a MinePeak world with no island is told why, in chat;
 * - /minepeak start | beach | campfire <n>: to the start, the plane wreck, or a campfire; /minepeak title: the title card of
 *   the stage you're in (cheats on).
 */
public final class MinePeakServer {
	/** The airport dimension of worlds made before the airport was dropped (only to bring people out of it). */
	public static final ResourceKey<Level> AIRPORT = ResourceKey.create(Registries.DIMENSION, MinePeak.id("airport"));
	private static volatile String worldProblem;

	private MinePeakServer() { }

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTING.register(MinePeakServer::starting);
		ServerLifecycleEvents.SERVER_STARTED.register(MinePeakServer::started);
		// the world stopping: everything kept for it goes (another world may be opened without restarting Minecraft,
		// and the server's tick count starts again at 0)
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			PeakFog.stop(server); PeakStatus.stop(server); PeakHazards.stop(server);
			PeakGear.stop(server); PeakEnding.stop(server); PeakScoutmaster.stop(server);
			PeakClimb.stop(); PeakBadges.stop(); PeakCreatures.stop(); PeakBees.stop(); PeakLoops.stop(); PeakActions.stop(); PeakCooking.stop();
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> { PeakClimb.left(handler.player.getUUID()); PeakGear.left(handler.player); });
		// PEAK's day (PeakTime), and water as PEAK's: landing in it never hurts
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
			IslandData data = island(server);
			if (data == null) return;
			PeakTime.tick(server, data);
			PeakCreatures.tick(server);
			PeakFog.tick(server, data);
			PeakStatus.tick(server);
			PeakInventory.tick(server);
			PeakActions.tick(server);
			PeakGear.tick(server);
			PeakHazards.tick(server);
			PeakEnding.tick(server);
			PeakScoutmaster.tick(server);
			PeakBadges.tick(server);
			PeakCooking.tick(server);
			PeakBees.tick(server);
			PeakLoops.tick(server);
			for (var p : server.getPlayerList().getPlayers()) if (p.isInWater()) p.resetFallDistance();
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (handler.player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) == 0 || handler.player.level().dimension() == AIRPORT)
				toStart(server, handler.player);
			String problem = worldProblem;
			if (problem != null) handler.player.sendSystemMessage(Component.literal("MinePeak: " + problem).withStyle(ChatFormatting.RED));
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(
			Commands.literal("minepeak").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("start").executes(c -> go(c, "start", 0)))
				.then(Commands.literal("beach").executes(c -> go(c, "beach", 0)))
				.then(Commands.literal("title").executes(c -> HeroTitle.show(c.getSource().getPlayerOrException()) ? 1 : 0))
				.then(Commands.literal("campfire").executes(c -> go(c, "campfire", 0))
					.then(Commands.argument("number", IntegerArgumentType.integer(1, 9))
						.executes(c -> go(c, "campfire", IntegerArgumentType.getInteger(c, "number")))))));
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
			if (taken > 0 && source.getEntity() instanceof net.minecraft.world.entity.LivingEntity by) PeakCreatures.bitten(entity, by);
		});
		// PEAK's suitcases pop open, its statues bring scouts back, its campfires cook what's held to them
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (hand != net.minecraft.world.InteractionHand.MAIN_HAND) return net.minecraft.world.InteractionResult.PASS;
			var pos = hit.getBlockPos();
			var s = level.getBlockState(pos);
			if (s.is(net.minecraft.world.level.block.Blocks.CHEST) || PeakStatue.isStatue(s)) {
				if (!(level instanceof ServerLevel sl) || !(player instanceof net.minecraft.server.level.ServerPlayer sp)) return PeakWorld.is(level) ? net.minecraft.world.InteractionResult.SUCCESS : net.minecraft.world.InteractionResult.PASS;
				boolean done = s.is(net.minecraft.world.level.block.Blocks.CHEST) ? PeakStatue.open(sl, pos, sp) : PeakStatue.use(sl, pos, sp);
				return done ? net.minecraft.world.InteractionResult.SUCCESS : net.minecraft.world.InteractionResult.PASS;
			}
			// PEAK's beehive is a thing: a hand on the nest takes it (and its bees come after you)
			if (s.is(net.minecraft.world.level.block.Blocks.BEE_NEST) && PeakWorld.scout(player)) {
				if (player instanceof net.minecraft.server.level.ServerPlayer sp) PeakBees.take(sp, pos);
				return net.minecraft.world.InteractionResult.SUCCESS;
			}
			if (s.is(net.minecraft.world.level.block.Blocks.CAMPFIRE) && s.getValue(net.minecraft.world.level.block.CampfireBlock.LIT) && PeakItems.peak(player.getItemInHand(hand)) != null) {
				if (player instanceof net.minecraft.server.level.ServerPlayer sp) PeakCooking.hold(sp, pos);
				return net.minecraft.world.InteractionResult.SUCCESS;
			}
			return net.minecraft.world.InteractionResult.PASS;
		});
		// PEAK's campfires are lit by a hand on them (no flint and steel in PEAK)
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			var s = level.getBlockState(hit.getBlockPos());
			if (hand != net.minecraft.world.InteractionHand.MAIN_HAND || !s.is(net.minecraft.world.level.block.Blocks.CAMPFIRE)
				|| s.getValue(net.minecraft.world.level.block.CampfireBlock.LIT) || !player.getItemInHand(hand).isEmpty()) return net.minecraft.world.InteractionResult.PASS;
			if (!level.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer sp && PeakFog.light(sp.level().getServer(), hit.getBlockPos(), sp))
				level.setBlock(hit.getBlockPos(), s.setValue(net.minecraft.world.level.block.CampfireBlock.LIT, true), 11);
			return net.minecraft.world.InteractionResult.SUCCESS;
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((old, player, alive) -> {
			if (!alive && player.getRespawnConfig() == null) toStart(player.level().getServer(), player);
		});
		// scouts don't break PEAK's island (nor get its blocks); creative does
		net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) ->
			PeakWorld.scout(player) ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS);
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			if (PeakWorld.scout(player)) return false;
			if (player.isCreative() || !(level instanceof ServerLevel sl) || sl.dimension() != Level.OVERWORLD) return true;
			if (!(sl.getChunkSource().getGenerator() instanceof IslandChunkGenerator g) || !g.walls()) return true;
			IslandData data = g.data();
			if (data == null || !data.edge(pos.getX(), pos.getZ()) && data.wall(pos.getX(), pos.getZ()) == null) return true;
			player.sendOverlayMessage(Component.literal("The island ends here."));
			return false;
		});
	}

	static IslandChunkGenerator generator(MinecraftServer server) {
		var stem = server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM).getValue(LevelStem.OVERWORLD);
		return stem != null && stem.generator() instanceof IslandChunkGenerator g ? g : null;
	}

	static Path marker(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("minepeak.json"); }

	static void starting(MinecraftServer server) {
		worldProblem = null;
		IslandChunkGenerator.pinned = null;
		IslandChunkGenerator g = generator(server);
		if (g == null) return;
		Path marker = marker(server);
		try {
			if (Files.exists(marker)) {
				JsonObject o = JsonParser.parseString(Files.readString(marker)).getAsJsonObject();
				if (o.has("island")) {
					IslandChunkGenerator.pinned = o.get("island").getAsString();
					if (IslandData.get(IslandChunkGenerator.pinned, "island") == null)
						worldProblem = "this world was made from the PEAK island " + IslandChunkGenerator.pinned + ", which isn't in " + IslandData.ROOT + " any more.";
					return;
				}
			}
		} catch (Exception e) {
			MinePeak.LOG.warn("Couldn't read {}", marker, e);
		}
		// a new world made with an older island (picked in Create World): that one
		if (!"latest".equals(g.island())) {
			IslandChunkGenerator.pinned = g.island();
			if (IslandData.get(g.island(), "island") == null)
				worldProblem = "this world was made from the PEAK island " + g.island() + ", which isn't in " + IslandData.ROOT + ".";
			return;
		}
		// a new world: today's island. In single player MinePeak's waiting screen has seen to it before the world
		// started loading; a dedicated server exports it now, if it isn't yet, and waits
		if (server.isDedicatedServer() && !PeakExporter.todayExported() && PeakExporter.startIfNeeded("a new MinePeak world")) {
			MinePeak.LOG.info("Waiting for today's PEAK island...");
			PeakExporter.waitUntilDone(20 * 60_000L, s -> MinePeak.LOG.info("  {} ({}%)", s.message(), s.percent()));
		}
		try {
			Path latest = IslandData.latest();
			if (latest != null) IslandChunkGenerator.pinned = latest.getFileName().toString();
		} catch (Exception ignored) { }
		if (IslandChunkGenerator.pinned == null || IslandData.get(IslandChunkGenerator.pinned, "island") == null) {
			String why = PeakExporter.lastError();
			worldProblem = "there's no PEAK island to build this world from" + (why != null ? ": " + why : ".")
				+ " Only the sea was made; fix that, then make a new MinePeak world.";
		} else if (!PeakExporter.todayExported() && PeakExporter.lastError() != null) {
			worldProblem = "today's island couldn't be exported (" + PeakExporter.lastError() + "), so this world uses " + IslandChunkGenerator.pinned + ".";
		}
	}

	/** The island of the world (its overworld's generator), or null when it isn't a MinePeak world or has none. */
	public static IslandData island(MinecraftServer server) {
		return server.overworld().getChunkSource().getGenerator() instanceof IslandChunkGenerator g ? g.data() : null;
	}

	static void started(MinecraftServer server) {
		IslandData data = island(server);
		if (data == null) return;
		PeakTime.reset();
		Path marker = marker(server);
		if (Files.exists(marker)) return;
		// first start: none of Minecraft's wandering visitors (PEAK's island has only its own creatures; the biome
		// spawns only fish), and the spawn is the start picked in Create World (the bottom, as PEAK starts on the beach)
		var rules = server.getGameRules();
		rules.set(GameRules.SPAWN_PATROLS, false, server);
		rules.set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
		rules.set(GameRules.SPAWN_PHANTOMS, false, server);
		PeakTime.start(server, data);   // (PEAK's first morning: 9 AM, day one)
		Start s = start(server);
		if (s != null) {
			server.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, s.pos(), s.yaw(), 0f));
			MinePeak.LOG.info("MinePeak world: spawn set to {} ({})", s.pos(), s.what());
		}
		try {
			JsonObject o = new JsonObject();
			o.addProperty("island", data.name);
			Files.writeString(marker, o.toString());
		} catch (Exception e) {
			MinePeak.LOG.warn("Couldn't write {}", marker, e);
		}
	}

	/** Where everyone starts: its feet, the way it faces (up the mountain), and what it is. */
	record Start(BlockPos pos, float yaw, String what) { }

	/** The world's start (its generator's "start": "bottom" or "campfire1".."campfire4"; the bottom if that campfire isn't there). */
	static Start start(MinecraftServer server) {
		IslandChunkGenerator g = generator(server);
		IslandData data = island(server);
		if (g == null || data == null) return null;
		if (g.start().startsWith("campfire")) {
			try {
				Start c = campfire(server, data, Integer.parseInt(g.start().substring("campfire".length())));
				if (c != null) return c;
			} catch (NumberFormatException ignored) { }
		}
		BlockPos p = beach(data);
		return p != null ? new Start(p, 180f, "the beach") : null;
	}

	/** By campfire n (from 1), on the downhill side of it, facing up the mountain. */
	static Start campfire(MinecraftServer server, IslandData data, int n) {
		List<JsonObject> fires = data.campfires();
		if (n < 1 || n > fires.size()) return null;
		JsonObject a = fires.get(n - 1);
		BlockPos c = pos(a.getAsJsonArray("campfire"));
		return new Start(standable(server.overworld(), c.offset(0, 0, 4)), 180f, "the " + a.get("segment").getAsString() + " campfire");
	}

	/** Somewhere to stand at or near p (ground under the feet, room for the head), or p itself. */
	static BlockPos standable(ServerLevel level, BlockPos p) {
		for (int dy = 4; dy >= -8; dy--) {
			BlockPos f = p.above(dy);
			if (level.getBlockState(f.below()).blocksMotion() && !level.getBlockState(f).blocksMotion() && !level.getBlockState(f.above()).blocksMotion())
				return f;
		}
		return p;
	}

	/** To the world's start, exactly. */
	static void toStart(MinecraftServer server, ServerPlayer player) {
		Start s = start(server);
		if (s == null) return;
		player.teleportTo(server.overworld(), s.pos().getX() + 0.5, s.pos().getY(), s.pos().getZ() + 0.5, Set.of(), s.yaw(), 0f, true);
	}

	/** /minepeak start | beach | campfire [n] */
	private static int go(CommandContext<CommandSourceStack> c, String where, int n) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		var src = c.getSource();
		ServerPlayer player = src.getPlayerOrException();
		var server = src.getServer();
		IslandData data = island(server);
		if (data == null) { src.sendFailure(Component.literal("This isn't a MinePeak world with a PEAK island.")); return 0; }
		Start s;
		switch (where) {
			case "start" -> s = start(server);
			case "beach" -> {
				BlockPos p = beach(data);
				s = p != null ? new Start(p, 180f, "the beach") : null;
			}
			default -> {
				s = campfire(server, data, n);
				if (s == null) {
					List<JsonObject> fires = data.campfires();
					var list = new StringBuilder("Campfires:");
					for (int i = 0; i < fires.size(); i++) list.append(" ").append(i + 1).append(" ").append(fires.get(i).get("segment").getAsString()).append(i + 1 < fires.size() ? "," : "");
					src.sendFailure(Component.literal(list + " (/minepeak campfire <number>)"));
					return 0;
				}
			}
		}
		if (s == null) { src.sendFailure(Component.literal("This island has no beach start.")); return 0; }
		player.teleportTo(server.overworld(), s.pos().getX() + 0.5, s.pos().getY(), s.pos().getZ() + 0.5, Set.of(), s.yaw(), 0f, true);
		String said = s.what();
		src.sendSuccess(() -> Component.literal("To " + said), false);
		return 1;
	}

	/** Where the scouts start on the island: the plane wreck on the beach. */
	public static BlockPos beach(IslandData data) {
		for (var e : data.info.getAsJsonArray("areas")) {
			JsonObject a = e.getAsJsonObject();
			if (a.has("spawns") && a.getAsJsonArray("spawns").size() > 0) return pos(a.getAsJsonArray("spawns").get(0).getAsJsonArray());
		}
		return null;
	}

	static BlockPos pos(JsonArray a) {
		if (a == null || a.size() < 3) return null;
		return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt() + 1, a.get(2).getAsInt());
	}
}
