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
            "Native client-side visual compatibility for 26.3 blocks translated to Eaglercraft 26.2."
        ));

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
