package com.mimieffects.integration;

import com.mimieffects.config.GlobalConfig;

import net.minecraft.world.entity.LivingEntity;

/**
 * Added 2026-09-04 per user request: bosses must be exempt from BOTH
 * mob_purge (so it can't be abused to trivially kill/damage boss fights)
 * and from ordinary track effects (so a "melody of life" playing near a
 * boss fight can't accidentally heal it via a hostile/friendly/neutral
 * targeting flag). Deliberately server-wide (GlobalConfig), not
 * per-track — a per-track exclude list is one an admin can easily forget
 * to add to any given file; this way it's enforced everywhere at once,
 * with vanilla's own Ender Dragon and Wither excluded by default.
 *
 * Three independent, combinable checks:
 * - Exact entity type ID / tag / mod namespace (GlobalConfig.PROTECTED_*),
 *   via the shared EntityMatchUtil.
 * - EXTENDED 2026-09-05: max-health threshold
 *   (GlobalConfig.BOSS_MAX_HEALTH_THRESHOLD). The user's own reasoning:
 *   a mob whose max health is above some number is functionally a boss
 *   regardless of what mod added it or whether it implements any
 *   particular marker interface — and this transparently also catches
 *   Apotheosis-empowered "apotic invader" vanilla mobs (which get
 *   massively boosted stats from affixes) without needing any
 *   Apotheosis-specific integration at all. Set to 0 to disable.
 */
public final class BossProtection {

    private BossProtection() {
    }

    public static boolean isProtected(LivingEntity entity) {
        double threshold = GlobalConfig.BOSS_MAX_HEALTH_THRESHOLD.get();
        if (threshold > 0 && entity.getMaxHealth() >= threshold) {
            return true;
        }

        return EntityMatchUtil.matchesAny(
                entity,
                GlobalConfig.PROTECTED_ENTITY_TYPES.get(),
                GlobalConfig.PROTECTED_ENTITY_TAGS.get(),
                GlobalConfig.PROTECTED_ENTITY_NAMESPACES.get()
        );
    }
}
