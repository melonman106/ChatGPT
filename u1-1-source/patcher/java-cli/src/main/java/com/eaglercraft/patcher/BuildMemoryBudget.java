package com.eaglercraft.patcher;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** A launch-time budget, not a guarantee that a particular linker run will fit. */
final class BuildMemoryBudget {
    static final long MIB = 1024L * 1024L;
    static final int MIN_BUILD_MIB = 10240;
    static final int MAX_BUILD_MIB = 13312;
    private final long availableMiB;
    private final int capMiB;

    private BuildMemoryBudget(long availableMiB, int capMiB) {
        this.availableMiB = availableMiB;
        this.capMiB = capMiB;
    }

    static BuildMemoryBudget fromBytes(long total, long available) {
        if (total <= 0 || available <= 0) return new BuildMemoryBudget(0, 0);
        available = Math.min(total, available);
        long reserve = Math.max(2048L * MIB, total / 8);
        long budget = Math.max(0, (available - reserve) / MIB);
        int cap = (int) Math.min(MAX_BUILD_MIB, budget);
        cap = cap / 256 * 256;
        return new BuildMemoryBudget(available / MIB, cap);
    }

    static BuildMemoryBudget detect() {
        long jvmTotal = 0, jvmFree = 0;
        var bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean memory) {
            jvmTotal = memory.getTotalMemorySize();
            jvmFree = memory.getFreeMemorySize();
        }
        long hostTotal = 0, hostAvailable = 0;
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemTotal:")) hostTotal = kib(line);
                if (line.startsWith("MemAvailable:")) hostAvailable = kib(line);
            }
        } catch (IOException | RuntimeException ignored) {
            // Other operating systems use the management bean above.
        }
        String relative = "";
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/cgroup"))) {
                if (line.startsWith("0::")) {
                    relative = line.substring(3);
                    break;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Other operating systems do not have a cgroup v2 path.
        }
        return measured(jvmTotal, jvmFree, hostTotal, hostAvailable,
                Path.of("/sys/fs/cgroup"), relative);
    }

    /** Linux MemAvailable includes reclaimable cache; JVM free physical memory does not. */
    static BuildMemoryBudget measured(long jvmTotal, long jvmFree, long hostTotal,
                                      long hostAvailable, Path cgroupRoot, String relative) {
        long total = hostTotal > 0 && jvmTotal > 0 ? Math.min(hostTotal, jvmTotal)
                : Math.max(hostTotal, jvmTotal);
        long available = hostAvailable > 0 ? hostAvailable : jvmFree;
        boolean jvmConstrained = hostTotal > 0 && jvmTotal > 0
                && jvmTotal < hostTotal && hostTotal - jvmTotal >= 64 * MIB;
        Path root = cgroupRoot.toAbsolutePath().normalize();
        String suffix = relative.startsWith("/") ? relative.substring(1) : relative;
        Path group = root.resolve(suffix).normalize();
        if (!group.startsWith(root)) return fromBytes(0, 0);
        // Every ancestor can impose an independent memory.max. Checking only
        // the leaf misses limits on a parent slice or container.
        boolean finiteCgroup = false;
        for (Path current = group; current != null && current.startsWith(root); current = current.getParent()) {
            Path maxFile = current.resolve("memory.max");
            if (!Files.isRegularFile(maxFile)) continue;
            try {
                String text = Files.readString(maxFile).trim();
                if ("max".equals(text)) continue;
                long limit = Long.parseLong(text);
                if (limit <= 0) return fromBytes(0, 0);
                finiteCgroup = true;
                total = total > 0 ? Math.min(total, limit) : limit;
                long used = Long.parseLong(Files.readString(current.resolve("memory.current")).trim());
                if (used < 0) return fromBytes(0, 0);
                available = Math.min(available, Math.max(0, limit - used));
            } catch (IOException | RuntimeException invalidLimit) {
                // An unreadable finite limit must not turn into host-sized headroom.
                return fromBytes(0, 0);
            }
        }
        // Only use JVM free memory as a fallback for an unseen container
        // limit. A readable cgroup already gives the matching headroom.
        if (jvmConstrained && !finiteCgroup && jvmFree > 0) available = Math.min(available, jvmFree);
        return fromBytes(total, available);
    }

    private static long kib(String line) {
        return Math.multiplyExact(Long.parseLong(line.trim().split("\\s+")[1]), 1024L);
    }

    boolean canBuild() { return capMiB >= MIN_BUILD_MIB; }

    int decompilerHeapMiB() {
        if (capMiB < 3072) throw new IllegalArgumentException(
                "Not enough available RAM to extract source safely. Close other apps;"
                + " source extraction needs at least 3 GiB for the process plus the system reserve.");
        return Math.min(4096, capMiB - 1024);
    }

    String sourceSummary() {
        if (capMiB < 3072) return "RAM: source extraction needs 3 GiB available beyond the system reserve.";
        return String.format(Locale.ROOT, "Source extraction: Java heap limit %.1f GiB; system reserve retained.",
                decompilerHeapMiB() / 1024.0);
    }

    void requireBuildCapacity() {
        if (!canBuild()) throw new IllegalArgumentException(summary()
                + " Close other apps and try again. The current linker needs at least 10 GiB"
                + " for the build, plus reserved memory for the system; 16 GiB RAM or more is recommended.");
    }

    String summary() {
        if (availableMiB == 0) return "Available RAM could not be measured; build not started.";
        return String.format(Locale.ROOT, "RAM: %.1f GiB available; build budget %.1f GiB%s.",
                availableMiB / 1024.0, capMiB / 1024.0,
                canBuild() ? " (system reserve excluded)" : " (below the 10 GiB minimum)");
    }

    void applyTo(Map<String, String> env) {
        requireBuildCapacity();
        int javaHeapMiB = capMiB - 2048;
        env.put("EAGLER_BUILD_CAP_MIB", Integer.toString(capMiB));
        env.put("EAGLER_BUILD_XMX_MIB", Integer.toString(javaHeapMiB));
        env.put("EAGLER_LINK_HEAP_MIB", Integer.toString(javaHeapMiB));
        env.put("EAGLER_SERVER_LINK_HEAP_MIB", Integer.toString(javaHeapMiB));
        env.put("NODE_OPTIONS", "--max-old-space-size=1024");
    }
}
