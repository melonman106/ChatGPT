package com.eaglercraft.patcher;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Command-line entry point for JAR validation, source reconstruction, and standalone builds. */
public final class Main {
    private static final String TOOL_VERSION = "0.1.0-u1";
    private static final long EXPECTED_CLIENT_SIZE = 39_193_383L;
    private static final String EXPECTED_CLIENT_SHA1 = "2dc72797acbc1b63fc16a11c4ac393605f453754";
    private static final String EXPECTED_CLIENT_SHA256 = "40896ee9f1e2bec3c934daac7e93d41e9e3d9c2f8ae0ca366d52ffbfd1afa290";
    private static final String EXPECTED_VINEFLOWER_SHA256 = "1dfcfe974395734fa467ce620661c7623d05ba83670de0529b1fbd63ff548b9d";
    private static final String EXPECTED_VINEFLOWER_VERSION = "1.12.0";
    private static final String EXPECTED_MANIFEST_SHA256 = "747714ab16c5c0618c10096d9d243ac4d35356dde617603e97b19dd76acb882b";
    private static final int EXPECTED_JAVA_FILES = 7_055;
    static final int DEFAULT_BUILD_TIMEOUT_SECONDS = 7_200;
    private static final int MIN_BUILD_TIMEOUT_SECONDS = 60;
    private static final int MAX_BUILD_TIMEOUT_SECONDS = 7_200;
    private static final long MAX_ENTRY_UNCOMPRESSED = 64L * 1024L * 1024L;
    private static final long MAX_ARCHIVE_UNCOMPRESSED = 512L * 1024L * 1024L;
    private static final int MAX_ARCHIVE_ENTRIES = 100_000;
    private static final int MAX_VERSION_BYTES = 64 * 1024;
    private static final int MAX_WISPCRAFT_SCRIPT_BYTES = 16 * 1024 * 1024;
    private static final int MAX_HTML_HEAD_SCAN_BYTES = 1024 * 1024;
    private static final byte[] SCRIPT_END_TAG = {'<', '/', 's', 'c', 'r', 'i', 'p', 't'};
    private static final Pattern JAVA_VERSION = Pattern.compile(
            "(?:\\b(?:openjdk|java)\\s+(?:version\\s+)?|\\bversion\\s+)[\\\"]?(\\d+)(?:[.\\\"'\\s]|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION_ID = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"26\\.2\\\"", Pattern.MULTILINE);
    private static final Pattern WORLD_VERSION = Pattern.compile("\\\"world_version\\\"\\s*:\\s*4903(?:\\s*[,}])", Pattern.MULTILINE);
    private static final Pattern PROTOCOL_VERSION = Pattern.compile("\\\"protocol_version\\\"\\s*:\\s*776(?:\\s*[,}])", Pattern.MULTILINE);
    private static final Pattern JAVA_COMPONENT_VERSION = Pattern.compile("\\\"java_version\\\"\\s*:\\s*25(?:\\s*[,}])", Pattern.MULTILINE);
    private static final Set<Process> ACTIVE_IWA_CHILDREN = ConcurrentHashMap.newKeySet();
    private static final Set<Path> ACTIVE_IWA_TEMP_PATHS = ConcurrentHashMap.newKeySet();
    private static final Set<IwaOutputFile> ACTIVE_IWA_PROMOTIONS = ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Process process : ACTIVE_IWA_CHILDREN) terminateProcessTree(process);
            for (IwaOutputFile output : ACTIVE_IWA_PROMOTIONS) {
                try { deleteOwnedOutput(output); } catch (IOException ignored) { }
            }
            for (Path path : ACTIVE_IWA_TEMP_PATHS) {
                try { deleteTree(path); } catch (IOException ignored) { }
            }
        }, "eagler-iwa-child-cleanup"));
    }

    private Main() {
    }

    static String expectedClientJarSha256() {
        return EXPECTED_CLIENT_SHA256;
    }

    public static void main(String[] args) {
        try {
            if (args.length == 1 && "--self-test".equals(args[0])) {
                verifyIwaCliContract();
                System.out.println("iwa-cli-self-test: PASS");
                return;
            }
            if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
                usage(System.out);
                return;
            }
            if ("create-dev".equals(args[0])) {
                Options options = Options.parse(args);
                Receipt receipt = createDev(options);
                System.out.println(receipt.toJson());
                return;
            }
            if ("build-standalone".equals(args[0])) {
                BuildOptions options = BuildOptions.parse(args, false);
                buildStandalone(options);
                return;
            }
            if ("build-iwa".equals(args[0])) {
                BuildOptions options = BuildOptions.parse(args, true);
                buildIwa(options);
                return;
            }
            if ("apply-patch".equals(args[0])) {
                PatchOptions options = PatchOptions.parse(args);
                PatchEngine.Result result = PatchEngine.apply(options.bundle, options.baseSource,
                        options.output, options.expectedBundleSha256);
                System.out.println("{\"status\":\"applied\",\"bundle_sha256\":\""
                        + result.bundleSha256() + "\",\"final_file_count\":" + result.finalFileCount()
                        + ",\"final_manifest_sha256\":\"" + result.finalManifestSha256() + "\"}");
                return;
            }
            throw new CliError("unknown command: " + args[0]);
        } catch (CliError | PatchEngine.PatchError | IOException | SecurityException ex) {
            System.err.println("ERROR: " + ex.getMessage());
            System.exit(2);
        }
    }

    private static void usage(PrintStream sink) {
        sink.println("Eaglercraft 26.2 u1 JAR patch preflight " + TOOL_VERSION);
        sink.println("Usage: create-dev --jar <official-26.2.jar> --output <empty-or-absent-dir>"
                + " --vineflower <vineflower-1.12.0.jar> --java17 <java-17-executable>"
                + " --patch-bundle <source-patch-bundle.zip> --expected-bundle-sha256 <sha256>"
                + " [--project-skeleton <project-skeleton-u1-nettyfix.zip> --expected-skeleton-sha256 <sha256>]"
                + " [--resource-overlay <resource-overlay.zip> --expected-resource-overlay-sha256 <sha256>"
                + " --external-resource-root <resource-root>]");
        sink.println("       apply-patch --base-source <decompiled-java> --output <absent-dir>"
                + " --patch-bundle <source-patch-bundle.zip> --expected-bundle-sha256 <sha256>");
        sink.println("       build-standalone <all create-dev options> --java25 <java-25-executable>"
                + " --node <node-executable> --npm <npm-cli.js-or-launcher>"
                + " --sounds-epk <local-sounds.epk> --expected-sounds-epk-sha256 <sha256>"
                + " --music-epk <local-music.epk> --expected-music-epk-sha256 <sha256>"
                + " --standalone-output <absent-html-path>"
                + " [--reuse-project [--reuse-client]] [--wispcraft-script <local-dist-index.js>]"
                + " [--with-music | --music-pack-output <absent-zip-path>]"
                + " [--npm-timeout-seconds <seconds>] [--build-timeout-seconds <seconds>]");
        sink.println("       build-iwa <all create-dev options> --java25 <java-25-executable>"
                + " --node <node-executable> --npm <npm-cli.js-or-launcher>"
                + " --sounds-epk <local-sounds.epk> --expected-sounds-epk-sha256 <sha256>"
                + " --music-epk <local-music.epk> --expected-music-epk-sha256 <sha256>"
                + " --iwa-output <absent.swbn> [--iwa-key <local-private-key.pem>]"
                + " [--standalone-output <absent.html>]"
                + " [--npm-timeout-seconds <seconds>] [--build-timeout-seconds <seconds>]");
        sink.println("create-dev reconstructs a workspace; build-standalone additionally runs the"
                + " authenticated skeleton's standalone builder. build-iwa packages that compiled workspace locally."
                + " Wispcraft is user-supplied, never downloaded."
                + " A caller-supplied music EPK is authenticated and staged locally. Music defaults to a"
                + " lean HTML plus optional ZIP."
                + " Source project, build-standalone, and build-iwa accept the Normal profile only."
                + " build-standalone --reuse-project reuses the existing --output workspace after receipt validation.");
    }

    private static void verifyIwaCliContract() throws IOException {
        String[] arguments = { "build-iwa", "--jar", "input/client.jar", "--output", "out/project",
                "--vineflower", "input/vineflower.jar", "--java17", "tools/java17/bin/java",
                "--patch-bundle", "input/patches.zip", "--expected-bundle-sha256", "a".repeat(64),
                "--project-skeleton", "input/skeleton.zip", "--expected-skeleton-sha256", "b".repeat(64),
                "--resource-overlay", "input/resources.zip", "--expected-resource-overlay-sha256", "c".repeat(64),
                "--external-resource-root", "game/src/main/resources", "--java25", "tools/java25/bin/java",
                "--node", "tools/node/bin/node", "--npm", "tools/npm/bin/npm-cli.js",
                "--sounds-epk", "input/sounds.epk", "--expected-sounds-epk-sha256", "d".repeat(64),
                "--music-epk", "input/music.epk", "--expected-music-epk-sha256", "e".repeat(64),
                "--iwa-output", "out/client.swbn" };
        BuildOptions parsed = BuildOptions.parse(arguments, true);
        if (!parsed.isolatedApp || parsed.iwaOutput == null || parsed.standaloneOutput != null
                || parsed.create.projectSkeleton == null || parsed.create.resourceOverlay == null) {
            throw new IllegalStateException("build-iwa options were not parsed as a complete local IWA build");
        }
        List<String> withHtml = new ArrayList<>(List.of(arguments));
        withHtml.add("--standalone-output");
        withHtml.add("out/eaglercraft-26.2-u1.html");
        BuildOptions parsedWithHtml = BuildOptions.parse(withHtml.toArray(String[]::new), true);
        if (parsedWithHtml.standaloneOutput == null
                || !parsedWithHtml.standaloneOutput.toString().endsWith("eaglercraft-26.2-u1.html")) {
            throw new IllegalStateException("build-iwa did not accept its optional same-build HTML output");
        }
        String javaName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        Path currentJava = Path.of(System.getProperty("java.home"), "bin", javaName);
        Path expectedJavaHome = currentJava.toRealPath().getParent().getParent();
        if (!expectedJavaHome.equals(javaHomeForExecutable(currentJava))) {
            throw new IllegalStateException("selected Java executable did not resolve to its actual JDK home");
        }
        ProcessBuilder environmentProbe = new ProcessBuilder("unused-test-command");
        configureNodeEnvironment(environmentProbe, expectedJavaHome);
        if (!expectedJavaHome.toString().equals(environmentProbe.environment().get("JAVA_HOME"))) {
            throw new IllegalStateException("selected Java 25 home was not forwarded to the build environment");
        }
        List<String> invalid = new ArrayList<>(List.of(arguments));
        invalid.add("--standalone-output");
        invalid.add("out/client.html");
        invalid.add("--wispcraft-script");
        invalid.add("local.js");
        try {
            BuildOptions.parse(invalid.toArray(String[]::new), true);
            throw new IllegalStateException("build-iwa accepted a Wispcraft script option");
        } catch (CliError expected) {
            if (!expected.getMessage().contains("does not accept Wispcraft")) throw expected;
        }
    }

    static void verifyReuseCliContract() {
        List<String> arguments = new ArrayList<>(List.of(
                "build-standalone", "--jar", "inputs/client.jar", "--output", "existing-project",
                "--vineflower", "inputs/vineflower.jar", "--java17", "tools/java17/bin/java",
                "--patch-bundle", "inputs/source-patch-bundle.zip", "--expected-bundle-sha256",
                PatchEngine.acceptedBundleSha256(), "--project-skeleton", "inputs/project-skeleton.zip",
                "--expected-skeleton-sha256", ProjectSkeleton.acceptedArchiveSha256(),
                "--resource-overlay", "inputs/resource-overlay.zip", "--expected-resource-overlay-sha256",
                "2ba7e3376891c64f8bf57f3687e05b8dbe1971a75475b6825449e5e5f96d71f3",
                "--external-resource-root", "inputs/resources", "--java25", "tools/java25/bin/java",
                "--node", "tools/node/bin/node", "--npm", "tools/npm/bin/npm-cli.js",
                "--sounds-epk", "inputs/sounds.epk", "--expected-sounds-epk-sha256", "d".repeat(64),
                "--music-epk", "inputs/music.epk", "--expected-music-epk-sha256", "e".repeat(64),
                "--standalone-output", "out/client.html", "--reuse-project"));
        BuildOptions standalone = BuildOptions.parse(arguments.toArray(String[]::new), false);
        if (!standalone.reuseProject || !Path.of("existing-project").equals(standalone.create.output)) {
            throw new IllegalStateException("build-standalone did not bind --reuse-project to its existing --output workspace");
        }
        List<String> iwa = new ArrayList<>(arguments);
        iwa.set(0, "build-iwa");
        iwa.remove("--standalone-output");
        iwa.remove("out/client.html");
        iwa.addAll(List.of("--iwa-output", "out/client.swbn"));
        try {
            BuildOptions.parse(iwa.toArray(String[]::new), true);
            throw new IllegalStateException("build-iwa accepted standalone-only --reuse-project");
        } catch (CliError expected) {
            if (!expected.getMessage().contains("does not accept")) throw expected;
        }
    }

    private static void progress(String stage, long startedNanos) {
        long elapsed = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000_000L);
        System.err.println("PROGRESS stage=" + stage + " elapsed_seconds=" + elapsed);
    }

    private static Receipt createDev(Options options) throws IOException {
        long progressStarted = System.nanoTime();
        progress("verify-inputs", progressStarted);
        Path jar = absolute(options.jar);
        Path output = absolute(options.output);
        Path vineflower = absolute(options.vineflower);
        Path java17 = absolute(options.java17);
        BuildMemoryBudget sourceBudget = BuildMemoryBudget.detect();
        int decompilerHeapMiB;
        try {
            decompilerHeapMiB = sourceBudget.decompilerHeapMiB();
        } catch (IllegalArgumentException ex) {
            throw new CliError(ex.getMessage());
        }
        System.err.println(sourceBudget.sourceSummary());
        Path patchBundle = absolute(options.patchBundle);
        Path projectSkeleton = options.projectSkeleton == null ? null : absolute(options.projectSkeleton);
        Path resourceOverlay = options.resourceOverlay == null ? null : absolute(options.resourceOverlay);
        Path externalResourceRoot = options.externalResourceRoot == null ? null : absolute(options.externalResourceRoot);
        ensureRegular(jar, "official client JAR");
        ensureRegular(vineflower, "Vineflower JAR");
        ensureExecutable(java17, "Java 17 executable");
        PatchEngine.assertAcceptedBundle(patchBundle, options.expectedBundleSha256);
        PatchEngine.assertCreateDevProfilePair(options.expectedBundleSha256,
                resourceOverlay == null ? null : options.expectedResourceOverlaySha256);
        if (projectSkeleton != null) {
            ProjectSkeleton.assertAcceptedArchive(projectSkeleton, options.expectedSkeletonSha256);
        }
        if (resourceOverlay != null) {
            ResourceOverlay.assertAcceptedArchive(resourceOverlay, options.expectedResourceOverlaySha256);
        }
        ensureSafeOutput(output);

        Hashes jarHashes = hashFile(jar, true);
        if (jarHashes.size != EXPECTED_CLIENT_SIZE) {
            throw new CliError("official client JAR size mismatch: expected " + EXPECTED_CLIENT_SIZE
                    + ", got " + jarHashes.size);
        }
        if (!EXPECTED_CLIENT_SHA1.equals(jarHashes.sha1) || !EXPECTED_CLIENT_SHA256.equals(jarHashes.sha256)) {
            throw new CliError("official client JAR hash mismatch: sha1=" + jarHashes.sha1
                    + " sha256=" + jarHashes.sha256);
        }
        validateClientZip(jar);

        Hashes vineflowerHashes = hashFile(vineflower, false);
        if (!EXPECTED_VINEFLOWER_SHA256.equals(vineflowerHashes.sha256)) {
            throw new CliError("Vineflower SHA-256 mismatch: " + vineflowerHashes.sha256);
        }
        String javaVersion = readProcessVersion(java17);
        if (!isJava17(javaVersion)) {
            throw new CliError("the supplied Java executable must report a recognized Java 17 version: "
                    + firstLine(javaVersion));
        }
        Hashes javaHashes = hashFile(java17, false);
        Hashes javaModulesHashes = observeJavaRuntimeModules(java17);

        Path parent = output.getParent();
        if (parent == null) {
            throw new CliError("output must have a parent directory: " + output);
        }
        ensureNoSymlinkAncestors(parent, "output parent");
        Files.createDirectories(parent);
        Path staging = parent.resolve("." + output.getFileName() + ".staging-" + UUID.randomUUID());
        Files.createDirectory(staging);
        boolean promoted = false;
        long started = System.nanoTime();
        try {
            progress("extract-skeleton", progressStarted);
            ProjectSkeleton.Result skeletonResult = projectSkeleton == null ? null
                    : ProjectSkeleton.extract(projectSkeleton, staging, options.expectedSkeletonSha256);
            ProjectReuse.ensureGuide(staging);
            Path source = staging.resolve("source");
            Files.createDirectory(source);
            Path log = staging.resolve("vineflower.log");
            progress("decompile", progressStarted);
            int exit = runVineflower(java17, vineflower, jar, source, log, options.timeoutSeconds, staging,
                    decompilerHeapMiB);
            if (exit != 0) {
                throw new CliError("Vineflower failed with exit code " + exit + "; see " + log);
            }
            List<FileRecord> records = scanJavaTree(source);
            String manifestSha256 = assertBaselineSourceIdentity(records);
            writeManifest(staging.resolve("baseline-manifest.json"), records, manifestSha256);
            progress("apply-source-patches", progressStarted);
            Path patchedSource = staging.resolve("game/src/main/java");
            Files.createDirectories(patchedSource.getParent());
            PatchEngine.Result patchResult = PatchEngine.apply(patchBundle, source, patchedSource,
                    options.expectedBundleSha256);
            ResourceOverlay.Result resourceResult = resourceOverlay == null ? null
                    : ResourceOverlay.apply(resourceOverlay, jar, staging.resolve("game/src/main/resources"),
                    options.expectedResourceOverlaySha256, externalResourceRoot);
            progress("write-receipt", progressStarted);
            Receipt receipt = new Receipt(jar, output, vineflower, java17, jarHashes, vineflowerHashes,
                    javaHashes, javaModulesHashes, javaVersion, records.size(), manifestSha256,
                    patchBundle, options.expectedBundleSha256, projectSkeleton, options.expectedSkeletonSha256,
                    skeletonResult, resourceOverlay, options.expectedResourceOverlaySha256, externalResourceRoot,
                    resourceResult, patchResult, elapsedSeconds(started),
                    options.timeoutSeconds);
            Files.writeString(staging.resolve("receipt.json"), receipt.toJson() + "\n", StandardCharsets.UTF_8);
            promote(staging, output);
            promoted = true;
            return receipt;
        } finally {
            if (!promoted) {
                deleteTree(staging);
            }
        }
    }

    private static void buildStandalone(BuildOptions build) throws IOException {
        Options create = build.create;
        PatchEngine.assertWebBuildAllowed(create.expectedBundleSha256);
        if (create.projectSkeleton == null) {
            throw new CliError("build-standalone requires --project-skeleton and --expected-skeleton-sha256");
        }
        if (create.resourceOverlay == null) {
            throw new CliError("build-standalone requires --resource-overlay and --external-resource-root");
        }
        requireStandaloneSkeleton(create.projectSkeleton);
        Path java25 = absolute(build.java25);
        Path node = absolute(build.node);
        Path npm = absolute(build.npm);
        Path soundsInput = absolute(build.soundsEpk);
        Path musicInput = absolute(build.musicEpk);
        Path standaloneOutput = absolute(build.standaloneOutput);
        Path workspace = absolute(create.output);
        Path standaloneReceiptOutput = build.reuseProject
                ? standaloneOutput.resolveSibling(standaloneOutput.getFileName() + ".receipt.json")
                : workspace.resolve("standalone-receipt.json");
        ensureSafeFileOutput(standaloneOutput);
        ensureSafeFileOutput(standaloneReceiptOutput);
        BuildMemoryBudget memoryBudget = BuildMemoryBudget.detect();
        try {
            memoryBudget.requireBuildCapacity();
        } catch (IllegalArgumentException ex) {
            throw new CliError(ex.getMessage());
        }
        System.err.println(memoryBudget.summary());
        ProjectReuse.Result reuse = null;
        Receipt createReceipt = null;
        if (build.reuseProject) {
            Path patchBundle = absolute(create.patchBundle);
            Path projectSkeleton = absolute(create.projectSkeleton);
            Path resourceOverlay = absolute(create.resourceOverlay);
            PatchEngine.assertAcceptedBundle(patchBundle, create.expectedBundleSha256);
            PatchEngine.assertCreateDevProfilePair(create.expectedBundleSha256,
                    create.expectedResourceOverlaySha256);
            ProjectSkeleton.assertAcceptedArchive(projectSkeleton, create.expectedSkeletonSha256);
            ResourceOverlay.assertAcceptedArchive(resourceOverlay, create.expectedResourceOverlaySha256);
            verifyCreateDevInputs(create);
            reuse = ProjectReuse.validate(workspace, create.expectedBundleSha256,
                    create.expectedSkeletonSha256, create.expectedResourceOverlaySha256);
            requireStandaloneProject(workspace);
            if (standaloneOutput.startsWith(workspace)) {
                throw new CliError("standalone HTML output must be outside the reused project folder: "
                        + standaloneOutput);
            }
        }
        ScriptInput wispcraftScript = build.wispcraftScript == null ? null
                : readWispcraftScript(absolute(build.wispcraftScript));
        verifySoundsInput(soundsInput, build.expectedSoundsEpkSha256);
        verifyMusicInput(musicInput, build.expectedMusicEpkSha256);
        ensureExecutable(java25, "Java 25 executable");
        ensureExecutable(node, "Node.js executable");
        String javaVersion = readProcessVersion(java25);
        if (!isJavaMajor(javaVersion, "25")) {
            throw new CliError("the supplied standalone-build Java executable is not Java 25: " + javaVersion);
        }
        String nodeVersion = readCommandVersion(node, "--version");
        if (!nodeVersion.matches("^v?(?:2[0-9]|[3-9][0-9])\\..*")) {
            throw new CliError("Node.js 20 or newer is required: " + nodeVersion);
        }
        Path musicPackOutput = build.musicPackOutput == null ? null : absolute(build.musicPackOutput);
        Path musicPackPartial = null;
        if (musicPackOutput != null) {
            if (musicPackOutput.equals(standaloneOutput)) {
                throw new CliError("music ZIP output must differ from standalone HTML output");
            }
            ensureSafeFileOutput(musicPackOutput);
            Path musicParent = musicPackOutput.getParent();
            if (musicParent == null) {
                throw new CliError("music ZIP output must have a parent directory");
            }
            ensureNoSymlinkAncestors(musicParent, "music ZIP output parent");
            Files.createDirectories(musicParent);
            musicPackPartial = musicParent.resolve("." + musicPackOutput.getFileName()
                    + ".partial-" + UUID.randomUUID());
            ensureSafeFileOutput(musicPackPartial);
        }
        if (build.reuseProject) ProjectReuse.ensureGuide(workspace);

        long started = System.nanoTime();
        if (build.reuseProject) {
            progress("reuse-project", started);
        } else {
            progress("create-dev", started);
            createReceipt = createDev(create);
        }
        SoundsInstallResult soundsInstall = stageSoundsEpk(soundsInput,
                build.expectedSoundsEpkSha256, workspace, build.reuseProject);
        MusicInstallResult musicInstall = stageMusicEpk(musicInput,
                build.expectedMusicEpkSha256, workspace, build.reuseProject);
        progress("npm-ci", started);
        NpmInstallResult npmInstall = runPinnedNpmCi(node, npm, workspace,
                build.npmTimeoutSeconds, started, build.reuseProject, memoryBudget);
        try {
        Path nodeModulesDirectory = build.reuseProject
                ? ProjectReuse.preparePinnedDecoder(workspace, npmInstall.nodeModulesDirectory)
                : npmInstall.nodeModulesDirectory;
        Path builder = workspace.resolve("wasm-toolchain/build-single-html.js");
        ensureRegular(builder, "standalone build script");
        Path parent = standaloneOutput.getParent();
        if (parent == null) {
            throw new CliError("standalone output must have a parent directory");
        }
        ensureNoSymlinkAncestors(parent, "standalone output parent");
        Files.createDirectories(parent);
        Path partial = parent.resolve("." + standaloneOutput.getFileName() + ".partial-" + UUID.randomUUID());
        boolean promoted = false;
        try {
            progress("build-standalone", started);
            List<String> command = new ArrayList<>(List.of(node.toString(), builder.toString(),
                    "--output", partial.toString()));
            if (build.withMusic) {
                command.add("--with-music");
            }
            if (build.reuseClient) {
                command.add("--reuse-client");
            }
            if (musicPackPartial != null) {
                command.add("--music-pack-output");
                command.add(musicPackPartial.toString());
            }
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(workspace.toFile());
            processBuilder.inheritIO();
            configureNodeEnvironment(processBuilder, javaHomeForExecutable(java25));
            processBuilder.environment().put("NODE_PATH", nodeModulesDirectory.toString());
            BuildMemoryBudget launchBudget = BuildMemoryBudget.detect();
            requireBuildCapacity(launchBudget);
            System.err.println(launchBudget.summary());
            launchBudget.applyTo(processBuilder.environment());
            Process process = processBuilder.start();
            long buildStarted = System.nanoTime();
            while (true) {
                try {
                    if (process.waitFor(30L, java.util.concurrent.TimeUnit.SECONDS)) {
                        break;
                    }
                } catch (InterruptedException ex) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                    throw new CliError("standalone build was interrupted");
                }
                long buildElapsed = (System.nanoTime() - buildStarted) / 1_000_000_000L;
                progress("build-standalone", started);
                if (buildElapsed >= build.buildTimeoutSeconds) {
                    process.destroy();
                    try {
                        if (!process.waitFor(10L, java.util.concurrent.TimeUnit.SECONDS)) {
                            process.destroyForcibly();
                        }
                    } catch (InterruptedException ex) {
                        process.destroyForcibly();
                        Thread.currentThread().interrupt();
                    }
                    throw new CliError("standalone build timed out after " + build.buildTimeoutSeconds + " seconds");
                }
            }
            if (process.exitValue() != 0) {
                throw new CliError("standalone builder failed with exit code " + process.exitValue());
            }
            ensureRegular(partial, "standalone builder output");
            if (wispcraftScript != null) {
                injectWispcraftScriptIntoHtml(partial, wispcraftScript.contents);
            }
            Hashes musicArtifact = null;
            if (musicPackPartial != null) {
                ensureRegular(musicPackPartial, "standalone music ZIP output");
                musicArtifact = hashFile(musicPackPartial, false);
            }
            boolean musicPackPromoted = false;
            Object musicPackFileKey = null;
            try {
                if (musicPackPartial != null) {
                    Files.move(musicPackPartial, musicPackOutput, StandardCopyOption.ATOMIC_MOVE);
                    musicPackPromoted = true;
                    musicPackFileKey = Files.readAttributes(musicPackOutput, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS).fileKey();
                }
                Files.move(partial, standaloneOutput, StandardCopyOption.ATOMIC_MOVE);
                promoted = true;
            } catch (IOException publicationFailure) {
                if (musicPackPromoted) {
                    try {
                        rollbackMusicPackOutput(musicPackOutput, musicPackPartial, musicArtifact,
                                musicPackFileKey);
                    } catch (IOException rollbackFailure) {
                        throw new IOException(publicationFailure.getMessage()
                                + "; music ZIP rollback also failed and it may remain at "
                                + musicPackOutput + ": " + rollbackFailure.getMessage(), publicationFailure);
                    }
                }
                throw publicationFailure;
            }
            Hashes artifact = hashFile(standaloneOutput, false);
            String sourceReceiptSha256 = hashFile(workspace.resolve("receipt.json"), false).sha256;
            String originalSourceManifest = reuse == null
                    ? createReceipt.patchResult.finalManifestSha256()
                    : reuse.originalSourceManifestSha256();
            String currentSourceManifest = reuse == null
                    ? originalSourceManifest : reuse.currentSourceManifestSha256();
            int currentSourceFileCount = reuse == null
                    ? createReceipt.patchResult.finalFileCount() : reuse.currentSourceFileCount();
            long originalSourceFileCount = reuse == null
                    ? currentSourceFileCount : reuse.originalSourceFileCount();
            String receipt = standaloneReceiptHeader(workspace, javaVersion, nodeVersion, npmInstall,
                    soundsInstall, musicInstall)
                    + musicReceiptFields(build.musicPackOutput == null ? null : musicPackOutput, musicArtifact)
                    + wispcraftReceiptFields(wispcraftScript)
                    + "  \"reuse_project\":" + build.reuseProject + ",\n"
                    + "  \"source_project_receipt_sha256\":" + jsonString(sourceReceiptSha256) + ",\n"
                    + "  \"source_original_patched_manifest_sha256\":"
                    + jsonString(originalSourceManifest) + ",\n"
                    + "  \"source_current_manifest_sha256\":" + jsonString(currentSourceManifest) + ",\n"
                    + "  \"source_original_file_count\":" + originalSourceFileCount + ",\n"
                    + "  \"source_current_file_count\":" + currentSourceFileCount + ",\n"
                    + "  \"source_change_status\":"
                    + jsonString(reuse == null || reuse.sourceMatchesOriginalReceipt()
                            ? "matches-original-project-receipt" : "modified-since-project-receipt") + ",\n"
                    + "  \"status\":\"standalone-built\",\n"
                    + "  \"standalone_output\":" + jsonString(standaloneOutput.toString()) + ",\n"
                    + "  \"standalone_size\":" + artifact.size + ",\n"
                    + "  \"standalone_sha256\":\"" + artifact.sha256 + "\",\n"
                    + "  \"create_dev_status\":" + jsonString(build.reuseProject
                            ? "reused-existing-dev-workspace" : "dev-workspace-ready") + ",\n"
                    + "  \"elapsed_seconds\":" + elapsedSeconds(started) + "\n"
                    + "}\n";
            Files.writeString(standaloneReceiptOutput, receipt, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            progress("complete", started);
            System.out.print(receipt);
        } finally {
            if (!promoted) {
                Files.deleteIfExists(partial);
            }
            if (musicPackPartial != null) {
                Files.deleteIfExists(musicPackPartial);
            }
        }
        } finally {
            cleanupTemporaryNpmInstall(npmInstall.temporaryRoot);
        }
    }

    private static void buildIwa(BuildOptions build) throws IOException {
        Options create = build.create;
        PatchEngine.assertIwaBuildAllowed(create.expectedBundleSha256);
        BuildMemoryBudget memoryBudget = BuildMemoryBudget.detect();
        requireBuildCapacity(memoryBudget);
        System.err.println(memoryBudget.summary());
        if (create.projectSkeleton == null) {
            throw new CliError("build-iwa requires --project-skeleton and --expected-skeleton-sha256");
        }
        if (create.resourceOverlay == null) {
            throw new CliError("build-iwa requires --resource-overlay and --external-resource-root");
        }
        requireStandaloneSkeleton(create.projectSkeleton);
        requireIwaSkeleton(create.projectSkeleton);
        Path java25 = absolute(build.java25);
        Path node = absolute(build.node);
        Path npm = absolute(build.npm);
        Path soundsInput = absolute(build.soundsEpk);
        Path musicInput = absolute(build.musicEpk);
        Path iwaOutput = absolute(build.iwaOutput);
        Path standaloneOutput = build.standaloneOutput == null ? null : absolute(build.standaloneOutput);
        Path workspace = absolute(create.output);
        if (iwaOutput.getParent() == null || iwaOutput.getFileName() == null) {
            throw new CliError("IWA output must name a file inside a directory");
        }
        Path key = build.iwaKey == null ? workspace.resolve("iwa/local-dev-key.pem")
                : absolute(build.iwaKey);
        if (key.getParent() == null || key.getFileName() == null) {
            throw new CliError("IWA signing key must name a file inside a directory");
        }
        Path receiptOutput = iwaReceiptPath(iwaOutput);
        Path infoOutput = iwaInfoPath(iwaOutput);
        Path hashOutput = iwaHashPath(iwaOutput);
        Path musicPackOutput = standaloneOutput == null ? null : musicResourcePackPath(standaloneOutput);
        List<Path> publishedOutputs = new ArrayList<>(List.of(iwaOutput, infoOutput, hashOutput, receiptOutput));
        if (standaloneOutput != null) publishedOutputs.add(standaloneOutput);
        if (musicPackOutput != null) publishedOutputs.add(musicPackOutput);
        Set<Path> uniqueOutputs = new HashSet<>();
        for (Path output : publishedOutputs) {
            if (!uniqueOutputs.add(output)) throw new CliError("IWA output paths overlap: " + output);
            if (output.startsWith(workspace)) {
                throw new CliError("IWA outputs must be outside the project folder: " + output);
            }
        }
        verifySoundsInput(soundsInput, build.expectedSoundsEpkSha256);
        verifyMusicInput(musicInput, build.expectedMusicEpkSha256);
        ensureExecutable(java25, "Java 25 executable");
        ensureExecutable(node, "Node.js executable");
        String javaVersion = readProcessVersion(java25);
        if (!isJavaMajor(javaVersion, "25")) {
            throw new CliError("the supplied IWA build Java executable is not Java 25: " + javaVersion);
        }
        String nodeVersion = readCommandVersion(node, "--version");
        if (!nodeVersion.matches("^v?(?:2[0-9]|[3-9][0-9])\\..*")) {
            throw new CliError("Node.js 20 or newer is required: " + nodeVersion);
        }
        if (!".swbn".equalsIgnoreCase(extension(iwaOutput))) {
            throw new CliError("IWA output must use the .swbn extension");
        }
        if (standaloneOutput != null && !".html".equalsIgnoreCase(extension(standaloneOutput))) {
            throw new CliError("optional standalone output must use the .html extension");
        }
        for (Path output : publishedOutputs) ensureSafeFileOutput(output);
        if (uniqueOutputs.contains(key)) {
            throw new CliError("IWA output, receipt, and signing key must be separate files");
        }
        for (Path output : publishedOutputs) {
            Path parent = output.getParent();
            if (parent == null) throw new CliError("IWA output must have a parent directory: " + output);
            ensureNoSymlinkAncestors(parent, "IWA output parent");
            Files.createDirectories(parent);
        }

        ensureNoSymlinkAncestors(key.getParent(), "IWA signing-key parent");
        if (Files.exists(key, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(key) || !Files.isRegularFile(key, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isReadable(key)) {
                throw new CliError("IWA signing key must be a readable regular file, not a symlink: " + key);
            }
        }

        long started = System.nanoTime();
        progress("create-dev", started);
        Receipt createReceipt = createDev(create);
        Path createDevReceiptPath = workspace.resolve("receipt.json");
        ensureRegular(createDevReceiptPath, "generated source-project receipt");
        String createDevReceiptSha256 = hashFile(createDevReceiptPath, false).sha256;
        SoundsInstallResult soundsInstall = stageSoundsEpk(soundsInput,
                build.expectedSoundsEpkSha256, workspace);
        MusicInstallResult musicInstall = stageMusicEpk(musicInput,
                build.expectedMusicEpkSha256, workspace);
        progress("npm-ci", started);
        NpmInstallResult rootNpm = runPinnedNpmCi(node, npm, workspace,
                build.npmTimeoutSeconds, started, false, memoryBudget);
        Path iwaDirectory = workspace.resolve("iwa");
        NpmInstallResult iwaNpm = runNpmCiInDirectory(node, npm, iwaDirectory,
                build.npmTimeoutSeconds, started, "iwa-npm-ci");

        Path privateStage = workspace.resolve(".patcher-iwa-build-" + UUID.randomUUID());
        Files.createDirectory(privateStage);
        ACTIVE_IWA_TEMP_PATHS.add(privateStage);
        String stageId = UUID.randomUUID().toString();
        Path stagedBundle = partialSibling(iwaOutput, stageId);
        Path stagedInfo = partialSibling(infoOutput, stageId);
        Path stagedHash = partialSibling(hashOutput, stageId);
        Path stagedReceipt = partialSibling(receiptOutput, stageId);
        Path generatedInfo = iwaInfoPath(stagedBundle);
        Path generatedHash = iwaHashPath(stagedBundle);
        Path stagedHtml = standaloneOutput == null ? privateStage.resolve("standalone.html")
                : partialSibling(standaloneOutput, stageId);
        Path stagedMusicPack = musicPackOutput == null ? privateStage.resolve("music-resource-pack.zip")
                : partialSibling(musicPackOutput, stageId);
        for (Path temporary : List.of(stagedBundle, stagedInfo, stagedHash, stagedReceipt,
                generatedInfo, generatedHash, stagedHtml, stagedMusicPack)) {
            ensureSafeFileOutput(temporary);
            ACTIVE_IWA_TEMP_PATHS.add(temporary);
        }
        List<IwaOutputFile> outputsToPromote = new ArrayList<>();
        try {
            Path builder = workspace.resolve("wasm-toolchain/build-single-html.js");
            ensureRegular(builder, "standalone build script");
            progress("build-web", started);
            runTimedNodeCommand(node, List.of(builder.toString(), "--output", stagedHtml.toString(),
                    "--music-pack-output", stagedMusicPack.toString()),
                    workspace, build.buildTimeoutSeconds, started, "build-web", javaHomeForExecutable(java25));
            ensureRegular(stagedHtml, "generated standalone HTML");
            ensureRegular(stagedMusicPack, "generated music resource-pack ZIP");
            Path sourceWeb = workspace.resolve("target_teavm_wasm_gc/build/web");
            ensureRegular(sourceWeb.resolve("index.html"), "compiled hosted IWA source index.html");

            progress("pack-iwa", started);
            Path packager = iwaDirectory.resolve("build-iwa.mjs");
            ensureRegular(packager, "IWA package script");
            runTimedNodeCommand(node, List.of(packager.toString(), "--source", sourceWeb.toString(),
                    "--output", stagedBundle.toString(), "--key", key.toString()),
                    workspace, build.buildTimeoutSeconds, started, "pack-iwa");
            ensureRegular(stagedBundle, "signed IWA bundle");
            if (Files.size(stagedBundle) == 0L) throw new CliError("IWA packager produced an empty bundle");
            ensureRegular(generatedInfo, "IWA bundle information sidecar");
            ensureRegular(generatedHash, "IWA bundle SHA-256 sidecar");
            if (!Files.isRegularFile(key, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(key)
                    || !Files.isReadable(key)) {
                throw new CliError("IWA packager did not preserve a readable local signing key: " + key);
            }

            Hashes bundleHashes = hashFile(stagedBundle, false);
            String packagedHash = Files.readString(generatedHash, StandardCharsets.UTF_8).trim();
            String[] hashParts = packagedHash.split("\\s+", 2);
            if (hashParts.length != 2 || !bundleHashes.sha256.equals(hashParts[0])
                    || !stagedBundle.getFileName().toString().equals(hashParts[1])) {
                throw new CliError("IWA packager SHA-256 sidecar does not match its signed bundle");
            }
            Files.writeString(stagedHash, bundleHashes.sha256 + "  " + iwaOutput.getFileName() + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.move(generatedInfo, stagedInfo);
            Hashes infoHashes = hashFile(stagedInfo, false);
            Hashes sidecarHashes = hashFile(stagedHash, false);
            Hashes standaloneHashes = standaloneOutput == null ? null : hashFile(stagedHtml, false);
            Hashes musicPackHashes = musicPackOutput == null ? null : hashFile(stagedMusicPack, false);
            Hashes keyHashes = hashFile(key, false);
            String receipt = iwaReceipt(workspace, create, createReceipt, createDevReceiptSha256,
                    javaVersion, nodeVersion, rootNpm, iwaNpm, soundsInstall, musicInstall, iwaOutput, bundleHashes,
                    infoOutput, infoHashes, hashOutput, sidecarHashes,
                    standaloneOutput, standaloneHashes, musicPackOutput, musicPackHashes,
                    key, keyHashes, elapsedSeconds(started));
            Files.writeString(stagedReceipt, receipt, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            outputsToPromote.add(new IwaOutputFile(stagedBundle, iwaOutput, bundleHashes.sha256));
            outputsToPromote.add(new IwaOutputFile(stagedInfo, infoOutput, infoHashes.sha256));
            outputsToPromote.add(new IwaOutputFile(stagedHash, hashOutput, sidecarHashes.sha256));
            if (standaloneOutput != null) {
                outputsToPromote.add(new IwaOutputFile(stagedHtml, standaloneOutput, standaloneHashes.sha256));
                outputsToPromote.add(new IwaOutputFile(stagedMusicPack, musicPackOutput, musicPackHashes.sha256));
            }
            promoteIwaOutputs(outputsToPromote);
            try {
                Files.move(stagedReceipt, receiptOutput);
                ACTIVE_IWA_PROMOTIONS.removeAll(outputsToPromote);
            } catch (IOException publicationFailure) {
                rollbackIwaOutputs(outputsToPromote, publicationFailure);
                throw publicationFailure;
            }
            progress("complete", started);
            System.out.print(receipt);
        } finally {
            deleteTree(privateStage);
            ACTIVE_IWA_TEMP_PATHS.remove(privateStage);
            Files.deleteIfExists(stagedBundle);
            Files.deleteIfExists(stagedInfo);
            Files.deleteIfExists(stagedHash);
            Files.deleteIfExists(iwaHashPath(stagedBundle));
            Files.deleteIfExists(stagedReceipt);
            Files.deleteIfExists(generatedInfo);
            Files.deleteIfExists(generatedHash);
            ACTIVE_IWA_TEMP_PATHS.remove(stagedBundle);
            ACTIVE_IWA_TEMP_PATHS.remove(stagedInfo);
            ACTIVE_IWA_TEMP_PATHS.remove(stagedHash);
            ACTIVE_IWA_TEMP_PATHS.remove(stagedReceipt);
            ACTIVE_IWA_TEMP_PATHS.remove(generatedInfo);
            ACTIVE_IWA_TEMP_PATHS.remove(generatedHash);
            ACTIVE_IWA_TEMP_PATHS.remove(stagedHtml);
            ACTIVE_IWA_TEMP_PATHS.remove(stagedMusicPack);
            if (standaloneOutput != null) {
                Files.deleteIfExists(stagedHtml);
                Files.deleteIfExists(stagedMusicPack);
            }
        }
    }

    private static Path partialSibling(Path output, String stageId) {
        String name = output.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String suffix = dot > 0 ? name.substring(dot) : "";
        return output.resolveSibling("." + stem + ".partial-" + stageId + suffix);
    }

    private static Path iwaInfoPath(Path output) {
        return output.resolveSibling(output.getFileName() + ".info.txt");
    }

    private static Path iwaHashPath(Path output) {
        return output.resolveSibling(output.getFileName() + ".sha256");
    }

    private static Path musicResourcePackPath(Path html) {
        String filename = html.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        String stem = dot > 0 ? filename.substring(0, dot) : filename;
        return html.resolveSibling(stem + "-music-resource-pack.zip");
    }

    private static void promoteIwaOutputs(List<IwaOutputFile> outputs) throws IOException {
        List<IwaOutputFile> moved = new ArrayList<>();
        try {
            for (IwaOutputFile output : outputs) {
                Files.move(output.staged, output.target);
                moved.add(output);
                ACTIVE_IWA_PROMOTIONS.add(output);
            }
        } catch (IOException failure) {
            rollbackIwaOutputs(moved, failure);
            throw failure;
        }
    }

    private static void rollbackIwaOutputs(List<IwaOutputFile> outputs, IOException failure) {
        for (int i = outputs.size() - 1; i >= 0; i--) {
            IwaOutputFile output = outputs.get(i);
            try {
                deleteOwnedOutput(output);
            } catch (IOException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
    }

    private static void deleteOwnedOutput(IwaOutputFile output) throws IOException {
        if (Files.exists(output.target, LinkOption.NOFOLLOW_LINKS)) {
            Hashes current = hashFile(output.target, false);
            if (output.sha256.equals(current.sha256)) Files.delete(output.target);
        }
        ACTIVE_IWA_PROMOTIONS.remove(output);
    }

    private static Path iwaReceiptPath(Path output) {
        return output.resolveSibling(output.getFileName() + ".receipt.json");
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    private static void requireIwaSkeleton(Path archive) throws IOException {
        List<String> required = List.of("iwa/build-iwa.mjs", "iwa/brotli-loader-iwa.js",
                "iwa/direct-socket-loopback.js", "iwa/package.json", "iwa/package-lock.json",
                "cloudflare-client/brotli-decoder-worker.js",
                "wasm-toolchain/generate-iwa-csp-runtime.mjs");
        try (ZipFile zip = new ZipFile(absolute(archive).toFile())) {
            for (String name : required) {
                if (zip.getEntry(name) == null) {
                    throw new CliError("project skeleton cannot build an IWA: missing " + name);
                }
            }
        }
    }

    private static NpmInstallResult runNpmCiInDirectory(Path node, Path npmInput, Path directory,
            int timeoutSeconds, long progressStarted, String progressStage) throws IOException {
        Path packageJson = directory.resolve("package.json");
        Path packageLock = directory.resolve("package-lock.json");
        ensureRegular(packageJson, "IWA package.json");
        ensureRegular(packageLock, "IWA package-lock.json");
        Path npmCli = npmInput.toRealPath();
        ensureRegular(npmCli, "npm CLI");
        String npmVersion = readNodeScriptVersion(node, npmCli);
        Hashes npmHashes = hashFile(npmCli, false);
        Hashes lockBefore = hashFile(packageLock, false);
        Path log = directory.resolve("npm-ci.log");
        List<String> command = List.of(node.toString(), npmCli.toString(), "ci", "--ignore-scripts",
                "--no-audit", "--no-fund");
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        builder.environment().put("npm_config_ignore_scripts", "true");
        builder.environment().put("npm_config_audit", "false");
        builder.environment().put("npm_config_fund", "false");
        builder.environment().put("npm_config_update_notifier", "false");
        BuildMemoryBudget npmBudget = BuildMemoryBudget.detect();
        requireBuildCapacity(npmBudget);
        System.err.println(npmBudget.summary());
        npmBudget.applyTo(builder.environment());
        Process process = builder.start();
        ACTIVE_IWA_CHILDREN.add(process);
        boolean success = false;
        try {
            long npmStarted = System.nanoTime();
            long lastProgressSeconds = 0L;
            while (true) {
                try {
                    if (process.waitFor(1L, java.util.concurrent.TimeUnit.SECONDS)) break;
                } catch (InterruptedException ex) {
                    terminateProcessTree(process);
                    Thread.currentThread().interrupt();
                    throw new CliError("IWA npm ci was interrupted");
                }
                long elapsed = (System.nanoTime() - npmStarted) / 1_000_000_000L;
                if (elapsed - lastProgressSeconds >= 15L) {
                    progress(progressStage, progressStarted);
                    lastProgressSeconds = elapsed;
                }
                if (elapsed >= timeoutSeconds) {
                    terminateProcessTree(process);
                    throw new CliError("IWA npm ci timed out after " + timeoutSeconds + " seconds; log: " + log);
                }
            }
            if (process.exitValue() != 0) {
                throw new CliError("IWA npm ci failed with exit code " + process.exitValue() + "; log: " + log);
            }
            Hashes lockAfter = hashFile(packageLock, false);
            if (!lockBefore.sha256.equals(lockAfter.sha256)) {
                throw new CliError("IWA npm ci changed the authenticated package-lock.json");
            }
            success = true;
            return new NpmInstallResult(npmCli, npmHashes.sha256, npmVersion, timeoutSeconds,
                    lockAfter.sha256, hashFile(log, false).sha256);
        } finally {
            ACTIVE_IWA_CHILDREN.remove(process);
            if (!success) deleteTree(directory.resolve("node_modules"));
        }
    }

    private static void runTimedNodeCommand(Path node, List<String> arguments, Path workingDirectory,
            int timeoutSeconds, long progressStarted, String progressStage) throws IOException {
        runTimedNodeCommand(node, arguments, workingDirectory, timeoutSeconds, progressStarted,
                progressStage, null);
    }

    private static void runTimedNodeCommand(Path node, List<String> arguments, Path workingDirectory,
            int timeoutSeconds, long progressStarted, String progressStage, Path javaHome) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(node.toString());
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile()).inheritIO();
        configureNodeEnvironment(builder, javaHome);
        BuildMemoryBudget launchBudget = BuildMemoryBudget.detect();
        requireBuildCapacity(launchBudget);
        System.err.println(launchBudget.summary());
        launchBudget.applyTo(builder.environment());
        Process process = builder.start();
        ACTIVE_IWA_CHILDREN.add(process);
        long commandStarted = System.nanoTime();
        long lastProgressSeconds = 0L;
        try {
            while (true) {
                try {
                    if (process.waitFor(1L, java.util.concurrent.TimeUnit.SECONDS)) break;
                } catch (InterruptedException ex) {
                    terminateProcessTree(process);
                    Thread.currentThread().interrupt();
                    throw new CliError(progressStage + " was interrupted");
                }
                long elapsed = (System.nanoTime() - commandStarted) / 1_000_000_000L;
                if (elapsed - lastProgressSeconds >= 15L) {
                    progress(progressStage, progressStarted);
                    lastProgressSeconds = elapsed;
                }
                if (elapsed >= timeoutSeconds) {
                    terminateProcessTree(process);
                    throw new CliError(progressStage + " timed out after " + timeoutSeconds + " seconds");
                }
            }
            if (process.exitValue() != 0) {
                throw new CliError(progressStage + " failed with exit code " + process.exitValue());
            }
        } finally {
            ACTIVE_IWA_CHILDREN.remove(process);
        }
    }

    private static Path javaHomeForExecutable(Path executable) throws IOException {
        Path realExecutable = absolute(executable).toRealPath();
        Path bin = realExecutable.getParent();
        if (bin == null || bin.getFileName() == null
                || !"bin".equalsIgnoreCase(bin.getFileName().toString()) || bin.getParent() == null) {
            throw new CliError("Java executable must be inside a JDK bin directory: " + realExecutable);
        }
        return bin.getParent();
    }

    private static void configureNodeEnvironment(ProcessBuilder builder, Path javaHome) {
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        if (javaHome != null) builder.environment().put("JAVA_HOME", javaHome.toString());
    }

    private static void terminateProcessTree(Process process) {
        List<ProcessHandle> descendants = new ArrayList<>();
        try {
            process.toHandle().descendants().forEach(descendants::add);
        } catch (SecurityException ignored) {
            // The direct child is still terminated below.
        }
        descendants.sort(Comparator.comparingLong(ProcessHandle::pid).reversed());
        for (ProcessHandle child : descendants) {
            try { child.destroy(); } catch (SecurityException ignored) { }
        }
        process.destroy();
        try {
            if (!process.waitFor(1_500L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                for (ProcessHandle child : descendants) {
                    if (child.isAlive()) {
                        try { child.destroyForcibly(); } catch (SecurityException ignored) { }
                    }
                }
                process.destroyForcibly();
                process.waitFor(1_000L, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            for (ProcessHandle child : descendants) {
                if (child.isAlive()) {
                    try { child.destroyForcibly(); } catch (SecurityException ignored) { }
                }
            }
            process.destroyForcibly();
        }
    }

    private static String iwaReceipt(Path workspace, Options create, Receipt createReceipt,
            String createDevReceiptSha256, String javaVersion, String nodeVersion,
            NpmInstallResult rootNpm, NpmInstallResult iwaNpm,
            SoundsInstallResult sounds, MusicInstallResult music, Path output, Hashes bundle,
            Path infoOutput, Hashes info, Path hashOutput, Hashes sidecar,
            Path standaloneOutput, Hashes standalone, Path musicPackOutput, Hashes musicPack,
            Path key, Hashes keyHashes, double elapsedSeconds) {
        return "{\n"
                + "  \"tool\":\"eaglercraft-26.2-java-cli\",\n"
                + "  \"tool_version\":" + jsonString(TOOL_VERSION) + ",\n"
                + "  \"command\":\"build-iwa\",\n"
                + "  \"workspace\":" + jsonString(workspace.toString()) + ",\n"
                + "  \"official_jar\":" + jsonString(absolute(create.jar).toString()) + ",\n"
                + "  \"official_jar_sha256\":" + jsonString(createReceipt.jarHashes.sha256) + ",\n"
                + "  \"source_patch_bundle_sha256\":" + jsonString(createReceipt.expectedBundleSha256) + ",\n"
                + "  \"project_skeleton_sha256\":" + jsonString(createReceipt.expectedSkeletonSha256) + ",\n"
                + "  \"resource_overlay_sha256\":" + jsonString(createReceipt.expectedResourceOverlaySha256) + ",\n"
                + "  \"patched_source_manifest_sha256\":" + jsonString(createReceipt.patchResult.finalManifestSha256()) + ",\n"
                + "  \"resource_tree_sha256\":" + jsonString(createReceipt.resourceResult.finalTreeSha256()) + ",\n"
                + "  \"create_dev_receipt_sha256\":" + jsonString(createDevReceiptSha256) + ",\n"
                + "  \"java25_version\":" + jsonString(javaVersion) + ",\n"
                + "  \"node_version\":" + jsonString(nodeVersion) + ",\n"
                + "  \"root_package_lock_sha256\":" + jsonString(rootNpm.packageLockSha256) + ",\n"
                + "  \"root_npm_log_sha256\":" + jsonString(rootNpm.logSha256) + ",\n"
                + "  \"iwa_package_lock_sha256\":" + jsonString(iwaNpm.packageLockSha256) + ",\n"
                + "  \"iwa_npm_log_sha256\":" + jsonString(iwaNpm.logSha256) + ",\n"
                + "  \"sounds_epk_sha256\":" + jsonString(sounds.sha256) + ",\n"
                + "  \"music_epk_sha256\":" + jsonString(music.sha256) + ",\n"
                + "  \"iwa_output\":" + jsonString(output.toString()) + ",\n"
                + "  \"iwa_size\":" + bundle.size + ",\n"
                + "  \"iwa_sha256\":" + jsonString(bundle.sha256) + ",\n"
                + "  \"iwa_info_output\":" + jsonString(infoOutput.toString()) + ",\n"
                + "  \"iwa_info_sha256\":" + jsonString(info.sha256) + ",\n"
                + "  \"iwa_sha256_file_output\":" + jsonString(hashOutput.toString()) + ",\n"
                + "  \"iwa_sha256_file_sha256\":" + jsonString(sidecar.sha256) + ",\n"
                + (standaloneOutput == null ? "" : "  \"standalone_output\":"
                        + jsonString(standaloneOutput.toString()) + ",\n"
                        + "  \"standalone_size\":" + standalone.size + ",\n"
                        + "  \"standalone_sha256\":" + jsonString(standalone.sha256) + ",\n")
                + (musicPackOutput == null ? "" : "  \"music_pack_output\":"
                        + jsonString(musicPackOutput.toString()) + ",\n"
                        + "  \"music_pack_size\":" + musicPack.size + ",\n"
                        + "  \"music_pack_sha256\":" + jsonString(musicPack.sha256) + ",\n")
                + "  \"signing_key\":" + jsonString(key.toString()) + ",\n"
                + "  \"signing_key_sha256\":" + jsonString(keyHashes.sha256) + ",\n"
                + "  \"elapsed_seconds\":" + elapsedSeconds + ",\n"
                + "  \"status\":\"signed-iwa-built\"\n"
                + "}\n";
    }

    private record IwaOutputFile(Path staged, Path target, String sha256) {
    }

    private static String sha256String(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String musicReceiptFields(Path output, Hashes artifact) {
        if (output == null || artifact == null) {
            return "";
        }
        return "  \"music_pack_output\":" + jsonString(output.toString()) + ",\n"
                + "  \"music_pack_size\":" + artifact.size + ",\n"
                + "  \"music_pack_sha256\":\"" + artifact.sha256 + "\",\n";
    }

    private static String wispcraftReceiptFields(ScriptInput script) {
        if (script == null) {
            return "";
        }
        return "  \"wispcraft_script_input\":" + jsonString(script.path.toString()) + ",\n"
                + "  \"wispcraft_script_sha256\":\"" + script.sha256 + "\",\n"
                + "  \"wispcraft_script_injection\":\"native WebSocket capture, then inline script at start of head\",\n";
    }

    private static ScriptInput readWispcraftScript(Path path) throws IOException {
        ensureRegular(path, "Wispcraft script");
        byte[] contents;
        try (InputStream input = Files.newInputStream(path)) {
            contents = readLimited(input, MAX_WISPCRAFT_SCRIPT_BYTES, "Wispcraft script");
        }
        if (contents.length == 0) {
            throw new CliError("Wispcraft script is empty: " + path);
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(contents));
        } catch (CharacterCodingException ex) {
            throw new CliError("Wispcraft script is not valid UTF-8: " + path);
        }
        return new ScriptInput(path, digestBytes(contents, "SHA-256"), contents);
    }

    static void injectWispcraftScriptIntoHtml(Path generatedHtml, byte[] scriptContents) throws IOException {
        if (scriptContents.length == 0 || scriptContents.length > MAX_WISPCRAFT_SCRIPT_BYTES) {
            throw new CliError("Wispcraft script must be between 1 byte and "
                    + MAX_WISPCRAFT_SCRIPT_BYTES + " bytes");
        }
        Path html = absolute(generatedHtml);
        ensureRegular(html, "generated standalone HTML");
        Path parent = html.getParent();
        if (parent == null) {
            throw new CliError("generated standalone HTML must have a parent directory");
        }
        Path staged = parent.resolve("." + html.getFileName() + ".wispcraft-" + UUID.randomUUID());
        boolean promoted = false;
        try {
            try (InputStream input = new BufferedInputStream(Files.newInputStream(html));
                    OutputStream output = new BufferedOutputStream(Files.newOutputStream(staged,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                ByteArrayOutputStream head = new ByteArrayOutputStream();
                HtmlHeadScanner scanner = new HtmlHeadScanner();
                boolean found = false;
                int value;
                while ((value = input.read()) >= 0) {
                    head.write(value);
                    if (head.size() > MAX_HTML_HEAD_SCAN_BYTES) {
                        throw new CliError("standalone HTML has no opening <head> within the scan limit");
                    }
                    if (scanner.accept(value)) {
                        output.write(head.toByteArray());
                        writeInlineWispcraftScript(output, scriptContents);
                        input.transferTo(output);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    throw new CliError("standalone HTML has no opening <head> tag");
                }
            }
            ensureRegular(staged, "Wispcraft-injected standalone HTML");
            Files.move(staged, html, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            promoted = true;
        } finally {
            if (!promoted) {
                Files.deleteIfExists(staged);
            }
        }
    }

    private static void writeInlineWispcraftScript(OutputStream output, byte[] source) throws IOException {
        output.write(("<meta charset=\"UTF-8\">\n"
                + "<script>if(typeof globalThis!==\"undefined\"&&typeof globalThis.WebSocket===\"function\""
                + "&&!Object.prototype.hasOwnProperty.call(globalThis,\"__eaglerNativeWebSocket\"))"
                + "Object.defineProperty(globalThis,\"__eaglerNativeWebSocket\",{value:globalThis.WebSocket,"
                + "writable:false,configurable:false});</script>\n<script>\n")
                .getBytes(StandardCharsets.US_ASCII));
        int copiedThrough = 0;
        for (int index = 0; index < source.length; index++) {
            if (isScriptEndTagAt(source, index)) {
                output.write(source, copiedThrough, index - copiedThrough);
                output.write('<');
                output.write('\\');
                for (int offset = 1; offset < 8; offset++) {
                    output.write(source[index + offset]);
                }
                index += 7;
                copiedThrough = index + 1;
            }
        }
        output.write(source, copiedThrough, source.length - copiedThrough);
        output.write("\n</script>\n".getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean isScriptEndTagAt(byte[] source, int index) {
        if (source.length - index < SCRIPT_END_TAG.length) {
            return false;
        }
        for (int offset = 0; offset < SCRIPT_END_TAG.length; offset++) {
            int value = source[index + offset] & 0xff;
            if (value >= 'A' && value <= 'Z') {
                value += 'a' - 'A';
            }
            if (value != SCRIPT_END_TAG[offset]) {
                return false;
            }
        }
        return true;
    }

    private static final class HtmlHeadScanner {
        private static final int SEARCH = 0;
        private static final int AFTER_LT = 1;
        private static final int DECLARATION_BANG = 2;
        private static final int DECLARATION_DASH = 3;
        private static final int COMMENT = 4;
        private static final int HEAD_NAME = 5;
        private static final int HEAD_BOUNDARY = 6;
        private static final int HEAD_ATTRIBUTES = 7;
        private static final int OTHER_TAG = 8;
        private static final byte[] HEAD_NAME_BYTES = {'h', 'e', 'a', 'd'};

        private int state = SEARCH;
        private int headNameIndex;
        private int quote;
        private int commentDashes;

        boolean accept(int input) {
            int value = asciiLower(input);
            switch (state) {
                case SEARCH -> {
                    if (value == '<') state = AFTER_LT;
                }
                case AFTER_LT -> {
                    if (value == 'h') {
                        state = HEAD_NAME;
                        headNameIndex = 1;
                    } else if (value == '!') {
                        state = DECLARATION_BANG;
                    } else if (value == '<') {
                        state = AFTER_LT;
                    } else {
                        state = OTHER_TAG;
                        updateOtherTag(value);
                    }
                }
                case DECLARATION_BANG -> {
                    if (value == '-') {
                        state = DECLARATION_DASH;
                    } else {
                        state = OTHER_TAG;
                        updateOtherTag(value);
                    }
                }
                case DECLARATION_DASH -> {
                    if (value == '-') {
                        state = COMMENT;
                        commentDashes = 0;
                    } else {
                        state = OTHER_TAG;
                        updateOtherTag(value);
                    }
                }
                case COMMENT -> {
                    if (input == '-') {
                        commentDashes = Math.min(2, commentDashes + 1);
                    } else if (input == '>' && commentDashes >= 2) {
                        state = SEARCH;
                        commentDashes = 0;
                    } else {
                        commentDashes = 0;
                    }
                }
                case HEAD_NAME -> {
                    if (headNameIndex < HEAD_NAME_BYTES.length && value == HEAD_NAME_BYTES[headNameIndex]) {
                        headNameIndex++;
                        if (headNameIndex == HEAD_NAME_BYTES.length) state = HEAD_BOUNDARY;
                    } else {
                        state = OTHER_TAG;
                        updateOtherTag(value);
                    }
                }
                case HEAD_BOUNDARY -> {
                    if (value == '>') return true;
                    if (isHtmlWhitespace(value) || value == '/') {
                        state = HEAD_ATTRIBUTES;
                    } else {
                        state = OTHER_TAG;
                        updateOtherTag(value);
                    }
                }
                case HEAD_ATTRIBUTES -> {
                    if (quote != 0) {
                        if (input == quote) quote = 0;
                    } else if (input == '"' || input == '\'') {
                        quote = input;
                    } else if (input == '>') {
                        return true;
                    }
                }
                case OTHER_TAG -> updateOtherTag(input);
                default -> throw new AssertionError("unknown HTML scan state: " + state);
            }
            return false;
        }

        private void updateOtherTag(int input) {
            if (quote != 0) {
                if (input == quote) quote = 0;
            } else if (input == '"' || input == '\'') {
                quote = input;
            } else if (input == '>') {
                state = SEARCH;
            }
        }

        private static int asciiLower(int value) {
            return value >= 'A' && value <= 'Z' ? value + ('a' - 'A') : value;
        }

        private static boolean isHtmlWhitespace(int value) {
            return value == ' ' || value == '\t' || value == '\n' || value == '\r' || value == '\f';
        }
    }

    private static String standaloneReceiptHeader(Path workspace, String javaVersion, String nodeVersion,
            NpmInstallResult npm, SoundsInstallResult sounds, MusicInstallResult music) {
        return "{\n"
                + "  \"tool\":\"eaglercraft-26.2-java-cli\",\n"
                + "  \"tool_version\":\"" + TOOL_VERSION + "\",\n"
                + "  \"command\":\"build-standalone\",\n"
                + "  \"workspace\":" + jsonString(workspace.toString()) + ",\n"
                + "  \"java25_version\":" + jsonString(javaVersion) + ",\n"
                + "  \"node_version\":" + jsonString(nodeVersion) + ",\n"
                + "  \"npm_cli\":" + jsonString(npm.npmCli.toString()) + ",\n"
                + "  \"npm_cli_sha256\":\"" + npm.npmCliSha256 + "\",\n"
                + "  \"npm_version\":" + jsonString(npm.npmVersion) + ",\n"
                + "  \"npm_command\":\"npm ci --ignore-scripts --no-audit --no-fund (invoked by the selected Node executable)\",\n"
                + "  \"npm_timeout_seconds\":" + npm.timeoutSeconds + ",\n"
                + "  \"package_lock_sha256\":\"" + npm.packageLockSha256 + "\",\n"
                + "  \"npm_log_sha256\":\"" + npm.logSha256 + "\",\n"
                + "  \"sounds_epk_input\":" + jsonString(sounds.input.toString()) + ",\n"
                + "  \"sounds_epk_staged\":" + jsonString(sounds.destination.toString()) + ",\n"
                + "  \"sounds_epk_size\":" + sounds.size + ",\n"
                + "  \"sounds_epk_sha256\":\"" + sounds.sha256 + "\",\n"
                + "  \"music_epk_input\":" + jsonString(music.input.toString()) + ",\n"
                + "  \"music_epk_staged\":" + jsonString(music.destination.toString()) + ",\n"
                + "  \"music_epk_size\":" + music.size + ",\n"
                + "  \"music_epk_sha256\":\"" + music.sha256 + "\",\n";
    }

    private static void rollbackMusicPackOutput(Path published, Path staged, Hashes expected,
            Object expectedFileKey) throws IOException {
        if (published == null || staged == null || expected == null
                || !Files.exists(published, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("cannot locate the published music ZIP for rollback");
        }
        BasicFileAttributes attributes = Files.readAttributes(published, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || Files.isSymbolicLink(published)
                || (expectedFileKey != null && !expectedFileKey.equals(attributes.fileKey()))) {
            throw new IOException("published music ZIP changed before rollback; leaving it untouched: " + published);
        }
        Hashes current = hashFile(published, false);
        if (current.size != expected.size || !current.sha256.equals(expected.sha256)) {
            throw new IOException("published music ZIP no longer matches staged bytes; leaving it untouched: "
                    + published);
        }
        ensureSafeFileOutput(staged);
        Files.move(published, staged, StandardCopyOption.ATOMIC_MOVE);
    }

    static void verifySoundsInput(Path input, String expectedSha256) throws IOException {
        verifyEpkInput(input, expectedSha256, "sounds EPK input", "--expected-sounds-epk-sha256");
    }

    static void verifyMusicInput(Path input, String expectedSha256) throws IOException {
        verifyEpkInput(input, expectedSha256, "music EPK input", "--expected-music-epk-sha256");
    }

    private static void verifyEpkInput(Path input, String expectedSha256, String label, String hashOption)
            throws IOException {
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")) {
            throw new CliError(hashOption + " must be 64 lowercase hexadecimal characters");
        }
        ensureRegular(input, label);
        Hashes hashes = hashFile(input, false);
        if (!expectedSha256.equals(hashes.sha256)) {
            throw new CliError(label + " SHA-256 mismatch: expected " + expectedSha256
                    + ", got " + hashes.sha256);
        }
        if (hashes.size < 16L || !hasFileBoundary(input, "EAGPKG$$".getBytes(StandardCharsets.US_ASCII),
                ":::YEE:>".getBytes(StandardCharsets.US_ASCII))) {
            throw new CliError(label + " has invalid EPK framing");
        }
    }

    static SoundsInstallResult stageSoundsEpk(Path input, String expectedSha256,
            Path workspace) throws IOException {
        return stageSoundsEpk(input, expectedSha256, workspace, false);
    }

    static SoundsInstallResult stageSoundsEpk(Path input, String expectedSha256,
            Path workspace, boolean reuseProject) throws IOException {
        Path source = absolute(input);
        verifySoundsInput(source, expectedSha256);
        EpkInstallResult result = stageEpk(source, expectedSha256, workspace,
                "sounds.epk", "sounds EPK", reuseProject);
        return new SoundsInstallResult(result.input, result.destination, result.size, result.sha256);
    }

    static MusicInstallResult stageMusicEpk(Path input, String expectedSha256,
            Path workspace) throws IOException {
        return stageMusicEpk(input, expectedSha256, workspace, false);
    }

    static MusicInstallResult stageMusicEpk(Path input, String expectedSha256,
            Path workspace, boolean reuseProject) throws IOException {
        Path source = absolute(input);
        verifyMusicInput(source, expectedSha256);
        EpkInstallResult result = stageEpk(source, expectedSha256, workspace,
                "music.epk", "music EPK", reuseProject);
        return new MusicInstallResult(result.input, result.destination, result.size, result.sha256);
    }

    private static EpkInstallResult stageEpk(Path source, String expectedSha256, Path workspace,
            String fileName, String label, boolean reuseProject) throws IOException {
        Path destination = workspace.resolve("target_teavm/build/web").resolve(fileName);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(destination)) {
            if (reuseProject && !Files.isSymbolicLink(destination)
                    && Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
                Hashes existing = hashFile(destination, false);
                if (!expectedSha256.equals(existing.sha256)) {
                    throw new CliError(label + " already exists in the reused project with a different SHA-256: "
                            + destination);
                }
                return new EpkInstallResult(source, destination, existing.size, existing.sha256);
            }
            throw new CliError(label + " destination must be absent: " + destination);
        }
        Path parent = destination.getParent();
        ensureNoSymlinkAncestors(workspace, "standalone workspace");
        Files.createDirectories(parent);
        ensureNoSymlinkAncestors(parent, label + " destination parent");
        Path partial = parent.resolve("." + fileName + ".partial-" + UUID.randomUUID());
        boolean promoted = false;
        try {
            Files.copy(source, partial);
            Hashes copied = hashFile(partial, false);
            if (!expectedSha256.equals(copied.sha256)) {
                throw new CliError(label + " changed while staging: expected " + expectedSha256
                        + ", copied " + copied.sha256);
            }
            Files.move(partial, destination, StandardCopyOption.ATOMIC_MOVE);
            promoted = true;
            return new EpkInstallResult(source, destination, copied.size, copied.sha256);
        } finally {
            if (!promoted) Files.deleteIfExists(partial);
        }
    }

    private static boolean hasFileBoundary(Path path, byte[] prefix, byte[] suffix) throws IOException {
        long size = Files.size(path);
        if (size < prefix.length + suffix.length) return false;
        byte[] first = new byte[prefix.length];
        byte[] last = new byte[suffix.length];
        try (var channel = java.nio.channels.FileChannel.open(path)) {
            java.nio.ByteBuffer start = java.nio.ByteBuffer.wrap(first);
            while (start.hasRemaining() && channel.read(start) >= 0) { }
            channel.position(size - suffix.length);
            java.nio.ByteBuffer end = java.nio.ByteBuffer.wrap(last);
            while (end.hasRemaining() && channel.read(end) >= 0) { }
        }
        return java.util.Arrays.equals(first, prefix) && java.util.Arrays.equals(last, suffix);
    }

    private static NpmInstallResult runPinnedNpmCi(Path node, Path npmInput, Path workspace,
            int timeoutSeconds, long progressStarted, boolean preserveWorkspace,
            BuildMemoryBudget memoryBudget) throws IOException {
        requireBuildCapacity(memoryBudget);
        Path packageJson = workspace.resolve("package.json");
        Path packageLock = workspace.resolve("package-lock.json");
        ensureRegular(packageJson, "package.json");
        ensureRegular(packageLock, "package-lock.json");
        Path installRoot = workspace;
        Path temporaryRoot = null;
        if (preserveWorkspace) {
            temporaryRoot = Files.createTempDirectory("eagler-patcher-npm-");
            ACTIVE_IWA_TEMP_PATHS.add(temporaryRoot);
            Files.copy(packageJson, temporaryRoot.resolve("package.json"));
            Files.copy(packageLock, temporaryRoot.resolve("package-lock.json"));
            installRoot = temporaryRoot;
        }
        Path installPackageLock = installRoot.resolve("package-lock.json");
        Path npmCli = npmInput.toRealPath();
        ensureRegular(npmCli, "npm CLI");
        String npmVersion = readNodeScriptVersion(node, npmCli);
        if (!npmVersion.matches("^(?:[1-9][0-9]*)\\..*")) {
            throw new CliError("npm version output is invalid: " + npmVersion);
        }
        Hashes npmHashes = hashFile(npmCli, false);
        Hashes lockBefore = hashFile(installPackageLock, false);
        Path log = installRoot.resolve("npm-ci.log");
        List<String> command = List.of(node.toString(), npmCli.toString(), "ci", "--ignore-scripts",
                "--no-audit", "--no-fund");
        ProcessBuilder builder = new ProcessBuilder(command).directory(installRoot.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        builder.environment().put("npm_config_ignore_scripts", "true");
        builder.environment().put("npm_config_audit", "false");
        builder.environment().put("npm_config_fund", "false");
        builder.environment().put("npm_config_update_notifier", "false");
        BuildMemoryBudget npmBudget = BuildMemoryBudget.detect();
        requireBuildCapacity(npmBudget);
        System.err.println(npmBudget.summary());
        npmBudget.applyTo(builder.environment());
        Process process = builder.start();
        boolean success = false;
        try {
            long npmStarted = System.nanoTime();
            long lastProgressSeconds = 0L;
            while (true) {
                try {
                    if (process.waitFor(1L, java.util.concurrent.TimeUnit.SECONDS)) break;
                } catch (InterruptedException ex) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                    throw new CliError("npm ci was interrupted");
                }
                long elapsed = (System.nanoTime() - npmStarted) / 1_000_000_000L;
                if (elapsed - lastProgressSeconds >= 15L) {
                    progress("npm-ci", progressStarted);
                    lastProgressSeconds = elapsed;
                }
                if (elapsed >= timeoutSeconds) {
                    process.destroyForcibly();
                    try {
                        process.waitFor(5L, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    throw new CliError("npm ci timed out after " + timeoutSeconds + " seconds; log: " + log);
                }
            }
            if (process.exitValue() != 0) {
                throw new CliError("npm ci failed with exit code " + process.exitValue() + "; log: " + log);
            }
            Hashes lockAfter = hashFile(installPackageLock, false);
            if (!lockBefore.sha256.equals(lockAfter.sha256)) {
                throw new CliError("npm ci changed the authenticated package-lock.json");
            }
            ensureRegular(installRoot.resolve("node_modules/brotli-dec-wasm/pkg/brotli_dec_wasm.js"),
                    "installed brotli decoder JavaScript");
            ensureRegular(installRoot.resolve("node_modules/brotli-dec-wasm/pkg/brotli_dec_wasm_bg.wasm"),
                    "installed brotli decoder Wasm");
            success = true;
            return new NpmInstallResult(npmCli, npmHashes.sha256, npmVersion, timeoutSeconds,
                    lockAfter.sha256, hashFile(log, false).sha256,
                    installRoot.resolve("node_modules"), temporaryRoot);
        } finally {
            if (!success && temporaryRoot != null) {
                deleteTree(temporaryRoot);
                ACTIVE_IWA_TEMP_PATHS.remove(temporaryRoot);
            } else if (!success) {
                deleteTree(workspace.resolve("node_modules"));
            }
        }
    }

    private static String readNodeScriptVersion(Path node, Path script) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(node.toString(), script.toString(), "--version")
                .redirectErrorStream(true);
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        Process process = builder.start();
        try {
            if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new CliError("npm version check timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0 || output.isEmpty()) throw new CliError("npm version check failed");
            return firstLine(output);
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new CliError("npm version check was interrupted");
        }
    }

    private static void verifyCreateDevInputs(Options create) throws IOException {
        Path jar = absolute(create.jar);
        Path vineflower = absolute(create.vineflower);
        Path java17 = absolute(create.java17);
        ensureRegular(jar, "official client JAR");
        Hashes jarHashes = hashFile(jar, true);
        if (jarHashes.size != EXPECTED_CLIENT_SIZE
                || !EXPECTED_CLIENT_SHA1.equals(jarHashes.sha1)
                || !EXPECTED_CLIENT_SHA256.equals(jarHashes.sha256)) {
            throw new CliError("reuse project official client JAR does not match the pinned 26.2 input");
        }
        validateClientZip(jar);
        ensureRegular(vineflower, "Vineflower JAR");
        if (!EXPECTED_VINEFLOWER_SHA256.equals(hashFile(vineflower, false).sha256)) {
            throw new CliError("reuse project Vineflower JAR does not match the pinned 1.12.0 input");
        }
        ensureExecutable(java17, "Java 17 executable");
        String javaVersion = readProcessVersion(java17);
        if (!isJava17(javaVersion)) {
            throw new CliError("the supplied Java executable must report a recognized Java 17 version: "
                    + firstLine(javaVersion));
        }
    }

    private static void requireStandaloneProject(Path workspace) throws IOException {
        ensureNoSymlinkAncestors(workspace, "reused project");
        for (String relative : standaloneSkeletonRequiredFiles()) {
            Path file = workspace.resolve(relative);
            ensureNoSymlinkAncestors(file, "reused project member");
            ensureRegular(file, "reused standalone project member " + relative);
        }
    }

    private static void requireBuildCapacity(BuildMemoryBudget budget) {
        try {
            budget.requireBuildCapacity();
        } catch (IllegalArgumentException ex) {
            throw new CliError(ex.getMessage());
        }
    }

    private static void requireStandaloneSkeleton(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(absolute(archive).toFile())) {
            for (String name : standaloneSkeletonRequiredFiles()) {
                if (zip.getEntry(name) == null) {
                    throw new CliError("project skeleton is compile-only and cannot build a standalone: missing " + name);
                }
            }
        }
    }

    static List<String> standaloneSkeletonRequiredFiles() {
        return List.of(
                "package.json", "package-lock.json",
                "wasm-toolchain/build-single-html.js", "wasm-toolchain/content-verified-brotli.js",
                "wasm-toolchain/run-memory-capped.js", "wasm-toolchain/link-web-target-standalone.js",
                "wasm-toolchain/link-server-standalone.js", "wasm-toolchain/deploy_wasm_web.sh",
                "wasm-toolchain/precompress-web.sh", "wasm-toolchain/precompress-web.js",
                "wasm-toolchain/StandaloneTeaVMLinker.java", "wasm-toolchain/standalone-classpaths.js",
                "wasm-toolchain/standalone-link-cache.js", "wasm-toolchain/field-overlay-link.js",
                "wasm-toolchain/build-server-fastutil-slice.js",
                "wasm-toolchain/export-music-resource-pack.js");
    }

    private static void ensureSafeFileOutput(Path output) throws IOException {
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(output)) {
            throw new CliError("standalone output must be absent: " + output);
        }
    }

    private static int runVineflower(Path java17, Path vineflower, Path jar, Path source,
            Path log, int timeoutSeconds, Path workingDirectory, int decompilerHeapMiB) throws IOException {
        List<String> command = List.of(
                java17.toString(), "-Xms256m", "-Xmx" + decompilerHeapMiB + "m",
                "-Dfile.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US",
                "-Duser.timezone=UTC", "-jar", vineflower.toString(),
                "--log-level=warn", "-dgs=1", "-rsy=1", jar.toString(), source.toString());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("LC_ALL", "C");
        builder.environment().put("LANG", "C");
        builder.environment().put("TZ", "UTC");
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        builder.redirectOutput(log.toFile());
        Process process = builder.start();
        try {
            if (!process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                }
                throw new CliError("Vineflower timed out after " + timeoutSeconds + " seconds");
            }
            return process.exitValue();
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new CliError("Vineflower run was interrupted");
        }
    }

    private static void validateClientZip(Path jar) throws IOException {
        Set<String> names = new HashSet<>();
        long totalUncompressed = 0L;
        boolean sawVersion = false;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            if (zip.size() > MAX_ARCHIVE_ENTRIES) {
                throw new CliError("client JAR has too many ZIP entries: " + zip.size());
            }
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                validateZipName(name);
                if (!names.add(name)) {
                    throw new CliError("duplicate ZIP entry: " + name);
                }
                if (entry.getSize() > MAX_ENTRY_UNCOMPRESSED) {
                    throw new CliError("ZIP entry is too large: " + name);
                }
                if (entry.getSize() >= 0) {
                    totalUncompressed = Math.addExact(totalUncompressed, entry.getSize());
                    if (totalUncompressed > MAX_ARCHIVE_UNCOMPRESSED) {
                        throw new CliError("client JAR expands beyond safety limit");
                    }
                }
                if ("version.json".equals(name)) {
                    sawVersion = true;
                    byte[] data = readLimited(zip.getInputStream(entry), MAX_VERSION_BYTES);
                    validateVersionJson(new String(data, StandardCharsets.UTF_8));
                }
            }
        } catch (ArithmeticException ex) {
            throw new CliError("ZIP size overflow");
        }
        if (!sawVersion) {
            throw new CliError("client JAR has no version.json");
        }
    }

    private static void validateZipName(String name) {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0 || name.indexOf('\\') >= 0
                || name.startsWith("/") || name.startsWith("//") || name.matches("^[A-Za-z]:.*")) {
            throw new CliError("unsafe ZIP entry name: " + name);
        }
        String[] components = name.split("/", -1);
        for (int index = 0; index < components.length; index++) {
            String component = components[index];
            boolean trailingDirectoryMarker = index == components.length - 1
                    && component.isEmpty() && name.endsWith("/");
            if ((!trailingDirectoryMarker && component.isEmpty()) || ".".equals(component)
                    || "..".equals(component)) {
                throw new CliError("unsafe ZIP entry path: " + name);
            }
        }
    }

    private static void validateVersionJson(String json) {
        if (!VERSION_ID.matcher(json).find() || !WORLD_VERSION.matcher(json).find()
                || !PROTOCOL_VERSION.matcher(json).find() || !JAVA_COMPONENT_VERSION.matcher(json).find()) {
            throw new CliError("version.json is not the pinned 26.2 client metadata");
        }
    }

    static List<FileRecord> scanJavaTree(Path root) throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new CliError("decompiler did not create a real source directory");
        }
        List<FileRecord> records = new ArrayList<>();
        Files.walkFileTree(root, Set.of(), Integer.MAX_VALUE,
                new java.nio.file.SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                        if (Files.isSymbolicLink(path)) {
                            throw new CliError("symlink in generated source tree: " + path);
                        }
                        if (path.getFileName().toString().endsWith(".java")) {
                            String rel = root.relativize(path).toString().replace('\\', '/');
                            records.add(new FileRecord(rel, digest(path, "SHA-256"), attrs.size()));
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        records.sort(Comparator.comparing(record -> record.path));
        for (int i = 1; i < records.size(); i++) {
            if (records.get(i - 1).path.equals(records.get(i).path)) {
                throw new CliError("duplicate generated source path: " + records.get(i).path);
            }
        }
        return records;
    }

    static String manifestDigest(List<FileRecord> records) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            FileRecord record = records.get(i);
            builder.append('[').append(jsonString(record.path)).append(',')
                    .append(jsonString(record.sha256)).append(',').append(record.size).append(']');
        }
        builder.append("]\n");
        return digestBytes(builder.toString().getBytes(StandardCharsets.UTF_8), "SHA-256");
    }

    static String assertBaselineSourceIdentity(List<FileRecord> records) {
        String manifestSha256 = manifestDigest(records);
        if (records.size() != EXPECTED_JAVA_FILES || !EXPECTED_MANIFEST_SHA256.equals(manifestSha256)) {
            throw new CliError("baseline manifest mismatch: files=" + records.size()
                    + " sha256=" + manifestSha256 + " (expected " + EXPECTED_JAVA_FILES
                    + ", " + EXPECTED_MANIFEST_SHA256 + ")");
        }
        return manifestSha256;
    }

    private static void writeManifest(Path target, List<FileRecord> records, String digest) throws IOException {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"format\":\"eaglercraft-26.2-java-source-manifest-v1\",\n")
                .append("  \"file_count\":").append(records.size()).append(",\n")
                .append("  \"manifest_sha256\":").append(jsonString(digest)).append(",\n  \"records\":[\n");
        for (int i = 0; i < records.size(); i++) {
            FileRecord record = records.get(i);
            json.append("    {")
                    .append("\"path\":").append(jsonString(record.path)).append(',')
                    .append("\"sha256\":").append(jsonString(record.sha256)).append(',')
                    .append("\"size\":").append(record.size).append('}');
            if (i + 1 < records.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        json.append("  ]\n}\n");
        Files.writeString(target, json.toString(), StandardCharsets.UTF_8);
    }

    private static void promote(Path staging, Path output) throws IOException {
        boolean outputExists = Files.exists(output, LinkOption.NOFOLLOW_LINKS);
        if (outputExists) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(output)) {
                if (children.iterator().hasNext()) {
                    throw new CliError("output became non-empty while the build was running: " + output);
                }
            }
            Files.delete(output);
        }
        Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void ensureSafeOutput(Path output) throws IOException {
        if (Files.isSymbolicLink(output)) {
            throw new CliError("output cannot be a symlink: " + output);
        }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) {
                throw new CliError("output must be absent or an empty directory: " + output);
            }
            try (DirectoryStream<Path> children = Files.newDirectoryStream(output)) {
                if (children.iterator().hasNext()) {
                    throw new CliError("output must be absent or an empty directory: " + output);
                }
            }
        }
    }

    private static void ensureNoSymlinkAncestors(Path path, String label) {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        if (current == null) {
            current = Path.of("");
        }
        for (Path part : absolute) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new CliError(label + " contains a symlink: " + current);
            }
        }
    }

    private static void ensureRegular(Path path, String label) {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new CliError(label + " is not a regular file: " + path);
        }
    }

    private static void ensureExecutable(Path path, String label) {
        ensureRegular(path, label);
        if (!Files.isExecutable(path)) {
            throw new CliError(label + " is not executable: " + path);
        }
    }

    private static byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        return readLimited(input, maxBytes, "version.json");
    }

    private static byte[] readLimited(InputStream input, int maxBytes, String label) throws IOException {
        try (InputStream stream = new BufferedInputStream(input)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > maxBytes) {
                    throw new CliError(label + " exceeds safety limit of " + maxBytes + " bytes");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String readProcessVersion(Path executable) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(executable.toString(), "-version").redirectErrorStream(true);
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        Process process = builder.start();
        try {
            if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new CliError("Java version check timed out");
            }
            String version = selectVersionLine(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            if (process.exitValue() != 0) {
                throw new CliError("Java version check failed");
            }
            return version;
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new CliError("Java version check was interrupted");
        }
    }

    static boolean isJava17(String version) {
        return isJavaMajor(version, "17");
    }

    private static boolean isJavaMajor(String version, String expected) {
        Matcher matcher = JAVA_VERSION.matcher(version);
        return matcher.find() && expected.equals(matcher.group(1));
    }

    private static Hashes observeJavaRuntimeModules(Path executable) throws IOException {
        Path bin = executable.getParent();
        Path javaHome = bin == null ? null : bin.getParent();
        if (javaHome == null) {
            return null;
        }
        Path modules = javaHome.resolve("lib/modules");
        if (Files.isSymbolicLink(modules) || !Files.isRegularFile(modules, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        return hashFile(modules, false);
    }

    private static String readCommandVersion(Path executable, String argument) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(executable.toString(), argument).redirectErrorStream(true);
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        Process process = builder.start();
        try {
            if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new CliError("tool version check timed out: " + executable);
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0 || output.isEmpty()) {
                throw new CliError("tool version check failed: " + executable);
            }
            return firstLine(output);
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new CliError("tool version check was interrupted: " + executable);
        }
    }

    private static String selectVersionLine(String output) {
        for (String line : output.replace("\r\n", "\n").split("\n")) {
            String candidate = line.trim();
            if (!candidate.isEmpty() && JAVA_VERSION.matcher(candidate).find()) {
                return candidate;
            }
        }
        throw new CliError("Java version output did not contain a version line");
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }

    private static Hashes hashFile(Path path, boolean withSha1) throws IOException {
        MessageDigest sha256 = digest("SHA-256");
        MessageDigest sha1 = withSha1 ? digest("SHA-1") : null;
        long size = 0L;
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                size += read;
                sha256.update(buffer, 0, read);
                if (sha1 != null) {
                    sha1.update(buffer, 0, read);
                }
            }
        }
        return new Hashes(size, hex(sha1 == null ? null : sha1.digest()), hex(sha256.digest()));
    }

    private static String digest(Path path, String algorithm) {
        try {
            MessageDigest messageDigest = digest(algorithm);
            try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        messageDigest.update(buffer, 0, read);
                    }
                }
            }
            return hex(messageDigest.digest());
        } catch (IOException ex) {
            throw new CliError("cannot hash " + path + ": " + ex.getMessage());
        }
    }

    private static String digestBytes(byte[] bytes, String algorithm) {
        MessageDigest messageDigest = digest(algorithm);
        messageDigest.update(bytes);
        return hex(messageDigest.digest());
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
    }

    private static String hex(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            output.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return output.toString();
    }

    private static String jsonString(String value) {
        StringBuilder output = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        output.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        output.append(ch);
                    }
                }
            }
        }
        return output.append('"').toString();
    }

    private static Path absolute(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static double elapsedSeconds(long started) {
        return Math.round((System.nanoTime() - started) / 10_000_000.0) / 100.0;
    }

    static void cleanupTemporaryNpmInstall(Path temporaryRoot) throws IOException {
        if (temporaryRoot == null) return;
        deleteTree(temporaryRoot);
        ACTIVE_IWA_TEMP_PATHS.remove(temporaryRoot);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static final class CliError extends RuntimeException {
        CliError(String message) {
            super(message);
        }
    }

    private record Options(Path jar, Path output, Path vineflower, Path java17, Path patchBundle,
            String expectedBundleSha256, Path projectSkeleton, String expectedSkeletonSha256,
            Path resourceOverlay, String expectedResourceOverlaySha256, Path externalResourceRoot,
            int timeoutSeconds) {
        static Options parse(String[] args) {
            Path jar = null;
            Path output = null;
            Path vineflower = null;
            Path java17 = null;
            Path patchBundle = null;
            String expectedBundleSha256 = null;
            Path projectSkeleton = null;
            String expectedSkeletonSha256 = null;
            Path resourceOverlay = null;
            String expectedResourceOverlaySha256 = null;
            Path externalResourceRoot = null;
            int timeout = 900;
            for (int i = 1; i < args.length; i++) {
                String option = args[i];
                if (i + 1 >= args.length) {
                    throw new CliError("missing value for " + option);
                }
                String value = args[++i];
                switch (option) {
                    case "--jar" -> jar = Path.of(value);
                    case "--output" -> output = Path.of(value);
                    case "--vineflower" -> vineflower = Path.of(value);
                    case "--java17" -> java17 = Path.of(value);
                    case "--patch-bundle" -> patchBundle = Path.of(value);
                    case "--expected-bundle-sha256" -> expectedBundleSha256 = value;
                    case "--project-skeleton" -> projectSkeleton = Path.of(value);
                    case "--expected-skeleton-sha256" -> expectedSkeletonSha256 = value;
                    case "--resource-overlay" -> resourceOverlay = Path.of(value);
                    case "--expected-resource-overlay-sha256" -> expectedResourceOverlaySha256 = value;
                    case "--external-resource-root" -> externalResourceRoot = Path.of(value);
                    case "--timeout-seconds" -> {
                        try {
                            timeout = Integer.parseInt(value);
                        } catch (NumberFormatException ex) {
                            throw new CliError("invalid --timeout-seconds: " + value);
                        }
                    }
                    default -> throw new CliError("unknown option: " + option);
                }
            }
            if (jar == null || output == null || vineflower == null || java17 == null || patchBundle == null
                    || expectedBundleSha256 == null) {
                throw new CliError("--jar, --output, --vineflower, --java17, --patch-bundle and"
                        + " --expected-bundle-sha256 are required");
            }
            if ((projectSkeleton == null) != (expectedSkeletonSha256 == null)) {
                throw new CliError("--project-skeleton and --expected-skeleton-sha256 must be supplied together");
            }
            if ((resourceOverlay == null) != (expectedResourceOverlaySha256 == null)) {
                throw new CliError("--resource-overlay and --expected-resource-overlay-sha256 must be supplied together");
            }
            if ((resourceOverlay == null) != (externalResourceRoot == null)) {
                throw new CliError("--resource-overlay and --external-resource-root must be supplied together");
            }
            if (timeout < 1 || timeout > 900) {
                throw new CliError("--timeout-seconds must be between 1 and 900");
            }
            return new Options(jar, output, vineflower, java17, patchBundle, expectedBundleSha256,
                    projectSkeleton, expectedSkeletonSha256, resourceOverlay, expectedResourceOverlaySha256,
                    externalResourceRoot, timeout);
        }
    }

    private record PatchOptions(Path baseSource, Path output, Path bundle, String expectedBundleSha256) {
        static PatchOptions parse(String[] args) {
            Path baseSource = null;
            Path output = null;
            Path bundle = null;
            String expected = null;
            for (int i = 1; i < args.length; i++) {
                if (i + 1 >= args.length) {
                    throw new CliError("missing value for " + args[i]);
                }
                String option = args[i];
                String value = args[++i];
                switch (option) {
                    case "--base-source" -> baseSource = Path.of(value);
                    case "--output" -> output = Path.of(value);
                    case "--patch-bundle" -> bundle = Path.of(value);
                    case "--expected-bundle-sha256" -> expected = value;
                    default -> throw new CliError("unknown option: " + option);
                }
            }
            if (baseSource == null || output == null || bundle == null || expected == null) {
                throw new CliError("--base-source, --output, --patch-bundle and"
                        + " --expected-bundle-sha256 are required");
            }
            return new PatchOptions(baseSource, output, bundle, expected);
        }
    }

    private record BuildOptions(Options create, Path java25, Path node, Path npm, Path soundsEpk,
            String expectedSoundsEpkSha256, Path musicEpk, String expectedMusicEpkSha256,
            Path standaloneOutput, Path wispcraftScript, Path iwaOutput, Path iwaKey, boolean isolatedApp,
            boolean withMusic, boolean reuseProject, boolean reuseClient, Path musicPackOutput,
            int npmTimeoutSeconds, int buildTimeoutSeconds) {
        static BuildOptions parse(String[] args, boolean isolatedApp) {
            Path java25 = null;
            Path node = null;
            Path npm = null;
            Path soundsEpk = null;
            String expectedSoundsEpkSha256 = null;
            Path musicEpk = null;
            String expectedMusicEpkSha256 = null;
            Path standaloneOutput = null;
            Path iwaOutput = null;
            Path iwaKey = null;
            Path wispcraftScript = null;
            boolean withMusic = false;
            boolean reuseProject = false;
            boolean reuseClient = false;
            Path musicPackOutput = null;
            int npmTimeout = 600;
            int buildTimeout = DEFAULT_BUILD_TIMEOUT_SECONDS;
            List<String> createArgs = new ArrayList<>();
            createArgs.add("create-dev");
            for (int i = 1; i < args.length; i++) {
                String option = args[i];
                if ("--with-music".equals(option)) {
                    withMusic = true;
                    continue;
                }
                if ("--reuse-project".equals(option)) {
                    reuseProject = true;
                    continue;
                }
                if ("--reuse-client".equals(option)) {
                    reuseClient = true;
                    continue;
                }
                if (i + 1 >= args.length) {
                    throw new CliError("missing value for " + option);
                }
                String value = args[++i];
                switch (option) {
                    case "--java25" -> java25 = Path.of(value);
                    case "--node" -> node = Path.of(value);
                    case "--npm" -> npm = Path.of(value);
                    case "--sounds-epk" -> soundsEpk = Path.of(value);
                    case "--expected-sounds-epk-sha256" -> expectedSoundsEpkSha256 = value;
                    case "--music-epk" -> musicEpk = Path.of(value);
                    case "--expected-music-epk-sha256" -> expectedMusicEpkSha256 = value;
                    case "--standalone-output" -> standaloneOutput = Path.of(value);
                    case "--iwa-output" -> iwaOutput = Path.of(value);
                    case "--iwa-key" -> iwaKey = Path.of(value);
                    case "--wispcraft-script" -> wispcraftScript = Path.of(value);
                    case "--music-pack-output" -> musicPackOutput = Path.of(value);
                    case "--npm-timeout-seconds" -> {
                        try {
                            npmTimeout = Integer.parseInt(value);
                        } catch (NumberFormatException ex) {
                            throw new CliError("invalid --npm-timeout-seconds: " + value);
                        }
                    }
                    case "--build-timeout-seconds" -> {
                        try {
                            buildTimeout = Integer.parseInt(value);
                        } catch (NumberFormatException ex) {
                            throw new CliError("invalid --build-timeout-seconds: " + value);
                        }
                    }
                    default -> {
                        createArgs.add(option);
                        createArgs.add(value);
                    }
                }
            }
            if (java25 == null || node == null || npm == null || soundsEpk == null
                    || expectedSoundsEpkSha256 == null || musicEpk == null
                    || expectedMusicEpkSha256 == null
                    || (isolatedApp ? iwaOutput == null : standaloneOutput == null)) {
                if (isolatedApp) {
                    throw new CliError("build-iwa requires --java25, --node, --npm, --sounds-epk,"
                            + " --expected-sounds-epk-sha256, --music-epk,"
                            + " --expected-music-epk-sha256 and --iwa-output");
                }
                throw new CliError("build-standalone requires --java25, --node, --npm, --sounds-epk,"
                        + " --expected-sounds-epk-sha256, --music-epk,"
                        + " --expected-music-epk-sha256 and --standalone-output");
            }
            if (npmTimeout < 10 || npmTimeout > 1_800) {
                throw new CliError("--npm-timeout-seconds must be between 10 and 1800");
            }
            if (!isSupportedBuildTimeoutSeconds(buildTimeout)) {
                throw new CliError("--build-timeout-seconds must be between 60 and 7200");
            }
            if (reuseClient && !reuseProject) {
                throw new CliError("--reuse-client requires --reuse-project");
            }
            if (withMusic && musicPackOutput != null) {
                throw new CliError("--with-music and --music-pack-output are mutually exclusive");
            }
            if (isolatedApp && (wispcraftScript != null || withMusic
                    || reuseProject || reuseClient || musicPackOutput != null)) {
                throw new CliError("build-iwa does not accept Wispcraft, project reuse, or music-output options");
            }
            if (!isolatedApp && (iwaOutput != null || iwaKey != null)) {
                throw new CliError("--iwa-output and --iwa-key are only valid with build-iwa");
            }
            Options create = Options.parse(createArgs.toArray(String[]::new));
            return new BuildOptions(create, java25, node, npm, soundsEpk, expectedSoundsEpkSha256,
                    musicEpk, expectedMusicEpkSha256, standaloneOutput, wispcraftScript,
                    iwaOutput, iwaKey, isolatedApp, withMusic, reuseProject, reuseClient, musicPackOutput,
                    npmTimeout, buildTimeout);
        }
    }

    static boolean isSupportedBuildTimeoutSeconds(int seconds) {
        return seconds >= MIN_BUILD_TIMEOUT_SECONDS && seconds <= MAX_BUILD_TIMEOUT_SECONDS;
    }

    private record ScriptInput(Path path, String sha256, byte[] contents) {
    }

    private record NpmInstallResult(Path npmCli, String npmCliSha256, String npmVersion,
            int timeoutSeconds, String packageLockSha256, String logSha256,
            Path nodeModulesDirectory, Path temporaryRoot) {
        NpmInstallResult(Path npmCli, String npmCliSha256, String npmVersion,
                int timeoutSeconds, String packageLockSha256, String logSha256) {
            this(npmCli, npmCliSha256, npmVersion, timeoutSeconds, packageLockSha256, logSha256, null, null);
        }
    }

    record SoundsInstallResult(Path input, Path destination, long size, String sha256) {
    }

    record MusicInstallResult(Path input, Path destination, long size, String sha256) {
    }

    private record EpkInstallResult(Path input, Path destination, long size, String sha256) {
    }

    private record Hashes(long size, String sha1, String sha256) {
    }

    record FileRecord(String path, String sha256, long size) {
    }

    private record Receipt(Path jar, Path output, Path vineflower, Path java17, Hashes jarHashes,
            Hashes vineflowerHashes, Hashes javaHashes, Hashes javaModulesHashes, String javaVersion, int javaFileCount,
            String baselineManifestSha256, Path patchBundle, String expectedBundleSha256,
            Path projectSkeleton, String expectedSkeletonSha256, ProjectSkeleton.Result skeletonResult,
            Path resourceOverlay, String expectedResourceOverlaySha256, Path externalResourceRoot,
            ResourceOverlay.Result resourceResult,
            PatchEngine.Result patchResult, double elapsedSeconds, int timeoutSeconds) {
        String toJson() {
            String status = projectSkeleton != null && resourceOverlay != null ? "dev-workspace-ready"
                    : resourceOverlay != null ? "patched-source-resources-ready" : "patched-source-ready";
            String skeletonFields = projectSkeleton == null ? ""
                    : "  \"project_skeleton\":" + jsonString(projectSkeleton.toString()) + ",\n"
                    + "  \"project_skeleton_sha256\":\"" + expectedSkeletonSha256 + "\",\n"
                    + "  \"project_skeleton_file_count\":" + skeletonResult.fileCount() + ",\n"
                    + "  \"project_skeleton_manifest_sha256\":\"" + skeletonResult.manifestSha256() + "\",\n";
            String resourceFields = resourceOverlay == null ? ""
                    : "  \"resource_overlay\":" + jsonString(resourceOverlay.toString()) + ",\n"
                    + "  \"resource_overlay_sha256\":\"" + expectedResourceOverlaySha256 + "\",\n"
                    + "  \"external_resource_root\":" + jsonString(externalResourceRoot.toString()) + ",\n"
                    + "  \"resource_base_file_count\":" + resourceResult.baseFileCount() + ",\n"
                    + "  \"resource_base_total_bytes\":" + resourceResult.baseBytes() + ",\n"
                    + "  \"resource_base_tree_sha256\":\"" + resourceResult.baseTreeSha256() + "\",\n"
                    + "  \"resource_final_file_count\":" + resourceResult.finalFileCount() + ",\n"
                    + "  \"resource_final_total_bytes\":" + resourceResult.finalBytes() + ",\n"
                    + "  \"resource_final_tree_sha256\":\"" + resourceResult.finalTreeSha256() + "\",\n"
                    + "  \"resource_operation_count\":" + resourceResult.operationCount() + ",\n"
                    + "  \"resource_add_count\":" + resourceResult.addCount() + ",\n"
                    + "  \"resource_external_count\":" + resourceResult.externalCount() + ",\n";
            return "{\n"
                    + "  \"tool\":\"eaglercraft-26.2-java-cli\",\n"
                    + "  \"tool_version\":\"" + TOOL_VERSION + "\",\n"
                    + "  \"command\":\"create-dev\",\n"
                    + "  \"status\":\"" + status + "\",\n"
                    + "  \"patch_apply_status\":\"applied\",\n"
                    + "  \"official_client_jar\":" + jsonString(jar.toString()) + ",\n"
                    + "  \"official_client_jar_size\":" + jarHashes.size + ",\n"
                    + "  \"official_client_jar_sha1\":\"" + jarHashes.sha1 + "\",\n"
                    + "  \"official_client_jar_sha256\":\"" + jarHashes.sha256 + "\",\n"
                    + "  \"vineflower\":" + jsonString(vineflower.toString()) + ",\n"
                    + "  \"vineflower_sha256\":\"" + vineflowerHashes.sha256 + "\",\n"
                    + "  \"vineflower_version\":\"" + EXPECTED_VINEFLOWER_VERSION + "\",\n"
                    + "  \"java17\":" + jsonString(java17.toString()) + ",\n"
                    + "  \"java17_sha256\":\"" + javaHashes.sha256 + "\",\n"
                    + "  \"java17_modules_sha256\":"
                    + (javaModulesHashes == null ? "null" : jsonString(javaModulesHashes.sha256)) + ",\n"
                    + "  \"java_version\":" + jsonString(firstLine(javaVersion)) + ",\n"
                    + "  \"vineflower_flags\":[\"--log-level=warn\",\"-dgs=1\",\"-rsy=1\"],\n"
                    + "  \"environment\":{\"LANG\":\"C\",\"LC_ALL\":\"C\",\"TZ\":\"UTC\"},\n"
                    + "  \"removed_environment_options\":[\"JAVA_TOOL_OPTIONS\",\"JDK_JAVA_OPTIONS\",\"_JAVA_OPTIONS\"],\n"
                    + "  \"baseline_java_file_count\":" + javaFileCount + ",\n"
                    + "  \"baseline_manifest_sha256\":\"" + baselineManifestSha256 + "\",\n"
                    + "  \"patch_bundle\":" + jsonString(patchBundle.toString()) + ",\n"
                    + "  \"patch_bundle_sha256\":\"" + expectedBundleSha256 + "\",\n"
                    + skeletonFields
                    + resourceFields
                    + "  \"final_java_file_count\":" + patchResult.finalFileCount() + ",\n"
                    + "  \"final_manifest_sha256\":\"" + patchResult.finalManifestSha256() + "\",\n"
                    + "  \"patch_counts\":{\"add\":" + patchResult.counts().get("add")
                    + ",\"modify\":" + patchResult.counts().get("modify")
                    + ",\"delete\":" + patchResult.counts().get("delete") + "},\n"
                    + "  \"elapsed_seconds\":" + elapsedSeconds + ",\n"
                    + "  \"timeout_seconds\":" + timeoutSeconds + "\n"
                    + "}";
        }
    }
}
