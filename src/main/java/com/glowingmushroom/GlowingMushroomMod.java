package com.glowingmushroom;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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

        LevelRenderEvents.BEFORE_GIZMOS.register(context -> drawRoute());

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
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("nuker").executes(c -> {
                controller.nuker = !controller.nuker;
                Chat.msg("Nuker " + (controller.nuker ? "ON (breaks everything in reach, no aiming)" : "OFF (aimed mining)"));
                return 1;
            }));
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

    private static final int RED = 0xFFFF2020;
    private static final int RED_FILL = 0x30FF2020;

    /** Red box on every waypoint, a red line to the next one (looping) and its number floating above. */
    private void drawRoute() {
        int n = route.size();
        for (int i = 0; i < n; i++) {
            Vec3 p = route.get(i);
            Gizmos.cuboid(new AABB(p.x - 0.3, p.y, p.z - 0.3, p.x + 0.3, p.y + 0.6, p.z + 0.3),
                    GizmoStyle.strokeAndFill(RED, 2f, RED_FILL)).setAlwaysOnTop();
            if (n > 1) {
                Vec3 q = route.get((i + 1) % n);
                Gizmos.line(p.add(0, 0.3, 0), q.add(0, 0.3, 0), RED, 3f).setAlwaysOnTop();
            }
            Gizmos.billboardText(String.valueOf(i + 1), new Vec3(p.x, p.y + 1.1, p.z),
                    TextGizmo.Style.forColorAndCentered(0xFFFFFFFF).withScale(1.6f)).setAlwaysOnTop();
        }
    }
}
