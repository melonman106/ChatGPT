package com.eaglercraft.patcher;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Focused, fixture-only checks for the optional inline Wispcraft injector. */
public final class WispcraftInjectionTest {
    private WispcraftInjectionTest() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("wispcraft-injection-test-");
        try {
            injectsAfterTheRealHeadAndEscapesEndTags(directory);
            rejectsMissingHeadWithoutChangingTheInput(directory);
            System.out.println("Wispcraft HTML injection: PASS");
        } finally {
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });
            }
        }
    }

    private static void injectsAfterTheRealHeadAndEscapesEndTags(Path directory) throws Exception {
        Path html = directory.resolve("fixture.html");
        String original = "<!doctype html><!-- <head data-fake> --><html>"
                + "<HEAD data-real=\"yes\"><meta charset=\"utf-8\"></HEAD>"
                + "<body>fixture</body></html>";
        String script = "window.wispLabel = \"žluťoučký\"; window.wisp = \"</ScRiPt>\"; // </script>\n";
        Files.writeString(html, original, StandardCharsets.UTF_8);

        Main.injectWispcraftScriptIntoHtml(html, script.getBytes(StandardCharsets.UTF_8));

        String result = Files.readString(html, StandardCharsets.UTF_8);
        int openingHeadEnd = result.indexOf("><meta charset=\"UTF-8\">\n<script>if(");
        check(openingHeadEnd >= 0, "UTF-8 declaration and script were not prepended to the head");
        check(result.contains("window.wispLabel = \"žluťoučký\";"),
                "non-ASCII source text changed during injection");
        int nativeCapture = result.indexOf("Object.defineProperty(globalThis,\"__eaglerNativeWebSocket\"");
        int wispcraftCode = result.indexOf("window.wispLabel");
        check(nativeCapture >= 0 && wispcraftCode > nativeCapture,
                "native WebSocket capture must run immediately before the Wispcraft code");
        check(result.contains("});</script>\n<script>\nwindow.wispLabel"),
                "native WebSocket capture is not immediately ahead of the Wispcraft script");
        check(result.contains("writable:false,configurable:false"),
                "the native WebSocket capture can be replaced after initialization");
        check(result.contains("window.wisp = \"<\\/ScRiPt>\"; // <\\/script>"),
                "script end-tag sequences were not escaped");
        check(countIgnoreCase(result, "</script") == 2,
                "an injected end-tag sequence can terminate the inline script early");
        check(result.endsWith("<meta charset=\"utf-8\"></HEAD><body>fixture</body></html>"),
                "HTML after the insertion point changed");
    }

    private static void rejectsMissingHeadWithoutChangingTheInput(Path directory) throws Exception {
        Path html = directory.resolve("no-head.html");
        String original = "<!doctype html><body>no head</body>";
        Files.writeString(html, original, StandardCharsets.UTF_8);
        boolean rejected = false;
        try {
            Main.injectWispcraftScriptIntoHtml(html, "window.wisp = true;".getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            rejected = true;
        }
        check(rejected, "HTML without an opening head tag was accepted");
        check(Files.readString(html, StandardCharsets.UTF_8).equals(original),
                "failed injection changed the original HTML");
    }

    private static int countIgnoreCase(String text, String needle) {
        int count = 0;
        String lowerText = text.toLowerCase(java.util.Locale.ROOT);
        String lowerNeedle = needle.toLowerCase(java.util.Locale.ROOT);
        for (int offset = 0; (offset = lowerText.indexOf(lowerNeedle, offset)) >= 0;
                offset += lowerNeedle.length()) {
            count++;
        }
        return count;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
