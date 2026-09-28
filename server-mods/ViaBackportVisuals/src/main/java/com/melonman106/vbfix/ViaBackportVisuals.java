package com.melonman106.vbfix;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ViaBackportVisuals implements ModInitializer {
    public static final String MOD_ID = "viabackportvisuals";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("ViaBackportVisuals loaded for Minecraft 26.3.");
    }
}
