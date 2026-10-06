package com.melonman106.vbvclient;

public final class VBVArmorHudConfig {
    public static final String[] DURABILITY_MODES = { "Number", "Percent", "Bar", "Off" };
    public static final int DURA_NUMBER = 0;
    public static final int DURA_PERCENT = 1;
    public static final int DURA_BAR = 2;
    public static final int DURA_OFF = 3;

    public static boolean enabled = true;
    public static boolean leftSide = true;
    public static boolean showEmptySlots = true;
    public static boolean warning = true;
    public static int durabilityMode = DURA_NUMBER;

    private VBVArmorHudConfig() {}

    public static void reset() {
        enabled = true;
        leftSide = true;
        showEmptySlots = true;
        warning = true;
        durabilityMode = DURA_NUMBER;
    }
}
