package com.eaglercraft.patcher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SingleFileLauncherTest {
    private static byte[] zip(String name) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry(name));
            out.write("bad".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String sha(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder();
        for (byte value : digest) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    private static byte[] validKit() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("eaglercraft-26.2-u1-patcher-gui.jar", "test-gui".getBytes(StandardCharsets.UTF_8));
        files.put("eaglercraft-26.2-java-cli.jar", "test-cli".getBytes(StandardCharsets.UTF_8));
        files.put("bootstrap/bootstrap-linux-x86_64.sh",
                "#!/bin/sh\n[ -f \"$PWD/.toolchain/ready\" ] || exit 11\n".getBytes(StandardCharsets.UTF_8));
        StringBuilder manifest = new StringBuilder();
        for (var entry : files.entrySet()) manifest.append(sha(entry.getValue())).append("  ")
                .append(entry.getKey()).append('\n');
        files.put("SHA256SUMS", manifest.toString().getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (var entry : files.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        Path parent = Files.createTempDirectory("single-file-test-");
        for (String name : new String[] {"../escape", "/absolute", "C:/drive", "a\\escape", "a/../b"}) {
            byte[] bytes = zip(name);
            Path destination = parent.resolve("candidate");
            try {
                SingleFileLauncher.extract(new ByteArrayInputStream(bytes), destination, sha(bytes));
                throw new AssertionError("Accepted unsafe ZIP entry " + name);
            } catch (IOException expected) {
                if (Files.exists(destination)) throw new AssertionError("Partial destination after " + name);
            }
        }
        Path existing = Files.createDirectory(parent.resolve("existing"));
        Path sentinel = Files.writeString(existing.resolve("user-file.txt"), "preserve");
        byte[] bytes = zip("safe-file");
        try {
            SingleFileLauncher.extract(new ByteArrayInputStream(bytes), existing, sha(bytes));
            throw new AssertionError("Accepted existing destination");
        } catch (IOException expected) {
            if (!"preserve".equals(Files.readString(sentinel))) throw new AssertionError("Overwrote user file");
        }
        try {
            SingleFileLauncher.extract(new ByteArrayInputStream(bytes), parent.resolve("bad-hash"), "0".repeat(64));
            throw new AssertionError("Accepted corrupt ZIP checksum");
        } catch (IOException expected) {
            if (Files.exists(parent.resolve("bad-hash"))) throw new AssertionError("Partial destination after bad checksum");
        }
        byte[] kit = validKit();
        Path retry = SingleFileLauncher.extract(new ByteArrayInputStream(kit), parent.resolve("retry"), sha(kit));
        try {
            SingleFileLauncher.bootstrap(retry);
            throw new AssertionError("Expected first bootstrap failure");
        } catch (IOException expected) {
            if (!Files.isRegularFile(retry.resolve("setup-tools.log"))) throw new AssertionError("Missing failure log");
        }
        Files.createDirectory(retry.resolve(".toolchain"));
        Files.writeString(retry.resolve(".toolchain/ready"), "ready");
        if (!SingleFileLauncher.verifyExisting(new ByteArrayInputStream(kit), retry, sha(kit)).equals(retry)) {
            throw new AssertionError("Failed to resume verified installation");
        }
        SingleFileLauncher.bootstrap(retry);
        Files.writeString(retry.resolve("eaglercraft-26.2-java-cli.jar"), "tampered");
        try {
            SingleFileLauncher.verifyExisting(new ByteArrayInputStream(kit), retry, sha(kit));
            throw new AssertionError("Accepted tampered existing kit");
        } catch (IOException expected) {
            if (!"tampered".equals(Files.readString(retry.resolve("eaglercraft-26.2-java-cli.jar")))) {
                throw new AssertionError("Overwrote tampered file");
            }
        }
        try {
            SingleFileLauncher.verifyExisting(new ByteArrayInputStream(kit), existing, sha(kit));
            throw new AssertionError("Accepted unrelated user folder");
        } catch (IOException expected) {
            if (!"preserve".equals(Files.readString(sentinel))) throw new AssertionError("Overwrote user file");
        }
        System.out.println("SingleFileLauncher: unsafe ZIP paths, checksum cleanup, failed bootstrap retry, tamper refusal PASS");
    }
}
