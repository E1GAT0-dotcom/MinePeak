package gamemashup.minepeak;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

/** Where PEAK's rules apply: a MinePeak Island world (its overworld is of MinePeak's dimension type "island"). */
public final class PeakWorld {
	private PeakWorld() { }

	static final ResourceKey<DimensionType> ISLAND = ResourceKey.create(Registries.DIMENSION_TYPE, MinePeak.id("island"));

	/** Is this level PEAK's island? */
	public static boolean is(Level level) {
		return level != null && level.dimensionTypeRegistration().is(ISLAND);
	}

	/** Is this player a scout on PEAK's island (survival or adventure: creative and spectators play by Minecraft's rules)? */
	public static boolean scout(Player p) {
		return p != null && is(p.level()) && !p.isCreative() && !p.isSpectator();
	}
}
