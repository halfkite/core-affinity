package dev.bigcore;

/** id: Windows group * 64 + logical index; Linux kernel CPU number. */
public record Cpu(int id, int group, String physicalCore, int performanceClass, boolean allowed) {}
