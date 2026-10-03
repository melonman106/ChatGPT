package com.eaglercraft.patcher;

import java.awt.Window;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** Exercises the packaged window's discovery without displaying it or building a game. */
public final class PackagedInputDiscoveryTest {
    public static void main(String[] args) throws Exception {
        Path kit = Path.of(args[0]).toAbsolutePath();
        Path custom = Files.createTempFile("eagler-custom-sounds-", ".epk");
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Constructor<GuiMain> constructor = GuiMain.class.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    Object gui = constructor.newInstance();
                    Field fieldsField = GuiMain.class.getDeclaredField("fields");
                    fieldsField.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Map<String, JTextField> fields = (Map<String, JTextField>) fieldsField.get(gui);
                    Map<String, String> expected = Map.of(
                            "patchBundle", "source-patch-bundle.zip",
                            "skeleton", "project-skeleton-u1-nettyfix.zip",
                            "resourceOverlay", "inputs/resource-overlay-normal.zip",
                            "vineflower", "inputs/vineflower-1.12.0.jar",
                            "sounds", "inputs/sounds.epk",
                            "externalRoot", "inputs/resources");
                    check(fields, expected, kit);
                    Map<String, String> expectedHashes = Map.of(
                            "patchSha", fields.get("patchSha").getText(),
                            "skeletonSha", fields.get("skeletonSha").getText(),
                            "resourceSha", fields.get("resourceSha").getText(),
                            "soundsSha", fields.get("soundsSha").getText());
                    for (String key : expected.keySet()) fields.get(key).setText(kit.resolve("missing").toString());
                    for (String key : expectedHashes.keySet()) fields.get(key).setText("0".repeat(64));
                    Method refresh = GuiMain.class.getDeclaredMethod("refreshBundledInputs");
                    refresh.setAccessible(true);
                    refresh.invoke(gui);
                    check(fields, expected, kit);
                    for (var entry : expectedHashes.entrySet()) {
                        if (!fields.get(entry.getKey()).getText().equals(entry.getValue())) {
                            throw new AssertionError("Rediscovered hash not restored: " + entry.getKey());
                        }
                    }
                    for (String key : expected.keySet()) {
                        fields.get(key).setText((key.equals("externalRoot") ? custom.getParent() : custom).toString());
                    }
                    refresh.invoke(gui);
                    for (String key : expected.keySet()) {
                        String chosen = (key.equals("externalRoot") ? custom.getParent() : custom).toString();
                        if (!fields.get(key).getText().equals(chosen)) {
                            throw new AssertionError("Explicit readable input was replaced: " + key);
                        }
                    }
                    for (String key : new String[]{"output", "standaloneOutput", "iwaOutput", "iwaKey"}) {
                        if (!fields.get(key).getText().isBlank()) throw new AssertionError("Output was preselected: " + key);
                    }
                    System.out.println("PASS: six packaged inputs selected automatically, stale paths and four hashes repaired, six custom inputs preserved, output paths blank");
                } catch (Exception error) {
                    throw new RuntimeException(error);
                } finally {
                    for (Window window : Window.getWindows()) window.dispose();
                }
            });
        } finally {
            Files.deleteIfExists(custom);
        }
        System.exit(0);
    }

    private static void check(Map<String, JTextField> fields, Map<String, String> expected, Path kit) {
        for (var entry : expected.entrySet()) {
            Path selected = Path.of(fields.get(entry.getKey()).getText()).toAbsolutePath();
            if (!selected.equals(kit.resolve(entry.getValue())) || !Files.exists(selected)) {
                throw new AssertionError("Missing automatic input: " + entry.getKey() + " = " + selected);
            }
        }
    }
}
