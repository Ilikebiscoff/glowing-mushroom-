package com.glowingmushroom;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Dark profit panel in the top-left corner. */
public final class ProfitHud {
    public static volatile boolean enabled = true;

    private static final int BG = 0xE0101216;      // near-black, slightly transparent
    private static final int HEADER = 0xF01A1D24;
    private static final int ACCENT = 0xFF3DDC84;  // green accent bar
    private static final int TITLE = 0xFFE8EAED;
    private static final int LABEL = 0xFF8A8F98;
    private static final int VALUE = 0xFFE8EAED;
    private static final int GOLD = 0xFFF2C14E;
    private static final int GLOW = 0xFF6FE3FF;

    private ProfitHud() {}

    private record Row(String label, String value, int color) {}

    public static void render(GuiGraphicsExtractor g, DeltaTracker delta, BooleanSupplier running, BooleanSupplier wanted) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.player == null || mc.options.hideGui) return;
        boolean run = running.getAsBoolean();
        String fs = wanted.getAsBoolean() ? Failsafe.status : null;
        if (!run && fs == null && ProfitTracker.macroMs == 0 && ProfitTracker.glowing == 0) return;
        Font f = mc.font;

        boolean priced = ProfitTracker.glowingPrice >= 0;
        List<Row> rows = new ArrayList<>();
        if (fs != null) rows.add(new Row("Status", fs, 0xFFFFB347));
        else rows.add(new Row("Status", run ? "Running" : "Paused", run ? ACCENT : 0xFFFF6B6B));
        rows.add(new Row("Time", time(ProfitTracker.macroMs), VALUE));
        rows.add(new Row("Glowing", ProfitTracker.glowing + "  " + coins(ProfitTracker.glowing * ProfitTracker.glowingPrice, priced), GLOW));
        long regular = ProfitTracker.red + ProfitTracker.brown;
        double regCoins = ProfitTracker.red * Math.max(0, ProfitTracker.redPrice) + ProfitTracker.brown * Math.max(0, ProfitTracker.brownPrice);
        rows.add(new Row("Regular", regular + "  " + coins(regCoins, priced), VALUE));
        rows.add(new Row("Profit", coins(ProfitTracker.coins(), priced), GOLD));
        rows.add(new Row("Per hour", coins(ProfitTracker.perHour(), priced) + "/h", GOLD));

        String title = "Glowing Mushroom Profit";
        String foot = !priced ? "prices loading..." : ProfitTracker.pricesLive ? "Bazaar instant-sell" : "Bazaar (offline, last known)";
        int labelW = 0, valueW = 0;
        for (Row r : rows) {
            labelW = Math.max(labelW, f.width(r.label()));
            valueW = Math.max(valueW, f.width(r.value()));
        }
        int pad = 6, gap = 10, lineH = 11;
        int w = Math.max(Math.max(f.width(title), f.width(foot)), labelW + gap + valueW) + pad * 2;
        int x = 4, y = 4;
        int headerH = 16;
        int h = headerH + rows.size() * lineH + 4 + lineH + pad;

        g.fill(x, y, x + w, y + h, BG);
        g.fill(x, y, x + w, y + headerH, HEADER);
        g.fill(x, y, x + 2, y + h, ACCENT);
        g.text(f, title, x + pad, y + 4, TITLE, false);

        int ty = y + headerH + 3;
        for (Row r : rows) {
            g.text(f, r.label(), x + pad, ty, LABEL, false);
            g.text(f, r.value(), x + w - pad - f.width(r.value()), ty, r.color(), false);
            ty += lineH;
        }
        g.fill(x + pad, ty + 1, x + w - pad, ty + 2, 0x40FFFFFF);
        g.text(f, foot, x + pad, ty + 4, LABEL, false);
    }

    private static String time(long ms) {
        long s = ms / 1000;
        return String.format("%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    private static String coins(double v, boolean priced) {
        if (!priced) return "? coins";
        if (v >= 1_000_000) return String.format("%.2fM", v / 1_000_000);
        if (v >= 1_000) return String.format("%.1fk", v / 1_000);
        return String.format("%.0f", v);
    }
}
