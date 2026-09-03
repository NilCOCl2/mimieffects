package com.mimieffects.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.mimieffects.MimiEffectsMod;

/**
 * Client -> server: "please save this file with this content". fileName
 * is validated server-side against path traversal (see
 * ServerPayloadHandlers) before ever touching the filesystem — never
 * trust a client-supplied path.
 */
public record SaveTrackPayload(String fileName, String json) implements CustomPacketPayload {

    public static final Type<SaveTrackPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MimiEffectsMod.MOD_ID, "save_track"));

    public static final StreamCodec<ByteBuf, SaveTrackPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SaveTrackPayload::fileName,
            ByteBufCodecs.STRING_UTF8, SaveTrackPayload::json,
            SaveTrackPayload::new
    );

    @Override
    public Type<SaveTrackPayload> type() {
        return TYPE;
    }
}
