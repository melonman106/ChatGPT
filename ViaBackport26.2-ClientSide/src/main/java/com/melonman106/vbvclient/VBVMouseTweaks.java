package com.melonman106.vbvclient;

/**
 * Native Eaglercraft inventory-mouse helper.
 *
 * This is intentionally independent of Fabric/NeoForge/Mixin APIs. The
 * container-screen mouse hook can call these helpers once the generated
 * Eagler screen input path is wired up.
 */
public final class VBVMouseTweaks {
    private static boolean wheelTweakEnabled = true;
    private static boolean quickMoveEnabled = true;
    private static boolean dragEvenlyEnabled = true;

    private VBVMouseTweaks() {}

    public static boolean isWheelTweakEnabled() {
        return wheelTweakEnabled;
    }

    public static boolean isQuickMoveEnabled() {
        return quickMoveEnabled;
    }

    public static boolean isDragEvenlyEnabled() {
        return dragEvenlyEnabled;
    }

    public static void setWheelTweakEnabled(boolean enabled) {
        wheelTweakEnabled = enabled;
    }

    public static void setQuickMoveEnabled(boolean enabled) {
        quickMoveEnabled = enabled;
    }

    public static void setDragEvenlyEnabled(boolean enabled) {
        dragEvenlyEnabled = enabled;
    }

    /**
     * Converts a mouse-wheel direction into a deterministic inventory scan
     * direction. Positive is the next slot; negative is the previous slot.
     */
    public static int wheelDirection(double scrollDelta) {
        if (scrollDelta > 0.0D) return 1;
        if (scrollDelta < 0.0D) return -1;
        return 0;
    }

    /**
     * Returns the next slot index while wrapping around the inventory.
     */
    public static int nextSlot(int current, int slotCount, int direction) {
        if (slotCount <= 0) return -1;
        int slot = current + direction;
        if (slot < 0) slot = slotCount - 1;
        if (slot >= slotCount) slot = 0;
        return slot;
    }
}
