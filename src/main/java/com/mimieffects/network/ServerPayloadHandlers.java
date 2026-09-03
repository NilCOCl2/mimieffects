package com.mimieffects.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import com.mimieffects.MimiEffectsMod;
import com.mimieffects.track.TrackConfig;
import com.mimieffects.track.TrackLoader;
import com.mimieffects.track.TrackSyncUtil;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * All-server-side; never imports client classes, safe to reference from
 * the common @Mod entry point (unlike ClientPayloadHandlers, which must
 * only ever be referenced from the client-only entry point — see
 * MimiEffectsModClient).
 */
public final class ServerPayloadHandlers {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ServerPayloadHandlers() {
    }

    public static void handleSaveTrack(SaveTrackPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }

        // Never trust a client-supplied path — this check exists even
        // though only OPs can open the editor in the first place, because
        // the payload itself could in principle be crafted by a modified
        // client, not just sent through our own screen.
        if (!player.hasPermissions(2)) {
            PacketDistributor.sendToPlayer(player, new SaveResultPayload("mimieffects.editor.error.no_permission"));
            return;
        }

        Path tracksDir = MimiEffectsMod.tracksDir();
        Path resolved;
        try {
            resolved = resolveSafeChild(tracksDir, sanitizeFileName(payload.fileName()));
        } catch (IllegalArgumentException e) {
            PacketDistributor.sendToPlayer(player, new SaveResultPayload("mimieffects.editor.error.bad_filename"));
            return;
        }

        // Validate the JSON actually parses as a TrackConfig BEFORE writing
        // anything to disk — a syntax error from the editor should never
        // corrupt a previously-good file.
        try {
            TrackConfig parsed = GSON.fromJson(payload.json(), TrackConfig.class);
            if (parsed == null) {
                PacketDistributor.sendToPlayer(player, new SaveResultPayload("mimieffects.editor.error.empty"));
                return;
            }
        } catch (JsonSyntaxException e) {
            PacketDistributor.sendToPlayer(player, new SaveResultPayload("mimieffects.editor.error.invalid_json"));
            return;
        }

        try {
            Files.createDirectories(tracksDir);
            Files.write(resolved, payload.json().getBytes(StandardCharsets.UTF_8));
            TrackLoader.loadAll(tracksDir, MimiEffectsMod.TRACK_REGISTRY);

            // Reply with a fresh full sync — doubles as the save
            // acknowledgement and guarantees the editor reflects exactly
            // what's on disk, not just what the client thinks it sent.
            PacketDistributor.sendToPlayer(player, TrackSyncUtil.buildSyncPayload(tracksDir));
        } catch (IOException e) {
            MimiEffectsMod.LOGGER.error("Failed to save track file {}", resolved, e);
            PacketDistributor.sendToPlayer(player, new SaveResultPayload("mimieffects.editor.error.io"));
        }
    }

    /** Strips anything that isn't a safe bare filename component. */
    private static String sanitizeFileName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException("blank filename");
        }
        String name = rawName.endsWith(".json") ? rawName : rawName + ".json";
        if (!name.matches("[a-zA-Z0-9_\\-]+\\.json")) {
            throw new IllegalArgumentException("unsafe filename: " + rawName);
        }
        return name;
    }

    /** Resolves childName under baseDir and verifies it didn't escape via ".." or symlink tricks. */
    private static Path resolveSafeChild(Path baseDir, String childName) {
        Path resolved = baseDir.resolve(childName).normalize();
        if (!resolved.startsWith(baseDir.normalize())) {
            throw new IllegalArgumentException("path traversal attempt: " + childName);
        }
        return resolved;
    }
}
