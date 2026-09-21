package dev.bigcore;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CoreGroupsTest {
    @Test
    void assignmentIsMutuallyExclusive() {
        CoreGroups groups = new CoreGroups(Set.of(0), Set.of(2), Set.of(4));
        CoreGroups moved = groups.assign(0, CoreGroups.Group.DISABLED);
        assertEquals(CoreGroups.Group.DISABLED, moved.groupOf(0));
        assertFalse(moved.main().contains(0));
        assertTrue(moved.disabled().contains(0));
        assertEquals(CoreGroups.Group.UNASSIGNED, moved.assign(0, CoreGroups.Group.UNASSIGNED).groupOf(0));
    }

    @Test
    void disableMatchingUsesTopologyClass() {
        List<Cpu> topology = List.of(
                new Cpu(0, 0, "0", 10, true),
                new Cpu(1, 0, "0", 10, true),
                new Cpu(2, 0, "1", 0, true),
                new Cpu(3, 0, "1", 0, true));
        CoreGroups groups = new CoreGroups().disableMatching(topology, false);
        assertEquals(Set.of(2, 3), groups.disabled());
        assertEquals(Set.of(0, 1), new CoreGroups().disableMatching(topology, true).disabled());
    }
}
