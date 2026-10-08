package com.glowingmushroom.pathing;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Compact copy of the blocks around the player (passable / solid / fluid), filled on the client
 * thread a few thousand blocks per tick, nearest first, and refreshed continuously. The planner
 * thread only ever reads this copy, never the live world.
 */
public class WalkCache {
    public static final byte UNKNOWN = 0, PASS = 1, SOLID = 2, FLUID = 3;
    public static final int SX = 96, SY = 40, SZ = 96;
    public static final int N = SX * SY * SZ;

    private static final int PER_TICK = 12_000;
    private static final long BUDGET_NS = 3_000_000; // never spend more than 3 ms per tick

    /** Local cell indices sorted by distance from the box centre, so the nearest area fills first. */
    private static final int[] ORDER = buildOrder();

    public static final class Grid {
        public final int ox, oy, oz;
        final byte[] data = new byte[N];

        Grid(int ox, int oy, int oz) {
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
        }

        public int index(int x, int y, int z) {
            int lx = x - ox, ly = y - oy, lz = z - oz;
            if (lx < 0 || ly < 0 || lz < 0 || lx >= SX || ly >= SY || lz >= SZ) return -1;
            return (lx * SY + ly) * SZ + lz;
        }

        public int x(int idx) { return ox + idx / (SY * SZ); }
        public int y(int idx) { return oy + (idx / SZ) % SY; }
        public int z(int idx) { return oz + idx % SZ; }

        public byte get(int x, int y, int z) {
            int i = index(x, y, z);
            return i < 0 ? UNKNOWN : data[i];
        }

        public boolean passable(int x, int y, int z) {
            return get(x, y, z) == PASS;
        }

        /** Feet and head free. */
        public boolean clear(int x, int y, int z) {
            return passable(x, y, z) && passable(x, y + 1, z);
        }

        /** A player can stand with their feet in this block. */
        public boolean standable(int x, int y, int z) {
            return get(x, y - 1, z) == SOLID && clear(x, y, z);
        }
    }

    private volatile Grid grid;
    private ClientLevel lastLevel;
    private int cx, cy, cz, cursor;

    public Grid grid() {
        return grid;
    }

    public void tick(Minecraft mc) {
        var p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null) {
            grid = null;
            lastLevel = null;
            return;
        }
        BlockPos bp = p.blockPosition();
        Grid g = grid;
        if (g == null || level != lastLevel || Math.abs(bp.getX() - cx) > 16
                || Math.abs(bp.getZ() - cz) > 16 || Math.abs(bp.getY() - cy) > 8) {
            g = recenter(bp, level == lastLevel ? g : null);
            lastLevel = level;
        }
        long deadline = System.nanoTime() + BUDGET_NS;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int k = 0; k < PER_TICK; k++) {
            if ((k & 511) == 0 && System.nanoTime() > deadline) break;
            int li = ORDER[cursor];
            cursor = cursor + 1 == ORDER.length ? 0 : cursor + 1;
            m.set(g.x(li), g.y(li), g.z(li));
            g.data[li] = classify(level, m);
        }
    }

    private Grid recenter(BlockPos c, Grid old) {
        cx = c.getX();
        cy = c.getY();
        cz = c.getZ();
        Grid g = new Grid(cx - SX / 2, cy - SY / 2, cz - SZ / 2);
        if (old != null) { // keep what we already know
            for (int i = 0; i < N; i++) {
                byte v = old.data[i];
                if (v == UNKNOWN) continue;
                int ni = g.index(old.x(i), old.y(i), old.z(i));
                if (ni >= 0) g.data[ni] = v;
            }
        }
        cursor = 0;
        grid = g;
        return g;
    }

    private static byte classify(ClientLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (!st.getFluidState().isEmpty()) return FLUID;
        return st.getCollisionShape(level, pos).isEmpty() ? PASS : SOLID;
    }

    private static int[] buildOrder() {
        // counting sort of every cell by (weighted) squared distance from the centre
        int hx = SX / 2, hy = SY / 2, hz = SZ / 2;
        int maxKey = hx * hx + 4 * hy * hy + hz * hz + 1;
        int[] count = new int[maxKey + 1];
        int[] keys = new int[N];
        for (int lx = 0; lx < SX; lx++)
            for (int ly = 0; ly < SY; ly++)
                for (int lz = 0; lz < SZ; lz++) {
                    int dx = lx - hx, dy = ly - hy, dz = lz - hz;
                    int key = dx * dx + 4 * dy * dy + dz * dz;
                    keys[(lx * SY + ly) * SZ + lz] = key;
                    count[key]++;
                }
        int[] start = new int[maxKey + 1];
        for (int k = 1; k <= maxKey; k++) start[k] = start[k - 1] + count[k - 1];
        int[] order = new int[N];
        for (int i = 0; i < N; i++) order[start[keys[i]]++] = i;
        return order;
    }
}
