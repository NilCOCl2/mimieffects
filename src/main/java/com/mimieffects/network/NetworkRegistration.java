package com.mimieffects.network;

import com.mimieffects.client.network.ClientPayloadHandlers;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * All payload registration lives here, in ONE common method registered
 * from MimiEffectsMod (loads on both physical sides) — matching the
 * pattern shown in NeoForge's own docs example, rather than splitting
 * playToClient into a client-only class.
 *
 * Why this is safe on a dedicated server despite referencing
 * ClientPayloadHandlers (which internally uses Minecraft/Screen): a
 * method REFERENCE only requires the JVM to resolve the target method's
 * SIGNATURE, not to load/verify everything its body touches — and
 * ClientPayloadHandlers' public method signatures use only plain
 * (non-client) types (see that class's javadoc). The registrar call
 * itself ("this payload flows server->client") has to run on BOTH sides
 * regardless — the server is literally the one sending TracksSyncPayload,
 * so IT needs this type+codec registered in its own registrar, or
 * PacketDistributor.sendToPlayer would have no codec to encode it with.
 * The handler body only ever executes on the side that actually RECEIVES
 * a given payload; a dedicated server never receives a payload it only
 * ever sends, so ClientPayloadHandlers' bodies are simply dead code there.
 */
public final class NetworkRegistration {

    private NetworkRegistration() {
    }

    public static void registerCommon(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        registrar.playToServer(
                SaveTrackPayload.TYPE,
                SaveTrackPayload.STREAM_CODEC,
                ServerPayloadHandlers::handleSaveTrack
        );

        registrar.playToClient(
                TracksSyncPayload.TYPE,
                TracksSyncPayload.STREAM_CODEC,
                ClientPayloadHandlers::handleTracksSync
        );

        registrar.playToClient(
                SaveResultPayload.TYPE,
                SaveResultPayload.STREAM_CODEC,
                ClientPayloadHandlers::handleSaveResult
        );
    }
}
