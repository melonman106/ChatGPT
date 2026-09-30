package com.melonman106.vbfix;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record VbvMarkerPayload(BlockPos pos, int visualId, boolean remove) implements CustomPacketPayload {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("viabackportvisuals", "marker");
    public static final Type<VbvMarkerPayload> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, VbvMarkerPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public void encode(RegistryFriendlyByteBuf buf, VbvMarkerPayload value) {
                    buf.writeBlockPos(value.pos());
                    buf.writeVarInt(value.visualId());
                    buf.writeBoolean(value.remove());
                }

                @Override
                public VbvMarkerPayload decode(RegistryFriendlyByteBuf buf) {
                    return new VbvMarkerPayload(buf.readBlockPos(), buf.readVarInt(), buf.readBoolean());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
