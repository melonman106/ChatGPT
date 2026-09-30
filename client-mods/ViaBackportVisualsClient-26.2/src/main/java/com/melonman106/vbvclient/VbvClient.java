package com.melonman106.vbvclient;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey;
import net.fabricmc.fabric.api.client.model.loading.v1.FabricModelManager;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.SimpleUnbakedExtraModel;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockModelRotation;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

public final class VbvClient implements ClientModInitializer {
    public static final Map<BlockPos, Integer> MARKERS = new HashMap<>();
    private static final String[] COLORS = {
            "white","orange","magenta","light_blue","yellow","lime","pink","gray",
            "light_gray","cyan","purple","blue","brown","green","red","black"
    };

    private static final Map<String, ExtraModelKey<BlockStateModel>> MODELS = new HashMap<>();

    @Override
    public void onInitializeClient() {
        registerModelKeys();

        ModelLoadingPlugin.register(context -> {
            for (Map.Entry<String, ExtraModelKey<BlockStateModel>> entry : MODELS.entrySet()) {
                String[] parts = entry.getKey().split(":", 2);
                int visualId = Integer.parseInt(parts[0]);
                String variant = parts[1];
                context.addModel(entry.getValue(), extraModel(visualId, variant));
            }
        });

        VbvClientModelPlugin.register();

        PayloadTypeRegistryHelper.register();

        ClientPlayNetworking.registerReceiver(VbvPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                if (payload.remove()) {
                    MARKERS.remove(payload.pos());
                } else {
                    MARKERS.put(payload.pos().immutable(), payload.visualId());
                }
                if (context.client().level != null) {
                    context.client().levelRenderer.invalidateCompiledGeometry(
                            context.client().level,
                            context.client().options,
                            context.client().gameRenderer.mainCamera(),
                            context.client().getBlockColors()
                    );
                }
            });
        });

        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((oldLevel, newLevel) -> MARKERS.clear());
    }

    private static void registerModelKeys() {
        for (int visual = 0; visual < 64; visual++) {
            if (visual < 32) {
                for (String shape : new String[]{"straight","inner","outer"}) {
                    for (String half : new String[]{"bottom","top"}) {
                        for (String facing : new String[]{"east","north","south","west"}) {
                            addKey(visual, shape + ":" + half + ":" + facing);
                        }
                    }
                }
                for (String type : new String[]{"bottom","top","double"}) addKey(visual, "slab:" + type);
            }
        }
    }

    private static void addKey(int visual, String variant) {
        MODELS.put(visual + ":" + variant, ExtraModelKey.create(() -> "viabackportvisuals:" + visual + "/" + variant));
    }

    private static SimpleUnbakedExtraModel<BlockStateModel> extraModel(int visual, String variant) {
        String color = COLORS[visual % 16];
        boolean concrete = visual >= 32;
        String base = concrete ? color + "_concrete" : color + "_wool";

        if (visual >= 16 && visual < 32) {
            String model = "minecraft:block/" + color + "_wool_slab";
            String type = variant.substring("slab:".length());
            int x = type.equals("top") ? 180 : 0;
            if (type.equals("double")) model = "minecraft:block/" + color + "_wool_slab_double";
            return SimpleUnbakedExtraModel.blockStateModel(Identifier.parse(model), BlockModelRotation.get(x, 0));
        }

        if (visual >= 48) {
            String model = "minecraft:block/" + color + "_concrete_slab";
            String type = variant.substring("slab:".length());
            int x = type.equals("top") ? 180 : 0;
            if (type.equals("double")) model = "minecraft:block/" + color + "_concrete_slab_double";
            return SimpleUnbakedExtraModel.blockStateModel(Identifier.parse(model), BlockModelRotation.get(x, 0));
        }

        String[] p = variant.split(":");
        String shape = p[0];
        String half = p[1];
        String facing = p[2];
        String suffix = shape.equals("straight") ? "_stairs" : "_stairs_" + shape;
        String model = "minecraft:block/" + base + suffix;
        int y = switch (facing) {
            case "east" -> 90; case "north" -> 180; case "west" -> 270; default -> 0;
        };
        int x = half.equals("top") ? 180 : 0;
        return SimpleUnbakedExtraModel.blockStateModel(Identifier.parse(model), BlockModelRotation.get(x, y));
    }
}
