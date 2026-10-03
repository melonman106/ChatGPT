package com.eaglercraft.patcher;

import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/** First-run wrapper for the Eaglercraft release package. Requires Java 17+. */
public final class SingleFileLauncher {
    private static final String FOLDER = "eaglercraft-26.2-patcher";
    private static final String ZIP = "/portable-kit.zip";
    private static final String DIGEST = "/portable-kit.sha256";
    private static final int MAX_ARCHIVE_BYTES = 250 * 1024 * 1024;
    private static final long MAX_UNPACKED_BYTES = 1024L * 1024 * 1024;

    private SingleFileLauncher() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "--install-dir".equals(args[0])) {
            Path installed = installEmbedded(Path.of(args[1]));
            System.out.println("Verified release package installation: " + installed);
            return;
        }
        if (args.length != 0) {
            System.err.println("Usage: java -jar patcher.jar [--install-dir NEW_OR_VERIFIED_DIRECTORY]");
            System.exit(2);
        }
        if (GraphicsEnvironment.isHeadless()) {
            throw new IOException("A desktop is required. For extraction only, use --install-dir NEW_DIRECTORY.");
        }
        SwingUtilities.invokeLater(SingleFileLauncher::showSetup);
    }

    private static void showSetup() {
        JFrame window = new JFrame("Eaglercraft 26.2 Patcher Setup");
        window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel panel = new JPanel(new BorderLayout(12, 12));
        JLabel status = new JLabel("Choose a parent folder for " + FOLDER + ".");
        JButton choose = new JButton("Choose setup folder...");
        panel.add(status, BorderLayout.CENTER);
        panel.add(choose, BorderLayout.SOUTH);
        window.setContentPane(panel);
        window.setSize(500, 135);
        window.setLocationRelativeTo(null);
        choose.addActionListener(event -> {
            JFileChooser picker = new JFileChooser();
            picker.setDialogTitle("Choose where to install " + FOLDER);
            picker.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (picker.showOpenDialog(window) != JFileChooser.APPROVE_OPTION) return;
            Path destination = picker.getSelectedFile().toPath().resolve(FOLDER);
            choose.setEnabled(false);
            status.setText("Installing or verifying existing kit...");
            new SwingWorker<Path, Void>() {
                @Override protected Path doInBackground() throws Exception {
                    Path installed = installEmbedded(destination);
                    SwingUtilities.invokeLater(() -> status.setText("Installing Java and Node tools..."));
                    bootstrap(installed);
                    return installed;
                }
                @Override protected void done() {
                    try {
                        Path installed = get();
                        launchGui(installed);
                        window.dispose();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        fail(exception);
                    } catch (ExecutionException | IOException exception) {
                        fail(exception.getCause() == null ? exception : exception.getCause());
                    }
                }
                private void fail(Throwable cause) {
                    choose.setEnabled(true);
                    status.setText("Setup stopped. Retry this folder or choose another.");
                    JOptionPane.showMessageDialog(window, cause.toString(), "Setup error", JOptionPane.ERROR_MESSAGE);
                }
            }.execute();
        });
        window.setVisible(true);
    }

    private static Path installEmbedded(Path destination) throws IOException {
        try (InputStream zip = resource(ZIP); InputStream digest = resource(DIGEST)) {
            String expected = new String(digest.readAllBytes(), StandardCharsets.US_ASCII).trim();
            if (!expected.matches("[0-9a-f]{64}")) throw new IOException("Invalid embedded kit checksum");
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                return verifyExisting(zip, destination, expected);
            }
            return extract(zip, destination, expected);
        }
    }

    private static InputStream resource(String name) throws IOException {
        InputStream stream = SingleFileLauncher.class.getResourceAsStream(name);
        if (stream == null) throw new IOException("Embedded resource missing: " + name);
        return stream;
    }

    /** Package-private for focused malicious ZIP tests. Destination must not exist. */
    static Path extract(InputStream zip, Path destination, String expectedZipHash) throws IOException {
        Path target = destination.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(parent) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Destination must be a new directory under an existing real folder: " + target);
        }
        Path staging = Files.createTempDirectory(parent, ".eagler-patcher-setup-");
        boolean promoted = false;
        try {
            MessageDigest zipDigest = sha256();
            byte[] archive = zip.readNBytes(MAX_ARCHIVE_BYTES + 1);
            if (archive.length > MAX_ARCHIVE_BYTES || zip.read() != -1) {
                throw new IOException("Embedded kit exceeds the archive size limit");
            }
            zipDigest.update(archive);
            if (!hex(zipDigest.digest()).equals(expectedZipHash)) throw new IOException("Embedded kit SHA-256 mismatch");
            Set<String> names = new HashSet<>();
            Map<String, String> actual = new HashMap<>();
            long unpacked = 0;
            try (ZipInputStream entries = new ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
                ZipEntry entry;
                while ((entry = entries.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (name.isEmpty() || name.startsWith("/") || name.startsWith("\\") || name.indexOf('\\') >= 0
                            || name.indexOf('\0') >= 0 || name.matches("(?i)^[a-z]:.*")) {
                        throw new IOException("Unsafe ZIP entry: " + name);
                    }
                    String normalizedName = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
                    Path relative = Path.of(normalizedName).normalize();
                    if (relative.isAbsolute() || relative.startsWith("..") || relative.toString().equals(".")
                            || !relative.toString().replace('\\', '/').equals(normalizedName)) {
                        throw new IOException("Unsafe ZIP entry: " + name);
                    }
                    if (!names.add(normalizedName)) throw new IOException("Duplicate ZIP entry: " + name);
                    if (names.size() > 100_000) throw new IOException("Kit contains too many ZIP entries");
                    Path output = staging.resolve(relative);
                    if (!output.normalize().startsWith(staging)) throw new IOException("ZIP path escaped destination");
                    if (entry.isDirectory()) {
                        Files.createDirectories(output);
                    } else {
                        Files.createDirectories(output.getParent());
                        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IOException("ZIP entry collision: " + name);
                        MessageDigest fileDigest = sha256();
                        try (var out = Files.newOutputStream(output)) {
                            byte[] buffer = new byte[65536];
                            int read;
                            while ((read = entries.read(buffer)) != -1) {
                                unpacked += read;
                                if (unpacked > MAX_UNPACKED_BYTES) throw new IOException("Kit exceeds the extraction size limit");
                                out.write(buffer, 0, read);
                                fileDigest.update(buffer, 0, read);
                            }
                        }
                        actual.put(normalizedName, hex(fileDigest.digest()));
                    }
                    entries.closeEntry();
                }
            }
            verifyManifest(staging, actual);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Destination appeared during setup");
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("Atomic installation is unavailable for this folder", exception);
            }
            promoted = true;
            setScriptPermissions(target);
            return target;
        } finally {
            if (!promoted) deleteTree(staging);
        }
    }

    /** Resume only a complete, byte-for-byte matching kit; bootstrap-created state may remain. */
    static Path verifyExisting(InputStream zip, Path destination, String expectedZipHash) throws IOException {
        Path target = destination.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(parent) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(target)) {
            throw new IOException("Existing destination is not a real installation directory: " + target);
        }
        Path reference = parent.resolve(".eagler-patcher-verify-" + UUID.randomUUID());
        try {
            extract(zip, reference, expectedZipHash);
            Set<Path> expected = new HashSet<>();
            try (var files = Files.walk(reference)) {
                for (Path source : files.toList()) {
                    Path relative = reference.relativize(source);
                    expected.add(relative);
                    Path installed = target.resolve(relative);
                    if (Files.isSymbolicLink(installed)
                            || (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                                ? !Files.isDirectory(installed, LinkOption.NOFOLLOW_LINKS)
                                : !Files.isRegularFile(installed, LinkOption.NOFOLLOW_LINKS)
                                    || Files.mismatch(source, installed) != -1)) {
                        throw new IOException("Existing kit differs from the packaged version: " + installed);
                    }
                }
            }
            try (var installed = Files.walk(target)) {
                for (Path entry : installed.toList()) {
                    Path relative = target.relativize(entry);
                    if (expected.contains(relative)) continue;
                    String name = relative.toString().replace('\\', '/');
                    boolean toolData = name.equals(".toolchain") || name.startsWith(".toolchain/");
                    boolean logOrManifest = name.equals("toolchain.properties") || name.equals("setup-tools.log")
                            || name.equals("gui.log");
                    if (!(toolData || logOrManifest) || Files.isSymbolicLink(entry)
                            && (name.equals(".toolchain") || logOrManifest)
                            || toolData && name.equals(".toolchain")
                                && !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
                            || logOrManifest && !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Unrecognized or unsafe file in existing installation: " + entry);
                    }
                }
            }
            return target;
        } finally {
            deleteTree(reference);
        }
    }

    private static void verifyManifest(Path staging, Map<String, String> actual) throws IOException {
        Path manifest = staging.resolve("SHA256SUMS");
        if (!Files.isRegularFile(manifest)) throw new IOException("Kit file manifest missing");
        Map<String, String> expected = new HashMap<>();
        try (BufferedReader lines = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.length() < 67 || !line.substring(0, 64).matches("[0-9a-f]{64}")
                        || !line.startsWith("  ", 64)) throw new IOException("Invalid kit file manifest");
                String name = line.substring(66);
                if (expected.putIfAbsent(name, line.substring(0, 64)) != null) throw new IOException("Duplicate manifest entry");
            }
        }
        actual.remove("SHA256SUMS");
        if (!actual.equals(expected)) throw new IOException("Kit file manifest mismatch");
        if (!actual.containsKey("eaglercraft-26.2-u1-patcher-gui.jar")
                || !actual.containsKey("eaglercraft-26.2-java-cli.jar")) throw new IOException("Kit GUI is missing");
    }

    private static void setScriptPermissions(Path root) {
        for (String name : Arrays.asList("eagler-patcher", "eagler-patcher-gui",
                "bootstrap/bootstrap-linux-x86_64.sh", "bootstrap/launch-gui-linux-x86_64.sh",
                "bootstrap/macos/bootstrap-macos.sh", "bootstrap/macos/launch-gui-macos.sh",
                "bootstrap/macos/launch-gui-macos.command", "local-media-verify.sh")) {
            Path script = root.resolve(name);
            if (Files.isRegularFile(script, LinkOption.NOFOLLOW_LINKS)) script.toFile().setExecutable(true, true);
        }
    }

    static void bootstrap(Path root) throws IOException, InterruptedException {
        String os = System.getProperty("os.name", "").toLowerCase();
        ProcessBuilder command;
        if (os.contains("win")) {
            command = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                    root.resolve("bootstrap/windows/bootstrap-windows-x86_64.ps1").toString(), "-AppDir", root.toString());
        } else if (os.contains("mac")) {
            command = new ProcessBuilder("/bin/sh", root.resolve("bootstrap/macos/bootstrap-macos.sh").toString(),
                    "--app-dir", root.toString());
        } else if (os.contains("linux")) {
            command = new ProcessBuilder("/bin/sh", root.resolve("bootstrap/bootstrap-linux-x86_64.sh").toString(),
                    "--app-dir", root.toString());
        } else throw new IOException("Unsupported OS: " + os);
        Path log = root.resolve("setup-tools.log");
        command.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        Process process = command.start();
        int result = process.waitFor();
        if (result != 0) throw new IOException("Tool setup exited " + result + ". See " + log);
    }

    private static void launchGui(Path root) throws IOException {
        java.util.Properties properties = new java.util.Properties();
        try (InputStream input = Files.newInputStream(root.resolve("toolchain.properties"))) { properties.load(input); }
        String java17 = properties.getProperty("java17");
        if (java17 == null || !Files.isRegularFile(Path.of(java17))) throw new IOException("Installed Java 17 is missing");
        new ProcessBuilder(java17, "-jar", root.resolve("eaglercraft-26.2-u1-patcher-gui.jar").toString())
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(root.resolve("gui.log").toFile()).start();
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new AssertionError(exception); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) Files.delete(path);
        }
    }
}
