package gamemashup.minepeak.mixin.client;

import gamemashup.minepeak.PeakPack;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Minecraft's resource packs get PEAK's (PeakPack: PEAK's item icons and names, made on this PC from the player's PEAK). */
@Mixin(PackRepository.class)
abstract class PackRepositoryMixin {
	@Shadow @Final @Mutable private Set<RepositorySource> sources;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void minepeak$peakPack(RepositorySource[] given, CallbackInfo ci) {
		for (RepositorySource s : given)
			if (s instanceof ClientPackSource) {
				sources = new LinkedHashSet<>(sources);
				sources.add(PeakPack.SOURCE);
				return;
			}
	}
}
