package com.eaglercraft.patcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

public final class BuildMemoryBudgetTest {
    public static void main(String[] args) throws Exception {
        long gib = 1024L * BuildMemoryBudget.MIB;
        if (BuildMemoryBudget.fromBytes(8 * gib, 7 * gib).canBuild()) throw new AssertionError("8 GiB");
        if (BuildMemoryBudget.fromBytes(16 * gib, 8 * gib).canBuild()) throw new AssertionError("busy host");
        if (BuildMemoryBudget.fromBytes(0, 0).canBuild()) throw new AssertionError("unknown");
        var env = new HashMap<String, String>();
        BuildMemoryBudget.fromBytes(16 * gib, 14 * gib).applyTo(env);
        if (!"12288".equals(env.get("EAGLER_BUILD_CAP_MIB"))) throw new AssertionError(env);
        if (!"10240".equals(env.get("EAGLER_BUILD_XMX_MIB"))) throw new AssertionError(env);
        if (!"10240".equals(env.get("EAGLER_LINK_HEAP_MIB"))) throw new AssertionError(env);
        if (!env.get("EAGLER_LINK_HEAP_MIB").equals(env.get("EAGLER_SERVER_LINK_HEAP_MIB"))) throw new AssertionError(env);
        var minimum = BuildMemoryBudget.fromBytes(16 * gib, 12 * gib);
        minimum.applyTo(env);
        if (!minimum.canBuild() || !"10240".equals(env.get("EAGLER_BUILD_CAP_MIB"))
                || !"8192".equals(env.get("EAGLER_BUILD_XMX_MIB"))) {
            throw new AssertionError("minimum supported budget was not preserved: " + env);
        }
        BuildMemoryBudget.fromBytes(64 * gib, 60 * gib).applyTo(env);
        if (!"13312".equals(env.get("EAGLER_BUILD_CAP_MIB"))) throw new AssertionError(env);
        if (!"11264".equals(env.get("EAGLER_BUILD_XMX_MIB"))) throw new AssertionError(env);
        try {
            BuildMemoryBudget.fromBytes(8 * gib, 7 * gib).applyTo(env);
            throw new AssertionError("low memory accepted");
        } catch (IllegalArgumentException expected) { }

        Path root = Files.createTempDirectory("memory-budget-test-");
        Path parent = Files.createDirectory(root.resolve("parent"));
        Path child = Files.createDirectory(parent.resolve("child"));
        try {
            // Host cache is reclaimable: a 3.7 GiB JVM free figure must not
            // override Linux's 17 GiB MemAvailable on an unlimited host.
            BuildMemoryBudget host = BuildMemoryBudget.measured(31 * gib, 37 * gib / 10,
                    31 * gib, 17 * gib, root, "/parent/child");
            host.applyTo(env);
            if (!"13312".equals(env.get("EAGLER_BUILD_CAP_MIB"))) throw new AssertionError(host.summary());

            Files.writeString(child.resolve("memory.max"), Long.toString(16 * gib));
            Files.writeString(child.resolve("memory.current"), Long.toString(2 * gib));
            BuildMemoryBudget childBound = BuildMemoryBudget.measured(31 * gib, 3 * gib,
                    31 * gib, 17 * gib, root, "/parent/child");
            childBound.applyTo(env);
            if (!"12288".equals(env.get("EAGLER_BUILD_CAP_MIB"))) throw new AssertionError(childBound.summary());
            BuildMemoryBudget jvmSeesCgroup = BuildMemoryBudget.measured(16 * gib, 3 * gib,
                    31 * gib, 17 * gib, root, "/parent/child");
            jvmSeesCgroup.applyTo(env);
            if (!"12288".equals(env.get("EAGLER_BUILD_CAP_MIB"))) {
                throw new AssertionError("JVM raw free overrode readable cgroup: " + jvmSeesCgroup.summary());
            }

            Files.writeString(parent.resolve("memory.max"), Long.toString(14 * gib));
            Files.writeString(parent.resolve("memory.current"), Long.toString(4 * gib));
            if (BuildMemoryBudget.measured(31 * gib, 3 * gib, 31 * gib, 17 * gib,
                    root, "/parent/child").canBuild()) throw new AssertionError("parent cgroup limit ignored");

            Files.writeString(parent.resolve("memory.current"), "bad");
            if (BuildMemoryBudget.measured(31 * gib, 3 * gib, 31 * gib, 17 * gib,
                    root, "/parent/child").canBuild()) throw new AssertionError("unreadable finite limit accepted");

            // If only the JVM can see a smaller domain, its free bytes remain
            // a conservative bound until the cgroup path is readable.
            if (BuildMemoryBudget.measured(16 * gib, 4 * gib, 31 * gib, 17 * gib,
                    root, "/unavailable").canBuild()) throw new AssertionError("JVM container fallback ignored");
            if (BuildMemoryBudget.measured(31 * gib, 3 * gib, 31 * gib, 17 * gib,
                    root, "/../outside").canBuild()) throw new AssertionError("escaped cgroup path accepted");
        } finally {
            Files.deleteIfExists(child.resolve("memory.max"));
            Files.deleteIfExists(child.resolve("memory.current"));
            Files.deleteIfExists(parent.resolve("memory.max"));
            Files.deleteIfExists(parent.resolve("memory.current"));
            Files.deleteIfExists(child);
            Files.deleteIfExists(parent);
            Files.deleteIfExists(root);
        }
        System.out.println("BuildMemoryBudgetTest PASS");
        System.out.println("Detected " + BuildMemoryBudget.detect().summary());
    }
}
