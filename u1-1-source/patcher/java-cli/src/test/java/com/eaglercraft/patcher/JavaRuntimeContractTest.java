package com.eaglercraft.patcher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Focused checks for portable Java 17 selection and pinned source identity. */
public final class JavaRuntimeContractTest {
    private JavaRuntimeContractTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: <baseline-source-directory>");
        }

        require(Main.isJava17("openjdk version \"17.0.16\" 2025-07-15"), "OpenJDK 17 was rejected");
        require(Main.isJava17("java version \"17.0.2\""), "Java 17 version output was rejected");
        require(Main.isJava17("openjdk 17.0.2 2022-01-18"), "OpenJDK without the version word was rejected");
        require(!Main.isJava17("openjdk version \"21.0.1\""), "Java 21 was accepted as Java 17");
        require(!Main.isJava17("unknown vendor build 17.0.2"), "unrecognized version output was accepted");
        require(!Main.isJava17(""), "empty version output was accepted");

        List<Main.FileRecord> baseline = Main.scanJavaTree(Path.of(args[0]));
        String acceptedManifest = Main.assertBaselineSourceIdentity(baseline);
        require(baseline.size() == 7_055, "baseline file count changed: " + baseline.size());
        require(acceptedManifest.equals("747714ab16c5c0618c10096d9d243ac4d35356dde617603e97b19dd76acb882b"),
                "baseline source identity changed: " + acceptedManifest);

        List<Main.FileRecord> changedContent = new ArrayList<>(baseline);
        Main.FileRecord first = changedContent.get(0);
        changedContent.set(0, new Main.FileRecord(first.path(), "0".repeat(64), first.size()));
        expectBaselineMismatch(changedContent, "changed source content passed the manifest gate");
        expectBaselineMismatch(baseline.subList(1, baseline.size()), "changed source file count passed the manifest gate");

        System.out.println("Java runtime contract: PASS (Java 17 recognition, wrong/unknown version rejection, exact source identity)");
    }

    private static void expectBaselineMismatch(List<Main.FileRecord> records, String failureMessage) {
        try {
            Main.assertBaselineSourceIdentity(records);
            throw new AssertionError(failureMessage);
        } catch (RuntimeException ex) {
            require(ex.getMessage() != null && ex.getMessage().contains("baseline manifest mismatch"),
                    "unexpected baseline failure: " + ex.getMessage());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
