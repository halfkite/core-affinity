package dev.bigcore;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.*;
import java.util.*;

public record AffinityConfig(Policy server, Policy client, CoreGroups groups, String language, boolean avoidSmt) {
    public AffinityConfig(Policy server, Policy client, CoreGroups groups, String language) {
        this(server, client, groups, language, false);
    }
    public AffinityConfig(Policy server, Policy client) { this(server, client, new CoreGroups(), "auto"); }
    public static final String DEFAULT = """
            # Core Affinity - use /servercore list and Confirm apply for live changes.
            # mode: auto | explicit | off
            # cpus: OS logical CPU numbers, e.g. 2 or 2,4 or 2-5 (explicit only).
            # Windows ID = processor group * 64 + group-local logical index.
            # coreIndex: -1 = all detected P cores; 0,1,... = one physical P core (auto only).
            # Unknown/homogeneous topology: auto does nothing. Use explicit if needed.
            # language: auto | zh_cn | en_us. auto checks Carpet's setting, then the JVM system locale.
            language=auto
            server.mode=auto
            server.cpus=
            server.coreIndex=-1
            server.avoidSmt=false
            client.mode=auto
            client.cpus=
            client.coreIndex=-1
            main.cpus=
            shared.cpus=
            disabled.cpus=
            """;
    public static AffinityConfig load(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve("big-core-affinity.properties");
        if (!Files.exists(file)) {
            try { Files.writeString(file, DEFAULT, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW); }
            catch (FileAlreadyExistsException ignored) { /* another instance created it */ }
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        return new AffinityConfig(read(properties, "server"), read(properties, "client"),
                new CoreGroups(CpuList.parse(properties.getProperty("main.cpus", "")),
                        CpuList.parse(properties.getProperty("shared.cpus", "")),
                        CpuList.parse(properties.getProperty("disabled.cpus", ""))),
                properties.getProperty("language", "auto").trim().toLowerCase(Locale.ROOT),
                Boolean.parseBoolean(properties.getProperty("server.avoidSmt", "false")));
    }

    public static void saveGroups(Path directory, CoreGroups groups) throws IOException {
        saveServerSettings(directory, groups, load(directory).avoidSmt());
    }

    public static void saveServerSettings(Path directory, CoreGroups groups, boolean avoidSmt) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve("big-core-affinity.properties");
        if (!Files.exists(file)) Files.writeString(file, DEFAULT, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        properties.setProperty("main.cpus", groups.mainCsv());
        properties.setProperty("shared.cpus", groups.sharedCsv());
        properties.setProperty("disabled.cpus", groups.disabledCsv());
        properties.setProperty("server.avoidSmt", Boolean.toString(avoidSmt));
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            properties.store(writer, "Core Affinity settings");
        }
    }

    public static void saveLanguage(Path directory, String language) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve("big-core-affinity.properties");
        if (!Files.exists(file)) Files.writeString(file, DEFAULT, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        properties.setProperty("language", language);
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.ISO_8859_1)) {
            properties.store(writer, "Core Affinity settings");
        }
    }
    private static Policy read(Properties p, String role) {
        return new Policy(Policy.Mode.valueOf(p.getProperty(role + ".mode", "auto").trim().toUpperCase(Locale.ROOT)),
                CpuList.parse(p.getProperty(role + ".cpus", "")),
                Integer.parseInt(p.getProperty(role + ".coreIndex", "-1").trim()));
    }
}
