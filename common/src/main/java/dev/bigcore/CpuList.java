package dev.bigcore;

import java.util.*;

public final class CpuList {
    private CpuList() {}
    public static Set<Integer> parse(String text) {
        Set<Integer> result = new TreeSet<>();
        if (text == null || text.isBlank()) return result;
        for (String token : text.split(",", -1)) {
            String[] range = token.trim().split("-", -1);
            if (range.length > 2) throw new IllegalArgumentException("Invalid CPU range: " + token);
            int first = Integer.parseInt(range[0].trim());
            int last = range.length == 2 ? Integer.parseInt(range[1].trim()) : first;
            if (first < 0 || last < first || last > 1048575)
                throw new IllegalArgumentException("Invalid CPU range: " + token);
            for (int i = first; i <= last; i++) result.add(i);
        }
        return result;
    }
    public static String format(Collection<Integer> cpus) {
        return cpus.stream().sorted().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
    }
}
