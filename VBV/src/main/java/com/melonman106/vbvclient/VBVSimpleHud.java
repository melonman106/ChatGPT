package com.melonman106.vbvclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;

public final class VBVSimpleHud {
    private VBVSimpleHud() {}

    public static void render(GuiGraphicsExtractor graphics) {
        if (!VBVSimpleHudConfig.enabled) return;
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) return;

        int x = 6;
        int y = 6;
        int line = 11;
        int lines = lineCount();
        graphics.fill(x - 3, y - 3, x + 190, y + lines * line + 1, 0x66000000);

        if (VBVSimpleHudConfig.fps) y = text(graphics, "FPS: " + minecraft.getFps(), x, y, line);
        if (VBVSimpleHudConfig.coordinates) y = text(graphics, "XYZ: " + (int) player.getX() + " " + (int) player.getY() + " " + (int) player.getZ(), x, y, line);
        if (VBVSimpleHudConfig.speed) {
            double speed = player.getDeltaMovement().horizontalDistance() * 20.0D;
            y = text(graphics, String.format(java.util.Locale.ROOT, "Speed: %.2f m/s", speed), x, y, line);
        }
        if (VBVSimpleHudConfig.lightLevel) y = text(graphics, "Light: " + minecraft.level.getMaxLocalRawBrightness(player.blockPosition()), x, y, line);
        if (VBVSimpleHudConfig.gameTime) {
            long ticks = minecraft.level.getGameTime() % 24000L;
            long hours = (ticks / 1000L + 6L) % 24L;
            long minutes = (ticks % 1000L) * 60L / 1000L;
            y = text(graphics, String.format(java.util.Locale.ROOT, "Game time: %02d:%02d", hours, minutes), x, y, line);
        }
        if (VBVSimpleHudConfig.playerName) y = text(graphics, "Player: " + player.getName().getString(), x, y, line);
        if (VBVSimpleHudConfig.health) y = text(graphics, String.format(java.util.Locale.ROOT, "Health: %.1f / %.1f", player.getHealth(), player.getMaxHealth()), x, y, line);
        if (VBVSimpleHudConfig.hunger) y = text(graphics, "Food: " + player.getFoodData().getFoodLevel(), x, y, line);
        if (VBVSimpleHudConfig.movement) {
            String state = player.isSprinting() ? "Sprinting" : player.isSwimming() ? "Swimming" : player.isCrouching() ? "Sneaking" : "Walking";
            y = text(graphics, "State: " + state, x, y, line);
        }
        if (VBVSimpleHudConfig.effects) y = text(graphics, "Effects: " + player.getActiveEffects().size(), x, y, line);
    }

    private static int text(GuiGraphicsExtractor graphics, String value, int x, int y, int line) {
        graphics.text(Minecraft.getInstance().font, value, x, y, 0xFFFFFFFF, true);
        return y + line;
    }

    private static int lineCount() {
        int count = 0;
        if (VBVSimpleHudConfig.fps) count++;
        if (VBVSimpleHudConfig.coordinates) count++;
        if (VBVSimpleHudConfig.speed) count++;
        if (VBVSimpleHudConfig.lightLevel) count++;
        if (VBVSimpleHudConfig.gameTime) count++;
        if (VBVSimpleHudConfig.playerName) count++;
        if (VBVSimpleHudConfig.health) count++;
        if (VBVSimpleHudConfig.hunger) count++;
        if (VBVSimpleHudConfig.movement) count++;
        if (VBVSimpleHudConfig.effects) count++;
        return Math.max(1, count);
    }
}