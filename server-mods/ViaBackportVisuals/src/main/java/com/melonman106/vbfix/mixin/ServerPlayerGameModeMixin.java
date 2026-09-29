package com.melonman106.vbfix.mixin;

import com.melonman106.vbfix.ViaBackportVisuals;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(net.minecraft.server.level.ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
    @Redirect(
        method = "incrementDestroyProgress",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getDestroyProgress(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F"
        )
    )
    private float viabackportvisuals$useVanillaToolProgress(
            BlockState state, Player player, BlockGetter level, BlockPos pos
    ) {
        BlockState reference = ViaBackportVisuals.getMiningReferenceState(state);
        return reference != null
                ? reference.getDestroyProgress(player, level, pos)
                : state.getDestroyProgress(player, level, pos);
    }
}
