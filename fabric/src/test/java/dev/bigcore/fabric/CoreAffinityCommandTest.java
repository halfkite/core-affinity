package dev.bigcore.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.bigcore.AffinityConfig;
import dev.bigcore.CoreGroups;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CoreAffinityCommandTest {
    @TempDir Path directory;

    @BeforeAll static void initializeMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private CommandSourceStack source(PermissionSet permissions) {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null,
                permissions, Component.literal("test"), null);
    }

    @Test void lowPermissionNonPlayerCannotChangeGlobalLanguageViaEitherRoute() throws Exception {
        AffinityConfig.load(directory);
        AffinityConfig.saveLanguage(directory, "zh_cn");
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        CoreAffinityCommand.register(dispatcher, directory);
        CommandSourceStack source = source(PermissionSet.NO_PERMISSIONS);
        assertNull(source.getPlayer());
        assertEquals(0, dispatcher.execute("coreaffinity language en_us", source));
        assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("coreaffinity language global en_us", source));
        assertEquals("zh_cn", AffinityConfig.load(directory).language());
        for (String command : new String[]{"coreaffinity assign 12 main", "coreaffinity apply", "coreaffinity smt off"})
            assertThrows(CommandSyntaxException.class, () -> dispatcher.execute(command, source));
    }

    @Test void authorizedConsoleCanStillSetDefaultLanguage() throws Exception {
        AffinityConfig.load(directory);
        AffinityConfig.saveLanguage(directory, "zh_cn");
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        CoreAffinityCommand.register(dispatcher, directory);
        assertEquals(1, dispatcher.execute("coreaffinity language en_us", source(PermissionSet.ALL_PERMISSIONS)));
        assertEquals("en_us", AffinityConfig.load(directory).language());
    }

    @Test void confirmationButtonRejectsTheTokenFromAnOlderPlan() {
        var oldPlan = new CoreAffinityCommand.Draft(new CoreGroups(), false, UUID.randomUUID().toString());
        var newPlan = new CoreAffinityCommand.Draft(new CoreGroups(), true, UUID.randomUUID().toString());
        var oldClick = assertInstanceOf(ClickEvent.RunCommand.class,
                CoreAffinityCommand.applyButton("en_us", oldPlan).getStyle().getClickEvent());
        var newClick = assertInstanceOf(ClickEvent.RunCommand.class,
                CoreAffinityCommand.applyButton("en_us", newPlan).getStyle().getClickEvent());
        String prefix = "/coreaffinity apply ";
        assertTrue(oldClick.command().startsWith(prefix));
        assertTrue(newClick.command().startsWith(prefix));
        assertFalse(newPlan.confirms(oldClick.command().substring(prefix.length())));
        assertTrue(newPlan.confirms(newClick.command().substring(prefix.length())));
    }

    @Test void listWithoutAPendingPlanHasNoActiveConfirmationButton() {
        assertNull(CoreAffinityCommand.applyButton("en_us", null).getStyle().getClickEvent());
    }
}
