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
 * entries live in assets/mimieffects/lang/{en_us,ru_ru}.json. Section
 * headers use the conventional "<modid>.configuration.<section>" key that
 * NeoForge's screen looks up automatically for category tab titles.
 */
public final class GlobalConfig {

    public static final ModConfigSpec SPEC;

    // --- [General] ---
    public static final ModConfigSpec.IntValue BASE_RADIUS;
    public static final ModConfigSpec.IntValue TICK_INTERVAL;
    public static final ModConfigSpec.IntValue SESSION_TIMEOUT_SECONDS;

    // --- [Scaling] ---
    public static final ModConfigSpec.DoubleValue PER_PLAYER_BONUS;
    public static final ModConfigSpec.DoubleValue FULL_ENSEMBLE_BONUS;

    // --- [Dissonance] ---
    public static final ModConfigSpec.ConfigValue<String> DISSONANCE_BEHAVIOR;
    public static final ModConfigSpec.IntValue REDUCE_BY_LEVELS;

    // --- [Defaults] ---
    public static final ModConfigSpec.ConfigValue<String> DEFAULT_AFFECTS;
    public static final ModConfigSpec.IntValue DEFAULT_DURATION_SECONDS;

    private static final String TR = "mimieffects.configuration.";

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("General");
        BASE_RADIUS = builder
                .translation(TR + "general.base_radius")
                .comment("Радиус проверки оркестра (блоков)")
                .defineInRange("base_radius", 15, 1, 128);
        TICK_INTERVAL = builder
                .translation(TR + "general.tick_interval")
                .comment("Как часто Orchestrator обновляет эффекты (тиков, 20 = 1 сек)")
                .defineInRange("tick_interval", 100, 20, 1200);
        SESSION_TIMEOUT_SECONDS = builder
                .translation(TR + "general.session_timeout_seconds")
                .comment("Через сколько секунд сессия игрока считается мёртвой, если он перестал играть")
                .defineInRange("session_timeout_seconds", 10, 1, 300);
        builder.pop();

        builder.push("Scaling");
        PER_PLAYER_BONUS = builder
                .translation(TR + "scaling.per_player_bonus")
                .comment("Бонус к уровню эффекта за каждого дополнительного игрока в оркестре")
                .defineInRange("per_player_bonus", 0.25, 0.0, 10.0);
        FULL_ENSEMBLE_BONUS = builder
                .translation(TR + "scaling.full_ensemble_bonus")
                .comment("Бонус за полный набор инструментов (все из списка присутствуют)")
                .defineInRange("full_ensemble_bonus", 0.5, 0.0, 10.0);
        builder.pop();

        builder.push("Dissonance");
        DISSONANCE_BEHAVIOR = builder
                .translation(TR + "dissonance.behavior")
                .comment(
                        "Что делать, если в одном кластере играют разные треки:",
                        "\"reduce\" — ослабить эффект, \"cancel\" — не дать вообще, \"ignore\" — всем по отдельности"
                )
                .define("behavior", "reduce");
        REDUCE_BY_LEVELS = builder
                .translation(TR + "dissonance.reduce_by_levels")
                .comment("На сколько уровней понижать при диссонансе (если behavior = reduce)")
                .defineInRange("reduce_by_levels", 2, 0, 20);
        builder.pop();

        builder.push("Defaults");
        DEFAULT_AFFECTS = builder
                .translation(TR + "defaults.affects")
                .comment("Кого охватывать, если в треке не указано: \"ensemble\" или \"all_nearby\"")
                .define("affects", "all_nearby");
        DEFAULT_DURATION_SECONDS = builder
                .translation(TR + "defaults.duration_seconds")
                .comment("Длительность эффекта в секундах, если в треке не указано")
                .defineInRange("duration_seconds", 5, 1, 3600);
        builder.pop();

        SPEC = builder.build();
    }

    private GlobalConfig() {
    }
}
