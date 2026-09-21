package dev.bigcore;

import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.util.*;

/** Windows 10+/Server 2016+, 64-bit. Uses hard affinity, not a scheduling hint. */
public final class WindowsBackend implements AffinityBackend {
    public interface Kernel extends StdCallLibrary {
        Kernel INSTANCE = Native.load("kernel32", Kernel.class);
        Pointer GetCurrentThread();
        Pointer GetCurrentProcess();
        boolean GetSystemCpuSetInformation(Pointer info, int length, IntByReference required, Pointer process, int flags);
        boolean GetThreadGroupAffinity(Pointer thread, GroupAffinity affinity);
        boolean SetThreadGroupAffinity(Pointer thread, GroupAffinity affinity, GroupAffinity previous);
    }
    @Structure.FieldOrder({"mask", "group", "reserved"})
    public static class GroupAffinity extends Structure {
        public long mask;
        public short group;
        public short[] reserved = new short[3];
    }
    private final Kernel kernel;
    public WindowsBackend() {
        if (Native.POINTER_SIZE != 8) throw new UnsupportedOperationException("64-bit Windows required");
        kernel = Kernel.INSTANCE;
    }
    private GroupAffinity current() {
        GroupAffinity result = new GroupAffinity();
        if (!kernel.GetThreadGroupAffinity(kernel.GetCurrentThread(), result)) throw failure("GetThreadGroupAffinity");
        return result;
    }
    @Override public List<Cpu> topology() {
        GroupAffinity allowed = current();
        IntByReference size = new IntByReference();
        kernel.GetSystemCpuSetInformation(null, 0, size, kernel.GetCurrentProcess(), 0);
        if (size.getValue() <= 0) throw failure("GetSystemCpuSetInformation size");
        // Retry if CPU hotplug changes the required buffer size between calls.
        for (int attempt = 0; attempt < 3; attempt++) {
            try (Memory memory = new Memory(size.getValue())) {
                if (!kernel.GetSystemCpuSetInformation(memory, (int) memory.size(), size, kernel.GetCurrentProcess(), 0)) {
                    if (Native.getLastError() == 122) continue;
                    throw failure("GetSystemCpuSetInformation");
                }
                List<Cpu> cpus = new ArrayList<>();
                for (long offset = 0; offset < size.getValue();) {
                    int length = memory.getInt(offset);
                    if (length < 8 || offset + length > size.getValue()) throw new IllegalStateException("Malformed CPU set record");
                    if (memory.getInt(offset + 4) == 0 && length >= 32) {
                        int group = Short.toUnsignedInt(memory.getShort(offset + 12));
                        int logical = Byte.toUnsignedInt(memory.getByte(offset + 14));
                        int core = Byte.toUnsignedInt(memory.getByte(offset + 15));
                        int efficiency = Byte.toUnsignedInt(memory.getByte(offset + 18));
                        int flags = Byte.toUnsignedInt(memory.getByte(offset + 19));
                        boolean available = group == Short.toUnsignedInt(allowed.group)
                                && (allowed.mask & (1L << logical)) != 0 && ((flags & 2) == 0 || (flags & 4) != 0);
                        cpus.add(new Cpu(group * 64 + logical, group, group + ":" + core, efficiency, available));
                    }
                    offset += length;
                }
                return List.copyOf(cpus);
            }
        }
        throw new IllegalStateException("CPU topology kept changing");
    }
    @Override public AutoCloseable bind(List<Cpu> cpus) throws Exception {
        if (cpus.isEmpty()) throw new IllegalArgumentException("Empty affinity");
        int group = cpus.getFirst().group();
        if (cpus.stream().anyMatch(c -> c.group() != group)) throw new IllegalArgumentException("Windows hard affinity must stay in one processor group");
        GroupAffinity requested = new GroupAffinity();
        requested.group = (short) group;
        for (Cpu cpu : cpus) requested.mask |= 1L << (cpu.id() % 64);
        GroupAffinity previous = new GroupAffinity();
        if (!kernel.SetThreadGroupAffinity(kernel.GetCurrentThread(), requested, previous)) throw failure("SetThreadGroupAffinity");
        Thread owner = Thread.currentThread();
        AutoCloseable restore = () -> {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Restore must run on binding thread");
            if (!kernel.SetThreadGroupAffinity(kernel.GetCurrentThread(), previous, null)) throw failure("Restore affinity");
        };
        try {
            GroupAffinity actual = current();
            if (actual.mask != requested.mask || actual.group != requested.group) throw new IllegalStateException("Affinity readback mismatch");
        } catch (Exception e) {
            try { restore.close(); } catch (Exception rollback) { e.addSuppressed(rollback); }
            throw e;
        }
        return restore;
    }
    private static IllegalStateException failure(String operation) {
        return new IllegalStateException(operation + " failed, Win32 error " + Native.getLastError());
    }
}
