package com.eaglercraft.patcher;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;

public final class GuiBundledToolsContractTest {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toRealPath();
        Method discover = GuiMain.class.getDeclaredMethod("discoverBundledTools");
        discover.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Path> found = (Map<String, Path>) discover.invoke(null);
        for (String key : new String[] {"java17", "java25", "node", "npm"}) {
            Path path = found.get(key);
            if (path == null || !path.toRealPath().startsWith(root.resolve(".toolchain"))) {
                throw new AssertionError("GUI did not discover app-local " + key + ": " + path);
            }
        }
        System.out.println("GUI app-local tool discovery: PASS");
    }
}
