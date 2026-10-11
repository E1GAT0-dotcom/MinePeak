package gamemashup.minepeak;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

/**
 * PEAK's campfire cooking: hold what's in hand to a lit campfire (keep using it on the fire) for PEAK's cook time (2 s)
 * and it's cooked one level more (PeakItems.cook: food is worth more, then less, then poisons; PeakActions). Some things
 * don't take it: medicine and heat packs go off in PEAK's little explosion; some just break.
 */
final class PeakCooking {
	private PeakCooking() { }

	private record Holding(BlockPos at, long since, long last) { }
	private static final Map<UUID, Holding> HOLDING = new HashMap<>();

	static void stop() { HOLDING.clear(); }

	static void hold(ServerPlayer p, BlockPos fire) {
		long now = p.level().getServer().getTickCount();
		Holding h = HOLDING.get(p.getUUID());
		if (h == null || !h.at.equals(fire) || now - h.last > 8) h = new Holding(fire, now, now);
		else h = new Holding(fire, h.since, now);
		HOLDING.put(p.getUUID(), h);
	}

	static void tick(MinecraftServer server) {
		long now = server.getTickCount();
		for (var it = HOLDING.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			Holding h = e.getValue();
			if (p == null || now - h.last > 8) { it.remove(); continue; }
			var stack = p.getMainHandItem();
			var def = PeakItems.def(stack);
			if (def == null) { it.remove(); continue; }
			var cook = def.part("ItemCooking");
			if (cook == null || cook.bool("disableCooking", false)) { p.sendOverlayMessage(Component.literal("That won't cook")); it.remove(); continue; }
			float need = 2f * 20;
			float done = (now - h.since) / need;
			int bars = Math.min(10, Math.round(done * 10));
			p.sendOverlayMessage(Component.literal("COOKING " + "|".repeat(bars) + ".".repeat(10 - bars)));
			if (now % 6 == 0) ((net.minecraft.server.level.ServerLevel) p.level()).sendParticles(ParticleTypes.SMOKE, p.getX(), p.getEyeY(), p.getZ(), 2, 0.2, 0.1, 0.2, 0.01);
			if (done < 1) continue;
			it.remove();
			boolean explodes = false;
			for (var b : PeakItemData.array(cook.f(), "additionalCookingBehaviors"))
				if (b.isJsonObject() && b.getAsJsonObject().has("$type") && b.getAsJsonObject().get("$type").getAsString().contains("Explode")) explodes = true;
			if (explodes) {
				stack.shrink(1);
				p.level().explode(null, p.getX(), p.getY() + 1, p.getZ(), 1.5f, Level.ExplosionInteraction.NONE);
				continue;
			}
			if (cook.bool("wreckWhenCooked", false)) { stack.shrink(1); p.level().playSound(null, p.blockPosition(), SoundEvents.ITEM_BREAK.value(), SoundSource.PLAYERS, 1f, 1f); continue; }
			PeakItems.cook(stack);
			PeakSfx.play((net.minecraft.server.level.ServerLevel) p.level(), h.at, "cook", SoundEvents.CAMPFIRE_CRACKLE, 1.5f, 1.2f);
			p.sendOverlayMessage(stack.getHoverName());
		}
	}
}
