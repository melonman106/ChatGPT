package com.melonman106.vbvclient;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;

/**
 * Lightweight client-side explored-chunk world map for Eaglercraft 26.2.
 *
 * A chunk is recorded when the local player enters it. No server-side
 * component is required.
 */
public final class VBVWorldMap {
    private static final Map<String, Set<Long>> EXPLORED = new HashMap<>();
    private static final Map<String, Map<String, VBVWaypoint>> WAYPOINTS = new HashMap<>();

    private VBVWorldMap() {}

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.getCameraEntity() instanceof Player player)) return;
        if (minecraft.level == null) return;

        ChunkPos chunk = new ChunkPos(player.blockPosition());
        exploredSet(worldKey(minecraft), true).add(chunk.toLong());
    }

    public static void open(Screen parent) {
        Minecraft.getInstance().setScreen(new VBVWorldMapScreen(parent));
    }

    public static Set<Long> explored(String world) {
        return Collections.unmodifiableSet(exploredSet(world, false));
    }

    public static Map<String, VBVWaypoint> waypoints(String world) {
        return Collections.unmodifiableMap(WAYPOINTS.getOrDefault(world, Collections.emptyMap()));
    }

    public static void addWaypoint(String world, VBVWaypoint waypoint) {
        WAYPOINTS.computeIfAbsent(world, ignored -> new HashMap<>()).put(waypoint.id(), waypoint);
    }

    public static void removeWaypoint(String world, String id) {
        Map<String, VBVWaypoint> map = WAYPOINTS.get(world);
        if (map != null) map.remove(id);
    }

    public static String worldKey(Minecraft minecraft) {
        if (minecraft.level == null) return "unknown";
        String levelName = minecraft.level.dimension().location().toString();
        String server = minecraft.getCurrentServer() == null
            ? "singleplayer"
            : minecraft.getCurrentServer().ip;
        return server + "|" + levelName;
    }

    private static Set<Long> exploredSet(String world, boolean create) {
        if (create) return EXPLORED.computeIfAbsent(world, ignored -> new HashSet<>());
        return EXPLORED.getOrDefault(world, Collections.emptySet());
    }

    public static int playerBlockX() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player == null ? 0 : BlockPos.containing(minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ()).getX();
    }

    public static int playerBlockZ() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player == null ? 0 : BlockPos.containing(minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ()).getZ();
    }
}
