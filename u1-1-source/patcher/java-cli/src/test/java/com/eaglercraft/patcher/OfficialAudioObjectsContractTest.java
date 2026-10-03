package com.eaglercraft.patcher;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class OfficialAudioObjectsContractTest {
    private OfficialAudioObjectsContractTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("official-audio-objects-test-");
        try {
            verifiesPinnedOfficialIndex();
            stagesDownloadAndVerifiedCacheReuse(root.resolve("successful-app"));
            stagesMixedCacheAndDownload(root.resolve("mixed-cache-app"));
            rejectsChangedCachedObject(root.resolve("invalid-object-app"));
            rejectsWrongDownloadedObject(root.resolve("wrong-download-app"));
            cancelsDuringObjectStream(root.resolve("cancelled-stream-app"));
            boundsSlowObjectStreamByDeadline(root.resolve("deadline-stream-app"));
            preservesThreadInterruptDuringCacheDigest(root.resolve("interrupted-cache-object"));
            rejectsTamperedIndexWithoutReplacingIt(root.resolve("invalid-index-app"));
            rejectsRedirect(root.resolve("redirect-app"));
            stagesConcurrentNoClobberRace(root.resolve("race-app"));
            rejectsSymlinkedObject(root.resolve("symlink-app"), root.resolve("outside-object"));
            System.out.println("official audio objects contract: PASS (pinned index 4871/4871; "
                    + "download, verified and mixed cache reuse, tamper, redirect, cancellation, deadline, "
                    + "concurrent no-clobber, symlink)");
        } finally {
            deleteTree(root);
        }
    }

    private static void verifiesPinnedOfficialIndex() throws Exception {
        Path fixture = Path.of("assets/indexes/32.json");
        require(Files.isRegularFile(fixture), "official index fixture missing: " + fixture);
        byte[] bytes = Files.readAllBytes(fixture);
        List<OfficialAudioObjects.Entry> entries = OfficialAudioObjects.parseIndex(bytes,
                OfficialAudioObjects.INDEX_SHA1, OfficialAudioObjects.EXPECTED_OGG_COUNT, 12 * 1024 * 1024);
        require(entries.size() == 4871, "pinned index did not contain 4871 OGGs");
        require(entries.get(0).name().compareTo(entries.get(entries.size() - 1).name()) < 0,
                "official index entries were not sorted by name");
        byte[] tampered = bytes.clone();
        tampered[0] ^= 1;
        expectIOException(() -> OfficialAudioObjects.parseIndex(tampered, OfficialAudioObjects.INDEX_SHA1,
                OfficialAudioObjects.EXPECTED_OGG_COUNT, 12 * 1024 * 1024), "SHA-1 mismatch");
    }

    private static void stagesDownloadAndVerifiedCacheReuse(Path app) throws Exception {
        Files.createDirectories(app);
        Fixture fixture = fixture("small verified OGG fixture\n".getBytes(StandardCharsets.US_ASCII));
        FakeTransport transport = new FakeTransport(fixture.index, fixture.object);
        List<String> progress = new ArrayList<>();
        OfficialAudioObjects.Result first = OfficialAudioObjects.stage(app, progress::add, transport,
                fixture.settings);
        require(first.total() == 1 && first.downloaded() == 1 && first.cached() == 0 && first.cachedRace() == 0,
                "first run counts were wrong: " + first);
        require(Files.readAllBytes(first.indexPath()).length == fixture.index.length, "saved index differs");
        require(java.util.Arrays.equals(Files.readAllBytes(OfficialAudioObjects.objectPath(first.objectsPath(),
                fixture.entry.sha1())), fixture.object), "downloaded object differs");
        require(transport.indexRequests.get() == 1 && transport.objectRequests.get() == 1,
                "initial run used unexpected network requests");
        require(progress.stream().anyMatch(value -> value.contains("1/1")), "progress did not report counts");

        FakeTransport mustStayOffline = new FakeTransport(fixture.index, fixture.object) {
            @Override
            public OfficialAudioObjects.Response get(URI uri, Duration timeout) throws IOException {
                throw new IOException("verified cache unexpectedly attempted a request: " + uri);
            }
        };
        OfficialAudioObjects.Result second = OfficialAudioObjects.stage(app, ignored -> { }, mustStayOffline,
                fixture.settings);
        require(second.total() == 1 && second.cached() == 1 && second.downloaded() == 0
                && second.cachedRace() == 0, "verified cache was not reused: " + second);
    }

    private static void rejectsChangedCachedObject(Path app) throws Exception {
        Files.createDirectories(app);
        Fixture fixture = fixture("good bytes".getBytes(StandardCharsets.US_ASCII));
        Path indexPath = app.resolve("inputs/audio-cache/indexes/32.json");
        Path objectPath = OfficialAudioObjects.objectPath(app.resolve("inputs/audio-cache/objects"),
                fixture.entry.sha1());
        Files.createDirectories(indexPath.getParent());
        Files.write(indexPath, fixture.index);
        Files.createDirectories(objectPath.getParent());
        byte[] invalid = "evil bytes".getBytes(StandardCharsets.US_ASCII);
        Files.write(objectPath, invalid);
        FakeTransport offline = new FakeTransport(fixture.index, fixture.object) {
            @Override
            public OfficialAudioObjects.Response get(URI uri, Duration timeout) throws IOException {
                throw new IOException("invalid cache should fail closed: " + uri);
            }
        };
        expectIOException(() -> OfficialAudioObjects.stage(app, ignored -> { }, offline, fixture.settings),
                "existing cache object is invalid");
        require(java.util.Arrays.equals(Files.readAllBytes(objectPath), invalid),
                "invalid existing object was overwritten");
    }

    private static void stagesMixedCacheAndDownload(Path app) throws Exception {
        Files.createDirectories(app);
        MultiFixture fixture = multiFixture("warm cached object".getBytes(StandardCharsets.US_ASCII),
                "cold downloaded object".getBytes(StandardCharsets.US_ASCII));
        Path cacheRoot = app.resolve("inputs/audio-cache");
        Path indexPath = cacheRoot.resolve("indexes/32.json");
        Files.createDirectories(indexPath.getParent());
        Files.write(indexPath, fixture.index);
        OfficialAudioObjects.Entry cachedEntry = fixture.entries.get(0);
        byte[] cachedBytes = fixture.objectBytes.get(cachedEntry.sha1());
        Path cachedObject = OfficialAudioObjects.objectPath(cacheRoot.resolve("objects"), cachedEntry.sha1());
        Files.createDirectories(cachedObject.getParent());
        Files.write(cachedObject, cachedBytes);

        AtomicInteger indexRequests = new AtomicInteger();
        AtomicInteger objectRequests = new AtomicInteger();
        OfficialAudioObjects.Transport transport = (uri, timeout) -> {
            if (uri.equals(OfficialAudioObjects.INDEX_URI)) {
                indexRequests.incrementAndGet();
                return response(200, uri, fixture.index);
            }
            objectRequests.incrementAndGet();
            for (OfficialAudioObjects.Entry entry : fixture.entries) {
                if (uri.getPath().endsWith("/" + entry.sha1())) {
                    return response(200, uri, fixture.objectBytes.get(entry.sha1()));
                }
            }
            throw new IOException("unexpected object URL: " + uri);
        };
        OfficialAudioObjects.Result result = OfficialAudioObjects.stage(app, ignored -> { }, transport,
                fixture.settings);
        require(result.total() == 2 && result.cached() == 1 && result.downloaded() == 1
                && result.cachedRace() == 0, "mixed cache counts were wrong: " + result);
        require(indexRequests.get() == 0 && objectRequests.get() == 1,
                "mixed cache run fetched unexpected inputs");
        for (OfficialAudioObjects.Entry entry : fixture.entries) {
            Path object = OfficialAudioObjects.objectPath(result.objectsPath(), entry.sha1());
            require(java.util.Arrays.equals(Files.readAllBytes(object), fixture.objectBytes.get(entry.sha1())),
                    "mixed cache object differs: " + entry.name());
        }
    }

    private static void rejectsWrongDownloadedObject(Path app) throws Exception {
        Files.createDirectories(app);
        byte[] expected = "aaaa same length".getBytes(StandardCharsets.US_ASCII);
        Fixture fixture = fixture(expected);
        byte[] wrong = "bbbb same length".getBytes(StandardCharsets.US_ASCII);
        require(wrong.length == expected.length, "test payloads must have equal size");
        FakeTransport corrupt = new FakeTransport(fixture.index, wrong);
        expectIOException(() -> OfficialAudioObjects.stage(app, ignored -> { }, corrupt, fixture.settings),
                "size/SHA-1 mismatch");
        Path object = OfficialAudioObjects.objectPath(app.resolve("inputs/audio-cache/objects"),
                fixture.entry.sha1());
        require(!Files.exists(object), "wrong object payload was promoted");
        try (var files = Files.list(object.getParent())) {
            require(files.findAny().isEmpty(), "failed object download left a temporary file");
        }
    }

    private static void cancelsDuringObjectStream(Path app) throws Exception {
        Files.createDirectories(app);
        Fixture fixture = fixture("cancel stream check".getBytes(StandardCharsets.US_ASCII));
        CountDownLatch streamEntered = new CountDownLatch(1);
        FakeTransport interruptedStream = new FakeTransport(fixture.index, fixture.object) {
            @Override
            public OfficialAudioObjects.Response get(URI uri, Duration timeout) throws IOException {
                if (uri.equals(OfficialAudioObjects.INDEX_URI)) {
                    return super.get(uri, timeout);
                }
                InputStream body = new ByteArrayInputStream(fixture.object) {
                    private boolean interrupted;

                    @Override
                    public synchronized int read(byte[] bytes, int offset, int length) {
                        int count = super.read(bytes, offset, Math.min(1, length));
                        if (!interrupted) {
                            interrupted = true;
                            streamEntered.countDown();
                            Thread.currentThread().interrupt();
                        }
                        return count;
                    }
                };
                return new OfficialAudioObjects.Response(200, uri, fixture.object.length, body);
            }
        };
        expectIOException(() -> OfficialAudioObjects.stage(app, ignored -> { }, interruptedStream,
                fixture.settings), "interrupted");
        require(streamEntered.getCount() == 0, "object stream did not reach its cancellation check");
        Path object = OfficialAudioObjects.objectPath(app.resolve("inputs/audio-cache/objects"),
                fixture.entry.sha1());
        require(!Files.exists(object), "interrupted object stream was promoted");
        try (var files = Files.list(object.getParent())) {
            require(files.findAny().isEmpty(), "interrupted object stream left a temporary file");
        }
    }

    private static void boundsSlowObjectStreamByDeadline(Path app) throws Exception {
        Files.createDirectories(app);
        Fixture fixture = fixture("deadline stream check".getBytes(StandardCharsets.US_ASCII));
        OfficialAudioObjects.Settings shortDeadline = new OfficialAudioObjects.Settings(fixture.settings.indexSha1(),
                1, fixture.settings.maxIndexBytes(), fixture.settings.maxObjectBytes(), 2, 8.0,
                Duration.ofMillis(300));
        CountDownLatch streamEntered = new CountDownLatch(1);
        FakeTransport slowStream = new FakeTransport(fixture.index, fixture.object) {
            @Override
            public OfficialAudioObjects.Response get(URI uri, Duration timeout) throws IOException {
                if (uri.equals(OfficialAudioObjects.INDEX_URI)) {
                    return super.get(uri, timeout);
                }
                InputStream body = new InputStream() {
                    @Override
                    public int read() throws IOException {
                        streamEntered.countDown();
                        try {
                            Thread.sleep(2_000);
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IOException("test stream interrupted by deadline cancellation", error);
                        }
                        return fixture.object[0] & 0xff;
                    }

                    @Override
                    public int read(byte[] bytes, int offset, int length) throws IOException {
                        int first = read();
                        bytes[offset] = (byte) first;
                        return 1;
                    }
                };
                return new OfficialAudioObjects.Response(200, uri, fixture.object.length, body);
            }
        };
        long started = System.nanoTime();
        expectIOException(() -> OfficialAudioObjects.stage(app, ignored -> { }, slowStream, shortDeadline),
                "deadline");
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        require(streamEntered.getCount() == 0, "slow stream did not start");
        require(elapsedMillis < 1_500, "deadline cancellation took too long: " + elapsedMillis + " ms");
        Path object = OfficialAudioObjects.objectPath(app.resolve("inputs/audio-cache/objects"),
                fixture.entry.sha1());
        require(!Files.exists(object), "deadline-expired object stream was promoted");
    }

    private static void preservesThreadInterruptDuringCacheDigest(Path path) throws Exception {
        Fixture fixture = fixture("interrupted cache digest".getBytes(StandardCharsets.US_ASCII));
        Files.write(path, fixture.object);
        Thread.currentThread().interrupt();
        try {
            expectIOException(() -> OfficialAudioObjects.cachedValid(path, fixture.entry), "interrupted");
            require(Thread.currentThread().isInterrupted(), "cache verification cleared the interrupt flag");
        } finally {
            Thread.interrupted();
        }
    }

    private static void rejectsTamperedIndexWithoutReplacingIt(Path app) throws Exception {
        Files.createDirectories(app);
        Fixture fixture = fixture("good bytes".getBytes(StandardCharsets.US_ASCII));
        Path indexPath = app.resolve("inputs/audio-cache/indexes/32.json");
        Files.createDirectories(indexPath.getParent());
        byte[] invalid = "not the pinned index".getBytes(StandardCharsets.US_ASCII);
        Files.write(indexPath, invalid);
        FakeTransport offline = new FakeTransport(fixture.index, fixture.object) {
            @Override
            public OfficialAudioObjects.Response get(URI uri, Duration timeout) throws IOException {
                throw new IOException("invalid cached index should not be replaced: " + uri);
            }
        };
        expectIOException(() -> OfficialAudioObjects.stage(app, ignored -> { }, offline, fixture.settings),
                "SHA-1 mismatch");
        require(java.util.Arrays.equals(Files.readAllBytes(indexPath), invalid),
                "invalid existing index was overwritten");
    }

    private static void rejectsRedirect(Path app) throws Exception {
        Files.createDirectories(app);
        Fixture fixture = fixture("redirect target".getBytes(StandardCharsets.US_ASCII));
        FakeTransport redirect = new FakeTransport(fixture.index, fixture.object) {
            @Override
            public OfficialAudioObjects.Response get(URI uri, Duration timeout) {
                if (uri.equals(OfficialAudioObjects.INDEX_URI)) {
                    return response(200, uri, fixture.index);
                }
                return response(302, uri.resolve("/redirected"), fixture.object);
            }
        };
        expectIOException(() -> OfficialAudioObjects.stage(app, ignored -> { }, redirect, fixture.settings),
                "HTTP 302");
        Path object = OfficialAudioObjects.objectPath(app.resolve("inputs/audio-cache/objects"),
                fixture.entry.sha1());
        require(!Files.exists(object), "redirected download created an object");
    }

    private static void stagesConcurrentNoClobberRace(Path app) throws Exception {
        Files.createDirectories(app);
        Fixture fixture = fixture("concurrent valid payload".getBytes(StandardCharsets.US_ASCII));
        CountDownLatch bothObjectRequests = new CountDownLatch(2);
        FakeTransport shared = new FakeTransport(fixture.index, fixture.object) {
            @Override
            public OfficialAudioObjects.Response get(URI uri, Duration timeout) throws IOException {
                if (!uri.equals(OfficialAudioObjects.INDEX_URI)) {
                    bothObjectRequests.countDown();
                    try {
                        if (!bothObjectRequests.await(4, TimeUnit.SECONDS)) {
                            throw new IOException("both concurrent object requests did not arrive");
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IOException("test interrupted", error);
                    }
                }
                return super.get(uri, timeout);
            }
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> OfficialAudioObjects.stage(app, ignored -> { }, shared,
                    fixture.settings));
            var second = executor.submit(() -> OfficialAudioObjects.stage(app, ignored -> { }, shared,
                    fixture.settings));
            OfficialAudioObjects.Result a = first.get(8, TimeUnit.SECONDS);
            OfficialAudioObjects.Result b = second.get(8, TimeUnit.SECONDS);
            require(a.total() == 1 && b.total() == 1, "concurrent stage counts were wrong");
            require(a.downloaded() + b.downloaded() == 1, "both concurrent stages claimed a new object");
            require(a.cachedRace() + b.cachedRace() == 1, "no-clobber race was not verified");
            Path object = OfficialAudioObjects.objectPath(a.objectsPath(), fixture.entry.sha1());
            require(java.util.Arrays.equals(Files.readAllBytes(object), fixture.object),
                    "concurrent no-clobber object bytes differ");
        } finally {
            executor.shutdownNow();
        }
    }

    private static void rejectsSymlinkedObject(Path app, Path outside) throws Exception {
        Files.createDirectories(app);
        Fixture fixture = fixture("symlink fixture".getBytes(StandardCharsets.US_ASCII));
        Path indexPath = app.resolve("inputs/audio-cache/indexes/32.json");
        Path objectPath = OfficialAudioObjects.objectPath(app.resolve("inputs/audio-cache/objects"),
                fixture.entry.sha1());
        Files.createDirectories(indexPath.getParent());
        Files.write(indexPath, fixture.index);
        Files.createDirectories(objectPath.getParent());
        Files.write(outside, fixture.object);
        try {
            Files.createSymbolicLink(objectPath, outside);
        } catch (UnsupportedOperationException | IOException error) {
            System.out.println("official audio objects contract: symlink case skipped (symlinks unavailable)");
            return;
        }
        FakeTransport offline = new FakeTransport(fixture.index, fixture.object) {
            @Override
            public OfficialAudioObjects.Response get(URI uri, Duration timeout) throws IOException {
                throw new IOException("symlinked cache should fail before network: " + uri);
            }
        };
        expectIOException(() -> OfficialAudioObjects.stage(app, ignored -> { }, offline, fixture.settings),
                "symlink in path");
        require(java.util.Arrays.equals(Files.readAllBytes(outside), fixture.object),
                "symlink target changed");
    }

    private static Fixture fixture(byte[] object) throws Exception {
        String sha1 = sha1(object);
        String name = "minecraft/sounds/test.ogg";
        Map<String, Object> entry = Map.of("hash", sha1, "size", (long) object.length);
        byte[] index = Json.canonical(Map.of("objects", Map.of(name, entry)));
        String indexSha1 = sha1(index);
        OfficialAudioObjects.Entry parsed = OfficialAudioObjects.parseIndex(index, indexSha1, 1, 4096).get(0);
        OfficialAudioObjects.Settings settings = new OfficialAudioObjects.Settings(indexSha1, 1, 2048, 4096, 2,
                8.0, Duration.ofSeconds(8));
        return new Fixture(index, object, parsed, settings);
    }

    private static MultiFixture multiFixture(byte[] first, byte[] second) throws Exception {
        String firstHash = sha1(first);
        String secondHash = sha1(second);
        require(!firstHash.equals(secondHash), "mixed cache fixture hashes must be unique");
        Map<String, Object> firstEntry = Map.of("hash", firstHash, "size", (long) first.length);
        Map<String, Object> secondEntry = Map.of("hash", secondHash, "size", (long) second.length);
        byte[] index = Json.canonical(Map.of("objects", Map.of(
                "minecraft/sounds/a.ogg", firstEntry,
                "minecraft/sounds/b.ogg", secondEntry)));
        String indexSha1 = sha1(index);
        List<OfficialAudioObjects.Entry> entries = OfficialAudioObjects.parseIndex(index, indexSha1, 2, 4096);
        Map<String, byte[]> objectBytes = Map.of(firstHash, first, secondHash, second);
        OfficialAudioObjects.Settings settings = new OfficialAudioObjects.Settings(indexSha1, 2, 2048, 4096, 2,
                8.0, Duration.ofSeconds(8));
        return new MultiFixture(index, entries, objectBytes, settings);
    }

    private static String sha1(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    }

    private static OfficialAudioObjects.Response response(int status, URI uri, byte[] bytes) {
        return new OfficialAudioObjects.Response(status, uri, bytes.length, new ByteArrayInputStream(bytes));
    }

    private static void expectIOException(Throwing action, String expectedText) throws Exception {
        try {
            action.run();
            throw new AssertionError("expected IOException containing: " + expectedText);
        } catch (IOException error) {
            require(error.getMessage() != null && error.getMessage().contains(expectedText),
                    "unexpected IOException: " + error.getMessage());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record Fixture(byte[] index, byte[] object, OfficialAudioObjects.Entry entry,
            OfficialAudioObjects.Settings settings) {
    }

    private record MultiFixture(byte[] index, List<OfficialAudioObjects.Entry> entries,
            Map<String, byte[]> objectBytes, OfficialAudioObjects.Settings settings) {
    }

    private static class FakeTransport implements OfficialAudioObjects.Transport {
        private final byte[] index;
        private final byte[] object;
        final AtomicInteger indexRequests = new AtomicInteger();
        final AtomicInteger objectRequests = new AtomicInteger();

        FakeTransport(byte[] index, byte[] object) {
            this.index = index;
            this.object = object;
        }

        @Override
        public OfficialAudioObjects.Response get(URI uri, Duration timeout) throws IOException {
            if (uri.equals(OfficialAudioObjects.INDEX_URI)) {
                indexRequests.incrementAndGet();
                return response(200, uri, index);
            }
            objectRequests.incrementAndGet();
            return response(200, uri, object);
        }
    }

    @FunctionalInterface
    private interface Throwing {
        void run() throws Exception;
    }
}
