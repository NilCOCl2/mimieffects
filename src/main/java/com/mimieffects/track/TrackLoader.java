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

    private static void logWarn(Path file, String message) {
        // TODO wire to the mod's real logger (org.slf4j.Logger via LogUtils) once
        // this class is loaded inside the mod; kept plain here to stay
        // dependency-light for early scaffolding.
        System.err.println("[MimiEffects] TrackLoader: " + file + ": " + message);
    }
}
