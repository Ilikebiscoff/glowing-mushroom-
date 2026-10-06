package com.glowingmushroom;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.EnumParticleTypes;

import java.util.Map;

public class GmCommand extends CommandBase {
    private final GlowingMushroomMod mod;

    public GmCommand(GlowingMushroomMod mod) {
        this.mod = mod;
    }

    @Override public String getCommandName() { return "gm"; }
    @Override public String getCommandUsage(ICommandSender s) {
        return "/gm <add|undo|clear|list|start|stop|particle [TYPE]|scan>";
    }
    @Override public int getRequiredPermissionLevel() { return 0; }
    @Override public boolean canCommandSenderUseCommand(ICommandSender s) { return true; }

    @Override
    public void processCommand(ICommandSender sender, String[] a) {
        Minecraft mc = Minecraft.getMinecraft();
        String sub = a.length == 0 ? "" : a[0].toLowerCase();
        switch (sub) {
            case "add":
                mod.route.add(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
                Chat.msg("Added waypoint #" + mod.route.size());
                break;
            case "undo":
                Chat.msg(mod.route.removeLast() ? "Removed last waypoint." : "Route is empty.");
                break;
            case "clear":
                mod.route.clear();
                Chat.msg("Route cleared.");
                break;
            case "list":
                Chat.msg(mod.route.size() + " waypoints, particle=" + mod.tracker.markerParticle
                        + ", broken=" + mod.controller.broken);
                break;
            case "start":
                if (mod.route.size() == 0) { Chat.msg("Record a route first with /gm add."); break; }
                mod.controller.start();
                Chat.msg("Started.");
                break;
            case "stop":
                mod.controller.stop();
                Chat.msg("Stopped.");
                break;
            case "particle":
                if (a.length < 2) { Chat.msg("Current: " + mod.tracker.markerParticle); break; }
                EnumParticleTypes t = null;
                for (EnumParticleTypes v : EnumParticleTypes.values())
                    if (v.name().equalsIgnoreCase(a[1])) t = v;
                if (t == null) { Chat.msg("Unknown particle type."); break; }
                mod.tracker.markerParticle = t;
                Chat.msg("Marker particle set to " + t);
                break;
            case "scan":
                // Toggle: stand next to a glowing mushroom, run once, wait ~5s, run again to see counts.
                if (!mod.tracker.debug) {
                    mod.tracker.debug = true;
                    mod.tracker.drainSeen();
                    Chat.msg("Counting particles. Stand near glowing mushrooms, then run /gm scan again.");
                } else {
                    mod.tracker.debug = false;
                    for (Map.Entry<EnumParticleTypes, Integer> en : mod.tracker.drainSeen().entrySet())
                        Chat.msg(en.getKey() + ": " + en.getValue());
                    Chat.msg("Pick the one that only shows on mushrooms: /gm particle <TYPE>");
                }
                break;
            default:
                Chat.msg(getCommandUsage(sender));
        }
    }
}
