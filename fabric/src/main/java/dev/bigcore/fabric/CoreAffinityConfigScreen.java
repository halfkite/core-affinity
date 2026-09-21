package dev.bigcore.fabric;

import dev.bigcore.AffinityConfig;
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

/** Small dependency-free client screen exposed through Mod Menu. */
public final class CoreAffinityConfigScreen extends Screen {
    private final Screen parent;
    private final Path configDirectory;
    private Policy.Mode mode;
    private TextFieldWidget cpusField;
    private TextFieldWidget coreIndexField;
    private ButtonWidget modeButton;
    private Text error;

    public CoreAffinityConfigScreen(Screen parent) {
        super(Text.translatable("screen.core_affinity.title"));
        this.parent = parent;
        this.configDirectory = BigCoreFabric.configDirectory;
        try {
            this.mode = AffinityConfig.load(configDirectory).client().mode();
        } catch (IOException | RuntimeException e) {
            this.mode = Policy.Mode.AUTO;
            this.error = Text.translatable("screen.core_affinity.load_error");
        }
    }

    @Override
    protected void init() {
        int left = this.width / 2 - 120;
        AffinityConfig config;
        try {
            config = AffinityConfig.load(configDirectory);
        } catch (IOException | RuntimeException e) {
            config = new AffinityConfig(new Policy(Policy.Mode.AUTO, Set.of(), -1),
                    new Policy(Policy.Mode.AUTO, Set.of(), -1));
        }
        Policy client = config.client();

        modeButton = addDrawableChild(ButtonWidget.builder(modeText(), button -> cycleMode())
                .dimensions(left, 62, 240, 20).build());
        cpusField = new TextFieldWidget(textRenderer, left, 106, 240, 20,
                Text.translatable("screen.core_affinity.cpus"));
        cpusField.setMaxLength(256);
        cpusField.setText(CpuList.format(client.cpus()));
        cpusField.setPlaceholder(Text.translatable("screen.core_affinity.cpus_placeholder"));
        addDrawableChild(cpusField);

        coreIndexField = new TextFieldWidget(textRenderer, left, 150, 240, 20,
                Text.translatable("screen.core_affinity.core_index"));
        coreIndexField.setMaxLength(10);
        coreIndexField.setText(Integer.toString(client.coreIndex()));
        addDrawableChild(coreIndexField);

        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.core_affinity.save"), button -> save())
                .dimensions(left, 194, 115, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), button -> close())
                .dimensions(left + 125, 194, 115, 20).build());
    }

    private void cycleMode() {
        mode = switch (mode) {
            case AUTO -> Policy.Mode.EXPLICIT;
            case EXPLICIT -> Policy.Mode.OFF;
            case OFF -> Policy.Mode.AUTO;
        };
        modeButton.setMessage(modeText());
    }

    private Text modeText() {
        return Text.translatable("screen.core_affinity.mode", Text.translatable(
                "screen.core_affinity.mode." + mode.name().toLowerCase(Locale.ROOT)));
    }

    private void save() {
        try {
            Set<Integer> cpus = CpuList.parse(cpusField.getText());
            int coreIndex = Integer.parseInt(coreIndexField.getText().trim());
            AffinityConfig.saveClientPolicy(configDirectory, new Policy(mode, cpus, coreIndex));
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) client.player.sendMessage(Text.translatable("screen.core_affinity.saved"), false);
            close();
        } catch (IOException | RuntimeException e) {
            error = Text.translatable("screen.core_affinity.invalid", e.getMessage());
        }
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 20, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.translatable("screen.core_affinity.mode_label"), width / 2 - 120, 48, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.translatable("screen.core_affinity.cpus_label"), width / 2 - 120, 92, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.translatable("screen.core_affinity.core_index_label"), width / 2 - 120, 136, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.translatable("screen.core_affinity.restart"), width / 2 - 120, 226, 0xAAAAAA);
        if (error != null) context.drawTextWithShadow(textRenderer, error, width / 2 - 120, 246, 0xFF5555);
        super.render(context, mouseX, mouseY, delta);
    }
}
