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

    /**
     * ADDED 2026-09-12 (user request, Note Scroll item): the scroll's
     * primary displayed name — a thematic/"spell" name distinct from the
     * song's own title (e.g. "Scroll of the Gale" for a speed track), not
     * auto-filled by TrackScaffolder since there's no reasonable default —
     * falls back to display_name (then the raw track_id) if left unset.
     */
    public String spell_name;

    /**
     * ADDED 2026-09-12 (user request): who performs/wrote the track, shown
     * as descriptive lore on the Note Scroll alongside display_name.
     * Optional — null/blank just omits the "artist —" prefix in the lore.
     */
    public String artist;

    /**
     * ADDED 2026-09-12 (user request): the raw .mid file name exactly as
     * it appears on disk (NOT humanized like display_name) — shown on the
     * Note Scroll as the "technical name, same as MIMI's transmitter
     * shows". Filled once by TrackScaffolder when the stub is created;
     * unlike track_id this can't be recovered later if lost (track_id is a
     * one-way hash of the file, not the name itself), so don't blank it
     * out by hand unless you're fine losing that display line.
     */
    public String midi_file_name;

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
