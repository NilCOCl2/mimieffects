package com.mimieffects.track;

/**
 * One effect line inside an Arrangement's "effects" array (ТЗ §3.2).
 * Field names match the JSON keys 1:1 for direct Gson deserialization.
 */
public class EffectEntry {
    public String effect;      // e.g. "minecraft:regeneration"
    public int base_level;
    public int max_level;

    /**
     * ADDED 2026-09-12 (user request): lets ONE arrangement layer effects
     * by ensemble size — e.g. effect #1 with min_ensemble_size=1 (always
     * on, and already grows stronger with more players via
     * EffectCalculator's per-player bonus) plus effect #2 with
     * min_ensemble_size=2 that only joins in once a second player shows
     * up. Default 1 = always included once the arrangement itself matches,
     * so existing tracks (no such field in their JSON) are unaffected.
     */
    public int min_ensemble_size = 1;

    public EffectEntry() {
        // for Gson
    }

    public EffectEntry(String effect, int baseLevel, int maxLevel) {
        this.effect = effect;
        this.base_level = baseLevel;
        this.max_level = maxLevel;
    }
}
