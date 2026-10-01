package com.melonman106.vbvclient;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Label-only details page; see VBVModsScreen for why it uses disabled Buttons. */
public final class VBVModDetailsScreen extends Screen {
    private final Screen parent;
    private final VBVModInfo info;
    private int labelY;

    public VBVModDetailsScreen(Screen parent, VBVModInfo info) {
        super(Component.literal(info.name()));
        this.parent = parent;
        this.info = info;
    }

    private void label(String text) {
        Button b = Button.builder(Component.literal(text), button -> { })
            .bounds(20, labelY, Math.min(400, width - 40), 20).build();
        b.active = false;
        addRenderableWidget(b);
        labelY += 24;
    }

    @Override
    protected void init() {
        super.init();
        labelY = 16;
        label(info.name());
        label("ID: " + info.id());
        label("Version: " + info.version());
        label("Authors: " + info.authors());
        String text = String.valueOf(info.description());
        while (!text.isEmpty() && labelY < height - 50) {
            int cut = Math.min(text.length(), 60);
            if (cut < text.length()) {
                int space = text.lastIndexOf(' ', cut);
                if (space > 20) cut = space;
            }
            label(text.substring(0, cut).trim());
            text = text.substring(cut).trim();
        }
        addRenderableWidget(Button.builder(
            Component.literal("Back"),
            button -> VBVNav.open(parent)
        ).bounds(width / 2 - 50, height - 30, 100, 20).build());
    }
}
