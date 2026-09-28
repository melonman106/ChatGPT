package com.melonman106.vbfix;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.data.MappingData;
import com.viaversion.viaversion.api.data.Mappings;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public final class ViaBackportVisuals implements ModInitializer {
    public static final String MOD_ID = "viabackportvisuals";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final ProtocolVersion SERVER_VERSION = ProtocolVersion.v26_3;
    private static final ProtocolVersion CLIENT_VERSION = ProtocolVersion.v26_2;

    private static final String[] WOOL_COLORS = {
            "white", "orange", "magenta", "light_blue",
            "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue",
            "brown", "green", "red", "black"
    };

    /*
     * These are 26.2-compatible stair/slab blocks with the same state geometry
     * as the new wool stairs/slabs. The resource pack can replace their models
     * without changing the block shape/collision sent to the old client.
     */
    private static final String[] STAIR_PLACEHOLDERS = {
            "smooth_quartz_stairs", "smooth_red_sandstone_stairs",
            "smooth_sandstone_stairs", "brick_stairs",
            "nether_brick_stairs", "red_nether_brick_stairs",
            "sandstone_stairs", "smooth_stone_stairs",
            "cobbled_deepslate_stairs", "polished_deepslate_stairs",
            "tuff_stairs", "polished_tuff_stairs",
            "granite_stairs", "diorite_stairs",
            "mossy_stone_brick_stairs", "stone_brick_stairs"
    };

    private static final String[] SLAB_PLACEHOLDERS = {
            "smooth_quartz_slab", "smooth_red_sandstone_slab",
            "smooth_sandstone_slab", "smooth_stone_slab",
            "brick_slab", "nether_brick_slab",
            "red_nether_brick_slab", "sandstone_slab",
            "granite_slab", "diorite_slab",
            "cobbled_deepslate_slab", "polished_deepslate_slab",
            "tuff_slab", "polished_tuff_slab",
            "mossy_stone_brick_slab", "stone_brick_slab"
    };

    private static final String STRAW_BED_PLACEHOLDER = "black_bed";

    @Override
    public void onInitialize() {
        LOGGER.info("ViaBackportVisuals loaded for Minecraft 26.3.");

        /*
         * ViaVersion/ViaBackwards loads its protocol mappings during its own
         * initialization. SERVER_STARTED runs after that work has completed,
         * while still occurring before normal players can use the server.
         */
        ServerLifecycleEvents.SERVER_STARTED.register(server -> installMappings());
    }

    private static void installMappings() {
        try {
            if (!Via.getManager().isInitialized()) {
                LOGGER.warn("ViaVersion is not initialized; visual mappings were not installed.");
                return;
            }

            Protocol protocol = Via.getManager().getProtocolManager()
                    .getProtocol(CLIENT_VERSION, SERVER_VERSION);

            if (protocol == null) {
                LOGGER.warn("Could not find the ViaBackwards 26.3 -> 26.2 protocol.");
                return;
            }

            MappingData mappingData = protocol.getMappingData();
            if (mappingData == null || mappingData.getBlockStateMappings() == null) {
                LOGGER.warn("ViaBackwards 26.3 -> 26.2 has no block-state mappings.");
                return;
            }

            Mappings mappings = mappingData.getBlockStateMappings();

            int stairs = 0;
            int slabs = 0;
            int beds = 0;

            for (String color : WOOL_COLORS) {
                stairs += remapBlockStates(
                        mappings,
                        "minecraft:" + color + "_wool_stairs",
                        "minecraft:" + STAIR_PLACEHOLDERS[indexOf(WOOL_COLORS, color)]
                );

                slabs += remapBlockStates(
                        mappings,
                        "minecraft:" + color + "_wool_slab",
                        "minecraft:" + SLAB_PLACEHOLDERS[indexOf(WOOL_COLORS, color)]
                );
            }

            beds = remapBlockStates(
                    mappings,
                    "minecraft:straw_bed",
                    "minecraft:" + STRAW_BED_PLACEHOLDER
            );

            LOGGER.info(
                    "Installed ViaBackportVisuals mappings: {} wool stair states, {} wool slab states, {} straw-bed states.",
                    stairs, slabs, beds
            );
        } catch (Throwable t) {
            LOGGER.error("Failed to install ViaBackportVisuals mappings.", t);
        }
    }

    private static int remapBlockStates(Mappings mappings, String sourceId, String placeholderId) {
        Block source = getBlock(sourceId);
        Block placeholder = getBlock(placeholderId);

        if (source == null || placeholder == null) {
            LOGGER.warn("Missing block while installing mapping: {} -> {}", sourceId, placeholderId);
            return 0;
        }

        Map<String, BlockState> placeholderStates = new HashMap<>();
        for (BlockState state : placeholder.getStateDefinition().getPossibleStates()) {
            placeholderStates.put(stateKey(state), state);
        }

        int changed = 0;

        for (BlockState sourceState : source.getStateDefinition().getPossibleStates()) {
            int sourceStateId = Block.BLOCK_STATE_REGISTRY.getId(sourceState);
            if (sourceStateId < 0) {
                continue;
            }

            BlockState placeholderState = placeholderStates.get(stateKey(sourceState));
            if (placeholderState == null) {
                LOGGER.warn(
                        "No matching placeholder state for {} state {} using {}.",
                        sourceId, sourceState, placeholderId
                );
                continue;
            }

            int placeholderSourceId = Block.BLOCK_STATE_REGISTRY.getId(placeholderState);
            if (placeholderSourceId < 0) {
                continue;
            }

            int clientStateId = mappings.getNewId(placeholderSourceId);
            if (clientStateId < 0) {
                LOGGER.warn(
                        "Placeholder state {} has no 26.2 mapping; leaving {} unchanged.",
                        placeholderState, sourceState
                );
                continue;
            }

            mappings.setNewId(sourceStateId, clientStateId);
            changed++;
        }

        return changed;
    }

    private static Block getBlock(String id) {
        ResourceLocation key = ResourceLocation.parse(id);
        return BuiltInRegistries.BLOCK.getValue(key);
    }

    private static String stateKey(BlockState state) {
        StringBuilder result = new StringBuilder();
        for (Property<?> property : state.getProperties()) {
            result.append(property.getName())
                    .append('=')
                    .append(state.getValue(property))
                    .append(';');
        }
        return result.toString();
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unknown wool color: " + value);
    }
}
