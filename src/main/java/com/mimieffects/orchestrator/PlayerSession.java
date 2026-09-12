package com.mimieffects.orchestrator;

import java.util.UUID;

/**
 * Minecraft-independent snapshot of "who's playing what, where, on what
 * instrument, as of when" — feeds ClusterAnalyzer (ТЗ §4.3) for ensemble
 * grouping and cross-song dissonance detection. The songId component
 * satisfies ClusterableSession's accessor automatically since a record's
 * component name becomes its accessor method name.
 */
public record PlayerSession(UUID playerId, String songId, String instrumentId, double x, double y, double z, long lastActiveTick)
        implements ClusterableSession {

    @Override
    public double distanceTo(ClusterableSession other) {
        if (!(other instanceof PlayerSession o)) {
            return Double.MAX_VALUE;
        }
        double dx = x - o.x;
        double dy = y - o.y;
        double dz = z - o.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
