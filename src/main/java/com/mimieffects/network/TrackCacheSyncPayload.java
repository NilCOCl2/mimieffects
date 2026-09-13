package com.mimieffects.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.mimieffects.MimiEffectsMod;

/**
 * ADDED 2026-09-13 (user report, dedicated server): server -> every
 * connected client, carries the CURRENTLY LOADED track list (Gson-
 * serialized List&lt;TrackConfig&gt;) so the client can populate its own
 * copy of MimiEffectsMod.TRACK_REGISTRY.
 *
 * Distinct from TracksSyncPayload on purpose: that one is tied to opening/
 * refreshing the Track Editor screen (and additionally carries the
 * instrument/effect id lists the picker needs) — broadcasting THAT to
 * every player on login would pop the editor GUI open for everyone, admin
 * or not. This payload only ever updates the client's registry copy in the
 * background; it never touches the screen.
 *
 * Root cause this fixes: in singleplayer, the client and the integrated
 * server share one JVM, so MimiEffectsMod.TRACK_REGISTRY (a plain static
 * field) is effectively "shared" by accident — the same object gets
 * populated by ServerStartingEvent and read by client-side code
 * (NoteScrollItem's tooltip, the creative tab listing) without any real
 * synchronization ever happening. On a real dedicated server the client is
 * a separate JVM entirely — its own TRACK_REGISTRY is NEVER populated by
 * anything, so every Note Scroll tooltip reads as unbound/missing and the
 * creative tab only ever shows the empty-registry fallback stack. This
 * payload is what actually keeps the client's copy in sync going forward.
 */
public record TrackCacheSyncPayload(String tracksJson) implements CustomPacketPayload {

    public static final Type<TrackCacheSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MimiEffectsMod.MOD_ID, "track_cache_sync"));

    public static final StreamCodec<ByteBuf, TrackCacheSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, TrackCacheSyncPayload::tracksJson,
            TrackCacheSyncPayload::new
    );

    @Override
    public Type<TrackCacheSyncPayload> type() {
        return TYPE;
    }
}
