package com.mimieffects.orchestrator;

/**
 * Minimal contract ClusterAnalyzer needs from a "player session".
 * Kept independent of Minecraft/NeoForge types (no ResourceLocation, no Entity)
 * so the clustering algorithm can be unit-tested in plain Java, and so the
 * real PlayerSession class only needs to implement this interface.
 */
public interface ClusterableSession {

    /**
     * Identifier of the track being played. Two sessions with different,
     * non-null songIds are never clustered together even if in range
     * (they land in separate track-groups per ТЗ §4.3.2).
     * Null means "improvisation" and is treated as its own group.
     */
    String songId();

    double distanceTo(ClusterableSession other);
}
