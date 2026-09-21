package dev.bigcore.fabric;

import dev.bigcore.AffinityConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Resource-backed command text and language selection. */
final class ServerCoreLanguage {
    private static final String LANGUAGE_DIRECTORY = "assets/big_core_affinity/lang";
    private static final Pattern LANGUAGE_FILE = Pattern.compile("servercore_([a-z0-9_]+)\\.properties");
    private static final Pattern CARPET_LANGUAGE = Pattern.compile("(?im)^\\s*(?:language|lang)\\s*[=: ]\\s*([a-z]{2}(?:[_-][a-z]{2})?)\\s*$");
    private ServerCoreLanguage() { }

    static String resolve(AffinityConfig config, Path configDirectory) {
        String configured = normalize(config == null ? "auto" : config.language());
        if (!configured.equals("auto")) return supportedOrEnglish(configured);
        if (FabricLoader.getInstance().isModLoaded("carpet")) {
            String carpet = carpetLanguage(configDirectory);
            if (carpet != null) return supportedOrEnglish(carpet);
        }
        Locale locale = Locale.getDefault();
        String system = locale.getLanguage() + (locale.getCountry().isBlank() ? "" : "_" + locale.getCountry());
        return supportedOrEnglish(system);
    }

    static MutableText text(String language, String key, Object... args) {
        String template = value(language, key);
        return Text.literal(args.length == 0 ? template : String.format(Locale.ROOT, template, args));
    }

    static String value(String language, String key) {
        String selected = supportedOrEnglish(normalize(language));
        Properties properties = new Properties();
        String resource = "/assets/big_core_affinity/lang/servercore_" + selected + ".properties";
        try (InputStream stream = ServerCoreLanguage.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Missing language resource " + resource);
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return key;
        }
        return properties.getProperty(key, key);
    }

    static String label(String language) { return value(language, "language." + supportedOrEnglish(normalize(language))); }

    static List<String> supportedLanguages() {
        SortedSet<String> languages = new TreeSet<>();
        Optional<ModContainer> container = FabricLoader.getInstance().getModContainer("big_core_affinity");
        if (container.isPresent()) {
            Optional<Path> directory = container.get().findPath(LANGUAGE_DIRECTORY);
            if (directory.isPresent() && Files.isDirectory(directory.get())) {
                try (Stream<Path> files = Files.list(directory.get())) {
                    files.map(path -> path.getFileName().toString())
                            .map(LANGUAGE_FILE::matcher)
                            .filter(Matcher::matches)
                            .map(matcher -> matcher.group(1))
                            .forEach(languages::add);
                } catch (IOException ignored) { }
            }
        }
        if (languages.isEmpty()) languages.add("en_us");
        return List.copyOf(languages);
    }

    static boolean isSupported(String language) {
        return supportedLanguages().contains(canonical(language));
    }

    private static String carpetLanguage(Path configDirectory) {
        for (String file : List.of("carpet.conf", "carpet.properties", "carpet-language.properties")) {
            Path path = configDirectory.resolve(file);
            if (!Files.isReadable(path)) continue;
            try {
                Matcher matcher = CARPET_LANGUAGE.matcher(Files.readString(path, StandardCharsets.UTF_8));
                if (matcher.find()) return normalize(matcher.group(1));
            } catch (Exception ignored) { }
        }
        return null;
    }

    static String normalize(String language) {
        if (language == null || language.isBlank()) return "auto";
        return language.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String supportedOrEnglish(String language) {
        String canonical = canonical(language);
        return supportedLanguages().contains(canonical) ? canonical : "en_us";
    }

    private static String canonical(String language) {
        return switch (normalize(language)) {
            case "zh" -> "zh_cn";
            case "en" -> "en_us";
            default -> normalize(language);
        };
    }
}
