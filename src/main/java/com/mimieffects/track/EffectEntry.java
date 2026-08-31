package com.mimieffects.track;

/**
 * One effect line inside an Arrangement's "effects" array (ТЗ §3.2).
 * Field names match the JSON keys 1:1 for direct Gson deserialization.
 */
public class EffectEntry {
    public String effect;      // e.g. "minecraft:regeneration"
    public int base_level;
    public int max_level;

    public EffectEntry() {
        // for Gson
    }

    public EffectEntry(String effect, int baseLevel, int maxLevel) {
        this.effect = effect;
        this.base_level = baseLevel;
        this.max_level = maxLevel;
    }
}
