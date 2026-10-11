package gamemashup.minepeak.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import gamemashup.minepeak.MinePeakClient;
import gamemashup.minepeak.PeakInventoryClient;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	/**
	 * A MinePeak world that still needs today's PEAK island waits on MinePeak's own screen (with Cancel) while it's
	 * exported, before Minecraft starts loading it; the screen loads it when the island is ready.
	 */
	@Inject(method = "doWorldLoad", at = @At("HEAD"), cancellable = true)
	private void minepeak$waitForIsland(LevelStorageSource.LevelStorageAccess storage, PackRepository packs, WorldStem stem,
			Optional<GameRules> rules, boolean newWorld, CallbackInfo ci) {
		if (MinePeakClient.waitForIsland((Minecraft) (Object) this, storage, packs, stem, rules, newWorld)) ci.cancel();
	}

	/**
	 * While a world loads, Minecraft draws the loading screen in a loop of its own that never handles the window's
	 * messages (only the main loop does): fine for the usual few seconds, but past five Windows calls the window "Not
	 * responding" and shows a frozen picture of it (a MinePeak world is 1,408 blocks tall, and slow PCs take a while).
	 */
	/** A scout using (right click) at a thing on the ground picks it up, as in PEAK (PeakInventoryClient). */
	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void minepeak$pickUp(CallbackInfo ci) {
		if (PeakInventoryClient.pickUp((Minecraft) (Object) this)) ci.cancel();
	}

	/** Left click is PEAK's climb for a scout: at a block (or on a wall) it's nothing else (PeakClimbClient). */
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void minepeak$climbNotAttack(CallbackInfoReturnable<Boolean> cir) {
		if (gamemashup.minepeak.PeakClimbClient.leftClickIsClimb((Minecraft) (Object) this)) cir.setReturnValue(false);
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void minepeak$climbNotMining(boolean down, CallbackInfo ci) {
		if (gamemashup.minepeak.PeakClimbClient.leftClickIsClimb((Minecraft) (Object) this)) ci.cancel();
	}

	@Inject(method = "doWorldLoad", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V"))
	private void minepeak$handleWindowWhileLoading(CallbackInfo ci) {
		RenderSystem.pollEvents();
	}
}
