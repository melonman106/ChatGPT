package com.melonman106.vbvclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class VBVArmorHud {
    private static final int STEP=20, SIZE=22, HOTBAR_OFFSET=98;
    private static final EquipmentSlot[] SLOTS={EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET};
    private VBVArmorHud(){}
    public static void render(GuiGraphicsExtractor graphics){try{renderInner(graphics);}catch(Throwable t){VBVArmorHudConfig.enabled=false;}}
    private static void renderInner(GuiGraphicsExtractor graphics){
        if(!VBVArmorHudConfig.enabled)return;
        Minecraft minecraft=Minecraft.getInstance();
        if(!(minecraft.getCameraEntity() instanceof Player player))return;
        boolean left=VBVArmorHudConfig.leftSide;
        int baseX=left?graphics.guiWidth()/2-HOTBAR_OFFSET-SIZE:graphics.guiWidth()/2+HOTBAR_OFFSET;
        int top=graphics.guiHeight()-(SIZE+STEP*(SLOTS.length-1))-2;
        Font font=minecraft.font;
        for(int i=0;i<SLOTS.length;i++){
            ItemStack stack=player.getItemBySlot(SLOTS[i]);
            if(stack.isEmpty()&&!VBVArmorHudConfig.showEmptySlots)continue;
            int x=baseX,y=top+STEP*i;
            graphics.fill(x,y,x+SIZE,y+SIZE,0x66000000);
            graphics.outline(x,y,SIZE,SIZE,0x99FFFFFF);
            if(stack.isEmpty())continue;
            graphics.item(stack,x+3,y+3);
            if(!stack.isDamageableItem())continue;
            int max=Math.max(1,stack.getMaxDamage()), remaining=Math.max(0,max-stack.getDamageValue());
            int percent=Math.max(0,Math.min(100,remaining*100/max));
            int color=stack.getBarColor()|0xFF000000;
            if(VBVArmorHudConfig.warning){if(percent<=20)color=0xFFFF5555;else if(percent<=50)color=0xFFFFFF55;}
            int mode=VBVArmorHudConfig.durabilityMode;
            if(mode==VBVArmorHudConfig.DURA_NUMBER)drawText(graphics,font,Integer.toString(remaining),x,y,left,color);
            else if(mode==VBVArmorHudConfig.DURA_PERCENT)drawText(graphics,font,percent+"%",x,y,left,color);
            else if(mode==VBVArmorHudConfig.DURA_BAR){
                int filled=Math.max(1,remaining*(SIZE-4)/max);
                graphics.fill(x+2,y+SIZE-4,x+SIZE-2,y+SIZE-2,0xFF000000);
                graphics.fill(x+2,y+SIZE-4,x+2+filled,y+SIZE-3,color);
            }
        }
    }
    private static void drawText(GuiGraphicsExtractor graphics,Font font,String text,int x,int y,boolean left,int color){
        int tx=left?x-font.width(text)-2:x+SIZE+2; graphics.text(font,text,tx,y+7,color,true);
    }
}
