package com.melonman106.vbvclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Single place that opens a screen. The Eaglercraft 26.2 decompile renamed
 * Minecraft.setScreen, so apply_vbv_patch.py rewrites the method name below
 * to whatever the generated Minecraft.java actually declares.
 */
public final class VBVNav {
    private VBVNav() {}

    public static void open(Screen screen) {
        Minecraft.getInstance().setScreen(screen);
    }
}
