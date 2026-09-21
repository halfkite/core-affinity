package dev.bigcore;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PolicyTest {
    private final List<Cpu> hybrid = List.of(
            new Cpu(0, 0, "0:0", 1, true), new Cpu(1, 0, "0:0", 1, true),
            new Cpu(2, 0, "0:1", 1, true), new Cpu(3, 0, "0:1", 1, true),
            new Cpu(4, 0, "0:2", 0, true), new Cpu(5, 0, "0:3", 0, true));
    @Test void autoExcludesEfficiencyCores() {
        assertEquals(List.of(0, 1, 2, 3), ids(new Policy(Policy.Mode.AUTO, Set.of(), -1).select(hybrid)));
    }
    @Test void coreIndexSeparatesPhysicalCoresNotSmtSiblings() {
        assertEquals(List.of(2), ids(new Policy(Policy.Mode.AUTO, Set.of(), 1).select(hybrid)));
    }
    @Test void explicitAllowsUserChosenEfficiencyCores() {
        assertEquals(List.of(5), ids(new Policy(Policy.Mode.EXPLICIT, Set.of(5), -1).select(hybrid)));
    }
    @Test void explicitRejectsMissingOrDisallowedCpuWithoutPartialBinding() {
        assertThrows(IllegalArgumentException.class, () -> new Policy(Policy.Mode.EXPLICIT, Set.of(0, 99), -1).select(hybrid));
        assertThrows(IllegalArgumentException.class, () -> new Policy(Policy.Mode.EXPLICIT, Set.of(0), -1)
                .select(List.of(new Cpu(0, 0, "0", 1, false))));
    }
    @Test void unknownOrHomogeneousDoesNotGuess() {
        Policy p = new Policy(Policy.Mode.AUTO, Set.of(), -1);
        assertTrue(p.select(List.of(new Cpu(0, 0, "0", -1, true))).isEmpty());
        assertTrue(p.select(hybrid.subList(0, 4)).isEmpty());
    }
    @Test void restrictedToEfficiencyCoresDoesNotMisidentifyThemAsPerformance() {
        List<Cpu> restricted = hybrid.stream().map(c -> new Cpu(c.id(), c.group(), c.physicalCore(), c.performanceClass(), c.id() >= 4)).toList();
        assertThrows(IllegalArgumentException.class, () -> new Policy(Policy.Mode.AUTO, Set.of(), -1).select(restricted));
    }
    @Test void invalidCoreIndexFails() {
        assertThrows(IllegalArgumentException.class, () -> new Policy(Policy.Mode.AUTO, Set.of(), 2).select(hybrid));
        assertThrows(IllegalArgumentException.class, () -> new Policy(Policy.Mode.AUTO, Set.of(), -2));
    }
    @Test void disabledNeverBinds() {
        assertTrue(new Policy(Policy.Mode.OFF, Set.of(), -1).select(hybrid).isEmpty());
    }
    @Test void cpuListSupportsRangesAndDeduplication() {
        assertEquals(Set.of(0, 2, 3, 4, 65), CpuList.parse("0, 2-4,3,65"));
        assertEquals(Set.of(), CpuList.parse(""));
    }
    @Test void cpuListRejectsMalformedAndUnboundedValues() {
        for (String input : List.of("-1", "4-2", "0,", "1-2-3", "a", "1048576"))
            assertThrows(IllegalArgumentException.class, () -> CpuList.parse(input), input);
    }
    private static List<Integer> ids(List<Cpu> cpus) { return cpus.stream().map(Cpu::id).toList(); }
}
