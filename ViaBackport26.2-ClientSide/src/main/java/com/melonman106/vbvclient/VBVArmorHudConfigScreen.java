package com.melonman106.vbvclient;

import java.util.function.Supplier;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class VBVArmorHudConfigScreen extends Screen {
    private final Screen parent;
    public VBVArmorHudConfigScreen(Screen parent) { super(Component.literal("Uku's Armor HUD Config")); this.parent=parent; }
    @Override protected void init() {
        super.init(); int x=width/2-100;
        toggle(x,30,"Armor HUD",()->VBVArmorHudConfig.enabled?"ON":"OFF",()->VBVArmorHudConfig.enabled=!VBVArmorHudConfig.enabled);
        toggle(x,57,"Side",()->VBVArmorHudConfig.leftSide?"Left of hotbar":"Right of hotbar",()->VBVArmorHudConfig.leftSide=!VBVArmorHudConfig.leftSide);
        toggle(x,84,"Empty slots",()->VBVArmorHudConfig.showEmptySlots?"Shown":"Hidden",()->VBVArmorHudConfig.showEmptySlots=!VBVArmorHudConfig.showEmptySlots);
        toggle(x,111,"Durability",()->VBVArmorHudConfig.DURABILITY_MODES[VBVArmorHudConfig.durabilityMode],()->VBVArmorHudConfig.durabilityMode=(VBVArmorHudConfig.durabilityMode+1)%VBVArmorHudConfig.DURABILITY_MODES.length);
        toggle(x,138,"Low-durability warning",()->VBVArmorHudConfig.warning?"ON":"OFF",()->VBVArmorHudConfig.warning=!VBVArmorHudConfig.warning);
        addRenderableWidget(Button.builder(Component.literal("Reset defaults"),b->{VBVArmorHudConfig.reset();VBVNav.open(new VBVArmorHudConfigScreen(parent));}).bounds(width/2-100,height-52,95,20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"),b->VBVNav.open(parent)).bounds(width/2+5,height-52,95,20).build());
    }
    private void toggle(int x,int y,String label,Supplier<String> value,Runnable action){
        addRenderableWidget(Button.builder(Component.literal(label+": "+value.get()),b->{action.run();b.setMessage(Component.literal(label+": "+value.get()));}).bounds(x,y,200,22).build());
    }
}
