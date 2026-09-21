package dev.bigcore;

import java.util.*;
import java.util.concurrent.*;

/** Real native binding smoke test; changes only this short-lived Java test process. */
public final class NativeSmoke {
    public static void main(String[] args) throws Exception {
        AffinityBackend backend = Backends.create();
        List<Cpu> before = backend.topology();
        System.out.println("Topology: " + before);
        Cpu target = before.stream().filter(Cpu::allowed).findFirst().orElseThrow();
        java.nio.file.Path configDir = java.nio.file.Files.createTempDirectory("bigcore-live-smoke");
        AffinityService service = new AffinityService(new AffinityConfig(
                new Policy(Policy.Mode.AUTO, Set.of(), -1), new Policy(Policy.Mode.OFF, Set.of(), -1)), backend, before);
        try {
            for (Cpu cpu : List.of(target, before.stream().filter(Cpu::allowed).reduce((a,b) -> b).orElseThrow(), target)) {
                service.applyServerGroups(new CoreGroups(Set.of(cpu.id()), Set.of(), Set.of()), configDir);
                if (!allowed(backend.topology()).equals(List.of(cpu.id()))) throw new AssertionError("Live rebind failed");
            }
            System.out.println("PASS live P/E/P rebind and save");
            Set<Integer> all = new HashSet<>(allowed(before));
            CoreGroups groups = new CoreGroups(all, Set.of(), Set.of());
            service.applyServerGroups(groups, true, configDir);
            List<Integer> expected = allowed(AffinityService.oneThreadPerCore(before.stream().filter(Cpu::allowed).toList()));
            if (!allowed(backend.topology()).equals(expected)) throw new AssertionError("SMT filter readback mismatch");
            System.out.println("PASS native one-thread-per-core mask: " + expected);
            service.applyServerGroups(groups, false, configDir);
            if (!allowed(backend.topology()).equals(allowed(before))) throw new AssertionError("SMT on did not restore selected siblings");
            System.out.println("PASS native SMT filter disabled");
        } finally { service.restoreCurrentThread(); }
        if (!allowed(backend.topology()).equals(allowed(before))) throw new AssertionError("Original mask lost after rebinds");
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            List<Integer> workerBefore = worker.submit(() -> allowed(backend.topology())).get();
            AutoCloseable restore = backend.bind(List.of(target));
            try {
                if (!allowed(backend.topology()).equals(List.of(target.id()))) throw new AssertionError("Target thread not bound");
                if (!worker.submit(() -> allowed(backend.topology())).get().equals(workerBefore)) throw new AssertionError("Other thread affected");
                System.out.println("PASS native bind/readback; existing worker affinity unchanged");
            } finally { restore.close(); }
            if (!allowed(backend.topology()).equals(allowed(before))) throw new AssertionError("Restoration failed");
            System.out.println("PASS native restoration");
            List<Cpu> automatic = new Policy(Policy.Mode.AUTO, Set.of(), -1).select(before);
            if (!automatic.isEmpty()) {
                try (AutoCloseable ignored = backend.bind(automatic)) {
                    if (!allowed(backend.topology()).equals(allowed(automatic))) throw new AssertionError("Automatic P-core mask mismatch");
                    System.out.println("PASS automatic P-core native affinity: " + allowed(automatic));
                }
            }
        }
    }
    private static List<Integer> allowed(List<Cpu> cpus) { return cpus.stream().filter(Cpu::allowed).map(Cpu::id).toList(); }
}
