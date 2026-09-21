package dev.bigcore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {
    @TempDir Path temp;
    @Test void generatesDefaultsAndPreservesExistingFile() throws Exception {
        assertEquals(Policy.Mode.AUTO, AffinityConfig.load(temp).server().mode());
        Path file = temp.resolve("big-core-affinity.properties");
        String value = "server.mode=explicit\nserver.cpus=2,4\nclient.mode=off\n";
        Files.writeString(file, value);
        AffinityConfig config = AffinityConfig.load(temp);
        assertEquals(Policy.Mode.EXPLICIT, config.server().mode());
        assertEquals(Policy.Mode.OFF, config.client().mode());
        assertEquals(value, Files.readString(file));
    }
    @Test void malformedConfigFailsAndIsNotOverwritten() throws Exception {
        Path file = temp.resolve("big-core-affinity.properties");
        Files.writeString(file, "server.mode=explicit\nserver.cpus=\n");
        assertThrows(IllegalArgumentException.class, () -> AffinityConfig.load(temp));
        assertEquals("server.mode=explicit\nserver.cpus=\n", Files.readString(file));
    }

    @Test void savesLanguageWithoutDroppingAffinitySettings() throws Exception {
        AffinityConfig.load(temp);
        AffinityConfig.saveLanguage(temp, "zh_cn");
        AffinityConfig config = AffinityConfig.load(temp);
        assertEquals("zh_cn", config.language());
        assertEquals(Policy.Mode.AUTO, config.server().mode());
    }
}
