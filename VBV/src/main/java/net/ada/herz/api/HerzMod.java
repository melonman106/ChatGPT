package net.ada.herz.api;

/**
 * Minimal Herz-style client mod contract for the Eaglercraft 26.2 u1 port.
 *
 * This is the 26.2 adapter layer used by VBV. It intentionally does not depend
 * on the current Herz-Core 1.8.8/u53 runtime, which is a different game target.
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
