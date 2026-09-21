package dev.bigcore;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loader-neutral entry point. Adapters must invoke it on the actual game/server thread. */
public final class AffinityService {
    public enum Role { SERVER, CLIENT }
    private static final Logger LOG = LoggerFactory.getLogger("CoreAffinity");
    private volatile AffinityConfig config;
    private final AffinityBackend backend;
    private final List<Cpu> startupTopology;
    private final ThreadLocal<AutoCloseable> restoration = new ThreadLocal<>();
    AffinityService(AffinityConfig config, AffinityBackend backend, List<Cpu> topology) {
        this.config = config;
        this.backend = backend;
        this.startupTopology = topology;
    }
    public static AffinityService load(Path configDirectory) {
        AffinityConfig config;
        try { config = AffinityConfig.load(configDirectory); }
        catch (Exception e) {
            LOG.error("Invalid affinity configuration; binding disabled. Fix config and restart.", e);
            return new AffinityService(null, null, List.of());
        }
        try {
            AffinityBackend backend = Backends.create();
            // Snapshot before any main thread is pinned. In Linux a later integrated-server
            // thread may inherit the client's mask; it still needs the original allowed pool.
            return new AffinityService(config, backend, backend.topology());
        } catch (Exception | LinkageError e) {
            LOG.error("Native affinity initialization unavailable; binding disabled", e);
            return new AffinityService(null, null, List.of());
        }
    }
    public void bindCurrentThread(Role role) {
        if (config == null || restoration.get() != null) return;
        Policy policy = role == Role.SERVER ? config.server() : config.client();
        if (role == Role.SERVER && !config.groups().main().isEmpty())
            policy = new Policy(Policy.Mode.EXPLICIT, config.groups().main(), -1);
        if (policy.mode() == Policy.Mode.OFF) return;
        try {
            if (Thread.currentThread().isVirtual()) throw new IllegalStateException("Cannot bind a virtual thread");
            List<Cpu> topology = effectiveTopology(role);
            if (role == Role.SERVER && !config.groups().main().isEmpty())
                policy = new Policy(Policy.Mode.EXPLICIT, config.groups().main(), -1);
            LOG.info("{} CPU topology (id, group, physicalCore, performanceClass, allowed): {}", role, topology);
            List<Cpu> selected = policy.select(topology);
            if (role == Role.SERVER && config.avoidSmt()) selected = oneThreadPerCore(selected);
            if (selected.isEmpty()) {
                LOG.warn("{}: no reliable heterogeneous CPU classes; auto binding skipped. Use explicit mode to select CPUs.", role);
                return;
            }
            restoration.set(backend.bind(selected));
            LOG.info("{} thread '{}' bound and verified: logical CPUs {}, physical cores {}", role,
                    Thread.currentThread().getName(), selected.stream().map(Cpu::id).toList(),
                    selected.stream().map(Cpu::physicalCore).distinct().toList());
        } catch (Exception | LinkageError e) {
            LOG.error("{} affinity unavailable; Minecraft will continue. Check CPU IDs and OS restrictions.", role, e);
        }
    }
    /** Must be called by the server adapter on its main thread. Persistence is transactional. */
    public List<Integer> applyServerGroups(CoreGroups groups, Path directory) throws Exception {
        return applyServerGroups(groups, config != null && config.avoidSmt(), directory);
    }

    public List<Integer> applyServerGroups(CoreGroups groups, boolean avoidSmt, Path directory) throws Exception {
        if (backend == null || config == null) throw new IllegalStateException("Affinity unavailable");
        Policy policy = groups.main().isEmpty() ? config.server()
                : new Policy(Policy.Mode.EXPLICIT, groups.main(), -1);
        List<Cpu> available = startupTopology.stream().map(cpu -> groups.disabled().contains(cpu.id())
                ? new Cpu(cpu.id(), cpu.group(), cpu.physicalCore(), cpu.performanceClass(), false) : cpu).toList();
        List<Cpu> selected = policy.select(available);
        if (avoidSmt) selected = oneThreadPerCore(selected);
        if (selected.isEmpty()) throw new IllegalArgumentException("No target CPUs selected");
        AutoCloseable rollback = backend.bind(selected);
        try {
            AffinityConfig.saveServerSettings(directory, groups, avoidSmt);
        } catch (Exception e) {
            try { rollback.close(); } catch (Exception restoreError) { e.addSuppressed(restoreError); }
            throw e;
        }
        // Keep the original pre-mod mask for shutdown, not the mask from the last update.
        if (restoration.get() == null) restoration.set(rollback);
        config = new AffinityConfig(config.server(), config.client(), groups, config.language(), avoidSmt);
        List<Integer> ids = selected.stream().map(Cpu::id).toList();
        LOG.info("SERVER thread '{}' rebound and verified: logical CPUs {}", Thread.currentThread().getName(), ids);
        return ids;
    }

    /** Choose the lowest selected logical ID for each physical core; never assume even/odd siblings. */
    public static List<Cpu> oneThreadPerCore(List<Cpu> selected) {
        java.util.Map<String, Cpu> cores = new java.util.LinkedHashMap<>();
        selected.stream().sorted(java.util.Comparator.comparingInt(Cpu::id)).forEach(cpu -> {
            if (cpu.physicalCore() == null || cpu.physicalCore().isBlank())
                throw new IllegalArgumentException("Physical core topology unavailable");
            cores.putIfAbsent(cpu.group() + ":" + cpu.physicalCore(), cpu);
        });
        return List.copyOf(cores.values());
    }

    public void restoreCurrentThread() {
        AutoCloseable restore = restoration.get();
        if (restore == null) return;
        try { restore.close(); restoration.remove(); }
        catch (Exception | LinkageError e) { LOG.error("Could not restore main-thread affinity", e); }
    }
    public List<Cpu> topology() { return startupTopology; }
    public AffinityConfig config() { return config; }

    private List<Cpu> effectiveTopology(Role role) {
        if (config == null) return startupTopology;
        CoreGroups groups = config.groups();
        Set<Integer> disabled = groups.disabled();
        if (disabled.isEmpty()) return startupTopology;
        return startupTopology.stream().map(cpu -> disabled.contains(cpu.id())
                ? new Cpu(cpu.id(), cpu.group(), cpu.physicalCore(), cpu.performanceClass(), false) : cpu).toList();
    }
}
