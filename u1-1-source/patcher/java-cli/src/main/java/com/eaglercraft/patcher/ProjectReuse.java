package com.eaglercraft.patcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.eaglercraft.patcher.PatchEngine.PatchError;

/** Receipt and layout checks for reusing a patcher-created standalone workspace. */
final class ProjectReuse {
    private static final int MAX_RECEIPT_BYTES = 1024 * 1024;
    private static final String TOOL = "eaglercraft-26.2-java-cli";
    private static final String COMMAND = "create-dev";
    private static final String READY = "dev-workspace-ready";
    private static final Set<String> REQUIRED_FILES = Set.of(
            "receipt.json", "baseline-manifest.json", "skeleton-manifest.json",
            "settings.gradle.kts", "build.gradle.kts", "gradlew", "gradle/wrapper/gradle-wrapper.jar",
            "package.json", "package-lock.json", "source/version.json",
            "game/build.gradle.kts", "game/src/main/java",
            "wasm-toolchain/build-single-html.js", "wasm-toolchain/content-verified-brotli.js");

    private ProjectReuse() {
    }

    /** Cheap, non-recursive predicate suitable for an asynchronous CLI preflight. */
    static boolean looksLikeProject(Path directory) {
        try {
            Path root = directory.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return false;
            for (String relative : REQUIRED_FILES) {
                Path member = root;
                for (Path part : Path.of(relative)) {
                    member = member.resolve(part);
                    if (Files.isSymbolicLink(member)) return false;
                }
                if (relative.endsWith("/java")) {
                    if (!Files.isDirectory(member, LinkOption.NOFOLLOW_LINKS)) return false;
                } else if (!Files.isRegularFile(member, LinkOption.NOFOLLOW_LINKS)) {
                    return false;
                }
            }
            Map<String, Object> receipt = receipt(root);
            return TOOL.equals(string(receipt, "tool"))
                    && COMMAND.equals(string(receipt, "command"))
                    && READY.equals(string(receipt, "status"))
                    && "applied".equals(string(receipt, "patch_apply_status"));
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    static Result validate(Path directory, String expectedBundleSha256,
            String expectedSkeletonSha256, String expectedResourceOverlaySha256) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        if (!looksLikeProject(root)) {
            throw new PatchError("reuse project is not a recognized patcher-created workspace (receipt or required layout is missing/incompatible): " + root);
        }
        rejectSymlinkAncestors(root);
        Map<String, Object> receipt = receipt(root);
        requireEqual("official_client_jar_sha256", Main.expectedClientJarSha256(), receipt);
        requireEqual("patch_bundle_sha256", expectedBundleSha256, receipt);
        requireEqual("project_skeleton_sha256", expectedSkeletonSha256, receipt);
        requireEqual("project_skeleton_manifest_sha256", ProjectSkeleton.acceptedManifestSha256(), receipt);
        requireEqual("resource_overlay_sha256", expectedResourceOverlaySha256, receipt);

        String originalManifest = hash(receipt, "final_manifest_sha256");
        long originalFileCount = number(receipt, "final_java_file_count");
        if (originalFileCount < 1) {
            throw new PatchError("reuse project receipt has an invalid original patched-source file count");
        }
        var currentFiles = Main.scanJavaTree(root.resolve("game/src/main/java"));
        if (currentFiles.isEmpty()) {
            throw new PatchError("reuse project has no Java sources under game/src/main/java");
        }
        String currentManifest = sourceManifestDigest(currentFiles);
        Result result = new Result(root, currentFiles.size(), originalFileCount, currentManifest,
                originalManifest, currentManifest.equals(originalManifest));
        ensureGuide(root);
        return result;
    }

    /** Uses the same canonical manifest-object format as PatchEngine's recorded final_manifest_sha256. */
    static String sourceManifestDigest(List<Main.FileRecord> files) {
        List<Object> records = new ArrayList<>(files.size());
        for (Main.FileRecord file : files) {
            records.add(Map.of("path", file.path(), "sha256", file.sha256(), "size", file.size()));
        }
        Map<String, Object> manifest = Map.of(
                "format", "eaglercraft-26.2-java-source-manifest-v1",
                "root", "game/src/main/java",
                "file_count", (long) files.size(),
                "records", records);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Json.canonical(manifest));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format("%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
    }

    static void ensureGuide(Path root) throws IOException {
        Path guide = root.resolve("GUIDE.md");
        if (Files.exists(guide, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(guide) || !Files.isRegularFile(guide, LinkOption.NOFOLLOW_LINKS)) {
                throw new PatchError("GUIDE.md must be a regular file if present: " + guide);
            }
            return;
        }
        try {
            Files.writeString(guide, GUIDE, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException race) {
            if (Files.isSymbolicLink(guide) || !Files.isRegularFile(guide, LinkOption.NOFOLLOW_LINKS)) throw race;
        }
    }

    /** Makes the pinned decoder files available at the path the HTML packer reads, preserving existing dependencies. */
    static Path preparePinnedDecoder(Path project, Path installedNodeModules) throws IOException {
        Path sourcePackage = installedNodeModules.resolve("brotli-dec-wasm");
        Path sourceJs = sourcePackage.resolve("pkg/brotli_dec_wasm.js");
        Path sourceWasm = sourcePackage.resolve("pkg/brotli_dec_wasm_bg.wasm");
        requireRegular(sourceJs, "pinned Brotli decoder JavaScript");
        requireRegular(sourceWasm, "pinned Brotli decoder Wasm");
        Path nodeModules = project.resolve("node_modules");
        boolean createdNodeModules = false;
        if (Files.exists(nodeModules, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(nodeModules) || !Files.isDirectory(nodeModules, LinkOption.NOFOLLOW_LINKS)) {
                throw new PatchError("reused project node_modules must be a real directory if present: " + nodeModules);
            }
        } else {
            Files.createDirectory(nodeModules);
            createdNodeModules = true;
        }
        Path destinationPackage = nodeModules.resolve("brotli-dec-wasm");
        Path destinationJs = destinationPackage.resolve("pkg/brotli_dec_wasm.js");
        Path destinationWasm = destinationPackage.resolve("pkg/brotli_dec_wasm_bg.wasm");
        Path destinationPkg = destinationPackage.resolve("pkg");
        Path stagedPackage = nodeModules.resolve(".patcher-brotli-decoder-" + UUID.randomUUID());
        boolean installed = false;
        try {
            if (Files.exists(destinationPackage, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(destinationPackage)
                        || !Files.isDirectory(destinationPackage, LinkOption.NOFOLLOW_LINKS)) {
                    throw new PatchError("reused project Brotli package must be a real directory: " + destinationPackage);
                }
                if (Files.isSymbolicLink(destinationPkg)
                        || !Files.isDirectory(destinationPkg, LinkOption.NOFOLLOW_LINKS)) {
                    throw new PatchError("reused project Brotli pkg path must be a real directory: " + destinationPkg);
                }
                requireRegular(destinationJs, "reused project's Brotli decoder JavaScript");
                requireRegular(destinationWasm, "reused project's Brotli decoder Wasm");
                if (Files.mismatch(sourceJs, destinationJs) != -1
                        || Files.mismatch(sourceWasm, destinationWasm) != -1) {
                    throw new PatchError("reused project has a different brotli-dec-wasm decoder; preserving it and stopping");
                }
                return nodeModules;
            }
            Files.createDirectory(stagedPackage);
            copyPackageTree(sourcePackage, stagedPackage);
            Files.move(stagedPackage, destinationPackage, StandardCopyOption.ATOMIC_MOVE);
            installed = true;
            return nodeModules;
        } finally {
            if (!installed) deleteTree(stagedPackage);
            if (createdNodeModules && !installed) {
                try (var children = Files.newDirectoryStream(nodeModules)) {
                    if (!children.iterator().hasNext()) Files.deleteIfExists(nodeModules);
                }
            }
        }
    }

    private static void copyPackageTree(Path source, Path destination) throws IOException {
        rejectSymlinkAncestors(source);
        Files.walkFileTree(source, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path directory,
                    java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new PatchError("symlink in pinned Brotli package: " + directory);
                }
                Path target = destination.resolve(source.relativize(directory));
                if (!target.equals(destination)) Files.createDirectory(target);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                if (!attributes.isRegularFile() || Files.isSymbolicLink(file)) {
                    throw new PatchError("non-regular member in pinned Brotli package: " + file);
                }
                Files.copy(file, destination.resolve(source.relativize(file)));
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private static void requireRegular(Path path, String label) {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new PatchError(label + " is missing or not a regular file: " + path);
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(path, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path directory, IOException error)
                    throws IOException {
                if (error != null) throw error;
                Files.deleteIfExists(directory);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private static Map<String, Object> receipt(Path root) throws IOException {
        Path path = root.resolve("receipt.json");
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new PatchError("reuse project receipt.json is missing or is not a regular file: " + path);
        }
        long size = Files.size(path);
        if (size < 2 || size > MAX_RECEIPT_BYTES) {
            throw new PatchError("reuse project receipt.json has an invalid size: " + size);
        }
        String source = Files.readString(path, StandardCharsets.UTF_8);
        Object parsed = Json.parse(integerizeReceiptNumbers(source).getBytes(StandardCharsets.UTF_8),
                "reuse project receipt.json");
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new PatchError("reuse project receipt.json must contain a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) raw;
        return result;
    }

    /** Existing build receipts may contain valid decimal timing fields; the source-bundle JSON parser is integer-only. */
    private static String integerizeReceiptNumbers(String source) {
        StringBuilder result = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            char ch = source.charAt(index);
            if (ch == '"') {
                int start = index++;
                boolean escaped = false;
                while (index < source.length()) {
                    char current = source.charAt(index++);
                    if (escaped) {
                        escaped = false;
                    } else if (current == '\\') {
                        escaped = true;
                    } else if (current == '"') {
                        break;
                    }
                }
                result.append(source, start, index);
            } else if (ch == '-' || ch >= '0' && ch <= '9') {
                int start = index;
                if (ch == '-') index++;
                if (index >= source.length()) throw new Json.JsonError("reuse project receipt.json: invalid number");
                if (source.charAt(index) == '0') {
                    index++;
                    if (index < source.length() && isDigit(source.charAt(index))) {
                        throw new Json.JsonError("reuse project receipt.json: leading zero in number");
                    }
                } else if (source.charAt(index) >= '1' && source.charAt(index) <= '9') {
                    while (index < source.length() && isDigit(source.charAt(index))) index++;
                } else {
                    throw new Json.JsonError("reuse project receipt.json: invalid number");
                }
                boolean fractional = false;
                if (index < source.length() && source.charAt(index) == '.') {
                    fractional = true;
                    index++;
                    int digitsStart = index;
                    while (index < source.length() && isDigit(source.charAt(index))) index++;
                    if (digitsStart == index) throw new Json.JsonError("reuse project receipt.json: invalid decimal");
                }
                if (index < source.length() && (source.charAt(index) == 'e' || source.charAt(index) == 'E')) {
                    fractional = true;
                    index++;
                    if (index < source.length() && (source.charAt(index) == '+' || source.charAt(index) == '-')) index++;
                    int digitsStart = index;
                    while (index < source.length() && isDigit(source.charAt(index))) index++;
                    if (digitsStart == index) throw new Json.JsonError("reuse project receipt.json: invalid exponent");
                }
                if (index < source.length()) {
                    char next = source.charAt(index);
                    if (!(next == ',' || next == ']' || next == '}' || Character.isWhitespace(next))) {
                        throw new Json.JsonError("reuse project receipt.json: invalid number terminator");
                    }
                }
                result.append(fractional ? "0" : source.substring(start, index));
            } else {
                result.append(ch);
                index++;
            }
        }
        return result.toString();
    }

    private static boolean isDigit(char ch) {
        return ch >= '0' && ch <= '9';
    }

    private static String string(Map<String, Object> receipt, String field) {
        Object value = receipt.get(field);
        if (!(value instanceof String string)) {
            throw new PatchError("reuse project receipt is missing string field " + field);
        }
        return string;
    }

    private static String hash(Map<String, Object> receipt, String field) {
        String value = string(receipt, field);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new PatchError("reuse project receipt has an invalid SHA-256 field " + field);
        }
        return value;
    }

    private static long number(Map<String, Object> receipt, String field) {
        Object value = receipt.get(field);
        if (!(value instanceof Long number) || number < 0) {
            throw new PatchError("reuse project receipt is missing non-negative integer field " + field);
        }
        return number;
    }

    private static void requireEqual(String field, String expected, Map<String, Object> receipt) {
        String recorded = field.endsWith("sha256") ? hash(receipt, field) : string(receipt, field);
        if (!recorded.equals(expected)) {
            throw new PatchError("reuse project is incompatible: receipt " + field + " is " + recorded
                    + ", expected " + expected);
        }
    }

    private static void rejectSymlinkAncestors(Path root) {
        Path current = root.getRoot();
        for (Path part : root) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new PatchError("reuse project path contains a symlink: " + current);
            }
        }
    }

    static String guideText() {
        return GUIDE;
    }

    record Result(Path directory, int currentSourceFileCount, long originalSourceFileCount,
            String currentSourceManifestSha256, String originalSourceManifestSha256,
            boolean sourceMatchesOriginalReceipt) {
    }

    private static final String GUIDE = """
            # Building Eaglercraft 26.2

            ## Choose an output

            - Source project extracts and patches the Java source without compiling the client.
            - Standalone HTML creates the source project, then builds one playable HTML file. You do not need to extract source first.
            - Isolated Web App builds a signed local app bundle. Installation is covered in `iwa/README.md`.

            Choose your own project and output paths. A new project folder must be empty.
            After a successful run, Open folder shows the result.

            The complete Setup JAR unpacks the patch bundle, skeleton, resource overlay
            and bundled audio into its installation folder. The patcher finds these
            files automatically; you do not need to extract or link the internal ZIPs.
            Supply your official Minecraft 26.2 JAR and choose the output paths.
            Keep the installation files together. A bare GUI JAR is not a complete kit.

            For an existing project, select Standalone HTML and choose its folder. Reuse is
            automatic. Choose a new HTML filename. The patcher checks the
            project's receipt and keeps your source edits, original receipt and existing
            guide. The build receipt records whether the Java source changed.

            ## Tools, memory and time

            Install build tools in the patcher to set up Java and Node locally. The patcher
            uses Java 17 for extraction and Java 25 for compilation. Keep the supplied input
            files together; their hashes identify the supported version.

            If sounds or music are missing, Get game audio can fetch the official sound
            objects and rebuild the archives. Install FFmpeg 6.1.1 and select it first;
            the patcher does not install FFmpeg. Get official resources rebuilds the six
            external files used by the overlay. These actions do not create the source
            patch bundle, project skeleton, or overlay supplied with the patcher.

            Allow about 30 minutes on a reasonably capable PC. One completed local build
            took 41 minutes 46 seconds. Downloads and slower hardware can add time.

            HTML and IWA builds need at least a 10 GiB build budget plus memory reserved for
            the system. 16 GiB RAM or more is recommended. The patcher measures available
            memory, sets Java limits and stops if too little is available. Smaller heaps can
            take longer or still run out of heap. Source extraction uses a smaller budget.
            Do not run two builds in the same project.

            ## What is in the source folder?

            | Folder or file | Contents |
            | --- | --- |
            | `game/src/main/java` | Patched Minecraft game code |
            | `game/src/main/resources` | Game resources, when present |
            | `platform` | Shared Eaglercraft platform code |
            | `platform-teavm` | Browser platform implementation |
            | `teavm-compat` | Java compatibility code for TeaVM |
            | `target_teavm_wasm_gc` | Browser client target and web output |
            | `target_teavm_wasm_gc_server` | Singleplayer server worker target |
            | `target_teavm_wasm_gc_mesh` | Mesh worker target |
            | `wasm-toolchain` | Linking and HTML packaging scripts |
            | `source` | Extracted inputs and source-generation files |
            | `receipt.json` | Source creation details and input hashes |
            | `build.gradle.kts`, `settings.gradle.kts` | Gradle build configuration |

            Keep backups before editing. Do not delete the receipt if you want to reuse
            the project in the patcher.

            ## Compile Java only

            Set `JAVA_HOME` and `PATH` to a Java 25 JDK, then run this from the project folder:

            ```sh
            ./gradlew :game:compileJava --console=plain --no-daemon
            ```

            On Windows, use `gradlew.bat :game:compileJava --console=plain --no-daemon`.
            This checks the game Java code; it does not produce the browser client.

            ## Build the normal HTML

            Use Standalone HTML in the patcher. It supplies the tool paths, checked inputs
            and memory limits. Advanced settings let you embed music in the HTML. Otherwise,
            the build writes an optional music resource-pack ZIP beside it; import that ZIP
            through Minecraft's Resource Packs menu.

            The CLI equivalent is `build-standalone ... --output <this-folder> --reuse-project`
            with the required input and output arguments. Run
            `java -jar eaglercraft-26.2-java-cli.jar --help` for the argument list.

            Reuse does not extract or patch Java again. It keeps existing dependencies and
            may add the pinned Brotli decoder if missing. A different decoder is rejected
            instead of being overwritten.

            ## Smaller files, faster packaging and raw web files

            The normal standalone HTML uses lossless compression. There is no uncompressed
            single-HTML option.

            After a successful build, you can repackage its existing web output with faster
            compression. From the project folder, with Node on PATH:

            ```sh
            node wasm-toolchain/build-single-html.js --skip-build --fast-package --output fast-client.html
            ```

            This uses faster compression when needed, or reuses valid compressed files.
            The HTML may be larger and is still compressed. Use a new output filename.
            Do not use `--skip-build` after source edits: it packages the old build.

            The same build leaves a multi-file website in
            `target_teavm_wasm_gc/build/web`. Its `.wasm` files are the raw WebAssembly output;
            `.br` and `.gz` files are compressed copies. For ordinary raw-file hosting,
            serve the folder without automatic Brotli/gzip rewrites. EPK asset archives keep
            their own format, so this is not a promise that every asset is uncompressed.

            To test that folder locally, if Python 3 is installed:

            ```sh
            python3 -m http.server 8000 --directory target_teavm_wasm_gc/build/web
            ```

            Open `http://localhost:8000/`. On Windows, `py -3` can replace `python3`.
            Test the title screen, a world, and saving before sharing the build.

            ## Cloudflare hosting

            Cloudflare is a hosting step after compilation, not another Java build mode.
            The large HTML and raw Wasm files can exceed Cloudflare static-asset limits.
            Do not assume that uploading the standalone HTML to Pages will work.

            One option is an R2 bucket served by a Worker. This avoids packing the whole
            game into the Worker code. The recipe below is a deployment example, not a
            deployment performed by the patcher. Check Cloudflare's current limits and
            pricing, and only upload files you are allowed to distribute.

            1. Finish a normal HTML build.
            2. Create a separate folder for the Worker configuration below.
            3. Create an R2 bucket and upload the contents of
               `target_teavm_wasm_gc/build/web` to its root, preserving subfolders.
               Upload the raw `.wasm` files. The example does not use `.br`, `.gz`,
               `.meta` or `.sha256` sidecars.
            4. Bind the bucket as `GAME` and deploy the Worker. Use your own project and
               bucket names, never another project's credentials.

            Example `wrangler.toml`:

            ```toml
            name = "my-eagler-client"
            main = "worker.js"
            compatibility_date = "2026-09-29"

            [[r2_buckets]]
            binding = "GAME"
            bucket_name = "my-eagler-client-files"
            ```

            Example `worker.js`:

            ```js
            export default {
              async fetch(request, env) {
                if (request.method !== "GET" && request.method !== "HEAD") {
                  return new Response("Method not allowed", { status: 405 });
                }
                const url = new URL(request.url);
                const key = url.pathname === "/" ? "index.html" : url.pathname.slice(1);
                const object = await env.GAME.get(key);
                if (!object) return new Response("Not found", { status: 404 });
                const types = {
                  html: "text/html; charset=utf-8",
                  js: "text/javascript; charset=utf-8",
                  wasm: "application/wasm",
                  json: "application/json",
                  png: "image/png",
                  ico: "image/x-icon"
                };
                const extension = key.split(".").pop();
                return new Response(request.method === "HEAD" ? null : object.body, {
                  headers: {
                    "Content-Type": types[extension] || "application/octet-stream",
                    "Cache-Control": "no-cache",
                    "X-Content-Type-Options": "nosniff"
                  }
                });
              }
            };
            ```

            Install Wrangler in that separate folder and sign in to your account:

            ```sh
            npm install --save-dev wrangler
            npx wrangler login
            npx wrangler deploy
            ```

            The last command publishes the Worker. Do not run it until you want the site
            online. Test the resulting URL in a fresh browser session. A signed IWA bundle
            is a separate output; hosting this website does not install an IWA.
            """;
}
