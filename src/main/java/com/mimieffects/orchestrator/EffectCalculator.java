package com.mimieffects.orchestrator;

/**
 * Pure math implementation of the effect formula from ТЗ §4.5.
 * Deliberately free of any Minecraft/NeoForge types so it can be
 * unit-tested in plain Java without a dev environment.
 */
public final class EffectCalculator {

    private EffectCalculator() {
    }

    /**
     * @param baseLevel           base_level of the effect entry
     * @param maxLevel            max_level of the effect entry
     * @param playerCount         unique players in the cluster
     * @param instrumentCoverage  matched / required for the chosen arrangement (>= 1.0 to trigger at all)
     * @param perPlayerBonus      Scaling.per_player_bonus
     * @param fullEnsembleBonus   Scaling.full_ensemble_bonus
     * @param hasDissonance       whether the cluster contains sessions playing a different track
     * @param reduceByLevels      Dissonance.reduce_by_levels (only used when hasDissonance)
     * @return final effect level, or 0 meaning "no effect should be applied"
     */
    public static int computeFinalLevel(
            int baseLevel,
            int maxLevel,
            int playerCount,
            double instrumentCoverage,
            double perPlayerBonus,
            double fullEnsembleBonus,
            boolean hasDissonance,
            int reduceByLevels
    ) {
        if (playerCount <= 0) {
            return 0;
        }

        double playerMult = 1.0 + (playerCount - 1) * perPlayerBonus;
        double ensembleMult = 1.0 + instrumentCoverage * fullEnsembleBonus;

        int finalLevel = (int) Math.floor(baseLevel * playerMult * ensembleMult);
        finalLevel = Math.min(finalLevel, maxLevel);

        if (hasDissonance) {
            finalLevel = Math.max(0, finalLevel - reduceByLevels);
        }

        return Math.max(finalLevel, 0);
    }
}
