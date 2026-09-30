package com.melonman106.vbfix;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Eagler-safe visual overlay.
 *
 * The 26.2 client cannot apply a custom per-position predicate to an ordinary
 * block resource-pack model. Keep the real 26.2 stair/slab placeholder for
 * collision, then draw the 26.3 appearance as an item_display entity.
 *
 * The display carries minecraft:item_model, so the resource pack changes only
 * the dedicated display item model. Genuine 26.2 blocks are never overridden.
 */
public final class VbvDisplays {
    private static final String ROOT_TAG = "viabackportvisuals.visual";
    private static final Map<ServerLevel, Map<BlockPos, String>> ACTIVE = new HashMap<>();

    private VbvDisplays() {}

    public static void reset(MinecraftServer server) {
        ACTIVE.clear();
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "kill @e[type=minecraft:item_display,tag=" + ROOT_TAG + "]"
        );
    }

    public static void scanAround(ServerPlayer player, BlockPos center) {
        ServerLevel level = player.level();
        Map<BlockPos, String> active = ACTIVE.computeIfAbsent(level, ignored -> new HashMap<>());
        Set<BlockPos> seen = new HashSet<>();

        int radius = 4;
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int y = center.getY() - radius; y <= center.getY() + radius; y++) {
                for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    String modelId = modelId(state);
                    BlockPos key = pos.immutable();

                    if (modelId == null) {
                        if (active.remove(key) != null) {
                            killAt(level.getServer(), key);
                        }
                        continue;
                    }

                    seen.add(key);
                    String old = active.get(key);
                    if (!modelId.equals(old)) {
                        if (old != null) killAt(level.getServer(), key);
                        summon(level.getServer(), key, modelId);
                        active.put(key, modelId);
                    }
                }
            }
        }

        // Never remove an active display merely because another player's
        // scan cube does not include it. Multiple players can scan the same
        // world from different centers. A display is removed only when a scan
        // actually reaches its position and finds that the block is no longer
        // a VBV visual block.
    }

    private static void summon(MinecraftServer server, BlockPos pos, String modelId) {
        String positionTag = positionTag(pos);
        String modelTag = "vbv_m_" + modelId;
        String command = String.format(Locale.ROOT,
                "summon minecraft:item_display %d.5 %d.5 %d.5 {Tags:[\"%s\",\"%s\"],item:{id:\"minecraft:paper\",count:1,components:{\"minecraft:item_model\":\"viabackportvisuals:display/%s\"}},item_display:\"fixed\",transformation:{translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],scale:[1.01f,1.01f,1.01f],right_rotation:[0f,0f,0f,1f]}}",
                pos.getX(), pos.getY(), pos.getZ(), ROOT_TAG, positionTag, modelTag
        );
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }

    private static void killAt(MinecraftServer server, BlockPos pos) {
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "kill @e[type=minecraft:item_display,tag=" + positionTag(pos) + "]"
        );
    }

    private static String positionTag(BlockPos pos) {
        return "vbv_p_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
    }

    private static String modelId(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();

        if (id.endsWith("_wool_stairs") || id.endsWith("_concrete_stairs")) {
            String family = id.endsWith("_wool_stairs")
                    ? id.substring(0, id.length() - 7)
                    : id.substring(0, id.length() - 7);
            return family + "_stairs_" +
                    value(state, "facing", "north") + "_" +
                    value(state, "half", "bottom") + "_" +
                    value(state, "shape", "straight");
        }

        if (id.endsWith("_wool_slab") || id.endsWith("_concrete_slab")) {
            String family = id.endsWith("_wool_slab")
                    ? id.substring(0, id.length() - "_wool_slab".length())
                    : id.substring(0, id.length() - "_concrete_slab".length());
            return family + "_slab_" + value(state, "type", "bottom");
        }

        return null;
    }

    private static String value(BlockState state, String name, String fallback) {
        return state.getProperties().stream()
                .filter(property -> property.getName().equals(name))
                .map(property -> String.valueOf(state.getValue(property)).toLowerCase(Locale.ROOT))
                .findFirst()
                .orElse(fallback);
    }
}
