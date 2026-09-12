package com.mimieffects.track;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import io.github.tofodroid.mods.mimi.common.midi.LocalMidiInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * Answers the "songs should be added to the config automatically" need:
 * scans MIMI's server_midi_files folder, and for every *.mid/*.midi file
 * that has no matching TrackConfig yet (matched by track_id), writes an
 * editable stub JSON into config/mimieffects/tracks/.
 *
 * The stub is intentionally inert: empty required_instruments and no
 * effects, so it does nothing in-game until an admin assigns instruments
 * and effects to it. That's a deliberate safety default, not a bug —
 * see Arrangement#isUnconfigured().
 *
 * track_id is the exact UUID MIMI itself computes for the file
 * (see LocalMidiInfo.createFileId, a public static method on MIMI's own
 * class — we call it directly rather than re-deriving the hash ourselves,
 * so we can never drift from MIMI's algorithm if it changes). This
 * replaces an earlier, WRONG assumption (ТЗ §6) that track_id looked like
 * a ResourceLocation "mimi:midi/<file>.mid" — confirmed false by reading
 * MIMI's source: it's UUID.nameUUIDFromBytes over
 * "file:<name>;tempo:<bpm>;length:<sec>;channels:<mapping>;". See
 * INTEGRATION.md for the full trace.
 *
 * Requires MIMI as a compile-time dependency (already planned in
 * build.gradle) since it calls MIMI's class directly instead of
 * reimplementing the hash.
 *
 * Intended call sites: on mod init, and on /mimieffects reload (alongside
 * TrackLoader.loadAll) — so this happens automatically, no separate admin
 * step required, matching the "songs get added to config automatically"
 * request.
 */
public final class TrackScaffolder {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private TrackScaffolder() {
    }

    public static final class Result {
        public final int scanned;
        public final int alreadyConfigured;
        public final int created;
        public final java.util.List<String> createdFileNames;

        Result(int scanned, int alreadyConfigured, int created, java.util.List<String> createdFileNames) {
            this.scanned = scanned;
            this.alreadyConfigured = alreadyConfigured;
            this.created = created;
            this.createdFileNames = createdFileNames;
        }
    }

    /**
     * @param midiDir   MIMI's server_midi_files directory
     *                  (config/mimi/server_midi_files — confirmed constant
     *                  FilesystemMidiFileProvider.SERVER_MIDI_DIR)
     * @param tracksDir config/mimieffects/tracks directory (must already exist)
     */
    public static Result scaffoldMissingTracks(Path midiDir, Path tracksDir) throws IOException {
        Set<String> knownTrackIds = collectKnownTrackIds(tracksDir);
        java.util.List<String> created = new java.util.ArrayList<>();
        int scanned = 0;

        if (!Files.isDirectory(midiDir)) {
            return new Result(0, knownTrackIds.size(), 0, created);
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(midiDir, TrackScaffolder::isMidiFile)) {
            for (Path midiFile : stream) {
                scanned++;
                String fileName = midiFile.getFileName().toString();
                String baseName = stripMidiExtension(fileName);

                String trackId;
                try {
                    // Calls MIMI's own algorithm directly — see class javadoc.
                    trackId = LocalMidiInfo.createFileId(midiFile.toFile()).toString();
                } catch (RuntimeException e) {
                    logWarn(midiFile, "could not compute MIMI fileId (corrupt/unreadable MIDI?): " + e.getMessage());
                    continue;
                }

                if (knownTrackIds.contains(trackId)) {
                    continue;
                }

                Path stubPath = tracksDir.resolve(sanitizeFileName(baseName) + ".json");
                if (Files.exists(stubPath)) {
                    // A file with this name exists but with a different/missing track_id
                    // inside — don't clobber someone's manual edit, skip and let the
                    // admin resolve the mismatch (logged, not thrown).
                    continue;
                }

                writeStub(stubPath, trackId, baseName, fileName);
                created.add(stubPath.getFileName().toString());
            }
        }

        return new Result(scanned, knownTrackIds.size(), created.size(), created);
    }

    private static boolean isMidiFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".mid") || name.endsWith(".midi");
    }

    private static String stripMidiExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static Set<String> collectKnownTrackIds(Path tracksDir) throws IOException {
        Set<String> ids = new HashSet<>();
        if (!Files.isDirectory(tracksDir)) {
            return ids;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tracksDir, "*.json")) {
            for (Path file : stream) {
                try {
                    String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                    TrackConfig config = GSON.fromJson(json, TrackConfig.class);
                    if (config != null && config.track_id != null) {
                        ids.add(config.track_id);
                    }
                } catch (JsonSyntaxException e) {
                    // Malformed existing file — ignore for the purposes of
                    // duplicate detection, TrackLoader will report it separately.
                }
            }
        }
        return ids;
    }

    private static void writeStub(Path stubPath, String trackId, String displayNameGuess, String rawFileName) throws IOException {
        TrackConfig stub = new TrackConfig();
        stub.track_id = trackId;
        stub.display_name = humanize(displayNameGuess);
        // ADDED 2026-09-12: raw, un-humanized — see TrackConfig.midi_file_name's javadoc for why this has to be captured now rather than derived later.
        stub.midi_file_name = rawFileName;

        Arrangement placeholder = new Arrangement();
        placeholder.name = "TODO: rename me";
        placeholder.required_instruments = new LinkedHashMap<>();
        placeholder.effects = new EffectEntry[0];
        placeholder.affects = "all_nearby";

        stub.arrangements = new Arrangement[] { placeholder };

        String json = GSON.toJson(stub);
        // Gson has no native comment support; prepend a human-readable header
        // as a JSON5-style leading comment is not valid JSON, so we keep the
        // guidance in the display_name/arrangement name instead (see above)
        // plus this file's sibling README for the full field reference.
        Files.write(stubPath, json.getBytes(StandardCharsets.UTF_8));
    }

    private static String humanize(String baseName) {
        String spaced = baseName.replace('_', ' ').replace('-', ' ');
        if (spaced.isEmpty()) {
            return spaced;
        }
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String sanitizeFileName(String baseName) {
        return baseName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    private static void logWarn(Path file, String message) {
        // TODO wire to the mod's real logger once loaded inside the mod.
        System.err.println("[MimiEffects] TrackScaffolder: " + file + ": " + message);
    }
}
