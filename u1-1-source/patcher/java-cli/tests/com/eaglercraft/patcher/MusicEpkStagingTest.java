package com.eaglercraft.patcher;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;

/** Fixture-only checks for authenticated local music EPK staging. */
public final class MusicEpkStagingTest {
    private MusicEpkStagingTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("music-epk-staging-test-");
        try {
            byte[] epk = "EAGPKG$$local-fixture:::YEE:>".getBytes(StandardCharsets.US_ASCII);
            Path input = root.resolve("music.epk");
            Files.write(input, epk);
            String sha256 = sha256(epk);

            Main.verifyMusicInput(input, sha256);
            Path workspace = root.resolve("workspace");
            Files.createDirectory(workspace);
            Main.MusicInstallResult result = Main.stageMusicEpk(input, sha256, workspace);
            Path staged = workspace.resolve("target_teavm/build/web/music.epk");
            check(result.input().equals(input.toAbsolutePath().normalize()), "input path receipt mismatch");
            check(result.destination().equals(staged), "staged path receipt mismatch");
            check(result.size() == epk.length, "staged size receipt mismatch");
            check(result.sha256().equals(sha256), "staged hash receipt mismatch");
            check(Arrays.equals(Files.readAllBytes(staged), epk), "staged bytes differ from selected EPK");

            Path wrongHashWorkspace = root.resolve("wrong-hash");
            Files.createDirectory(wrongHashWorkspace);
            expectFailure(() -> Main.stageMusicEpk(input, "0".repeat(64), wrongHashWorkspace),
                    "SHA-256 mismatch");
            check(!Files.exists(wrongHashWorkspace.resolve("target_teavm/build/web/music.epk")),
                    "wrong-hash input created staged music");

            Path malformed = root.resolve("malformed.epk");
            Files.write(malformed, "not-an-epk".getBytes(StandardCharsets.US_ASCII));
            Path malformedWorkspace = root.resolve("malformed-workspace");
            Files.createDirectory(malformedWorkspace);
            expectFailure(() -> Main.stageMusicEpk(malformed, sha256(Files.readAllBytes(malformed)),
                    malformedWorkspace), "invalid EPK framing");
            check(!Files.exists(malformedWorkspace.resolve("target_teavm/build/web/music.epk")),
                    "invalid EPK framing created staged music");

            System.out.println("music EPK staging: PASS (hash, framing, staged bytes and result fields)");
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });
            }
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void expectFailure(Throwing action, String expectedMessage) throws Exception {
        try {
            action.run();
            throw new AssertionError("expected failure containing: " + expectedMessage);
        } catch (RuntimeException ex) {
            check(ex.getMessage() != null && ex.getMessage().contains(expectedMessage),
                    "unexpected failure: " + ex.getMessage());
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface Throwing {
        void run() throws Exception;
    }
}
