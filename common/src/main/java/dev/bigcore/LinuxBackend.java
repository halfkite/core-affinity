package dev.bigcore;

import com.sun.jna.*;
import java.nio.file.*;
import java.util.*;

public final class LinuxBackend implements AffinityBackend {
    public interface LibC extends Library {
        LibC INSTANCE = Native.load("c", LibC.class);
        int sched_getaffinity(int pid, NativeLong size, Pointer mask);
        int sched_setaffinity(int pid, NativeLong size, Pointer mask);
    }
    private final LibC libc = LibC.INSTANCE;
    private Memory current() {
        for (int bytes = 128; bytes <= 131072; bytes *= 2) {
            Memory mask = new Memory(bytes);
            mask.clear();
            if (libc.sched_getaffinity(0, new NativeLong(bytes), mask) == 0) return mask;
            int error = Native.getLastError();
            mask.close();
            if (error != 22) throw new IllegalStateException("sched_getaffinity errno=" + error);
        }
        throw new IllegalStateException("CPU affinity mask too large");
    }
    @Override public List<Cpu> topology() throws Exception {
        Path root = Path.of("/sys/devices/system/cpu");
        Set<Integer> online = CpuList.parse(Files.readString(root.resolve("online")));
        Path pCoreFile = Path.of("/sys/devices/cpu_core/cpus");
        Path eCoreFile = Path.of("/sys/devices/cpu_atom/cpus");
        boolean intelHybrid = Files.isReadable(pCoreFile) && Files.isReadable(eCoreFile);
        Set<Integer> pCores = intelHybrid ? CpuList.parse(Files.readString(pCoreFile)) : Set.of();
        List<Cpu> result = new ArrayList<>();
        try (Memory allowed = current()) {
            for (int cpu : online) {
                Path dir = root.resolve("cpu" + cpu);
                String core = Files.readString(dir.resolve("topology/thread_siblings_list")).trim();
                int performance = intelHybrid ? (pCores.contains(cpu) ? 1 : 0) : -1;
                Path capacity = dir.resolve("cpu_capacity");
                if (!intelHybrid && Files.isReadable(capacity)) performance = Integer.parseInt(Files.readString(capacity).trim());
                boolean usable = cpu / 8 < allowed.size() && (allowed.getByte(cpu / 8) & (1 << (cpu % 8))) != 0;
                result.add(new Cpu(cpu, 0, core, performance, usable));
            }
        }
        return List.copyOf(result);
    }
    @Override public AutoCloseable bind(List<Cpu> cpus) throws Exception {
        if (cpus.isEmpty()) throw new IllegalArgumentException("Empty affinity");
        byte[] oldMask;
        try (Memory previous = current()) { oldMask = previous.getByteArray(0, (int) previous.size()); }
        byte[] requested = new byte[oldMask.length];
        for (Cpu cpu : cpus) {
            if (cpu.id() < 0 || cpu.id() / 8 >= requested.length) throw new IllegalArgumentException("CPU out of range");
            requested[cpu.id() / 8] |= (byte) (1 << (cpu.id() % 8));
        }
        set(requested);
        Thread owner = Thread.currentThread();
        AutoCloseable restore = () -> {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Restore must run on binding thread");
            set(oldMask);
        };
        try (Memory actual = current()) {
            if (!Arrays.equals(requested, actual.getByteArray(0, (int) actual.size()))) throw new IllegalStateException("Affinity readback mismatch");
        } catch (Exception e) {
            try { restore.close(); } catch (Exception rollback) { e.addSuppressed(rollback); }
            throw e;
        }
        return restore;
    }
    private void set(byte[] bytes) {
        try (Memory memory = new Memory(bytes.length)) {
            memory.write(0, bytes, 0, bytes.length);
            if (libc.sched_setaffinity(0, new NativeLong(bytes.length), memory) != 0)
                throw new IllegalStateException("sched_setaffinity errno=" + Native.getLastError());
        }
    }
}
