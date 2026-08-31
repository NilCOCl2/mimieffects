package com.mimieffects.track;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ТЗ: Map<trackId, TrackConfig> in memory (§2). Rebuilt wholesale on
 * /mimieffects reload by TrackLoader — no incremental mutation needed.
 */
public class TrackRegistry {

    /** Sentinel key for the track whose track_id is JSON null (universal improvisation). */
    public static final String IMPROVISATION_KEY = "\0__improv__";

    private final Map<String, TrackConfig> byId = new HashMap<>();

    public void clear() {
        byId.clear();
    }

    public void register(TrackConfig config) {
        String key = config.track_id == null ? IMPROVISATION_KEY : config.track_id;
        byId.put(key, config);
    }

    /** @param songId null means "no specific song" -> look up the improvisation track. */
    public TrackConfig find(String songId) {
        String key = songId == null ? IMPROVISATION_KEY : songId;
        return byId.get(key);
    }

    public List<TrackConfig> all() {
        return new ArrayList<>(byId.values());
    }

    public int size() {
        return byId.size();
    }
}
