package com.melonman106.vbvclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Native Eagler port of uku's Armor HUD (ArmorHudMod + ArmorHudRenderer).
 * Fabric, Mixin, Lombok and ukulib are not used; the widget is drawn straight
 * from the hook inserted into Hud.extractItemHotbar by apply_vbv_patch.py.
 */
public final class VBVArmorHud {
    private static final int STEP = 20;
    private static final int SIZE = 22;
    private static final int HOTBAR_OFFSET = 98;
    private static final int OFFHAND_OFFSET = 29;
    private static final int ATTACK_INDICATOR_OFFSET = 23;
    private static final int WARNING_SIZE = 8;

    private static final EquipmentSlot[] SLOT_IDS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private static final Random RANDOM = new Random();

    private VBVArmorHud() {}

    public static void render(GuiGraphicsExtractor graphics) {
        try {
            renderInner(graphics);
        } catch (Throwable t) {
            // A HUD bug must never take down the client; stop retrying every frame.
            VBVArmorHudConfig.enabled = false;
        }
    }

    private static void renderInner(GuiGraphicsExtractor graphics) {
        if (!VBVArmorHudConfig.enabled) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.getCameraEntity() instanceof Player player)) return;
        Font font = minecraft.font;

        List<ItemStack> items = armorItems(player);
        if (!items.isEmpty()) drawWidget(graphics, minecraft, font, player, items);
        drawHands(graphics, font, player);
    }

    // ---------------------------------------------------------------- items

    private static List<ItemStack> armorItems(Player player) {
        List<ItemStack> all = new ArrayList<>(4);
        for (EquipmentSlot slot : SLOT_IDS) all.add(player.getItemBySlot(slot));

        List<ItemStack> out = new ArrayList<>(4);
        int mode = VBVArmorHudConfig.widgetShown;
        if (mode == VBVArmorHudConfig.SHOWN_ALWAYS) {
            out.addAll(all);
        } else if (mode == VBVArmorHudConfig.SHOWN_IF_ANY) {
            boolean any = false;
            for (ItemStack s : all) if (!s.isEmpty()) any = true;
            if (any) out.addAll(all);
        } else if (mode == VBVArmorHudConfig.SHOWN_NOT_EMPTY) {
            for (ItemStack s : all) if (!s.isEmpty()) out.add(s);
        } else {
            for (ItemStack s : all) if (shouldShowWarning(s)) out.add(s);
        }
        if (VBVArmorHudConfig.reversed) Collections.reverse(out);
        return out;
    }

    private static boolean shouldShowWarning(ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageableItem()) return false;
        int damage = stack.getDamageValue();
        int max = Math.max(1, stack.getMaxDamage());
        double percentage = 1.0D - ((double) damage / max);
        return percentage <= VBVArmorHudConfig.minDurabilityPercent / 100.0D
            || max - damage <= VBVArmorHudConfig.minDurabilityValue;
    }

    private static String durabilityText(ItemStack stack) {
        int damage = stack.getDamageValue();
        int max = Math.max(1, stack.getMaxDamage());
        if (VBVArmorHudConfig.durabilityDisplay == VBVArmorHudConfig.DURA_NUMERIC) {
            return String.valueOf(max - damage);
        }
        if (damage == 0) return "";
        double percentage = 1.0D - (double) damage / max;
        return (int) Math.floor(percentage * 100.0D) + "%";
    }

    private static int barColor(ItemStack stack) {
        return stack.getBarColor() | 0xFF000000;
    }

    // ----------------------------------------------------------- geometry

    /** Returns {x, y, width, height} of the widget (no warning/text extras). */
    private static int[] widgetRect(GuiGraphicsExtractor g, Minecraft minecraft, Player player, int count) {
        int anchor = VBVArmorHudConfig.anchor;
        int side = VBVArmorHudConfig.side;
        boolean hotbar = anchor == VBVArmorHudConfig.ANCHOR_HOTBAR;
        boolean left = side == VBVArmorHudConfig.SIDE_LEFT;

        int sm;
        int som;
        if ((hotbar && left) || (!hotbar && !left)) {
            sm = -1;
            som = -1;
        } else {
            sm = 1;
            som = 0;
        }

        HumanoidArm sideArm = left ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
        boolean mainOnSide = player.getMainArm() == sideArm;
        int added = 0;
        if (VBVArmorHudConfig.offhandBehavior == VBVArmorHudConfig.OFFHAND_LEAVE_SPACE) {
            added = mainOnSide ? ATTACK_INDICATOR_OFFSET : OFFHAND_OFFSET;
        } else if (VBVArmorHudConfig.offhandBehavior == VBVArmorHudConfig.OFFHAND_ADHERE) {
            if (mainOnSide) {
                if (minecraft.options.attackIndicator().get() == AttackIndicatorStatus.HOTBAR
                        && player.getAttackStrengthScale(0.0F) < 1.0F) {
                    added = ATTACK_INDICATOR_OFFSET;
                }
            } else if (!player.getOffhandItem().isEmpty()) {
                added = OFFHAND_OFFSET;
            }
        }

        boolean vertical = VBVArmorHudConfig.orientation == VBVArmorHudConfig.ORIENT_VERTICAL;
        int length = SIZE + (count - 1) * STEP;
        int w = vertical ? SIZE : length;
        int h = vertical ? length : SIZE;
        int gw = g.guiWidth();
        int gh = g.guiHeight();

        int x = VBVArmorHudConfig.offsetX * sm;
        if (anchor == VBVArmorHudConfig.ANCHOR_TOP_CENTER) {
            x += (gw - w) / 2;
        } else if (hotbar) {
            x += gw / 2 + (HOTBAR_OFFSET + added) * sm + w * som;
        } else {
            x += (w - gw) * som;
        }

        int y;
        if (anchor == VBVArmorHudConfig.ANCHOR_BOTTOM || hotbar) {
            y = gh - h - VBVArmorHudConfig.offsetY;
        } else if (anchor == VBVArmorHudConfig.ANCHOR_VERT_CENTER) {
            y = (gh - h) / 2 + VBVArmorHudConfig.offsetY;
        } else {
            y = VBVArmorHudConfig.offsetY;
        }
        return new int[] { x, y, w, h };
    }

    // ------------------------------------------------------------ drawing

    private static void drawWidget(GuiGraphicsExtractor g, Minecraft minecraft, Font font,
                                   Player player, List<ItemStack> items) {
        int[] rect = widgetRect(g, minecraft, player, items.size());
        boolean horizontal = VBVArmorHudConfig.orientation == VBVArmorHudConfig.ORIENT_HORIZONTAL;
        boolean top = VBVArmorHudConfig.anchor == VBVArmorHudConfig.ANCHOR_TOP
            || VBVArmorHudConfig.anchor == VBVArmorHudConfig.ANCHOR_TOP_CENTER;
        boolean extrasRight;
        if (VBVArmorHudConfig.anchor == VBVArmorHudConfig.ANCHOR_HOTBAR) {
            extrasRight = VBVArmorHudConfig.side == VBVArmorHudConfig.SIDE_RIGHT;
        } else {
            extrasRight = VBVArmorHudConfig.side == VBVArmorHudConfig.SIDE_LEFT;
        }

        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            int x = rect[0] + (horizontal ? STEP * i : 0);
            int y = rect[1] + (horizontal ? 0 : STEP * i);

            if (VBVArmorHudConfig.style != VBVArmorHudConfig.STYLE_NONE) {
                g.fill(x, y, x + SIZE, y + SIZE, 0x66000000);
                g.outline(x, y, SIZE, SIZE, 0x99FFFFFF);
            }
            if (stack.isEmpty()) continue;
            g.item(stack, x + 3, y + 3);

            boolean damageable = stack.isDamageableItem();
            if (damageable && VBVArmorHudConfig.durabilityDisplay == VBVArmorHudConfig.DURA_BAR) {
                int max = Math.max(1, stack.getMaxDamage());
                int remaining = Math.max(0, max - stack.getDamageValue());
                if (remaining < max) {
                    int filled = Math.max(1, Math.round(13.0F * remaining / max));
                    g.fill(x + 5, y + 16, x + 18, y + 18, 0xFF000000);
                    g.fill(x + 5, y + 16, x + 5 + filled, y + 17, barColor(stack));
                }
            }

            int ex = x;
            int ey = y;
            if (horizontal && top) {
                ey += SIZE;
            } else if (!horizontal && extrasRight) {
                ex += SIZE;
            }

            if (damageable && VBVArmorHudConfig.durabilityDisplay != VBVArmorHudConfig.DURA_BAR) {
                String text = durabilityText(stack);
                int lineHeight = font.lineHeight;
                if (horizontal) {
                    if (!top) ey -= lineHeight;
                    centered(g, font, text, ex + SIZE / 2, ey, barColor(stack));
                    if (top) ey += lineHeight;
                } else {
                    int textWidth = font.width(text) + 2;
                    int textY = (SIZE - lineHeight) / 2;
                    if (!extrasRight) ex -= textWidth;
                    g.text(font, text, ex + 1, ey + textY, barColor(stack), true);
                    if (extrasRight) ex += textWidth;
                }
            }

            if (VBVArmorHudConfig.warningShown && shouldShowWarning(stack)) {
                ey += bob();
                if (horizontal) {
                    if (!top) ey -= WARNING_SIZE + 2;
                    drawWarning(g, font, ex + (SIZE - WARNING_SIZE) / 2, ey + 1);
                } else {
                    if (!extrasRight) ex -= WARNING_SIZE + 2;
                    drawWarning(g, font, ex + 1, ey + (SIZE - WARNING_SIZE) / 2);
                }
            }
        }
    }

    private static void drawHands(GuiGraphicsExtractor g, Font font, Player player) {
        int gw = g.guiWidth();
        int gh = g.guiHeight();

        ItemStack offhand = player.getOffhandItem();
        if (offhand.isDamageableItem() && VBVArmorHudConfig.offHandDurability) {
            int x = player.getMainArm() == HumanoidArm.RIGHT
                ? gw / 2 - 91 - 29
                : gw / 2 + 91 + 7;
            int y = gh - SIZE;

            if (VBVArmorHudConfig.durabilityDisplay != VBVArmorHudConfig.DURA_BAR) {
                y -= font.lineHeight;
                centered(g, font, durabilityText(offhand), x + SIZE / 2, y, barColor(offhand));
            }
            if (VBVArmorHudConfig.warningShown && shouldShowWarning(offhand)) {
                y += bob();
                y -= WARNING_SIZE + 2;
                drawWarning(g, font, x + (SIZE - WARNING_SIZE) / 2, y + 1);
            }
        }

        ItemStack mainHand = player.getMainHandItem();
        if (mainHand.isDamageableItem() && VBVArmorHudConfig.mainHandDurability
                && VBVArmorHudConfig.durabilityDisplay != VBVArmorHudConfig.DURA_BAR) {
            int x = gw / 2 - 91 + player.getInventory().getSelectedSlot() * 20 + SIZE / 2;
            int y = gh - SIZE - 1;
            centered(g, font, durabilityText(mainHand), x, y, barColor(mainHand));
        }
    }

    private static int bob() {
        int intensity = VBVArmorHudConfig.warningBobIntensity;
        if (intensity <= 0) return 0;
        return (int) (RANDOM.nextInt(intensity) - Math.ceil(intensity / 2.0F));
    }

    /** The original mod ships a warn.png sprite; draw an equivalent without needing the asset. */
    private static void drawWarning(GuiGraphicsExtractor g, Font font, int x, int y) {
        g.fill(x, y, x + WARNING_SIZE, y + WARNING_SIZE, 0xFFCC2222);
        g.text(font, "!", x + 3, y, 0xFFFFFFFF, false);
    }

    private static void centered(GuiGraphicsExtractor g, Font font, String text, int cx, int y, int color) {
        if (text.isEmpty()) return;
        g.text(font, text, cx - font.width(text) / 2, y, color, true);
    }
}