package com.glowingmushroom;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Walks through every loaded chunk (nearest first, a few per tick) and records each small red/brown
 * mushroom plant as a <em>candidate</em>. Particles only reach ~32 blocks, so this is how mushrooms far
 * across the island are found; a candidate is confirmed as glowing by its particle when we get close,
 * and dropped if it doesn't glow.
 */
public final class IslandScanner {
    private static final int RADIUS_CHUNKS = 16;
    private static final int CHUNKS_PER_TICK = 3;

    private final ArrayDeque<long[]> queue = new ArrayDeque<>();
    private boolean active;
    private int found, chunks;

    public boolean active() {
        return active;
    }

    public void begin(Minecraft mc) {
        queue.clear();
        found = chunks = 0;
        if (mc.player == null || mc.level == null) return;
        int pcx = mc.player.blockPosition().getX() >> 4, pcz = mc.player.blockPosition().getZ() >> 4;
        List<long[]> all = new ArrayList<>();
        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++)
            for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++)
                all.add(new long[]{pcx + dx, pcz + dz, (long) dx * dx + (long) dz * dz});
        all.sort(Comparator.comparingLong(a -> a[2]));
        queue.addAll(all);
        active = true;
    }

    /** Returns the number of candidates found when the scan just finished this tick, else -1. */
    public int tick(Minecraft mc) {
        if (!active) return -1;
        ClientLevel level = mc.level;
        if (level == null) {
            active = false;
            queue.clear();
            return -1;
        }
        for (int n = 0; n < CHUNKS_PER_TICK && !queue.isEmpty(); n++) {
            long[] c = queue.poll();
            LevelChunk chunk = level.getChunkSource().getChunkNow((int) c[0], (int) c[1]);
            if (chunk == null) continue;
            chunks++;
            scan(chunk);
        }
        if (queue.isEmpty()) {
            active = false;
            return found;
        }
        return -1;
    }

    public int chunksScanned() {
        return chunks;
    }

    private void scan(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection sec = sections[i];
            if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(IslandScanner::isPlant)) continue; // cheap palette check
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
            int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
            for (int x = 0; x < 16; x++)
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++)
                        if (isPlant(sec.getBlockState(x, y, z))) {
                            MushroomTracker.addCandidate(new BlockPos(baseX + x, baseY + y, baseZ + z));
                            found++;
                        }
        }
    }

    private static boolean isPlant(BlockState st) {
        return st.is(Blocks.RED_MUSHROOM) || st.is(Blocks.BROWN_MUSHROOM);
    }
}
