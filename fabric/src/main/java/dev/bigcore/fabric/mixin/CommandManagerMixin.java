package dev.bigcore.fabric.mixin;

import com.mojang.brigadier.CommandDispatcher;
import dev.bigcore.fabric.CoreAffinityCommand;
import dev.bigcore.fabric.BigCoreFabric;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Commands.class)
public abstract class CommandManagerMixin {
    @Shadow @Final private CommandDispatcher<CommandSourceStack> dispatcher;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void coreaffinity$register(Commands.CommandSelection environment,
                                     CommandBuildContext registryAccess, CallbackInfo ci) {
        CoreAffinityCommand.register(dispatcher, BigCoreFabric.configDirectory);
    }
}
