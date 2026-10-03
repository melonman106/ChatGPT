package com.eaglercraft.patcher;

import java.nio.file.Files;
import java.nio.file.Path;

/** Focused contract check for the one accepted standalone project skeleton. */
public final class ProjectSkeletonContractTest {
    private static final String ACCEPTED_SHA256 =
            "a9172bdd903ea65afd4c87cdb29b500c237c823fdd94789c2e90561b1b049469";
    private static final String ACCEPTED_MANIFEST_SHA256 =
            "be269c8f139cc5faaa45e9c44700e89a54ba602114eee3d981b53e4ab6642ff2";

    private ProjectSkeletonContractTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("usage: <skeleton.zip> <temporary-root>");
        Path archive = Path.of(args[0]);
        Path root = Path.of(args[1]);
        Files.createDirectory(root);
        try {
            ProjectSkeleton.assertAcceptedArchive(archive, ACCEPTED_SHA256);
            ProjectSkeleton.Result result = ProjectSkeleton.extract(archive, root.resolve("extracted"), ACCEPTED_SHA256);
            require(result.archiveSha256().equals(ACCEPTED_SHA256), "accepted archive hash mismatch");
            require(result.manifestSha256().equals(ACCEPTED_MANIFEST_SHA256), "accepted manifest hash mismatch");
            require(result.fileCount() == 788, "accepted manifest file count mismatch");
            for (String path : Main.standaloneSkeletonRequiredFiles()) {
                require(Files.isRegularFile(root.resolve("extracted").resolve(path)),
                        "standalone skeleton member missing: " + path);
            }
            require(Files.isRegularFile(root.resolve("extracted/iwa/README.md")),
                    "IWA install guide missing from project skeleton");
            require(Files.isRegularFile(root.resolve("extracted/third_party/skeleton/THIRD_PARTY_NOTICES.md")),
                    "third-party notice inventory missing from project skeleton");

            String clientLinker = Files.readString(root.resolve("extracted/wasm-toolchain/link-web-target-standalone.js"));
            require(clientLinker.contains("org/teavm/backend/wasm/wasm-gc-runtime.min.js")
                            && clientLinker.contains("1e80092312d7bfe6efa74f8bb372d5521bc0549dcfbf384f25a145261a80d124")
                            && clientLinker.contains("WASM_GC_RUNTIME_BYTES = 13984")
                            && clientLinker.contains("installClientRuntime(toolClasspath"),
                    "client linker must derive and verify the current TeaVM runtime resource");
            String builder = Files.readString(root.resolve("extracted/wasm-toolchain/build-single-html.js"));
            require(builder.contains("invalid TeaVM 0.13.1 runtime JS")
                            && builder.contains("1e80092312d7bfe6efa74f8bb372d5521bc0549dcfbf384f25a145261a80d124"),
                    "standalone builder must fail closed on a wrong or missing runtime");
            require(builder.contains("detectBuildMemory()")
                            && builder.contains("EAGLER_BUILD_XMX_MIB")
                            && builder.contains("at least 10 GiB after the system reserve"),
                    "standalone builder must use a measured RAM budget and heap");
            String gradleProperties = Files.readString(root.resolve("extracted/gradle.properties"));
            require(gradleProperties.contains("org.gradle.jvmargs=-Xmx8192m"),
                    "direct Gradle task fallback must exceed the old 4 GiB heap");
            String deploy = Files.readString(root.resolve("extracted/wasm-toolchain/deploy_wasm_web.sh"));
            require(deploy.contains("target_teavm_wasm_gc/build/generated/teavm/wasm-gc/classes.wasm-runtime.js")
                            && deploy.contains("ERROR: missing authenticated TeaVM 0.13.1 client runtime")
                            && !deploy.contains("target_teavm_spike/build/generated"),
                    "deployment must require only the current client runtime, not the stale spike donor");

            expectFailure(() -> ProjectSkeleton.assertAcceptedArchive(archive, "0".repeat(64)),
                    "not the accepted v5 archive identity");
            Path tampered = root.resolve("tampered.zip");
            byte[] bytes = Files.readAllBytes(archive);
            bytes[bytes.length - 1] ^= 1;
            Files.write(tampered, bytes);
            expectFailure(() -> ProjectSkeleton.assertAcceptedArchive(tampered, ACCEPTED_SHA256),
                    "project skeleton SHA-256 mismatch");

            System.out.println("project skeleton contract: PASS (u1 netty-fix pin, manifest, current runtime members, wrong-pin and tamper rejection)");
        } finally {
            deleteTree(root);
        }
    }

    private static void expectFailure(Throwing action, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError("expected failure containing: " + message);
        } catch (PatchEngine.PatchError ex) {
            require(ex.getMessage() != null && ex.getMessage().contains(message),
                    "unexpected failure: " + ex.getMessage());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void deleteTree(Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            for (Path item : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
        }
    }

    @FunctionalInterface
    private interface Throwing {
        void run() throws Exception;
    }
}
