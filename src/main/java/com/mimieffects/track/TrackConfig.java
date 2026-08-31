package com.mimieffects.track;

import java.util.List;
import java.util.Map;

/**
 * Deserialized form of one config/mimieffects/tracks/*.json file (ТЗ §3.2).
 * track_id == null marks the universal improvisation track.
 */
public class TrackConfig {
    public String track_id;
    public String display_name;
    public Arrangement[] arrangements = new Arrangement[0];

    /**
     * Optional "living music repels/kills pests" mechanic, independent of
     * the ensemble buff system above: requested directly by the user on
     * 2026-08-31 (see chat) — playing THIS specific track (not
     * improvisation) on a static/block instrument purges hostile mobs in a
     * large radius around it, for as long as the music keeps playing.
     * Null/absent means this track has no purge behavior (the default —
     * most tracks are just buff music).
     */
    public MobPurge mob_purge;

    public TrackConfig() {
        // for Gson
    }

    /**
     * Implements ТЗ §4.4 steps 3–5: pick the first arrangement (in file order)
     * whose coverage score is >= 1.0, or null if none qualify.
     */
    public Arrangement selectArrangement(Map<String, Integer> availableInstruments) {
        for (Arrangement a : arrangements) {
            if (a.coverageScore(availableInstruments) >= 1.0) {
                return a;
            }
        }
        return null;
    }
}
