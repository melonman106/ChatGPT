package com.melonman106.vbvclient;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class PayloadTypeRegistryHelper {
    private PayloadTypeRegistryHelper() {}

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(VbvPayload.TYPE, VbvPayload.CODEC);
    }
}
