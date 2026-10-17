package com.melonman106.vbvclient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class VBVWaypointsScreen extends Screen {
    private final Screen parent;

    public VBVWaypointsScreen(Screen parent) {
        super(Component.literal("Waypoints"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        Minecraft minecraft = Minecraft.getInstance();
        String world = VBVWorldMap.worldKey(minecraft);
        List<VBVWaypoint> list = new ArrayList<>(VBVWorldMap.waypoints(world).values());
        list.sort(Comparator.comparing(VBVWaypoint::name, String.CASE_INSENSITIVE_ORDER));

        int y = 44;
        for (VBVWaypoint waypoint : list) {
            final VBVWaypoint selected = waypoint;
            addRenderableWidget(Button.builder(
                Component.literal(selected.name() + "  [" + selected.x() + ", " + selected.y() + ", " + selected.z() + "]"),
                button -> VBVNav.open(new VBVWaypointDetailsScreen(this, selected))
            ).bounds(12, y, Math.min(width - 24, 520), 22).build());
            y += 26;
            if (y > height - 55) break;
        }

        addRenderableWidget(Button.builder(Component.literal("Back"), button -> VBVNav.open(parent))
            .bounds(width / 2 - 50, height - 30, 100, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF151515);
        graphics.text(font, title.getString(), width / 2 - font.width(title.getString()) / 2, 14, 0xFFFFFFFF, true);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }
}
