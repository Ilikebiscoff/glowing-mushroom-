package com.glowingmushroom;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

public class GlowingMushroomMod implements ClientModInitializer {
    private Route route;
    private MacroController controller;

    @Override
    public void onInitializeClient() {
        route = new Route(FabricLoader.getInstance().getConfigDir().resolve("glowingmushroom_route.json"));
        controller = new MacroController(route);

        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            MushroomTracker.tick(mc);
            controller.tick(mc);
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            Minecraft mc = Minecraft.getInstance();
            var root = LiteralArgumentBuilder.<FabricClientCommandSource>literal("gm");
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add").executes(c -> {
                var p = mc.player;
                route.add(p.getX(), p.getY(), p.getZ());
                Chat.msg("Added waypoint #" + route.size());
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("undo").executes(c -> {
                Chat.msg(route.removeLast() ? "Removed last waypoint." : "Route is empty.");
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear").executes(c -> {
                route.clear();
                Chat.msg("Route cleared.");
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list").executes(c -> {
                Chat.msg(route.size() + " waypoints, particle=" + MushroomTracker.markers
                        + ", broken=" + controller.broken);
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("start").executes(c -> {
                if (route.size() == 0) Chat.msg("Record a route first with /gm add.");
                else if (controller.start(mc)) Chat.msg("Started.");
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("stop").executes(c -> {
                controller.stop(mc);
                Chat.msg("Stopped.");
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("scan").executes(c -> {
                if (!MushroomTracker.scanning) {
                    MushroomTracker.scanning = true;
                    MushroomTracker.drainSeen();
                    Chat.msg("Counting particles. Stand near glowing mushrooms, then run /gm scan again.");
                } else {
                    MushroomTracker.scanning = false;
                    MushroomTracker.drainSeen().forEach((k, v) -> Chat.msg(k + ": " + v));
                    Chat.msg("Pick the one that only shows on mushrooms: /gm particle <id>");
                }
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("particle")
                    .executes(c -> {
                        Chat.msg("Current: " + MushroomTracker.markers + " (/gm particle potion to reset)");
                        return 1;
                    })
                    .then(arg("ids", StringArgumentType.greedyString()).executes(c -> {
                        String in = StringArgumentType.getString(c, "ids").trim();
                        if (in.equalsIgnoreCase("potion")) {
                            MushroomTracker.markers = MushroomTracker.POTION_PARTICLES;
                        } else {
                            var set = new java.util.HashSet<String>();
                            for (String id : in.split("[\\s,]+")) set.add(id.contains(":") ? id : "minecraft:" + id);
                            MushroomTracker.markers = set;
                        }
                        Chat.msg("Marker particles: " + MushroomTracker.markers);
                        return 1;
                    })));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("speed").executes(c -> {
                Chat.msg("Tab list speed: " + MacroController.readTabSpeed(mc));
                return 1;
            }));
            dispatcher.register(root);
        });
    }

    private static <T> RequiredArgumentBuilder<FabricClientCommandSource, T> arg(String name, ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }
}
