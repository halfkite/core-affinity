package dev.bigcore.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.bigcore.AffinityConfig;
import dev.bigcore.AffinityService;
import dev.bigcore.CoreGroups;
import dev.bigcore.Cpu;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class CoreAffinityCommand {
    private static final Map<UUID, String> PLAYER_LANGUAGES = new ConcurrentHashMap<>();
    private record Draft(CoreGroups groups, boolean avoidSmt, String token) { }
    private static final Map<net.minecraft.server.MinecraftServer, Draft> DRAFTS = new WeakHashMap<>();
    private CoreAffinityCommand() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Path configDirectory) {
        dispatcher.register(literal("coreaffinity")
                .then(literal("help")
                        .executes(context -> help(context, configDirectory)))
                .then(literal("language")
                        .executes(context -> languageMenu(context, configDirectory))
                        .then(argument("language", StringArgumentType.word())
                                .suggests(CoreAffinityCommand::suggestLanguages)
                                .executes(context -> setPersonalLanguage(context, configDirectory)))
                        .then(literal("global")
                                .requires(source -> net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
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
                        .requires(source -> net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
                        .then(literal("off").executes(context -> setSmt(context, configDirectory, true)))
                        .then(literal("on").executes(context -> setSmt(context, configDirectory, false))))
                .then(literal("apply")
                        .requires(source -> net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
                        .executes(context -> applyCurrent(context, configDirectory))
                        .then(argument("confirmation", StringArgumentType.word())
                                .executes(context -> apply(context, configDirectory))))
                .then(literal("assign")
                        .requires(source -> net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
                        .then(argument("cpu", IntegerArgumentType.integer(0))
                                .then(argument("group", StringArgumentType.word())
                                        .executes(context -> assign(context, configDirectory)))))
                .then(literal("disable-all")
                        .requires(source -> net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
                        .then(argument("type", StringArgumentType.word())
                                .executes(context -> disableAll(context, configDirectory))))
                .then(literal("assign-all")
                        .requires(source -> net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
                        .then(argument("type", StringArgumentType.word())
                                .then(argument("group", StringArgumentType.word())
                                        .executes(context -> assignAll(context, configDirectory))))));
    }

    private static int help(CommandContext<CommandSourceStack> context, Path directory) {
        return showHelp(context.getSource(), language(context.getSource(), directory));
    }

    private static int setPersonalLanguage(CommandContext<CommandSourceStack> context, Path directory) {
        String requested = StringArgumentType.getString(context, "language");
        String selected = CoreAffinityLanguage.normalize(requested);
        if (!CoreAffinityLanguage.isSupported(selected)) {
            context.getSource().sendFailure(CoreAffinityLanguage.text(language(context.getSource(), directory), "message.unknown_language", requested));
            return 0;
        }
        selected = canonicalLanguage(selected);
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return setGlobalLanguageValue(context.getSource(), directory, selected);
        PLAYER_LANGUAGES.put(player.getUUID(), selected);
        sendPersonalLanguagePrompt(context.getSource(), selected);
        return 1;
    }

    private static int languageMenu(CommandContext<CommandSourceStack> context, Path directory) {
        CommandSourceStack source = context.getSource();
        String language = language(source, directory);
        feedback(context.getSource(), CoreAffinityLanguage.text(language, "language.title").withStyle(ChatFormatting.GOLD));
        feedback(source, CoreAffinityLanguage.text(language, "language.current", languageLabel(language)));
        feedback(source, languageButtons(language));
        return 1;
    }

    private static int showHelp(CommandSourceStack source, String language) {
        feedback(source, CoreAffinityLanguage.text(language, "help.title").withStyle(ChatFormatting.GOLD));
        int commandWidth = helpCommandWidth(language);
        for (int i = 1; i <= 9; i++) {
            Component line = helpLine(language, i, commandWidth);
            if (line != null) feedback(source, line);
        }
        feedback(source, languageButtons(language));
        return 1;
    }

    private static int helpCommandWidth(String language) {
        int width = 0;
        for (int number = 1; number <= 9; number++) {
            String prefix = "help.line." + number;
            String command = CoreAffinityLanguage.value(language, prefix + ".command");
            String description = CoreAffinityLanguage.value(language, prefix + ".description");
            if (!command.equals(prefix + ".command") && !description.equals(prefix + ".description")) {
                width = Math.max(width, displayWidth(command));
            }
        }
        return width;
    }

    private static int displayWidth(String value) {
        return value.codePoints().map(codePoint -> codePoint > 0xFF ? 2 : 1).sum();
    }

    private static Component helpLine(String language, int number, int commandWidth) {
        String prefix = "help.line." + number;
        String command = CoreAffinityLanguage.value(language, prefix + ".command");
        String description = CoreAffinityLanguage.value(language, prefix + ".description");
        if (!command.equals(prefix + ".command") && !description.equals(prefix + ".description")) {
            String suggestion = CoreAffinityLanguage.value(language, prefix + ".suggest");
            if (suggestion.equals(prefix + ".suggest")) suggestion = command;
            String finalSuggestion = suggestion;
            int padding = Math.max(1, commandWidth - displayWidth(command) + 1);
            return Component.literal(command).withStyle(ChatFormatting.WHITE)
                    .withStyle(style -> style.withClickEvent(new ClickEvent.SuggestCommand(finalSuggestion)))
                    .append(Component.literal(" ".repeat(padding) + "# ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(description).withStyle(ChatFormatting.GRAY));
        }
        String fallback = CoreAffinityLanguage.value(language, prefix);
        return fallback.equals(prefix) ? null : Component.literal(fallback).withStyle(ChatFormatting.GRAY);
    }

    private static Component languageButtons(String language) {
        Component result = Component.literal(CoreAffinityLanguage.value(language, "language.available") + " ");
        boolean first = true;
        for (String id : CoreAffinityLanguage.supportedLanguages()) {
            if (!first) result = result.copy().append(Component.literal(" "));
            result = result.copy().append(button(CoreAffinityLanguage.value(language, "language." + id),
                    "/coreaffinity language " + id, ChatFormatting.YELLOW));
            first = false;
        }
        return result;
    }

    private static int setPersonalOnly(CommandContext<CommandSourceStack> context, Path directory) {
        String requested = StringArgumentType.getString(context, "language");
        String selected = CoreAffinityLanguage.normalize(requested);
        if (!CoreAffinityLanguage.isSupported(selected)) {
            context.getSource().sendFailure(CoreAffinityLanguage.text(language(context.getSource(), directory), "message.unknown_language", requested));
            return 0;
        }
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(CoreAffinityLanguage.text(language(context.getSource(), directory), "message.players_only"));
            return 0;
        }
        selected = canonicalLanguage(selected);
        PLAYER_LANGUAGES.put(player.getUUID(), selected);
        feedback(context.getSource(), CoreAffinityLanguage.text(selected, "language.personal_set", languageLabel(selected)));
        return 1;
    }

    private static int setGlobalLanguage(CommandContext<CommandSourceStack> context, Path directory) {
        String requested = StringArgumentType.getString(context, "language");
        String selected = CoreAffinityLanguage.normalize(requested);
        if (!CoreAffinityLanguage.isSupported(selected)) {
            context.getSource().sendFailure(CoreAffinityLanguage.text(language(context.getSource(), directory), "message.unknown_language", requested));
            return 0;
        }
        return setGlobalLanguageValue(context.getSource(), directory, canonicalLanguage(selected));
    }

    private static int setGlobalLanguageValue(CommandSourceStack source, Path directory, String selected) {
        try {
            AffinityConfig.saveLanguage(directory, selected);
            ServerPlayer player = source.getPlayer();
            if (player != null) PLAYER_LANGUAGES.put(player.getUUID(), selected);
            feedback(source, CoreAffinityLanguage.text(selected, "language.global_set", languageLabel(selected)));
            return 1;
        } catch (Exception e) {
            source.sendFailure(CoreAffinityLanguage.text(language(source, directory), "message.command_error", e.getMessage()));
            return 0;
        }
    }

    private static void sendPersonalLanguagePrompt(CommandSourceStack source, String selected) {
        feedback(source, CoreAffinityLanguage.text(selected, "language.personal_set", languageLabel(selected)));
        Component prompt = CoreAffinityLanguage.text(selected, "language.default_prompt", languageLabel(selected));
        if (net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(source.permissions())) {
            prompt = prompt.copy()
                    .append(Component.literal(" "))
                    .append(button(CoreAffinityLanguage.value(selected, "language.default_button"),
                            "/coreaffinity language global " + selected, ChatFormatting.GOLD));
        } else {
            prompt = prompt.copy().append(Component.literal(" "))
                    .append(CoreAffinityLanguage.text(selected, "language.default_permission"));
        }
        prompt = prompt.copy().append(Component.literal(" "))
                .append(button(CoreAffinityLanguage.value(selected, "language.personal_button"),
                        "/coreaffinity language personal " + selected, ChatFormatting.GRAY));
        feedback(source, prompt);
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestLanguages(
            CommandContext<CommandSourceStack> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
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

    private static int list(CommandContext<CommandSourceStack> context, Path directory) {
        CommandSourceStack source = context.getSource();
        AffinityService service = BigCoreFabric.service;
        List<Cpu> topology = service == null ? List.of() : service.topology();
        try {
            AffinityConfig config = AffinityConfig.load(directory);
            String language = language(source, directory, config);
            if (topology.isEmpty()) {
                source.sendFailure(CoreAffinityLanguage.text(language, "message.no_topology"));
                return 0;
            }
            CoreGroups groups = draftGroups(source, config);
            feedback(source, CoreAffinityLanguage.text(language, "list.title").withStyle(ChatFormatting.GOLD));
            feedback(source, CoreAffinityLanguage.text(language, "list.main_help").withStyle(ChatFormatting.GRAY));
            feedback(source, CoreAffinityLanguage.text(language, "list.disabled_help").withStyle(ChatFormatting.GRAY));
            feedback(source, CoreAffinityLanguage.text(language, "list.unassigned_help").withStyle(ChatFormatting.GRAY));
            feedback(source, CoreAffinityLanguage.text(language, "list.pe_help").withStyle(ChatFormatting.GRAY));
            List<Cpu> sortedTopology = topology.stream().sorted(Comparator.comparingInt(Cpu::id)).toList();
            int labelWidth = sortedTopology.stream().mapToInt(cpu -> coreName(cpu, topology).length()).max().orElse(1);
            for (Cpu cpu : sortedTopology) {
                CoreGroups.Group current = groups.groupOf(cpu.id());
                if (current == CoreGroups.Group.SHARED) current = CoreGroups.Group.UNASSIGNED;
                String label = coreName(cpu, topology);
                label += " ".repeat(Math.max(0, labelWidth - label.length()));
                Component row = Component.literal(label + ":")
                        .append(button(CoreAffinityLanguage.value(language, "button.main"), "/coreaffinity assign " + cpu.id() + " main",
                                current == CoreGroups.Group.MAIN ? ChatFormatting.BLUE : ChatFormatting.GRAY))
                        .append(button(CoreAffinityLanguage.value(language, "button.disabled"), "/coreaffinity assign " + cpu.id() + " disabled",
                                current == CoreGroups.Group.DISABLED ? ChatFormatting.BLUE : ChatFormatting.GRAY))
                        .append(button(CoreAffinityLanguage.value(language, "button.unassigned"), "/coreaffinity assign " + cpu.id() + " unassigned",
                                current == CoreGroups.Group.UNASSIGNED ? ChatFormatting.BLUE : ChatFormatting.GRAY));
                feedback(source, row);
            }
            feedback(source, Component.literal(CoreAffinityLanguage.value(language, "list.actions")).withStyle(ChatFormatting.GOLD));
            feedback(source, quickActionRow(language, "button.main_p", "assign-all p main", "button.main_e", "assign-all e main", ChatFormatting.GREEN));
            feedback(source, quickActionRow(language, "button.disabled_p", "assign-all p disabled", "button.disabled_e", "assign-all e disabled", ChatFormatting.RED));
            feedback(source, quickActionRow(language, "button.unassigned_p", "assign-all p unassigned", "button.unassigned_e", "assign-all e unassigned", ChatFormatting.GRAY));
            if (DRAFTS.containsKey(source.getServer())) feedback(source, CoreAffinityLanguage.text(language, "message.pending").withStyle(ChatFormatting.YELLOW));
            if (net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(source.permissions())) feedback(source, button(CoreAffinityLanguage.value(language, "button.apply"),
                    "/coreaffinity apply", ChatFormatting.GREEN));
            return 1;
        } catch (Exception e) {
            source.sendFailure(CoreAffinityLanguage.text(language(source, directory), "message.config_error", e.getMessage()));
            return 0;
        }
    }

    private static int assign(CommandContext<CommandSourceStack> context, Path directory) {
        CommandSourceStack source = context.getSource();
        int cpu = IntegerArgumentType.getInteger(context, "cpu");
        String language = language(source, directory);
        String groupArgument = StringArgumentType.getString(context, "group");
        CoreGroups.Group group;
        try {
            group = CoreGroups.Group.parse(groupArgument);
        } catch (IllegalArgumentException ignored) {
            source.sendFailure(CoreAffinityLanguage.text(language, "message.group_error", groupArgument));
            return 0;
        }
        try {
            AffinityConfig config = AffinityConfig.load(directory);
            if (draftGroups(source, config).groupOf(cpu) == group) return list(context, directory);
            if (BigCoreFabric.service == null || BigCoreFabric.service.topology().stream().noneMatch(c -> c.id() == cpu)) {
                source.sendFailure(CoreAffinityLanguage.text(language, "message.cpu_missing", cpu));
                return 0;
            }
            CoreGroups next = draftGroups(source, config).assign(cpu, group);
            DRAFTS.put(source.getServer(), new Draft(next, draftAvoidSmt(source, config), UUID.randomUUID().toString()));
            feedback(source, CoreAffinityLanguage.text(language, "message.assigned", cpu,
                    CoreAffinityLanguage.value(language, "group." + group.name().toLowerCase(Locale.ROOT)))
                     .withStyle(ChatFormatting.GREEN));
            return list(context, directory);
        } catch (Exception e) {
            source.sendFailure(CoreAffinityLanguage.text(language, "message.command_error", e.getMessage()));
            return 0;
        }
    }

    private static int disableAll(CommandContext<CommandSourceStack> context, Path directory) {
        String language = language(context.getSource(), directory);
        String type = StringArgumentType.getString(context, "type").toLowerCase(Locale.ROOT);
        if (!type.equals("p") && !type.equals("e")) {
            context.getSource().sendFailure(CoreAffinityLanguage.text(language, "message.type_error"));
            return 0;
        }
        try {
            if (BigCoreFabric.service == null || BigCoreFabric.service.topology().isEmpty()) {
                context.getSource().sendFailure(CoreAffinityLanguage.text(language, "message.no_topology"));
                return 0;
            }
            AffinityConfig config = AffinityConfig.load(directory);
            CoreGroups next = draftGroups(context.getSource(), config).disableMatching(BigCoreFabric.service.topology(), type.equals("p"));
            DRAFTS.put(context.getSource().getServer(), new Draft(next, draftAvoidSmt(context.getSource(), config), UUID.randomUUID().toString()));
            feedback(context.getSource(), CoreAffinityLanguage.text(language, "message.disabled_all", type.toUpperCase(Locale.ROOT))
                     .withStyle(ChatFormatting.GREEN));
            return list(context, directory);
        } catch (Exception e) {
            context.getSource().sendFailure(CoreAffinityLanguage.text(language, "message.command_error", e.getMessage()));
            return 0;
        }
    }

    private static int assignAll(CommandContext<CommandSourceStack> context, Path directory) {
        CommandSourceStack source = context.getSource();
        String language = language(source, directory);
        String type = StringArgumentType.getString(context, "type").toLowerCase(Locale.ROOT);
        String groupArgument = StringArgumentType.getString(context, "group");
        if (!type.equals("p") && !type.equals("e")) {
            source.sendFailure(CoreAffinityLanguage.text(language, "message.type_error"));
            return 0;
        }
        CoreGroups.Group group;
        try {
            group = CoreGroups.Group.parse(groupArgument);
            if (group == CoreGroups.Group.SHARED) throw new IllegalArgumentException("shared is not a list group");
        } catch (IllegalArgumentException ignored) {
            source.sendFailure(CoreAffinityLanguage.text(language, "message.group_error", groupArgument));
            return 0;
        }
        try {
            if (BigCoreFabric.service == null || BigCoreFabric.service.topology().isEmpty()) {
                source.sendFailure(CoreAffinityLanguage.text(language, "message.no_topology"));
                return 0;
            }
            AffinityConfig config = AffinityConfig.load(directory);
            CoreGroups next = draftGroups(source, config);
            for (Cpu cpu : BigCoreFabric.service.topology()) {
                boolean matches = type.equals("p") ? CoreGroups.isPerformance(cpu, BigCoreFabric.service.topology())
                        : CoreGroups.isEfficiency(cpu, BigCoreFabric.service.topology());
                if (matches) next = next.assign(cpu.id(), group);
            }
            DRAFTS.put(source.getServer(), new Draft(next, draftAvoidSmt(source, config), UUID.randomUUID().toString()));
            feedback(source, CoreAffinityLanguage.text(language, "message.assigned_all", type.toUpperCase(Locale.ROOT),
                    CoreAffinityLanguage.value(language, "group." + group.name().toLowerCase(Locale.ROOT))).withStyle(ChatFormatting.GREEN));
            return list(context, directory);
        } catch (Exception e) {
            source.sendFailure(CoreAffinityLanguage.text(language, "message.command_error", e.getMessage()));
            return 0;
        }
    }

    private static CoreGroups draftGroups(CommandSourceStack source, AffinityConfig config) {
        Draft draft = DRAFTS.get(source.getServer());
        return draft == null ? config.groups() : draft.groups();
    }

    private static boolean draftAvoidSmt(CommandSourceStack source, AffinityConfig config) {
        Draft draft = DRAFTS.get(source.getServer());
        return draft == null ? config.avoidSmt() : draft.avoidSmt();
    }

    private static int setSmt(CommandContext<CommandSourceStack> context, Path directory, boolean avoidSmt) {
        CommandSourceStack source = context.getSource();
        try {
            AffinityConfig config = AffinityConfig.load(directory);
            DRAFTS.put(source.getServer(), new Draft(draftGroups(source, config), avoidSmt, UUID.randomUUID().toString()));
            return list(context, directory);
        } catch (Exception e) {
            source.sendFailure(CoreAffinityLanguage.text(language(source, directory), "message.apply_failed"));
            return 0;
        }
    }

    private static int applyCurrent(CommandContext<CommandSourceStack> context, Path directory) {
        CommandSourceStack source = context.getSource();
        try {
            AffinityConfig config = AffinityConfig.load(directory);
            Draft draft = DRAFTS.get(source.getServer());
            return applyNow(source, directory,
                    draft == null ? config.groups() : draft.groups(),
                    draft == null ? config.avoidSmt() : draft.avoidSmt());
        } catch (Exception e) {
            source.sendFailure(CoreAffinityLanguage.text(language(source, directory), "message.apply_failed"));
            return 0;
        }
    }

    private static int apply(CommandContext<CommandSourceStack> context, Path directory) {
        CommandSourceStack source = context.getSource();
        String language = language(source, directory);
        Draft draft = DRAFTS.get(source.getServer());
        if (draft == null || !draft.token().equals(StringArgumentType.getString(context, "confirmation"))) {
            source.sendFailure(CoreAffinityLanguage.text(language, "message.stale"));
            return 0;
        }
        if (!source.getServer().isSameThread()) {
            source.getServer().execute(() -> apply(context, directory));
            return 1;
        }
        return applyNow(source, directory, draft.groups(), draft.avoidSmt());
    }

    private static int applyNow(CommandSourceStack source, Path directory, CoreGroups groups, boolean avoidSmt) {
        String language = language(source, directory);
        try {
            if (!source.getServer().isSameThread()) {
                source.getServer().execute(() -> applyNow(source, directory, groups, avoidSmt));
                return 1;
            }
            List<Integer> actual = BigCoreFabric.service.applyServerGroups(groups, avoidSmt, directory);
            DRAFTS.remove(source.getServer());
            feedback(source, CoreAffinityLanguage.text(language, "message.applied", actual.toString()).withStyle(ChatFormatting.GREEN));
            return 1;
        } catch (Exception | LinkageError e) {
            org.slf4j.LoggerFactory.getLogger("CoreAffinity").error("Could not apply server affinity", e);
            source.sendFailure(CoreAffinityLanguage.text(language, "message.apply_failed"));
            return 0;
        }
    }

    private static Component groupSummary(String language, String group, Set<Integer> ids, List<Cpu> topology, ChatFormatting color) {
        String value = ids.stream().sorted().map(id -> topology.stream().filter(cpu -> cpu.id() == id).findFirst()
                .map(cpu -> coreName(cpu, topology)).orElse(String.valueOf(id))).reduce((a, b) -> a + "," + b).orElse("");
        return Component.literal(CoreAffinityLanguage.value(language, "group." + group) + ":" + value).withStyle(color);
    }

    private static Component quickActionRow(String language, String leftKey, String leftCommand, String rightKey,
                                       String rightCommand, ChatFormatting color) {
        return Component.literal("")
                .append(button(CoreAffinityLanguage.value(language, leftKey), "/coreaffinity " + leftCommand, color))
                .append(button(CoreAffinityLanguage.value(language, rightKey), "/coreaffinity " + rightCommand, color));
    }

    private static String coreName(Cpu cpu, List<Cpu> topology) {
        int max = topology.stream().mapToInt(Cpu::performanceClass).filter(v -> v >= 0).max().orElse(-1);
        int min = topology.stream().mapToInt(Cpu::performanceClass).filter(v -> v >= 0).min().orElse(-1);
        String kind = cpu.performanceClass() < 0 ? "?" : (max > min && cpu.performanceClass() == min ? "E" : "P");
        return cpu.id() + kind;
    }

    private static Component button(String label, String command, ChatFormatting color) {
        return Component.literal("[" + label + "]").withStyle(color)
                .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command)));
    }

    private static void feedback(CommandSourceStack source, Component text) { source.sendSuccess(() -> text, false); }
    private static String language(Path directory) {
        try { return language(directory, AffinityConfig.load(directory)); }
        catch (Exception ignored) { return "en_us"; }
    }
    private static String language(CommandSourceStack source, Path directory) {
        try { return language(source, directory, AffinityConfig.load(directory)); }
        catch (Exception ignored) { return "en_us"; }
    }
    private static String language(CommandSourceStack source, Path directory, AffinityConfig config) {
        String resolved = CoreAffinityLanguage.resolve(config, directory);
        ServerPlayer player = source.getPlayer();
        if (player == null) return resolved;
        return PLAYER_LANGUAGES.getOrDefault(player.getUUID(), resolved);
    }
    private static String language(Path directory, AffinityConfig config) { return CoreAffinityLanguage.resolve(config, directory); }
}
