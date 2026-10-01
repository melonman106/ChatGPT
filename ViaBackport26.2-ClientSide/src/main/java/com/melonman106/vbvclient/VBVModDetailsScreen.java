package com.melonman106.vbvclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class VBVModDetailsScreen extends Screen {
    private final Screen parent;
    private final VBVModInfo info;

    public VBVModDetailsScreen(Screen parent, VBVModInfo info) {
        super(Component.literal(info.name()));
        this.parent = parent;
        this.info = info;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(
            Component.literal("Back"),
            button -> Minecraft.getInstance().setScreen(parent)
        ).bounds(width / 2 - 50, height - 30, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        int x = 20;
        int y = 24;
        graphics.drawString(font, info.name(), x, y, 0xFFFFFF);
        graphics.drawString(font, "ID: " + info.id(), x, y += 18, 0xDDDDDD);
        graphics.drawString(font, "Version: " + info.version(), x, y += 18, 0xDDDDDD);
        graphics.drawString(font, "Authors: " + info.authors(), x, y += 18, 0xDDDDDD);
        graphics.drawString(font, info.description(), x, y + 30, 0xBBBBBB);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
