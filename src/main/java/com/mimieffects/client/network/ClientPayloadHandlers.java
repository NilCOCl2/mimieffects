package com.mimieffects.client.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import com.mimieffects.MimiEffectsMod;
import com.mimieffects.client.gui.TrackEditorScreen;
import com.mimieffects.network.SaveResultPayload;
import com.mimieffects.network.TrackCacheSyncPayload;
import com.mimieffects.network.TrackFileDto;
import com.mimieffects.network.TracksSyncPayload;
import com.mimieffects.track.TrackConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * IMPORTANT: keep every public method's SIGNATURE (parameter/return types)
 * free of client-only types (TracksSyncPayload/SaveResultPayload/
 * IPayloadContext are all common types). Minecraft/Screen only appear
 * inside method BODIES. This is what lets NetworkRegistration reference
 * these methods from common registration code that also runs on a
 * dedicated server without crashing it: the JVM resolves a method
 * reference's signature eagerly, but a method's body is only linked when
 * actually invoked — and a dedicated server never receives a
 * server-to-client payload to invoke these on, so the body's Minecraft/
 * Screen references are simply never touched there.
 */
public final class ClientPayloadHandlers {

    private static final Gson GSON = new GsonBuilder().create();

    private ClientPayloadHandlers() {
    }

    public static void handleTracksSync(TracksSyncPayload payload, IPayloadContext context) {
        List<TrackFileDto> tracks = GSON.fromJson(
                payload.tracksJson(),
                new TypeToken<List<TrackFileDto>>() {}.getType()
        );
        List<String> instrumentIds = GSON.fromJson(
                payload.instrumentIdsJson(),
                new TypeToken<List<String>>() {}.getType()
        );
        List<String> effectIds = GSON.fromJson(
                payload.effectIdsJson(),
                new TypeToken<List<String>>() {}.getType()
        );

        Minecraft mc = Minecraft.getInstance();
        Screen current = mc.screen;
        if (current instanceof TrackEditorScreen editor) {
            editor.updateTracks(tracks, instrumentIds, effectIds);
        } else {
            mc.setScreen(new TrackEditorScreen(tracks, instrumentIds, effectIds));
        }
    }

    public static void handleSaveResult(SaveResultPayload payload, IPayloadContext context) {
        if (context.player() != null) {
            context.player().displayClientMessage(Component.translatable(payload.reason()), false);
        }
    }

    /**
     * ADDED 2026-09-13 (user report, dedicated server): keeps THIS client's
     * own copy of MimiEffectsMod.TRACK_REGISTRY in sync with the server —
     * see TrackCacheSyncPayload's javadoc for why this is a separate,
     * screen-free payload from TracksSyncPayload. Never touches the
     * current screen.
     */
    public static void handleTrackCacheSync(TrackCacheSyncPayload payload, IPayloadContext context) {
        List<TrackConfig> tracks = GSON.fromJson(
                payload.tracksJson(),
                new TypeToken<List<TrackConfig>>() {}.getType()
        );
        MimiEffectsMod.TRACK_REGISTRY.clear();
        for (TrackConfig track : tracks) {
            MimiEffectsMod.TRACK_REGISTRY.register(track);
        }

        // Forces the creative tab (Note Scroll's per-track listing) to
        // recompute NOW instead of staying stuck with whatever it baked in
        // at login-time, before this payload arrived — see javadoc above.
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.player != null) {
            CreativeModeTabs.tryRebuildTabContents(mc.level.enabledFeatures(), mc.player.hasPermissions(2), mc.level.registryAccess());
        }
    }
}
