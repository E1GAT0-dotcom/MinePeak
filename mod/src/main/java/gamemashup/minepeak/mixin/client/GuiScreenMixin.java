package gamemashup.minepeak.mixin.client;

import gamemashup.minepeak.PeakPauseScreen;
import gamemashup.minepeak.PeakTitleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * PEAK's menus in place of Minecraft's title screen and pause menu (MinePeak's PeakTitleScreen, PeakPauseScreen); but
 * Minecraft's own pause menu when it's asked for from PEAK's (INVITE FRIENDS: Open to LAN, and other mods' buttons).
 */
@Mixin(Gui.class)
public class GuiScreenMixin {
	@ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
	private Screen minepeak$peakMenus(Screen screen) {
		// no screen, outside a world: Minecraft makes its own title screen inside setScreen (after this), so PEAK's here
		if (screen == null) return Minecraft.getInstance().level == null ? new PeakTitleScreen() : null;
		if (screen.getClass() == TitleScreen.class) return new PeakTitleScreen();
		if (screen.getClass() == PauseScreen.class && ((PauseScreen) screen).showsPauseMenu() && !PeakPauseScreen.isMinecrafts(screen)) return new PeakPauseScreen();
		return screen;
	}
}
