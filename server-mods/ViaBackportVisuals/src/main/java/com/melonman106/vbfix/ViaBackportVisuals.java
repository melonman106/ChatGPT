package com.melonman106.vbfix;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.data.MappingData;
import com.viaversion.viaversion.api.data.Mappings;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viabackwards.api.data.MappedItem;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;\nimport net.fabricmc.fabric.api.event.player.UseItemOnCallback;\nimport net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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

// Regression-tested placeholder palette: wool and concrete sets are disjoint.
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
            "waxed_cut_copper_stairs",
            "waxed_exposed_cut_copper_stairs",
            "waxed_weathered_cut_copper_stairs",
            "waxed_oxidized_cut_copper_stairs",
            "cut_copper_stairs",
            "exposed_cut_copper_stairs",
            "weathered_cut_copper_stairs",
            "oxidized_cut_copper_stairs",
            "mud_brick_stairs",
            "tuff_brick_stairs",
            "polished_tuff_stairs",
            "bamboo_mosaic_stairs",
            "end_stone_brick_stairs",
            "resin_brick_stairs",
            "cinnabar_brick_stairs",
            "sulfur_brick_stairs"
    };

    private static final String[] WOOL_SLAB_PLACEHOLDERS = {
            "waxed_cut_copper_slab",
            "waxed_exposed_cut_copper_slab",
            "waxed_weathered_cut_copper_slab",
            "waxed_oxidized_cut_copper_slab",
            "cut_copper_slab",
            "exposed_cut_copper_slab",
            "weathered_cut_copper_slab",
            "oxidized_cut_copper_slab",
            "mud_brick_slab",
            "tuff_brick_slab",
            "polished_tuff_slab",
            "bamboo_mosaic_slab",
            "end_stone_brick_slab",
            "resin_brick_slab",
            "cinnabar_brick_slab",
            "sulfur_brick_slab"
    };

    private static final String[] CONCRETE_STAIR_PLACEHOLDERS = {
            "pale_oak_stairs", "deepslate_brick_stairs", "deepslate_tile_stairs",
            "polished_deepslate_stairs", "polished_blackstone_brick_stairs",
            "polished_blackstone_stairs", "blackstone_stairs", "cobbled_deepslate_stairs",
            "prismarine_brick_stairs", "dark_prismarine_stairs", "purpur_stairs",
            "nether_brick_stairs", "red_nether_brick_stairs",
            "mossy_stone_brick_stairs", "stone_brick_stairs", "brick_stairs"
    };

    private static final String[] CONCRETE_SLAB_PLACEHOLDERS = {
            "pale_oak_slab", "deepslate_brick_slab", "deepslate_tile_slab",
            "polished_deepslate_slab", "polished_blackstone_brick_slab",
            "polished_blackstone_slab", "blackstone_slab", "cobbled_deepslate_slab",
            "prismarine_brick_slab", "dark_prismarine_slab", "purpur_slab",
            "nether_brick_slab", "red_nether_brick_slab",
            "mossy_stone_brick_slab", "stone_brick_slab", "brick_slab"
    };
    /*
     * These are the non-marker 26.3 blocks. Leaves are intentionally NOT in
     * this list: their vanilla placeholder states are shared with real trees,
     * so they use a rare marker state installed below.
     */
    private static final String[][] BLOCK_MAPPINGS = {
            {"poplar_log", "cherry_log"},
            {"stripped_poplar_log", "stripped_cherry_log"},
            {"poplar_wood", "cherry_wood"},
            {"stripped_poplar_wood", "stripped_cherry_wood"},
            {"poplar_planks", "cherry_planks"},
            {"poplar_stairs", "prismarine_stairs"},
            {"poplar_slab", "prismarine_slab"},
            {"poplar_fence", "cherry_fence"},
            {"poplar_fence_gate", "cherry_fence_gate"},
            {"poplar_door", "cherry_door"},
            {"poplar_trapdoor", "cherry_trapdoor"},
            {"poplar_button", "cherry_button"},
            {"poplar_pressure_plate", "cherry_pressure_plate"},
            {"poplar_sign", "cherry_sign"},
            {"poplar_wall_sign", "cherry_wall_sign"},
            {"poplar_hanging_sign", "cherry_hanging_sign"},
            {"poplar_wall_hanging_sign", "cherry_wall_hanging_sign"},
            {"poplar_sapling", "cherry_sapling"},
            {"red_shrub", "dead_bush"},
            {"shelf_mushroom", "brown_mushroom"},
    };

    private static final String STRAW_BED_PLACEHOLDER = "yellow_bed";

    private static final String[] STAIR_FACINGS = {"east","north","south","west"};
    private static final String[] STAIR_HALVES = {"bottom","top"};
    private static final String[] STAIR_SHAPES = {"inner_left","inner_right","outer_left","outer_right","straight"};
    private static final String[] SLAB_TYPES = {"bottom","double","top"};


    private static final Map<Integer, Integer> ORIGINAL_MAPPINGS = new HashMap<>();
    static final Map<Integer, BlockState> PLACEHOLDER_OF = new HashMap<>();
    static final Map<Integer, Integer> APPLIED = new HashMap<>();

    private static Mappings activeMappings;
    private static volatile boolean mappingsEnabled = true;

    @Override
    public void onInitialize() {
        LOGGER.info("ViaBackportVisuals loaded for Minecraft 26.3.");

        VbvMarkers.registerPayload();\n        ServerLifecycleEvents.SERVER_STARTED.register(server -> installMappings());\n\n        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->\n                VbvMarkers.scanAround(handler.getPlayer(), handler.getPlayer().blockPosition()));\n\n        UseItemOnCallback.EVENT.register((player, level, hand, hitResult) -> {\n            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {\n                serverPlayer.server.execute(() -> VbvMarkers.scanAround(serverPlayer, hitResult.getBlockPos()));\n            }\n            return InteractionResult.PASS;\n        });\n\n        ServerTickEvents.END_SERVER_TICK.register(server -> {\n            if (server.getTickCount() % 20 != 0) return;\n            for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {\n                VbvMarkers.scanAround(player, player.blockPosition());\n            }\n        });

        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (!mappingsEnabled || !(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            VbvMining.apply(serverPlayer, level.getBlockState(pos), pos);
            return InteractionResult.PASS;
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                VbvMining.clear(handler.getPlayer());\n                VbvMarkers.clear(handler.getPlayer()));

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
                                .then(RequiredArgumentBuilder.<net.minecraft.commands.CommandSourceStack, String>argument("block", StringArgumentType.word())
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

            /*
             * Use real 26.2 stair/slab placeholders so the client receives the
             * correct vanilla collision geometry: stairs stay stairs and slabs
             * stay slabs. Each colour uses a separate existing 26.2 block.
             */
            LOGGER.info("Installing real stair/slab placeholders for wool and concrete.");
            for (int i = 0; i < WOOL_COLORS.length; i++) {
                String color = WOOL_COLORS[i];
                woolStairs += remapBlockStates(mappings, "minecraft:" + color + "_wool_stairs", "minecraft:" + WOOL_STAIR_PLACEHOLDERS[i]);
                woolSlabs += remapBlockStates(mappings, "minecraft:" + color + "_wool_slab", "minecraft:" + WOOL_SLAB_PLACEHOLDERS[i]);
                concreteStairs += remapBlockStates(mappings, "minecraft:" + color + "_concrete_stairs", "minecraft:" + CONCRETE_STAIR_PLACEHOLDERS[i]);
                concreteSlabs += remapBlockStates(mappings, "minecraft:" + color + "_concrete_slab", "minecraft:" + CONCRETE_SLAB_PLACEHOLDERS[i]);
            }
            for (String[] mapping : BLOCK_MAPPINGS) {
                otherBlocks += remapBlockStates(mappings,
                        "minecraft:" + mapping[0],
                        "minecraft:" + mapping[1]);
            }

            /*
             * Do not rewrite poplar leaves to oak/spruce/jungle states. Those are
             * legitimate 26.2 blocks and their state/model files are shared with
             * real trees. ViaBackwards/ViaBackwards-Plus supplies the poplar leaf
             * visual mapping without corrupting genuine leaf blocks.
             */
            LOGGER.info("Leaving ViaBackwards poplar-leaf state mappings untouched.");

            /*
             * Straw beds also get an isolated marker. The client patch can turn the
             * reserved redstone states back into the real bed shape while the server
             * continues to store minecraft:straw_bed.
             */
            strawBed += remapBlockStates(mappings, "minecraft:straw_bed", "minecraft:yellow_bed");

            mappingsEnabled = true;

            MappedItem strawBedItem = ViaBackwardsItemBridge.findMappedItem("minecraft:straw_bed");
            if (strawBedItem != null) {
                LOGGER.info(
                        "ViaBackwards item identity verified: minecraft:straw_bed -> 26.2 item id {} with custom_model_data {}. " +
                                "The companion pack can safely select it by the injected original identifier.",
                        strawBedItem.id(), strawBedItem.customModelData()
                );
            } else {
                LOGGER.warn(
                        "ViaBackwards did not expose a mapped-item entry for minecraft:straw_bed. " +
                                "Item identity checks will not be available until ViaBackwards mapping data is loaded."
                );
            }

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

    private static BlockState markerState(BlockState src, BlockState placeholder) { return placeholder; }

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
            referenceId = "minecraft:cherry_" + suffix;
        } else if (id.equals("minecraft:stripped_poplar_log") || id.equals("minecraft:stripped_poplar_wood")) {
            referenceId = "minecraft:stripped_cherry_" +
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
