package com.melonman106.vbfix;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class VbvMining {
    private static final Identifier ID = Identifier.parse("viabackportvisuals:mining_comp");
    private static final Map<UUID, Float> FACTOR = new HashMap<>();

    private VbvMining() {}

    public static void apply(ServerPlayer player, BlockState sourceState) {
        clear(player);
        if (!ViaBackportVisuals.isMappingsEnabled()) return;

        BlockState reference = ViaBackportVisuals.getMiningReferenceState(sourceState);
        BlockState placeholder = ViaBackportVisuals.getPlaceholderState(sourceState);
        if (reference == null || placeholder == null) return;

        float placeholderProgress = placeholder.getDestroyProgress(player, player.level(), player.blockPosition());
        float referenceProgress = reference.getDestroyProgress(player, player.level(), player.blockPosition());
        if (!(placeholderProgress > 0.0F) || !(referenceProgress > 0.0F)) return;

        float factor = referenceProgress / placeholderProgress;
        if (!Float.isFinite(factor) || factor <= 0.0F || Math.abs(factor - 1.0F) < 0.0001F) return;

        AttributeInstance attribute = player.getAttribute(Attributes.BLOCK_BREAK_SPEED);
        if (attribute == null) return;

        attribute.removeModifier(ID);
        attribute.addTransientModifier(new AttributeModifier(ID, factor - 1.0D,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        FACTOR.put(player.getUUID(), factor);
    }

    public static float factor(ServerPlayer player) {
        return FACTOR.getOrDefault(player.getUUID(), 1.0F);
    }

    public static void clear(ServerPlayer player) {
        AttributeInstance attribute = player.getAttribute(Attributes.BLOCK_BREAK_SPEED);
        if (attribute != null) attribute.removeModifier(ID);
        FACTOR.remove(player.getUUID());
    }

    public static void clearAll() {
        FACTOR.clear();
    }
}
