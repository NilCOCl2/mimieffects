package com.mimieffects.network;

import com.mimieffects.client.network.ClientPayloadHandlers;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * FIXED 2026-09-04 — this was the cause of the dedicated-server crash:
 * "Attempted to load class net/minecraft/client/gui/screens/Screen for
 * invalid dist DEDICATED_SERVER".
 *
 * The previous version of this file was WRONG about why referencing
 * ClientPayloadHandlers::handleTracksSync here was safe. The claim was
 * "a method reference only requires resolving the target method's
 * SIGNATURE, not its body" — that's true for a plain Java invokedynamic
 * bootstrap in general, but NeoForge's RuntimeDistCleaner is a classfile
 * TRANSFORM that runs at CLASS-LOAD time and scans the WHOLE class being
 * loaded for banned dist references — and creating a method reference
 * (`Foo::bar`) forces the JVM to load `Foo` right then, on whichever side
 * is executing that line. Since registerCommon() runs on BOTH sides, the
 * server loads ClientPayloadHandlers too, RuntimeDistCleaner scans it,
 * finds its use of Minecraft/Screen, and refuses — crashing mod loading.
 * Confirmed against other mods that hit the exact same crash from the
 * exact same mistake (e.g. Iron's Spells v0.9.4 changelog: "NeoForge
 * resolves the types a method references before the Dist.CLIENT check
 * inside it can run — so a runtime guard could never have stopped it").
 * That's the key lesson: the guard has to wrap the REFERENCE-CREATING
 * EXPRESSION itself, not live inside the method the reference points to.
 *
 * At the same time, the type+codec for a playToClient payload DOES need
 * to be registered on BOTH sides — the server is the one calling
 * PacketDistributor.sendToPlayer(...), and if it never registers the
 * channel, connecting clients get rejected during channel negotiation
 * ("Client wants payload to be sent in: CLIENTBOUND, but server doesn't
 * support it!" — the exact failure another mod, mc-webgui/webgui, hit
 * from registering these ONLY client-side; see its v1.4.1 changelog).
 *
 * The fix: register the type+codec unconditionally on both sides, but
 * only ever EVALUATE the `ClientPayloadHandlers::...` method reference
 * inside a branch gated by FMLEnvironment.dist.isClient() — since that
 * branch's bytecode never executes on a dedicated server, the reference
 * is never created there, so ClientPayloadHandlers.class is never loaded
 * there, so RuntimeDistCleaner never gets a chance to scan it. The
 * server-side branch registers the same channel with a harmless no-op
 * handler — it's never invoked anyway, since a dedicated server only
 * ever SENDS these two payloads, never receives them.
 */
public final class NetworkRegistration {

    private NetworkRegistration() {
    }

    public static void registerCommon(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        // ServerPayloadHandlers never references client-only types, so
        // this one was never the problem — safe to register directly on
        // both sides as before.
        registrar.playToServer(
                SaveTrackPayload.TYPE,
                SaveTrackPayload.STREAM_CODEC,
                ServerPayloadHandlers::handleSaveTrack
        );

        if (FMLEnvironment.dist == Dist.CLIENT) {
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
            registrar.playToClient(
                    TrackCacheSyncPayload.TYPE,
                    TrackCacheSyncPayload.STREAM_CODEC,
                    ClientPayloadHandlers::handleTrackCacheSync
            );
        } else {
            // Same channels, registered so the server declares them
            // during negotiation — handler is unreachable dead code on
            // a dedicated server (it only ever sends these, never
            // receives them), so a no-op is correct, not a stub to fill in.
            registrar.playToClient(
                    TracksSyncPayload.TYPE,
                    TracksSyncPayload.STREAM_CODEC,
                    (payload, context) -> {
                    }
            );
            registrar.playToClient(
                    SaveResultPayload.TYPE,
                    SaveResultPayload.STREAM_CODEC,
                    (payload, context) -> {
                    }
            );
            registrar.playToClient(
                    TrackCacheSyncPayload.TYPE,
                    TrackCacheSyncPayload.STREAM_CODEC,
                    (payload, context) -> {
                    }
            );
        }
    }
}
