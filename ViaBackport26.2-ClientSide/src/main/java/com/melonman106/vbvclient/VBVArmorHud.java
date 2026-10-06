package com.melonman106.vbvclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Native Eaglercraft implementation of uku's Armor HUD for 26.2 u1.
 *
 * The original mod is intentionally minimalist: it shows the four equipped
 * armor items and a visual low-durability warning without putting durability
 * numbers on the screen. This native port keeps that behavior while exposing
 * position/number options through the existing VBV HUD configuration screen.
 */
public final class VBVArmorHud {
    private static final int STEP = 20;
    private static final int SIZE = 20;
    private static final int HOTBAR_OFFSET = 98;

    private VBVArmorHud() {}

    public static void render(GuiGraphicsExtractor graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.getCameraEntity() instanceof Player player)) return;

        EquipmentSlot[] slots = {
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
        };

        int left = graphics.guiWidth() / 2 - HOTBAR_OFFSET - SIZE
            + VBVSimpleHudConfig.armorXOffset;
        int top = graphics.guiHeight() - (SIZE + STEP * (slots.length - 1)) - 2
            + VBVSimpleHudConfig.armorYOffset;

        for (int i = 0; i < slots.length; i++) {
            ItemStack stack = player.getItemBySlot(slots[i]);
            int x = left;
            int y = top + STEP * i;

            // Vanilla-like slot backing, kept deliberately quiet.
            graphics.fill(x, y, x + SIZE, y + SIZE, 0x55000000);

            if (stack.isEmpty()) {
                graphics.outline(x, y, SIZE, SIZE, 0x44999999);
                continue;
            }

            graphics.item(stack, x + 2, y + 2);

            if (stack.isDamageableItem()) {
                int max = Math.max(1, stack.getMaxDamage());
                int remaining = Math.max(0, max - stack.getDamageValue());
                int percent = remaining * 100 / max;

                // Match uku's warning-focused presentation: no durability
                // numbers by default, only a warning when the item is close
                // to breaking.
                if (percent <= VBVSimpleHudConfig.armorWarningPercent) {
                    int warning = percent <= 10 ? 0xFFFF5555 : 0xFFFFAA00;
                    graphics.outline(x - 1, y - 1, SIZE + 2, SIZE + 2, warning);

                    if (VBVSimpleHudConfig.armorShowNumbers) {
                        graphics.text(
                            minecraft.font,
                            Integer.toString(remaining),
                            x + SIZE + 2,
                            y + 6,
                            warning,
                            true
                        );
                    }
                } else if (VBVSimpleHudConfig.armorShowNumbers) {
                    graphics.text(
                        minecraft.font,
                        Integer.toString(remaining),
                        x + SIZE + 2,
                        y + 6,
                        stack.getBarColor(),
                        true
                    );
                }
            }
        }
    }
}
