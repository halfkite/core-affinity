package dev.bigcore;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/** JSON5-backed configuration with migration from the original properties file. */
public record AffinityConfig(Policy server, Policy client, CoreGroups groups, String language, boolean avoidSmt) {
    private static final String FILE_NAME = "core-affinity.json5";
    private static final String LEGACY_FILE_NAME = "big-core-affinity.properties";

    public AffinityConfig(Policy server, Policy client, CoreGroups groups, String language) {
        this(server, client, groups, language, false);
    }

    public AffinityConfig(Policy server, Policy client) {
        this(server, client, new CoreGroups(), "auto");
    }

    public static final String DEFAULT = """
            {
              // Core Affinity configuration / Core Affinity 配置文件
              "language": "auto", // Chat language: auto follows config, Carpet, then system / 聊天语言：auto 依次跟随本模组、Carpet、系统语言
              "server": {
                "mode": "auto", // Server main thread: auto=P cores, explicit=cpus, off=disabled / 服务端主线程：auto=大核，explicit=按 cpus，off=关闭
                "cpus": [], // Logical CPU IDs used only by explicit mode / 仅 explicit 模式使用的逻辑 CPU 编号
                "coreIndex": -1, // In auto mode: -1=all P cores, 0+=one physical P core / auto 模式：-1=全部 P 核，0+=指定一个物理 P 核
                "avoidSmt": false // true keeps one logical thread per physical core; SMT hardware stays on / true=每个物理核保留一个逻辑线程；不关闭硬件超线程
              },
              "client": {
                "mode": "auto", // Client main thread: auto=P cores, explicit=cpus, off=disabled / 客户端主线程：auto=大核，explicit=按 cpus，off=关闭
                "cpus": [], // Logical CPU IDs used only by explicit mode / 仅 explicit 模式使用的逻辑 CPU 编号
                "coreIndex": -1 // In auto mode: -1=all P cores, 0+=one physical P core / auto 模式：-1=全部 P 核，0+=指定一个物理 P 核
              },
              "groups": {
                "main": [], // CPUs used by the server main thread after Confirm apply / 点击确认应用后供服务端主线程使用的 CPU
                "shared": [], // Reserved for future worker-thread affinity; currently informational / 预留给后续工作线程绑核；当前仅记录和显示
                "disabled": [] // CPUs rejected by automatic selection and group application / 自动选核和分组应用时排除的 CPU
              }
            }
            """;

    public static AffinityConfig load(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            Path legacy = directory.resolve(LEGACY_FILE_NAME);
            AffinityConfig migrated = Files.exists(legacy) ? loadLegacy(legacy) : defaults();
            write(directory, migrated);
            return migrated;
        }
        try {
            return fromJson5(Json5.parse(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid JSON5 configuration: " + file, e);
        }
    }

    public static void saveGroups(Path directory, CoreGroups groups) throws IOException {
        AffinityConfig current = load(directory);
        write(directory, new AffinityConfig(current.server(), current.client(), groups,
                current.language(), current.avoidSmt()));
    }

    public static void saveServerSettings(Path directory, CoreGroups groups, boolean avoidSmt) throws IOException {
        AffinityConfig current = load(directory);
        write(directory, new AffinityConfig(current.server(), current.client(), groups,
                current.language(), avoidSmt));
    }

    public static void saveLanguage(Path directory, String language) throws IOException {
        AffinityConfig current = load(directory);
        write(directory, new AffinityConfig(current.server(), current.client(), current.groups(),
                language, current.avoidSmt()));
    }

    public static void saveClientPolicy(Path directory, Policy client) throws IOException {
        AffinityConfig current = load(directory);
        write(directory, new AffinityConfig(current.server(), client, current.groups(),
                current.language(), current.avoidSmt()));
    }

    private static AffinityConfig defaults() {
        return new AffinityConfig(new Policy(Policy.Mode.AUTO, Set.of(), -1),
                new Policy(Policy.Mode.AUTO, Set.of(), -1), new CoreGroups(), "auto", false);
    }

    private static AffinityConfig loadLegacy(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return new AffinityConfig(readLegacyPolicy(properties, "server"), readLegacyPolicy(properties, "client"),
                new CoreGroups(CpuList.parse(properties.getProperty("main.cpus", "")),
                        CpuList.parse(properties.getProperty("shared.cpus", "")),
                        CpuList.parse(properties.getProperty("disabled.cpus", ""))),
                properties.getProperty("language", "auto").trim().toLowerCase(Locale.ROOT),
                Boolean.parseBoolean(properties.getProperty("server.avoidSmt", "false")));
    }

    private static Policy readLegacyPolicy(Properties properties, String role) {
        return new Policy(Policy.Mode.valueOf(properties.getProperty(role + ".mode", "auto")
                        .trim().toUpperCase(Locale.ROOT)),
                CpuList.parse(properties.getProperty(role + ".cpus", "")),
                Integer.parseInt(properties.getProperty(role + ".coreIndex", "-1").trim()));
    }

    private static AffinityConfig fromJson5(Object value) {
        Map<String, Object> root = object(value, "root");
        Map<String, Object> server = object(root.getOrDefault("server", Map.of()), "server");
        Map<String, Object> client = object(root.getOrDefault("client", Map.of()), "client");
        Map<String, Object> groups = object(root.getOrDefault("groups", Map.of()), "groups");
        return new AffinityConfig(readPolicy(server, "server"), readPolicy(client, "client"),
                new CoreGroups(readCpus(groups.getOrDefault("main", List.of()), "groups.main"),
                        readCpus(groups.getOrDefault("shared", List.of()), "groups.shared"),
                        readCpus(groups.getOrDefault("disabled", List.of()), "groups.disabled")),
                string(root.getOrDefault("language", "auto"), "language").trim().toLowerCase(Locale.ROOT),
                bool(server.getOrDefault("avoidSmt", false), "server.avoidSmt"));
    }

    private static Policy readPolicy(Map<String, Object> values, String role) {
        String modeText = string(values.getOrDefault("mode", "auto"), role + ".mode");
        Set<Integer> cpus = readCpus(values.getOrDefault("cpus", List.of()), role + ".cpus");
        int coreIndex = number(values.getOrDefault("coreIndex", -1), role + ".coreIndex").intValue();
        return new Policy(Policy.Mode.valueOf(modeText.trim().toUpperCase(Locale.ROOT)), cpus, coreIndex);
    }

    private static Set<Integer> readCpus(Object value, String field) {
        if (value instanceof String text) return CpuList.parse(text);
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException(field + " must be an array");
        Set<Integer> result = new LinkedHashSet<>();
        for (Object item : list) result.add(number(item, field).intValue());
        return Set.copyOf(result);
    }

    private static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(field + " must be an object");
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static Number number(Object value, String field) {
        if (value instanceof Number number) return number;
        if (value instanceof String text) {
            try { return Integer.valueOf(text.trim()); }
            catch (NumberFormatException ignored) { }
        }
        throw new IllegalArgumentException(field + " must be a number");
    }

    private static String string(Object value, String field) {
        if (value instanceof String text) return text;
        throw new IllegalArgumentException(field + " must be a string");
    }

    private static boolean bool(Object value, String field) {
        if (value instanceof Boolean result) return result;
        if (value instanceof String text) return Boolean.parseBoolean(text.trim());
        throw new IllegalArgumentException(field + " must be true or false");
    }

    private static void write(Path directory, AffinityConfig config) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(FILE_NAME), render(config), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static String render(AffinityConfig config) {
        Policy server = config.server();
        Policy client = config.client();
        return "{\n" +
                "  // Core Affinity configuration / Core Affinity 配置文件\n" +
                "  \"language\": \"" + escape(config.language()) + "\", // Chat language: auto follows config, Carpet, then system / 聊天语言：auto 依次跟随本模组、Carpet、系统语言\n" +
                "  \"server\": {\n" +
                "    \"mode\": \"" + server.mode().name().toLowerCase(Locale.ROOT) + "\", // Server main thread: auto=P cores, explicit=cpus, off=disabled / 服务端主线程：auto=大核，explicit=按 cpus，off=关闭\n" +
                "    \"cpus\": " + array(server.cpus()) + ", // Logical CPU IDs used only by explicit mode / 仅 explicit 模式使用的逻辑 CPU 编号\n" +
                "    \"coreIndex\": " + server.coreIndex() + ", // In auto mode: -1=all P cores, 0+=one physical P core / auto 模式：-1=全部 P 核，0+=指定一个物理 P 核\n" +
                "    \"avoidSmt\": " + config.avoidSmt() + " // true keeps one logical thread per physical core; SMT hardware stays on / true=每个物理核保留一个逻辑线程；不关闭硬件超线程\n" +
                "  },\n" +
                "  \"client\": {\n" +
                "    \"mode\": \"" + client.mode().name().toLowerCase(Locale.ROOT) + "\", // Client main thread: auto=P cores, explicit=cpus, off=disabled / 客户端主线程：auto=大核，explicit=按 cpus，off=关闭\n" +
                "    \"cpus\": " + array(client.cpus()) + ", // Logical CPU IDs used only by explicit mode / 仅 explicit 模式使用的逻辑 CPU 编号\n" +
                "    \"coreIndex\": " + client.coreIndex() + " // In auto mode: -1=all P cores, 0+=one physical P core / auto 模式：-1=全部 P 核，0+=指定一个物理 P 核\n" +
                "  },\n" +
                "  \"groups\": {\n" +
                "    \"main\": " + array(config.groups().main()) + ", // CPUs used by the server main thread after Confirm apply / 点击确认应用后供服务端主线程使用的 CPU\n" +
                "    \"shared\": " + array(config.groups().shared()) + ", // Reserved for future worker-thread affinity; currently informational / 预留给后续工作线程绑核；当前仅记录和显示\n" +
                "    \"disabled\": " + array(config.groups().disabled()) + " // CPUs rejected by automatic selection and group application / 自动选核和分组应用时排除的 CPU\n" +
                "  }\n" +
                "}\n";
    }

    private static String array(Set<Integer> values) {
        return values.stream().sorted().map(String::valueOf).reduce((a, b) -> a + ", " + b)
                .map(value -> "[" + value + "]").orElse("[]");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
