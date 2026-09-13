package com.mimieffects.track;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.mimieffects.MimiEffectsMod;
import com.mimieffects.network.TrackCacheSyncPayload;
import com.mimieffects.network.TrackFileDto;
import com.mimieffects.network.TracksSyncPayload;

import io.github.tofodroid.mods.mimi.common.config.instrument.InstrumentConfig;
import io.github.tofodroid.mods.mimi.common.config.instrument.InstrumentSpec;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the payload sent to a client's Track Editor screen: every
 * config/mimieffects/tracks/*.json file's raw text, unparsed, plus the
 * live registry contents needed for the picker UI (2026-09-03 request:
 * "choose from a list, not free text, the same way command
 * tab-completion knows every effect/item"). Deliberately lives in the
 * track package (not network) since it touches the filesystem and
 * registries directly — network/ stays free of that.
 */
public final class TrackSyncUtil {

    private static final Gson GSON = new GsonBuilder().create();

    private TrackSyncUtil() {
    }

    public static TracksSyncPayload buildSyncPayload(Path tracksDir) throws IOException {
        List<TrackFileDto> files = new ArrayList<>();

        if (Files.isDirectory(tracksDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(tracksDir, "*.json")) {
                for (Path file : stream) {
                    String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                    files.add(new TrackFileDto(file.getFileName().toString(), json));
                }
            }
        }

        return new TracksSyncPayload(
                GSON.toJson(files),
                GSON.toJson(availableInstrumentIds()),
                GSON.toJson(availableEffectIds())
        );
    }

    /**
     * ADDED 2026-09-13 (user report, dedicated server): builds the
     * lightweight, screen-free payload that keeps every connected client's
     * OWN copy of MimiEffectsMod.TRACK_REGISTRY in sync — see
     * TrackCacheSyncPayload's javadoc for the full story. Serializes the
     * already-parsed, already-validated TrackConfig objects currently in
     * the registry (not a fresh disk read), so what's sent is exactly
     * what's actually active right now.
     */
    public static TrackCacheSyncPayload buildCacheSyncPayload() {
        return new TrackCacheSyncPayload(GSON.toJson(MimiEffectsMod.TRACK_REGISTRY.all()));
    }

    /**
     * FIX (2026-09-13, user report): this used to be "every item registered
     * under the mimi namespace", on the assumption that MIMI only puts
     * instruments there. Wrong — MIMI also registers non-instrument
     * infrastructure under the same namespace (the ledcube_a..h decorative
     * blocks the user spotted, plus broadcaster/receiver/relay/transmitter/
     * switchboard/sourcelinker/filecaster's radio-network blocks,
     * conductor/listener/mechanicalmaestro/tuningtable/effectemitter/
     * settingssync), so a track could end up "requiring" a decorative LED
     * cube to unlock its effects.
     *
     * MIMI's own InstrumentConfig.getAllInstruments() (a public static API,
     * not guessed) is the actual authoritative instrument list — every
     * InstrumentSpec.registryName here corresponds 1:1 to a real playable
     * instrument's item id ("mimi:" + registryName), matching
     * data/mimi/instruments/*.json exactly, verified by reading the real
     * MIMI 4.2.0 jar rather than assumed. Using the live API instead of
     * hand-copying that list means it automatically reflects whatever MIMI
     * version + custom.json additions are actually loaded on this server.
     */
    public static List<String> availableInstrumentIds() {
        List<String> ids = new ArrayList<>();
        for (InstrumentSpec spec : InstrumentConfig.getAllInstruments()) {
            ids.add("mimi:" + spec.registryName);
        }
        ids.sort(String::compareTo);
        return ids;
    }

    /**
     * Every registered mob effect, across ALL loaded mods (not just
     * vanilla/MIMI) — a track's effects[] can legitimately reference any
     * effect present on the server, so this deliberately isn't filtered
     * by namespace the way instruments are.
     */
    private static List<String> availableEffectIds() {
        List<String> ids = new ArrayList<>();
        for (ResourceLocation id : BuiltInRegistries.MOB_EFFECT.keySet()) {
            ids.add(id.toString());
        }
        ids.sort(String::compareTo);
        return ids;
    }
}
