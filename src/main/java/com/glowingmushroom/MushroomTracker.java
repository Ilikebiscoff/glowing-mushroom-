package com.glowingmushroom;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Collects particle packets (the same signal SkyHanni's highlighters use) and remembers the
 * mushroom blocks sitting next to the configured marker particle.
 */
public class MushroomTracker {
    /** Potion-style particles (how 1.8 SPELL_* particles arrive after protocol translation). */
    public static final Set<String> POTION_PARTICLES = Set.of("minecraft:entity_effect",
            "minecraft:ambient_entity_effect", "minecraft:effect", "minecraft:instant_effect");
    /** Default marker: the potion swirl particle Hypixel puts on glowing mushrooms. */
    public static final Set<String> DEFAULT_PARTICLES = Set.of("minecraft:entity_effect");
    /** Registry ids treated as the mushroom marker. Change with /glowing particle. */
    public static volatile Set<String> markers = DEFAULT_PARTICLES;
    /** While true every particle id is counted so the right marker can be found (/glowing scan). */
    public static volatile boolean scanning = false;

    private static final ConcurrentLinkedQueue<ClientboundLevelParticlesPacket> QUEUE = new ConcurrentLinkedQueue<>();
    private static final Map<String, Integer> SEEN = new HashMap<>();
    /** Forget a mushroom if no particle confirmed it for this long while we were close enough to see it. */
    private static final long EXPIRE_MS = 60_000;
    private static final double SEE_RANGE = 24;
    private static final java.util.concurrent.atomic.AtomicInteger VERSION = new java.util.concurrent.atomic.AtomicInteger();

    /** Every spot where a glow particle ever confirmed a glowing mushroom (they respawn in place). */
    private static final Set<BlockPos> EVER_GLOWING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static java.nio.file.Path historyFile;
    private static volatile boolean historyDirty;
    private static long lastHistorySave;

    /** Remembered spots that are not confirmed right now: candidates until a particle shows up. */
    public static final Set<BlockPos> CANDIDATES = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Candidates we got close to that did not glow (ignored until the next refresh). */
    private static final Set<BlockPos> NOT_GLOWING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Map<BlockPos, Long> CAND_NEAR = new HashMap<>();
    /** Within this range a glowing mushroom's particle would certainly have reached us (server range ~32). */
    private static final double CONFIRM_RANGE = 18;
    private static final long CONFIRM_MS = 3000;

    /** Known mushrooms -> last time (ms) a particle confirmed them. */
    public static final Map<BlockPos, Long> MUSHROOMS = new ConcurrentHashMap<>();

    /** Called from the mixin (may be off the main thread). */
    public static void offer(ClientboundLevelParticlesPacket packet) {
        QUEUE.add(packet);
    }

    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        ClientboundLevelParticlesPacket p;
        while ((p = QUEUE.poll()) != null) {
            if (level == null) continue;
            var id = BuiltInRegistries.PARTICLE_TYPE.getKey(p.getParticle().getType());
            String name = String.valueOf(id);
            if (scanning) SEEN.merge(name, 1, Integer::sum);
            if (!markers.contains(name)) continue;
            BlockPos hit = findMushroom(level, p.getX(), p.getY(), p.getZ());
            if (hit != null) {
                boolean fresh = MUSHROOMS.put(hit, System.currentTimeMillis()) == null;
                boolean wasCandidate = CANDIDATES.remove(hit); // a glow particle confirms it
                if (EVER_GLOWING.add(hit.immutable())) historyDirty = true;
                CAND_NEAR.remove(hit);
                NOT_GLOWING.remove(hit);
                if (fresh || wasCandidate) VERSION.incrementAndGet();
            }
        }
        long now = System.currentTimeMillis();
        if (historyDirty && now - lastHistorySave > 15_000) {
            historyDirty = false;
            lastHistorySave = now;
            saveHistory();
        }
        for (Iterator<Map.Entry<BlockPos, Long>> it = MUSHROOMS.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            BlockPos bp = en.getKey();
            if (level == null) {
                it.remove();
                continue;
            }
            if (!level.isLoaded(bp)) continue; // out of loaded range: keep remembering it
            if (CANDIDATES.contains(bp)) {
                // unconfirmed: if we stand close for a few seconds and it never glows, it's a plain mushroom
                boolean close = mc.player != null && mc.player.position().distanceToSqr(bp.getCenter()) < CONFIRM_RANGE * CONFIRM_RANGE;
                if (!isMushroom(level.getBlockState(bp).getBlock())) {
                    CANDIDATES.remove(bp);
                    CAND_NEAR.remove(bp);
                    it.remove();
                } else if (!close) {
                    CAND_NEAR.remove(bp);
                } else {
                    Long since = CAND_NEAR.putIfAbsent(bp, now);
                    if (since != null && now - since > CONFIRM_MS) {
                        CANDIDATES.remove(bp);
                        CAND_NEAR.remove(bp);
                        NOT_GLOWING.add(bp);
                        it.remove();
                        VERSION.incrementAndGet();
                    }
                }
                continue;
            }
            boolean near = mc.player != null && mc.player.position().distanceToSqr(bp.getCenter()) < SEE_RANGE * SEE_RANGE;
            if (!isMushroom(level.getBlockState(bp).getBlock()) || (near && now - en.getValue() > EXPIRE_MS)) {
                it.remove();
            }
        }
    }

    /** True if a glow particle confirmed this mushroom (not just found by the block scan). */
    public static boolean confirmed(BlockPos bp) {
        return MUSHROOMS.containsKey(bp) && !CANDIDATES.contains(bp);
    }

    /** Loads the remembered glowing spots from disk. */
    public static void initHistory(java.nio.file.Path file) {
        historyFile = file;
        try {
            if (java.nio.file.Files.exists(file)) {
                try (var r = java.nio.file.Files.newBufferedReader(file)) {
                    int[][] arr = new com.google.gson.Gson().fromJson(r, int[][].class);
                    if (arr != null) for (int[] a : arr) if (a.length == 3) EVER_GLOWING.add(new BlockPos(a[0], a[1], a[2]));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void saveHistory() {
        if (historyFile == null) return;
        try {
            java.nio.file.Files.createDirectories(historyFile.getParent());
            int[][] arr = EVER_GLOWING.stream().map(b -> new int[]{b.getX(), b.getY(), b.getZ()}).toArray(int[][]::new);
            try (var w = java.nio.file.Files.newBufferedWriter(historyFile)) {
                new com.google.gson.Gson().toJson(arr, w);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static int historySize() {
        return EVER_GLOWING.size();
    }

    /** Re-adds every remembered glowing spot that currently holds a mushroom as an unconfirmed candidate. */
    public static int restoreHistory(ClientLevel level) {
        if (level == null) return 0;
        int n = 0;
        for (BlockPos bp : EVER_GLOWING) {
            if (!level.isLoaded(bp) || !isMushroom(level.getBlockState(bp).getBlock())) continue;
            if (!MUSHROOMS.containsKey(bp) && !NOT_GLOWING.contains(bp)) {
                addCandidate(bp);
                n++;
            }
        }
        return n;
    }

    /** Adds a remembered glowing spot as an unconfirmed candidate (confirmed again by its particle). */
    public static void addCandidate(BlockPos bp) {
        if (MUSHROOMS.containsKey(bp) || NOT_GLOWING.contains(bp)) return;
        BlockPos im = bp.immutable();
        CANDIDATES.add(im);
        MUSHROOMS.put(im, System.currentTimeMillis());
        VERSION.incrementAndGet();
    }

    /** Called on every refresh: give previously ignored mushrooms another chance. */
    public static void clearIgnored() {
        NOT_GLOWING.clear();
    }

    /** Changes every time a new mushroom is discovered (planner re-plans on change). */
    public static int version() {
        return VERSION.get();
    }

    public static java.util.List<BlockPos> snapshot() {
        return new java.util.ArrayList<>(MUSHROOMS.keySet());
    }

    public static Map<String, Integer> drainSeen() {
        Map<String, Integer> copy = new TreeMap<>(SEEN);
        SEEN.clear();
        return copy;
    }

    /**
     * Checks the particle's block and its neighbours for a mushroom and returns the closest. Small
     * mushroom plants win over giant-mushroom blocks so a nearby cap/stem doesn't steal the match.
     */
    private static BlockPos findMushroom(ClientLevel level, double x, double y, double z) {
        BlockPos base = BlockPos.containing(x, y, z);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos bp = base.offset(dx, dy, dz);
                    Block b = level.getBlockState(bp).getBlock();
                    if (!isMushroom(b)) continue;
                    double d = bp.distToCenterSqr(x, y, z);
                    if (b != Blocks.RED_MUSHROOM && b != Blocks.BROWN_MUSHROOM) d += 100;
                    if (d < bestDist) {
                        bestDist = d;
                        best = bp;
                    }
                }
        return best;
    }

    public static boolean isMushroom(Block b) {
        return b == Blocks.RED_MUSHROOM || b == Blocks.BROWN_MUSHROOM
                || b == Blocks.RED_MUSHROOM_BLOCK || b == Blocks.BROWN_MUSHROOM_BLOCK;
    }
}
