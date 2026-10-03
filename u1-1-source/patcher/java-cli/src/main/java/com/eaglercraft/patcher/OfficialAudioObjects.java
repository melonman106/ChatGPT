package com.eaglercraft.patcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Flow;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Fetches and verifies the pinned Minecraft sound objects used by the GUI audio workflow. */
final class OfficialAudioObjects {
    static final String INDEX_SHA1 = "981aab8147520cdc1f0d4a84f46c161929021fee";
    static final URI INDEX_URI = URI.create("https://piston-meta.mojang.com/v1/packages/"
            + INDEX_SHA1 + "/32.json");
    static final URI OBJECT_ORIGIN = URI.create("https://resources.download.minecraft.net");
    static final int EXPECTED_OGG_COUNT = 4871;

    private static final int INDEX_MAX_BYTES = 2_000_000;
    private static final int OBJECT_MAX_BYTES = 12 * 1024 * 1024;
    private static final int WORKERS = 4;
    private static final double REQUESTS_PER_SECOND = 2.0;
    private static final Duration MAX_DURATION = Duration.ofHours(1);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);
    private static final Pattern SHA1_PATTERN = Pattern.compile("[0-9a-f]{40}");
    private static final String USER_AGENT = "eaglercraft-26.2-audio-fetch/1";

    private OfficialAudioObjects() {
    }

    static Result stage(Path appDirectory, Consumer<String> progress) throws IOException {
        return stage(appDirectory, progress, new HttpTransport(), Settings.DEFAULT);
    }

    /** Test seam for a bounded synthetic index and transport; production always uses Settings.DEFAULT. */
    static Result stage(Path appDirectory, Consumer<String> progress, Transport transport, Settings settings)
            throws IOException {
        long started = System.nanoTime();
        long deadline = started + settings.maxDuration.toNanos();
        Consumer<String> reporter = progress == null ? ignored -> { } : progress;
        checkInterrupted("official audio object staging");

        Path app = appDirectory.toAbsolutePath().normalize();
        rejectSymlinks(app);
        if (!Files.isDirectory(app, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("application directory is not a regular directory: " + app);
        }
        Path cache = app.resolve("inputs").resolve("audio-cache");
        Path indexPath = cache.resolve("indexes").resolve("32.json");
        Path objectsPath = cache.resolve("objects");
        checkedDirectory(indexPath.getParent());
        checkedDirectory(objectsPath);

        RateLimiter limiter = new RateLimiter(settings.requestsPerSecond);
        byte[] indexBytes;
        if (Files.exists(indexPath, LinkOption.NOFOLLOW_LINKS)) {
            indexBytes = readBounded(indexPath, settings.maxIndexBytes);
            verifySha1(indexBytes, settings.indexSha1, "cached official audio index");
        } else {
            indexBytes = fetchIndex(transport, limiter, deadline, settings.maxIndexBytes);
            verifySha1(indexBytes, settings.indexSha1, "official audio index");
        }
        List<Entry> entries = parseIndex(indexBytes, settings.indexSha1, settings.expectedOggCount,
                settings.maxObjectBytes);
        checkInterrupted("official audio index validation");
        saveVerifiedIndex(indexPath, indexBytes, settings.indexSha1, settings.maxIndexBytes);

        List<Entry> missing = new ArrayList<>();
        int cached = 0;
        int inspected = 0;
        for (Entry entry : entries) {
            checkInterrupted("official audio cache scan");
            if (System.nanoTime() >= deadline) {
                throw new IOException("official audio object fetch reached its one-hour deadline during cache scan");
            }
            Path object = objectPath(objectsPath, entry.sha1);
            boolean verified = cachedValid(object, entry);
            if (System.nanoTime() >= deadline) {
                throw new IOException("official audio object fetch reached its one-hour deadline during cache scan");
            }
            if (verified) {
                cached++;
            } else {
                missing.add(entry);
            }
            if (++inspected % 250 == 0) {
                reporter.accept("Audio objects: " + inspected + "/" + entries.size()
                        + " cache entries checked (" + cached + " verified)");
            }
        }
        reporter.accept("Audio objects: " + cached + "/" + entries.size() + " verified in cache");

        int downloaded = 0;
        int cachedRace = 0;
        List<String> failures = new ArrayList<>();
        if (!missing.isEmpty()) {
            DownloadCounts counts = downloadMissing(missing, objectsPath, transport, limiter, deadline,
                    settings, reporter, cached, entries.size());
            downloaded = counts.downloaded;
            cachedRace = counts.cachedRace;
            failures.addAll(counts.failures);
        }
        if (!failures.isEmpty()) {
            String details = String.join("; ", failures.subList(0, Math.min(5, failures.size())));
            throw new IOException("official audio object fetch incomplete (" + failures.size() + " failed): "
                    + details);
        }
        if (System.nanoTime() >= deadline && cached + downloaded + cachedRace != entries.size()) {
            throw new IOException("official audio object fetch reached its one-hour deadline");
        }
        return new Result(indexPath, objectsPath, entries.size(), cached, downloaded, cachedRace);
    }

    static List<Entry> parseIndex(byte[] raw, String expectedIndexSha1, int expectedCount, int maxObjectBytes)
            throws IOException {
        verifySha1(raw, expectedIndexSha1, "official audio index");
        Object parsed;
        try {
            parsed = Json.parse(raw, "official audio index");
        } catch (Json.JsonError error) {
            throw new IOException("official audio index is invalid JSON", error);
        }
        Map<?, ?> document = requireMap(parsed, "official audio index");
        Map<?, ?> objects = requireMap(document.get("objects"), "official audio index objects map");
        List<Entry> entries = new ArrayList<>();
        Set<String> hashes = new HashSet<>();
        for (Map.Entry<?, ?> item : objects.entrySet()) {
            if (!(item.getKey() instanceof String name)
                    || !name.startsWith("minecraft/sounds/") || !name.endsWith(".ogg")) {
                continue;
            }
            Map<?, ?> value = requireMap(item.getValue(), "official audio index entry " + name);
            if (value.size() != 2 || !value.containsKey("hash") || !value.containsKey("size")) {
                throw new IOException("invalid official audio index entry: " + name);
            }
            Object hashValue = value.get("hash");
            Object sizeValue = value.get("size");
            if (!(hashValue instanceof String sha1) || !SHA1_PATTERN.matcher(sha1).matches()) {
                throw new IOException("invalid object SHA-1 in official audio index: " + name);
            }
            if (!(sizeValue instanceof Long size) || size <= 0 || size > maxObjectBytes) {
                throw new IOException("invalid object size in official audio index: " + name);
            }
            if (!hashes.add(sha1)) {
                throw new IOException("official audio index contains a duplicate object hash: " + sha1);
            }
            entries.add(new Entry(name, sha1, size));
        }
        entries.sort(Comparator.comparing(Entry::name));
        if (expectedCount < 0 || entries.size() != expectedCount) {
            throw new IOException("official OGG set count mismatch: expected " + expectedCount + ", found "
                    + entries.size());
        }
        return List.copyOf(entries);
    }

    static boolean cachedValid(Path path, Entry entry) throws IOException {
        rejectSymlinks(path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("cache object path is not a regular file: " + path);
        }
        if (Files.size(path) != entry.size) {
            throw new IOException("existing cache object is invalid; preserved without overwrite: " + path);
        }
        DigestResult result;
        try {
            result = digestFile(path, entry.size);
        } catch (IOException error) {
            if (error.getMessage() != null && error.getMessage().startsWith("file exceeds expected object size:")) {
                throw new IOException("existing cache object is invalid; preserved without overwrite: " + path,
                        error);
            }
            throw error;
        }
        if (result.size != entry.size || !result.sha1.equals(entry.sha1)) {
            throw new IOException("existing cache object is invalid; preserved without overwrite: " + path);
        }
        return true;
    }

    static Path objectPath(Path root, String sha1) {
        return root.resolve(sha1.substring(0, 2)).resolve(sha1);
    }

    private static byte[] fetchIndex(Transport transport, RateLimiter limiter, long deadline, int maxBytes)
            throws IOException {
        checkInterrupted("official audio index request");
        limiter.await(deadline);
        checkInterrupted("official audio index request");
        try (Response response = transport.get(INDEX_URI, requestTimeout(deadline))) {
            validateResponse(response, INDEX_URI, "official index");
            if (response.contentLength > maxBytes) {
                throw new IOException("official audio index exceeds size limit");
            }
            byte[] data = readAtMost(response.body, maxBytes, deadline);
            if (data.length > maxBytes) {
                throw new IOException("official audio index exceeds size limit");
            }
            if (System.nanoTime() >= deadline) {
                throw new IOException("run deadline reached while reading official audio index");
            }
            return data;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("official audio index request interrupted", error);
        }
    }

    private static void saveVerifiedIndex(Path path, byte[] bytes, String expectedSha1, int maxBytes)
            throws IOException {
        rejectSymlinks(path);
        verifySha1(bytes, expectedSha1, "refusing to save unverified official audio index");
        checkedDirectory(path.getParent());
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] existing = readBounded(path, maxBytes);
            verifySha1(existing, expectedSha1, "existing official audio index");
            if (!java.util.Arrays.equals(existing, bytes)) {
                throw new IOException("existing official audio index differs from pinned bytes; preserved: " + path);
            }
            return;
        }
        Path temporary = Files.createTempFile(path.getParent(), ".index-", ".tmp");
        try {
            writeAndSync(temporary, bytes);
            try {
                Files.createLink(path, temporary);
            } catch (FileAlreadyExistsException appeared) {
                byte[] existing = readBounded(path, maxBytes);
                verifySha1(existing, expectedSha1, "official audio index created concurrently");
                if (!java.util.Arrays.equals(existing, bytes)) {
                    throw new IOException("official audio index appeared with different bytes; preserved: " + path,
                            appeared);
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static DownloadCounts downloadMissing(List<Entry> entries, Path root, Transport transport,
            RateLimiter limiter, long deadline, Settings settings, Consumer<String> progress, int initiallyCached,
            int total) throws IOException {
        ThreadFactory threadFactory = new ThreadFactory() {
            private int number;

            @Override
            public synchronized Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "official-audio-fetch-" + (++number));
                thread.setDaemon(true);
                return thread;
            }
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(settings.workers, settings.workers, 0L,
                TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), threadFactory);
        CompletionService<ObjectOutcome> completed = new ExecutorCompletionService<>(executor);
        Set<Future<ObjectOutcome>> active = new HashSet<>();
        Iterator<Entry> iterator = entries.iterator();
        List<String> failures = new ArrayList<>();
        int downloaded = 0;
        int cachedRace = 0;
        int finished = 0;
        int inFlightLimit = settings.workers * 2;
        try {
            while ((!active.isEmpty() || iterator.hasNext()) && System.nanoTime() < deadline) {
                while (active.size() < inFlightLimit && iterator.hasNext() && System.nanoTime() < deadline) {
                    Entry entry = iterator.next();
                    Future<ObjectOutcome> future = completed.submit(
                            () -> fetchOne(entry, root, transport, limiter, deadline, settings));
                    active.add(future);
                }
                if (active.isEmpty()) {
                    break;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                Future<ObjectOutcome> future;
                try {
                    future = completed.poll(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("official audio object fetch interrupted", error);
                }
                if (future == null) {
                    break;
                }
                active.remove(future);
                finished++;
                try {
                    ObjectOutcome outcome = future.get();
                    if (outcome.status == Status.DOWNLOADED) {
                        downloaded++;
                    } else {
                        cachedRace++;
                    }
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause();
                    String detail = cause == null ? error.toString() : cause.getMessage();
                    failures.add(detail == null ? cause.getClass().getSimpleName() : detail);
                } catch (CancellationException error) {
                    failures.add("object request cancelled before completion");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("official audio object fetch interrupted", error);
                }
                progress.accept("Audio objects: " + (initiallyCached + finished) + "/" + total
                        + " checked (" + downloaded + " downloaded)");
            }
        } finally {
            for (Future<ObjectOutcome> future : active) {
                future.cancel(true);
            }
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    failures.add("download workers did not stop after cancellation");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                failures.add("interrupted while stopping download workers");
            }
        }
        if (finished != entries.size()) {
            failures.add("run deadline reached with " + (entries.size() - finished)
                    + " object requests unfinished");
        }
        return new DownloadCounts(downloaded, cachedRace, failures);
    }

    private static ObjectOutcome fetchOne(Entry entry, Path root, Transport transport, RateLimiter limiter,
            long deadline, Settings settings) throws IOException {
        Path destination = objectPath(root, entry.sha1);
        if (cachedValid(destination, entry)) {
            return new ObjectOutcome(Status.CACHED);
        }
        checkedDirectory(destination.getParent());
        URI uri = objectUri(entry.sha1);
        IOException lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            Path temporary = null;
            try {
                checkInterrupted("official audio object request");
                limiter.await(deadline);
                checkInterrupted("official audio object request");
                try (Response response = transport.get(uri, requestTimeout(deadline))) {
                    validateResponse(response, uri, "official audio object " + entry.name);
                    if (response.contentLength >= 0 && response.contentLength != entry.size) {
                        throw new PermanentFetchException("official object Content-Length mismatch: " + entry.name);
                    }
                    temporary = Files.createTempFile(destination.getParent(), ".fetch-", ".tmp");
                    DigestResult result = streamObject(response.body, temporary, entry, deadline);
                    if (result.size != entry.size || !result.sha1.equals(entry.sha1)) {
                        throw new PermanentFetchException("downloaded object size/SHA-1 mismatch: " + entry.name);
                    }
                    try {
                        Files.createLink(destination, temporary);
                        return new ObjectOutcome(Status.DOWNLOADED);
                    } catch (FileAlreadyExistsException appeared) {
                        if (cachedValid(destination, entry)) {
                            return new ObjectOutcome(Status.CACHED_RACE);
                        }
                        throw new IOException("object appeared but failed verification; preserved: " + destination,
                                appeared);
                    }
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("object fetch interrupted: " + entry.name, error);
            } catch (PermanentFetchException error) {
                throw error;
            } catch (IOException error) {
                lastError = error;
                if (error instanceof HttpStatusException status && status.statusCode != 429
                        && status.statusCode < 500) {
                    break;
                }
                if (System.nanoTime() >= deadline || attempt == 3) {
                    break;
                }
                sleepBeforeRetry(attempt, deadline);
            } finally {
                if (temporary != null) {
                    Files.deleteIfExists(temporary);
                }
            }
        }
        throw new IOException("failed to fetch official object " + entry.name + ": "
                + (lastError == null ? "deadline reached" : lastError.getMessage()), lastError);
    }

    private static URI objectUri(String sha1) {
        return OBJECT_ORIGIN.resolve("/" + sha1.substring(0, 2) + "/" + sha1);
    }

    private static void validateResponse(Response response, URI requested, String label) throws IOException {
        if (response.statusCode != 200) {
            throw new HttpStatusException(response.statusCode, label + " request failed: HTTP "
                    + response.statusCode);
        }
        if (!requested.equals(response.uri)) {
            throw new PermanentFetchException(label + " URL changed or redirected: " + response.uri);
        }
    }

    private static DigestResult streamObject(InputStream input, Path temporary, Entry entry, long deadline)
            throws IOException {
        MessageDigest digest = sha1Digest();
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            while (true) {
                checkInterrupted("official audio object download");
                if (System.nanoTime() >= deadline) {
                    throw new IOException("run deadline reached during object download: " + entry.name);
                }
                int allowed = (int) Math.min(buffer.length, entry.size - total + 1);
                int count = input.read(buffer, 0, allowed);
                if (count < 0) {
                    break;
                }
                total += count;
                if (total > entry.size) {
                    throw new PermanentFetchException("official object exceeds declared size: " + entry.name);
                }
                digest.update(buffer, 0, count);
                ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, count);
                while (bytes.hasRemaining()) {
                    checkInterrupted("official audio object write");
                    output.write(bytes);
                }
            }
            output.force(true);
        }
        if (System.nanoTime() >= deadline) {
            throw new IOException("run deadline reached during object download: " + entry.name);
        }
        return new DigestResult(total, HexFormat.of().formatHex(digest.digest()));
    }

    private static byte[] readAtMost(InputStream input, int limit, long deadline) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(Math.min(limit, 64 * 1024));
        byte[] buffer = new byte[64 * 1024];
        int remaining = limit + 1;
        while (remaining > 0) {
            checkInterrupted("official audio index read");
            if (System.nanoTime() >= deadline) {
                throw new IOException("run deadline reached while reading official audio index");
            }
            int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (count < 0) {
                break;
            }
            output.write(buffer, 0, count);
            remaining -= count;
        }
        return output.toByteArray();
    }

    private static byte[] readBounded(Path path, int limit) throws IOException {
        rejectSymlinks(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("expected a regular file: " + path);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] data = input.readNBytes(limit + 1);
            if (data.length > limit) {
                throw new IOException("file exceeds size limit: " + path);
            }
            return data;
        }
    }

    private static void writeAndSync(Path path, byte[] bytes) throws IOException {
        try (FileChannel output = FileChannel.open(path, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                output.write(buffer);
            }
            output.force(true);
        }
    }

    private static void checkedDirectory(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        rejectSymlinks(absolute);
        Files.createDirectories(absolute);
        rejectSymlinks(absolute);
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("not a directory: " + absolute);
        }
    }

    private static void rejectSymlinks(Path path) throws IOException {
        for (Path item = path.toAbsolutePath().normalize(); item != null; item = item.getParent()) {
            if (Files.isSymbolicLink(item)) {
                throw new IOException("symlink in path: " + item);
            }
        }
    }

    private static void verifySha1(byte[] bytes, String expected, String label) throws IOException {
        String actual = HexFormat.of().formatHex(sha1Digest().digest(bytes));
        if (!actual.equals(expected)) {
            throw new IOException(label + " SHA-1 mismatch: " + actual);
        }
    }

    private static DigestResult digestFile(Path path, long byteLimit) throws IOException {
        MessageDigest digest = sha1Digest();
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(path)) {
            int count;
            while (true) {
                checkInterrupted("official audio cache verification");
                count = input.read(buffer);
                if (count < 0) {
                    break;
                }
                total += count;
                if (total > byteLimit) {
                    throw new IOException("file exceeds expected object size: " + path);
                }
                digest.update(buffer, 0, count);
            }
        }
        return new DigestResult(total, HexFormat.of().formatHex(digest.digest()));
    }

    private static void checkInterrupted(String operation) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException(operation + " interrupted");
        }
    }

    private static MessageDigest sha1Digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("Java runtime lacks SHA-1", impossible);
        }
    }

    private static Map<?, ?> requireMap(Object value, String label) throws IOException {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IOException(label + " is not an object");
        }
        return map;
    }

    private static Duration requestTimeout(long deadline) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new IOException("run deadline reached before network request");
        }
        return Duration.ofNanos(Math.min(remaining, REQUEST_TIMEOUT.toNanos()));
    }

    private static void sleepBeforeRetry(int attempt, long deadline) throws IOException {
        long delay = TimeUnit.SECONDS.toNanos(1L << (attempt - 1));
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return;
        }
        long sleep = Math.min(delay, remaining);
        try {
            TimeUnit.NANOSECONDS.sleep(sleep);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted during official object retry delay", error);
        }
    }

    record Result(Path indexPath, Path objectsPath, int total, int cached, int downloaded, int cachedRace) {
    }

    record Entry(String name, String sha1, long size) {
    }

    record Settings(String indexSha1, int expectedOggCount, int maxIndexBytes, int maxObjectBytes,
            int workers, double requestsPerSecond, Duration maxDuration) {
        static final Settings DEFAULT = new Settings(INDEX_SHA1, EXPECTED_OGG_COUNT, INDEX_MAX_BYTES,
                OBJECT_MAX_BYTES, WORKERS, REQUESTS_PER_SECOND, MAX_DURATION);

        Settings {
            if (indexSha1 == null || !SHA1_PATTERN.matcher(indexSha1).matches() || expectedOggCount <= 0
                    || maxIndexBytes <= 0 || maxObjectBytes <= 0 || maxObjectBytes > OBJECT_MAX_BYTES
                    || maxIndexBytes > INDEX_MAX_BYTES
                    || workers < 1 || workers > 8 || !Double.isFinite(requestsPerSecond)
                    || requestsPerSecond <= 0 || requestsPerSecond > 8 || maxDuration == null
                    || maxDuration.isZero() || maxDuration.isNegative()) {
                throw new IllegalArgumentException("invalid official audio fetch limits");
            }
        }
    }

    interface Transport {
        Response get(URI uri, Duration timeout) throws IOException, InterruptedException;
    }

    static final class Response implements AutoCloseable {
        final int statusCode;
        final URI uri;
        final long contentLength;
        final InputStream body;

        Response(int statusCode, URI uri, long contentLength, InputStream body) {
            this.statusCode = statusCode;
            this.uri = uri;
            this.contentLength = contentLength;
            this.body = body;
        }

        @Override
        public void close() throws IOException {
            body.close();
        }
    }

    private static final class HttpTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();

        @Override
        public Response get(URI uri, Duration timeout) throws IOException, InterruptedException {
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IOException("refusing non-HTTPS official asset URL: " + uri);
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request,
                    ignored -> new BoundedBodySubscriber(OBJECT_MAX_BYTES));
            long size = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            return new Response(response.statusCode(), response.uri(), size,
                    new ByteArrayInputStream(response.body()));
        }
    }

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        BoundedBodySubscriber(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            try {
                for (ByteBuffer buffer : buffers) {
                    if (buffer.remaining() > limit - bytes.size()) {
                        subscription.cancel();
                        body.completeExceptionally(new IOException("official asset response exceeds size limit"));
                        return;
                    }
                    byte[] chunk = new byte[buffer.remaining()];
                    buffer.get(chunk);
                    bytes.write(chunk, 0, chunk.length);
                }
                subscription.request(1);
            } catch (RuntimeException error) {
                subscription.cancel();
                body.completeExceptionally(error);
            }
        }

        @Override
        public void onError(Throwable error) {
            body.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }

    private static final class RateLimiter {
        private final long intervalNanos;
        private long nextStart;

        RateLimiter(double requestsPerSecond) {
            intervalNanos = (long) (1_000_000_000.0 / requestsPerSecond);
        }

        void await(long deadline) throws IOException {
            long waitNanos;
            synchronized (this) {
                long now = System.nanoTime();
                long start = Math.max(now, nextStart);
                nextStart = start + intervalNanos;
                if (start >= deadline) {
                    throw new IOException("run deadline reached before network request");
                }
                waitNanos = start - now;
            }
            if (waitNanos <= 0) {
                return;
            }
            if (System.nanoTime() + waitNanos >= deadline) {
                throw new IOException("run deadline reached while rate limited");
            }
            try {
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while rate limited", error);
            }
        }
    }

    private enum Status { DOWNLOADED, CACHED, CACHED_RACE }

    private record ObjectOutcome(Status status) {
    }

    private record DigestResult(long size, String sha1) {
    }

    private record DownloadCounts(int downloaded, int cachedRace, List<String> failures) {
    }

    private static class PermanentFetchException extends IOException {
        PermanentFetchException(String message) {
            super(message);
        }
    }

    private static final class HttpStatusException extends IOException {
        final int statusCode;

        HttpStatusException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }
}
