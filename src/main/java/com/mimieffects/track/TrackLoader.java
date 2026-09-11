package com.mimieffects.track;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ТЗ §2: scans config/mimieffects/tracks/*.json, one file per track, and
 * fills a TrackRegistry. Gson is a transitive Minecraft/NeoForge dependency
 * at runtime, so no extra library needs to be shaded — this class cannot be
 * compiled standalone in an environment without the Minecraft/NeoForge
 * classpath, unlike the pure-logic classes in the orchestrator package.
 *
 * Called on mod init and again on /mimieffects reload (ТЗ §8 criterion).
 */
public final class TrackLoader {

    private static final Gson GSON = new GsonBuilder().create();

    private TrackLoader() {
    }

    /**
     * @param tracksDir path to config/mimieffects/tracks
     * @param registry  registry to repopulate; cleared before loading
     * @return number of files successfully loaded
     */
    public static int loadAll(Path tracksDir, TrackRegistry registry) {
        registry.clear();
        int loaded = 0;

        if (!Files.isDirectory(tracksDir)) {
            return 0;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tracksDir, "*.json")) {
            for (Path file : stream) {
                try {
                    String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                    TrackConfig config = GSON.fromJson(json, TrackConfig.class);
                    if (config == null) {
                        logWarn(file, "file is empty or parsed to null, skipping");
                        continue;
                    }
                    // ADDED 2026-09-06: this exact class of bug has hit
                    // twice now — a track_id left as a copy-pasted
                    // placeholder (e.g. "REPLACE_WITH_REAL_FILEID_UUID")
                    // silently never matches anything, since real track
                    // ids are UUIDs computed by MIMI itself. Fail loud
                    // instead of silently doing nothing at runtime.
                    if (config.track_id != null && !isValidUuid(config.track_id)) {
                        logWarn(file, "track_id \"" + config.track_id + "\" is not a valid UUID — "
                                + "this track will NEVER match a real song and its effects/mob_purge "
                                + "will never trigger. Real track_id values are generated automatically "
                                + "by TrackScaffolder once the matching .mid file exists in "
                                + "config/mimi/server_midi_files/ and you run /mimieffects reload.");
                        continue;
                    }
                    registry.register(config);
                    loaded++;
                } catch (JsonSyntaxException e) {
                    logWarn(file, "invalid JSON: " + e.getMessage());
                } catch (IOException e) {
                    logWarn(file, "could not read file: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            logWarn(tracksDir, "could not list tracks directory: " + e.getMessage());
        }

        return loaded;
    }

    private static boolean isValidUuid(String s) {
        try {
            java.util.UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void logWarn(Path file, String message) {
        // TODO wire to the mod's real logger (org.slf4j.Logger via LogUtils) once
        // this class is loaded inside the mod; kept plain here to stay
        // dependency-light for early scaffolding.
        System.err.println("[MimiEffects] TrackLoader: " + file + ": " + message);
    }
}
