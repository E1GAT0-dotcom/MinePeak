package gamemashup.minepeak;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * Water as PEAK's, on the client (the player's movement is the client's): no swimming up a waterfall. In falling water
 * (a waterfall's: water pouring down), off the ground, the player sinks with it. (Landing in water never hurts:
 * MinePeakServer clears the fall as Minecraft does.)
 */
final class PeakWater {
	private PeakWater() { }

	static void tick(Minecraft mc) {
		var p = mc.player;
		if (p == null || p.onGround() || p.getAbilities().flying) return;
		if (!falling(mc, p.blockPosition()) && !falling(mc, BlockPos.containing(p.getEyePosition()))) return;
		Vec3 v = p.getDeltaMovement();
		if (v.y > -0.12) p.setDeltaMovement(v.x, -0.12, v.z);
	}

	private static boolean falling(Minecraft mc, BlockPos pos) {
		FluidState f = mc.level.getFluidState(pos);
		return f.is(FluidTags.WATER) && !f.isSource() && f.hasProperty(FlowingFluid.FALLING) && f.getValue(FlowingFluid.FALLING);
	}
}
