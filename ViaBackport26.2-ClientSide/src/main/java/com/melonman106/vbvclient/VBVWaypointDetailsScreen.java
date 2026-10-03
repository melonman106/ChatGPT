package com.melonman106.vbvclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class VBVWaypointDetailsScreen extends Screen {
    private final Screen parent;
    private final VBVWaypoint waypoint;

    public VBVWaypointDetailsScreen(Screen parent, VBVWaypoint waypoint) {
        super(Component.literal(waypoint.name()));
        this.parent = parent;
        this.waypoint = waypoint;
    }

    private void centered(GuiGraphicsExtractor graphics, String text, int y, int color) {
        graphics.text(font, text, width / 2 - font.width(text) / 2, y, color, true);
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(Component.literal("Center on Map"), button -> VBVNav.open(new VBVWorldMapScreen(parent)))
            .bounds(width / 2 - 70, height / 2 - 30, 140, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Delete"), button -> {
            VBVWorldMap.removeWaypoint(VBVWorldMap.worldKey(Minecraft.getInstance()), waypoint.id());
            VBVNav.open(parent);
        }).bounds(width / 2 - 70, height / 2, 140, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> VBVNav.open(parent))
            .bounds(width / 2 - 50, height - 30, 100, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF151515);
        centered(graphics, title.getString(), height / 2 - 80, 0xFFFFFFFF);
        String coords = "X " + waypoint.x() + "  Y " + waypoint.y() + "  Z " + waypoint.z();
        centered(graphics, coords, height / 2 - 55, 0xFFCCCCCC);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }
}
