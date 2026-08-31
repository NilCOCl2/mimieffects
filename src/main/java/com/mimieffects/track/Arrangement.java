package com.mimieffects.track;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One arrangement variant of a track (ТЗ §3.2): a required instrument set,
 * the effects it grants, who it affects and at what radius.
 */
public class Arrangement {
    public String name;

    /** key = MIMI instrument id (item or block), value = quantity required. */
    public Map<String, Integer> required_instruments = new LinkedHashMap<>();

    public EffectEntry[] effects = new EffectEntry[0];

    /** "ensemble" (only players actively playing) or "all_nearby". Null = use Defaults.affects. */
    public String affects;

    /** Null = use Defaults.duration_seconds via GlobalConfig. */
    public Integer duration_seconds;

    /** Null = use General.base_radius from GlobalConfig. */
    public Integer radius;

    /**
     * Only meaningful when required_instruments is empty (e.g. the universal
     * improvisation arrangement in ТЗ §9: "слабый эффект удачи для любых 2+
     * инструментов"). Guards against an empty required set matching trivially
     * for a single instrument. Ignored when required_instruments is non-empty.
     */
    public int min_instrument_count = 0;

    public Arrangement() {
        // for Gson
    }

    /** True when this arrangement has no instruments and no effects assigned —
     * the shape TrackScaffolder produces for a freshly auto-discovered song. */
    public boolean isUnconfigured() {
        return required_instruments.isEmpty() && (effects == null || effects.length == 0);
    }

    /**
     * Computes matched/required instrument coverage per ТЗ §4.4 step 3.
     * A single player closing multiple slots is handled upstream by the
     * detector supplying a flat multiset of available instruments — this
     * method only compares counts.
     *
     * @param available multiset of instrument ids currently present in the cluster
     * @return score in [0, +inf); arrangement is eligible when score >= 1.0
     */
    public double coverageScore(Map<String, Integer> available) {
        if (required_instruments.isEmpty()) {
            int totalAvailable = available.values().stream().mapToInt(Integer::intValue).sum();
            return totalAvailable >= min_instrument_count ? 1.0 : 0.0;
        }
        int totalRequired = 0;
        int totalMatched = 0;
        for (Map.Entry<String, Integer> req : required_instruments.entrySet()) {
            int needed = req.getValue();
            int have = available.getOrDefault(req.getKey(), 0);
            totalRequired += needed;
            totalMatched += Math.min(have, needed);
        }
        return totalRequired == 0 ? 1.0 : (double) totalMatched / totalRequired;
    }
}
