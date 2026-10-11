package gamemashup.minepeak;

import com.mojang.serialization.MapCodec;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The blocks PEAK's gear leaves on the island, Minecraft's own made over:
 * - rope (a rope spool's, a rope cannon's): a hanging strand, climbable (as a chain looks, in rope);
 * - vine rope (the chain launcher's, the magic bean's): the same, green;
 * - piton: hammered into a wall (a tripwire hook in iron): hanging by it you get your stamina back, as on PEAK's;
 *   the Alpine's own rusty ones crack and break;
 * - scout cannon (a dispenser, pointing where it fires): step up and use it, and after PEAK's 3 s it fires you.
 */
public final class PeakGearBlocks {
	private PeakGearBlocks() { }

	public static final Block ROPE = register("rope", RopeBlock::new, rope(MapColor.WOOD));
	public static final Block VINE_ROPE = register("vine_rope", RopeBlock::new, rope(MapColor.PLANT));
	public static final Block PITON = register("piton", PitonBlock::new, BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(1.5f).sound(SoundType.METAL)
		.noOcclusion().pushReaction(PushReaction.DESTROY));
	/** The Alpine's rusty pitons (PEAK's ShittyPiton): they crack under you, then break (PeakHazards). */
	public static final Block RUSTY_PITON = register("rusty_piton", PitonBlock::new, BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(1f).sound(SoundType.COPPER)
		.noOcclusion().pushReaction(PushReaction.DESTROY));
	public static final Block SCOUT_CANNON = register("scout_cannon", CannonBlock::new, BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(2f).sound(SoundType.STONE));

	private static BlockBehaviour.Properties rope(MapColor c) {
		return BlockBehaviour.Properties.of().mapColor(c).noCollision().strength(0.4f).sound(SoundType.WOOL).noOcclusion().pushReaction(PushReaction.DESTROY);
	}

	private static Block register(String name, java.util.function.Function<BlockBehaviour.Properties, Block> f, BlockBehaviour.Properties p) {
		return Blocks.register(ResourceKey.create(Registries.BLOCK, MinePeak.id(name)), f, p);
	}

	static void init() { }

	/** A strand of rope: climbable (block tag minecraft:climbable), nothing to bump into. */
	public static final class RopeBlock extends Block {
		public static final MapCodec<RopeBlock> CODEC = simpleCodec(RopeBlock::new);
		private static final VoxelShape SHAPE = Block.box(6.5, 0, 6.5, 9.5, 16, 9.5);
		public RopeBlock(BlockBehaviour.Properties p) { super(p); }
		@Override protected MapCodec<RopeBlock> codec() { return CODEC; }
		@Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return SHAPE; }
	}

	/** A piton in a wall (facing out of it). */
	public static final class PitonBlock extends HorizontalDirectionalBlock {
		public static final MapCodec<PitonBlock> CODEC = simpleCodec(PitonBlock::new);
		private static final Map<Direction, VoxelShape> SHAPES = Shapes.rotateHorizontal(Block.boxZ(6.0, 6.0, 10.0, 10.0, 16.0));
		public PitonBlock(BlockBehaviour.Properties p) { super(p); registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)); }
		@Override protected MapCodec<PitonBlock> codec() { return CODEC; }
		@Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING); }
		@Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return SHAPES.get(s.getValue(FACING)); }
	}

	/** PEAK's scout cannon: use it and, after its fuse, it fires you the way it points. */
	public static final class CannonBlock extends DirectionalBlock {
		public static final MapCodec<CannonBlock> CODEC = simpleCodec(CannonBlock::new);
		public static final EnumProperty<Direction> FACING = DirectionalBlock.FACING;
		public CannonBlock(BlockBehaviour.Properties p) { super(p); registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP)); }
		@Override protected MapCodec<CannonBlock> codec() { return CODEC; }
		@Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING); }
		@Override
		protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
			if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) {
				level.playSound(null, pos, SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1f, 1f);
				Direction d = state.getValue(FACING);
				Vec3 at = Vec3.atCenterOf(pos).add(d.getStepX() * 0.9, d.getStepY() * 0.9 + (d == Direction.DOWN ? -1 : 0), d.getStepZ() * 0.9);
				sp.teleportTo(at.x, at.y - 0.5, at.z);
				PeakActions.later(sl.getServer(), 60, () -> PeakGear.fireCannon(sl, pos, sp));
			}
			return InteractionResult.SUCCESS;
		}
	}

	static void dummy(RandomSource r) { }
}
