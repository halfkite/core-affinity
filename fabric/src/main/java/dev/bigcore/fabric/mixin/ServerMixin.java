package dev.bigcore.fabric.mixin;

import dev.bigcore.AffinityService;
import dev.bigcore.fabric.BigCoreFabric;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class ServerMixin {
    @Unique private boolean bigcore$attempted;
    // First tick runs on the real Server thread, after startup has created worker pools.
    @Inject(method = "tick", at = @At("HEAD"))
    private void bigcore$bind(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        if (!bigcore$attempted) {
            bigcore$attempted = true;
            BigCoreFabric.service.bindCurrentThread(AffinityService.Role.SERVER);
        }
    }
    @Inject(method = "shutdown", at = @At("HEAD"))
    private void bigcore$restore(CallbackInfo ci) { BigCoreFabric.service.restoreCurrentThread(); }
}
