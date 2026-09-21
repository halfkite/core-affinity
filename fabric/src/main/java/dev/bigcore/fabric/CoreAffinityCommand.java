package dev.bigcore.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.bigcore.AffinityConfig;
import dev.bigcore.AffinityService;
import dev.bigcore.CoreGroups;
import dev.bigcore.Cpu;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class CoreAffinityCommand {
    private static final Map<UUID, String> PLAYER_LANGUAGES = new ConcurrentHashMap<>();
    private record Draft(CoreGroups groups, boolean avoidSmt, String token) { }
    private static final Map<net.minecraft.server.MinecraftServer, Draft> DRAFTS = new WeakHashMap<>();
    private CoreAffinityCommand() { }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, Path configDirectory) {
        dispatcher.register(literal("coreaffinity")
                .then(literal("help")
                        .executes(context -> help(context, configDirectory)))
                .then(literal("language")
                        .executes(context -> languageMenu(context, configDirectory))
                        .then(argument("language", StringArgumentType.word())
                                .suggests(CoreAffinityCommand::suggestLanguages)
                                .executes(context -> setPersonalLanguage(context, configDirectory)))
                        .then(literal("global")
                                .requires(source -> source.hasPermissionLevel(2))
                                .then(argument("language", StringArgumentType.word())
                                        .suggests(CoreAffinityCommand::suggestLanguages)
                                        .executes(context -> setGlobalLanguage(context, configDirectory))))
                        .then(literal("personal")
                                .then(argument("language", StringArgumentType.word())
                                        .suggests(CoreAffinityCommand::suggestLanguages)
                                        .executes(context -> setPersonalOnly(context, configDirectory)))))
                .then(literal("list")
                        .executes(context -> list(context, configDirectory)))
                .then(literal("smt")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(literal("off").executes(context -> setSmt(context, configDirectory, true)))
                        .then(literal("on").executes(context -> setSmt(context, configDirectory, false))))
                .then(literal("apply")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(argument("confirmation", StringArgumentType.word())
                                .executes(context -> apply(context, configDirectory))))
                .then(literal("assign")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(argument("cpu", IntegerArgumentType.integer(0))
                                .then(argument("group", StringArgumentType.word())
                                        .executes(context -> assign(context, configDirectory)))))
                .then(literal("disable-all")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(argument("type", StringArgumentType.word())
                                .executes(context -> disableAll(context, configDirectory)))));
    }

    private static int help(CommandContext<ServerCommandSource> context, Path directory) {
        return showHelp(context.getSource(), language(context.getSource(), directory));
    }

    private static int setPersonalLanguage(CommandContext<ServerCommandSource> context, Path directory) {
        String requested = StringArgumentType.getString(context, "language");
        String selected = CoreAffinityLanguage.normalize(requested);
        if (!CoreAffinityLanguage.isSupported(selected)) {
            context.getSource().sendError(CoreAffinityLanguage.text(language(context.getSource(), directory), "message.unknown_language", requested));
            return 0;
        }
        selected = canonicalLanguage(selected);
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player == null) return setGlobalLanguageValue(context.getSource(), directory, selected);
        PLAYER_LANGUAGES.put(player.getUuid(), selected);
        sendPersonalLanguagePrompt(context.getSource(), selected);
        return 1;
    }

    private static int languageMenu(CommandContext<ServerCommandSource> context, Path directory) {
        ServerCommandSource source = context.getSource();
        String language = language(source, directory);
        feedback(context.getSource(), CoreAffinityLanguage.text(language, "language.title").formatted(Formatting.GOLD));
        feedback(source, CoreAffinityLanguage.text(language, "language.current", languageLabel(language)));
        feedback(source, languageButtons(language));
        return 1;
    }

    private static int showHelp(ServerCommandSource source, String language) {
        feedback(source, CoreAffinityLanguage.text(language, "help.title").formatted(Formatting.GOLD));
        for (int i = 1; i <= 9; i++) {
            Text line = helpLine(language, i);
            if (line != null) feedback(source, line);
        }
        feedback(source, languageButtons(language));
        return 1;
    }

    private static Text helpLine(String language, int number) {
        String prefix = "help.line." + number;
        String command = CoreAffinityLanguage.value(language, prefix + ".command");
        String description = CoreAffinityLanguage.value(language, prefix + ".description");
        if (!command.equals(prefix + ".command") && !description.equals(prefix + ".description")) {
            String suggestion = CoreAffinityLanguage.value(language, prefix + ".suggest");
            if (suggestion.equals(prefix + ".suggest")) suggestion = command;
            String finalSuggestion = suggestion;
            return Text.literal(command).formatted(Formatting.WHITE)
                    .styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, finalSuggestion)))
                    .append(Text.literal(" # ").formatted(Formatting.GRAY))
                    .append(Text.literal(description).formatted(Formatting.GRAY));
        }
        String fallback = CoreAffinityLanguage.value(language, prefix);
        return fallback.equals(prefix) ? null : Text.literal(fallback).formatted(Formatting.GRAY);
    }

    private static Text languageButtons(String language) {
        Text result = Text.literal(CoreAffinityLanguage.value(language, "language.available") + " ");
        boolean first = true;
        for (String id : CoreAffinityLanguage.supportedLanguages()) {
            if (!first) result = result.copy().append(Text.literal(" "));
            result = result.copy().append(button(CoreAffinityLanguage.value(language, "language." + id),
                    "/coreaffinity language " + id, Formatting.YELLOW));
            first = false;
        }
        return result;
    }

    private static int setPersonalOnly(CommandContext<ServerCommandSource> context, Path directory) {
        String requested = StringArgumentType.getString(context, "language");
        String selected = CoreAffinityLanguage.normalize(requested);
        if (!CoreAffinityLanguage.isSupported(selected)) {
            context.getSource().sendError(CoreAffinityLanguage.text(language(context.getSource(), directory), "message.unknown_language", requested));
            return 0;
        }
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendError(CoreAffinityLanguage.text(language(context.getSource(), directory), "message.players_only"));
            return 0;
        }
        selected = canonicalLanguage(selected);
        PLAYER_LANGUAGES.put(player.getUuid(), selected);
        feedback(context.getSource(), CoreAffinityLanguage.text(selected, "language.personal_set", languageLabel(selected)));
        return 1;
    }

    private static int setGlobalLanguage(CommandContext<ServerCommandSource> context, Path directory) {
        String requested = StringArgumentType.getString(context, "language");
        String selected = CoreAffinityLanguage.normalize(requested);
        if (!CoreAffinityLanguage.isSupported(selected)) {
            context.getSource().sendError(CoreAffinityLanguage.text(language(context.getSource(), directory), "message.unknown_language", requested));
            return 0;
        }
        return setGlobalLanguageValue(context.getSource(), directory, canonicalLanguage(selected));
    }

    private static int setGlobalLanguageValue(ServerCommandSource source, Path directory, String selected) {
        try {
            AffinityConfig.saveLanguage(directory, selected);
            ServerPlayerEntity player = source.getPlayer();
            if (player != null) PLAYER_LANGUAGES.put(player.getUuid(), selected);
            feedback(source, CoreAffinityLanguage.text(selected, "language.global_set", languageLabel(selected)));
            return 1;
        } catch (Exception e) {
            source.sendError(CoreAffinityLanguage.text(language(source, directory), "message.command_error", e.getMessage()));
            return 0;
        }
    }

    private static void sendPersonalLanguagePrompt(ServerCommandSource source, String selected) {
        feedback(source, CoreAffinityLanguage.text(selected, "language.personal_set", languageLabel(selected)));
        Text prompt = CoreAffinityLanguage.text(selected, "language.default_prompt", languageLabel(selected));
        if (source.hasPermissionLevel(2)) {
            prompt = prompt.copy()
                    .append(Text.literal(" "))
                    .append(button(CoreAffinityLanguage.value(selected, "language.default_button"),
                            "/coreaffinity language global " + selected, Formatting.GOLD));
        } else {
            prompt = prompt.copy().append(Text.literal(" "))
                    .append(CoreAffinityLanguage.text(selected, "language.default_permission"));
        }
        prompt = prompt.copy().append(Text.literal(" "))
                .append(button(CoreAffinityLanguage.value(selected, "language.personal_button"),
                        "/coreaffinity language personal " + selected, Formatting.GRAY));
        feedback(source, prompt);
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestLanguages(
            CommandContext<ServerCommandSource> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        for (String id : CoreAffinityLanguage.supportedLanguages()) builder.suggest(id);
        return builder.buildFuture();
    }

    private static String canonicalLanguage(String language) {
        if (language.equals("zh")) return "zh_cn";
        if (language.equals("en")) return "en_us";
        return language;
    }

    private static String languageLabel(String language) {
        return CoreAffinityLanguage.value(language, "language." + language);
    }

    private static int list(CommandContext<ServerCommandSource> context, Path directory) {
        ServerCommandSource source = context.getSource();
        AffinityService service = BigCoreFabric.service;
        List<Cpu> topology = service == null ? List.of() : service.topology();
        try {
            AffinityConfig config = AffinityConfig.load(directory);
            String language = language(source, directory, config);
            if (topology.isEmpty()) {
                source.sendError(CoreAffinityLanguage.text(language, "message.no_topology"));
                return 0;
            }
            CoreGroups groups = draftGroups(source, config);
            feedback(source, CoreAffinityLanguage.text(language, "list.title").formatted(Formatting.GOLD));
            feedback(source, groupSummary(language, "main", groups.main(), topology, Formatting.GREEN));
            feedback(source, groupSummary(language, "shared", groups.shared(), topology, Formatting.AQUA));
            feedback(source, groupSummary(language, "disabled", groups.disabled(), topology, Formatting.RED));
            Set<Integer> assigned = new HashSet<>(groups.main());
            assigned.addAll(groups.shared());
            assigned.addAll(groups.disabled());
            List<Cpu> unassigned = topology.stream().filter(cpu -> !assigned.contains(cpu.id())).toList();
            feedback(source, Text.literal(CoreAffinityLanguage.value(language, "group.unassigned") + ": ").formatted(Formatting.GRAY)
                    .append(unassigned.stream().sorted(Comparator.comparingInt(Cpu::id)).map(cpu -> coreName(cpu, topology))
                            .reduce((a, b) -> a + "," + b).map(Text::literal).orElse(Text.literal(""))));
            for (Cpu cpu : topology.stream().sorted(Comparator.comparingInt(Cpu::id)).toList()) {
                CoreGroups.Group current = groups.groupOf(cpu.id());
                Text row = Text.literal(String.format(Locale.ROOT, "%-4s", coreName(cpu, topology)))
                        .append(button(CoreAffinityLanguage.value(language, "button.main"), "/coreaffinity assign " + cpu.id() + " main",
                                current == CoreGroups.Group.MAIN ? Formatting.BLUE : Formatting.GRAY))
                        .append(Text.literal(" "))
                        .append(button(CoreAffinityLanguage.value(language, "button.shared"), "/coreaffinity assign " + cpu.id() + " shared",
                                current == CoreGroups.Group.SHARED ? Formatting.BLUE : Formatting.GRAY))
                        .append(Text.literal(" "))
                        .append(button(CoreAffinityLanguage.value(language, "button.disabled"), "/coreaffinity assign " + cpu.id() + " disabled",
                                current == CoreGroups.Group.DISABLED ? Formatting.BLUE : Formatting.GRAY))
                        .append(Text.literal(" "))
                        .append(button(CoreAffinityLanguage.value(language, "button.unassigned"), "/coreaffinity assign " + cpu.id() + " unassigned",
                                current == CoreGroups.Group.UNASSIGNED ? Formatting.BLUE : Formatting.GRAY));
                feedback(source, row);
            }
            Text actions = Text.literal(CoreAffinityLanguage.value(language, "list.actions") + " ")
                    .append(button(CoreAffinityLanguage.value(language, "button.disable_p"), "/coreaffinity disable-all p", Formatting.RED))
                    .append(Text.literal(" "))
                    .append(button(CoreAffinityLanguage.value(language, "button.disable_e"), "/coreaffinity disable-all e", Formatting.RED))
                    .append(Text.literal(" "))
                    .append(button(CoreAffinityLanguage.value(language, "button.refresh"), "/coreaffinity list", Formatting.YELLOW));
            feedback(source, actions);
            feedback(source, CoreAffinityLanguage.text(language, "smt.status",
                    CoreAffinityLanguage.value(language, draftAvoidSmt(source, config) ? "smt.off" : "smt.on"))
                    .append(Text.literal(" "))
                    .append(button(CoreAffinityLanguage.value(language, "smt.disable"), "/coreaffinity smt off",
                            draftAvoidSmt(source, config) ? Formatting.BLUE : Formatting.GRAY))
                    .append(Text.literal(" "))
                    .append(button(CoreAffinityLanguage.value(language, "smt.enable"), "/coreaffinity smt on",
                            draftAvoidSmt(source, config) ? Formatting.GRAY : Formatting.BLUE)));
            feedback(source, CoreAffinityLanguage.text(language, "smt.scope").formatted(Formatting.GRAY));
            Draft draft = DRAFTS.get(source.getServer());
            if (draft != null) {
                feedback(source, CoreAffinityLanguage.text(language, "message.pending").formatted(Formatting.YELLOW));
                if (source.hasPermissionLevel(2)) feedback(source, button(CoreAffinityLanguage.value(language, "button.apply"),
                        "/coreaffinity apply " + draft.token(), Formatting.GREEN));
            }
            return 1;
        } catch (Exception e) {
            source.sendError(CoreAffinityLanguage.text(language(source, directory), "message.config_error", e.getMessage()));
            return 0;
        }
    }

    private static int assign(CommandContext<ServerCommandSource> context, Path directory) {
        ServerCommandSource source = context.getSource();
        int cpu = IntegerArgumentType.getInteger(context, "cpu");
        String language = language(source, directory);
        String groupArgument = StringArgumentType.getString(context, "group");
        CoreGroups.Group group;
        try {
            group = CoreGroups.Group.parse(groupArgument);
        } catch (IllegalArgumentException ignored) {
            source.sendError(CoreAffinityLanguage.text(language, "message.group_error", groupArgument));
            return 0;
        }
        try {
            AffinityConfig config = AffinityConfig.load(directory);
            if (draftGroups(source, config).groupOf(cpu) == group) return list(context, directory);
            if (BigCoreFabric.service == null || BigCoreFabric.service.topology().stream().noneMatch(c -> c.id() == cpu)) {
                source.sendError(CoreAffinityLanguage.text(language, "message.cpu_missing", cpu));
                return 0;
            }
            CoreGroups next = draftGroups(source, config).assign(cpu, group);
            DRAFTS.put(source.getServer(), new Draft(next, draftAvoidSmt(source, config), UUID.randomUUID().toString()));
            feedback(source, CoreAffinityLanguage.text(language, "message.assigned", cpu,
                    CoreAffinityLanguage.value(language, "group." + group.name().toLowerCase(Locale.ROOT)))
                    .formatted(Formatting.GREEN));
            return list(context, directory);
        } catch (Exception e) {
            source.sendError(CoreAffinityLanguage.text(language, "message.command_error", e.getMessage()));
            return 0;
        }
    }

    private static int disableAll(CommandContext<ServerCommandSource> context, Path directory) {
        String language = language(context.getSource(), directory);
        String type = StringArgumentType.getString(context, "type").toLowerCase(Locale.ROOT);
        if (!type.equals("p") && !type.equals("e")) {
            context.getSource().sendError(CoreAffinityLanguage.text(language, "message.type_error"));
            return 0;
        }
        try {
            if (BigCoreFabric.service == null || BigCoreFabric.service.topology().isEmpty()) {
                context.getSource().sendError(CoreAffinityLanguage.text(language, "message.no_topology"));
                return 0;
            }
            AffinityConfig config = AffinityConfig.load(directory);
            CoreGroups next = draftGroups(context.getSource(), config).disableMatching(BigCoreFabric.service.topology(), type.equals("p"));
            DRAFTS.put(context.getSource().getServer(), new Draft(next, draftAvoidSmt(context.getSource(), config), UUID.randomUUID().toString()));
            feedback(context.getSource(), CoreAffinityLanguage.text(language, "message.disabled_all", type.toUpperCase(Locale.ROOT))
                    .formatted(Formatting.GREEN));
            return list(context, directory);
        } catch (Exception e) {
            context.getSource().sendError(CoreAffinityLanguage.text(language, "message.command_error", e.getMessage()));
            return 0;
        }
    }

    private static CoreGroups draftGroups(ServerCommandSource source, AffinityConfig config) {
        Draft draft = DRAFTS.get(source.getServer());
        return draft == null ? config.groups() : draft.groups();
    }

    private static boolean draftAvoidSmt(ServerCommandSource source, AffinityConfig config) {
        Draft draft = DRAFTS.get(source.getServer());
        return draft == null ? config.avoidSmt() : draft.avoidSmt();
    }

    private static int setSmt(CommandContext<ServerCommandSource> context, Path directory, boolean avoidSmt) {
        ServerCommandSource source = context.getSource();
        try {
            AffinityConfig config = AffinityConfig.load(directory);
            DRAFTS.put(source.getServer(), new Draft(draftGroups(source, config), avoidSmt, UUID.randomUUID().toString()));
            return list(context, directory);
        } catch (Exception e) {
            source.sendError(CoreAffinityLanguage.text(language(source, directory), "message.apply_failed"));
            return 0;
        }
    }

    private static int apply(CommandContext<ServerCommandSource> context, Path directory) {
        ServerCommandSource source = context.getSource();
        String language = language(source, directory);
        Draft draft = DRAFTS.get(source.getServer());
        if (draft == null || !draft.token().equals(StringArgumentType.getString(context, "confirmation"))) {
            source.sendError(CoreAffinityLanguage.text(language, "message.stale"));
            return 0;
        }
        if (!source.getServer().isOnThread()) {
            source.getServer().execute(() -> apply(context, directory));
            return 1;
        }
        try {
            List<Integer> actual = BigCoreFabric.service.applyServerGroups(draft.groups(), draft.avoidSmt(), directory);
            DRAFTS.remove(source.getServer());
            feedback(source, CoreAffinityLanguage.text(language, "message.applied", actual.toString()).formatted(Formatting.GREEN));
            return 1;
        } catch (Exception | LinkageError e) {
            org.slf4j.LoggerFactory.getLogger("CoreAffinity").error("Could not apply server affinity", e);
            source.sendError(CoreAffinityLanguage.text(language, "message.apply_failed"));
            return 0;
        }
    }

    private static Text groupSummary(String language, String group, Set<Integer> ids, List<Cpu> topology, Formatting color) {
        String value = ids.stream().sorted().map(id -> topology.stream().filter(cpu -> cpu.id() == id).findFirst()
                .map(cpu -> coreName(cpu, topology)).orElse(String.valueOf(id))).reduce((a, b) -> a + "," + b).orElse("");
        return Text.literal(CoreAffinityLanguage.value(language, "group." + group) + ":" + value).formatted(color);
    }

    private static String coreName(Cpu cpu, List<Cpu> topology) {
        int max = topology.stream().mapToInt(Cpu::performanceClass).filter(v -> v >= 0).max().orElse(-1);
        int min = topology.stream().mapToInt(Cpu::performanceClass).filter(v -> v >= 0).min().orElse(-1);
        String kind = cpu.performanceClass() < 0 ? "?" : (max > min && cpu.performanceClass() == min ? "E" : "P");
        return cpu.id() + kind;
    }

    private static Text button(String label, String command, Formatting color) {
        return Text.literal("[" + label + "]").formatted(color)
                .styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
    }

    private static void feedback(ServerCommandSource source, Text text) { source.sendFeedback(() -> text, false); }
    private static String language(Path directory) {
        try { return language(directory, AffinityConfig.load(directory)); }
        catch (Exception ignored) { return "en_us"; }
    }
    private static String language(ServerCommandSource source, Path directory) {
        try { return language(source, directory, AffinityConfig.load(directory)); }
        catch (Exception ignored) { return "en_us"; }
    }
    private static String language(ServerCommandSource source, Path directory, AffinityConfig config) {
        String resolved = CoreAffinityLanguage.resolve(config, directory);
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return resolved;
        return PLAYER_LANGUAGES.getOrDefault(player.getUuid(), resolved);
    }
    private static String language(Path directory, AffinityConfig config) { return CoreAffinityLanguage.resolve(config, directory); }
}
