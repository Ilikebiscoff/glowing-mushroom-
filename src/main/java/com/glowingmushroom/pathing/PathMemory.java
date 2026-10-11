package com.glowingmushroom.pathing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the pathfinder has learned, saved between sessions (the cave layout is the same every lobby):
 * <ul>
 *   <li><b>trouble map</b>: cells where it got stuck, spun, hit a wall it can't jump, fell in water or had to
 *       warp out get an extra path cost, which fades when it later passes there without trouble and with time</li>
 *   <li><b>dead targets</b>: mushroom spots it repeatedly failed to reach/break are skipped until they work again</li>
 *   <li><b>jump lead</b>: how early before a step it starts jumping (x speed); grows when jumps fail,
 *       shrinks slowly when they work</li>
 * </ul>
 * Read by the planner thread, written by the client thread, so everything is concurrent.
 */
public final class PathMemory {
    private PathMemory() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final float MAX_PENALTY = 40f;
    private static final int DEAD_AFTER = 3;
    private static final long SAVE_EVERY_MS = 60_000;
    private static final long DECAY_EVERY_MS = 10 * 60_000;

    public static volatile boolean enabled = true;
    /** Takeoff distance for jumps = speed * jumpLead. */
    public static volatile float jumpLead = 3.0f;

    private static final Map<Long, Float> TROUBLE = new ConcurrentHashMap<>();
    private static final Map<Long, Integer> DEAD = new ConcurrentHashMap<>();
    private static Path file;
    private static boolean dirty;
    private static long lastSave, lastDecay;
    public static volatile int events, jumpOk, jumpFail;

    public static void init(Path configFile) {
        file = configFile;
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file)) {
            JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            if (o.has("jumpLead")) jumpLead = clamp(o.get("jumpLead").getAsFloat(), 1.5f, 5f);
            if (o.has("trouble")) for (var e : o.getAsJsonObject("trouble").entrySet())
                TROUBLE.put(Long.parseLong(e.getKey()), e.getValue().getAsFloat());
            if (o.has("dead")) for (var e : o.getAsJsonObject("dead").entrySet())
                DEAD.put(Long.parseLong(e.getKey()), e.getValue().getAsInt());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ---- planner side ------------------------------------------------------------------------

    /** Extra path cost for walking through this cell. */
    public static float penalty(int x, int y, int z) {
        if (!enabled || TROUBLE.isEmpty()) return 0f;
        Float f = TROUBLE.get(BlockPos.asLong(x, y, z));
        return f == null ? 0f : f;
    }

    /** Mushroom spots that kept failing: leave them out of the plan. */
    public static boolean isDead(BlockPos target) {
        if (!enabled) return false;
        Integer n = DEAD.get(target.asLong());
        return n != null && n >= DEAD_AFTER;
    }

    // ---- client side learning ----------------------------------------------------------------

    /** Something went wrong in/at this cell: make the pathfinder like it less (spreads a little to neighbours). */
    public static void trouble(BlockPos cell, float amount) {
        if (!enabled) return;
        events++;
        add(cell.asLong(), amount);
        for (var d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
            add(cell.offset(d[0], 0, d[1]).asLong(), amount * 0.4f);
        dirty = true;
    }

    private static void add(long key, float amount) {
        TROUBLE.merge(key, Math.min(MAX_PENALTY, amount), (a, b) -> Math.min(MAX_PENALTY, a + b));
    }

    /** Passed through this cell without any trouble: forget some of its bad reputation. */
    public static void fine(BlockPos cell) {
        if (!enabled || TROUBLE.isEmpty()) return;
        TROUBLE.computeIfPresent(cell.asLong(), (k, v) -> {
            dirty = true;
            float nv = v * 0.8f;
            return nv < 0.5f ? null : nv;
        });
    }

    public static void targetFailed(BlockPos t) {
        if (!enabled) return;
        DEAD.merge(t.asLong(), 1, Integer::sum);
        dirty = true;
    }

    public static void targetOk(BlockPos t) {
        if (DEAD.remove(t.asLong()) != null) dirty = true;
    }

    public static void jumpResult(boolean ok) {
        if (!enabled) return;
        if (ok) {
            jumpOk++;
            jumpLead = clamp(jumpLead - 0.05f, 1.5f, 5f);
        } else {
            jumpFail++;
            jumpLead = clamp(jumpLead + 0.3f, 1.5f, 5f);
        }
        dirty = true;
    }

    /** Call every tick: slow decay and periodic saving. */
    public static void tick() {
        long now = System.currentTimeMillis();
        if (lastDecay == 0) lastDecay = lastSave = now;
        if (now - lastDecay > DECAY_EVERY_MS) {
            lastDecay = now;
            TROUBLE.replaceAll((k, v) -> v * 0.9f);
            TROUBLE.values().removeIf(v -> v < 0.5f);
            DEAD.replaceAll((k, v) -> Math.max(0, v - 1));
            DEAD.values().removeIf(v -> v <= 0);
            jumpLead += (3.0f - jumpLead) * 0.1f; // drift back toward the default
            dirty = true;
        }
        if (dirty && now - lastSave > SAVE_EVERY_MS) save();
    }

    public static synchronized void save() {
        lastSave = System.currentTimeMillis();
        dirty = false;
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("jumpLead", jumpLead);
            JsonObject t = new JsonObject();
            TROUBLE.forEach((k, v) -> t.addProperty(Long.toString(k), v));
            JsonObject d = new JsonObject();
            DEAD.forEach((k, v) -> d.addProperty(Long.toString(k), v));
            o.add("trouble", t);
            o.add("dead", d);
            try (Writer w = Files.newBufferedWriter(file)) {
                GSON.toJson(o, w);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void reset() {
        TROUBLE.clear();
        DEAD.clear();
        jumpLead = 3.0f;
        events = jumpOk = jumpFail = 0;
        dirty = true;
        save();
    }

    public static String stats() {
        int dead = 0;
        for (int n : DEAD.values()) if (n >= DEAD_AFTER) dead++;
        return String.format("learning %s | %d trouble cells, %d dead targets, jump lead x%.2f (jumps ok %d / failed %d), %d incidents this session",
                enabled ? "ON" : "OFF", TROUBLE.size(), dead, jumpLead, jumpOk, jumpFail, events);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
