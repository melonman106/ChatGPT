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

/**
 * Lightweight client-side explored-chunk world map for Eaglercraft 26.2.
 * A chunk is recorded when the local player enters it. No server component is required.
 */
public final class VBVWorldMap {
    private static final Map<String, Set<Long>> EXPLORED = new HashMap<>();
    private static final Map<String, Map<String, VBVWaypoint>> WAYPOINTS = new HashMap<>();

    private VBVWorldMap() {}

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.getCameraEntity() instanceof Player player) || minecraft.level == null) return;

        int chunkX = Math.floorDiv(BlockPos.containing(player.getX(), player.getY(), player.getZ()).getX(), 16);
        int chunkZ = Math.floorDiv(BlockPos.containing(player.getX(), player.getY(), player.getZ()).getZ(), 16);
        exploredSet(worldKey(minecraft), true).add(pack(chunkX, chunkZ));
    }

    public static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    public static int chunkX(long packed) {
        return (int) (packed >> 32);
    }

    public static int chunkZ(long packed) {
        return (int) packed;
    }

    public static void open(Screen parent) {
        VBVNav.open(new VBVWorldMapScreen(parent));
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
        String levelName = minecraft.level.dimension().toString();
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
        return minecraft.player == null ? 0 : (int) minecraft.player.getX();
    }

    public static int playerBlockZ() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player == null ? 0 : (int) minecraft.player.getZ();
    }
}
