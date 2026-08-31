package com.mimieffects.orchestrator;

/**
 * Decides whether a single candidate entity should be purged, given a
 * MobPurge-style ruleset. Deliberately takes plain booleans instead of a
 * real Minecraft Entity so it's unit-testable without Minecraft on the
 * classpath — the real call site (inside the mod) will pass:
 *   isPlayer          = entity instanceof Player
 *   isHostile         = entity instanceof net.minecraft.world.entity.monster.Enemy
 *   isInWater         = entity.isInWater()
 *   isTamedOrOwned    = entity instanceof OwnableEntity && ((OwnableEntity)entity).getOwner() != null
 *
 * See MobPurge.java for why environment-based (not entity-id-based)
 * targeting was chosen.
 */
public final class MobPurgeMatcher {

    private MobPurgeMatcher() {
    }

    /**
     * @param environment one of "underwater", "surface", "any" (MobPurge.environment)
     * @param hostileOnly MobPurge.hostile_only
     */
    public static boolean shouldPurge(
            boolean isPlayer,
            boolean isHostile,
            boolean isInWater,
            boolean isTamedOrOwned,
            String environment,
            boolean hostileOnly
    ) {
        // Players are NEVER a valid target, regardless of any config value.
        // This check exists independent of hostile_only so a misconfigured
        // (or malicious) config can't turn this into a player-kill switch.
        if (isPlayer) {
            return false;
        }

        // Tamed/owned entities (pets) are never purged, regardless of
        // hostility flags a modded entity might carry.
        if (isTamedOrOwned) {
            return false;
        }

        if (hostileOnly && !isHostile) {
            return false;
        }

        switch (environment == null ? "surface" : environment) {
            case "underwater":
                return isInWater;
            case "any":
                return true;
            case "surface":
            default:
                return !isInWater;
        }
    }
}
