package com.melonman106.vbvclient;

import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel;

import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

public final class VbvClientModelPlugin {
    private VbvClientModelPlugin() {}

    public static void register() {
        ModelLoadingPlugin.register(context -> context.modifyBlockModelAfterBake()
                .register(ModelModifier.WRAP_PHASE, (model, modelContext) -> new MarkerAwareModel(model)));
    }

    private static final class MarkerAwareModel extends WrapperBlockStateModel implements FabricBlockStateModel {
        MarkerAwareModel(BlockStateModel model) {
            super(model);
        }

        @Override
        public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state,
                               RandomSource random, Predicate<@Nullable Direction> cullTest) {
            Integer marker = VbvClient.MARKERS.get(pos);
            if (marker != null) {
                BlockStateModel target = VbvClient.targetModel(state, marker);
                if (target != null) {
                    ((FabricBlockStateModel) target).emitQuads(emitter, level, pos, state, random, cullTest);
                    return;
                }
            }
            ((FabricBlockStateModel) wrapped).emitQuads(emitter, level, pos, state, random, cullTest);
        }
    }
}
