package com.melonman106.vbvclient;

/** Minimal Herz-style mod contract adapted for Eaglercraft 26.2 u1. */
public interface HerzMod {
    String id();
    String name();
    String version();
    String authors();
    String description();
    default void onInitialize() {}
    default void onTick() {}
}
