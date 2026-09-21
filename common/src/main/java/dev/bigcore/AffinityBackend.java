package dev.bigcore;

import java.util.List;

/** OS boundary. Every method operates on the calling native/platform thread. */
public interface AffinityBackend {
    List<Cpu> topology() throws Exception;
    /** Bind and verify; return a restoration handle usable only on this same thread. */
    AutoCloseable bind(List<Cpu> cpus) throws Exception;
}
