package com.melonman106.vbvclient;

/**
 * Native replacement for uku's Armor HUD ArmorHudConfig (which needed Lombok
 * and ukulib). Enums are plain ints so no extra classes are needed.
 */
public final class VBVArmorHudConfig {
    public static final String[] ANCHORS = { "Top center", "Top", "Vertical center", "Bottom", "Hotbar" };
    public static final int ANCHOR_TOP_CENTER = 0, ANCHOR_TOP = 1, ANCHOR_VERT_CENTER = 2,
        ANCHOR_BOTTOM = 3, ANCHOR_HOTBAR = 4;

    public static final String[] SIDES = { "Left", "Right" };
    public static final int SIDE_LEFT = 0, SIDE_RIGHT = 1;

    public static final String[] ORIENTATIONS = { "Horizontal", "Vertical" };
    public static final int ORIENT_HORIZONTAL = 0, ORIENT_VERTICAL = 1;

    public static final String[] STYLES = { "Box", "None" };
    public static final int STYLE_BOX = 0, STYLE_NONE = 1;

    public static final String[] WIDGET_SHOWN = { "Always", "If any present", "Not empty", "Damaged pieces" };
    public static final int SHOWN_ALWAYS = 0, SHOWN_IF_ANY = 1, SHOWN_NOT_EMPTY = 2, SHOWN_DAMAGED = 3;

    public static final String[] OFFHAND_BEHAVIOR = { "Always ignore", "Adhere", "Always leave space" };
    public static final int OFFHAND_IGNORE = 0, OFFHAND_ADHERE = 1, OFFHAND_LEAVE_SPACE = 2;

    public static final String[] DURABILITY = { "Bar", "Numeric", "Percentage" };
    public static final int DURA_BAR = 0, DURA_NUMERIC = 1, DURA_PERCENT = 2;

    public static boolean enabled;
    public static int anchor;
    public static int side;
    public static int offsetX;
    public static int offsetY;
    public static int style;
    public static int orientation;
    public static int widgetShown;
    public static int offhandBehavior;
    public static int durabilityDisplay;
    public static boolean offHandDurability;
    public static boolean mainHandDurability;
    public static boolean reversed;
    public static boolean warningShown;
    public static int minDurabilityValue;
    public static int minDurabilityPercent;
    public static int warningBobIntensity;

    static { reset(); }

    private VBVArmorHudConfig() {}

    public static int next(int value, int count) {
        return (value + 1) % count;
    }

    public static void reset() {
        enabled = true;
        anchor = ANCHOR_HOTBAR;
        side = SIDE_LEFT;
        offsetX = 0;
        offsetY = 0;
        style = STYLE_HOTBAR;
        orientation = ORIENT_HORIZONTAL;
        widgetShown = SHOWN_NOT_EMPTY;
        offhandBehavior = OFFHAND_ADHERE;
        durabilityDisplay = DURA_BAR;
        offHandDurability = true;
        mainHandDurability = false;
        reversed = false;
        warningShown = true;
        minDurabilityValue = 20;
        minDurabilityPercent = 10;
        warningBobIntensity = 3;
    }
}