package com.melonman106.vbvclient.herz;

/**
 * Minimal Herz-style client mod contract adapted for Eaglercraft 26.2 u1.
 * The upstream Herz-Core runtime currently targets Eaglercraft 1.8.8/u53,
 * so this adapter keeps the API shape local to the 26.2 client until a native
 * 26.2 Herz port is available.
 */
public interface HerzMod {
    String id();
    String name();
    String version();
    String authors();
    String description();
    default void onInitialize() {}
    default void onTick() {}
}
