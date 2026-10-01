package com.melonman106.vbvclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class VBVArmorHud {
    private static final int SLOT_SIZE = 20;
    private static final int PANEL_WIDTH = 62;
    private static final int PANEL_HEIGHT = SLOT_SIZE * 4;
    private static final int HOTBAR_OFFSET = 91;
    private static final int GAP = 4;

    private VBVArmorHud() {}

    public static void render(GuiGraphicsExtractor graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.getCameraEntity() instanceof Player player)) return;
        if (minecraft.screen != null) return;

        int left = graphics.guiWidth() / 2 - HOTBAR_OFFSET - PANEL_WIDTH - GAP;
        int top = graphics.guiHeight() - PANEL_HEIGHT - 2;

        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0x66000000);

        EquipmentSlot[] slots = {
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
        };

        for (int i = 0; i < slots.length; i++) {
            int y = top + i * SLOT_SIZE;
            ItemStack stack = player.getItemBySlot(slots[i]);

            graphics.outline(left, y, SLOT_SIZE, SLOT_SIZE, 0x99FFFFFF);

            if (!stack.isEmpty()) {
                graphics.item(stack, left + 2, y + 2);

                if (stack.isDamageableItem()) {
                    int remaining = Math.max(0, stack.getMaxDamage() - stack.getDamageValue());
                    int max = Math.max(1, stack.getMaxDamage());
                    int percent = Math.max(0, Math.min(100, remaining * 100 / max));

                    int color = percent > 50 ? 0xFF55FF55
                        : percent > 20 ? 0xFFFFFF55
                        : 0xFFFF5555;

                    Font font = minecraft.font;
                    graphics.text(font, Integer.toString(remaining), left + SLOT_SIZE + 4, y + 6, color, true);
                }
            }
        }
    }
}
