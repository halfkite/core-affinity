package dev.bigcore.fabric;

import dev.bigcore.AffinityConfig;
import dev.bigcore.CoreGroups;
import dev.bigcore.CpuList;
import dev.bigcore.Policy;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

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
    private TextFieldWidget serverCpus;
    private TextFieldWidget serverCoreIndex;
    private TextFieldWidget clientCpus;
    private TextFieldWidget clientCoreIndex;
    private TextFieldWidget language;
    private TextFieldWidget mainGroup;
    private TextFieldWidget sharedGroup;
    private TextFieldWidget disabledGroup;
    private ButtonWidget serverModeButton;
    private ButtonWidget clientModeButton;
    private ButtonWidget smtButton;
    private Text error;

    public CoreAffinityConfigScreen(Screen parent) {
        super(Text.translatable("screen.core_affinity.title"));
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
            this.error = Text.translatable("screen.core_affinity.load_error");
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

        serverModeButton = addDrawableChild(ButtonWidget.builder(modeText("server"), button -> cycleServerMode())
                .dimensions(left, 48, columnWidth, 20).build());
        clientModeButton = addDrawableChild(ButtonWidget.builder(modeText("client"), button -> cycleClientMode())
                .dimensions(right, 48, columnWidth, 20).build());

        serverCpus = field(left, 85, columnWidth, config.server().cpus(), "screen.core_affinity.cpus_placeholder");
        clientCpus = field(right, 85, columnWidth, config.client().cpus(), "screen.core_affinity.cpus_placeholder");
        serverCoreIndex = field(left, 122, columnWidth, Integer.toString(config.server().coreIndex()), "screen.core_affinity.index_placeholder");
        clientCoreIndex = field(right, 122, columnWidth, Integer.toString(config.client().coreIndex()), "screen.core_affinity.index_placeholder");

        smtButton = addDrawableChild(ButtonWidget.builder(smtText(), button -> {
            avoidSmt = !avoidSmt;
            smtButton.setMessage(smtText());
        }).dimensions(left, 151, wide, 20).build());

        language = field(left, 204, wide, Set.of(), "screen.core_affinity.language_placeholder");
        language.setText(config.language());
        mainGroup = field(left, 246, wide, config.groups().main(), "screen.core_affinity.group_placeholder");
        sharedGroup = field(left, 283, wide, config.groups().shared(), "screen.core_affinity.group_placeholder");
        disabledGroup = field(left, 320, wide, config.groups().disabled(), "screen.core_affinity.group_placeholder");

        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.core_affinity.save"), button -> save())
                .dimensions(left, 360, columnWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), button -> close())
                .dimensions(right, 360, columnWidth, 20).build());
    }

    private TextFieldWidget field(int x, int y, int fieldWidth, Set<Integer> values, String placeholderKey) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, fieldWidth, 20,
                Text.translatable(placeholderKey));
        field.setMaxLength(256);
        field.setText(CpuList.format(values));
        field.setPlaceholder(Text.translatable(placeholderKey));
        addDrawableChild(field);
        return field;
    }

    private TextFieldWidget field(int x, int y, int fieldWidth, String value, String placeholderKey) {
        TextFieldWidget field = field(x, y, fieldWidth, Set.of(), placeholderKey);
        field.setText(value);
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

    private Text modeText(String role) {
        Policy.Mode mode = role.equals("server") ? serverMode : clientMode;
        return Text.translatable("screen.core_affinity.mode", Text.translatable(
                "screen.core_affinity.mode." + mode.name().toLowerCase(Locale.ROOT)));
    }

    private Text smtText() {
        return Text.translatable("screen.core_affinity.smt", Text.translatable(
                avoidSmt ? "screen.core_affinity.smt.on" : "screen.core_affinity.smt.off"));
    }

    private void save() {
        try {
            Policy server = new Policy(serverMode, CpuList.parse(serverCpus.getText()), parseIndex(serverCoreIndex));
            Policy clientPolicy = new Policy(clientMode, CpuList.parse(clientCpus.getText()), parseIndex(clientCoreIndex));
            CoreGroups groups = new CoreGroups(CpuList.parse(mainGroup.getText()), CpuList.parse(sharedGroup.getText()),
                    CpuList.parse(disabledGroup.getText()));
            String selectedLanguage = language.getText().trim().toLowerCase(Locale.ROOT);
            if (selectedLanguage.isBlank()) selectedLanguage = "auto";
            AffinityConfig.saveAll(configDirectory, new AffinityConfig(server, clientPolicy, groups,
                    selectedLanguage, avoidSmt));
            MinecraftClient minecraft = MinecraftClient.getInstance();
            if (minecraft.player != null) minecraft.player.sendMessage(Text.translatable("screen.core_affinity.saved"), false);
            close();
        } catch (IOException | RuntimeException e) {
            error = Text.translatable("screen.core_affinity.invalid", e.getMessage());
        }
    }

    private static int parseIndex(TextFieldWidget field) {
        return Integer.parseInt(field.getText().trim());
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        int columnWidth = Math.min(170, (width - 50) / 2);
        int gap = 10;
        int left = width / 2 - columnWidth - gap / 2;
        int right = width / 2 + gap / 2;
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 18, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.translatable("screen.core_affinity.server"), left, 34, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.translatable("screen.core_affinity.client"), right, 34, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.translatable("screen.core_affinity.cpus_label"), left, 73, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.translatable("screen.core_affinity.cpus_label"), right, 73, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.translatable("screen.core_affinity.core_index_label"), left, 110, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.translatable("screen.core_affinity.core_index_label"), right, 110, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.translatable("screen.core_affinity.language_label"), left, 191, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.translatable("screen.core_affinity.group_main"), left, 233, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.translatable("screen.core_affinity.group_shared"), left, 270, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.translatable("screen.core_affinity.group_disabled"), left, 307, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.translatable("screen.core_affinity.restart"), left, 389, 0xAAAAAA);
        if (error != null) context.drawTextWithShadow(textRenderer, error, left, 405, 0xFF5555);
        super.render(context, mouseX, mouseY, delta);
    }
}
