package com.glowingmushroom;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Keeps the macro going without babysitting:
 * <ul>
 *   <li>kicked/disconnected -> reconnects to the same server (backing off 10 s .. 2 min)</li>
 *   <li>in Limbo -> /lobby, in a lobby -> /skyblock, elsewhere in SkyBlock -> /warp glowing, then waits
 *       until the sidebar shows the cave before resuming</li>
 *   <li>after dying -> /warp glowing</li>
 * </ul>
 * It only acts while the user wants the macro running (/glowing start until /glowing stop).
 */
public class Failsafe {
    private static final long CMD_GAP_MS = 8000;
    private static final long RESUME_SETTLE_MS = 2000;
    private static final long WRONG_ZONE_CONFIRM_MS = 4000;
    private static final long NO_SIDEBAR_LIMBO_MS = 6000;
    private static final int MAX_CMD_TRIES = 6;
    private static final int MAX_RECONNECTS = 10;

    /** Shown on the HUD instead of Running/Paused while the fail-safe is doing something. */
    public static volatile String status;

    public volatile boolean enabled = true;
    /** Sidebar text (any line, case-insensitive) that means "we're in the cave". */
    public volatile String zoneText = "glowing";
    private volatile boolean wanted;

    private final MacroController controller;
    private final Random rand = new Random();

    private ServerData lastServer;
    private boolean kicked;
    private long disconnectAt, nextReconnect, joinedAt;
    private int reconnects;

    private long lastCmd, wrongSince, inZoneSince, noSidebarSince, nextResume, forceWarpAt;
    private int cmdTries;

    public Failsafe(MacroController controller) {
        this.controller = controller;
    }

    public boolean wanted() {
        return wanted;
    }

    /** /glowing start */
    public void begin(Minecraft mc) {
        wanted = true;
        cmdTries = 0;
        reconnects = 0;
        status = null;
        if (!enabled || inZone(mc) || sidebar(mc) == null) {
            if (controller.start(mc)) Chat.msg("Started (" + (controller.pathMode ? "path" : "route") + " mode).");
            else wanted = enabled; // the fail-safe will keep trying once things are in order
        } else {
            Chat.msg("Not in the cave yet - heading there first.");
        }
    }

    /** /glowing stop */
    public void end(Minecraft mc) {
        wanted = false;
        status = null;
        controller.stop(mc);
    }

    // ---- events --------------------------------------------------------------------------------

    public void onJoin(Minecraft mc) {
        ServerData sd = mc.getCurrentServer();
        if (sd != null) lastServer = sd;
        joinedAt = System.currentTimeMillis();
        kicked = false;
        noSidebarSince = 0;
        wrongSince = 0;
        inZoneSince = 0;
        cmdTries = 0;
        lastCmd = joinedAt; // give the server a moment before sending commands
    }

    public void onDisconnect(Minecraft mc) {
        if (controller.isRunning()) controller.stop(mc);
        disconnectAt = System.currentTimeMillis();
        kicked = true; // decided in tick(): only reconnect if we land on the "Disconnected" screen
        nextReconnect = 0;
    }

    public void onGameMessage(String text) {
        if (!wanted) return;
        String t = text.replaceAll("§.", "");
        if (t.contains("☠ You") || t.startsWith("You died")) forceWarpAt = System.currentTimeMillis() + 2500;
    }

    // ---- tick ----------------------------------------------------------------------------------

    public void tick(Minecraft mc) {
        if (!enabled || !wanted) return;
        long now = System.currentTimeMillis();

        if (mc.level == null || mc.player == null) {
            reconnectTick(mc, now);
            return;
        }
        if (joinedAt != 0 && now - joinedAt > 30_000) reconnects = 0;

        List<String> lines = sidebar(mc);
        if (lines == null) { // no sidebar: Limbo (or still loading)
            if (noSidebarSince == 0) noSidebarSince = now;
            if (now - noSidebarSince > NO_SIDEBAR_LIMBO_MS) {
                pause(mc, "In Limbo - going to lobby");
                command(mc, now, "lobby");
            }
            return;
        }
        noSidebarSince = 0;

        boolean skyblock = sidebarTitle(mc).toUpperCase(Locale.ROOT).contains("SKYBLOCK");
        boolean inZone = skyblock && matchesZone(lines);

        if (forceWarpAt != 0 && now >= forceWarpAt) { // died
            forceWarpAt = 0;
            pause(mc, "Died - warping back");
            lastCmd = 0;
            command(mc, now, "warp glowing");
            return;
        }

        if (inZone) {
            wrongSince = 0;
            cmdTries = 0;
            if (inZoneSince == 0) inZoneSince = now;
            if (!controller.isRunning() && now - inZoneSince > RESUME_SETTLE_MS && now >= nextResume) {
                nextResume = now + 30_000; // if start fails (e.g. no shears) don't spam
                status = null;
                if (controller.start(mc)) Chat.msg("In the cave - resuming.");
            }
            if (controller.isRunning()) status = null;
            return;
        }

        inZoneSince = 0;
        if (wrongSince == 0) wrongSince = now;
        if (now - wrongSince < WRONG_ZONE_CONFIRM_MS) return; // ignore brief blips (e.g. mid-warp)

        if (!skyblock) {
            pause(mc, "In a lobby - joining SkyBlock");
            command(mc, now, "skyblock");
        } else {
            pause(mc, "Wrong area - /warp glowing");
            command(mc, now, "warp glowing");
        }
    }

    private void pause(Minecraft mc, String why) {
        if (controller.isRunning()) {
            controller.stop(mc);
            Chat.msg(why + ".");
        }
        status = why;
    }

    /** Sends a command at most every 8-10 s; after 6 tries without success waits a minute. */
    private void command(Minecraft mc, long now, String cmd) {
        if (now - lastCmd < CMD_GAP_MS + rand.nextInt(2000)) return;
        if (cmdTries >= MAX_CMD_TRIES) {
            if (now - lastCmd < 60_000) {
                status = "Waiting before retrying /" + cmd;
                return;
            }
            cmdTries = 0;
        }
        cmdTries++;
        lastCmd = now;
        mc.player.connection.sendCommand(cmd);
        status = "/" + cmd + " (" + cmdTries + "/" + MAX_CMD_TRIES + ")";
    }

    private void reconnectTick(Minecraft mc, long now) {
        if (!kicked || lastServer == null) return;
        if (!(mc.screen instanceof DisconnectedScreen)) return; // left on purpose: don't reconnect
        if (reconnects >= MAX_RECONNECTS) {
            status = "Gave up reconnecting";
            return;
        }
        if (nextReconnect == 0) {
            long delay = Math.min(120_000L, 10_000L << Math.min(reconnects, 4)) + rand.nextInt(3000);
            nextReconnect = now + delay;
        }
        long left = nextReconnect - now;
        status = "Reconnecting in " + Math.max(0, left / 1000) + "s";
        if (left > 0) return;
        reconnects++;
        nextReconnect = 0;
        status = "Reconnecting (" + reconnects + "/" + MAX_RECONNECTS + ")";
        ConnectScreen.startConnecting(mc.screen, mc, ServerAddress.parseString(lastServer.ip), lastServer, false, null);
    }

    // ---- scoreboard --------------------------------------------------------------------------

    public boolean inZone(Minecraft mc) {
        List<String> lines = sidebar(mc);
        return lines != null && matchesZone(lines);
    }

    private boolean matchesZone(List<String> lines) {
        String z = zoneText.toLowerCase(Locale.ROOT);
        for (String l : lines) if (l.toLowerCase(Locale.ROOT).contains(z)) return true;
        return false;
    }

    /** Sidebar lines as plain text, or null if there is no sidebar. */
    public static List<String> sidebar(Minecraft mc) {
        if (mc.level == null) return null;
        Scoreboard sb = mc.level.getScoreboard();
        Objective obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (obj == null) return null;
        List<String> out = new ArrayList<>();
        for (PlayerScoreEntry e : sb.listPlayerScores(obj)) {
            if (e.isHidden()) continue;
            PlayerTeam team = sb.getPlayersTeam(e.owner());
            String line = team == null ? e.ownerName().getString()
                    : PlayerTeam.formatNameForTeam(team, e.ownerName()).getString();
            out.add(line.replaceAll("§.", "").trim());
        }
        return out;
    }

    public static String sidebarTitle(Minecraft mc) {
        if (mc.level == null) return "";
        Objective obj = mc.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
        return obj == null ? "" : obj.getDisplayName().getString().replaceAll("§.", "");
    }
}
