package com.melonman106.vbvclient;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;

public final class VBVWaypointScreen extends Screen {
    private final Screen parent;
    private EditBox nameBox;

    private VBVWaypointScreen(Screen parent) {
        super(Component.literal("Mark Location"));
        this.parent = parent;
    }

    public static void openCreate(Screen parent) {
        VBVNav.open(new VBVWaypointScreen(parent));
    }

    @Override
    protected void init() {
        super.init();
        nameBox = new EditBox(font, width / 2 - 120, height / 2 - 35, 240, 20, Component.literal("Name"));
        nameBox.setValue("Waypoint");
        addRenderableWidget(nameBox);

        addRenderableWidget(Button.builder(Component.literal("Save"), button -> save())
            .bounds(width / 2 - 105, height / 2 + 5, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> VBVNav.open(parent))
            .bounds(width / 2 + 5, height / 2 + 5, 100, 20).build());
    }

    private void save() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) return;
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) name = "Waypoint";
        String id = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-") + "-" + System.nanoTime();
        VBVWorldMap.addWaypoint(
            VBVWorldMap.worldKey(minecraft),
            new VBVWaypoint(name.isEmpty() ? id : id, name, (int) minecraft.player.getX(), (int) minecraft.player.getY(), (int) minecraft.player.getZ())
        );
        VBVNav.open(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xDD000000);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 70, 0xFFFFFFFF);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }
}
