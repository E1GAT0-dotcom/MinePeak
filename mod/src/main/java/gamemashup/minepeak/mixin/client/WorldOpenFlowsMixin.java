package gamemashup.minepeak.mixin.client;

import com.mojang.serialization.Lifecycle;
import gamemashup.minepeak.IslandChunkGenerator;
import gamemashup.minepeak.MinePeak;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft calls any world whose world type comes from a mod's data "experimental": a warning when it's made ("These
 * settings are using experimental features...") and, each time it's opened, an offer of a backup. For MinePeak worlds,
 * which are what MinePeak is for, neither (other worlds keep them).
 */
@Mixin(WorldOpenFlows.class)
abstract class WorldOpenFlowsMixin {
	@Inject(method = "confirmWorldCreation", at = @At("HEAD"), cancellable = true)
	private static void minepeak$noWarningForMinePeak(Minecraft mc, CreateWorldScreen screen, Lifecycle lifecycle, Runnable createWorld,
			boolean skipWarnings, CallbackInfo ci) {
		var type = screen.getUiState().getWorldType();
		if (lifecycle == Lifecycle.experimental() && type.preset() != null && type.preset().unwrapKey().map(k -> k.identifier().equals(MinePeak.id("island"))).orElse(false)) {
			createWorld.run();
			ci.cancel();
		}
	}

	@Redirect(method = "openWorldCheckWorldStemCompatibility",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/WorldData;worldGenSettingsLifecycle()Lcom/mojang/serialization/Lifecycle;"))
	private Lifecycle minepeak$stableForMinePeak(WorldData data, LevelStorageSource.LevelStorageAccess access, WorldStem stem, PackRepository packs, Runnable onFail) {
		if (stem.worldDataAndGenSettings().genSettings().dimensions().overworld() instanceof IslandChunkGenerator) return Lifecycle.stable();
		return data.worldGenSettingsLifecycle();
	}
}
