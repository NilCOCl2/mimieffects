import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Demonstrates TrackScaffolder's diffing logic end-to-end without requiring
 * Gson/NeoForge on the classpath. Uses a plain regex to pull "track_id"
 * out of existing JSON files (the real class uses Gson properly — this is
 * only here so the algorithm can be exercised standalone).
 */
public class ScaffolderDemo {

    static final String PREFIX = "mimi:midi/";
    static final Pattern TRACK_ID_RE = Pattern.compile("\"track_id\"\\s*:\\s*\"([^\"]*)\"");

    public static void main(String[] args) throws IOException {
        Path midiDir = Paths.get("demo/server_midi_files");
        Path tracksDir = Paths.get("demo/tracks");

        Set<String> knownIds = new HashSet<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(tracksDir, "*.json")) {
            for (Path f : ds) {
                String text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                Matcher m = TRACK_ID_RE.matcher(text);
                if (m.find()) {
                    knownIds.add(m.group(1));
                }
            }
        }
        System.out.println("Known track_ids before scaffolding: " + knownIds);

        List<String> created = new ArrayList<>();
        int scanned = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(midiDir, "*.mid")) {
            for (Path midi : ds) {
                scanned++;
                String fileName = midi.getFileName().toString();
                String baseName = fileName.substring(0, fileName.length() - ".mid".length());
                String trackId = PREFIX + fileName;

                if (knownIds.contains(trackId)) {
                    System.out.println("SKIP   " + fileName + " -> already configured (" + trackId + ")");
                    continue;
                }

                Path stubPath = tracksDir.resolve(baseName.replaceAll("[^a-zA-Z0-9_\\-]", "_") + ".json");
                String stubJson = "{\n"
                        + "  \"track_id\": \"" + trackId + "\",\n"
                        + "  \"display_name\": \"" + humanize(baseName) + "\",\n"
                        + "  \"arrangements\": [\n"
                        + "    {\n"
                        + "      \"name\": \"TODO: rename me\",\n"
                        + "      \"required_instruments\": {},\n"
                        + "      \"effects\": [],\n"
                        + "      \"affects\": \"all_nearby\"\n"
                        + "    }\n"
                        + "  ]\n"
                        + "}\n";
                Files.write(stubPath, stubJson.getBytes(StandardCharsets.UTF_8));
                created.add(stubPath.getFileName().toString());
                System.out.println("CREATE " + fileName + " -> " + stubPath.getFileName() + " (" + trackId + ")");
            }
        }

        System.out.println();
        System.out.println("scanned=" + scanned + " alreadyConfigured=" + knownIds.size() + " created=" + created.size());
        System.out.println("created files: " + created);

        // Assertions for this demo scenario
        boolean ok = scanned == 3 && created.size() == 2
                && created.contains("novy_trek.json")
                && created.contains("grustnaya_melodiya.json");
        System.out.println(ok ? "\nDEMO PASSED" : "\nDEMO FAILED");
        if (!ok) System.exit(1);
    }

    static String humanize(String baseName) {
        String spaced = baseName.replace('_', ' ').replace('-', ' ');
        return spaced.isEmpty() ? spaced : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
