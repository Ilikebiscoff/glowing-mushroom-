package com.glowingmushroom;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import static com.mojang.brigadier.builder.RequiredArgumentBuilder.argument;

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
            var root = LiteralArgumentBuilder.<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>literal("gm");
            root.then(LiteralArgumentBuilder.<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>literal("add").executes(c -> {
                var p = mc.player;
                route.add(p.getX(), p.getY(), p.getZ());
                Chat.msg("Added waypoint #" + route.size());
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>literal("undo").executes(c -> {
                Chat.msg(route.removeLast() ? "Removed last waypoint." : "Route is empty.");
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>literal("clear").executes(c -> {
                route.clear();
                Chat.msg("Route cleared.");
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>literal("list").executes(c -> {
                Chat.msg(route.size() + " waypoints, particle=" + MushroomTracker.markerParticle
                        + ", broken=" + controller.broken);
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>literal("start").executes(c -> {
                if (route.size() == 0) Chat.msg("Record a route first with /gm add.");
                else {
                    controller.start(mc);
                    Chat.msg("Started.");
                }
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>literal("stop").executes(c -> {
                controller.stop(mc);
                Chat.msg("Stopped.");
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>literal("scan").executes(c -> {
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
            root.then(LiteralArgumentBuilder.<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>literal("particle")
                    .executes(c -> {
                        Chat.msg("Current: " + MushroomTracker.markerParticle);
                        return 1;
                    })
                    .then(argument("id", StringArgumentType.greedyString()).executes(c -> {
                        String id = StringArgumentType.getString(c, "id").trim();
                        MushroomTracker.markerParticle = id.contains(":") ? id : "minecraft:" + id;
                        Chat.msg("Marker particle set to " + MushroomTracker.markerParticle);
                        return 1;
                    })));
            dispatcher.register(root);
        });
    }
}
