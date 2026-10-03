package com.eaglercraft.patcher;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Focused offline contract checks for recognized project reuse and source preservation. */
public final class ProjectReuseContractTest {
    private static final String OVERLAY_SHA256 =
            "2ba7e3376891c64f8bf57f3687e05b8dbe1971a75475b6825449e5e5f96d71f3";

    private ProjectReuseContractTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("usage: <temporary-root> [known-existing-project]");
        }
        Path root = Path.of(args[0]);
        Files.createDirectory(root);
        try {
            Main.verifyReuseCliContract();
            require(ProjectReuse.guideText().contains("Get game audio")
                            && ProjectReuse.guideText().contains("Get official resources")
                            && ProjectReuse.guideText().contains("does not install FFmpeg"),
                    "generated project guide omitted build input setup");
            Path clean = makeProject(root.resolve("clean"), "class Demo {}\n", true);
            byte[] cleanReceipt = Files.readAllBytes(clean.resolve("receipt.json"));
            String userGuide = "# My notes\nkeep this\n";
            Files.writeString(clean.resolve("GUIDE.md"), userGuide, StandardCharsets.UTF_8);
            require(ProjectReuse.looksLikeProject(clean), "valid receipt/layout was not recognized");
            ProjectReuse.Result cleanResult = ProjectReuse.validate(clean, PatchEngine.acceptedBundleSha256(),
                    ProjectSkeleton.acceptedArchiveSha256(), OVERLAY_SHA256);
            require(cleanResult.sourceMatchesOriginalReceipt(), "unchanged source tree was marked changed");
            require(cleanResult.currentSourceFileCount() == 1, "wrong current source file count");
            require(java.util.Arrays.equals(cleanReceipt, Files.readAllBytes(clean.resolve("receipt.json"))),
                    "reuse changed the original project receipt");
            require(userGuide.equals(Files.readString(clean.resolve("GUIDE.md"))),
                    "reuse overwrote the user's GUIDE.md");

            Path edited = makeProject(root.resolve("edited"), "class Demo { int userEdit = 1; }\n", false);
            byte[] editedSource = Files.readAllBytes(edited.resolve("game/src/main/java/example/Demo.java"));
            byte[] editedReceipt = Files.readAllBytes(edited.resolve("receipt.json"));
            ProjectReuse.Result editedResult = ProjectReuse.validate(edited, PatchEngine.acceptedBundleSha256(),
                    ProjectSkeleton.acceptedArchiveSha256(), OVERLAY_SHA256);
            require(!editedResult.sourceMatchesOriginalReceipt(), "edited source tree was reported unchanged");
            require(!editedResult.currentSourceManifestSha256().equals(
                    editedResult.originalSourceManifestSha256()), "current source manifest hid user edits");
            require(java.util.Arrays.equals(editedSource,
                    Files.readAllBytes(edited.resolve("game/src/main/java/example/Demo.java"))),
                    "reuse altered the user's Java source");
            require(java.util.Arrays.equals(editedReceipt, Files.readAllBytes(edited.resolve("receipt.json"))),
                    "reuse altered the original project receipt");
            require(Files.isRegularFile(edited.resolve("GUIDE.md"), LinkOption.NOFOLLOW_LINKS),
                    "successful reuse validation did not create a missing GUIDE.md");

            expectFailure(() -> ProjectReuse.validate(edited, PatchEngine.acceptedBundleSha256(),
                    ProjectSkeleton.acceptedArchiveSha256(), "0".repeat(64)), "incompatible");
            Path unrelated = Files.createDirectory(root.resolve("unrelated"));
            Files.writeString(unrelated.resolve("receipt.json"), "{}\n");
            require(!ProjectReuse.looksLikeProject(unrelated), "unrelated directory was recognized");
            byte[] unrelatedBytes = Files.readAllBytes(unrelated.resolve("receipt.json"));
            expectFailure(() -> ProjectReuse.validate(unrelated, PatchEngine.acceptedBundleSha256(),
                    ProjectSkeleton.acceptedArchiveSha256(), OVERLAY_SHA256), "not a recognized patcher-created workspace");
            require(java.util.Arrays.equals(unrelatedBytes, Files.readAllBytes(unrelated.resolve("receipt.json"))),
                    "rejected unrelated directory was modified");

            Path installedModules = Files.createDirectories(root.resolve("npm/node_modules/brotli-dec-wasm"));
            Files.writeString(installedModules.resolve("package.json"), "{\"version\":\"2.3.2\"}\n");
            Path installedPkg = Files.createDirectories(installedModules.resolve("pkg"));
            Files.writeString(installedPkg.resolve("brotli_dec_wasm.js"), "pinned decoder js\n");
            Files.write(installedPkg.resolve("brotli_dec_wasm_bg.wasm"), new byte[] { 1, 2, 3, 4 });
            Path modules = ProjectReuse.preparePinnedDecoder(clean, root.resolve("npm/node_modules"));
            require(Files.mismatch(installedPkg.resolve("brotli_dec_wasm.js"),
                    modules.resolve("brotli-dec-wasm/pkg/brotli_dec_wasm.js")) == -1,
                    "pinned decoder JavaScript was not staged at the HTML packer's path");
            require(Files.mismatch(installedPkg.resolve("brotli_dec_wasm_bg.wasm"),
                    modules.resolve("brotli-dec-wasm/pkg/brotli_dec_wasm_bg.wasm")) == -1,
                    "pinned decoder Wasm was not staged at the HTML packer's path");
            Files.writeString(modules.resolve("brotli-dec-wasm/pkg/brotli_dec_wasm.js"), "user bytes\n");
            expectFailure(() -> ProjectReuse.preparePinnedDecoder(clean, root.resolve("npm/node_modules")),
                    "preserving it and stopping");
            require("user bytes\n".equals(Files.readString(
                    modules.resolve("brotli-dec-wasm/pkg/brotli_dec_wasm.js"))),
                    "decoder mismatch handling overwrote existing user bytes");

            Path symlinked = makeProject(root.resolve("symlinked-decoder"), "class Demo {}\n", false);
            Path symlinkedPackage = Files.createDirectories(
                    symlinked.resolve("node_modules/brotli-dec-wasm"));
            Files.createSymbolicLink(symlinkedPackage.resolve("pkg"), installedPkg);
            expectFailure(() -> ProjectReuse.preparePinnedDecoder(symlinked, root.resolve("npm/node_modules")),
                    "pkg path must be a real directory");

            Path temporaryInstall = Files.createDirectories(root.resolve("temporary-npm-install/node_modules"));
            Files.writeString(temporaryInstall.resolve("marker"), "temporary\n");
            Main.cleanupTemporaryNpmInstall(temporaryInstall.getParent());
            require(!Files.exists(temporaryInstall.getParent(), LinkOption.NOFOLLOW_LINKS),
                    "temporary npm install cleanup left its tree behind");

            if (args.length == 2) {
                require(ProjectReuse.looksLikeProject(Path.of(args[1])),
                        "known existing project receipt/layout was not recognized");
                System.out.println("read-only existing project recognition: PASS");
            }
            System.out.println("project reuse contract: PASS (CLI mode, receipt/source preservation, edit reporting, decoder staging, symlink rejection, temporary cleanup)");
        } finally {
            deleteTree(root);
        }
    }

    private static Path makeProject(Path root, String source, boolean cleanReceipt) throws Exception {
        Path java = root.resolve("game/src/main/java/example/Demo.java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, source, StandardCharsets.UTF_8);
        for (String relative : new String[] {
                "baseline-manifest.json", "skeleton-manifest.json", "settings.gradle.kts", "build.gradle.kts",
                "gradlew", "gradle/wrapper/gradle-wrapper.jar", "package.json", "package-lock.json",
                "source/version.json", "game/build.gradle.kts", "wasm-toolchain/build-single-html.js",
                "wasm-toolchain/content-verified-brotli.js" }) {
            Path file = root.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "fixture\n", StandardCharsets.UTF_8);
        }
        String currentManifest = ProjectReuse.sourceManifestDigest(
                Main.scanJavaTree(root.resolve("game/src/main/java")));
        String receiptManifest = cleanReceipt ? currentManifest : "0".repeat(64);
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("tool", "eaglercraft-26.2-java-cli");
        receipt.put("tool_version", "0.1.0-u1");
        receipt.put("command", "create-dev");
        receipt.put("status", "dev-workspace-ready");
        receipt.put("patch_apply_status", "applied");
        receipt.put("official_client_jar_sha256", Main.expectedClientJarSha256());
        receipt.put("patch_bundle_sha256", PatchEngine.acceptedBundleSha256());
        receipt.put("project_skeleton_sha256", ProjectSkeleton.acceptedArchiveSha256());
        receipt.put("project_skeleton_manifest_sha256", ProjectSkeleton.acceptedManifestSha256());
        receipt.put("resource_overlay_sha256", OVERLAY_SHA256);
        receipt.put("final_java_file_count", 1L);
        receipt.put("final_manifest_sha256", receiptManifest);
        Files.write(root.resolve("receipt.json"), Json.canonical(receipt));
        return root;
    }

    private static void expectFailure(Throwing operation, String text) throws Exception {
        try {
            operation.run();
            throw new AssertionError("expected failure containing: " + text);
        } catch (PatchEngine.PatchError error) {
            require(error.getMessage() != null && error.getMessage().contains(text),
                    "unexpected error: " + error.getMessage());
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @FunctionalInterface
    private interface Throwing {
        void run() throws Exception;
    }
}
