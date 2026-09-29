package com.melonman106.vbfix;

import com.viaversion.viabackwards.api.data.BackwardsMappingData;
import com.viaversion.viabackwards.api.data.MappedItem;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;

/**
 * Reads the same ViaBackwards item mapping used by the packet rewriter.
 *
 * ViaBackwards 5.11+ adds the original 1.21.4+ item identifier to
 * custom_model_data for 1.21.4+ clients.  This bridge lets VBV verify that
 * an item is one of the mapped future items instead of guessing from the
 * 26.2 placeholder item alone.
 */
public final class ViaBackwardsItemBridge {
    private ViaBackwardsItemBridge() {}

    public static MappedItem findMappedItem(String identifier) {
        try {
            Protocol protocol = Via.getManager().getProtocolManager()
                    .getProtocol(ProtocolVersion.v26_2, ProtocolVersion.v26_3);
            if (protocol == null || !(protocol.getMappingData() instanceof BackwardsMappingData mappingData)) {
                return null;
            }

            if (mappingData.getBackwardsItemMappings() == null) {
                return null;
            }

            String wanted = identifier.startsWith("minecraft:")
                    ? identifier
                    : "minecraft:" + identifier;

            for (MappedItem mapped : mappingData.getBackwardsItemMappings().values()) {
                String mappedIdentifier = mappingData.getFullItemMappings().identifier(mapped.id());
                if (wanted.equals(mappedIdentifier)) {
                    return mapped;
                }
            }
        } catch (Throwable ignored) {
            // ViaBackwards may not be initialized yet or may use a different
            // protocol implementation. The normal VBV mapping path still works.
        }
        return null;
    }

    public static boolean isMappedFutureItem(String identifier) {
        return findMappedItem(identifier) != null;
    }

    public static int customModelData(String identifier) {
        MappedItem mapped = findMappedItem(identifier);
        return mapped == null ? -1 : mapped.customModelData();
    }
}
