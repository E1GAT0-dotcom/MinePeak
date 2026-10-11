package gamemashup.minepeak.mixin.client;

import gamemashup.minepeak.MinePeakWorldOptions;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Create World's World tab: MinePeak's settings, shown when the world type is MinePeak Island (MinePeakWorldOptions). */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$WorldTab")
abstract class CreateWorldTabMixin {
	@Shadow @Final private Button customizeTypeButton;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void minepeak$settings(CreateWorldScreen screen, CallbackInfo ci) {
		MinePeakWorldOptions.add(screen, (GridLayout) ((GridLayoutTab) (Object) this).getLayout(), customizeTypeButton);
	}
}
