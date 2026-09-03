package com.mimieffects.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.mimieffects.MimiEffectsMod;

/**
 * Server -> client, sent only when a save FAILS (invalid JSON, permission
 * denied, bad file name). A successful save gets a fresh
 * TracksSyncPayload instead, which doubles as both "ack" and "here's the
 * canonical refreshed state" — see ServerPayloadHandlers.
 */
public record SaveResultPayload(String reason) implements CustomPacketPayload {

    public static final Type<SaveResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MimiEffectsMod.MOD_ID, "save_result"));

    public static final StreamCodec<ByteBuf, SaveResultPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SaveResultPayload::reason,
            SaveResultPayload::new
    );

    @Override
    public Type<SaveResultPayload> type() {
        return TYPE;
    }
}
