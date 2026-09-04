package com.mimieffects.track;

/**
 * "Живая музыка отгоняет монстров" mechanic (user request, 2026-08-31):
 * playing this track on a static/block instrument kills qualifying hostile
 * mobs within radius_blocks, every purge_interval_ticks, for as long as
 * the music keeps playing.
 *
 * Deliberately mod-agnostic: rather than listing exact entity registry
 * IDs (which would require guessing at closed-source mods like "Born in
 * Chaos" — All Rights Reserved, MCreator-built, no public source to read),
 * targeting is done by RUNTIME ENVIRONMENT (is the entity currently in
 * water right now?) plus the vanilla hostility check (Monster/Enemy
 * marker interface). This works identically for vanilla zombies and any
 * modded aquatic horror without us ever needing to know that mod's class
 * names — see MobPurgeMatcher for the actual (pure, unit-tested) logic.
 */
public class MobPurge {

    /**
     * Radius in BLOCKS, not chunks. The user said "8 chunks"; 1 chunk = 16
     * blocks, so 8 chunks = 128. We store blocks here to avoid a silent
     * unit mix-up anywhere else in the codebase — always convert at the
     * config-authoring boundary, never downstream.
     */
    public int radius_blocks = 128;

    /**
     * If true (default, matches "статичном инструменте" from the request),
     * this only triggers for a player seated at a block instrument
     * (TileInstrument via EntitySeat — see REVERSE_ENGINEERING.md §2).
     * Playing the same track on a handheld instrument does NOT purge mobs.
     */
    public boolean requires_static_instrument = true;

    /**
     * Which mobs qualify, by where they physically are right now:
     * "underwater" = entity.isInWater() is true (e.g. Corpse Fish near an
     *   underwater base playing "Deep Blue"),
     * "surface"    = entity.isInWater() is false (ground + flying mobs
     *   near a surface base playing "Zemlya" — this deliberately does not
     *   try to distinguish "flying" from "ground" further, since vanilla
     *   has no reliable universal marker for that and the user's own
     *   example groups them together),
     * "any"        = both.
     */
    public String environment = "surface";

    /**
     * Safety default: only entities matching Minecraft's own hostile-mob
     * marker (net.minecraft.world.entity.monster.Enemy) are killed.
     * Passive animals, villagers, tamed pets, and — always, regardless of
     * this flag — players are never targeted. Set false only if you
     * really want to also clear passive/ambient mobs.
     */
    public boolean hostile_only = true;

    /**
     * How often (in ticks) to re-scan and purge while the track keeps
     * playing. 20 = once per second. Doesn't need to be as frequent as
     * note events; mobs don't teleport into a 128-block radius instantly.
     */
    public int purge_interval_ticks = 20;

    /**
     * CHANGED (2026-09-04): this used to mean "skip normal death via
     * entity.discard() instead of hurt()" — that bypassed vanilla damage
     * immunity/invulnerability entirely (a real abuse vector, since it
     * could remove even entities explicitly flagged invulnerable). Killing
     * is now ALWAYS done through the normal damage pipeline
     * (entity.hurt(...)), no exceptions — see MimiNoteBridge.purgeMobs.
     * This field is currently inert (kept for schema/JSON compatibility
     * and a possible future cosmetic-only use — e.g. suppressing the
     * death sound/particle without touching how the kill itself happens)
     * rather than removed outright, so existing track JSON files that set
     * it don't fail to parse.
     */
    public boolean silent = false;

    public MobPurge() {
        // for Gson
    }
}
