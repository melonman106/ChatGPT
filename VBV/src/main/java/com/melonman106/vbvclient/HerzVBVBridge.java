package com.melonman106.vbvclient;

import net.ada.herz.api.HerzClient;
import net.ada.herz.api.HerzMod;
import net.ada.herz.api.HerzModRegistry;

/** Bridges the existing VBV metadata/features into the new 26.2 Herz API layer. */
public final class HerzVBVBridge {
    private static boolean registered;

    private HerzVBVBridge() {}

    public static void register(VBVModInfo info) {
        if (info == null || registered && HerzModRegistry.get(info.id()) != null) return;
        HerzModRegistry.register(new HerzMod() {
            @Override public String id() { return info.id(); }
            @Override public String name() { return info.name(); }
            @Override public String version() { return info.version(); }
            @Override public String authors() { return info.authors(); }
            @Override public String description() { return info.description(); }
        });
        registered = true;
    }

    public static void initialize() {
        HerzClient.initialize();
    }

    public static void tick() {
        HerzClient.tick();
    }
}
