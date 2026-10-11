package gamemashup.minepeak;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CopperGolemStatueBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * PEAK's suitcases and scout statues, used:
 * - a suitcase (a chest MinePeak put there) pops open and PEAK's loot jumps out (drawn now: new every run, as in PEAK);
 *   then it stays open and empty.
 * - a scout statue (PEAK's RespawnChest: an old copper golem statue on a stone plinth, Minecraft's) brings back every
 *   scout who's dead or passed out, there, as PEAK's does (their statuses cleared; then a little curse and hunger);
 *   with nobody to bring back it gives one thing from PEAK's respawn coffin. Once: then it sits down.
 */
final class PeakStatue {
	private PeakStatue() { }

	static Block statue() { return Blocks.COPPER_GOLEM_STATUE.waxed().oxidized(); }

	static void place(WorldGenLevel level, BlockPos at, Direction facing) {
		level.setBlock(at, Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), 2);
		level.setBlock(at.above(), statue().defaultBlockState().setValue(CopperGolemStatueBlock.FACING, facing), 2);
	}

	static boolean isStatue(BlockState s) { return s.is(statue()); }

	/** A statue used: true if it did something (a spent one, sitting, does nothing). */
	static boolean use(ServerLevel level, BlockPos pos, ServerPlayer by) {
		BlockState s = level.getBlockState(pos);
		if (!isStatue(s) || !level.getBlockState(pos.below()).is(Blocks.CHISELED_STONE_BRICKS)) return false;
		if (s.getValue(CopperGolemStatueBlock.POSE) != CopperGolemStatueBlock.Pose.STANDING) {
			by.sendSystemMessage(Component.literal("This statue has already been used."));
			return true;
		}
		List<ServerPlayer> down = PeakStatus.deadOrDown(level.getServer());
		Vec3 at = Vec3.atBottomCenterOf(pos).add(0, 1, 0);
		if (!down.isEmpty()) {
			for (ServerPlayer p : down) {
				Vec3 r = at.add(level.getRandom().nextDouble() * 2 - 1, 4, level.getRandom().nextDouble() * 2 - 1);
				PeakStatus.revive(p, r, true);
				p.sendSystemMessage(Component.literal(by.getName().getString() + " brought you back."));
			}
			level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, at.x, at.y + 1, at.z, 80, 0.6, 1, 0.6, 0.4);
			PeakSfx.play(level, pos, "statue", SoundEvents.TOTEM_USE, 1f, 1f);
		} else {
			IslandData data = MinePeakServer.island(level.getServer());
			if (data != null)
				for (ItemStack st : PeakLoot.draw(data, "RespawnCoffin", 1, level.getRandom())) pop(level, pos, st);
			level.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 1f, 0.7f);
		}
		level.setBlock(pos, s.setValue(CopperGolemStatueBlock.POSE, CopperGolemStatueBlock.Pose.SITTING), 3);
		return true;
	}

	/** A suitcase used: its loot out (once). True if it was one of PEAK's. */
	static boolean open(ServerLevel level, BlockPos pos, ServerPlayer by) {
		if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) return false;
		var key = chest.getLootTable();
		if (key == null || !key.identifier().getNamespace().equals(MinePeak.ID)) return false;
		String path = key.identifier().getPath();
		if (path.equals("opened")) return true;
		if (!path.startsWith("luggage/")) return false;
		String[] parts = path.split("/");
		String pool = parts.length > 1 ? parts[1] : "luggagebeach";
		int count = parts.length > 2 ? Integer.parseInt(parts[2]) : 2;
		chest.setLootTable(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, MinePeak.id("opened")));
		chest.setChanged();
		IslandData data = MinePeakServer.island(level.getServer());
		if (data != null) for (ItemStack st : PeakLoot.draw(data, pool, count, level.getRandom())) pop(level, pos, st);
		level.blockEvent(pos, level.getBlockState(pos).getBlock(), 1, 1);   // (the lid stays up)
		PeakSfx.play(level, pos, "luggage_open", SoundEvents.CHEST_OPEN, 1f, 1.1f);
		level.sendParticles(ParticleTypes.POOF, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, 8, 0.3, 0.2, 0.3, 0.02);
		var sc = PeakStatus.of(by);
		sc.luggage++;
		if (pool.equals("luggageclown")) sc.clownLuggage++;
		if (sc.luggage >= 15) PeakBadges.award(by, "Plunderer");
		if (sc.clownLuggage >= 2) PeakBadges.award(by, "Jester");
		return true;
	}

	static void pop(ServerLevel level, BlockPos pos, ItemStack st) {
		ItemEntity e = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, st);
		e.setDeltaMovement((level.getRandom().nextDouble() - 0.5) * 0.15, 0.3, (level.getRandom().nextDouble() - 0.5) * 0.15);
		e.setPickUpDelay(10);
		e.setUnlimitedLifetime();
		level.addFreshEntity(e);
	}
}
