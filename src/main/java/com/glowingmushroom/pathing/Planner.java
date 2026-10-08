package com.glowingmushroom.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs on its own thread: groups known mushrooms into clusters, finds for each cluster the standing
 * spot that has the most of them in nuker reach, picks the best cluster by
 * {@code mushrooms / (walk cost + 6)} and paths there. It also plans the following cluster from that
 * spot, so the next leg is ready the moment the current one is cleared.
 */
public class Planner {
    /** Max eye-to-mushroom distance counted as "in reach" from a standing spot (nuker breaks at 5). */
    public static final double REACH = 4.6;
    private static final double CLUSTER_LINK = 5.0;
    private static final float COST_LIMIT = 160f;
    private static final int MAX_EXPANSIONS = 150_000;
    private static final double EYE = 1.62;
    /** Bonus for the cluster we're already heading to, so it doesn't flip-flop between similar ones. */
    private static final double KEEP_BONUS = 1.3;

    public record Leg(List<Vec3> path, List<BlockPos> targets, BlockPos stand, double score, double cost) {}

    public record Plan(List<Leg> legs) {}

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "glowing-planner");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicReference<Plan> result = new AtomicReference<>();

    /** Starts a plan in the background. Returns false (and does nothing) if one is already running. */
    public boolean request(WalkCache.Grid grid, BlockPos start, List<BlockPos> mushrooms, Set<BlockPos> keep,
                           Set<BlockPos> avoid) {
        if (grid == null || !busy.compareAndSet(false, true)) return false;
        exec.execute(() -> {
            try {
                result.set(compute(grid, start, mushrooms, keep, avoid));
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                busy.set(false);
            }
        });
        return true;
    }

    /** The newest finished plan, or null. */
    public Plan poll() {
        return result.getAndSet(null);
    }

    // ------------------------------------------------------------------------------------------

    /** Extra cost for cells where we recently got stuck, so the next path goes another way. */
    private static final float AVOID_COST = 25f;

    static Plan compute(WalkCache.Grid g, BlockPos start, List<BlockPos> mushrooms, Set<BlockPos> keep,
                        Set<BlockPos> avoid) {
        List<Leg> legs = new ArrayList<>();
        int s = findStart(g, start);
        if (s < 0 || mushrooms.isEmpty()) return new Plan(legs);
        Search first = dijkstra(g, s, avoid);
        Leg l1 = bestLeg(g, first, mushrooms, keep);
        if (l1 == null) return new Plan(legs);
        legs.add(l1);

        List<BlockPos> rest = new ArrayList<>(mushrooms);
        rest.removeAll(l1.targets());
        if (!rest.isEmpty()) {
            int s2 = g.index(l1.stand().getX(), l1.stand().getY(), l1.stand().getZ());
            Leg l2 = bestLeg(g, dijkstra(g, s2, avoid), rest, Set.of());
            if (l2 != null) legs.add(l2);
        }
        return new Plan(legs);
    }

    private static int findStart(WalkCache.Grid g, BlockPos p) {
        int[][] tries = {{0, 0, 0}, {0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
                {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1}, {0, -2, 0}};
        for (int[] t : tries) {
            int x = p.getX() + t[0], y = p.getY() + t[1], z = p.getZ() + t[2];
            if (g.standable(x, y, z)) return g.index(x, y, z);
        }
        return -1;
    }

    // ---- search ------------------------------------------------------------------------------

    record Search(float[] dist, int[] parent, int start) {}

    /** Dijkstra over standable cells: walk, diagonal (no corner cutting), step up 1, drop up to 3. */
    static Search dijkstra(WalkCache.Grid g, int start, Set<BlockPos> avoid) {
        float[] dist = new float[WalkCache.N];
        int[] parent = new int[WalkCache.N];
        Arrays.fill(dist, Float.POSITIVE_INFINITY);
        dist[start] = 0;
        parent[start] = -1;
        PriorityQueue<Long> pq = new PriorityQueue<>();
        pq.add(key(0f, start));
        int expansions = 0;
        while (!pq.isEmpty() && expansions++ < MAX_EXPANSIONS) {
            long k = pq.poll();
            int idx = (int) k;
            float cost = Float.intBitsToFloat((int) (k >>> 32));
            if (cost > dist[idx]) continue;
            int x = g.x(idx), y = g.y(idx), z = g.z(idx);
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    boolean diag = dx != 0 && dz != 0;
                    int nx = x + dx, nz = z + dz;
                    float base = diag ? 1.414f : 1f;
                    if (diag && !(g.clear(x + dx, y, z) && g.clear(x, y, z + dz))) continue;
                    if (g.standable(nx, y, nz)) {
                        relax(g, avoid, dist, parent, pq, idx, nx, y, nz, cost + base);
                    } else if (!diag) {
                        if (g.standable(nx, y + 1, nz) && g.passable(x, y + 2, z)) {
                            relax(g, avoid, dist, parent, pq, idx, nx, y + 1, nz, cost + base + 0.8f);
                        } else if (g.clear(nx, y, nz)) {
                            for (int d = 1; d <= 3; d++) {
                                if (g.standable(nx, y - d, nz)) {
                                    relax(g, avoid, dist, parent, pq, idx, nx, y - d, nz, cost + base + 0.3f * d);
                                    break;
                                }
                                if (!g.passable(nx, y - d, nz)) break;
                            }
                        }
                    }
                }
        }
        return new Search(dist, parent, start);
    }

    private static void relax(WalkCache.Grid g, Set<BlockPos> avoid, float[] dist, int[] parent,
                              PriorityQueue<Long> pq, int from, int x, int y, int z, float c) {
        if (!avoid.isEmpty() && avoid.contains(new BlockPos(x, y, z))) c += AVOID_COST;
        if (c > COST_LIMIT) return;
        int i = g.index(x, y, z);
        if (i < 0 || c >= dist[i]) return;
        dist[i] = c;
        parent[i] = from;
        pq.add(key(c, i));
    }

    private static long key(float cost, int idx) {
        return ((long) Float.floatToIntBits(cost) << 32) | (idx & 0xFFFFFFFFL);
    }

    // ---- clusters ----------------------------------------------------------------------------

    private static List<List<BlockPos>> clusters(List<BlockPos> ms) {
        List<List<BlockPos>> out = new ArrayList<>();
        boolean[] used = new boolean[ms.size()];
        double link2 = CLUSTER_LINK * CLUSTER_LINK;
        for (int i = 0; i < ms.size(); i++) {
            if (used[i]) continue;
            List<BlockPos> c = new ArrayList<>();
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(i);
            used[i] = true;
            while (!q.isEmpty()) {
                int a = q.poll();
                c.add(ms.get(a));
                for (int b = 0; b < ms.size(); b++)
                    if (!used[b] && ms.get(a).distSqr(ms.get(b)) <= link2) {
                        used[b] = true;
                        q.add(b);
                    }
            }
            out.add(c);
        }
        return out;
    }

    /** Best standing spot over all clusters, scored by mushrooms covered per walking cost. */
    private static Leg bestLeg(WalkCache.Grid g, Search s, List<BlockPos> mushrooms, Collection<BlockPos> keep) {
        double r2 = REACH * REACH;
        int bestIdx = -1;
        double bestScore = 0, bestCost = 0;
        List<BlockPos> bestTargets = null;
        for (List<BlockPos> c : clusters(mushrooms)) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos m : c) {
                minX = Math.min(minX, m.getX()); maxX = Math.max(maxX, m.getX());
                minY = Math.min(minY, m.getY()); maxY = Math.max(maxY, m.getY());
                minZ = Math.min(minZ, m.getZ()); maxZ = Math.max(maxZ, m.getZ());
            }
            boolean kept = false;
            for (BlockPos m : c) if (keep.contains(m)) { kept = true; break; }

            int cIdx = -1, cCover = 0;
            float cCost = Float.POSITIVE_INFINITY;
            for (int x = minX - 5; x <= maxX + 5; x++)
                for (int y = minY - 4; y <= maxY + 3; y++)
                    for (int z = minZ - 5; z <= maxZ + 5; z++) {
                        int i = g.index(x, y, z);
                        if (i < 0 || s.dist[i] == Float.POSITIVE_INFINITY) continue;
                        double ex = x + 0.5, ey = y + EYE, ez = z + 0.5;
                        int cover = 0;
                        for (BlockPos m : c) {
                            double ddx = m.getX() + 0.5 - ex, ddy = m.getY() + 0.5 - ey, ddz = m.getZ() + 0.5 - ez;
                            if (ddx * ddx + ddy * ddy + ddz * ddz <= r2) cover++;
                        }
                        if (cover > cCover || (cover == cCover && cover > 0 && s.dist[i] < cCost)) {
                            cCover = cover;
                            cCost = s.dist[i];
                            cIdx = i;
                        }
                    }
            if (cIdx < 0 || cCover == 0) continue;
            double score = cCover / (cCost + 6.0) * (kept ? KEEP_BONUS : 1.0);
            if (score > bestScore) {
                bestScore = score;
                bestCost = cCost;
                bestIdx = cIdx;
                double ex = g.x(cIdx) + 0.5, ey = g.y(cIdx) + EYE, ez = g.z(cIdx) + 0.5;
                List<BlockPos> t = new ArrayList<>();
                for (BlockPos m : c) {
                    double ddx = m.getX() + 0.5 - ex, ddy = m.getY() + 0.5 - ey, ddz = m.getZ() + 0.5 - ez;
                    if (ddx * ddx + ddy * ddy + ddz * ddz <= r2) t.add(m);
                }
                bestTargets = t;
            }
        }
        if (bestIdx < 0) return null;

        List<Integer> cells = new ArrayList<>();
        for (int i = bestIdx; i != -1; i = s.parent[i]) {
            cells.add(i);
            if (i == s.start) break;
        }
        java.util.Collections.reverse(cells);
        BlockPos stand = new BlockPos(g.x(bestIdx), g.y(bestIdx), g.z(bestIdx));
        return new Leg(pull(g, cells), bestTargets, stand, bestScore, bestCost);
    }

    // ---- smoothing ---------------------------------------------------------------------------

    /** String-pulling: keep only the corners, joining cells that can be walked in a straight line. */
    private static List<Vec3> pull(WalkCache.Grid g, List<Integer> cells) {
        List<Vec3> out = new ArrayList<>();
        int n = cells.size();
        int i = 0;
        out.add(center(g, cells.get(0)));
        while (i < n - 1) {
            int j = n - 1;
            while (j > i + 1 && !straight(g, cells.get(i), cells.get(j))) j--;
            out.add(center(g, cells.get(j)));
            i = j;
        }
        return out;
    }

    /**
     * True if a player can walk in a straight line from {@code a} to {@code b} on one level: every
     * cell under the line (and 0.3 to either side) is standable at a's feet height.
     */
    public static boolean walkable(WalkCache.Grid g, Vec3 a, Vec3 b) {
        if (g == null) return false;
        int y = (int) Math.floor(a.y + 0.01);
        if (Math.abs(Math.floor(b.y + 0.01) - y) > 0) return false;
        double dx = b.x - a.x, dz = b.z - a.z, len = Math.hypot(dx, dz);
        if (len < 1e-6) return true;
        double px = -dz / len, pz = dx / len;
        int steps = (int) Math.ceil(len / 0.25);
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            for (double off : new double[]{-0.3, 0, 0.3}) {
                int cx = (int) Math.floor(a.x + dx * t + px * off);
                int cz = (int) Math.floor(a.z + dz * t + pz * off);
                if (!g.standable(cx, y, cz)) return false;
            }
        }
        return true;
    }

    private static Vec3 center(WalkCache.Grid g, int i) {
        return new Vec3(g.x(i) + 0.5, g.y(i), g.z(i) + 0.5);
    }

    private static boolean straight(WalkCache.Grid g, int a, int b) {
        int y = g.y(a);
        if (y != g.y(b)) return false;
        double ax = g.x(a) + 0.5, az = g.z(a) + 0.5, bx = g.x(b) + 0.5, bz = g.z(b) + 0.5;
        double dx = bx - ax, dz = bz - az, len = Math.hypot(dx, dz);
        if (len < 1e-6) return true;
        double px = -dz / len, pz = dx / len; // perpendicular, for the player's width
        int steps = (int) Math.ceil(len / 0.25);
        Set<Long> seen = new HashSet<>();
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            for (double off : new double[]{-0.3, 0, 0.3}) {
                int cx = (int) Math.floor(ax + dx * t + px * off);
                int cz = (int) Math.floor(az + dz * t + pz * off);
                if (!seen.add(((long) cx << 32) ^ (cz & 0xFFFFFFFFL))) continue;
                if (!g.standable(cx, y, cz)) return false;
            }
        }
        return true;
    }
}
