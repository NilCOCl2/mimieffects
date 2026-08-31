package com.mimieffects.orchestrator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implements ТЗ §4.3: group active sessions by songId, then within each
 * group find connected components (clusters) where an edge exists between
 * two sessions whose distance is <= radius.
 *
 * Pure logic, no Minecraft/NeoForge dependency — takes any list of
 * {@link ClusterableSession} implementations, real or test doubles.
 */
public final class ClusterAnalyzer<T extends ClusterableSession> {

    private final double radius;

    public ClusterAnalyzer(double radius) {
        this.radius = radius;
    }

    /**
     * @return list of clusters; each cluster is a non-empty list of sessions.
     *         Sessions with null songId are grouped together as the
     *         "improvisation" bucket before being split into clusters.
     */
    public List<List<T>> cluster(List<T> sessions) {
        Map<String, List<T>> bySong = new HashMap<>();
        for (T session : sessions) {
            String key = session.songId() == null ? "\0__improv__" : session.songId();
            bySong.computeIfAbsent(key, k -> new ArrayList<>()).add(session);
        }

        List<List<T>> result = new ArrayList<>();
        for (List<T> group : bySong.values()) {
            result.addAll(connectedComponents(group));
        }
        return result;
    }

    private List<List<T>> connectedComponents(List<T> group) {
        List<List<T>> components = new ArrayList<>();
        Set<T> visited = new HashSet<>();

        for (T start : group) {
            if (visited.contains(start)) {
                continue;
            }
            List<T> component = new ArrayList<>();
            Deque<T> queue = new ArrayDeque<>();
            queue.add(start);
            visited.add(start);

            while (!queue.isEmpty()) {
                T current = queue.poll();
                component.add(current);
                for (T candidate : group) {
                    if (!visited.contains(candidate) && current.distanceTo(candidate) <= radius) {
                        visited.add(candidate);
                        queue.add(candidate);
                    }
                }
            }
            components.add(component);
        }
        return components;
    }
}
