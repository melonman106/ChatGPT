package com.melonman106.vbvclient;

/** Bridges existing VBV metadata/features into the 26.2 Herz adapter layer. */
public final class HerzVBVBridge {
    private HerzVBVBridge() {}

    public static void register(VBVModInfo info) {
        if (info == null || HerzModRegistry.get(info.id()) != null) return;
        HerzModRegistry.register(new HerzMod() {
            @Override public String id() { return info.id(); }
            @Override public String name() { return info.name(); }
            @Override public String version() { return info.version(); }
            @Override public String authors() { return info.authors(); }
            @Override public String description() { return info.description(); }
        });
    }

    public static void initialize() {
        HerzClient.initialize();
    }

    public static void tick() {
        HerzClient.tick();
    }
}
