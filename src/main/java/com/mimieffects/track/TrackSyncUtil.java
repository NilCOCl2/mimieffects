package com.mimieffects.track;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.mimieffects.network.TrackFileDto;
import com.mimieffects.network.TracksSyncPayload;

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
     * Every item registered under the "mimi" namespace. Instruments are
     * ordinary registered Items in MIMI (both handheld and block
     * instruments have an Item form — blocks need one to be placeable) —
     * see REVERSE_ENGINEERING.md §6 for how this matches MIMI's own
     * data/mimi/instruments/*.json registry. Using the live Item registry
     * instead of hand-copying that list means it automatically reflects
     * whatever MIMI version + custom.json additions are actually loaded
     * on this server, not a snapshot from whenever we last read the source.
     */
    private static List<String> availableInstrumentIds() {
        List<String> ids = new ArrayList<>();
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            if ("mimi".equals(id.getNamespace())) {
                ids.add(id.toString());
            }
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
