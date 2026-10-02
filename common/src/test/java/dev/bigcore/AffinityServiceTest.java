package dev.bigcore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AffinityServiceTest {
    @TempDir Path directory;
    @Test void smtUsesTopologyAndPreservesExplicitSiblingChoice() {
        Cpu a = new Cpu(3,0,"physical-a",1,true);
        Cpu sibling = new Cpu(11,0,"physical-a",1,true);
        Cpu b = new Cpu(4,0,"physical-b",1,true);
        assertEquals(List.of(a,b), AffinityService.oneThreadPerCore(List.of(sibling,b,a)));
        assertEquals(List.of(b,sibling), AffinityService.oneThreadPerCore(List.of(sibling,b)));
    }

    @Test void smtTogglePersistsAndRestoresSelectionOnRestart() throws Exception {
        Backend backend = new Backend();
        backend.cpus = List.of(new Cpu(0,0,"same",1,true), new Cpu(1,0,"same",1,true));
        AffinityService service = new AffinityService(new AffinityConfig(
                new Policy(Policy.Mode.AUTO, Set.of(), -1), new Policy(Policy.Mode.OFF, Set.of(), -1)), backend, backend.cpus);
        CoreGroups groups = new CoreGroups(Set.of(0,1), Set.of(), Set.of());
        service.applyServerGroups(groups, true, directory);
        assertEquals(List.of(0), backend.mask);
        assertTrue(AffinityConfig.load(directory).avoidSmt());
        assertEquals(Set.of(0,1), AffinityConfig.load(directory).groups().main());
        service.restoreCurrentThread();
        AffinityService restarted = new AffinityService(AffinityConfig.load(directory), backend, backend.cpus);
        restarted.bindCurrentThread(AffinityService.Role.SERVER);
        assertEquals(List.of(0), backend.mask);
        restarted.applyServerGroups(groups, false, directory);
        assertEquals(List.of(0,1), backend.mask);
        assertFalse(AffinityConfig.load(directory).avoidSmt());
        restarted.restoreCurrentThread();
    }
    static class Backend implements AffinityBackend {
        List<Cpu> cpus = List.of(new Cpu(0,0,"0",1,true), new Cpu(1,0,"1",0,true));
        List<Integer> mask = List.of(0,1);
        boolean fail;
        public List<Cpu> topology() { return cpus; }
        public AutoCloseable bind(List<Cpu> selected) {
            if (fail) throw new IllegalStateException("test failure");
            List<Integer> previous = mask;
            mask = selected.stream().map(Cpu::id).toList();
            return () -> mask = previous;
        }
    }
    @Test void transactionalRebindingAndOriginalRestoration() throws Exception {
        Backend backend = new Backend();
        AffinityService service = new AffinityService(new AffinityConfig(
                new Policy(Policy.Mode.AUTO, Set.of(), -1), new Policy(Policy.Mode.OFF, Set.of(), -1)), backend, backend.cpus);
        CoreGroups first = new CoreGroups(Set.of(0), Set.of(), Set.of());
        CoreGroups second = new CoreGroups(Set.of(1), Set.of(), Set.of());
        service.applyServerGroups(first, directory);
        backend.fail = true;
        assertThrows(Exception.class, () -> service.applyServerGroups(second, directory));
        assertEquals(List.of(0), backend.mask);
        assertEquals(first, AffinityConfig.load(directory).groups());
        backend.fail = false;
        Path file = Files.writeString(directory.resolve("not-directory"), "x");
        assertThrows(Exception.class, () -> service.applyServerGroups(second, file));
        assertEquals(List.of(0), backend.mask);
        assertEquals(first, service.config().groups());
        service.applyServerGroups(second, directory);
        assertEquals(List.of(1), backend.mask);
        service.restoreCurrentThread();
        assertEquals(List.of(0,1), backend.mask);
    }

    @Test void offModeWinsOverSavedGroupsAtStartupAndOnApply() throws Exception {
        Backend backend = new Backend();
        Policy off = new Policy(Policy.Mode.OFF, Set.of(), -1);
        CoreGroups groups = new CoreGroups(Set.of(1), Set.of(), Set.of());
        AffinityConfig saved = new AffinityConfig(off, off, groups, "en_us");
        AffinityConfig.saveAll(directory, saved);
        AffinityService service = new AffinityService(saved, backend, backend.cpus);
        service.bindCurrentThread(AffinityService.Role.SERVER);
        assertEquals(List.of(0, 1), backend.mask);
        assertThrows(AffinityService.BindingDisabledException.class,
                () -> service.applyServerGroups(groups, true, directory));
        assertEquals(List.of(0, 1), backend.mask);
        assertEquals(saved, AffinityConfig.load(directory));
    }

    @Test void applyHonorsOffModeSavedAfterServiceInitialization() throws Exception {
        Backend backend = new Backend();
        AffinityConfig initial = AffinityConfig.load(directory);
        AffinityService service = new AffinityService(initial, backend, backend.cpus);
        AffinityConfig.saveAll(directory, new AffinityConfig(
                new Policy(Policy.Mode.OFF, Set.of(), -1), initial.client()));
        assertThrows(AffinityService.BindingDisabledException.class,
                () -> service.applyServerGroups(new CoreGroups(Set.of(1), Set.of(), Set.of()), directory));
        assertEquals(List.of(0, 1), backend.mask);
    }

    @Test void atomicSaveFailureRollsBackBindingAndKeepsPreviousConfiguration() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Backend backend = new Backend();
        AffinityConfig initial = AffinityConfig.load(directory);
        AffinityService service = new AffinityService(initial, backend, backend.cpus);
        service.applyServerGroups(new CoreGroups(Set.of(0), Set.of(), Set.of()), directory);
        Path file = directory.resolve("core-affinity.json5");
        String original = Files.readString(file);
        try (var ignored = java.nio.channels.FileChannel.open(file, StandardOpenOption.READ,
                com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)) {
            assertThrows(java.io.IOException.class,
                    () -> service.applyServerGroups(new CoreGroups(Set.of(1), Set.of(), Set.of()), directory));
            assertEquals(List.of(0), backend.mask);
            assertEquals(original, Files.readString(file));
            assertEquals(Set.of(0), service.config().groups().main());
        }
        service.restoreCurrentThread();
        assertEquals(List.of(0, 1), backend.mask);
    }
}
