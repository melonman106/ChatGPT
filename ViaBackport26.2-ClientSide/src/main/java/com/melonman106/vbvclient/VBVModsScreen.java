package com.melonman106.vbvclient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class VBVModsScreen extends Screen {
    private final Screen parent;
    private final List<VBVModInfo> entries = new ArrayList<>();

    public VBVModsScreen(Screen parent) {
        super(Component.literal("Mods"));
        this.parent = parent;
        this.entries.addAll(VBVClient.getMods());
        this.entries.sort(Comparator.comparing(VBVModInfo::name, String.CASE_INSENSITIVE_ORDER));
    }

    @Override
    protected void init() {
        super.init();
        int y = 44;
        for (VBVModInfo info : entries) {
            final VBVModInfo selected = info;
            addRenderableWidget(Button.builder(
                Component.literal(selected.name() + "  " + selected.version()),
                button -> Minecraft.getInstance().setScreen(new VBVModDetailsScreen(this, selected))
            ).bounds(20, y, Math.min(320, width - 40), 22).build());
            y += 26;
            if (y > height - 50) break;
        }
        addRenderableWidget(Button.builder(
            Component.literal("Back"),
            button -> Minecraft.getInstance().setScreen(parent)
        ).bounds(width / 2 - 50, height - 30, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 15, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
