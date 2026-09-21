package dev.bigcore.fabric;

import dev.bigcore.AffinityService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Path;

public final class BigCoreFabric implements ModInitializer {
    public static AffinityService service;
    public static Path configDirectory;
    @Override public void onInitialize() {
        configDirectory = FabricLoader.getInstance().getConfigDir();
        service = AffinityService.load(configDirectory);
    }
}
