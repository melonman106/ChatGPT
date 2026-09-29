package com.melonman106.vbfix;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.data.MappingData;
import com.viaversion.viaversion.api.data.Mappings;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

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
            "waxed_cut_copper_stairs", "waxed_exposed_cut_copper_stairs",
            "waxed_weathered_cut_copper_stairs", "waxed_oxidized_cut_copper_stairs",
            "cut_copper_stairs", "exposed_cut_copper_stairs",
            "weathered_cut_copper_stairs", "oxidized_cut_copper_stairs",
            "mud_brick_stairs", "tuff_brick_stairs",
            "polished_tuff_stairs", "bamboo_mosaic_stairs",
            "end_stone_brick_stairs", "resin_brick_stairs",
            "cinnabar_brick_stairs", "sulfur_brick_stairs"
    };

    private static final String[] WOOL_SLAB_PLACEHOLDERS = {
            "waxed_cut_copper_slab", "waxed_exposed_cut_copper_slab",
            "waxed_weathered_cut_copper_slab", "waxed_oxidized_cut_copper_slab",
            "cut_copper_slab", "exposed_cut_copper_slab",
            "weathered_cut_copper_slab", "oxidized_cut_copper_slab",
            "mud_brick_slab", "tuff_brick_slab",
            "polished_tuff_slab", "bamboo_mosaic_slab",
            "end_stone_brick_slab", "resin_brick_slab",
            "cinnabar_brick_slab", "sulfur_brick_slab"
    };

    private static final String[] CONCRETE_STAIR_PLACEHOLDERS = {
            "pale_oak_stairs", "cut_copper_stairs", "exposed_cut_copper_stairs",
            "weathered_cut_copper_stairs", "oxidized_cut_copper_stairs",
            "waxed_cut_copper_stairs", "waxed_exposed_cut_copper_stairs",
            "waxed_weathered_cut_copper_stairs", "waxed_oxidized_cut_copper_stairs",
            "mud_brick_stairs", "tuff_brick_stairs", "polished_tuff_stairs",
            "bamboo_mosaic_stairs", "resin_brick_stairs", "cinnabar_brick_stairs",
            "sulfur_brick_stairs", "end_stone_brick_stairs"
    };

    private static final String[] CONCRETE_SLAB_PLACEHOLDERS = {
            "pale_oak_slab", "cut_copper_slab", "exposed_cut_copper_slab",
            "weathered_cut_copper_slab", "oxidized_cut_copper_slab",
            "waxed_cut_copper_slab", "waxed_exposed_cut_copper_slab",
            "waxed_weathered_cut_copper_slab", "waxed_oxidized_cut_copper_slab",
            "mud_brick_slab", "tuff_brick_slab", "polished_tuff_slab",
            "bamboo_mosaic_slab", "resin_brick_slab", "cinnabar_brick_slab",
            "sulfur_brick_slab", "end_stone_brick_slab"
    };

    /*
     * These are the non-marker 26.3 blocks. Leaves are intentionally NOT in
     * this list: their vanilla placeholder states are shared with real trees,
     * so they use a rare marker state installed below.
     */
    private static final String[][] BLOCK_MAPPINGS = {
            {"poplar_log", "birch_log"},
            {"stripped_poplar_log", "stripped_birch_log"},
            {"poplar_wood", "birch_wood"},
            {"stripped_poplar_wood", "stripped_birch_wood"},
            {"poplar_planks", "birch_planks"},
            {"poplar_stairs", "prismarine_stairs"},
            {"poplar_slab", "prismarine_slab"},
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
            {"red_shrub", "dead_bush"},
            {"shelf_mushroom", "brown_mushroom"},
    };

    private static final String STRAW_BED_PLACEHOLDER = "yellow_bed";

    private static final Map<Integer, Integer> ORIGINAL_MAPPINGS = new HashMap<>();
    static final Map<Integer, BlockState> PLACEHOLDER_OF = new HashMap<>();
    static final Map<Integer, Integer> APPLIED = new HashMap<>();

    private static Mappings activeMappings;
    private static volatile boolean mappingsEnabled = true;

    @Override
    public void onInitialize() {
        LOGGER.info("ViaBackportVisuals loaded for Minecraft 26.3.");

        ServerLifecycleEvents.SERVER_STARTED.register(server -> installMappings());

        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (!mappingsEnabled || !(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            VbvMining.apply(serverPlayer, level.getBlockState(pos), pos);
            return InteractionResult.PASS;
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                VbvMining.clear(handler.getPlayer()));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("vbv")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
                        .then(Commands.literal("disable")
                                .executes(context -> disableMappings(context.getSource())))
                        .then(Commands.literal("enable")
                                .executes(context -> enableMappings(context.getSource())))
                        .then(Commands.literal("status")
                                .executes(context -> status(context.getSource())))
                        .then(Commands.literal("dump")
                                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                                        .argument("block", StringArgumentType.word())
                                        .executes(context -> dump(context.getSource(),
                                                StringArgumentType.getString(context, "block")))))));
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
            activeMappings = mappings;
            ORIGINAL_MAPPINGS.clear();
            APPLIED.clear();
            PLACEHOLDER_OF.clear();

            int woolStairs = 0;
            int woolSlabs = 0;
            int concreteStairs = 0;
            int concreteSlabs = 0;
            int otherBlocks = 0;
            int strawBed = 0;
            int leaves = 0;

            for (int i = 0; i < WOOL_COLORS.length; i++) {
                String color = WOOL_COLORS[i];
                woolStairs += remapBlockStates(mappings,
                        "minecraft:" + color + "_wool_stairs",
                        "minecraft:" + WOOL_STAIR_PLACEHOLDERS[i]);
                woolSlabs += remapBlockStates(mappings,
                        "minecraft:" + color + "_wool_slab",
                        "minecraft:" + WOOL_SLAB_PLACEHOLDERS[i]);
            }

            for (int i = 0; i < WOOL_COLORS.length; i++) {
                String color = WOOL_COLORS[i];
                concreteStairs += remapBlockStates(mappings,
                        "minecraft:" + color + "_concrete_stairs",
                        "minecraft:" + CONCRETE_STAIR_PLACEHOLDERS[i]);
                concreteSlabs += remapBlockStates(mappings,
                        "minecraft:" + color + "_concrete_slab",
                        "minecraft:" + CONCRETE_SLAB_PLACEHOLDERS[i]);
            }

            for (String[] mapping : BLOCK_MAPPINGS) {
                otherBlocks += remapBlockStates(mappings,
                        "minecraft:" + mapping[0],
                        "minecraft:" + mapping[1]);
            }

            leaves += remapBlockStates(mappings, "minecraft:red_poplar_leaves",
                    "minecraft:oak_leaves", ViaBackportVisuals::redPoplarLeafMarker);
            leaves += remapBlockStates(mappings, "minecraft:orange_poplar_leaves",
                    "minecraft:spruce_leaves", ViaBackportVisuals::orangePoplarLeafMarker);
            leaves += remapBlockStates(mappings, "minecraft:yellow_poplar_leaves",
                    "minecraft:jungle_leaves", ViaBackportVisuals::yellowPoplarLeafMarker);

            strawBed = remapBlockStates(mappings, "minecraft:straw_bed",
                    "minecraft:" + STRAW_BED_PLACEHOLDER, ViaBackportVisuals::strawBedMarker);

            mappingsEnabled = true;

            LOGGER.info(
                    "Installed ViaBackportVisuals mappings: wool stairs={}, wool slabs={}, concrete stairs={}, concrete slabs={}, other={}, leaves={}, straw bed={}.",
                    woolStairs, woolSlabs, concreteStairs, concreteSlabs, otherBlocks, leaves, strawBed
            );
        } catch (Throwable t) {
            LOGGER.error("Failed to install ViaBackportVisuals mappings.", t);
        }
    }

    private static int remapBlockStates(Mappings mappings, String sourceId, String placeholderId) {
        return remapBlockStates(mappings, sourceId, placeholderId, null);
    }

    private static int remapBlockStates(Mappings mappings, String sourceId, String placeholderId,
                                        BiFunction<BlockState, BlockState, BlockState> picker) {
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
            if (sourceStateId < 0) continue;

            BlockState placeholderState = picker != null
                    ? picker.apply(sourceState, placeholder.defaultBlockState())
                    : placeholderStates.get(stateKey(sourceState));

            if (placeholderState == null) {
                LOGGER.warn("No matching placeholder state for {} state {} using {}.",
                        sourceId, sourceState, placeholderId);
                continue;
            }

            int placeholderStateId = Block.BLOCK_STATE_REGISTRY.getId(placeholderState);
            if (placeholderStateId < 0) continue;

            int clientStateId = mappings.getNewId(placeholderStateId);
            if (clientStateId < 0) {
                LOGGER.warn("Placeholder state {} has no 26.2 mapping; leaving {} unchanged.",
                        placeholderState, sourceState);
                continue;
            }

            ORIGINAL_MAPPINGS.putIfAbsent(sourceStateId, mappings.getNewId(sourceStateId));
            mappings.setNewId(sourceStateId, clientStateId);
            APPLIED.put(sourceStateId, clientStateId);
            PLACEHOLDER_OF.put(sourceStateId, placeholderState);
            changed++;
        }

        return changed;
    }

    private static BlockState redPoplarLeafMarker(BlockState src, BlockState ph) {
        return ph.setValue(LeavesBlock.DISTANCE, 7)
                .setValue(LeavesBlock.PERSISTENT, false)
                .setValue(LeavesBlock.WATERLOGGED, src.getValue(LeavesBlock.WATERLOGGED));
    }

    private static BlockState orangePoplarLeafMarker(BlockState src, BlockState ph) {
        return redPoplarLeafMarker(src, ph);
    }

    private static BlockState yellowPoplarLeafMarker(BlockState src, BlockState ph) {
        return redPoplarLeafMarker(src, ph);
    }

    private static BlockState strawBedMarker(BlockState src, BlockState ph) {
        return ph.setValue(BedBlock.FACING, src.getValue(BedBlock.FACING))
                .setValue(BedBlock.PART, src.getValue(BedBlock.PART))
                .setValue(BedBlock.OCCUPIED, true);
    }

    private static int disableMappings(net.minecraft.commands.CommandSourceStack source) {
        if (activeMappings == null) {
            source.sendFailure(Component.literal("ViaBackportVisuals has not installed its mappings yet."));
            return 0;
        }
        for (Map.Entry<Integer, Integer> entry : ORIGINAL_MAPPINGS.entrySet()) {
            activeMappings.setNewId(entry.getKey(), entry.getValue());
        }
        mappingsEnabled = false;
        VbvMining.clearAll(source.getServer().getPlayerList().getPlayers());
        source.sendSuccess(() -> Component.literal(
                "ViaBackportVisuals mappings disabled. Reconnect clients to refresh chunk visuals."), true);
        return 1;
    }

    private static int enableMappings(net.minecraft.commands.CommandSourceStack source) {
        if (activeMappings == null) {
            installMappings();
        } else {
            for (Map.Entry<Integer, Integer> entry : APPLIED.entrySet()) {
                activeMappings.setNewId(entry.getKey(), entry.getValue());
            }
            mappingsEnabled = true;
        }
        source.sendSuccess(() -> Component.literal(
                "ViaBackportVisuals mappings enabled. Reconnect clients to refresh chunk visuals."), true);
        return 1;
    }

    private static int status(net.minecraft.commands.CommandSourceStack source) {
        long applied = APPLIED.entrySet().stream()
                .filter(e -> activeMappings != null && activeMappings.getNewId(e.getKey()) != ORIGINAL_MAPPINGS.get(e.getKey()))
                .count();
        source.sendSuccess(() -> Component.literal(
                "ViaBackportVisuals mappings are " + (mappingsEnabled ? "enabled" : "disabled") +
                        " (" + applied + "/" + APPLIED.size() + " applied states; " +
                        ORIGINAL_MAPPINGS.size() + " original states tracked)."), false);
        return 1;
    }

    private static int dump(net.minecraft.commands.CommandSourceStack source, String id) {
        if (!id.contains(":")) id = "minecraft:" + id;
        final String dumpId = id;
        Block block = getBlock(dumpId);
        if (block == null) {
            source.sendFailure(Component.literal("Unknown block: " + dumpId));
            return 0;
        }
        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            int stateId = Block.BLOCK_STATE_REGISTRY.getId(state);
            int original = ORIGINAL_MAPPINGS.getOrDefault(stateId, Integer.MIN_VALUE);
            int current = activeMappings == null ? Integer.MIN_VALUE : activeMappings.getNewId(stateId);
            source.sendSuccess(() -> Component.literal(
                    dumpId + " stateId=" + stateId + " state=" + state +
                            " original=" + original + " current=" + current), false);
        }
        return 1;
    }

    public static boolean isMappingsEnabled() {
        return mappingsEnabled;
    }

    public static BlockState getPlaceholderState(BlockState state) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        return PLACEHOLDER_OF.get(id);
    }

    public static BlockState getMiningReferenceState(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String referenceId = null;

        if (id.endsWith("_wool_stairs") || id.endsWith("_wool_slab")) {
            referenceId = "minecraft:white_wool";
        } else if (id.endsWith("_concrete_stairs") || id.endsWith("_concrete_slab")) {
            referenceId = "minecraft:white_concrete";
        } else if (id.equals("minecraft:straw_bed")) {
            referenceId = "minecraft:white_bed";
        } else if (id.startsWith("minecraft:poplar_")) {
            String suffix = id.substring("minecraft:poplar_".length());
            referenceId = "minecraft:birch_" + suffix;
        } else if (id.equals("minecraft:stripped_poplar_log") || id.equals("minecraft:stripped_poplar_wood")) {
            referenceId = "minecraft:stripped_birch_" +
                    id.substring("minecraft:stripped_poplar_".length());
        } else if (id.equals("minecraft:red_poplar_leaves") || id.equals("minecraft:orange_poplar_leaves")
                || id.equals("minecraft:yellow_poplar_leaves")) {
            referenceId = "minecraft:oak_leaves";
        } else if (id.equals("minecraft:red_shrub")) {
            referenceId = "minecraft:dead_bush";
        } else if (id.equals("minecraft:shelf_mushroom")) {
            referenceId = "minecraft:brown_mushroom";
        }

        if (referenceId == null) return null;
        Block reference = getBlock(referenceId);
        return reference == null ? null : reference.defaultBlockState();
    }

    private static Block getBlock(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
    }

    private static String stateKey(BlockState state) {
        List<Property<?>> properties = new ArrayList<>(state.getProperties());
        properties.sort(Comparator.comparing(Property::getName));
        StringBuilder result = new StringBuilder();
        for (Property<?> property : properties) {
            result.append(property.getName()).append('=')
                    .append(state.getValue(property)).append(';');
        }
        return result.toString();
    }
}
