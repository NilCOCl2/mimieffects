import java.util.*;

public class SelfTest {

    // ---- inlined copy of ClusterableSession + ClusterAnalyzer for standalone compile ----
    interface ClusterableSession {
        String songId();
        double distanceTo(ClusterableSession other);
    }

    static class Session implements ClusterableSession {
        final String id;
        final String song;
        final double x;
        Session(String id, String song, double x) { this.id = id; this.song = song; this.x = x; }
        public String songId() { return song; }
        public double distanceTo(ClusterableSession other) { return Math.abs(x - ((Session) other).x); }
        public String toString() { return id; }
    }

    static class ClusterAnalyzer<T extends ClusterableSession> {
        final double radius;
        ClusterAnalyzer(double radius) { this.radius = radius; }

        List<List<T>> cluster(List<T> sessions) {
            Map<String, List<T>> bySong = new HashMap<>();
            for (T s : sessions) {
                String key = s.songId() == null ? "\0improv" : s.songId();
                bySong.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
            }
            List<List<T>> result = new ArrayList<>();
            for (List<T> group : bySong.values()) result.addAll(connectedComponents(group));
            return result;
        }

        List<List<T>> connectedComponents(List<T> group) {
            List<List<T>> components = new ArrayList<>();
            Set<T> visited = new HashSet<>();
            for (T start : group) {
                if (visited.contains(start)) continue;
                List<T> comp = new ArrayList<>();
                Deque<T> queue = new ArrayDeque<>();
                queue.add(start); visited.add(start);
                while (!queue.isEmpty()) {
                    T cur = queue.poll();
                    comp.add(cur);
                    for (T cand : group) {
                        if (!visited.contains(cand) && cur.distanceTo(cand) <= radius) {
                            visited.add(cand); queue.add(cand);
                        }
                    }
                }
                components.add(comp);
            }
            return components;
        }
    }

    static int computeFinalLevel(int baseLevel, int maxLevel, int playerCount, double coverage,
                                  double perPlayerBonus, double fullEnsembleBonus,
                                  boolean hasDissonance, int reduceByLevels) {
        if (playerCount <= 0) return 0;
        double playerMult = 1.0 + (playerCount - 1) * perPlayerBonus;
        double ensembleMult = 1.0 + coverage * fullEnsembleBonus;
        int finalLevel = (int) Math.floor(baseLevel * playerMult * ensembleMult);
        finalLevel = Math.min(finalLevel, maxLevel);
        if (hasDissonance) finalLevel = Math.max(0, finalLevel - reduceByLevels);
        return Math.max(finalLevel, 0);
    }

    static int checks = 0, failures = 0;

    static void check(String label, Object actual, Object expected) {
        checks++;
        boolean ok = Objects.equals(actual, expected);
        if (!ok) {
            failures++;
            System.out.println("FAIL  " + label + " -> got " + actual + ", expected " + expected);
        } else {
            System.out.println("ok    " + label + " -> " + actual);
        }
    }

    public static void main(String[] args) {
        System.out.println("=== EffectCalculator ===");
        // 1 player, no coverage bonus, no dissonance: base level unchanged
        check("solo player, base coverage",
                computeFinalLevel(1, 3, 1, 0.0, 0.25, 0.5, false, 2), 1);

        // 3 players, full coverage (1.0), matches ТЗ formula by hand:
        // playerMult = 1 + 2*0.25 = 1.5 ; ensembleMult = 1 + 1.0*0.5 = 1.5
        // finalLevel = floor(1 * 1.5 * 1.5) = floor(2.25) = 2
        check("3 players full coverage",
                computeFinalLevel(1, 3, 3, 1.0, 0.25, 0.5, false, 2), 2);

        // same as above but capped by maxLevel = 1
        check("capped by maxLevel",
                computeFinalLevel(1, 1, 3, 1.0, 0.25, 0.5, false, 2), 1);

        // dissonance reduces by 2, would go negative -> clamps to 0
        check("dissonance clamps to zero",
                computeFinalLevel(1, 3, 1, 0.0, 0.25, 0.5, true, 2), 0);

        // dissonance with headroom
        check("dissonance with headroom",
                computeFinalLevel(3, 5, 3, 1.0, 0.25, 0.5, true, 2),
                Math.max(0, Math.min((int) Math.floor(3 * 1.5 * 1.5), 5) - 2));

        System.out.println("\n=== ClusterAnalyzer ===");
        // Two players close together playing the same song -> 1 cluster of 2
        List<Session> sessions = List.of(
                new Session("A", "songX", 0),
                new Session("B", "songX", 10),
                new Session("C", "songX", 100), // far away -> separate cluster
                new Session("D", "songY", 5),   // different song -> never merges with songX group
                new Session("E", null, 1000)    // improvisation, alone
        );
        ClusterAnalyzer<Session> analyzer = new ClusterAnalyzer<>(15.0);
        List<List<Session>> clusters = analyzer.cluster(sessions);
        clusters.sort((a, b) -> a.toString().compareTo(b.toString()));

        check("total cluster count", clusters.size(), 4);

        boolean foundABTogether = clusters.stream().anyMatch(c -> c.size() == 2
                && c.stream().anyMatch(s -> s.id.equals("A"))
                && c.stream().anyMatch(s -> s.id.equals("B")));
        check("A and B share a cluster (within radius, same song)", foundABTogether, true);

        boolean cAlone = clusters.stream().anyMatch(c -> c.size() == 1 && c.get(0).id.equals("C"));
        check("C is alone (too far from A/B)", cAlone, true);

        boolean dAlone = clusters.stream().anyMatch(c -> c.size() == 1 && c.get(0).id.equals("D"));
        check("D is alone (different song, never merges)", dAlone, true);

        // Chain test: A(0) -- 14 -- F(14) -- 14 -- G(28), radius 15
        // A-F distance 14 (<=15, edge), F-G distance 14 (<=15, edge), A-G distance 28 (>15, no direct edge)
        // Connected components must still merge all three transitively.
        List<Session> chain = List.of(
                new Session("A", "songZ", 0),
                new Session("F", "songZ", 14),
                new Session("G", "songZ", 28)
        );
        List<List<Session>> chainClusters = new ClusterAnalyzer<Session>(15.0).cluster(chain);
        check("transitive chain merges into one cluster", chainClusters.size(), 1);
        check("transitive chain cluster size", chainClusters.get(0).size(), 3);

        System.out.println("\n=== Improvisation min_instrument_count guard ===");
        // Simulates Arrangement.coverageScore for empty required_instruments + min_instrument_count
        check("1 instrument does NOT satisfy min 2", satisfiesImprov(1, 2), false);
        check("2 instruments satisfies min 2", satisfiesImprov(2, 2), true);

        testMobPurge();

        System.out.println("\n" + checks + " checks, " + failures + " failures");
        if (failures > 0) {
            System.exit(1);
        }
    }

    static boolean satisfiesImprov(int totalAvailable, int minRequired) {
        return totalAvailable >= minRequired;
    }

    // ---- inlined copy of MobPurgeMatcher for standalone compile ----
    static boolean shouldPurge(boolean isPlayer, boolean isHostile, boolean isInWater,
                                boolean isTamedOrOwned, String environment, boolean hostileOnly) {
        if (isPlayer) return false;
        if (isTamedOrOwned) return false;
        if (hostileOnly && !isHostile) return false;
        switch (environment == null ? "surface" : environment) {
            case "underwater": return isInWater;
            case "any": return true;
            case "surface":
            default: return !isInWater;
        }
    }

    static void testMobPurge() {
        System.out.println("\n=== MobPurgeMatcher ===");

        check("underwater track kills hostile mob in water",
                shouldPurge(false, true, true, false, "underwater", true), true);

        check("underwater track spares hostile mob NOT in water",
                shouldPurge(false, true, false, false, "underwater", true), false);

        check("surface track kills hostile mob on land",
                shouldPurge(false, true, false, false, "surface", true), true);

        check("surface track spares hostile mob in water",
                shouldPurge(false, true, true, false, "surface", true), false);

        check("player is never purged even with permissive config",
                shouldPurge(true, true, true, false, "any", false), false);

        check("tamed/owned entity is never purged",
                shouldPurge(false, true, false, true, "any", true), false);

        check("hostile_only=false purges non-hostile mob too",
                shouldPurge(false, false, false, false, "surface", false), true);

        check("hostile_only=true spares passive mob",
                shouldPurge(false, false, false, false, "surface", true), false);
    }
}
