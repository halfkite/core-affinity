package dev.bigcore.fabric;

import dev.bigcore.AffinityConfig;
import dev.bigcore.CoreGroups;
import dev.bigcore.CpuList;
import dev.bigcore.Policy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** Complete dependency-free editor for the JSON5 configuration. */
public final class CoreAffinityConfigScreen extends Screen {
    private final Screen parent;
    private final Path configDirectory;
    private Policy.Mode serverMode;
    private Policy.Mode clientMode;
    private boolean avoidSmt;
    private EditBox serverCpus;
    private EditBox serverCoreIndex;
    private EditBox clientCpus;
    private EditBox clientCoreIndex;
    private EditBox language;
    private EditBox mainGroup;
    private EditBox sharedGroup;
    private EditBox disabledGroup;
    private Button serverModeButton;
    private Button clientModeButton;
    private Button smtButton;
    private Component error;

    public CoreAffinityConfigScreen(Screen parent) {
        super(Component.translatable("screen.core_affinity.title"));
        this.parent = parent;
        this.configDirectory = BigCoreFabric.configDirectory;
        try {
            AffinityConfig config = AffinityConfig.load(configDirectory);
            this.serverMode = config.server().mode();
            this.clientMode = config.client().mode();
            this.avoidSmt = config.avoidSmt();
        } catch (IOException | RuntimeException e) {
            this.serverMode = Policy.Mode.AUTO;
            this.clientMode = Policy.Mode.AUTO;
            this.avoidSmt = false;
            this.error = Component.translatable("screen.core_affinity.load_error");
        }
    }

    @Override
    protected void init() {
        AffinityConfig config;
        try {
            config = AffinityConfig.load(configDirectory);
        } catch (IOException | RuntimeException e) {
            config = new AffinityConfig(new Policy(Policy.Mode.AUTO, Set.of(), -1),
                    new Policy(Policy.Mode.AUTO, Set.of(), -1));
        }
        int columnWidth = Math.min(170, (width - 50) / 2);
        int gap = 10;
        int left = width / 2 - columnWidth - gap / 2;
        int right = width / 2 + gap / 2;
        int wide = columnWidth * 2 + gap;

        serverModeButton = addRenderableWidget(Button.builder(modeText("server"), button -> cycleServerMode())
                .bounds(left, 48, columnWidth, 20).build());
        clientModeButton = addRenderableWidget(Button.builder(modeText("client"), button -> cycleClientMode())
                .bounds(right, 48, columnWidth, 20).build());

        serverCpus = field(left, 85, columnWidth, config.server().cpus(), "screen.core_affinity.cpus_placeholder");
        clientCpus = field(right, 85, columnWidth, config.client().cpus(), "screen.core_affinity.cpus_placeholder");
        serverCoreIndex = field(left, 122, columnWidth, Integer.toString(config.server().coreIndex()), "screen.core_affinity.index_placeholder");
        clientCoreIndex = field(right, 122, columnWidth, Integer.toString(config.client().coreIndex()), "screen.core_affinity.index_placeholder");

        smtButton = addRenderableWidget(Button.builder(smtText(), button -> {
            avoidSmt = !avoidSmt;
            smtButton.setMessage(smtText());
        }).bounds(left, 151, wide, 20).build());

        language = field(left, 204, wide, Set.of(), "screen.core_affinity.language_placeholder");
        language.setValue(config.language());
        mainGroup = field(left, 246, wide, config.groups().main(), "screen.core_affinity.group_placeholder");
        sharedGroup = field(left, 283, wide, config.groups().shared(), "screen.core_affinity.group_placeholder");
        disabledGroup = field(left, 320, wide, config.groups().disabled(), "screen.core_affinity.group_placeholder");

        addRenderableWidget(Button.builder(Component.translatable("screen.core_affinity.save"), button -> save())
                .bounds(left, 360, columnWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(right, 360, columnWidth, 20).build());
    }

    private EditBox field(int x, int y, int fieldWidth, Set<Integer> values, String placeholderKey) {
        EditBox field = new EditBox(font, x, y, fieldWidth, 20,
                Component.translatable(placeholderKey));
        field.setMaxLength(256);
        field.setValue(CpuList.format(values));
        field.setHint(Component.translatable(placeholderKey));
        addRenderableWidget(field);
        return field;
    }

    private EditBox field(int x, int y, int fieldWidth, String value, String placeholderKey) {
        EditBox field = field(x, y, fieldWidth, Set.of(), placeholderKey);
        field.setValue(value);
        return field;
    }

    private void cycleServerMode() {
        serverMode = next(serverMode);
        serverModeButton.setMessage(modeText("server"));
    }

    private void cycleClientMode() {
        clientMode = next(clientMode);
        clientModeButton.setMessage(modeText("client"));
    }

    private static Policy.Mode next(Policy.Mode mode) {
        return switch (mode) {
            case AUTO -> Policy.Mode.EXPLICIT;
            case EXPLICIT -> Policy.Mode.OFF;
            case OFF -> Policy.Mode.AUTO;
        };
    }

    private Component modeText(String role) {
        Policy.Mode mode = role.equals("server") ? serverMode : clientMode;
        return Component.translatable("screen.core_affinity.mode", Component.translatable(
                "screen.core_affinity.mode." + mode.name().toLowerCase(Locale.ROOT)));
    }

    private Component smtText() {
        return Component.translatable("screen.core_affinity.smt", Component.translatable(
                avoidSmt ? "screen.core_affinity.smt.on" : "screen.core_affinity.smt.off"));
    }

    private void save() {
        try {
            Policy server = new Policy(serverMode, CpuList.parse(serverCpus.getValue()), parseIndex(serverCoreIndex));
            Policy clientPolicy = new Policy(clientMode, CpuList.parse(clientCpus.getValue()), parseIndex(clientCoreIndex));
            CoreGroups groups = new CoreGroups(CpuList.parse(mainGroup.getValue()), CpuList.parse(sharedGroup.getValue()),
                    CpuList.parse(disabledGroup.getValue()));
            String selectedLanguage = language.getValue().trim().toLowerCase(Locale.ROOT);
            if (selectedLanguage.isBlank()) selectedLanguage = "auto";
            AffinityConfig.saveAll(configDirectory, new AffinityConfig(server, clientPolicy, groups,
                    selectedLanguage, avoidSmt));
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) minecraft.player.sendSystemMessage(Component.translatable("screen.core_affinity.saved"));
            onClose();
        } catch (IOException | RuntimeException e) {
            error = Component.translatable("screen.core_affinity.invalid", e.getMessage());
        }
    }

    private static int parseIndex(EditBox field) {
        return Integer.parseInt(field.getValue().trim());
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        extractBackground(context, mouseX, mouseY, delta);
        int columnWidth = Math.min(170, (width - 50) / 2);
        int gap = 10;
        int left = width / 2 - columnWidth - gap / 2;
        int right = width / 2 + gap / 2;
        context.centeredText(font, title, width / 2, 18, 0xFFFFFF);
        context.text(font, Component.translatable("screen.core_affinity.server"), left, 34, 0xFFFFFF);
        context.text(font, Component.translatable("screen.core_affinity.client"), right, 34, 0xFFFFFF);
        context.text(font, Component.translatable("screen.core_affinity.cpus_label"), left, 73, 0xFFFFFF);
        context.text(font, Component.translatable("screen.core_affinity.cpus_label"), right, 73, 0xFFFFFF);
        context.text(font, Component.translatable("screen.core_affinity.core_index_label"), left, 110, 0xFFFFFF);
        context.text(font, Component.translatable("screen.core_affinity.core_index_label"), right, 110, 0xFFFFFF);
        context.text(font, Component.translatable("screen.core_affinity.language_label"), left, 191, 0xFFFFFF);
        context.text(font, Component.translatable("screen.core_affinity.group_main"), left, 233, 0xFFFFFF);
        context.text(font, Component.translatable("screen.core_affinity.group_shared"), left, 270, 0xFFFFFF);
        context.text(font, Component.translatable("screen.core_affinity.group_disabled"), left, 307, 0xFFFFFF);
        context.text(font, Component.translatable("screen.core_affinity.restart"), left, 389, 0xAAAAAA);
        if (error != null) context.text(font, error, left, 405, 0xFF5555);
        super.extractRenderState(context, mouseX, mouseY, delta);
    }
}
