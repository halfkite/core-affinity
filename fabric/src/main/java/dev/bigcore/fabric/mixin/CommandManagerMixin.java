package dev.bigcore.fabric.mixin;

import com.mojang.brigadier.CommandDispatcher;
import dev.bigcore.fabric.CoreAffinityCommand;
import dev.bigcore.fabric.BigCoreFabric;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CommandManager.class)
public abstract class CommandManagerMixin {
    @Shadow @Final private CommandDispatcher<ServerCommandSource> dispatcher;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void coreaffinity$register(CommandManager.RegistrationEnvironment environment,
                                     CommandRegistryAccess registryAccess, CallbackInfo ci) {
        CoreAffinityCommand.register(dispatcher, BigCoreFabric.configDirectory);
    }
}
