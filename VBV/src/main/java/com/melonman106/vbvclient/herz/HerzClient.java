package com.melonman106.vbvclient.herz;

/** 26.2 client lifecycle entry point for the Herz adapter layer. */
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
