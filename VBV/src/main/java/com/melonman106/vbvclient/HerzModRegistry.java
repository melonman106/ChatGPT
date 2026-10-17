package com.melonman106.vbvclient;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Central Herz-style mod registry for the Eaglercraft 26.2 client. */
public final class HerzModRegistry {
    private static final Map<String, HerzMod> MODS = new LinkedHashMap<>();

    private HerzModRegistry() {}

    public static void register(HerzMod mod) {
        if (mod == null || mod.id() == null || mod.id().isEmpty()) {
            throw new IllegalArgumentException("Herz mod must have a non-empty id");
        }
        MODS.put(mod.id(), mod);
    }

    public static HerzMod get(String id) {
        return MODS.get(id);
    }

    public static Collection<HerzMod> all() {
        return Collections.unmodifiableCollection(MODS.values());
    }
}
