package com.mimieffects.integration;

import com.mimieffects.config.GlobalConfig;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Enemy;

/**
 * Added 2026-09-05. Replaces raw "entity instanceof Enemy" checks in
 * MimiNoteBridge — that marker interface alone misses a lot of real
 * modded threats. Concrete reported cases: vampires/bats/maggots from an
 * unspecified mod, and Born in Chaos's anglerfish while in water — none
 * of these apparently implement Enemy, so mob_purge and hostile-targeted
 * effects silently skipped them even though they're clearly dangerous
 * creatures by design.
 *
 * Layered checks, most reliable first:
 * 1. Enemy marker interface (unchanged, still the most precise signal
 *    when a mod bothers to implement it — mostly vanilla + well-behaved
 *    mods).
 * 2. EntityType spawn category == MONSTER. Many modded mobs (especially
 *    MCreator-built ones) set their spawn category correctly even when
 *    they skip the Enemy interface, since the category also drives
 *    vanilla spawn-cap mechanics and is usually the "obviously correct"
 *    choice a mod author picks for a hostile creature regardless of
 *    which Java interfaces they bothered with.
 * 3. Admin-configurable extras (GlobalConfig [Hostility]): exact type,
 *    tag, or whole mod namespace — for the remaining cases neither of
 *    the above catches. This is NOT a universal "detect every hostile
 *    mob automatically" solution (no such thing reliably exists across
 *    arbitrary mods — the same limitation applies to minimap mods that
 *    show hostile/passive icons, which use this same combination of
 *    signals plus manually-maintained compatibility data). It's the
 *    practical, mod-agnostic-by-default-but-admin-extensible middle
 *    ground.
 */
public final class EntityHostilityUtil {

    private EntityHostilityUtil() {
    }

    public static boolean isHostile(LivingEntity entity) {
        if (entity instanceof Enemy) {
            return true;
        }
        if (entity.getType().getCategory() == MobCategory.MONSTER) {
            return true;
        }
        return EntityMatchUtil.matchesAny(
                entity,
                GlobalConfig.EXTRA_HOSTILE_TYPES.get(),
                GlobalConfig.EXTRA_HOSTILE_TAGS.get(),
                GlobalConfig.EXTRA_HOSTILE_NAMESPACES.get()
        );
    }
}
