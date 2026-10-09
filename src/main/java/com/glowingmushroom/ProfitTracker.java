package com.glowingmushroom;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session profit: counts broken mushrooms (a broken glowing mushroom = +2 Glowing Mushrooms, a broken
 * non-glowing red/brown mushroom = +1 of that), macro time, and values them at live Bazaar
 * instant-sell prices (refreshed every 5 minutes).
 */
public final class ProfitTracker {
    private ProfitTracker() {}

    private static final String BAZAAR = "https://api.hypixel.net/v2/skyblock/bazaar";
    private static final long PRICE_REFRESH_MS = 5 * 60_000;
    /** Wait this long for the server to confirm a break (block stays gone) before counting it. */
    private static final long CONFIRM_MS = 400;
    private static final long GIVE_UP_MS = 3000;

    public static long glowing, red, brown;
    public static long macroMs;
    public static volatile double glowingPrice = -1, redPrice = -1, brownPrice = -1;
    public static volatile boolean pricesLive;

    private record Pending(long time, boolean glowing, Block block) {}

    private static final Map<BlockPos, Pending> PENDING = new ConcurrentHashMap<>();
    private static long lastTick, lastPriceFetch;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** Called whenever we try to break / the client breaks a block (mixin + nuker). */
    public static void onBreakAttempt(BlockPos pos) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        Block b = level.getBlockState(pos).getBlock();
        if (!MushroomTracker.isMushroom(b)) return;
        boolean glow = MushroomTracker.MUSHROOMS.containsKey(pos);
        if (!glow && b != Blocks.RED_MUSHROOM && b != Blocks.BROWN_MUSHROOM) return; // giant-mushroom blocks
        PENDING.putIfAbsent(pos.immutable(), new Pending(System.currentTimeMillis(), glow, b));
    }

    public static void tick(Minecraft mc, boolean running) {
        long now = System.currentTimeMillis();
        if (lastTick != 0 && running) macroMs += Math.min(1000, now - lastTick);
        lastTick = now;

        var level = mc.level;
        for (Iterator<Map.Entry<BlockPos, Pending>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Pending pd = e.getValue();
            long age = now - pd.time();
            if (age < CONFIRM_MS) continue;
            if (level == null || age > GIVE_UP_MS) {
                it.remove();
                continue;
            }
            if (!MushroomTracker.isMushroom(level.getBlockState(e.getKey()).getBlock())) {
                if (pd.glowing()) glowing += 2;
                else if (pd.block() == Blocks.RED_MUSHROOM) red++;
                else brown++;
                it.remove();
            }
        }

        if (now - lastPriceFetch > PRICE_REFRESH_MS) {
            lastPriceFetch = now;
            fetchPrices();
        }
    }

    public static void reset() {
        glowing = red = brown = 0;
        macroMs = 0;
        PENDING.clear();
    }

    public static double coins() {
        return glowing * Math.max(0, glowingPrice) + red * Math.max(0, redPrice) + brown * Math.max(0, brownPrice);
    }

    public static double perHour() {
        return macroMs < 10_000 ? 0 : coins() / (macroMs / 3_600_000.0);
    }

    private static void fetchPrices() {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BAZAAR)).timeout(Duration.ofSeconds(15)).GET().build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept(resp -> {
            try {
                JsonObject products = JsonParser.parseString(resp.body()).getAsJsonObject().getAsJsonObject("products");
                glowingPrice = instantSell(products, "GLOWING_MUSHROOM", glowingPrice);
                redPrice = instantSell(products, "RED_MUSHROOM", redPrice);
                brownPrice = instantSell(products, "BROWN_MUSHROOM", brownPrice);
                pricesLive = true;
            } catch (Exception ex) {
                pricesLive = false;
            }
        }).exceptionally(t -> {
            pricesLive = false;
            return null;
        });
    }

    /** Price you get selling instantly = best buy order (sell_summary[0]), else quick_status.sellPrice. */
    private static double instantSell(JsonObject products, String id, double fallback) {
        if (products == null || !products.has(id)) return fallback;
        JsonObject p = products.getAsJsonObject(id);
        var summary = p.getAsJsonArray("sell_summary");
        if (summary != null && !summary.isEmpty())
            return summary.get(0).getAsJsonObject().get("pricePerUnit").getAsDouble();
        if (p.has("quick_status")) return p.getAsJsonObject("quick_status").get("sellPrice").getAsDouble();
        return fallback;
    }
}
