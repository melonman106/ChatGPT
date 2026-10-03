package com.eaglercraft.patcher;

/** Focused Normal-only source and output-mode policy checks. */
public final class PatcherModeProfileTest {
    private static final String NORMAL_SOURCE = PatchEngine.acceptedBundleSha256();
    private static final String NORMAL_RESOURCES = ResourceOverlay.NORMAL_ARCHIVE_SHA256;

    private PatcherModeProfileTest() {
    }

    public static void main(String[] args) {
        PatchEngine.assertCreateDevProfilePair(NORMAL_SOURCE, NORMAL_RESOURCES);
        expectRejected(() -> PatchEngine.assertCreateDevProfilePair("0".repeat(64), NORMAL_RESOURCES));
        expectRejected(() -> PatchEngine.assertCreateDevProfilePair(NORMAL_SOURCE, "1".repeat(64)));
        PatchEngine.assertWebBuildAllowed(NORMAL_SOURCE);
        PatchEngine.assertIwaBuildAllowed(NORMAL_SOURCE);
        expectRejected(() -> PatchEngine.assertWebBuildAllowed("0".repeat(64)));
        expectRejected(() -> PatchEngine.assertIwaBuildAllowed("0".repeat(64)));
        if (Main.DEFAULT_BUILD_TIMEOUT_SECONDS != 7_200
                || !Main.isSupportedBuildTimeoutSeconds(7_200)
                || Main.isSupportedBuildTimeoutSeconds(7_201)) {
            throw new AssertionError("standalone build timeout must default to and allow two hours, not exceed it");
        }
        System.out.println("Normal-only source, Standalone, and IWA contracts: PASS");
    }

    private static void expectRejected(CheckedAction action) {
        try {
            action.run();
        } catch (PatchEngine.PatchError expected) {
            return;
        }
        throw new AssertionError("Normal-only mode contract accepted an unpinned identity");
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run();
    }
}
