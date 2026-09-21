package dev.bigcore;

import java.util.*;

/** Persistent user classification of logical CPUs. Groups are mutually exclusive. */
public record CoreGroups(Set<Integer> main, Set<Integer> shared, Set<Integer> disabled) {
    public enum Group {
        MAIN, SHARED, DISABLED, UNASSIGNED;

        public static Group parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "main", "primary", "主核心" -> MAIN;
                case "shared", "share", "共享核心" -> SHARED;
                case "disabled", "disable", "off", "禁用核心", "不调用核心" -> DISABLED;
                case "unassigned", "none", "未分配" -> UNASSIGNED;
                default -> throw new IllegalArgumentException("Unknown core group: " + value);
            };
        }
    }

    public CoreGroups {
        main = immutable(main);
        shared = immutable(shared);
        disabled = immutable(disabled);
        Set<Integer> overlap = new HashSet<>(main);
        overlap.retainAll(shared);
        overlap.addAll(intersection(main, disabled));
        overlap.addAll(intersection(shared, disabled));
        if (!overlap.isEmpty()) throw new IllegalArgumentException("CPU belongs to multiple groups: " + overlap);
        if (main.stream().anyMatch(CoreGroups::invalid) || shared.stream().anyMatch(CoreGroups::invalid)
                || disabled.stream().anyMatch(CoreGroups::invalid)) throw new IllegalArgumentException("Invalid CPU group id");
    }

    public CoreGroups() { this(Set.of(), Set.of(), Set.of()); }

    public Group groupOf(int cpu) {
        if (main.contains(cpu)) return Group.MAIN;
        if (shared.contains(cpu)) return Group.SHARED;
        if (disabled.contains(cpu)) return Group.DISABLED;
        return Group.UNASSIGNED;
    }

    public CoreGroups assign(int cpu, Group group) {
        if (cpu < 0) throw new IllegalArgumentException("CPU id must be nonnegative");
        Set<Integer> nextMain = new TreeSet<>(main);
        Set<Integer> nextShared = new TreeSet<>(shared);
        Set<Integer> nextDisabled = new TreeSet<>(disabled);
        nextMain.remove(cpu);
        nextShared.remove(cpu);
        nextDisabled.remove(cpu);
        switch (group) {
            case MAIN -> nextMain.add(cpu);
            case SHARED -> nextShared.add(cpu);
            case DISABLED -> nextDisabled.add(cpu);
            case UNASSIGNED -> { }
        }
        return new CoreGroups(nextMain, nextShared, nextDisabled);
    }

    public CoreGroups disableMatching(Collection<Cpu> topology, boolean performance) {
        CoreGroups result = this;
        for (Cpu cpu : topology) {
            boolean matches = performance ? isPerformance(cpu, topology) : isEfficiency(cpu, topology);
            if (matches) result = result.assign(cpu.id(), Group.DISABLED);
        }
        return result;
    }

    public static boolean isPerformance(Cpu cpu, Collection<Cpu> topology) {
        int max = topology.stream().mapToInt(Cpu::performanceClass).filter(v -> v >= 0).max().orElse(-1);
        int min = topology.stream().mapToInt(Cpu::performanceClass).filter(v -> v >= 0).min().orElse(-1);
        return cpu.performanceClass() >= 0 && max > min && cpu.performanceClass() == max;
    }

    public static boolean isEfficiency(Cpu cpu, Collection<Cpu> topology) {
        int max = topology.stream().mapToInt(Cpu::performanceClass).filter(v -> v >= 0).max().orElse(-1);
        int min = topology.stream().mapToInt(Cpu::performanceClass).filter(v -> v >= 0).min().orElse(-1);
        return cpu.performanceClass() >= 0 && max > min && cpu.performanceClass() == min;
    }

    public String mainCsv() { return CpuList.format(main); }
    public String sharedCsv() { return CpuList.format(shared); }
    public String disabledCsv() { return CpuList.format(disabled); }

    private static boolean invalid(int id) { return id < 0 || id > 1048575; }
    private static Set<Integer> immutable(Set<Integer> value) { return Collections.unmodifiableSet(new TreeSet<>(value)); }
    private static Set<Integer> intersection(Set<Integer> a, Set<Integer> b) {
        Set<Integer> result = new HashSet<>(a);
        result.retainAll(b);
        return result;
    }
}
