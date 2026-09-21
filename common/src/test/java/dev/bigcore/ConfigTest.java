package dev.bigcore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {
    @TempDir Path temp;

    @Test void generatesJson5DefaultsWithBilingualComments() throws Exception {
        AffinityConfig config = AffinityConfig.load(temp);
        Path file = temp.resolve("core-affinity.json5");
        String text = Files.readString(file);
        assertEquals(Policy.Mode.AUTO, config.server().mode());
        assertTrue(text.contains("\"server\"") && text.contains("// Server main thread") && text.contains("服务端主线程"));
    }

    @Test void readsJson5CommentsArraysAndTrailingCommas() throws Exception {
        Files.writeString(temp.resolve("core-affinity.json5"), """
                {
                  // English / 中文
                  "language": "zh_cn",
                  "server": { "mode": "explicit", "cpus": [2, 4,], "coreIndex": -1, "avoidSmt": true, },
                  "client": { "mode": "off", "cpus": [], "coreIndex": -1, },
                  "groups": { "main": [2, 4], "shared": [6], "disabled": [12,], },
                }
                """);
        AffinityConfig config = AffinityConfig.load(temp);
        assertEquals(Policy.Mode.EXPLICIT, config.server().mode());
        assertEquals(Set.of(2, 4), config.server().cpus());
        assertEquals(Set.of(2, 4), config.groups().main());
        assertTrue(config.avoidSmt());
    }

    @Test void migratesLegacyPropertiesWithoutChangingTheOriginal() throws Exception {
        Path legacy = temp.resolve("big-core-affinity.properties");
        String value = "server.mode=explicit\nserver.cpus=2,4\nclient.mode=off\n";
        Files.writeString(legacy, value);
        AffinityConfig config = AffinityConfig.load(temp);
        assertEquals(Policy.Mode.EXPLICIT, config.server().mode());
        assertEquals(Policy.Mode.OFF, config.client().mode());
        assertTrue(Files.exists(temp.resolve("core-affinity.json5")));
        assertEquals(value, Files.readString(legacy));
    }

    @Test void malformedJson5FailsAndIsNotOverwritten() throws Exception {
        Path file = temp.resolve("core-affinity.json5");
        String value = "{ \"server\": [ }\n";
        Files.writeString(file, value);
        assertThrows(IllegalArgumentException.class, () -> AffinityConfig.load(temp));
        assertEquals(value, Files.readString(file));
    }

    @Test void savesLanguageWithoutDroppingAffinitySettings() throws Exception {
        AffinityConfig.load(temp);
        AffinityConfig.saveLanguage(temp, "zh_cn");
        AffinityConfig config = AffinityConfig.load(temp);
        assertEquals("zh_cn", config.language());
        assertEquals(Policy.Mode.AUTO, config.server().mode());
    }
}
