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
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.eaglercraft.patcher.PatchEngine.PatchError;

/** Applies the pinned resource overlay for local testing. */
final class ResourceOverlay {
    static final String NORMAL_ARCHIVE_SHA256 =
            "2ba7e3376891c64f8bf57f3687e05b8dbe1971a75475b6825449e5e5f96d71f3";
    static final String EXPECTED_ARCHIVE_SHA256 = NORMAL_ARCHIVE_SHA256;
    private static final String JAR_SHA256 =
            "40896ee9f1e2bec3c934daac7e93d41e9e3d9c2f8ae0ca366d52ffbfd1afa290";
    private static final String FORMAT = "eaglercraft-26.2-resource-overlay-v1";
    private static final String MANIFEST_FORMAT = "eaglercraft-26.2-resource-manifest-v1";
    private static final String OPERATIONS_FORMAT = "eaglercraft-26.2-resource-operations-v1";
    private static final String PROVENANCE_FORMAT = "eaglercraft-26.2-resource-provenance-v1";
    private static final String DELTA_MAGIC = "EGRD1\0";
    private static final long BASE_FILE_COUNT = 19_497;
    private static final long BASE_TOTAL_BYTES = 14_915_354;
    private static final String BASE_TREE_SHA256 = "c340d58c3759c0a2596c423441ce29cdc40c27fab3cb833641d3d6b03fd9b04a";
    private static final ResourceProfile NORMAL_PROFILE = new ResourceProfile(
            NORMAL_ARCHIVE_SHA256, 19_515, 17_581_948,
            "ced3f0610dbfb18f8ecebfee5501df0da0036b8ad64ba7beb93f592aa9a1a8df", 64, 131, 85);
    private static final long MAX_MEMBER_SIZE = 64L * 1024L * 1024L;
    private static final long MAX_TOTAL_SIZE = 256L * 1024L * 1024L;
    private static final int MAX_MEMBERS = 10_000;
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> BUNDLE_KEYS = Set.of("format", "tool_version", "release_status",
            "official_jar_sha256", "resource_selection", "base_file_count", "base_total_bytes",
            "base_tree_sha256", "final_file_count", "final_total_bytes", "final_tree_sha256", "counts",
            "base_manifest_sha256", "final_manifest_sha256", "operations_sha256", "provenance_sha256");
    private static final Set<String> MANIFEST_KEYS = Set.of("format", "root", "file_count", "total_bytes", "tree_sha256", "records");
    private static final Set<String> OPERATIONS_KEYS = Set.of("format", "counts", "operations");
    private static final Set<String> OP_KEYS = Set.of("index", "op", "path", "pre_size", "pre_sha256", "post_size", "post_sha256", "payload", "payload_kind", "payload_size", "payload_sha256", "external");
    private static final Set<String> PROVENANCE_KEYS = Set.of("format", "status", "entries");
    private static final Set<String> PROVENANCE_ENTRY_KEYS = Set.of("path", "kind", "status", "license", "note");
    private static final Map<String, ExternalFile> EXTERNAL_FILES = Map.of(
            "assets/minecraft/font/eagler_server_symbols.hex", new ExternalFile(668, "29d147c67366f6ccc9ea1efbc2a63b72c3c886769c34ec057fb17d2bcea21f8e"),
            "assets/minecraft/font/eagler_server_symbols.zip", new ExternalFile(415, "15a5363bc8762eff093f99e7a81a7575c81477145684a3da5b82468ab6d43989"),
            "assets/minecraft/font/unifont.zip", new ExternalFile(1559654, "aea3e9918b0d31de6f94623080f04c31c8c16a5b1a2e8d99ab39f1acdddd30f7"),
            "assets/minecraft/font/unifont_pua.zip", new ExternalFile(100360, "65388145333f6ceffe2be67790183a77d45b97236248ac1eab3befe6a17979d7"),
            "assets/minecraft/lang/zh_cn.json", new ExternalFile(534023, "47d66d5b25a5ff1c40a4a6a179b44b165517af1863617ecac5cd03e751f49ff2"),
            "assets/minecraft/sounds.json", new ExternalFile(626160, "84fe52cea79f67441ac00df61836e15d5c9ae98c406cebba557213b80f59c3b5"));

    private ResourceOverlay() {
    }

    static void assertAcceptedArchive(Path archive, String expectedSha256) throws IOException {
        Path path = archive.toAbsolutePath().normalize();
        profileFor(expectedSha256);
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new PatchError("resource overlay is not a regular file: " + path);
        }
        String actual = sha256(path);
        if (!actual.equals(expectedSha256)) {
            throw new PatchError("resource overlay SHA-256 mismatch: expected " + expectedSha256 + ", got " + actual);
        }
    }

    static Result apply(Path archive, Path jar, Path output, String expectedSha256, Path externalRoot) throws IOException {
        Path overlay = archive.toAbsolutePath().normalize();
        Path clientJar = jar.toAbsolutePath().normalize();
        Path destination = output.toAbsolutePath().normalize();
        assertAcceptedArchive(overlay, expectedSha256);
        ResourceProfile profile = profileFor(expectedSha256);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new PatchError("resource destination must be absent: " + destination);
        }
        if (externalRoot == null) throw new PatchError("resource overlay requires --external-resource-root");
        Path external = externalRoot.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(external) || !Files.isDirectory(external, LinkOption.NOFOLLOW_LINKS)) {
            throw new PatchError("external resource root is not a real directory: " + external);
        }
        rejectSymlinkAncestors(external, "external resource root");
        TreeMap<String, byte[]> members = readArchive(overlay);
        Map<String, Object> bundle = object(Json.parse(members.get("bundle.json"), "bundle.json"), "bundle.json");
        validateBundle(bundle, members, profile);
        TreeMap<String, byte[]> base = scanJar(clientJar);
        ManifestData baseManifest = manifest(members.get("base-manifest.json"), "official-jar-resources");
        ManifestData finalManifest = manifest(members.get("final-manifest.json"), "project-resource-tree");
        if (!baseManifest.matches(base)) throw new PatchError("resource base manifest does not match official JAR");
        List<Operation> operations = operations(members.get("operations.json"), baseManifest, finalManifest, members, profile);
        validateProvenance(members.get("provenance.json"), operations, profile);
        TreeMap<String, byte[]> rebuilt = new TreeMap<>(base);
        for (Operation operation : operations) applyOperation(operation, rebuilt, members, external);
        if (!finalManifest.matches(rebuilt)) throw new PatchError("reconstructed resource tree does not match final manifest");

        Path parent = destination.getParent();
        if (parent == null) throw new PatchError("resource destination must have a parent");
        rejectSymlinkAncestors(parent, "resource destination parent");
        Files.createDirectories(parent);
        boolean complete = false;
        try {
            for (Map.Entry<String, byte[]> entry : rebuilt.entrySet()) {
                Path target = safeJoin(destination, entry.getKey());
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
                    throw new PatchError("resource extraction collision: " + entry.getKey());
                }
                Files.createDirectories(target.getParent());
                rejectSymlinkAncestors(target.getParent(), "resource extraction");
                Files.write(target, entry.getValue());
            }
            complete = true;
        } finally {
            if (!complete) deleteTree(destination);
        }
        return new Result(baseManifest.count, baseManifest.bytes, baseManifest.treeSha, finalManifest.count,
                finalManifest.bytes, finalManifest.treeSha, operations.size(),
                operations.stream().filter(operation -> "add".equals(operation.kind)).count(),
                operations.stream().filter(operation -> operation.external != null).count());
    }

    private static ResourceProfile profileFor(String expectedSha256) {
        if (NORMAL_ARCHIVE_SHA256.equals(expectedSha256)) return NORMAL_PROFILE;
        throw new PatchError("resource overlay expected SHA-256 is not the accepted Normal identity");
    }

    private static void validateBundle(Map<String, Object> bundle, TreeMap<String, byte[]> members,
            ResourceProfile profile) {
        requireKeys(bundle, BUNDLE_KEYS, "bundle.json");
        requireString(bundle, "format", FORMAT);
        requireString(bundle, "tool_version", "1");
        requireString(bundle, "release_status", "local-test-only");
        requireString(bundle, "official_jar_sha256", JAR_SHA256);
        requireString(bundle, "resource_selection", "non-directory non-class non-META-INF ZIP entries");
        Map<String, Object> counts = object(bundle.get("counts"), "bundle counts");
        requireKeys(counts, Set.of("add", "modify", "delete"), "bundle counts");
        if (number(counts.get("add"), "add") != profile.addCount || number(counts.get("modify"), "modify") != 21
                || number(counts.get("delete"), "delete") != 46) throw new PatchError("resource overlay operation counts are not pinned");
        if (number(bundle.get("base_file_count"), "base_file_count") != BASE_FILE_COUNT
                || number(bundle.get("base_total_bytes"), "base_total_bytes") != BASE_TOTAL_BYTES
                || !BASE_TREE_SHA256.equals(string(bundle.get("base_tree_sha256"), "base_tree_sha256"))
                || number(bundle.get("final_file_count"), "final_file_count") != profile.finalFileCount
                || number(bundle.get("final_total_bytes"), "final_total_bytes") != profile.finalTotalBytes
                || !profile.finalTreeSha256.equals(string(bundle.get("final_tree_sha256"), "final_tree_sha256"))) {
            throw new PatchError("resource overlay tree summary is not pinned");
        }
        for (String metadata : List.of("base-manifest.json", "final-manifest.json", "operations.json", "provenance.json")) {
            String field = switch (metadata) {
                case "base-manifest.json" -> "base_manifest_sha256";
                case "final-manifest.json" -> "final_manifest_sha256";
                case "operations.json" -> "operations_sha256";
                default -> "provenance_sha256";
            };
            if (!sha256(members.get(metadata)).equals(string(bundle.get(field), field))) {
                throw new PatchError("resource overlay metadata digest mismatch: " + metadata);
            }
        }
    }

    private static TreeMap<String, byte[]> readArchive(Path archive) throws IOException {
        TreeMap<String, byte[]> members = new TreeMap<>();
        long total = 0L;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            if (zip.size() > MAX_MEMBERS) throw new PatchError("resource overlay has too many members");
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String path = normalize(entry.getName(), "bundle member");
                if (entry.isDirectory() || entry.getName().endsWith("/") || entry.getMethod() != ZipEntry.STORED
                        || entry.getExtra() != null || entry.getComment() != null) {
                    throw new PatchError("resource overlay member is not deterministic stored data: " + path);
                }
                long size = entry.getSize();
                long compressed = entry.getCompressedSize();
                if (size < 0 || size > MAX_MEMBER_SIZE || compressed != size) throw new PatchError("invalid resource overlay member size: " + path);
                total = Math.addExact(total, size);
                if (total > MAX_TOTAL_SIZE) throw new PatchError("resource overlay is too large");
                if (members.put(path, readEntry(zip, entry)) != null) throw new PatchError("duplicate resource overlay member: " + path);
            }
        } catch (ArithmeticException ex) {
            throw new PatchError("resource overlay size overflow");
        }
        checkPathSet(members.keySet(), "bundle member");
        Set<String> required = Set.of("bundle.json", "base-manifest.json", "final-manifest.json", "operations.json", "provenance.json");
        if (!members.keySet().containsAll(required)) throw new PatchError("resource overlay metadata is incomplete");
        for (String path : members.keySet()) {
            if (!required.contains(path) && !path.startsWith("payload/")) throw new PatchError("unknown resource overlay member: " + path);
        }
        return members;
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream input = new BufferedInputStream(zip.getInputStream(entry))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) entry.getSize());
            byte[] buffer = new byte[1024 * 1024];
            int count;
            long total = 0L;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) continue;
                total += count;
                if (total > entry.getSize() || total > MAX_MEMBER_SIZE) throw new PatchError("resource overlay member expanded beyond declaration");
                output.write(buffer, 0, count);
            }
            if (total != entry.getSize()) throw new PatchError("resource overlay member size changed");
            return output.toByteArray();
        }
    }

    private static TreeMap<String, byte[]> scanJar(Path jar) throws IOException {
        if (!JAR_SHA256.equals(sha256(jar))) throw new PatchError("resource overlay official JAR identity mismatch");
        TreeMap<String, byte[]> resources = new TreeMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            if (zip.size() > 500_000) throw new PatchError("official JAR has too many entries");
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || name.endsWith("/") || name.endsWith(".class") || name.startsWith("META-INF/")) continue;
                String path = normalize(name, "JAR resource path");
                if (entry.getSize() < 0 || entry.getSize() > MAX_MEMBER_SIZE) throw new PatchError("oversized JAR resource: " + path);
                if (resources.put(path, readEntry(zip, entry)) != null) throw new PatchError("duplicate JAR resource: " + path);
            }
        }
        checkPathSet(resources.keySet(), "JAR resource path");
        return resources;
    }

    private static ManifestData manifest(byte[] bytes, String expectedRoot) {
        Map<String, Object> object = object(Json.parse(bytes, expectedRoot), expectedRoot);
        requireKeys(object, Set.of("format", "root", "file_count", "total_bytes", "tree_sha256", "records"), expectedRoot);
        requireString(object, "format", MANIFEST_FORMAT);
        requireString(object, "root", expectedRoot);
        long count = number(object.get("file_count"), "manifest file_count");
        long bytesTotal = number(object.get("total_bytes"), "manifest total_bytes");
        String tree = string(object.get("tree_sha256"), "manifest tree_sha256");
        if (!HEX.matcher(tree).matches() || !(object.get("records") instanceof List<?> raw) || raw.size() != count) throw new PatchError("invalid resource manifest summary");
        TreeMap<String, Record> records = new TreeMap<>();
        String previous = null;
        long sum = 0L;
        for (Object value : raw) {
            Map<String, Object> record = object(value, "manifest record");
            requireKeys(record, Set.of("path", "size", "sha256"), "manifest record");
            String path = normalize(string(record.get("path"), "manifest path"), "manifest path");
            String hash = string(record.get("sha256"), "manifest sha256");
            long size = number(record.get("size"), "manifest size");
            if (!HEX.matcher(hash).matches() || size < 0 || size > MAX_MEMBER_SIZE || !path.equals(record.get("path"))
                    || (previous != null && path.compareTo(previous) <= 0) || records.put(path, new Record(size, hash)) != null) {
                throw new PatchError("non-canonical resource manifest record");
            }
            previous = path;
            sum = Math.addExact(sum, size);
        }
        if (sum != bytesTotal || !tree.equals(treeDigestRecords(records))) throw new PatchError("resource manifest digest/summary mismatch");
        return new ManifestData(records, count, bytesTotal, tree);
    }

    private static List<Operation> operations(byte[] bytes, ManifestData base, ManifestData finalManifest,
            TreeMap<String, byte[]> members, ResourceProfile profile) {
        Map<String, Object> object = object(Json.parse(bytes, "operations.json"), "operations.json");
        requireKeys(object, OPERATIONS_KEYS, "operations.json");
        requireString(object, "format", OPERATIONS_FORMAT);
        Map<String, Object> counts = object(object.get("counts"), "operation counts");
        requireKeys(counts, Set.of("add", "modify", "delete"), "operation counts");
        if (!(object.get("operations") instanceof List<?> raw) || raw.size() != profile.operationCount) throw new PatchError("resource operation count mismatch");
        List<Operation> result = new ArrayList<>();
        Set<String> changed = new HashSet<>();
        Set<String> payloads = new HashSet<>();
        String previous = null;
        for (int index = 0; index < raw.size(); index++) {
            Map<String, Object> op = object(raw.get(index), "resource operation");
            requireKeys(op, OP_KEYS, "resource operation");
            if (number(op.get("index"), "operation index") != index + 1) throw new PatchError("resource operation index mismatch");
            String kind = string(op.get("op"), "operation kind");
            String path = normalize(string(op.get("path"), "operation path"), "operation path");
            if (previous != null && path.compareTo(previous) <= 0 || !changed.add(path)) throw new PatchError("resource operations are not path-sorted or unique");
            previous = path;
            Record pre = base.records.get(path), post = finalManifest.records.get(path);
            if ("add".equals(kind) && (pre != null || post == null)) throw new PatchError("invalid resource add set");
            if ("modify".equals(kind) && (pre == null || post == null)) throw new PatchError("invalid resource modify set");
            if ("delete".equals(kind) && (pre == null || post != null)) throw new PatchError("invalid resource delete set");
            if (!Set.of("add", "modify", "delete").contains(kind)) throw new PatchError("unknown resource operation");
            checkNullableRecord(op, "add".equals(kind) ? null : pre, "add".equals(kind) || "delete".equals(kind) ? null : post, kind);
            String payload = op.get("payload") == null ? null : normalize(string(op.get("payload"), "operation payload"), "operation payload");
            ExternalFile external = null;
            if (op.get("external") != null) {
                if (!(op.get("external") instanceof Map<?, ?>)) throw new PatchError("invalid external resource record");
                Map<String, Object> ext = object(op.get("external"), "external resource record");
                requireKeys(ext, Set.of("required", "size", "sha256"), "external resource record");
                if (!"add".equals(kind) || payload != null || !Boolean.TRUE.equals(ext.get("required")) || !EXTERNAL_FILES.containsKey(path)) throw new PatchError("invalid external resource operation");
                external = EXTERNAL_FILES.get(path);
                if (number(ext.get("size"), "external size") != external.size || !external.sha256.equals(string(ext.get("sha256"), "external sha256"))) throw new PatchError("external resource identity mismatch");
                if (op.get("payload_kind") != null || op.get("payload_size") != null || op.get("payload_sha256") != null) throw new PatchError("external add must have null embedded payload identity");
            } else if ("add".equals(kind) && payload == null || "modify".equals(kind) && payload == null) {
                throw new PatchError("resource operation payload is missing");
            }
            if (payload != null) {
                if (!payload.startsWith("payload/") || !members.containsKey(payload)) throw new PatchError("resource operation payload is invalid");
                if (!payloads.add(payload)) throw new PatchError("resource operation payload is referenced more than once");
                if (number(op.get("payload_size"), "payload size") != members.get(payload).length || !sha256(members.get(payload)).equals(string(op.get("payload_sha256"), "payload sha256"))) throw new PatchError("resource payload identity mismatch");
            }
            if ("modify".equals(kind) && !"egrd1-v1".equals(string(op.get("payload_kind"), "payload kind"))) throw new PatchError("modify payload kind mismatch");
            if ("add".equals(kind) && payload != null && !"full-file-v1".equals(string(op.get("payload_kind"), "payload kind"))) throw new PatchError("add payload kind mismatch");
            if ("delete".equals(kind) && (payload != null || op.get("external") != null || op.get("post_size") != null || op.get("post_sha256") != null || op.get("payload_kind") != null || op.get("payload_size") != null || op.get("payload_sha256") != null)) throw new PatchError("delete operation has postimage data");
            if ("add".equals(kind) && (op.get("pre_size") != null || op.get("pre_sha256") != null)) throw new PatchError("add operation has preimage data");
            if (!"add".equals(kind) && pre != null && (number(op.get("pre_size"), "pre size") != pre.size || !pre.sha256.equals(string(op.get("pre_sha256"), "pre sha256")))) throw new PatchError("resource preimage metadata mismatch");
            if (!"delete".equals(kind) && post != null && (number(op.get("post_size"), "post size") != post.size || !post.sha256.equals(string(op.get("post_sha256"), "post sha256")))) throw new PatchError("resource postimage metadata mismatch");
            result.add(new Operation(kind, path, pre, post, payload, external));
        }
        long adds = result.stream().filter(op -> "add".equals(op.kind)).count();
        long modifies = result.stream().filter(op -> "modify".equals(op.kind)).count();
        long deletes = result.stream().filter(op -> "delete".equals(op.kind)).count();
        Set<String> expectedChanged = new HashSet<>(base.records.keySet());
        expectedChanged.addAll(finalManifest.records.keySet());
        expectedChanged.removeIf(path -> {
            Record before = base.records.get(path), after = finalManifest.records.get(path);
            return before != null && after != null && before.size == after.size && before.sha256.equals(after.sha256);
        });
        if (adds != profile.addCount || modifies != 21 || deletes != 46 || number(counts.get("add"), "add") != adds || number(counts.get("modify"), "modify") != modifies || number(counts.get("delete"), "delete") != deletes || !changed.equals(expectedChanged)) throw new PatchError("resource operation sets/counts mismatch");
        Set<String> expectedPayloads = new HashSet<>();
        for (String path : members.keySet()) if (path.startsWith("payload/")) expectedPayloads.add(path);
        if (!expectedPayloads.equals(payloads)) throw new PatchError("resource payload set is not exactly referenced");
        return result;
    }

    private static void checkNullableRecord(Map<String, Object> op, Record pre, Record post, String kind) {
        if (!"delete".equals(kind) && op.get("post_size") == null && post != null) throw new PatchError("resource postimage missing");
    }

    private static void validateProvenance(byte[] bytes, List<Operation> operations, ResourceProfile profile) {
        Map<String, Object> object = object(Json.parse(bytes, "provenance.json"), "provenance.json");
        requireKeys(object, PROVENANCE_KEYS, "provenance.json");
        requireString(object, "format", PROVENANCE_FORMAT);
        requireString(object, "status", "local-test-only");
        if (!(object.get("entries") instanceof List<?> raw) || raw.size() != profile.provenanceCount) throw new PatchError("resource provenance coverage mismatch");
        Map<String, Operation> payloadOps = new HashMap<>();
        for (Operation op : operations) if (!"delete".equals(op.kind)) payloadOps.put(op.path, op);
        String previous = null;
        for (Object value : raw) {
            Map<String, Object> entry = object(value, "provenance entry");
            requireKeys(entry, PROVENANCE_ENTRY_KEYS, "provenance entry");
            String path = normalize(string(entry.get("path"), "provenance path"), "provenance path");
            if (previous != null && path.compareTo(previous) <= 0 || !payloadOps.containsKey(path)) throw new PatchError("resource provenance is not sorted or does not cover payload operation");
            previous = path;
            if (!"unresolved".equals(entry.get("status")) || !"unresolved".equals(entry.get("license")) || !(entry.get("note") instanceof String)) throw new PatchError("resource provenance entry is not unresolved local-test metadata");
            Operation op = payloadOps.get(path);
            String expected = "add".equals(op.kind) ? (op.external == null ? "project-overlay" : "external-required") : "project-overlay-modification";
            if (!expected.equals(entry.get("kind"))) throw new PatchError("resource provenance kind mismatch");
        }
    }

    private static void applyOperation(Operation operation, TreeMap<String, byte[]> tree, TreeMap<String, byte[]> members, Path externalRoot) throws IOException {
        if ("delete".equals(operation.kind)) {
            byte[] before = tree.get(operation.path);
            if (before == null || operation.pre == null || before.length != operation.pre.size || !sha256(before).equals(operation.pre.sha256)) throw new PatchError("resource delete preimage mismatch: " + operation.path);
            tree.remove(operation.path);
        } else if ("modify".equals(operation.kind)) {
            byte[] before = tree.get(operation.path);
            if (before == null || operation.pre == null || before.length != operation.pre.size || !sha256(before).equals(operation.pre.sha256)) throw new PatchError("resource modify preimage mismatch: " + operation.path);
            tree.put(operation.path, applyDelta(members.get(operation.payload), before, operation.post.size, operation.post.sha256));
        } else {
            byte[] data;
            if (operation.external != null) {
                Path source = safeJoin(externalRoot, operation.path);
                if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw new PatchError("external resource is not a regular file: " + operation.path);
                data = Files.readAllBytes(source);
            } else data = members.get(operation.payload);
            if (data == null || operation.post == null || data.length != operation.post.size || !sha256(data).equals(operation.post.sha256)) throw new PatchError("resource add hash mismatch: " + operation.path);
            if (tree.put(operation.path, data) != null) throw new PatchError("resource add collision: " + operation.path);
        }
    }

    private static byte[] applyDelta(byte[] delta, byte[] before, long expectedSize, String expectedHash) {
        int[] position = {0};
        byte[] magic = DELTA_MAGIC.getBytes(StandardCharsets.US_ASCII);
        if (delta.length < magic.length || !java.util.Arrays.equals(java.util.Arrays.copyOf(delta, magic.length), magic)) throw new PatchError("resource delta magic mismatch");
        position[0] = magic.length;
        long preSize = uleb(delta, position), postSize = uleb(delta, position), count = uleb(delta, position);
        if (preSize != before.length || postSize != expectedSize || count > 1_000_000) throw new PatchError("resource delta header mismatch");
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) expectedSize);
        int previousKind = -1;
        long previousCopyEnd = -1;
        boolean copied = false;
        for (long index = 0; index < count; index++) {
            if (position[0] >= delta.length) throw new PatchError("truncated resource delta");
            int kind = delta[position[0]++];
            if (kind == 0) {
                long offset = uleb(delta, position), length = uleb(delta, position);
                if (length == 0 || offset > before.length - length || previousKind == 0 && previousCopyEnd == offset) throw new PatchError("non-canonical resource COPY");
                output.write(before, (int) offset, (int) length); previousCopyEnd = offset + length; copied = true;
            } else if (kind == 1) {
                long length = uleb(delta, position);
                if (length == 0 || length > delta.length - position[0] || previousKind == 1) throw new PatchError("non-canonical resource INSERT");
                output.write(delta, position[0], (int) length); position[0] += (int) length; previousCopyEnd = -1;
            } else throw new PatchError("unknown resource delta instruction");
            if (output.size() > expectedSize) throw new PatchError("resource delta output overflow");
            previousKind = kind;
        }
        if (position[0] != delta.length || output.size() != expectedSize || !copied || !expectedHash.equals(sha256(output.toByteArray()))) throw new PatchError("resource delta output mismatch");
        return output.toByteArray();
    }

    private static long uleb(byte[] bytes, int[] position) {
        int start = position[0]; long value = 0; int shift = 0;
        for (int count = 0; position[0] < bytes.length && count < 10; count++) {
            int current = bytes[position[0]++] & 0xff;
            if (shift == 63 && (current & 0x7e) != 0) throw new PatchError("resource delta integer overflow");
            value |= (long) (current & 0x7f) << shift;
            if ((current & 0x80) == 0) {
                if (position[0] - start != encodedLength(value)) throw new PatchError("non-canonical resource LEB128");
                return value;
            }
            shift += 7;
        }
        throw new PatchError("truncated resource LEB128");
    }

    private static int encodedLength(long value) {
        int length = 1;
        while ((value >>>= 7) != 0) length++;
        return length;
    }

    private static String treeDigest(Map<String, byte[]> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("eaglercraft-26.2-resource-manifest-v1\n".getBytes(StandardCharsets.UTF_8));
            for (String path : entries.keySet()) {
                byte[] data = entries.get(path);
                digest.update(path.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
                digest.update(Long.toString(data.length).getBytes(StandardCharsets.US_ASCII)); digest.update((byte) 0);
                digest.update(sha256(data).getBytes(StandardCharsets.US_ASCII)); digest.update((byte) '\n');
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
    }

    private static String treeDigestRecords(Map<String, Record> records) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("eaglercraft-26.2-resource-manifest-v1\n".getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, Record> entry : records.entrySet()) {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
                digest.update(Long.toString(entry.getValue().size).getBytes(StandardCharsets.US_ASCII)); digest.update((byte) 0);
                digest.update(entry.getValue().sha256.getBytes(StandardCharsets.US_ASCII)); digest.update((byte) '\n');
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
    }

    private static String normalize(String raw, String label) {
        if (raw == null || raw.isEmpty() || raw.indexOf('\0') >= 0 || raw.indexOf('\\') >= 0 || raw.startsWith("/") || raw.matches("^[A-Za-z]:.*") || raw.indexOf(':') >= 0) throw new PatchError("unsafe " + label + ": " + raw);
        String[] parts = raw.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part) || part.endsWith(".") || part.endsWith(" ")) throw new PatchError("unsafe " + label + ": " + raw);
            String upper = part.toUpperCase(java.util.Locale.ROOT).split("\\.", 2)[0];
            if (Set.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9").contains(upper)) throw new PatchError("reserved " + label + ": " + raw);
        }
        if (raw.length() > 240 || parts.length > 32) throw new PatchError("oversized " + label + ": " + raw);
        return raw;
    }

    private static void checkPathSet(Set<String> paths, String label) {
        Map<String, String> keys = new HashMap<>();
        Set<String> exact = new HashSet<>(paths);
        for (String path : paths) {
            String key = java.text.Normalizer.normalize(path, java.text.Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT);
            if (keys.put(key, path) != null) throw new PatchError(label + " Unicode/case collision: " + path);
            String[] parts = path.split("/");
            for (int i = 1; i < parts.length; i++) if (exact.contains(String.join("/", java.util.Arrays.copyOf(parts, i)))) throw new PatchError(label + " file/directory collision: " + path);
        }
    }

    private static Path safeJoin(Path root, String relative) {
        Path target = root.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(root.normalize())) throw new PatchError("resource path escapes root: " + relative);
        rejectSymlinkAncestors(target, "resource path");
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

    private static Map<String, Object> object(Object value, String label) {
        if (!(value instanceof Map<?, ?> raw)) throw new PatchError(label + " must be an object");
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new PatchError(label + " has a non-string key");
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static void requireKeys(Map<String, Object> object, Set<String> expected, String label) {
        if (!object.keySet().equals(expected)) throw new PatchError(label + " has unexpected or missing keys");
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

    private static long number(Object value, String label) {
        if (!(value instanceof Long number)) throw new PatchError(label + " must be an integer");
        return number;
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) { return digest(input); }
    }

    private static String digest(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024]; int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
    }

    private static String sha256(byte[] bytes) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.deleteIfExists(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException { if (exc != null) throw exc; Files.deleteIfExists(dir); return FileVisitResult.CONTINUE; }
        });
    }

    record Result(long baseFileCount, long baseBytes, String baseTreeSha256, long finalFileCount, long finalBytes,
            String finalTreeSha256, int operationCount, long addCount, long externalCount) {
    }

    private record ResourceProfile(String archiveSha256, long finalFileCount, long finalTotalBytes,
            String finalTreeSha256, long addCount, int operationCount, int provenanceCount) {
    }

    private record ExternalFile(long size, String sha256) {
    }

    private record Record(long size, String sha256) {
    }

    private record ManifestData(TreeMap<String, Record> records, long count, long bytes, String treeSha) {
        boolean matches(Map<String, byte[]> actual) {
            if (actual.size() != count || actual.values().stream().mapToLong(value -> value.length).sum() != bytes || !treeDigest(actual).equals(treeSha)) return false;
            for (Map.Entry<String, byte[]> entry : actual.entrySet()) {
                Record record = records.get(entry.getKey());
                if (record == null || record.size != entry.getValue().length || !record.sha256.equals(sha256(entry.getValue()))) return false;
            }
            return true;
        }
    }

    private record Operation(String kind, String path, Record pre, Record post, String payload, ExternalFile external) {
    }
}
