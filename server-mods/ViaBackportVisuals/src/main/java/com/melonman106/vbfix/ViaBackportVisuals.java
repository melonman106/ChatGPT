package com.melonman106.vbfix;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.data.MappingData;
import com.viaversion.viaversion.api.data.Mappings;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
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

    private static final String[] WOOL_STAIR_PLACEHOLDERS = {
            "smooth_quartz_stairs", "smooth_red_sandstone_stairs",
            "smooth_sandstone_stairs", "brick_stairs",
            "nether_brick_stairs", "red_nether_brick_stairs",
            "sandstone_stairs", "smooth_stone_stairs",
            "cobbled_deepslate_stairs", "polished_deepslate_stairs",
            "tuff_stairs", "polished_tuff_stairs",
            "granite_stairs", "diorite_stairs",
            "mossy_stone_brick_stairs", "stone_brick_stairs"
    };

    private static final String[] WOOL_SLAB_PLACEHOLDERS = {
            "smooth_quartz_slab", "smooth_red_sandstone_slab",
            "smooth_sandstone_slab", "smooth_stone_slab",
            "brick_slab", "nether_brick_slab",
            "red_nether_brick_slab", "sandstone_slab",
            "granite_slab", "diorite_slab",
            "cobbled_deepslate_slab", "polished_deepslate_slab",
            "tuff_slab", "polished_tuff_slab",
            "mossy_stone_brick_slab", "stone_brick_slab"
    };

    /*
     * Separate 26.2 stair/slab placeholders for 26.3 concrete stairs/slabs.
     * They are deliberately different from the wool placeholders so both
     * families can be rendered at the same time.
     */
    private static final String[] CONCRETE_STAIR_PLACEHOLDERS = {
            "quartz_stairs", "red_sandstone_stairs", "stone_stairs",
            "cobblestone_stairs", "mossy_cobblestone_stairs", "oak_stairs",
            "spruce_stairs", "birch_stairs", "jungle_stairs", "acacia_stairs",
            "dark_oak_stairs", "mangrove_stairs", "cherry_stairs",
            "bamboo_stairs", "crimson_stairs", "warped_stairs"
    };

    private static final String[] CONCRETE_SLAB_PLACEHOLDERS = {
            "quartz_slab", "red_sandstone_slab", "stone_slab",
            "cobblestone_slab", "mossy_cobblestone_slab", "oak_slab",
            "spruce_slab", "birch_slab", "jungle_slab", "acacia_slab",
            "dark_oak_slab", "mangrove_slab", "cherry_slab",
            "bamboo_slab", "crimson_slab", "warped_slab"
    };

    /*
     * New 26.3 blocks whose old client can render using a same-shape 26.2
     * placeholder. The companion resource pack changes the placeholder's
     * model to the 26.3 appearance supplied by ViaBackwards-Plus.
     */
    private static final String[][] BLOCK_MAPPINGS = {
            {"poplar_log", "birch_log"},
            {"stripped_poplar_log", "stripped_birch_log"},
            {"poplar_wood", "birch_wood"},
            {"stripped_poplar_wood", "stripped_birch_wood"},
            {"poplar_planks", "birch_planks"},
            {"poplar_stairs", "birch_stairs"},
            {"poplar_slab", "birch_slab"},
            {"poplar_fence", "birch_fence"},
            {"poplar_fence_gate", "birch_fence_gate"},
            {"poplar_door", "birch_door"},
            {"poplar_trapdoor", "birch_trapdoor"},
            {"poplar_button", "birch_button"},
            {"poplar_pressure_plate", "birch_pressure_plate"},
            {"poplar_sign", "birch_sign"},
            {"poplar_wall_sign", "birch_wall_sign"},
            {"poplar_hanging_sign", "birch_hanging_sign"},
            {"poplar_wall_hanging_sign", "birch_wall_hanging_sign"},
            {"poplar_sapling", "birch_sapling"},
            {"red_poplar_leaves", "oak_leaves"},
            {"orange_poplar_leaves", "oak_leaves"},
            {"yellow_poplar_leaves", "oak_leaves"},
            {"red_shrub", "dead_bush"},
            {"shelf_mushroom", "brown_mushroom"},
            {"white_cushion", "white_carpet"},
            {"orange_cushion", "orange_carpet"},
            {"magenta_cushion", "magenta_carpet"},
            {"light_blue_cushion", "light_blue_carpet"},
            {"yellow_cushion", "yellow_carpet"},
            {"lime_cushion", "lime_carpet"},
            {"pink_cushion", "pink_carpet"},
            {"gray_cushion", "gray_carpet"},
            {"light_gray_cushion", "light_gray_carpet"},
            {"cyan_cushion", "cyan_carpet"},
            {"purple_cushion", "purple_carpet"},
            {"blue_cushion", "blue_carpet"},
            {"brown_cushion", "brown_carpet"},
            {"green_cushion", "green_carpet"},
            {"red_cushion", "red_carpet"},
            {"black_cushion", "black_carpet"}
    };

    private static final String STRAW_BED_PLACEHOLDER = "black_bed";

    @Override
    public void onInitialize() {
        LOGGER.info("ViaBackportVisuals loaded for Minecraft 26.3.");
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

            int woolStairs = 0;
            int woolSlabs = 0;
            int concreteStairs = 0;
            int concreteSlabs = 0;
            int otherBlocks = 0;
            int strawBed = 0;

            for (int i = 0; i < WOOL_COLORS.length; i++) {
                String color = WOOL_COLORS[i];
                woolStairs += remapBlockStates(
                        mappings,
                        "minecraft:" + color + "_wool_stairs",
                        "minecraft:" + WOOL_STAIR_PLACEHOLDERS[i]
                );
                woolSlabs += remapBlockStates(
                        mappings,
                        "minecraft:" + color + "_wool_slab",
                        "minecraft:" + WOOL_SLAB_PLACEHOLDERS[i]
                );
            }

            String[] concreteColors = WOOL_COLORS;
            for (int i = 0; i < concreteColors.length; i++) {
                String color = concreteColors[i];
                concreteStairs += remapBlockStates(
                        mappings,
                        "minecraft:" + color + "_concrete_stairs",
                        "minecraft:" + CONCRETE_STAIR_PLACEHOLDERS[i]
                );
                concreteSlabs += remapBlockStates(
                        mappings,
                        "minecraft:" + color + "_concrete_slab",
                        "minecraft:" + CONCRETE_SLAB_PLACEHOLDERS[i]
                );
            }

            for (String[] mapping : BLOCK_MAPPINGS) {
                otherBlocks += remapBlockStates(
                        mappings,
                        "minecraft:" + mapping[0],
                        "minecraft:" + mapping[1]
                );
            }

            strawBed = remapBlockStates(
                    mappings,
                    "minecraft:straw_bed",
                    "minecraft:" + STRAW_BED_PLACEHOLDER
            );

            LOGGER.info(
                    "Installed ViaBackportVisuals mappings: wool stairs={}, wool slabs={}, concrete stairs={}, concrete slabs={}, other 26.3 blocks={}, straw bed={}.",
                    woolStairs, woolSlabs, concreteStairs, concreteSlabs, otherBlocks, strawBed
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

            int placeholderStateId = Block.BLOCK_STATE_REGISTRY.getId(placeholderState);
            if (placeholderStateId < 0) {
                continue;
            }

            int clientStateId = mappings.getNewId(placeholderStateId);
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
        return BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
    }

    /*
     * Property order is not guaranteed to be identical between two blocks.
     * Sorting by property name prevents false mismatches such as the previous
     * gray wool -> smooth stone stair failure.
     */
    private static String stateKey(BlockState state) {
        List<Property<?>> properties = new ArrayList<>(state.getProperties());
        properties.sort(Comparator.comparing(Property::getName));

        StringBuilder result = new StringBuilder();
        for (Property<?> property : properties) {
            result.append(property.getName())
                    .append('=')
                    .append(state.getValue(property))
                    .append(';');
        }
        return result.toString();
    }
}
