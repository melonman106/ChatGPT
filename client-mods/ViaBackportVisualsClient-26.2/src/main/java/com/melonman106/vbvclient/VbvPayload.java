package com.melonman106.vbvclient;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record VbvPayload(BlockPos pos, int visualId, boolean remove) implements CustomPacketPayload {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("viabackportvisuals", "marker");
    public static final Type<VbvPayload> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, VbvPayload> CODEC = new StreamCodec<>() {
        @Override
        public void encode(RegistryFriendlyByteBuf buf, VbvPayload value) {
            buf.writeBlockPos(value.pos());
            buf.writeVarInt(value.visualId());
            buf.writeBoolean(value.remove());
        }

        @Override
        public VbvPayload decode(RegistryFriendlyByteBuf buf) {
            return new VbvPayload(buf.readBlockPos(), buf.readVarInt(), buf.readBoolean());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
