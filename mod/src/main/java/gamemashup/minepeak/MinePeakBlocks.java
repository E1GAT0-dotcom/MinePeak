package gamemashup.minepeak;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.ShortDryGrassBlock;
import net.minecraft.world.level.block.TallDryGrassBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;

/**
 * MinePeak's own blocks, for PEAK's colours Minecraft has no block for. Their textures are MinePeak's own.
 * - Red Beach sand: the salmon sand of PEAK's Red Beach (Minecraft's red sand is much more orange). It doesn't fall,
 *   so the beach's overhangs stay up.
 * - Blue Beach sand and rock: the same for PEAK's Blue Beach, pale blue.
 */
public final class MinePeakBlocks {
	private MinePeakBlocks() { }

	public static final Block RED_BEACH_SAND = register("red_beach_sand",
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).instrument(NoteBlockInstrument.SNARE).strength(0.5F).sound(SoundType.SAND));

	/** The Blue Beach's sand, pale blue (PEAK's BlueBeach). */
	public static final Block BLUE_BEACH_SAND = register("blue_beach_sand",
		BlockBehaviour.Properties.of().mapColor(MapColor.ICE).instrument(NoteBlockInstrument.SNARE).strength(0.5F).sound(SoundType.SAND));

	/** The Blue Beach's rock, under its sand and in its cliffs (PEAK's BlueBeach is one pale blue rock all through). */
	public static final Block BLUE_BEACH_ROCK = register("blue_beach_rock",
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE).instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(1.5F, 6.0F).sound(SoundType.STONE));

	/** The Blue Beach's grass (PEAK's blue weeds there): a short tuft and a tall one, like Minecraft's dry grass. */
	public static final Block BLUE_BEACH_GRASS = register("blue_beach_grass", ShortDryGrassBlock::new, grass());
	public static final Block TALL_BLUE_BEACH_GRASS = register("tall_blue_beach_grass", TallDryGrassBlock::new, grass());

	private static BlockBehaviour.Properties grass() {
		return BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE).replaceable().noCollision().instabreak().sound(SoundType.GRASS)
			.ignitedByLava().offsetType(BlockBehaviour.OffsetType.XYZ).pushReaction(PushReaction.DESTROY);
	}

	private static Block register(String name, BlockBehaviour.Properties properties) {
		return register(name, Block::new, properties);
	}

	private static Block register(String name, java.util.function.Function<BlockBehaviour.Properties, Block> factory, BlockBehaviour.Properties properties) {
		Block block = Blocks.register(ResourceKey.create(Registries.BLOCK, MinePeak.id(name)), factory, properties);
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, MinePeak.id(name));
		BlockItem item = new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix());
		item.registerBlocks(Item.BY_BLOCK, item);
		Registry.register(BuiltInRegistries.ITEM, itemKey, item);
		return block;
	}

	/** Loads the class (registering the blocks), and puts them in the creative menu's Natural Blocks tab. */
	static void init() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.NATURAL_BLOCKS).register(out -> {
			out.accept(RED_BEACH_SAND);
			out.accept(BLUE_BEACH_SAND);
			out.accept(BLUE_BEACH_ROCK);
			out.accept(BLUE_BEACH_GRASS);
			out.accept(TALL_BLUE_BEACH_GRASS);
		});
	}
}
