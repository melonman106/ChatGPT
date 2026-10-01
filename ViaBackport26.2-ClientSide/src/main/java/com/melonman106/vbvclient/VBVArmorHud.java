package com.melonman106.vbvclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class VBVArmorHud {
    private static final int STEP = 20;
    private static final int SIZE = 22;
    private static final int HOTBAR_OFFSET = 98;

    private VBVArmorHud() {}

    /**
     * Native Eaglercraft 26.2 version of the useful Armor HUD behavior from
     * Uku's Armor HUD 26.2. Four armor slots are shown vertically to the
     * left of the hotbar: helmet, chestplate, leggings, boots.
     */
    public static void render(GuiGraphicsExtractor graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.getCameraEntity() instanceof Player player)) return;

        EquipmentSlot[] slots = {
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
        };

        int left = graphics.guiWidth() / 2 - HOTBAR_OFFSET - SIZE;
        int top = graphics.guiHeight() - (SIZE + STEP * (slots.length - 1)) - 2;

        for (int i = 0; i < slots.length; i++) {
            ItemStack stack = player.getItemBySlot(slots[i]);
            int x = left;
            int y = top + STEP * i;

            // Keep the widget visually close to Uku's Armor HUD slot size.
            graphics.fill(x, y, x + SIZE, y + SIZE, 0x66000000);
            graphics.outline(x, y, SIZE, SIZE, 0x99FFFFFF);

            if (stack.isEmpty()) continue;

            // Render the actual equipped armor item.
            graphics.item(stack, x + 3, y + 3);

            // Render remaining durability, matching the 26.2 source's
            // numeric durability behavior.
            if (stack.isDamageableItem()) {
                int remaining = Math.max(0, stack.getMaxDamage() - stack.getDamageValue());
                int max = Math.max(1, stack.getMaxDamage());
                int percent = Math.max(0, Math.min(100, remaining * 100 / max));

                int color = stack.getBarColor();
                if (percent <= 20) {
                    color = 0xFFFF5555;
                } else if (percent <= 50) {
                    color = 0xFFFFFF55;
                }

                Font font = minecraft.font;
                String durability = Integer.toString(remaining);
                graphics.text(font, durability, x + SIZE + 2, y + 7, color, true);
            }
        }
    }
}
