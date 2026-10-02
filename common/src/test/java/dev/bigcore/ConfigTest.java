package dev.bigcore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.List;
import java.util.concurrent.*;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import com.sun.nio.file.ExtendedOpenOption;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @Test void savesClientPolicyWithoutDroppingServerSettings() throws Exception {
        AffinityConfig.load(temp);
        AffinityConfig.saveClientPolicy(temp, new Policy(Policy.Mode.EXPLICIT, Set.of(2, 4), -1));
        AffinityConfig config = AffinityConfig.load(temp);
        assertEquals(Policy.Mode.EXPLICIT, config.client().mode());
        assertEquals(Set.of(2, 4), config.client().cpus());
        assertEquals(Policy.Mode.AUTO, config.server().mode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"4294967296", "-4294967296", "0.9", "1.0000000000000001", "NaN", "Infinity", "-1", "1048576"})
    void rejectsInvalidCpuNumbersWithoutChangingTheFile(String number) throws Exception {
        Path file = temp.resolve("core-affinity.json5");
        for (String content : List.of(
                "{server:{mode:'explicit',cpus:[" + number + "]}}",
                "{client:{mode:'explicit',cpus:[" + number + "]}}",
                "{groups:{main:[" + number + "]}}",
                "{groups:{shared:[" + number + "]}}",
                "{groups:{disabled:[" + number + "]}}")) {
            Files.writeString(file, content);
            assertThrows(IllegalArgumentException.class, () -> AffinityConfig.load(temp), content);
            assertEquals(content, Files.readString(file));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"4294967296", "0.9", "1.0000000000000001", "NaN", "Infinity", "-2", "1048576"})
    void rejectsInvalidCoreIndex(String number) throws Exception {
        for (String role : List.of("server", "client")) {
            Files.writeString(temp.resolve("core-affinity.json5"), "{" + role + ":{coreIndex:" + number + "}}");
            assertThrows(IllegalArgumentException.class, () -> AffinityConfig.load(temp));
        }
    }

    @Test void acceptsExactIntegerNotation() throws Exception {
        Files.writeString(temp.resolve("core-affinity.json5"),
                "{server:{mode:'explicit',cpus:[0,1.0,2e0,'3'],coreIndex:-1},groups:{main:[0]}}");
        assertEquals(Set.of(0, 1, 2, 3), AffinityConfig.load(temp).server().cpus());
    }

    @Test void concurrentPartialUpdatesPreserveBothSettings() throws Exception {
        AffinityConfig.load(temp);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> language = workers.submit(() -> {
                start.await();
                for (int i = 0; i < 500; i++) AffinityConfig.saveLanguage(temp, "zh_cn");
                return null;
            });
            Future<?> policy = workers.submit(() -> {
                start.await();
                for (int i = 0; i < 500; i++)
                    AffinityConfig.saveClientPolicy(temp, new Policy(Policy.Mode.EXPLICIT, Set.of(12), -1));
                return null;
            });
            start.countDown();
            language.get(30, TimeUnit.SECONDS);
            policy.get(30, TimeUnit.SECONDS);
        }
        AffinityConfig current = AffinityConfig.load(temp);
        assertEquals("zh_cn", current.language());
        assertEquals(Set.of(12), current.client().cpus());
        assertEquals(Policy.Mode.AUTO, current.server().mode());
    }

    @Test void failedReplacementPreservesDestinationAndRemovesTemporaryFile() throws Exception {
        Path destination = Files.createDirectory(temp.resolve("core-affinity.json5"));
        Path original = Files.writeString(destination.resolve("original"), "keep");
        assertThrows(java.io.IOException.class, () -> AffinityConfig.saveAll(temp,
                new AffinityConfig(new Policy(Policy.Mode.AUTO, Set.of(), -1), new Policy(Policy.Mode.OFF, Set.of(), -1))));
        assertEquals("keep", Files.readString(original));
        try (var files = Files.list(temp)) {
            assertEquals(List.of(destination), files.toList());
        }
    }

    @Test void failedAtomicReplacementPreservesPreviousConfiguration() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        AffinityConfig.load(temp);
        Path file = temp.resolve("core-affinity.json5");
        String original = Files.readString(file);
        // Windows permits reads/writes here but blocks rename/delete of the open destination.
        try (FileChannel ignored = FileChannel.open(file, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE)) {
            assertThrows(java.io.IOException.class, () -> AffinityConfig.saveLanguage(temp, "zh_cn"));
            assertEquals(original, Files.readString(file));
        }
        try (var files = Files.list(temp)) { assertEquals(List.of(file), files.toList()); }
    }
}
