package com.melonman106.vbvclient;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class VBVSimpleHudConfigScreen extends Screen {
    private final Screen parent;

    public VBVSimpleHudConfigScreen(Screen parent) {
        super(Component.literal("Simple HUD Enhanced Config"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        addToggle("Simple HUD", 0, 0, () -> VBVSimpleHudConfig.enabled = !VBVSimpleHudConfig.enabled, () -> VBVSimpleHudConfig.enabled);
        addToggle("FPS", 1, 0, () -> VBVSimpleHudConfig.fps = !VBVSimpleHudConfig.fps, () -> VBVSimpleHudConfig.fps);
        addToggle("Coordinates", 2, 0, () -> VBVSimpleHudConfig.coordinates = !VBVSimpleHudConfig.coordinates, () -> VBVSimpleHudConfig.coordinates);
        addToggle("Speed", 3, 0, () -> VBVSimpleHudConfig.speed = !VBVSimpleHudConfig.speed, () -> VBVSimpleHudConfig.speed);
        addToggle("Light level", 4, 0, () -> VBVSimpleHudConfig.lightLevel = !VBVSimpleHudConfig.lightLevel, () -> VBVSimpleHudConfig.lightLevel);
        addToggle("Game time", 5, 0, () -> VBVSimpleHudConfig.gameTime = !VBVSimpleHudConfig.gameTime, () -> VBVSimpleHudConfig.gameTime);

        addToggle("Player name", 0, 1, () -> VBVSimpleHudConfig.playerName = !VBVSimpleHudConfig.playerName, () -> VBVSimpleHudConfig.playerName);
        addToggle("Health", 1, 1, () -> VBVSimpleHudConfig.health = !VBVSimpleHudConfig.health, () -> VBVSimpleHudConfig.health);
        addToggle("Hunger", 2, 1, () -> VBVSimpleHudConfig.hunger = !VBVSimpleHudConfig.hunger, () -> VBVSimpleHudConfig.hunger);
        addToggle("Movement", 3, 1, () -> VBVSimpleHudConfig.movement = !VBVSimpleHudConfig.movement, () -> VBVSimpleHudConfig.movement);
        addToggle("Effects", 4, 1, () -> VBVSimpleHudConfig.effects = !VBVSimpleHudConfig.effects, () -> VBVSimpleHudConfig.effects);
        addToggle("Equipment HUD", 5, 1, () -> VBVSimpleHudConfig.equipment = !VBVSimpleHudConfig.equipment, () -> VBVSimpleHudConfig.equipment);

        addRenderableWidget(Button.builder(Component.literal("Reset defaults"), button -> {
            VBVSimpleHudConfig.reset();
            VBVNav.open(new VBVSimpleHudConfigScreen(parent));
        }).bounds(width / 2 - 100, height - 52, 95, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> VBVNav.open(parent))
            .bounds(width / 2 + 5, height - 52, 95, 20).build());
    }

    private void addToggle(String label, int row, int column, Runnable action, java.util.function.BooleanSupplier state) {
        int x = column == 0 ? width / 2 - 205 : width / 2 + 5;
        addRenderableWidget(Button.builder(Component.literal(label + ": " + (state.getAsBoolean() ? "ON" : "OFF")), button -> {
            action.run();
            button.setMessage(Component.literal(label + ": " + (state.getAsBoolean() ? "ON" : "OFF")));
        }).bounds(x, 24 + row * 27, 200, 22).build());
    }
}
