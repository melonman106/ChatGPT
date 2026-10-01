package com.melonman106.vbvclient;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class VBVClient {
    public static final String MOD_ID = "viabackportvisuals-client";
    public static final String VERSION = "1.0.0-26.2";

    private static final Map<String, VBVModInfo> MODS = new LinkedHashMap<>();
    private static final VBVVisualRegistry VISUALS = new VBVVisualRegistry();

    private VBVClient() {}

    public static void init() {
        if (!MODS.isEmpty()) return;

        registerMod(new VBVModInfo(
            MOD_ID,
            "ViaBackportVisuals Client",
            VERSION,
            "melonman106",
            "Native client-side visual compatibility for 26.3 blocks translated to Eaglercraft 26.2.",
            "vbvclient/mods/viabackportvisuals/pack.png",
            false
        ));

        registerMod(new VBVModInfo(
            "appleskin",
            "AppleSkin",
            "Eagler port 1.0.0",
            "Eagler client port",
            "Food, hunger, saturation, and exhaustion information in the client HUD.",
            "vbvclient/mods/appleskin/pack.png",
            false
        ));

        registerMod(new VBVModInfo(
            "panorama-selector",
            "Panorama Selector",
            "1.0.0-26.2",
            "Eagler client",
            "Select from built-in title-screen panorama sets without changing the server.",
            "vbvclient/mods/panorama-selector/pack.png",
            false
        ));

        registerMod(new VBVModInfo(
            "better-clouds",
            "Better Clouds",
            "1.0.0-26.2",
            "Eagler client",
            "Optional built-in cloud textures for the Eaglercraft client.",
            "vbvclient/mods/better-clouds/pack.png",
            true
        ));

        registerMod(new VBVModInfo(
            "dark-ui",
            "Dark UI",
            "1.0.0-26.2",
            "Eagler client",
            "Optional darker interface textures for menus and client GUI elements.",
            "vbvclient/mods/dark-ui/pack.png",
            true
        ));

        registerMod(new VBVModInfo(
            "low-fire",
            "Low Fire",
            "1.0.0-26.2",
            "Eagler client",
            "Optional lower fire overlay texture for clearer first-person visibility.",
            "vbvclient/mods/low-fire/pack.png",
            true
        ));

        registerMod(new VBVModInfo(
            "connected-glass",
            "Connected Glass",
            "1.0.0-26.2",
            "Eagler client",
            "Optional glass texture pack for a cleaner connected-glass appearance.",
            "vbvclient/mods/connected-glass/pack.png",
            true
        ));

        VBVGeneratedPackMods.register();
        VBVVisualMappings.register(VISUALS);
    }

    public static void registerMod(VBVModInfo info) {
        MODS.put(info.id(), info);
    }

    public static Collection<VBVModInfo> getMods() {
        return Collections.unmodifiableCollection(MODS.values());
    }

    public static VBVVisualRegistry visuals() {
        return VISUALS;
    }
}
