package dev.bigcore;

import java.util.*;

public record Policy(Mode mode, Set<Integer> cpus, int coreIndex) {
    public enum Mode { AUTO, EXPLICIT, OFF }
    public Policy {
        Objects.requireNonNull(mode);
        cpus = Set.copyOf(cpus);
        if (coreIndex < -1) throw new IllegalArgumentException("coreIndex must be -1 or nonnegative");
        if (mode == Mode.EXPLICIT && cpus.isEmpty()) throw new IllegalArgumentException("explicit mode requires cpus");
    }
    public List<Cpu> select(List<Cpu> topology) {
        if (mode == Mode.OFF) return List.of();
        if (mode == Mode.EXPLICIT) {
            List<Cpu> selected = topology.stream().filter(c -> cpus.contains(c.id()) && c.allowed()).toList();
            if (selected.size() != cpus.size()) throw new IllegalArgumentException("CPU absent or outside allowed affinity: " + cpus);
            return selected;
        }
        // Compare the whole topology, not just an externally restricted subset.
        if (topology.isEmpty() || topology.stream().anyMatch(c -> c.performanceClass() < 0)
                || topology.stream().map(Cpu::performanceClass).distinct().count() < 2) return List.of();
        int fastest = topology.stream().mapToInt(Cpu::performanceClass).max().orElseThrow();
        List<Cpu> candidates = topology.stream().filter(c -> c.allowed() && c.performanceClass() == fastest)
                .sorted(Comparator.comparingInt(Cpu::id)).toList();
        if (candidates.isEmpty()) throw new IllegalArgumentException("No performance CPU inside allowed affinity");
        if (coreIndex == -1) return candidates;
        List<String> cores = candidates.stream().map(Cpu::physicalCore).distinct().toList();
        if (coreIndex >= cores.size()) throw new IllegalArgumentException("coreIndex exceeds available performance cores: " + cores.size());
        String core = cores.get(coreIndex);
        // One hardware thread per selected physical core avoids unnecessary migration.
        return List.of(candidates.stream().filter(c -> c.physicalCore().equals(core)).findFirst().orElseThrow());
    }
}
