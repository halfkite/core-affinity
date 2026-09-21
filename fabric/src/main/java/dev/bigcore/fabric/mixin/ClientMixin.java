package dev.bigcore.fabric.mixin;

import dev.bigcore.AffinityService;
import dev.bigcore.fabric.BigCoreFabric;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class ClientMixin {
    @Unique private boolean bigcore$attempted;
    @Inject(method = "tick", at = @At("HEAD"))
    private void bigcore$bind(CallbackInfo ci) {
        if (!bigcore$attempted) {
            bigcore$attempted = true;
            BigCoreFabric.service.bindCurrentThread(AffinityService.Role.CLIENT);
        }
    }
    @Inject(method = "close", at = @At("HEAD"))
    private void bigcore$restore(CallbackInfo ci) { BigCoreFabric.service.restoreCurrentThread(); }
}
