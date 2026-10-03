package com.eaglercraft.patcher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Rebuilds the six pinned external overlay resources from official indexed objects. */
final class OfficialResources {
    private static final String INDEX_URL = "https://piston-meta.mojang.com/v1/packages/981aab8147520cdc1f0d4a84f46c161929021fee/32.json";
    private static final String INDEX_SHA1 = "981aab8147520cdc1f0d4a84f46c161929021fee";
    private static final String ASSET_URL = "https://resources.download.minecraft.net/";
    private static final String DELTA_FORMAT = "eagler-26.2-zh-cn-delta-v1";
    private static final String ZH_UPSTREAM_SHA256 = "5389c7aa92675c87480e4cbac48f1e3590550c954bb09a379a74007c614f1094";
    private static final String ZH_FINAL_SHA256 = "47d66d5b25a5ff1c40a4a6a179b44b165517af1863617ecac5cd03e751f49ff2";
    private static final String SYMBOL_HEX_SHA256 = "29d147c67366f6ccc9ea1efbc2a63b72c3c886769c34ec057fb17d2bcea21f8e";
    private static final String SYMBOL_ZIP_SHA256 = "15a5363bc8762eff093f99e7a81a7575c81477145684a3da5b82468ab6d43989";
    private static final List<String> SYMBOL_CODES = List.of("25AA", "26C3", "2708", "270C", "2716", "2719", "2726", "2733", "27A5", "27E1");
    private static final List<Asset> ASSETS = List.of(
            new Asset("minecraft/font/unifont.zip", "ccd5ac4767ce0a9c71d1dd62f2dc25449789b5dd", 1559654,
                    "aea3e9918b0d31de6f94623080f04c31c8c16a5b1a2e8d99ab39f1acdddd30f7"),
            new Asset("minecraft/font/unifont_pua.zip", "d7caa0e3aa5eb656c51817ae4bfbf3c4d72cdaad", 100360,
                    "65388145333f6ceffe2be67790183a77d45b97236248ac1eab3befe6a17979d7"),
            new Asset("minecraft/lang/zh_cn.json", "0d1c720356c912eba263f7898728e4cf109121c5", 532215,
                    ZH_UPSTREAM_SHA256),
            new Asset("minecraft/sounds.json", "9ac006d5537ed0fa4a7bcd1eccfc505155847686", 626160,
                    "84fe52cea79f67441ac00df61836e15d5c9ae98c406cebba557213b80f59c3b5"));

    private OfficialResources() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2 && args.length != 4) {
            System.err.println("Usage: OfficialResources NEW_OUTPUT_DIR zh-cn-delta.json [CACHED_INDEX CACHED_OBJECTS]");
            System.exit(2);
        }
        stage(Path.of(args[0]), Path.of(args[1]), args.length == 4 ? Path.of(args[2]) : null,
                args.length == 4 ? Path.of(args[3]) : null);
        System.out.println("Staged six verified external resources in " + args[0]);
    }

    static void verifyExisting(Path root) throws IOException {
        for (Asset asset : ASSETS) {
            String expected = asset.name.equals("minecraft/lang/zh_cn.json")
                    ? ZH_FINAL_SHA256 : asset.sha256;
            byte[] data = readFile(root.resolve("assets").resolve(asset.name),
                    asset.name.equals("minecraft/lang/zh_cn.json") ? 534023 : asset.size);
            checkHash(data, "SHA-256", expected, asset.name);
        }
        checkHash(readFile(root.resolve("assets/minecraft/font/eagler_server_symbols.hex"), 668),
                "SHA-256", SYMBOL_HEX_SHA256, "symbol glyphs");
        checkHash(readFile(root.resolve("assets/minecraft/font/eagler_server_symbols.zip"), 415),
                "SHA-256", SYMBOL_ZIP_SHA256, "symbol ZIP");
    }

    static void stage(Path destination, Path deltaPath, Path cachedIndex, Path cachedObjects) throws Exception {
        Path target = destination.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("resource output must be new and its parent must exist");
        }
        rejectSymlinks(parent);
        byte[] delta = readFile(deltaPath, 1_000_000);
        byte[] index = cachedIndex == null ? download(INDEX_URL, 2_000_000) : readFile(cachedIndex, 2_000_000);
        checkHash(index, "SHA-1", INDEX_SHA1, "official index");
        Map<?, ?> objects = map(map(Json.parse(index, "official index")).get("objects"));
        Path temporary = Files.createTempDirectory(parent, ".official-resources-");
        boolean promoted = false;
        try {
            byte[] unifont = null;
            for (Asset asset : ASSETS) {
                Map<?, ?> entry = map(objects.get(asset.name));
                if (!asset.sha1.equals(entry.get("hash")) || !Long.valueOf(asset.size).equals(entry.get("size"))) {
                    throw new IOException("official index entry changed: " + asset.name);
                }
                byte[] data = cachedObjects == null
                        ? download(ASSET_URL + asset.sha1.substring(0, 2) + "/" + asset.sha1, asset.size)
                        : readFile(cachedObjects.resolve(asset.sha1.substring(0, 2)).resolve(asset.sha1), asset.size);
                if (data.length != asset.size) throw new IOException("asset size mismatch: " + asset.name);
                checkHash(data, "SHA-1", asset.sha1, asset.name);
                checkHash(data, "SHA-256", asset.sha256, asset.name);
                if (asset.name.equals("minecraft/font/unifont.zip")) unifont = data;
                if (asset.name.equals("minecraft/lang/zh_cn.json")) data = rebuildChinese(data, delta);
                write(temporary, asset.name, data);
            }
            if (unifont == null) throw new IOException("Unifont asset missing");
            byte[] symbols = symbolsFrom(unifont);
            write(temporary, "minecraft/font/eagler_server_symbols.hex", symbols);
            write(temporary, "minecraft/font/eagler_server_symbols.zip", zipSymbols(symbols));
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target);
            }
            promoted = true;
        } finally {
            if (!promoted) deleteTree(temporary);
        }
    }

    private static byte[] rebuildChinese(byte[] upstream, byte[] deltaBytes) throws IOException {
        Map<?, ?> patch = map(Json.parse(deltaBytes, "Chinese catalog delta"));
        if (!DELTA_FORMAT.equals(patch.get("format")) || !ZH_UPSTREAM_SHA256.equals(patch.get("upstream_sha256"))
                || !ZH_FINAL_SHA256.equals(patch.get("final_sha256"))) {
            throw new IOException("Chinese catalog delta identity mismatch");
        }
        Map<?, ?> source = map(Json.parse(upstream, "official Chinese catalog"));
        Map<?, ?> overrides = map(patch.get("overrides"));
        Object orderValue = patch.get("order");
        if (!(orderValue instanceof List<?> order) || order.size() != source.size() + countNew(source, overrides)) {
            throw new IOException("Chinese catalog delta key count mismatch");
        }
        StringBuilder out = new StringBuilder("{\n");
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < order.size(); i++) {
            if (!(order.get(i) instanceof String key) || !seen.add(key)) throw new IOException("duplicate catalog key");
            Object value = overrides.containsKey(key) ? overrides.get(key) : source.get(key);
            if (!(value instanceof String)) throw new IOException("missing catalog value: " + key);
            out.append("  ").append(quoted(key)).append(": ").append(quoted((String) value));
            out.append(i == order.size() - 1 ? "\n" : ",\n");
        }
        if (!seen.containsAll(source.keySet()) || !seen.containsAll(overrides.keySet())) {
            throw new IOException("Chinese catalog keys missing");
        }
        out.append("}\n");
        byte[] data = out.toString().getBytes(StandardCharsets.UTF_8);
        checkHash(data, "SHA-256", ZH_FINAL_SHA256, "rebuilt Chinese catalog");
        return data;
    }

    private static int countNew(Map<?, ?> source, Map<?, ?> overrides) {
        int count = 0;
        for (Object key : overrides.keySet()) if (!source.containsKey(key)) count++;
        return count;
    }

    private static String quoted(String value) {
        return new String(Json.canonical(value), StandardCharsets.UTF_8).trim();
    }

    private static byte[] symbolsFrom(byte[] archiveBytes) throws IOException {
        byte[] source = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archiveBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("unifont_all_no_pua-17.0.01.hex")) {
                    source = zip.readNBytes(7_722_309);
                    break;
                }
            }
        }
        if (source == null || source.length != 7_722_308) throw new IOException("Unifont member missing or wrong size");
        List<String> lines = Arrays.asList(new String(source, StandardCharsets.US_ASCII).split("\n"));
        StringBuilder selected = new StringBuilder();
        for (String code : SYMBOL_CODES) {
            String prefix = code + ":";
            String line = lines.stream().filter(value -> value.startsWith(prefix)).findFirst()
                    .orElseThrow(() -> new IOException("Unifont glyph missing: " + code));
            selected.append(line).append('\n');
        }
        byte[] data = selected.toString().getBytes(StandardCharsets.US_ASCII);
        checkHash(data, "SHA-256", SYMBOL_HEX_SHA256, "symbol glyphs");
        return data;
    }

    private static byte[] zipSymbols(byte[] symbols) throws IOException {
        byte[] compressed = deflate(symbols);
        CRC32 crc = new CRC32();
        crc.update(symbols);
        byte[] name = "eagler_server_symbols.hex".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream(415);
        int dosTime = (22 << 11) | (19 << 5) | (36 / 2);
        int dosDate = ((2026 - 1980) << 9) | (8 << 5) | 20;
        little(out, 0x04034b50, 4); little(out, 20, 2); little(out, 0, 2); little(out, 8, 2);
        little(out, dosTime, 2); little(out, dosDate, 2); little(out, crc.getValue(), 4);
        little(out, compressed.length, 4); little(out, symbols.length, 4); little(out, name.length, 2);
        little(out, 0, 2); out.write(name); out.write(compressed);
        int central = out.size();
        little(out, 0x02014b50, 4); little(out, 0x031e, 2); little(out, 20, 2);
        little(out, 0, 2); little(out, 8, 2); little(out, dosTime, 2); little(out, dosDate, 2);
        little(out, crc.getValue(), 4); little(out, compressed.length, 4); little(out, symbols.length, 4);
        little(out, name.length, 2); little(out, 0, 2); little(out, 0, 2); little(out, 0, 2);
        little(out, 1, 2); little(out, (long) 0100664 << 16, 4); little(out, 0, 4); out.write(name);
        int centralSize = out.size() - central;
        little(out, 0x06054b50, 4); little(out, 0, 2); little(out, 0, 2);
        little(out, 1, 2); little(out, 1, 2); little(out, centralSize, 4); little(out, central, 4);
        little(out, 0, 2);
        byte[] data = out.toByteArray();
        checkHash(data, "SHA-256", SYMBOL_ZIP_SHA256, "symbol ZIP");
        return data;
    }

    private static byte[] deflate(byte[] data) {
        Deflater compressor = new Deflater(9, true);
        compressor.setInput(data);
        compressor.finish();
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        while (!compressor.finished()) {
            int count = compressor.deflate(buffer);
            result.write(buffer, 0, count);
        }
        compressor.end();
        return result.toByteArray();
    }

    private static void little(ByteArrayOutputStream out, long value, int bytes) {
        for (int i = 0; i < bytes; i++) out.write((int) (value >>> (8 * i)) & 255);
    }

    private static void write(Path root, String name, byte[] data) throws IOException {
        Path path = root.resolve("assets").resolve(name);
        Files.createDirectories(path.getParent());
        Files.write(path, data);
    }

    private static byte[] download(String url, int limit) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(30)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build();
        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) throw new IOException("asset HTTP " + response.statusCode());
        try (var input = response.body()) {
            byte[] data = input.readNBytes(limit + 1);
            if (data.length > limit) throw new IOException("asset response too large");
            return data;
        }
    }

    private static byte[] readFile(Path path, int limit) throws IOException {
        rejectSymlinks(path);
        try (var input = Files.newInputStream(path)) {
            byte[] data = input.readNBytes(limit + 1);
            if (data.length > limit) throw new IOException("input too large: " + path);
            return data;
        }
    }

    private static void rejectSymlinks(Path path) throws IOException {
        for (Path item = path.toAbsolutePath(); item != null; item = item.getParent()) {
            if (Files.isSymbolicLink(item)) throw new IOException("symlink in path: " + item);
        }
    }

    private static void checkHash(byte[] data, String algorithm, String expected, String label) throws IOException {
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
            if (!actual.equals(expected)) throw new IOException(label + " digest mismatch");
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IOException(error);
        }
    }

    private static Map<?, ?> map(Object value) throws IOException {
        if (value instanceof Map<?, ?> map) return map;
        throw new IOException("invalid asset JSON structure");
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) Files.deleteIfExists(path);
        }
    }

    private record Asset(String name, String sha1, int size, String sha256) {}
}
