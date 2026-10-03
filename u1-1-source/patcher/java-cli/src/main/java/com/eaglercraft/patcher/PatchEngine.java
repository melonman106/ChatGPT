package com.eaglercraft.patcher;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Validates and applies the accepted typed Java source-patch bundle. */
final class PatchEngine {
    private static final String FORMAT = "eaglercraft-26.2-java-source-patch-bundle-v1";
    private static final String MANIFEST_FORMAT = "eaglercraft-26.2-java-source-manifest-v1";
    private static final String DELTA_FORMAT = "eaglercraft-26.2-java-source-delta-v1";
    private static final String TOOL_VERSION = "1";
    private static final int MAX_ARCHIVE_ENTRIES = 10_000;
    private static final long MAX_MEMBER_UNCOMPRESSED = 64L * 1024L * 1024L;
    private static final long MAX_ARCHIVE_UNCOMPRESSED = 256L * 1024L * 1024L;
    private static final long MAX_COMPRESSION_RATIO = 200L;
    private static final Set<String> BUNDLE_FILES = Set.of(
            "bundle.json", "base-manifest.json", "final-manifest.json", "operations.json");
    private static final String EXPECTED_BUNDLE_SHA256 =
            "fd944e9cabbebbf4bddce8a39e1b233b3c7d0cd4f860f9cae37550a3fcfa8b02";

    static String acceptedBundleSha256() {
        return EXPECTED_BUNDLE_SHA256;
    }

    private PatchEngine() {
    }

    static Result apply(Path bundle, Path baseRoot, Path output, String expectedBundleSha256) throws IOException {
        Path archive = absolute(bundle);
        Path base = absolute(baseRoot);
        Path destination = absolute(output);
        ensureRegular(archive, "source patch bundle");
        if (!isAcceptedBundleSha256(expectedBundleSha256)) {
            throw new PatchError("source patch bundle expected SHA-256 is not the accepted bundle identity");
        }
        String actualBundleSha256 = sha256(archive);
        if (!actualBundleSha256.equals(expectedBundleSha256)) {
            throw new PatchError("source patch bundle SHA-256 mismatch: expected " + expectedBundleSha256
                    + ", got " + actualBundleSha256);
        }
        ensureSourceRoot(base);
        ensureAbsent(destination);
        Bundle loaded = loadBundle(archive);
        Map<String, ManifestRecord> actualBase = scanJavaTree(base);
        if (!actualBase.equals(loaded.base)) {
            throw new PatchError("decompiled source does not match the bundle base manifest");
        }

        Path parent = destination.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new PatchError("patch output parent must already be a directory: " + destination);
        }
        rejectSymlinkAncestors(parent, "patch output parent");
        Path stage = parent.resolve("." + destination.getFileName() + ".staging-" + UUID.randomUUID());
        Files.createDirectory(stage);
        boolean promoted = false;
        try {
            copyJavaTree(base, stage);
            for (Operation operation : loaded.operations) {
                applyOperation(operation, loaded.files, stage);
            }
            removeEmptyDirectories(stage);
            Map<String, ManifestRecord> actualFinal = scanJavaTree(stage);
            if (!actualFinal.equals(loaded.finalManifest)) {
                throw new PatchError("patched source does not match the bundle final manifest");
            }
            ensureAbsent(destination);
            Files.move(stage, destination, StandardCopyOption.ATOMIC_MOVE);
            promoted = true;
            return new Result(actualBundleSha256, actualFinal.size(), manifestDigest(loaded.finalObject),
                    loaded.counts);
        } finally {
            if (!promoted) {
                deleteTree(stage);
            }
        }
    }

    static void assertAcceptedBundle(Path bundle, String expectedBundleSha256) throws IOException {
        ensureRegular(bundle, "source patch bundle");
        if (!isAcceptedBundleSha256(expectedBundleSha256)) {
            throw new PatchError("source patch bundle expected SHA-256 is not the accepted bundle identity");
        }
        String actual = sha256(bundle);
        if (!actual.equals(expectedBundleSha256)) {
            throw new PatchError("source patch bundle SHA-256 mismatch: expected " + expectedBundleSha256
                    + ", got " + actual);
        }
    }

    static void assertCompatibleBundlePair(String sourceBundleSha256, String resourceOverlaySha256) {
        if (resourceOverlaySha256 == null) return;
        boolean normalPair = EXPECTED_BUNDLE_SHA256.equals(sourceBundleSha256)
                && ResourceOverlay.NORMAL_ARCHIVE_SHA256.equals(resourceOverlaySha256);
        if (!normalPair) {
            throw new PatchError("source patch and resource overlay identities are not a pinned matching pair");
        }
    }

    static void assertCreateDevProfilePair(String sourceBundleSha256, String resourceOverlaySha256) {
        assertCompatibleBundlePair(sourceBundleSha256, resourceOverlaySha256);
    }

    static void assertWebBuildAllowed(String sourceBundleSha256) {
        if (!EXPECTED_BUNDLE_SHA256.equals(sourceBundleSha256)) {
            throw new PatchError("build-standalone supports only the Normal content profile");
        }
    }

    static void assertIwaBuildAllowed(String sourceBundleSha256) {
        if (!EXPECTED_BUNDLE_SHA256.equals(sourceBundleSha256)) {
            throw new PatchError("build-iwa supports only the Normal content profile");
        }
    }

    private static boolean isAcceptedBundleSha256(String sha256) {
        return EXPECTED_BUNDLE_SHA256.equals(sha256);
    }

    private static Bundle loadBundle(Path archive) throws IOException {
        TreeMap<String, byte[]> files = new TreeMap<>();
        Map<String, String> collisions = new HashMap<>();
        long totalSize = 0;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            if (zip.size() > MAX_ARCHIVE_ENTRIES) {
                throw new PatchError("source bundle has too many ZIP entries: " + zip.size());
            }
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String raw = entry.getName();
                boolean directory = raw.endsWith("/") || entry.isDirectory();
                String normalizedRaw = directory ? raw.substring(0, raw.length() - 1) : raw;
                String path = normalizeRel(normalizedRaw, "bundle ZIP path");
                registerPrefixes(collisions, path, "bundle ZIP");
                if (files.containsKey(path) || (directory && collisions.containsKey(path + "/"))) {
                    throw new PatchError("duplicate bundle ZIP path: " + path);
                }
                if (entry.getSize() < 0 || entry.getSize() > MAX_MEMBER_UNCOMPRESSED) {
                    throw new PatchError("bundle ZIP member is too large: " + raw);
                }
                long compressed = entry.getCompressedSize();
                if (compressed < 0) {
                    throw new PatchError("bundle ZIP member has invalid compressed size: " + raw);
                }
                if (!directory && entry.getSize() > 0 && compressed == 0) {
                    throw new PatchError("bundle ZIP member has an invalid compression ratio: " + raw);
                }
                if (!directory && compressed > 0 && entry.getSize() / compressed > MAX_COMPRESSION_RATIO) {
                    throw new PatchError("bundle ZIP member compression ratio is too high: " + raw);
                }
                totalSize = Math.addExact(totalSize, Math.max(0, entry.getSize()));
                if (totalSize > MAX_ARCHIVE_UNCOMPRESSED) {
                    throw new PatchError("bundle ZIP expanded size exceeds the safety limit");
                }
                if (!directory) {
                    files.put(path, readEntry(zip, entry));
                }
            }
        } catch (ArithmeticException ex) {
            throw new PatchError("bundle ZIP size overflow");
        }
        return validateBundleFiles(files);
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream stream = new BufferedInputStream(zip.getInputStream(entry))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(entry.getSize(), 1024 * 1024));
            byte[] buffer = new byte[1024 * 1024];
            long copied = 0;
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                if (count == 0) {
                    continue;
                }
                copied += count;
                if (copied > MAX_MEMBER_UNCOMPRESSED || copied > entry.getSize()) {
                    throw new PatchError("bundle ZIP member expanded beyond its declared size: " + entry.getName());
                }
                out.write(buffer, 0, count);
            }
            if (copied != entry.getSize()) {
                throw new PatchError("bundle ZIP member size changed while reading: " + entry.getName());
            }
            return out.toByteArray();
        }
    }

    private static Bundle validateBundleFiles(TreeMap<String, byte[]> files) {
        Set<String> required = new HashSet<>(BUNDLE_FILES);
        if (!files.keySet().containsAll(required)) {
            required.removeAll(files.keySet());
            throw new PatchError("source bundle is missing required files: " + required);
        }
        Map<String, Object> metadata = jsonObject(files.get("bundle.json"), "bundle.json");
        requireKeys(metadata, Set.of("format", "tool_version", "source_kind", "base_root", "final_root",
                "base_file_count", "final_file_count", "counts", "base_manifest_sha256",
                "final_manifest_sha256", "operations_sha256"), "bundle.json");
        requireString(metadata, "format", FORMAT);
        requireString(metadata, "tool_version", TOOL_VERSION);
        requireString(metadata, "source_kind", "java-source");
        requireString(metadata, "base_root", "mojang-source");
        requireString(metadata, "final_root", "game/src/main/java");
        requireHash(metadata.get("base_manifest_sha256"), "bundle base manifest SHA-256");
        requireHash(metadata.get("final_manifest_sha256"), "bundle final manifest SHA-256");
        requireHash(metadata.get("operations_sha256"), "bundle operations SHA-256");
        Map<String, Long> metadataCounts = counts(metadata.get("counts"), "bundle counts");
        long baseFileCount = integer(metadata.get("base_file_count"), "bundle base_file_count");
        long finalFileCount = integer(metadata.get("final_file_count"), "bundle final_file_count");

        byte[] baseBytes = files.get("base-manifest.json");
        byte[] finalBytes = files.get("final-manifest.json");
        byte[] operationsBytes = files.get("operations.json");
        Object baseObject = canonicalObject(baseBytes, "base-manifest.json");
        Object finalObject = canonicalObject(finalBytes, "final-manifest.json");
        Object operationsObject = canonicalObject(operationsBytes, "operations.json");
        Map<String, ManifestRecord> base = manifest(baseObject, "base manifest", "mojang-source");
        Map<String, ManifestRecord> finalManifest = manifest(finalObject, "final manifest", "game/src/main/java");
        Map<String, Object> operationsMap = jsonMap(operationsObject, "operations.json");
        requireKeys(operationsMap, Set.of("format", "operations", "counts"), "operations.json");
        requireString(operationsMap, "format", FORMAT);
        Map<String, Long> operationCounts = counts(operationsMap.get("counts"), "operations counts");
        if (!metadataCounts.equals(operationCounts)) {
            throw new PatchError("bundle and operation counts differ");
        }
        Object operationListValue = operationsMap.get("operations");
        if (!(operationListValue instanceof List<?> operationList)) {
            throw new PatchError("operations.json operations must be an array");
        }
        List<Operation> operations = new ArrayList<>();
        Map<String, String> operationCollisions = new HashMap<>();
        Set<String> expectedPayloads = new HashSet<>();
        String previousPath = null;
        long[] seenCounts = new long[3];
        for (int i = 0; i < operationList.size(); i++) {
            Map<String, Object> object = jsonMap(operationList.get(i), "operation " + (i + 1));
            Operation operation = operation(object, i + 1, files, base, finalManifest,
                    operationCollisions, expectedPayloads);
            if (previousPath != null && operation.path.compareTo(previousPath) <= 0) {
                throw new PatchError("operation paths are not strictly sorted");
            }
            previousPath = operation.path;
            seenCounts[indexOf(operation.kind)]++;
            operations.add(operation);
        }
        if (seenCounts[0] != metadataCounts.get("add") || seenCounts[1] != metadataCounts.get("modify")
                || seenCounts[2] != metadataCounts.get("delete")) {
            throw new PatchError("operation counts do not match operations");
        }
        Set<String> actualPayloads = new HashSet<>();
        for (String path : files.keySet()) {
            if (path.startsWith("payload/")) {
                actualPayloads.add(path);
            }
        }
        if (!actualPayloads.equals(expectedPayloads)) {
            throw new PatchError("source bundle payload set mismatch");
        }
        Set<String> roots = new HashSet<>(files.keySet());
        roots.removeAll(actualPayloads);
        if (!roots.equals(BUNDLE_FILES)) {
            throw new PatchError("source bundle contains unexpected root files: " + roots);
        }
        Map<String, Long> expectedCounts = manifestDeltaCounts(base, finalManifest);
        if (!expectedCounts.equals(metadataCounts)) {
            throw new PatchError("operation counts do not match the complete manifest delta");
        }
        if (baseFileCount != base.size() || finalFileCount != finalManifest.size()) {
            throw new PatchError("bundle manifest file counts are inconsistent");
        }
        if (!sha256(Json.canonical(baseObject)).equals(metadata.get("base_manifest_sha256"))) {
            throw new PatchError("bundle base manifest digest mismatch");
        }
        if (!sha256(Json.canonical(finalObject)).equals(metadata.get("final_manifest_sha256"))) {
            throw new PatchError("bundle final manifest digest mismatch");
        }
        if (!sha256(operationsBytes).equals(metadata.get("operations_sha256"))) {
            throw new PatchError("bundle operations digest mismatch");
        }
        return new Bundle(files, base, finalManifest, finalObject, operations, metadataCounts);
    }

    private static Operation operation(Map<String, Object> object, int index, Map<String, byte[]> files,
            Map<String, ManifestRecord> base, Map<String, ManifestRecord> finalManifest,
            Map<String, String> collisions, Set<String> expectedPayloads) {
        Set<String> operationKeys = Set.of("index", "op", "path", "mode", "pre_sha256", "post_sha256",
                "pre_size", "post_size", "payload", "payload_kind", "payload_sha256", "payload_size");
        rejectUnknownKeys(object, operationKeys, "operation " + index);
        requirePresent(object, Set.of("index", "op", "path", "mode", "pre_sha256", "post_sha256",
                "pre_size", "post_size"), "operation " + index);
        if (integer(object.get("index"), "operation index") != index) {
            throw new PatchError("operation indexes are not contiguous at " + index);
        }
        String kind = string(object.get("op"), "operation type");
        if (!Set.of("add", "modify", "delete").contains(kind)) {
            throw new PatchError("unsupported operation type: " + kind);
        }
        String path = normalizeRel(string(object.get("path"), "operation path"), "operation path");
        if (!path.equals(object.get("path"))) {
            throw new PatchError("operation path is not canonical: " + path);
        }
        registerPrefixes(collisions, path, "operation");
        String mode = string(object.get("mode"), "operation mode");
        if (!"100644".equals(mode)) {
            throw new PatchError("operation must use regular-file mode 100644: " + path);
        }
        String pre = optionalHash(object.get("pre_sha256"), "operation preimage");
        String post = optionalHash(object.get("post_sha256"), "operation postimage");
        Long preSize = optionalInteger(object.get("pre_size"), "operation preimage size");
        Long postSize = optionalInteger(object.get("post_size"), "operation postimage size");
        if ((pre == null) != (preSize == null) || (post == null) != (postSize == null)) {
            throw new PatchError("operation image hash/size nullness differs: " + path);
        }
        if ("add".equals(kind) && (pre != null || preSize != null)) {
            throw new PatchError("add operation has a preimage: " + path);
        }
        if ("delete".equals(kind) && (post != null || postSize != null)) {
            throw new PatchError("delete operation has a postimage: " + path);
        }
        if ("add".equals(kind) && (pathIn(base, path) || !pathIn(finalManifest, path)
                || !post.equals(finalManifest.get(path).sha256) || postSize != finalManifest.get(path).size)) {
            throw new PatchError("add operation does not match manifests: " + path);
        }
        if ("modify".equals(kind) && (!pathIn(base, path) || !pathIn(finalManifest, path)
                || !pre.equals(base.get(path).sha256) || !post.equals(finalManifest.get(path).sha256)
                || preSize != base.get(path).size || postSize != finalManifest.get(path).size)) {
            throw new PatchError("modify operation does not match manifests: " + path);
        }
        if ("delete".equals(kind) && (!pathIn(base, path) || pathIn(finalManifest, path)
                || !pre.equals(base.get(path).sha256) || preSize != base.get(path).size)) {
            throw new PatchError("delete operation does not match manifests: " + path);
        }
        byte[] payload = null;
        String payloadPath = null;
        String payloadKind = null;
        if (!"delete".equals(kind)) {
            payloadPath = normalizeRel(string(object.get("payload"), "operation payload"), "operation payload");
            if (!payloadPath.startsWith("payload/")) {
                throw new PatchError("operation payload is outside payload/: " + payloadPath);
            }
            if (!payloadPath.equals(object.get("payload"))) {
                throw new PatchError("operation payload is not canonical: " + payloadPath);
            }
            expectedPayloads.add(payloadPath);
            payloadKind = string(object.get("payload_kind"), "operation payload kind");
            String expectedKind = "add".equals(kind) ? "full-v1" : "byte-spans-v1";
            if (!expectedKind.equals(payloadKind)) {
                throw new PatchError("operation payload kind mismatch: " + path);
            }
            payload = files.get(payloadPath);
            if (payload == null) {
                throw new PatchError("operation payload is missing: " + payloadPath);
            }
            String payloadSha = requireHash(object.get("payload_sha256"), "operation payload SHA-256");
            long payloadSize = integer(object.get("payload_size"), "operation payload size");
            if (payloadSize != payload.length || !payloadSha.equals(sha256(payload))) {
                throw new PatchError("operation payload hash/size mismatch: " + path);
            }
            if ("modify".equals(kind)) {
                Object deltaObject = canonicalObject(payload, "operation delta " + index);
                if (!(deltaObject instanceof Map<?, ?>)) {
                    throw new PatchError("operation delta is not an object: " + path);
                }
                Delta delta = delta(deltaObject, "operation delta " + index);
                if (delta.preSize != preSize || delta.postSize != postSize) {
                    throw new PatchError("operation delta size binding mismatch: " + path);
                }
            }
        } else if (object.get("payload") != null || object.get("payload_kind") != null
                || object.get("payload_sha256") != null || object.get("payload_size") != null) {
            throw new PatchError("delete operation contains payload fields: " + path);
        }
        return new Operation(index, kind, path, pre, post, preSize, postSize, payloadPath,
                payloadKind, payload);
    }

    private static void applyOperation(Operation operation, Map<String, byte[]> files, Path stage) throws IOException {
        Path target = safeJoin(stage, operation.path);
        if ("modify".equals(operation.kind) || "delete".equals(operation.kind)) {
            if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || !sha256(target).equals(operation.preSha256)) {
                throw new PatchError("preimage mismatch for " + operation.kind + ": " + operation.path);
            }
        }
        if ("delete".equals(operation.kind)) {
            Files.delete(target);
            return;
        }
        if ("add".equals(operation.kind) && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new PatchError("add operation target already exists: " + operation.path);
        }
        Path parent = target.getParent();
        Files.createDirectories(parent);
        rejectSymlinkAncestors(parent, "patch staging source");
        byte[] output;
        if ("modify".equals(operation.kind)) {
            output = applyDelta(Files.readAllBytes(target), files.get(operation.payloadPath),
                    "operation " + operation.index + " delta");
        } else {
            output = files.get(operation.payloadPath);
        }
        Path temporary = parent.resolve("." + target.getFileName() + ".patching-" + UUID.randomUUID());
        Files.write(temporary, output);
        try {
            if (!sha256(temporary).equals(operation.postSha256) || Files.size(temporary) != operation.postSize) {
                throw new PatchError("postimage mismatch after " + operation.kind + ": " + operation.path);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] applyDelta(byte[] before, byte[] payload, String label) {
        Object object = canonicalObject(payload, label);
        Delta delta = delta(object, label);
        if (before.length != delta.preSize) {
            throw new PatchError(label + " preimage size mismatch");
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream(delta.postSize);
        int cursor = 0;
        for (Hunk hunk : delta.hunks) {
            if (hunk.start < cursor || hunk.start + hunk.deleteSize > before.length) {
                throw new PatchError(label + " hunk overlaps or lies outside the preimage");
            }
            byte[] deleted = java.util.Arrays.copyOfRange(before, hunk.start, hunk.start + hunk.deleteSize);
            if (!sha256(deleted).equals(hunk.deleteSha256)) {
                throw new PatchError(label + " deleted-span hash mismatch");
            }
            result.writeBytes(java.util.Arrays.copyOfRange(before, cursor, hunk.start));
            result.writeBytes(hunk.insert);
            cursor = hunk.start + hunk.deleteSize;
        }
        result.writeBytes(java.util.Arrays.copyOfRange(before, cursor, before.length));
        byte[] bytes = result.toByteArray();
        if (bytes.length != delta.postSize) {
            throw new PatchError(label + " produced an unexpected postimage size");
        }
        return bytes;
    }

    private static Delta delta(Object object, String label) {
        Map<String, Object> map = jsonMap(object, label);
        requireKeys(map, Set.of("format", "codec", "pre_size", "post_size", "hunks"), label);
        requireString(map, "format", DELTA_FORMAT);
        requireString(map, "codec", "byte-spans-v1");
        long preSize = integer(map.get("pre_size"), label + " pre_size");
        long postSize = integer(map.get("post_size"), label + " post_size");
        if (preSize < 0 || postSize < 0 || preSize > Integer.MAX_VALUE || postSize > Integer.MAX_VALUE) {
            throw new PatchError(label + " has invalid sizes");
        }
        if (!(map.get("hunks") instanceof List<?> list)) {
            throw new PatchError(label + " hunks must be an array");
        }
        List<Hunk> hunks = new ArrayList<>();
        long previousStart = -1;
        long previousEnd = -1;
        long computedPost = preSize;
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> hunk = jsonMap(list.get(i), label + " hunk " + (i + 1));
            requireKeys(hunk, Set.of("start", "delete_size", "delete_sha256", "insert_b64", "insert_size"),
                    label + " hunk " + (i + 1));
            long start = integer(hunk.get("start"), label + " hunk start");
            long deleteSize = integer(hunk.get("delete_size"), label + " hunk delete_size");
            long insertSize = integer(hunk.get("insert_size"), label + " hunk insert_size");
            String deleteSha = requireHash(hunk.get("delete_sha256"), label + " hunk delete SHA-256");
            String insertBase64 = string(hunk.get("insert_b64"), label + " hunk insert_b64");
            if (start < 0 || deleteSize < 0 || insertSize < 0 || start + deleteSize > preSize
                    || start <= previousStart || start <= previousEnd || (deleteSize == 0 && insertSize == 0)) {
                throw new PatchError(label + " hunk has invalid or overlapping offsets");
            }
            byte[] insert;
            try {
                insert = java.util.Base64.getDecoder().decode(insertBase64);
            } catch (IllegalArgumentException ex) {
                throw new PatchError(label + " hunk has invalid base64");
            }
            if (insert.length != insertSize) {
                throw new PatchError(label + " hunk insert size does not match data");
            }
            computedPost = computedPost - deleteSize + insertSize;
            previousStart = start;
            previousEnd = start + deleteSize - 1;
            hunks.add(new Hunk((int) start, (int) deleteSize, deleteSha, insert));
        }
        if (computedPost != postSize) {
            throw new PatchError(label + " post_size does not match hunk sizes");
        }
        return new Delta((int) preSize, (int) postSize, hunks);
    }

    private static Map<String, ManifestRecord> manifest(Object value, String label, String expectedRoot) {
        Map<String, Object> object = jsonMap(value, label);
        requireKeys(object, Set.of("format", "root", "file_count", "records"), label);
        requireString(object, "format", MANIFEST_FORMAT);
        requireString(object, "root", expectedRoot);
        long fileCount = integer(object.get("file_count"), label + " file_count");
        if (!(object.get("records") instanceof List<?> list) || fileCount != list.size()) {
            throw new PatchError(label + " has an invalid records/file_count pair");
        }
        TreeMap<String, ManifestRecord> result = new TreeMap<>();
        Map<String, String> collisions = new HashMap<>();
        String previous = null;
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> record = jsonMap(list.get(i), label + " record");
            requireKeys(record, Set.of("path", "sha256", "size"), label + " record");
            String path = normalizeRel(string(record.get("path"), label + " path"), label + " path");
            if (!path.equals(record.get("path")) || (previous != null && path.compareTo(previous) <= 0)) {
                throw new PatchError(label + " records are not canonical and sorted");
            }
            previous = path;
            String sha = requireHash(record.get("sha256"), label + " record SHA-256");
            long size = integer(record.get("size"), label + " record size");
            if (size < 0 || result.put(path, new ManifestRecord(path, sha, size)) != null) {
                throw new PatchError("duplicate manifest path: " + path);
            }
            registerPrefixes(collisions, path, label);
        }
        return result;
    }

    private static Map<String, ManifestRecord> scanJavaTree(Path root) throws IOException {
        ensureSourceRoot(root);
        TreeMap<String, ManifestRecord> result = new TreeMap<>();
        Map<String, String> collisions = new HashMap<>();
        Files.walkFileTree(root, Set.of(), Integer.MAX_VALUE, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (Files.isSymbolicLink(dir)) {
                    throw new PatchError("symlink in Java source tree: " + dir);
                }
                if (!dir.equals(root)) {
                    registerPrefixes(collisions, root.relativize(dir).toString().replace('\\', '/'), "source");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (Files.isSymbolicLink(file)) {
                    throw new PatchError("symlink in Java source tree: " + file);
                }
                if (file.getFileName().toString().endsWith(".java")) {
                    String path = root.relativize(file).toString().replace('\\', '/');
                    registerPrefixes(collisions, path, "source");
                    result.put(path, new ManifestRecord(path, sha256(file), attrs.size()));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    private static void copyJavaTree(Path source, Path destination) throws IOException {
        Files.walkFileTree(source, Set.of(), Integer.MAX_VALUE, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file)) {
                    throw new PatchError("symlink in decompiled source tree: " + file);
                }
                if (!file.getFileName().toString().endsWith(".java")) {
                    return FileVisitResult.CONTINUE;
                }
                Path target = destination.resolve(source.relativize(file));
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void removeEmptyDirectories(Path root) throws IOException {
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) {
                    throw error;
                }
                if (!dir.equals(root)) {
                    try (var children = Files.list(dir)) {
                        if (!children.findAny().isPresent()) {
                            Files.delete(dir);
                        }
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
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
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) {
                    throw error;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void ensureSourceRoot(Path root) {
        rejectSymlinkAncestors(root, "source root");
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new PatchError("source root is not a real directory: " + root);
        }
    }

    private static void ensureAbsent(Path path) {
        rejectSymlinkAncestors(path, "output");
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new PatchError("patch output must be absent: " + path);
        }
    }

    private static Path safeJoin(Path root, String rel) {
        Path target = root.resolve(rel.replace('/', java.io.File.separatorChar)).normalize();
        try {
            target.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(root.toRealPath(LinkOption.NOFOLLOW_LINKS));
        } catch (IOException ignored) {
            // The target may not exist yet. The lexical check below is mandatory.
        }
        if (!target.startsWith(root.normalize())) {
            throw new PatchError("path escapes patch staging root: " + rel);
        }
        rejectSymlinkAncestors(target, "patch staging path");
        return target;
    }

    private static void rejectSymlinkAncestors(Path path, String label) {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        if (current == null) {
            current = Path.of("");
        }
        for (Path component : absolute) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new PatchError(label + " contains a symlink: " + current);
            }
        }
    }

    private static void ensureRegular(Path path, String label) {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new PatchError(label + " is not a regular file: " + path);
        }
    }

    private static Object canonicalObject(byte[] bytes, String label) {
        Object value = Json.parse(bytes, label);
        if (!java.util.Arrays.equals(bytes, Json.canonical(value))) {
            throw new PatchError(label + " is not canonical JSON");
        }
        return value;
    }

    private static Map<String, Object> jsonObject(byte[] bytes, String label) {
        return jsonMap(canonicalObject(bytes, label), label);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonMap(Object object, String label) {
        if (!(object instanceof Map<?, ?> map)) {
            throw new PatchError(label + " must be a JSON object");
        }
        return (Map<String, Object>) map;
    }

    private static void requireKeys(Map<String, Object> object, Set<String> expected, String label) {
        if (!object.keySet().equals(expected)) {
            throw new PatchError(label + " has unexpected or missing keys");
        }
    }

    private static void rejectUnknownKeys(Map<String, Object> object, Set<String> allowed, String label) {
        if (!allowed.containsAll(object.keySet())) {
            throw new PatchError(label + " has unknown keys");
        }
    }

    private static void requirePresent(Map<String, Object> object, Set<String> required, String label) {
        if (!object.keySet().containsAll(required)) {
            throw new PatchError(label + " is missing required keys");
        }
    }

    private static String requireString(Map<String, Object> object, String key, String expected) {
        String actual = string(object.get(key), key);
        if (!expected.equals(actual)) {
            throw new PatchError(key + " has unexpected value: " + actual);
        }
        return actual;
    }

    private static String string(Object value, String label) {
        if (!(value instanceof String string)) {
            throw new PatchError(label + " must be a string");
        }
        return string;
    }

    private static long integer(Object value, String label) {
        if (!(value instanceof Long number)) {
            throw new PatchError(label + " must be an integer");
        }
        return number;
    }

    private static Long optionalInteger(Object value, String label) {
        return value == null ? null : integer(value, label);
    }

    private static String requireHash(Object value, String label) {
        String hash = string(value, label);
        if (!hash.matches("[0-9a-f]{64}")) {
            throw new PatchError(label + " is not a SHA-256");
        }
        return hash;
    }

    private static String optionalHash(Object value, String label) {
        return value == null ? null : requireHash(value, label);
    }

    private static Map<String, Long> counts(Object value, String label) {
        Map<String, Object> object = jsonMap(value, label);
        if (!object.keySet().equals(Set.of("add", "modify", "delete"))) {
            throw new PatchError(label + " must contain exactly add/modify/delete");
        }
        Map<String, Long> result = new TreeMap<>();
        for (String key : List.of("add", "modify", "delete")) {
            long count = integer(object.get(key), label + " " + key);
            if (count < 0) {
                throw new PatchError(label + " has a negative count");
            }
            result.put(key, count);
        }
        return result;
    }

    private static Map<String, Long> manifestDeltaCounts(Map<String, ManifestRecord> base,
            Map<String, ManifestRecord> finalManifest) {
        long add = finalManifest.keySet().stream().filter(path -> !base.containsKey(path)).count();
        long delete = base.keySet().stream().filter(path -> !finalManifest.containsKey(path)).count();
        long modify = base.keySet().stream().filter(path -> finalManifest.containsKey(path)
                && !base.get(path).equals(finalManifest.get(path))).count();
        return Map.of("add", add, "modify", modify, "delete", delete);
    }

    private static boolean pathIn(Map<String, ?> map, String path) {
        return map.containsKey(path);
    }

    private static int indexOf(String kind) {
        return switch (kind) {
            case "add" -> 0;
            case "modify" -> 1;
            case "delete" -> 2;
            default -> throw new AssertionError(kind);
        };
    }

    private static String normalizeRel(String raw, String label) {
        if (raw == null || raw.isEmpty() || raw.indexOf('\0') >= 0) {
            throw new PatchError(label + " must be a non-empty path");
        }
        String candidate = raw.replace('\\', '/');
        if (candidate.startsWith("/") || candidate.startsWith("//") || candidate.matches("^[A-Za-z]:/.*")) {
            throw new PatchError(label + " is absolute: " + raw);
        }
        List<String> parts = new ArrayList<>();
        for (String component : candidate.split("/", -1)) {
            if (component.isEmpty() || ".".equals(component)) {
                continue;
            }
            if ("..".equals(component)) {
                throw new PatchError(label + " contains traversal: " + raw);
            }
            parts.add(component);
        }
        if (parts.isEmpty()) {
            throw new PatchError(label + " normalizes to an empty path: " + raw);
        }
        return String.join("/", parts);
    }

    private static void registerPrefixes(Map<String, String> seen, String path, String label) {
        String[] components = path.split("/");
        for (int end = 1; end <= components.length; end++) {
            String prefix = String.join("/", java.util.Arrays.copyOf(components, end));
            String key = Normalizer.normalize(prefix, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
            String previous = seen.putIfAbsent(key, prefix);
            if (previous != null && !previous.equals(prefix)) {
                throw new PatchError(label + " paths collide after normalization: " + previous + " vs " + prefix);
            }
        }
    }

    private static String manifestDigest(Object object) {
        return sha256(Json.canonical(object));
    }

    private static Path absolute(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static String sha256(Path path) {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            MessageDigest digest = digest();
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
            return hex(digest.digest());
        } catch (IOException ex) {
            throw new PatchError("cannot hash " + path + ": " + ex.getMessage());
        }
    }

    private static String sha256(byte[] bytes) {
        MessageDigest digest = digest();
        digest.update(bytes);
        return hex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return out.toString();
    }

    record Result(String bundleSha256, int finalFileCount, String finalManifestSha256,
            Map<String, Long> counts) {
    }

    static final class PatchError extends RuntimeException {
        PatchError(String message) {
            super(message);
        }
    }

    private record ManifestRecord(String path, String sha256, long size) {
    }

    private record Operation(int index, String kind, String path, String preSha256, String postSha256,
            Long preSize, Long postSize, String payloadPath, String payloadKind, byte[] payload) {
    }

    private record Hunk(int start, int deleteSize, String deleteSha256, byte[] insert) {
    }

    private record Delta(int preSize, int postSize, List<Hunk> hunks) {
    }

    private record Bundle(Map<String, byte[]> files, Map<String, ManifestRecord> base,
            Map<String, ManifestRecord> finalManifest, Object finalObject, List<Operation> operations,
            Map<String, Long> counts) {
    }
}
