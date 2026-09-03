package com.mimieffects.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.mimieffects.MimiEffectsMod;

/**
 * Server -> client. Carries every track file's raw JSON, Gson-serialized
 * as a single string (a JSON array of TrackFileDto) rather than using a
 * StreamCodec list-of-records — this way the wire format doesn't need to
 * change every time TrackConfig's Java schema does, and we reuse the same
 * Gson (de)serialization path TrackLoader already uses.
 *
 * ADDED (2026-09-03): instrumentIdsJson/effectIdsJson — the server's live
 * registry contents (BuiltInRegistries.ITEM filtered to the "mimi"
 * namespace, and BuiltInRegistries.MOB_EFFECT respectively), each a JSON
 * array of strings. This is what lets the editor offer a real picker
 * instead of a free-text box — same idea as Minecraft's own command
 * tab-completion, just delivered once up front instead of interactively.
 *
 * Sent by: MimiEffectsCommands ("/mimieffects gui") and
 * ServerPayloadHandlers (after a successful save, so the editor reflects
 * the canonical on-disk state rather than trusting the client's own copy).
 */
public record TracksSyncPayload(String tracksJson, String instrumentIdsJson, String effectIdsJson)
        implements CustomPacketPayload {

    public static final Type<TracksSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MimiEffectsMod.MOD_ID, "tracks_sync"));

    public static final StreamCodec<ByteBuf, TracksSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, TracksSyncPayload::tracksJson,
            ByteBufCodecs.STRING_UTF8, TracksSyncPayload::instrumentIdsJson,
            ByteBufCodecs.STRING_UTF8, TracksSyncPayload::effectIdsJson,
            TracksSyncPayload::new
    );

    @Override
    public Type<TracksSyncPayload> type() {
        return TYPE;
    }
}
