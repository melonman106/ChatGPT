package net.ada.herz.api;

/**
 * 26.2 client-side Herz adapter entry point.
 *
 * The adapter keeps the public mod lifecycle independent from VBV's feature
 * implementations so additional APIs can be added without rewriting every mod.
 */
public final class HerzClient {
    private static boolean initialized;

    private HerzClient() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        for (HerzMod mod : HerzModRegistry.all()) {
            mod.onInitialize();
        }
    }

    public static void tick() {
        for (HerzMod mod : HerzModRegistry.all()) {
            mod.onTick();
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }
}
