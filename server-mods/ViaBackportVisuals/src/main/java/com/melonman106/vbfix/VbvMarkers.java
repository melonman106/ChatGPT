package com.melonman106.vbfix;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class VbvMarkers {
    private static final int SCAN_RADIUS = 4;
    private static final Map<UUID, Map<BlockPos, Integer>> SENT = new HashMap<>();

    private VbvMarkers() {}

    public static void registerPayload() {
        PayloadTypeRegistry.clientboundPlay().register(VbvMarkerPayload.TYPE, VbvMarkerPayload.CODEC);
    }

    public static void clear(ServerPlayer player) {
        SENT.remove(player.getUUID());
    }

    public static void send(ServerPlayer player, BlockPos pos, int visualId) {
        if (!ServerPlayNetworking.canSend(player, VbvMarkerPayload.TYPE)) return;
        SENT.computeIfAbsent(player.getUUID(), ignored -> new HashMap<>()).put(pos.immutable(), visualId);
        ServerPlayNetworking.send(player, new VbvMarkerPayload(pos, visualId, false));
    }

    public static void remove(ServerPlayer player, BlockPos pos) {
        Map<BlockPos, Integer> map = SENT.get(player.getUUID());
        if (map != null) map.remove(pos);
        if (ServerPlayNetworking.canSend(player, VbvMarkerPayload.TYPE)) {
            ServerPlayNetworking.send(player, new VbvMarkerPayload(pos, 0, true));
        }
    }

    public static void scanAround(ServerPlayer player, BlockPos center) {
        if (!ServerPlayNetworking.canSend(player, VbvMarkerPayload.TYPE)) return;

        Map<BlockPos, Integer> known = SENT.computeIfAbsent(player.getUUID(), ignored -> new HashMap<>());
        Set<BlockPos> seen = new HashSet<>();

        for (int x = center.getX() - SCAN_RADIUS; x <= center.getX() + SCAN_RADIUS; x++) {
            for (int y = center.getY() - SCAN_RADIUS; y <= center.getY() + SCAN_RADIUS; y++) {
                for (int z = center.getZ() - SCAN_RADIUS; z <= center.getZ() + SCAN_RADIUS; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    int visual = visualId(player.level().getBlockState(pos));
                    if (visual >= 0) {
                        BlockPos key = pos.immutable();
                        seen.add(key);
                        Integer old = known.get(key);
                        if (old == null || old != visual) {
                            known.put(key, visual);
                            ServerPlayNetworking.send(player, new VbvMarkerPayload(key, visual, false));
                        }
                    }
                }
            }
        }

        for (BlockPos pos : Set.copyOf(known.keySet())) {
            if (pos.distManhattan(center) <= SCAN_RADIUS * 3 && !seen.contains(pos)) {
                known.remove(pos);
                ServerPlayNetworking.send(player, new VbvMarkerPayload(pos, 0, true));
            }
        }
    }

    public static int visualId(BlockState state) {
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (blockId.startsWith("minecraft:")) blockId = blockId.substring("minecraft:".length());

        if (blockId.endsWith("_wool_stairs")) {
            int i = colorIndex(blockId.substring(0, blockId.length() - "_wool_stairs".length()));
            return i < 0 ? -1 : i;
        }
        if (blockId.endsWith("_wool_slab")) {
            int i = colorIndex(blockId.substring(0, blockId.length() - "_wool_slab".length()));
            return i < 0 ? -1 : 16 + i;
        }
        if (blockId.endsWith("_concrete_stairs")) {
            int i = colorIndex(blockId.substring(0, blockId.length() - "_concrete_stairs".length()));
            return i < 0 ? -1 : 32 + i;
        }
        if (blockId.endsWith("_concrete_slab")) {
            int i = colorIndex(blockId.substring(0, blockId.length() - "_concrete_slab".length()));
            return i < 0 ? -1 : 48 + i;
        }
        if (blockId.equals("straw_bed")) return 64;
        return -1;
    }

    private static int colorIndex(String color) {
        return switch (color) {
            case "white" -> 0; case "orange" -> 1; case "magenta" -> 2; case "light_blue" -> 3;
            case "yellow" -> 4; case "lime" -> 5; case "pink" -> 6; case "gray" -> 7;
            case "light_gray" -> 8; case "cyan" -> 9; case "purple" -> 10; case "blue" -> 11;
            case "brown" -> 12; case "green" -> 13; case "red" -> 14; case "black" -> 15;
            default -> -1;
        };
    }
}
