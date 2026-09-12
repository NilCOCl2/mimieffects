package com.mimieffects.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * ТЗ §3.1 — config/mimieffects/common.toml.
 *
 * Registered as a SERVER config so NeoForge syncs it to clients on join
 * (ConfigSync) and exposes it in the vanilla "Mods" config screen when the
 * player is the local host. On a real dedicated server that screen is
 * disabled for remote clients, so admins edit the TOML directly and run
 * /mimieffects reload — see the ADR note in project README for why we did
 * not add a Configured-style in-game editor for this file in the MVP.
 *
 * FIX (2026-09-01): every value now has an explicit .translation(key) —
 * without it, NeoForge's auto-generated ConfigurationScreen falls back to
 * showing the raw TOML path ("general.base_radius") as the button label,
 * which is exactly the "technical names" the user reported. Matching
 * entries live in assets/mimieffects/lang/{en_us,ru_ru}.json.
 *
 * FIX (2026-09-12): that first fix only covered individual VALUES —
 * section/category headers (the push()ed groups below) need their OWN
 * separate .translation(key) call made IMMEDIATELY BEFORE the matching
 * push(), not "automatically" as the old comment above claimed. Checked
 * NeoForge's own source
 * (ModConfigSpec.Builder#push -> levelTranslationKeys.put(currentPath,
 * context.getTranslationKey())) to confirm: push() only records a
 * translation key if one was set on the builder's context right before
 * it's called, and CategoryScreen falls back to raw
 * "<modid>.configuration.<pushed name, case preserved>" (e.g.
 * "mimieffects.configuration.Debug") when nothing was recorded — exactly
 * the string the user saw un-translated in the config screen, since we
 * were only ever calling .translation() on the fields *inside* each
 * section, never on the section itself.
 *
 * REORGANIZED (2026-09-12, user request): 7 top-level sections collapsed
 * to 4 by grouping what's actually related — General absorbed Defaults
 * (both are "fallback mechanics" knobs), Scaling+Dissonance merged into
 * Ensemble (both are pure inputs to EffectCalculator), Protection+
 * Hostility merged into MobRules (both are entity-classification rules
 * for purge/targeting). Debug stays on its own since it's a different
 * kind of setting (diagnostics, not gameplay tuning). Leaf VALUE
 * translation keys are untouched (e.g. "defaults.affects" still reads
 * that even though it now lives under [General] in the TOML) — a
 * .translation(key) is just a lookup string, it doesn't have to match
 * where the value is actually nested, so no lang file churn was needed
 * for the fields themselves, only for the section headers.
 */
public final class GlobalConfig {

    public static final ModConfigSpec SPEC;

    // --- [General] (absorbed the old [Defaults]) ---
    public static final ModConfigSpec.IntValue BASE_RADIUS;
    public static final ModConfigSpec.IntValue TICK_INTERVAL;
    public static final ModConfigSpec.IntValue SESSION_TIMEOUT_SECONDS;
    public static final ModConfigSpec.ConfigValue<String> DEFAULT_AFFECTS;
    public static final ModConfigSpec.IntValue DEFAULT_DURATION_SECONDS;
    // ADDED 2026-09-13 (user request): the Track Editor's 5-instrument/
    // 5-effect row cap used to be a hardcoded Java constant — an admin who
    // wants fewer (to keep tracks simple) or more (hand-building a big
    // full-band arrangement) had no way to change it without a rebuild.
    public static final ModConfigSpec.IntValue MAX_INSTRUMENTS_PER_ARRANGEMENT;
    public static final ModConfigSpec.IntValue MAX_EFFECTS_PER_ARRANGEMENT;

    // --- [Ensemble] (old [Scaling] + [Dissonance] — both feed EffectCalculator) ---
    public static final ModConfigSpec.DoubleValue PER_PLAYER_BONUS;
    public static final ModConfigSpec.DoubleValue FULL_ENSEMBLE_BONUS;
    public static final ModConfigSpec.EnumValue<DissonanceBehavior> DISSONANCE_BEHAVIOR;
    public static final ModConfigSpec.IntValue REDUCE_BY_LEVELS;

    // --- [MobRules] (old [Protection] + [Hostility] — both are entity-classification rules) ---
    // Added 2026-09-04: server-wide exemption list so bosses can never be
    // killed by mob_purge NOR healed/harmed by regular track effects
    // (targets hostile/friendly/etc), regardless of what any individual
    // track's JSON says. Deliberately GLOBAL, not per-track — a per-track
    // exclude list is too easy for an admin to forget on any one file;
    // this way it's enforced everywhere at once. See BossProtection.java
    // for where this is actually applied.
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> PROTECTED_ENTITY_TYPES;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> PROTECTED_ENTITY_TAGS;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> PROTECTED_ENTITY_NAMESPACES;
    public static final ModConfigSpec.DoubleValue BOSS_MAX_HEALTH_THRESHOLD;
    // ADDED 2026-09-12 (user request): boss protection normally blocks BOTH
    // mob_purge damage AND regular track effects (heal/buff) on a boss-tier
    // entity — see BossProtection's own javadoc. This lets an admin opt
    // mob_purge specifically back IN to damaging boss-tier mobs (by exact
    // type/tag/namespace match OR by the max-health threshold above)
    // without touching the separate "never heal/buff a boss" protection,
    // which stays on unconditionally. Deliberately global (like the rest of
    // BossProtection), not per-track, per the user's explicit request.
    public static final ModConfigSpec.BooleanValue MOB_PURGE_IGNORES_BOSS_PROTECTION;
    // Added 2026-09-05: the base "isHostile" check (Enemy marker interface)
    // misses a lot of real modded threats — many MCreator-built mods (the
    // user's concrete examples: vampires, bats, maggots; also Born in
    // Chaos's anglerfish underwater) don't bother implementing Enemy even
    // though they're clearly hostile. Three admin-configurable, additive
    // ways to widen "hostile" beyond Enemy + MobCategory.MONSTER (the new
    // built-in fallback — see EntityHostilityUtil): exact type, tag, or
    // (most useful in practice) a whole mod namespace treated as entirely
    // hostile. Namespaces default to the two mods the user explicitly
    // named as all-hostile-creature packs — never guessed beyond that.
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> EXTRA_HOSTILE_TYPES;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> EXTRA_HOSTILE_TAGS;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> EXTRA_HOSTILE_NAMESPACES;

    // --- [Generation] ---
    // ADDED 2026-09-12 (user request): powers "/mimieffects genscrolls",
    // which auto-fills still-unconfigured track stubs with a random
    // combination of vanilla buff effects and matching instrument
    // requirements — see ScrollGenerator for the actual logic.
    public static final ModConfigSpec.BooleanValue PREFER_COMMON_INSTRUMENTS;

    // --- [Debug] ---
    // Added 2026-09-06 per user request: this exact bug pattern (mob_purge
    // "just not working" for a reason buried several method calls deep —
    // unresolved track, no matching arrangement, an instrument/environment
    // gate, a throttle window) has come up repeatedly and each time took a
    // manual back-and-forth to diagnose. This makes the mod explain itself.
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING;
    public static final ModConfigSpec.BooleanValue DEBUG_CHAT_FEEDBACK;
    public static final ModConfigSpec.IntValue DEBUG_THROTTLE_TICKS;

    private static final String TR = "mimieffects.configuration.";

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.translation(TR + "general").push("General");
        BASE_RADIUS = builder
                .translation(TR + "general.base_radius")
                .comment("Radius of the ensemble detection area, in blocks.")
                .defineInRange("base_radius", 15, 1, 128);
        TICK_INTERVAL = builder
                .translation(TR + "general.tick_interval")
                .comment("How often the orchestrator updates effects, in ticks (20 = 1 second).")
                .defineInRange("tick_interval", 100, 20, 1200);
        SESSION_TIMEOUT_SECONDS = builder
                .translation(TR + "general.session_timeout_seconds")
                .comment("Seconds of silence before a player session is considered expired.")
                .defineInRange("session_timeout_seconds", 10, 1, 300);
        DEFAULT_AFFECTS = builder
                .translation(TR + "defaults.affects")
                .comment("Who to affect when not specified in the track: \"ensemble\" or \"all_nearby\".")
                .define("affects", "all_nearby");
        DEFAULT_DURATION_SECONDS = builder
                .translation(TR + "defaults.duration_seconds")
                .comment("Default effect duration, in seconds, when not specified in the track.")
                .defineInRange("duration_seconds", 5, 1, 3600);
        MAX_INSTRUMENTS_PER_ARRANGEMENT = builder
                .translation(TR + "general.max_instruments_per_arrangement")
                .comment("Max instrument rows the in-game Track Editor lets you add per arrangement.")
                .defineInRange("max_instruments_per_arrangement", 5, 1, 20);
        MAX_EFFECTS_PER_ARRANGEMENT = builder
                .translation(TR + "general.max_effects_per_arrangement")
                .comment("Max effect rows the in-game Track Editor lets you add per arrangement.")
                .defineInRange("max_effects_per_arrangement", 5, 1, 20);
        builder.pop();

        builder.translation(TR + "ensemble").push("Ensemble");
        PER_PLAYER_BONUS = builder
                .translation(TR + "scaling.per_player_bonus")
                .comment("Effect level bonus per additional player in the ensemble.")
                .defineInRange("per_player_bonus", 0.25, 0.0, 10.0);
        FULL_ENSEMBLE_BONUS = builder
                .translation(TR + "scaling.full_ensemble_bonus")
                .comment("Bonus when all required instruments are present.")
                .defineInRange("full_ensemble_bonus", 0.5, 0.0, 10.0);
        DISSONANCE_BEHAVIOR = builder
                .translation(TR + "dissonance.behavior")
                .comment(
                        "What to do when players in the same cluster play different tracks:",
                        "REDUCE = lower the effect level, CANCEL = no effect, IGNORE = apply independently."
                )
                .defineEnum("behavior", DissonanceBehavior.REDUCE);
        REDUCE_BY_LEVELS = builder
                .translation(TR + "dissonance.reduce_by_levels")
                .comment("How many levels to reduce the effect by on dissonance (behavior = reduce).")
                .defineInRange("reduce_by_levels", 2, 0, 20);
        builder.pop();

        builder.translation(TR + "mob_rules").push("MobRules");
        PROTECTED_ENTITY_TYPES = builder
                .translation(TR + "protection.protected_entity_types")
                .comment(
                        "Exact entity type IDs that are NEVER affected by mob_purge",
                        "or effect targeting (hostile/friendly/neutral), regardless of track config.",
                        "Example: \"minecraft:ender_dragon\""
                )
                .defineList(
                        "protected_entity_types",
                        java.util.List.of("minecraft:ender_dragon", "minecraft:wither"),
                        () -> "minecraft:ender_dragon",
                        obj -> obj instanceof String
                );
        PROTECTED_ENTITY_TAGS = builder
                .translation(TR + "protection.protected_entity_tags")
                .comment(
                        "Entity type tags (with or without leading # — both accepted).",
                        "Entities matching any tag here are never affected.",
                        "Example: \"c:bosses\" if that tag is defined on your server."
                )
                .defineListAllowEmpty(
                        "protected_entity_tags",
                        java.util.List.of(),
                        () -> "c:bosses",
                        obj -> obj instanceof String
                );
        PROTECTED_ENTITY_NAMESPACES = builder
                .translation(TR + "protection.protected_entity_namespaces")
                .comment(
                        "All entities from these mods (by modid) are never affected.",
                        "Empty by default — use if you have a mod where ALL",
                        "creatures are bosses (rare; usually protected_entity_types is more precise)."
                )
                .defineListAllowEmpty(
                        "protected_entity_namespaces",
                        java.util.List.of(),
                        () -> "modid",
                        obj -> obj instanceof String
                );
        BOSS_MAX_HEALTH_THRESHOLD = builder
                .translation(TR + "protection.boss_max_health_threshold")
                .comment(
                        "If an entity's max health is >= this value, it is treated as a boss",
                        "and protected automatically. Also catches health-buffed",
                        "Apotheosis mobs without any Apotheosis-specific integration.",
                        "0 = disable this check."
                )
                .defineInRange("boss_max_health_threshold", 40.0, 0.0, 100000.0);
        MOB_PURGE_IGNORES_BOSS_PROTECTION = builder
                .translation(TR + "protection.mob_purge_ignores_boss_protection")
                .comment(
                        "If true, mob_purge can damage/kill boss-tier entities too (matched by",
                        "protected_entity_types/tags/namespaces above OR boss_max_health_threshold).",
                        "Regular track effects (heal/buff targeting) still NEVER affect a boss-tier",
                        "entity, regardless of this setting. Default false = purge respects boss",
                        "protection exactly like effects do."
                )
                .define("mob_purge_ignores_boss_protection", false);
        EXTRA_HOSTILE_TYPES = builder
                .translation(TR + "hostility.extra_hostile_types")
                .comment("Exact entity type IDs that are ALWAYS treated as hostile for mob_purge/targeting,",
                        "even if not marked as Enemy or in category MONSTER by the engine.")
                .defineListAllowEmpty(
                        "extra_hostile_types",
                        java.util.List.of(),
                        () -> "modid:entity",
                        obj -> obj instanceof String
                );
        EXTRA_HOSTILE_TAGS = builder
                .translation(TR + "hostility.extra_hostile_tags")
                .comment("Same as above but matched by entity type tag.")
                .defineListAllowEmpty(
                        "extra_hostile_tags",
                        java.util.List.of(),
                        () -> "c:hostile",
                        obj -> obj instanceof String
                );
        EXTRA_HOSTILE_NAMESPACES = builder
                .translation(TR + "hostility.extra_hostile_namespaces")
                .comment(
                        "All entities from these mods (by modid) are treated as hostile.",
                        "Defaults to Born in Chaos (born_in_chaos_v1) and Nightfall Plague (nightfall_plague),",
                        "both explicitly named as all-hostile-creature packs. Remove any entry that is wrong for your modpack."
                )
                .defineListAllowEmpty(
                        "extra_hostile_namespaces",
                        java.util.List.of("born_in_chaos_v1", "nightfall_plague"),
                        () -> "modid",
                        obj -> obj instanceof String
                );
        builder.pop();

        builder.translation(TR + "generation").push("Generation");
        PREFER_COMMON_INSTRUMENTS = builder
                .translation(TR + "generation.prefer_common_instruments")
                .comment(
                        "When genscrolls picks instruments for a new arrangement, favor common/",
                        "mainstream ones (winds, mics, guitars, violins/strings, flutes, piano/keys, drums)",
                        "over niche ones (synths, vibraphone, marimba, xylophone, etc.). Turn off for",
                        "uniformly random instrument picks instead."
                )
                .define("prefer_common_instruments", true);
        builder.pop();

        builder.translation(TR + "debug").push("Debug");
        DEBUG_LOGGING = builder
                .translation(TR + "debug.debug_logging")
                .comment(
                        "Logs a step-by-step trace of every note event's journey through the mod",
                        "(player found? track resolved? arrangement matched? mob_purge fired, and why/why not)",
                        "to the server log. Turn this on when something 'just doesn't work' instead of",
                        "guessing — the trace names the exact step where it stopped."
                )
                .define("debug_logging", false);
        DEBUG_CHAT_FEEDBACK = builder
                .translation(TR + "debug.debug_chat_feedback")
                .comment("Also sends the same trace as a chat message to the player who triggered it (only useful with debug_logging on).")
                .define("debug_chat_feedback", false);
        DEBUG_THROTTLE_TICKS = builder
                .translation(TR + "debug.debug_throttle_ticks")
                .comment("Minimum ticks between debug traces PER PLAYER, so a fast song doesn't flood the log/chat with one line per note.")
                .defineInRange("debug_throttle_ticks", 20, 0, 1200);
        builder.pop();

        SPEC = builder.build();
    }

    private GlobalConfig() {
    }
}
