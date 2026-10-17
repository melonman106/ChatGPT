package com.melonman106.vbvclient;

import java.util.function.Supplier;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Replacement for uku's ukulib-based tabbed config screen. */
public final class VBVArmorHudConfigScreen extends Screen {
    private final Screen parent;
    private EditBox offsetX;
    private EditBox offsetY;
    private EditBox minValue;
    private EditBox minPercent;

    public VBVArmorHudConfigScreen(Screen parent) {
        super(Component.literal("Uku's Armor HUD Config"));
        this.parent = parent;
    }

    private int left() { return width / 2 - 205; }
    private int right() { return width / 2 + 5; }
    private int rowY(int row) { return 24 + row * 24; }

    @Override
    protected void init() {
        super.init();
        button(0, 0, () -> "Armor HUD: " + onOff(VBVArmorHudConfig.enabled),
            () -> VBVArmorHudConfig.enabled = !VBVArmorHudConfig.enabled);
        button(1, 0, () -> "Anchor: " + VBVArmorHudConfig.ANCHORS[VBVArmorHudConfig.anchor],
            () -> VBVArmorHudConfig.anchor = VBVArmorHudConfig.next(VBVArmorHudConfig.anchor, VBVArmorHudConfig.ANCHORS.length));
        button(0, 1, () -> "Side: " + VBVArmorHudConfig.SIDES[VBVArmorHudConfig.side],
            () -> VBVArmorHudConfig.side = VBVArmorHudConfig.next(VBVArmorHudConfig.side, VBVArmorHudConfig.SIDES.length));
        button(1, 1, () -> "Orientation: " + VBVArmorHudConfig.ORIENTATIONS[VBVArmorHudConfig.orientation],
            () -> VBVArmorHudConfig.orientation = VBVArmorHudConfig.next(VBVArmorHudConfig.orientation, VBVArmorHudConfig.ORIENTATIONS.length));
        button(0, 2, () -> "Style: " + VBVArmorHudConfig.STYLES[VBVArmorHudConfig.style],
            () -> VBVArmorHudConfig.style = VBVArmorHudConfig.next(VBVArmorHudConfig.style, VBVArmorHudConfig.STYLES.length));
        button(1, 2, () -> "Show: " + VBVArmorHudConfig.WIDGET_SHOWN[VBVArmorHudConfig.widgetShown],
            () -> VBVArmorHudConfig.widgetShown = VBVArmorHudConfig.next(VBVArmorHudConfig.widgetShown, VBVArmorHudConfig.WIDGET_SHOWN.length));
        button(0, 3, () -> "Offhand slot: " + VBVArmorHudConfig.OFFHAND_BEHAVIOR[VBVArmorHudConfig.offhandBehavior],
            () -> VBVArmorHudConfig.offhandBehavior = VBVArmorHudConfig.next(VBVArmorHudConfig.offhandBehavior, VBVArmorHudConfig.OFFHAND_BEHAVIOR.length));
        button(1, 3, () -> "Durability: " + VBVArmorHudConfig.DURABILITY[VBVArmorHudConfig.durabilityDisplay],
            () -> VBVArmorHudConfig.durabilityDisplay = VBVArmorHudConfig.next(VBVArmorHudConfig.durabilityDisplay, VBVArmorHudConfig.DURABILITY.length));
        button(0, 4, () -> "Reversed: " + onOff(VBVArmorHudConfig.reversed),
            () -> VBVArmorHudConfig.reversed = !VBVArmorHudConfig.reversed);
        button(1, 4, () -> "Warning: " + onOff(VBVArmorHudConfig.warningShown),
            () -> VBVArmorHudConfig.warningShown = !VBVArmorHudConfig.warningShown);
        button(0, 5, () -> "Offhand durability: " + onOff(VBVArmorHudConfig.offHandDurability),
            () -> VBVArmorHudConfig.offHandDurability = !VBVArmorHudConfig.offHandDurability);
        button(1, 5, () -> "Main hand durability: " + onOff(VBVArmorHudConfig.mainHandDurability),
            () -> VBVArmorHudConfig.mainHandDurability = !VBVArmorHudConfig.mainHandDurability);

        offsetX = box(left() + 100, rowY(6), VBVArmorHudConfig.offsetX);
        offsetY = box(right() + 100, rowY(6), VBVArmorHudConfig.offsetY);
        minValue = box(left() + 100, rowY(7), VBVArmorHudConfig.minDurabilityValue);
        minPercent = box(right() + 100, rowY(7), VBVArmorHudConfig.minDurabilityPercent);

        addRenderableWidget(Button.builder(Component.literal("Reset defaults"), b -> {
            VBVArmorHudConfig.reset();
            VBVNav.open(new VBVArmorHudConfigScreen(parent));
        }).bounds(width / 2 - 100, height - 28, 95, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> {
            applyBoxes();
            VBVNav.open(parent);
        }).bounds(width / 2 + 5, height - 28, 95, 20).build());
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    private void button(int column, int row, Supplier<String> label, Runnable action) {
        int x = column == 0 ? left() : right();
        addRenderableWidget(Button.builder(Component.literal(label.get()), b -> {
            action.run();
            b.setMessage(Component.literal(label.get()));
        }).bounds(x, rowY(row), 200, 22).build());
    }

    private EditBox box(int x, int y, int value) {
        EditBox box = new EditBox(font, x, y, 100, 20, Component.literal("value"));
        box.setValue(Integer.toString(value));
        addRenderableWidget(box);
        return box;
    }

    private static int parse(EditBox box, int fallback, int min, int max) {
        try {
            int v = Integer.parseInt(box.getValue().trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void applyBoxes() {
        VBVArmorHudConfig.offsetX = parse(offsetX, VBVArmorHudConfig.offsetX, -2000, 2000);
        VBVArmorHudConfig.offsetY = parse(offsetY, VBVArmorHudConfig.offsetY, -2000, 2000);
        VBVArmorHudConfig.minDurabilityValue = parse(minValue, VBVArmorHudConfig.minDurabilityValue, 0, 100000);
        VBVArmorHudConfig.minDurabilityPercent = parse(minPercent, VBVArmorHudConfig.minDurabilityPercent, 0, 100);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF151515);
        String title = this.title.getString();
        graphics.text(font, title, width / 2 - font.width(title) / 2, 8, 0xFFFFFFFF, true);
        graphics.text(font, "X offset", left(), rowY(6) + 6, 0xFFCCCCCC, true);
        graphics.text(font, "Y offset", right(), rowY(6) + 6, 0xFFCCCCCC, true);
        graphics.text(font, "Min durability", left(), rowY(7) + 6, 0xFFCCCCCC, true);
        graphics.text(font, "Min dura %", right(), rowY(7) + 6, 0xFFCCCCCC, true);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }
}