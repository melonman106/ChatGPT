package com.eaglercraft.patcher;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class SoundsContractTest {
    private SoundsContractTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("eagler-sounds-contract-");
        try {
            byte[] framed = "EAGPKG$$fixture:::YEE:>".getBytes(StandardCharsets.US_ASCII);

            Path valid = root.resolve("selected.epk");
            Files.write(valid, framed);
            String validSha = sha256(valid);
            Path successWorkspace = root.resolve("success-workspace");
            Files.createDirectory(successWorkspace);
            Main.stageSoundsEpk(valid, validSha, successWorkspace);
            Path staged = successWorkspace.resolve("target_teavm/build/web/sounds.epk");
            check(Files.readAllBytes(staged), framed, "success bytes");

            Path absentWorkspace = root.resolve("absent-workspace");
            Files.createDirectory(absentWorkspace);
            expectFailure(() -> Main.stageSoundsEpk(root.resolve("absent.epk"), validSha, absentWorkspace),
                    "not a regular file");
            require(!Files.exists(absentWorkspace.resolve("target_teavm/build/web/sounds.epk")),
                    "absent input created output");

            Path wrongWorkspace = root.resolve("wrong-workspace");
            Files.createDirectory(wrongWorkspace);
            expectFailure(() -> Main.stageSoundsEpk(valid, "0".repeat(64), wrongWorkspace), "SHA-256 mismatch");
            require(!Files.exists(wrongWorkspace.resolve("target_teavm/build/web/sounds.epk")),
                    "wrong hash created output");

            Path tampered = root.resolve("tampered.epk");
            Files.write(tampered, framed);
            String beforeTamper = sha256(tampered);
            Files.writeString(tampered, "changed", StandardCharsets.US_ASCII,
                    java.nio.file.StandardOpenOption.APPEND);
            Path tamperWorkspace = root.resolve("tamper-workspace");
            Files.createDirectory(tamperWorkspace);
            expectFailure(() -> Main.stageSoundsEpk(tampered, beforeTamper, tamperWorkspace),
                    "SHA-256 mismatch");
            require(!Files.exists(tamperWorkspace.resolve("target_teavm/build/web/sounds.epk")),
                    "tampered input created output");

            System.out.println("sounds EPK contract: PASS (success, absent, wrong hash, tamper; no failed promotion)");
        } finally {
            delete(root);
        }
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static void check(byte[] actual, byte[] expected, String label) {
        require(java.util.Arrays.equals(actual, expected), label + " mismatch");
    }

    private static void expectFailure(Throwing action, String expected) throws Exception {
        try {
            action.run();
            throw new AssertionError("expected failure containing: " + expected);
        } catch (RuntimeException ex) {
            require(ex.getMessage() != null && ex.getMessage().contains(expected),
                    "unexpected failure: " + ex.getMessage());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void delete(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    @FunctionalInterface
    private interface Throwing {
        void run() throws Exception;
    }
}
