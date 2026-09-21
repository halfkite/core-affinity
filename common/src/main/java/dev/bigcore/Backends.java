package dev.bigcore;

import com.sun.jna.Platform;

public final class Backends {
    private Backends() {}
    public static AffinityBackend create() {
        if (Platform.isWindows()) return new WindowsBackend();
        if (Platform.isLinux()) return new LinuxBackend();
        throw new UnsupportedOperationException("Affinity supported only on Windows and Linux");
    }
}
