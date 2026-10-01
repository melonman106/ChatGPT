package com.melonman106.vbvclient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Built only from Button widgets (no render override, no graphics class) so it
 * does not depend on the renamed 26.2 drawing API. Disabled buttons act as labels.
 */
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
        Button heading = Button.builder(Component.literal("Mods"), button -> { })
            .bounds(width / 2 - 100, 12, 200, 20).build();
        heading.active = false;
        addRenderableWidget(heading);

        int y = 44;
        for (VBVModInfo info : entries) {
            final VBVModInfo selected = info;
            addRenderableWidget(Button.builder(
                Component.literal(selected.name() + "  " + selected.version()),
                button -> VBVNav.open(new VBVModDetailsScreen(this, selected))
            ).bounds(20, y, Math.min(320, width - 40), 22).build());
            y += 26;
            if (y > height - 50) break;
        }
        addRenderableWidget(Button.builder(
            Component.literal("Back"),
            button -> VBVNav.open(parent)
        ).bounds(width / 2 - 50, height - 30, 100, 20).build());
    }
}
