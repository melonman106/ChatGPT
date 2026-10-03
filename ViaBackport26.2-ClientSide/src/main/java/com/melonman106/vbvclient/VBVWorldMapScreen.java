package com.melonman106.vbvclient;

import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * Native fullscreen explored-chunk map.
 */
public final class VBVWorldMapScreen extends Screen {
    private final Screen parent;
    private double centerX;
    private double centerZ;
    private double zoom = 12.0D;

    public VBVWorldMapScreen(Screen parent) {
        super(Component.literal("World Map"));
        this.parent = parent;
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        this.centerX = player == null ? 0 : player.getX();
        this.centerZ = player == null ? 0 : player.getZ();
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(Component.literal("Mark Location"), button -> {
            VBVWaypointScreen.openCreate(this);
        }).bounds(8, 8, 120, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Waypoints"), button -> {
            VBVNav.open(new VBVWaypointsScreen(this));
        }).bounds(134, 8, 90, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Back"), button -> {
            VBVNav.open(parent);
        }).bounds(width - 108, 8, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("-"), button -> zoom = Math.max(2.0D, zoom / 1.35D))
            .bounds(width / 2 - 50, height - 30, 40, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+"), button -> zoom = Math.min(80.0D, zoom * 1.35D))
            .bounds(width / 2 + 10, height - 30, 40, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF101010);

        Minecraft minecraft = Minecraft.getInstance();
        String world = VBVWorldMap.worldKey(minecraft);
        int centerChunkX = (int) Math.floor(centerX / 16.0D);
        int centerChunkZ = (int) Math.floor(centerZ / 16.0D);
        int chunkPixels = Math.max(3, (int) Math.round(zoom));

        for (long packed : VBVWorldMap.explored(world)) {
            int cx = (int) packed;
            int cz = (int) (packed >> 32);
            int x = width / 2 + (cx - centerChunkX) * chunkPixels;
            int z = height / 2 + (cz - centerChunkZ) * chunkPixels;
            if (x + chunkPixels < 0 || x > width || z + chunkPixels < 28 || z > height - 36) continue;
            graphics.fill(x, z, x + chunkPixels - 1, z + chunkPixels - 1, 0xFF4B6B45);
        }

        for (VBVWaypoint waypoint : VBVWorldMap.waypoints(world).values()) {
            int x = width / 2 + (int) Math.round((waypoint.x / 16.0D - centerChunkX) * chunkPixels);
            int z = height / 2 + (int) Math.round((waypoint.z / 16.0D - centerChunkZ) * chunkPixels);
            graphics.fill(x - 3, z - 3, x + 4, z + 4, 0xFFE0B020);
            graphics.text(minecraft.font, waypoint.name(), x + 6, z - 4, 0xFFFFFFFF, true);
        }

        if (minecraft.player != null) {
            int px = width / 2 + (int) Math.round((minecraft.player.getX() / 16.0D - centerChunkX) * chunkPixels);
            int pz = height / 2 + (int) Math.round((minecraft.player.getZ() / 16.0D - centerChunkZ) * chunkPixels);
            graphics.fill(px - 4, pz - 4, px + 5, pz + 5, 0xFFFFFFFF);
            graphics.fill(px - 2, pz - 2, px + 3, pz + 3, 0xFF3A74D8);
        }

        graphics.text(minecraft.font, "Explored chunks: " + VBVWorldMap.explored(world).size(), 8, height - 48, 0xFFFFFFFF, true);
        graphics.text(minecraft.font, "Zoom: " + String.format(java.util.Locale.ROOT, "%.1fx", zoom), 8, height - 36, 0xFFCCCCCC, true);
        graphics.text(minecraft.font, "Click + / - to zoom. Use Mark Location for a waypoint.", 8, 32, 0xFFCCCCCC, true);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }
}
