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
        try {
            CoreAffinityLanguage.initialize(configDirectory);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger("CoreAffinity").warn("Could not resolve initial language; using English fallback", e);
        }
        service = AffinityService.load(configDirectory);
    }
}
