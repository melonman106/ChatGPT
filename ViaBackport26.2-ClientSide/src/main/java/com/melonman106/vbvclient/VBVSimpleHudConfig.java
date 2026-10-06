package com.melonman106.vbvclient;

public final class VBVSimpleHudConfig {
    public static boolean enabled = true;
    public static boolean fps = true;
    public static boolean coordinates = true;
    public static boolean speed = true;
    public static boolean lightLevel = false;
    public static boolean gameTime = false;
    public static boolean playerName = false;
    public static boolean health = true;
    public static boolean hunger = true;
    public static boolean movement = true;
    public static boolean effects = true;
    public static boolean equipment = true;
    public static boolean armorShowNumbers = false;
    public static int armorWarningPercent = 20;
    public static int armorXOffset = 0;
    public static int armorYOffset = 0;

    private VBVSimpleHudConfig() {}

    public static void reset() {
        enabled = true;
        fps = true;
        coordinates = true;
        speed = true;
        lightLevel = false;
        gameTime = false;
        playerName = false;
        health = true;
        hunger = true;
        movement = true;
        effects = true;
        equipment = true;
        armorShowNumbers = false;
        armorWarningPercent = 20;
        armorXOffset = 0;
        armorYOffset = 0;
    }
}
