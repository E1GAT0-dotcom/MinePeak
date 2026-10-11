package gamemashup.minepeak;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * PEAK's gear on the client (the player's own movement is the client's):
 * - parasol (and parachute): held while falling, you float down (PEAK: velocity x0.8 each 1/60 s);
 * - glider: held, jump in the air to open it and glide as with an elytra (it costs a little stamina);
 * - jetpack (in the backpack slot or held): jump held in the air flies you up while its fuel lasts;
 * - the wind (PeakHazards.Wind): pushes you when you're out in it, not when you hang on a piton; snow blows past;
 * - a guidebook or a torn page: read (used), PEAK's own pages (exported with the items, in the player's language);
 * - a compass in hand: its needle (bottom middle) to the summit; the pirate's to the nearest suitcase; the warp one spins.
 */
public final class PeakGearClient {
	private PeakGearClient() { }

	static volatile PeakHazards.Wind wind;

	static void init() {
		ClientPlayNetworking.registerGlobalReceiver(PeakHazards.Wind.TYPE, (payload, context) -> wind = payload);
		ClientPlayNetworking.registerGlobalReceiver(PeakIslandInfo.Info.TYPE, (payload, context) -> PeakIslandInfo.current = payload);
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> PeakIslandInfo.current = null);
		// PEAK's sound effects (or Minecraft's, without an export)
		ClientPlayNetworking.registerGlobalReceiver(PeakSfx.Sfx.TYPE, (s, context) -> context.client().execute(() -> {
			var mc = context.client();
			if (mc.level == null) return;
			Vec3 at = new Vec3(s.x(), s.y(), s.z());
			if (PeakSounds.once(s.role(), at, s.volume(), s.pitch()) || s.fallback().isEmpty()) return;
			var ev = net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.getValue(net.minecraft.resources.Identifier.parse(s.fallback()));
			if (ev != null) mc.level.playLocalSound(s.x(), s.y(), s.z(), ev, net.minecraft.sounds.SoundSource.PLAYERS, s.volume(), s.pitch(), false);
		}));
		ClientPlayNetworking.registerGlobalReceiver(PeakEnding.EndRun.TYPE, (payload, context) -> context.client().execute(() -> context.client().gui.setScreen(new PeakEndScreen(payload))));
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (!level.isClientSide()) return InteractionResult.PASS;
			var def = PeakItems.def(player.getItemInHand(hand));
			if (def == null || !def.item().startsWith("Guidebook")) return InteractionResult.PASS;
			if (PeakGuidebookScreen.open(def.item())) return InteractionResult.SUCCESS;   // (PEAK's pages as PEAK draws them)
			List<Component> pages = pages(def);
			Minecraft.getInstance().gui.setScreen(new BookViewScreen(new BookViewScreen.BookAccess(pages)));
			return InteractionResult.SUCCESS;
		});
	}

	/** PEAK's pages of a guidebook (or a torn page), as exported: its titles and words, a page each. */
	static List<Component> pages(PeakItemData.Def def) {
		List<Component> pages = new ArrayList<>();
		var texts = PeakItemData.texts(def.item());
		StringBuilder page = new StringBuilder();
		for (String[] t : texts) {
			String path = t[0], text = t[1].replace("<br>", "\n").replaceAll("<[^>]+>", "").replace("\t", "").trim();
			if (path.contains("PageTurningPivot") || text.isEmpty() || text.matches("Page \\d+")) continue;
			boolean title = path.contains("Title") || path.contains("RULE");
			if (title && page.length() > 0 && path.contains("Guide_TitleText") && !path.endsWith("RULE")) { pages.add(Component.literal(page.toString().trim())); page.setLength(0); }
			page.append(text).append(title ? "\n" : "\n\n");
			if (page.length() > 600) { pages.add(Component.literal(page.toString().trim())); page.setLength(0); }
		}
		if (page.length() > 0) pages.add(Component.literal(page.toString().trim()));
		if (pages.isEmpty()) pages.add(Component.literal("This page is too torn to read."));
		return pages;
	}

	private static float glideStamina;

	static void tick(Minecraft mc) {
		var p = mc.player;
		if (p == null || mc.level == null) { wind = null; return; }
		if (!PeakWorld.scout(p)) return;
		var held = PeakItems.def(p.getMainHandItem());
		Vec3 v = p.getDeltaMovement();
		boolean air = !p.onGround() && !p.isInWater() && !PeakClimbClient.climbing();
		if (held != null && air && v.y < -0.1) {
			if (held.has("Parasol") || held.item().equals("Parachute")) {
				p.setDeltaMovement(v.x * 0.92, Math.max(v.y, -0.12), v.z * 0.92);
				p.fallDistance = 0;
			}
		}
		// the glider (Minecraft's gliding, held): PEAK's 0.05 stamina to open it, a little more as it flies; out of stamina, it folds
		if (p.isFallFlying() && held != null && held.has("Glider")) {
			PeakClimbClient.spend(glideStamina == 0 ? 0.05f : 0.0015f);
			glideStamina += 1;
			if (PeakClimbClient.stamina + PeakClimbClient.bonus <= 0) p.stopFallFlying();
		} else glideStamina = 0;
		// the jetpack: worn (the backpack slot) or held
		var pack = PeakItems.def(p.getInventory().getItem(PeakInventory.BACKPACK));
		var jet = held != null && held.has("JetpackItem") ? p.getMainHandItem() : pack != null && pack.has("JetpackItem") ? p.getInventory().getItem(PeakInventory.BACKPACK) : null;
		if (jet != null && !p.onGround() && mc.options.keyJump.isDown() && PeakItems.tag(jet).getFloatOr("fuel", 0) > 0) {
			p.setDeltaMovement(v.x, Math.min(v.y + 0.12, 0.55), v.z);
			p.fallDistance = 0;
			mc.level.addParticle(ParticleTypes.FLAME, p.getX(), p.getY(), p.getZ(), 0, -0.2, 0);
		}
		// the wind
		var w = wind;
		if (w != null && w.on() && w.strength() > 0 && !PeakClimbClient.onPiton(p)) {
			double k = w.strength() * 0.0025 * (PeakStatusClient.balloons() > 0 ? 2 + PeakStatusClient.balloons() : 1) * (held != null && held.has("Parasol") ? 4 : 1);
			p.setDeltaMovement(p.getDeltaMovement().add(w.x() * k, 0, w.z() * k));
			for (int i = 0; i < 6; i++)
				mc.level.addParticle(ParticleTypes.SNOWFLAKE, p.getX() - w.x() * 12 + (Math.random() - 0.5) * 16, p.getY() + Math.random() * 6, p.getZ() - w.z() * 12 + (Math.random() - 0.5) * 16, w.x() * 0.9, -0.05, w.z() * 0.9);
		}
	}

	// ---- the compass ----


	static void drawCompass(GuiGraphicsExtractor g, Minecraft mc) {
		var p = mc.player;
		var def = PeakItems.def(p.getMainHandItem());
		if (def == null || !def.has("CompassPointer")) return;
		double angle;
		if (def.item().startsWith("Warp")) angle = (System.currentTimeMillis() % 1000) / 1000.0 * Math.PI * 2;
		else {
			Vec3 to = target(mc, def.item().startsWith("Pirate"));
			if (to == null) return;
			double world = Math.atan2(to.z - p.getZ(), to.x - p.getX());
			double facing = Math.toRadians(p.getYRot() + 90);
			angle = world - facing;
		}
		int cx = g.guiWidth() / 2, cy = g.guiHeight() - 40, r = 14;
		PeakHud.roundRect(g, cx - r - 2, cy - r - 2, 2 * r + 4, 2 * r + 4, r + 2, 0xC01A444D);
		for (int i = 0; i <= r; i++) {
			int x = cx + (int) Math.round(Math.sin(angle) * i), y = cy - (int) Math.round(Math.cos(angle) * i);
			g.fill(x - 1, y - 1, x + 1, y + 1, i > r / 3 ? 0xFFC4473A : 0xFFDFDAC2);
		}
	}

	private static Vec3 chest;
	private static long chestAt;

	private static Vec3 target(Minecraft mc, boolean pirate) {
		var p = mc.player;
		if (pirate) {
			// (the nearest chest looked for twice a second, not every frame: it's 289 chunks' block entities)
			long now = System.currentTimeMillis();
			if (now - chestAt < 500) return chest;
			chestAt = now;
			chest = nearestChest(mc, p);
			return chest;
		}
		// (the world's summit, as its server says: a friend's PC may have another island, or none)
		var info = PeakIslandInfo.current;
		Vec3 top = info != null ? info.summit() : null;
		return top != null ? top : p.position().add(0, 0, -100);
	}

	/** The nearest suitcase (chest) in the 17x17 chunks round the player, more than 2 blocks off. */
	private static Vec3 nearestChest(Minecraft mc, net.minecraft.client.player.LocalPlayer p) {
		Vec3 best = null;
		double bd = Double.MAX_VALUE;
		int r = 8;
		var cp = p.chunkPosition();
		for (int dx = -r; dx <= r; dx++)
			for (int dz = -r; dz <= r; dz++) {
				var chunk = mc.level.getChunkSource().getChunk(cp.x() + dx, cp.z() + dz, false);
				if (chunk == null) continue;
				for (var be : chunk.getBlockEntities().values())
					if (be instanceof ChestBlockEntity) { Vec3 v = Vec3.atCenterOf(be.getBlockPos()); double d = v.distanceToSqr(p.position()); if (d < bd && d > 4) { bd = d; best = v; } }
			}
		return best;
	}

	static BlockPos unused() { return BlockPos.ZERO; }
}
