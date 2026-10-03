package com.eaglercraft.patcher;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.eaglercraft.patcher.PatchEngine.PatchError;

/** Validates and extracts the pinned project skeleton for the selected build mode. */
final class ProjectSkeleton {
    private static final String EXPECTED_ARCHIVE_SHA256 =
            "a9172bdd903ea65afd4c87cdb29b500c237c823fdd94789c2e90561b1b049469";

    static String acceptedArchiveSha256() {
        return EXPECTED_ARCHIVE_SHA256;
    }

    /* The sidecar exported by export_skeleton.py is the public manifest pin. */
    private static final String EXPECTED_MANIFEST_SHA256 =
            "be269c8f139cc5faaa45e9c44700e89a54ba602114eee3d981b53e4ab6642ff2";

    /* ZIP members use the compact canonical form; it is validated independently. */
    private static final String EXPECTED_EMBEDDED_MANIFEST_SHA256 =
            "29debc98a2af488464d6ec813531f879f2c3c399ec8fbdba3c18378a4adc3d86";

    static String acceptedManifestSha256() {
        return EXPECTED_MANIFEST_SHA256;
    }
    private static final String MANIFEST_NAME = "skeleton-manifest.json";
    private static final String MANIFEST_SCHEMA = "eaglercraft-26.2-project-skeleton-v5";
    private static final String ARCHIVE_FORMAT = "zip-stored-deterministic-v1";
    private static final long MAX_MEMBER_SIZE = 64L * 1024L * 1024L;
    private static final long MAX_ARCHIVE_SIZE = 512L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 10_000;
    private static final List<String> LEGAL_EXCLUSIONS = List.of(
            "game/src/main/java", "game/src/main/resources", "mojang-source", "assets", "patcher",
            "build", ".gradle", "output", "mod-support", "node_modules", "wasm-toolchain/*.log",
            "wasm-toolchain/classes-debug-D8w.wasm");

    private ProjectSkeleton() {
    }

    static void assertAcceptedArchive(Path archive, String expectedSha256) throws IOException {
        Path zipPath = archive.toAbsolutePath().normalize();
        if (!EXPECTED_ARCHIVE_SHA256.equals(expectedSha256)) {
            throw new PatchError("project skeleton expected SHA-256 is not the accepted v5 archive identity");
        }
        if (Files.isSymbolicLink(zipPath) || !Files.isRegularFile(zipPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new PatchError("project skeleton is not a regular file: " + zipPath);
        }
        String actualSha = sha256(zipPath);
        if (!actualSha.equals(expectedSha256)) {
            throw new PatchError("project skeleton SHA-256 mismatch: expected " + expectedSha256 + ", got " + actualSha);
        }
    }

    static Result extract(Path archive, Path destination, String expectedSha256) throws IOException {
        Path zipPath = archive.toAbsolutePath().normalize();
        assertAcceptedArchive(zipPath, expectedSha256);
        Path parent = destination.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new PatchError("project skeleton destination parent must already exist: " + destination);
        }
        rejectSymlinkAncestors(parent, "project skeleton destination parent");

        TreeMap<String, byte[]> files = readArchive(zipPath);
        Map<String, ManifestEntry> manifest = validateManifest(files);
        Set<String> payload = new HashSet<>(files.keySet());
        payload.remove(MANIFEST_NAME);
        if (!payload.equals(manifest.keySet())) {
            throw new PatchError("project skeleton archive and manifest file sets differ");
        }
        for (Map.Entry<String, ManifestEntry> entry : manifest.entrySet()) {
            byte[] data = files.get(entry.getKey());
            ManifestEntry expected = entry.getValue();
            if (data.length != expected.size || !sha256(data).equals(expected.sha256)) {
                throw new PatchError("project skeleton file hash/size mismatch: " + entry.getKey());
            }
        }

        boolean createdDestination = false;
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(destination) || !Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new PatchError("project skeleton destination is not a real directory: " + destination);
            }
            try (var children = Files.list(destination)) {
                if (children.findAny().isPresent()) {
                    throw new PatchError("project skeleton destination must be empty: " + destination);
                }
            }
        } else {
            Files.createDirectory(destination);
            createdDestination = true;
        }
        boolean complete = false;
        try {
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                String path = entry.getKey();
                Path target = safeJoin(destination, path);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
                    throw new PatchError("project skeleton extraction collision: " + path);
                }
                Files.createDirectories(target.getParent());
                rejectSymlinkAncestors(target.getParent(), "project skeleton extraction");
                Files.write(target, entry.getValue());
                applyMode(target, MANIFEST_NAME.equals(path) ? "100644" : manifest.get(path).mode);
            }
            complete = true;
            return new Result(actualSha256(zipPath), manifest.size(), EXPECTED_MANIFEST_SHA256);
        } finally {
            if (!complete && createdDestination) deleteTree(destination);
        }
    }

    private static TreeMap<String, byte[]> readArchive(Path archive) throws IOException {
        TreeMap<String, byte[]> files = new TreeMap<>();
        long total = 0L;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            if (zip.size() > MAX_ENTRIES) throw new PatchError("project skeleton has too many ZIP entries: " + zip.size());
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String path = normalize(entry.getName());
                if (entry.isDirectory() || entry.getName().endsWith("/")) {
                    throw new PatchError("project skeleton must contain files only: " + path);
                }
                long size = entry.getSize();
                long compressed = entry.getCompressedSize();
                if (size < 0 || size > MAX_MEMBER_SIZE || compressed < 0
                        || (size > 0 && compressed == 0) || (compressed > 0 && size / compressed > 200)) {
                    throw new PatchError("project skeleton entry has an invalid size: " + path);
                }
                total = Math.addExact(total, size);
                if (total > MAX_ARCHIVE_SIZE) throw new PatchError("project skeleton expanded size exceeds the safety limit");
                if (files.put(path, readEntry(zip, entry)) != null) {
                    throw new PatchError("duplicate project skeleton path: " + path);
                }
            }
        } catch (ArithmeticException ex) {
            throw new PatchError("project skeleton size overflow");
        }
        String previous = null;
        for (String path : files.keySet()) {
            if (previous != null && path.startsWith(previous + "/")) {
                throw new PatchError("project skeleton file/directory collision: " + previous);
            }
            previous = path;
        }
        return files;
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry entry) throws IOException {
        long declared = entry.getSize();
        try (InputStream input = new BufferedInputStream(zip.getInputStream(entry))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) declared);
            byte[] buffer = new byte[1024 * 1024];
            long copied = 0L;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) continue;
                copied += count;
                if (copied > declared || copied > MAX_MEMBER_SIZE) {
                    throw new PatchError("project skeleton entry expanded beyond its declaration: " + entry.getName());
                }
                output.write(buffer, 0, count);
            }
            if (copied != declared) throw new PatchError("project skeleton entry size changed while reading: " + entry.getName());
            return output.toByteArray();
        }
    }

    private static Map<String, ManifestEntry> validateManifest(TreeMap<String, byte[]> files) {
        byte[] bytes = files.get(MANIFEST_NAME);
        if (bytes == null || !EXPECTED_EMBEDDED_MANIFEST_SHA256.equals(sha256(bytes))) {
            throw new PatchError("project skeleton manifest identity mismatch");
        }
        Map<String, Object> object = map(Json.parse(bytes, MANIFEST_NAME), MANIFEST_NAME);
        requireKeys(object, Set.of("archive_format", "capability", "file_count", "files", "omitted",
                "provenance_review", "schema"), MANIFEST_NAME);
        requireString(object, "archive_format", ARCHIVE_FORMAT);
        requireString(object, "schema", MANIFEST_SCHEMA);
        validateCapability(map(object.get("capability"), "skeleton capability"));
        long count = integer(object.get("file_count"), "skeleton file_count");
        if (!(object.get("files") instanceof List<?> list) || count != list.size() || count > MAX_ENTRIES) {
            throw new PatchError("project skeleton manifest file count is invalid");
        }
        TreeMap<String, ManifestEntry> result = new TreeMap<>();
        String previous = null;
        for (Object value : list) {
            Map<String, Object> record = map(value, "skeleton manifest file");
            requireKeys(record, Set.of("mode", "path", "sha256", "size"), "skeleton manifest file");
            String path = normalize(string(record.get("path"), "skeleton manifest path"));
            if (!path.equals(record.get("path")) || (previous != null && path.compareTo(previous) <= 0)) {
                throw new PatchError("skeleton manifest paths are not canonical and sorted");
            }
            previous = path;
            String mode = string(record.get("mode"), "skeleton manifest mode");
            if (!"100644".equals(mode) && !"100755".equals(mode)) throw new PatchError("unsupported project skeleton mode: " + path);
            String hash = string(record.get("sha256"), "skeleton manifest SHA-256");
            if (!hash.matches("[0-9a-f]{64}")) throw new PatchError("invalid project skeleton SHA-256: " + path);
            long size = integer(record.get("size"), "skeleton manifest size");
            if (size < 0 || size > MAX_MEMBER_SIZE || result.put(path, new ManifestEntry(hash, size, mode)) != null) {
                throw new PatchError("duplicate skeleton manifest path: " + path);
            }
        }
        Map<String, Object> omitted = map(object.get("omitted"), "skeleton omissions");
        if (!(omitted.get("paths") instanceof List<?> omittedPaths)
                || !(omitted.get("reason") instanceof String reason) || reason.isBlank()) {
            throw new PatchError("skeleton omissions are invalid");
        }
        Set<String> exclusions = new HashSet<>();
        for (Object value : omittedPaths) exclusions.add(string(value, "skeleton omitted path"));
        if (!exclusions.containsAll(LEGAL_EXCLUSIONS)) throw new PatchError("skeleton legal exclusions are incomplete");
        for (String path : result.keySet()) for (String excluded : exclusions) {
            if (omitted(path, excluded)) throw new PatchError("skeleton contains legally excluded path: " + path);
        }
        return result;
    }

    private static void validateCapability(Map<String, Object> capability) {
        requireKeys(capability, Set.of("classification", "entrypoint", "external_inputs_required",
                "generated_inputs", "host_tools", "known_blockers", "node_install"), "skeleton capability");
        requireString(capability, "classification",
                "standalone-and-local-iwa-build-input-skeleton; not a completed or tested build");
        requireString(capability, "entrypoint", "npm run build:single -- --output <path>; node iwa/build-iwa.mjs --source <hosted-web-dir> --output <local.swbn> --key <local.pem>");
        requireString(capability, "node_install",
                "the Java CLI runs npm ci --ignore-scripts against this authenticated lockfile");
        requireStringList(capability, "external_inputs_required", List.of(
                "patched game/src/main/java reconstructed from the verified official client JAR and source patch bundle",
                "patched game/src/main/resources reconstructed from the verified official client JAR, resource overlay, and six separately supplied external additions",
                "target_teavm/build/web/sounds.epk from a separately authorized, hash-pinned source"));
        requireStringList(capability, "known_blockers", List.of(
                "no fresh standalone Wasm/HTML build has been executed from this skeleton"));
        requireStringList(capability, "generated_inputs", List.of(
                "the standalone client linker extracts classes.wasm-runtime.js from the authenticated TeaVM 0.13.1 tool classpath and verifies its 13,984-byte SHA-256 before installation"));
        requireStringList(capability, "host_tools", List.of(
                "bash and POSIX core utilities used by deploy_wasm_web.sh",
                "unzip for extracting the pinned TeaVM runtime resource from the authenticated tool classpath",
                "Node.js plus npm",
                "OpenSSL for a locally generated IWA signing key",
                "IWA npm dependencies installed separately from iwa/package-lock.json",
                "the fully pinned Java runtime required by the Java patcher/build gate"));
    }

    private static void requireStringList(Map<String, Object> object, String key, List<String> expected) {
        Object value = object.get(key);
        if (!(value instanceof List<?> list) || list.size() != expected.size()) {
            throw new PatchError(key + " is not the pinned list");
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!(list.get(i) instanceof String text) || !expected.get(i).equals(text)) {
                throw new PatchError(key + " is not the pinned list");
            }
        }
    }

    private static boolean omitted(String path, String pattern) {
        if (pattern.indexOf('*') >= 0) {
            String regex = pattern.replace(".", "\\.").replace("*", ".*");
            return path.matches(regex);
        }
        return path.equals(pattern) || path.startsWith(pattern + "/");
    }

    private static void applyMode(Path target, String mode) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(target, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) return;
        Set<PosixFilePermission> permissions = new HashSet<>(Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));
        if ("100755".equals(mode)) permissions.addAll(Set.of(PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE));
        view.setPermissions(permissions);
    }

    private static Map<String, Object> map(Object value, String label) {
        if (!(value instanceof Map<?, ?> raw)) throw new PatchError(label + " must be an object");
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new PatchError(label + " has a non-string key");
            out.put(key, entry.getValue());
        }
        return out;
    }

    private static void requireKeys(Map<String, Object> object, Set<String> keys, String label) {
        if (!object.keySet().equals(keys)) throw new PatchError(label + " has unexpected or missing fields");
    }

    private static String requireString(Map<String, Object> object, String key, String expected) {
        String value = string(object.get(key), key);
        if (!expected.equals(value)) throw new PatchError(key + " is not the pinned value");
        return value;
    }

    private static String string(Object value, String label) {
        if (!(value instanceof String text) || text.isEmpty()) throw new PatchError(label + " must be a non-empty string");
        return text;
    }

    private static long integer(Object value, String label) {
        if (!(value instanceof Long number)) throw new PatchError(label + " must be an integer");
        return number;
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isEmpty() || raw.indexOf('\0') >= 0 || raw.indexOf('\\') >= 0
                || raw.startsWith("/") || raw.matches("^[A-Za-z]:/.*")) throw new PatchError("unsafe project skeleton path: " + raw);
        List<String> parts = new ArrayList<>();
        for (String part : raw.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (parts.isEmpty()) throw new PatchError("project skeleton path escapes root: " + raw);
                parts.remove(parts.size() - 1);
            } else parts.add(part);
        }
        if (parts.isEmpty()) throw new PatchError("empty project skeleton path: " + raw);
        return String.join("/", parts);
    }

    private static Path safeJoin(Path root, String relative) {
        Path target = root.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(root.normalize())) throw new PatchError("project skeleton path escapes destination: " + relative);
        rejectSymlinkAncestors(target, "project skeleton path");
        return target;
    }

    private static void rejectSymlinkAncestors(Path path, String label) {
        Path current = path.toAbsolutePath().normalize();
        Path root = current.getRoot();
        while (current != null && !current.equals(root)) {
            if (Files.isSymbolicLink(current)) throw new PatchError(label + " contains a symlink: " + current);
            current = current.getParent();
        }
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return digest(input);
        }
    }

    private static String actualSha256(Path path) throws IOException {
        return sha256(path);
    }

    private static String sha256(byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
    }

    private static String digest(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc; Files.deleteIfExists(dir); return FileVisitResult.CONTINUE;
            }
        });
    }

    private record ManifestEntry(String sha256, long size, String mode) {
    }

    record Result(String archiveSha256, int fileCount, String manifestSha256) {
    }
}
