package com.mimieffects.track;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Powers "/mimieffects genscrolls" (2026-09-12, user request): fills in
 * still-unconfigured track stubs (the ones TrackScaffolder creates with
 * empty required_instruments/effects — see Arrangement#isUnconfigured())
 * with a random, playable combination of ONLY vanilla buff effects, a
 * matching instrument requirement, an optional mob_purge, and a themed
 * spell_name — instead of an admin hand-filling every stub through the
 * Track Editor one by one.
 *
 * Deliberately conservative about scope: an arrangement that already has
 * ANY instrument or effect configured is left completely untouched, no
 * matter how this command is invoked — the user was explicit that this
 * must never clobber hand-tuned tracks.
 *
 * Design, per the user's own explicit choices (2026-09-12 chat):
 * - Effects are a COMBINATION (1-3), not one-effect-per-scroll — the
 *   vanilla buff pool is only ~17 entries, too small to stay unique
 *   per-scroll for very long. Repeats across different scrolls' effects
 *   are still soft-avoided (least-used-effect-first), just not banned.
 * - The more effects a scroll grants, the more DISTINCT instrument types
 *   its arrangement requires (one extra required instrument per effect
 *   tier), and each added effect is gated behind one more ensemble
 *   headcount via EffectEntry.min_ensemble_size (effect #1 works solo,
 *   effect #2 needs 2 players, etc.) — this is exactly the existing
 *   "layered effects" mechanic, just auto-populated instead of hand-set.
 * - mob_purge is added to AT MOST ONE track per run (2026-09-13, user
 *   request) — a single "hymn of exorcism", not a purge bonus scattered
 *   across many tracks — and that one track draws its effects from a
 *   health-themed subset (regeneration, health boost, absorption,
 *   saturation) instead of the general pool, so the purge track reads as
 *   "drives away evil AND restores you", not an arbitrary buff. Whenever
 *   mob_purge is added, requires_static_instrument is forced true (never
 *   triggers off a handheld performance).
 * - Instrument choice can favor "mainstream" families (winds/brass incl.
 *   flutes, microphones, guitars, violin-family strings, piano/keys,
 *   drums) over niche ones (synths, vibraphone, marimba, xylophone, and
 *   anything else) — toggle via GlobalConfig.PREFER_COMMON_INSTRUMENTS.
 */
public final class ScrollGenerator {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ScrollGenerator() {
    }

    public static final class Result {
        public final int scanned;
        public final int generated;
        public final List<String> generatedFileNames;

        Result(int scanned, int generated, List<String> generatedFileNames) {
            this.scanned = scanned;
            this.generated = generated;
            this.generatedFileNames = generatedFileNames;
        }
    }

    /**
     * Only vanilla, only clearly beneficial effects — a hand-picked
     * allowlist rather than "everything minus a blocklist", so a scroll can
     * never accidentally hand out Poison/Wither/etc. even if a future MC
     * version adds a new debuff. hero_of_the_village (a raid-discount
     * mechanic, not a duration buff) and the instant/one-shot effects are
     * deliberately excluded as a poor fit for "buff while the music plays".
     * IDs verified against the real 1.21.1 client jar's en_us.json, not
     * guessed.
     */
    private static final String[] POSITIVE_EFFECTS = {
            "minecraft:speed",
            "minecraft:haste",
            "minecraft:strength",
            "minecraft:jump_boost",
            "minecraft:regeneration",
            "minecraft:resistance",
            "minecraft:fire_resistance",
            "minecraft:water_breathing",
            "minecraft:invisibility",
            "minecraft:night_vision",
            "minecraft:health_boost",
            "minecraft:absorption",
            "minecraft:saturation",
            "minecraft:luck",
            "minecraft:slow_falling",
            "minecraft:conduit_power",
            "minecraft:dolphins_grace",
    };

    /**
     * ADDED 2026-09-13 (user request): the one track chosen to also get
     * mob_purge draws its effects from this health-themed subset instead of
     * the general POSITIVE_EFFECTS pool, so it reads as a "hymn of
     * exorcism" that also restores you, not a random buff bundle.
     */
    private static final String[] HEALTH_EFFECTS = {
            "minecraft:regeneration",
            "minecraft:health_boost",
            "minecraft:absorption",
            "minecraft:saturation",
    };

    /** Russian flavor word used to build spell_name — see buildSpellName(). */
    private static final Map<String, String> EFFECT_FLAVOR_RU = Map.ofEntries(
            Map.entry("minecraft:speed", "Прыткость"),
            Map.entry("minecraft:haste", "Расторопность"),
            Map.entry("minecraft:strength", "Мощь"),
            Map.entry("minecraft:jump_boost", "Прыгучесть"),
            Map.entry("minecraft:regeneration", "Исцеление"),
            Map.entry("minecraft:resistance", "Стойкость"),
            Map.entry("minecraft:fire_resistance", "Огнестойкость"),
            Map.entry("minecraft:water_breathing", "Дыхание глубин"),
            Map.entry("minecraft:invisibility", "Незримость"),
            Map.entry("minecraft:night_vision", "Совиный взор"),
            Map.entry("minecraft:health_boost", "Живучесть"),
            Map.entry("minecraft:absorption", "Щит"),
            Map.entry("minecraft:saturation", "Сытость"),
            Map.entry("minecraft:luck", "Удача"),
            Map.entry("minecraft:slow_falling", "Пёрышко"),
            Map.entry("minecraft:conduit_power", "Зов маяка"),
            Map.entry("minecraft:dolphins_grace", "Дельфинья грация")
    );

    /**
     * Matched against an instrument id's local part (after "mimi:"), by
     * prefix — MIMI registers numbered cosmetic variants of the same
     * instrument (piano/piano2/piano3/piano4, trumpet/trumpet2, ...), so a
     * prefix match covers every variant without listing each one. Built
     * from MIMI 4.2.0's real data/mimi/instruments/default.json registry
     * (verified, not guessed) grouped into exactly the families the user
     * named as "popular": wind/brass instruments including flutes,
     * microphones, guitars, the violin/strings family, piano/keyboard, and
     * drums. Anything not matching one of these — including the synths,
     * vibraphone, marimba and xylophone the user explicitly called out as
     * niche, and everything else (banjo, harp, organ, bells, etc.) — falls
     * through to the niche weight by default, per the user's own framing
     * ("и прочие, что я не упомянул").
     */
    private static final Set<String> POPULAR_INSTRUMENT_PREFIXES = Set.of(
            // wind/brass + flutes
            "trumpet", "trombone", "tuba", "frenchhorn", "saxophone", "clarinet",
            "flute", "piccolo", "ocarina", "bagpipe", "oboe", "accordion",
            "harmonica", "panflute", "recorder",
            // microphones
            "microphone",
            // guitars
            "acguitar", "elecguitar", "bassguitar",
            // violin family / bowed strings
            "violin", "viola", "cello", "contrabass",
            // piano/keys
            "piano", "keyboard",
            // drums
            "drums", "edrums"
    );

    private static final int POPULAR_WEIGHT = 5;
    private static final int NICHE_WEIGHT = 1;

    /**
     * ADDED 2026-09-13 (user request): "occasionally make [a multi-effect
     * scroll's] pairing thematic instead of arbitrary" — e.g. Water
     * Breathing + Dolphin's Grace read as one coherent "of the depths"
     * theme rather than two unrelated buffs. Rolled with THEMED_PAIR_CHANCE
     * whenever a scroll is getting 2+ effects; falls through to the normal
     * least-used pick otherwise (including when both pool members of every
     * pair are already unavailable).
     */
    private static final String[][] THEMED_PAIRS = {
            {"minecraft:water_breathing", "minecraft:dolphins_grace"},
            {"minecraft:speed", "minecraft:jump_boost"},
            {"minecraft:strength", "minecraft:resistance"},
            {"minecraft:night_vision", "minecraft:invisibility"},
            {"minecraft:fire_resistance", "minecraft:resistance"},
            {"minecraft:luck", "minecraft:saturation"},
    };
    private static final double THEMED_PAIR_CHANCE = 0.3;

    /**
     * ADDED 2026-09-13 (user request): "don't make duplicates out of the
     * whole set of scrolls" — a hard(er) constraint than the earlier
     * least-used-first soft anti-repetition: the exact SET of effects on a
     * scroll must not repeat elsewhere in this run. Capped rather than
     * unbounded, since the effect pools are small (17/4 entries) and a
     * large batch can genuinely exhaust every distinct combination of a
     * given size — at that point a repeat is accepted rather than looping
     * forever.
     */
    private static final int MAX_COMBO_ATTEMPTS = 20;

    /** Upper bound on effects/required-instruments/ensemble-tier per scroll when the caller doesn't specify one. */
    public static final int DEFAULT_MAX_ENSEMBLE_SIZE = 3;

    public static Result generate(Path tracksDir, List<String> availableInstrumentIds, boolean preferCommonInstruments) throws IOException {
        return generate(tracksDir, availableInstrumentIds, preferCommonInstruments, DEFAULT_MAX_ENSEMBLE_SIZE);
    }

    /**
     * @param maxEnsembleSize ADDED 2026-09-13 (user request): caps how many
     *                        effects (and therefore required instrument
     *                        types / ensemble-size tiers, since those scale
     *                        1:1 with effect count — see fillArrangement's
     *                        javadoc) a single generated scroll can demand.
     *                        Pass DEFAULT_MAX_ENSEMBLE_SIZE to keep the
     *                        original 1-3 behavior.
     */
    public static Result generate(Path tracksDir, List<String> availableInstrumentIds, boolean preferCommonInstruments, int maxEnsembleSize) throws IOException {
        List<String> generatedFiles = new ArrayList<>();
        int scanned = 0;

        if (!Files.isDirectory(tracksDir)) {
            return new Result(0, 0, generatedFiles);
        }

        Random random = new Random();

        // First pass: find every still-unconfigured track without touching
        // any of them yet — needed so exactly one of them (picked uniformly
        // at random below) can be chosen to receive mob_purge, instead of
        // each track independently rolling its own chance.
        List<Path> eligible = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tracksDir, "*.json")) {
            for (Path file : stream) {
                scanned++;
                TrackConfig config = tryParse(file);
                if (config == null || config.arrangements == null || config.arrangements.length == 0) {
                    continue;
                }
                if (!config.arrangements[0].isUnconfigured()) {
                    continue; // never touch a hand-configured track
                }
                eligible.add(file);
            }
        }

        Path purgeFile = eligible.isEmpty() ? null : eligible.get(random.nextInt(eligible.size()));

        // Soft anti-repetition across the whole run: effects used least so
        // far (across every track processed this invocation) are preferred
        // for the next track — see the user's "желательно, чтобы эффекты не
        // повторялись" (preferably, not a hard requirement).
        Map<String, Integer> effectUseCount = new HashMap<>();
        // Harder constraint on top: the exact combo (set of effects) a
        // scroll ends up with must not repeat elsewhere in this run — see
        // MAX_COMBO_ATTEMPTS's javadoc.
        Set<String> usedCombos = new HashSet<>();

        for (Path file : eligible) {
            TrackConfig config = tryParse(file);
            if (config == null) {
                continue; // shouldn't happen (already parsed once above), but don't crash a whole run over one file
            }
            Arrangement arrangement = config.arrangements[0];
            boolean withPurge = file.equals(purgeFile);

            String[] pool = withPurge ? HEALTH_EFFECTS : POSITIVE_EFFECTS;
            fillArrangement(arrangement, pool, availableInstrumentIds, preferCommonInstruments, random, effectUseCount, usedCombos, maxEnsembleSize);
            if (withPurge) {
                MobPurge purge = new MobPurge();
                // A generated track that also purges mobs must require the
                // block/stationary instrument — never let a handheld
                // performance trigger mob purge (user's explicit instruction).
                purge.requires_static_instrument = true;
                config.mob_purge = purge;
            }

            config.spell_name = buildSpellName(arrangement, withPurge);
            if (arrangement.name == null || arrangement.name.isBlank() || "TODO: rename me".equals(arrangement.name)) {
                arrangement.name = config.spell_name;
            }

            Files.write(file, GSON.toJson(config).getBytes(StandardCharsets.UTF_8));
            generatedFiles.add(file.getFileName().toString());
        }

        return new Result(scanned, generatedFiles.size(), generatedFiles);
    }

    private static TrackConfig tryParse(Path file) throws IOException {
        try {
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            return GSON.fromJson(json, TrackConfig.class);
        } catch (JsonSyntaxException e) {
            return null; // broken file — leave it for the admin, TrackLoader already reports it separately
        }
    }

    private static void fillArrangement(Arrangement arrangement, String[] effectPool, List<String> availableInstrumentIds,
                                         boolean preferCommon, Random random, Map<String, Integer> effectUseCount,
                                         Set<String> usedCombos, int maxEnsembleSize) {
        int effectCount = weightedEffectCount(random, maxEnsembleSize);
        List<String> chosen = chooseEffects(effectPool, effectCount, random, effectUseCount, usedCombos);

        EffectEntry[] effects = new EffectEntry[chosen.size()];
        Map<String, Integer> requiredInstruments = new LinkedHashMap<>();
        List<String> usedInstruments = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            EffectEntry entry = new EffectEntry(chosen.get(i), 1, 2);
            // Layers effects by ensemble size (existing mechanic, see
            // EffectEntry's own javadoc): the first effect works solo, each
            // additional one in the combo needs one more player — this is
            // the "more effects = more players/instruments needed" request.
            entry.min_ensemble_size = i + 1;
            effects[i] = entry;

            String instrument = pickInstrument(availableInstrumentIds, usedInstruments, preferCommon, random);
            if (instrument != null) {
                usedInstruments.add(instrument);
                requiredInstruments.put(instrument, 1);
            }
        }

        arrangement.effects = effects;
        arrangement.required_instruments = requiredInstruments;
    }

    /**
     * Picks the final effect combo for one scroll: tries a themed pair
     * first (chance-gated), fills any remaining slots with least-used
     * picks, then re-rolls (bounded) if the exact resulting combo has
     * already been used elsewhere in this run.
     */
    private static List<String> chooseEffects(String[] effectPool, int effectCount, Random random,
                                               Map<String, Integer> effectUseCount, Set<String> usedCombos) {
        List<String> chosen = chooseEffectsOnce(effectPool, effectCount, random, effectUseCount);
        for (int attempt = 1; attempt < MAX_COMBO_ATTEMPTS && usedCombos.contains(comboKey(chosen)); attempt++) {
            chosen = chooseEffectsOnce(effectPool, effectCount, random, effectUseCount);
        }
        usedCombos.add(comboKey(chosen));
        for (String effect : chosen) {
            effectUseCount.merge(effect, 1, Integer::sum);
        }
        return chosen;
    }

    /** One attempt at picking a combo — read-only against effectUseCount, no side effects, so a discarded attempt costs nothing. */
    private static List<String> chooseEffectsOnce(String[] effectPool, int effectCount, Random random, Map<String, Integer> effectUseCount) {
        List<String> pool = new ArrayList<>(List.of(effectPool));
        List<String> chosen = new ArrayList<>();

        if (effectCount >= 2 && random.nextDouble() < THEMED_PAIR_CHANCE) {
            List<String[]> viablePairs = new ArrayList<>();
            for (String[] pair : THEMED_PAIRS) {
                if (pool.contains(pair[0]) && pool.contains(pair[1])) {
                    viablePairs.add(pair);
                }
            }
            if (!viablePairs.isEmpty()) {
                String[] pair = viablePairs.get(random.nextInt(viablePairs.size()));
                chosen.add(pair[0]);
                chosen.add(pair[1]);
                pool.remove(pair[0]);
                pool.remove(pair[1]);
            }
        }

        while (chosen.size() < effectCount && !pool.isEmpty()) {
            String pick = pickLeastUsed(pool, effectUseCount, random);
            pool.remove(pick);
            chosen.add(pick);
        }
        return chosen;
    }

    /** Order-independent identity of an effect combo, for duplicate detection. */
    private static String comboKey(List<String> chosen) {
        List<String> sorted = new ArrayList<>(chosen);
        Collections.sort(sorted);
        return String.join("+", sorted);
    }

    private static int weightedEffectCount(Random random, int maxEnsembleSize) {
        int cap = Math.max(1, maxEnsembleSize);
        double roll = random.nextDouble();
        int count = roll < 0.5 ? 1 : roll < 0.85 ? 2 : 3;
        return Math.min(count, cap);
    }

    private static String pickLeastUsed(List<String> pool, Map<String, Integer> useCount, Random random) {
        int min = Integer.MAX_VALUE;
        List<String> candidates = new ArrayList<>();
        for (String id : pool) {
            int used = useCount.getOrDefault(id, 0);
            if (used < min) {
                min = used;
                candidates.clear();
                candidates.add(id);
            } else if (used == min) {
                candidates.add(id);
            }
        }
        return candidates.get(random.nextInt(candidates.size()));
    }

    private static String pickInstrument(List<String> available, List<String> exclude, boolean preferCommon, Random random) {
        List<String> candidates = new ArrayList<>();
        for (String id : available) {
            if (!exclude.contains(id)) {
                candidates.add(id);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        if (!preferCommon) {
            return candidates.get(random.nextInt(candidates.size()));
        }

        List<String> weighted = new ArrayList<>();
        for (String id : candidates) {
            int weight = isPopularInstrument(id) ? POPULAR_WEIGHT : NICHE_WEIGHT;
            for (int i = 0; i < weight; i++) {
                weighted.add(id);
            }
        }
        return weighted.get(random.nextInt(weighted.size()));
    }

    private static boolean isPopularInstrument(String instrumentId) {
        String local = instrumentId.contains(":") ? instrumentId.substring(instrumentId.indexOf(':') + 1) : instrumentId;
        for (String prefix : POPULAR_INSTRUMENT_PREFIXES) {
            if (local.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String buildSpellName(Arrangement arrangement, boolean hasMobPurge) {
        List<String> flavorNames = new ArrayList<>();
        for (EffectEntry entry : arrangement.effects) {
            flavorNames.add(EFFECT_FLAVOR_RU.getOrDefault(entry.effect, entry.effect));
        }
        String joined = joinRu(flavorNames);
        return hasMobPurge ? "Гимн изгнания: " + joined : joined;
    }

    /**
     * ADDED 2026-09-13 (user request): "/mimieffects clearscrolls" — resets
     * every track's arrangement (instruments/effects/name) and mob_purge
     * back to TrackScaffolder's original blank-stub shape, so the whole set
     * can be regenerated from scratch via genscrolls. Never touches
     * track_id/display_name/artist/midi_file_name — only the fields
     * genscrolls itself would have written.
     */
    public static int clearAll(Path tracksDir) throws IOException {
        int cleared = 0;
        if (!Files.isDirectory(tracksDir)) {
            return 0;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tracksDir, "*.json")) {
            for (Path file : stream) {
                TrackConfig config = tryParse(file);
                if (config == null || config.arrangements == null || config.arrangements.length == 0) {
                    continue;
                }
                config.spell_name = null;
                config.mob_purge = null;
                Arrangement arrangement = config.arrangements[0];
                arrangement.required_instruments = new LinkedHashMap<>();
                arrangement.effects = new EffectEntry[0];
                arrangement.name = "TODO: rename me";

                Files.write(file, GSON.toJson(config).getBytes(StandardCharsets.UTF_8));
                cleared++;
            }
        }
        return cleared;
    }

    private static String joinRu(List<String> items) {
        if (items.isEmpty()) {
            return "Тишина";
        }
        if (items.size() == 1) {
            return items.get(0);
        }
        if (items.size() == 2) {
            return items.get(0) + " и " + items.get(1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size() - 1; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(items.get(i));
        }
        sb.append(" и ").append(items.get(items.size() - 1));
        return sb.toString();
    }
}
