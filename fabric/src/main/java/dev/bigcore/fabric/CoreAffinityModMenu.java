package dev.bigcore.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Optional Mod Menu integration; Mod Menu remains an optional client dependency. */
public final class CoreAffinityModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return CoreAffinityConfigScreen::new;
    }
}
