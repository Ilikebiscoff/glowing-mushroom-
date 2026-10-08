package com.glowingmushroom;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import com.glowingmushroom.pathing.Planner;
import com.glowingmushroom.pathing.WalkCache;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
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
    private final WalkCache cache = new WalkCache();

    @Override
    public void onInitializeClient() {
        route = new Route(FabricLoader.getInstance().getConfigDir().resolve("glowingmushroom_route.json"));
        controller = new MacroController(route, cache);

        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            MushroomTracker.tick(mc);
            cache.tick(mc);
            controller.tick(mc);
            ProfitTracker.tick(mc, controller.isRunning());
        });

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("glowingmushroomauto", "profit"),
                (graphics, delta) -> ProfitHud.render(graphics, delta, controller::isRunning));

        LevelRenderEvents.BEFORE_GIZMOS.register(context -> {
            controller.frame(Minecraft.getInstance());
            drawRoute();
            drawMushrooms();
            drawPlan();
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            Minecraft mc = Minecraft.getInstance();
            var root = LiteralArgumentBuilder.<FabricClientCommandSource>literal("glowing");
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
                if (controller.start(mc)) {
                    Chat.msg("Started (" + (controller.pathMode ? "path" : "route") + " mode).");
                    if (controller.pathMode && route.size() == 0)
                        Chat.msg("Tip: record a patrol route with /glowing add so it can search when no mushrooms are known.");
                }
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
                    Chat.msg("Counting particles. Stand near glowing mushrooms, then run /glowing scan again.");
                } else {
                    MushroomTracker.scanning = false;
                    MushroomTracker.drainSeen().forEach((k, v) -> Chat.msg(k + ": " + v));
                    Chat.msg("Pick the one that only shows on mushrooms: /glowing particle <id>");
                }
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("particle")
                    .executes(c -> {
                        Chat.msg("Current: " + MushroomTracker.markers
                                + " (/glowing particle reset = entity_effect, /glowing particle potion = all potion types)");
                        return 1;
                    })
                    .then(arg("ids", StringArgumentType.greedyString()).executes(c -> {
                        String in = StringArgumentType.getString(c, "ids").trim();
                        if (in.equalsIgnoreCase("reset")) {
                            MushroomTracker.markers = MushroomTracker.DEFAULT_PARTICLES;
                        } else if (in.equalsIgnoreCase("potion")) {
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
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("mode")
                    .executes(c -> {
                        Chat.msg("Mode: " + (controller.pathMode ? "path" : "route") + " (/glowing mode path|route)");
                        return 1;
                    })
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("path").executes(c -> {
                        controller.pathMode = true;
                        Chat.msg("Path mode: pathfinds to the densest mushroom groups, route = patrol when none known.");
                        return 1;
                    }))
                    .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("route").executes(c -> {
                        controller.pathMode = false;
                        Chat.msg("Route mode: only walks the recorded route.");
                        return 1;
                    })));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("highlight").executes(c -> {
                highlight = !highlight;
                Chat.msg("Mushroom highlight " + (highlight ? "ON" : "OFF"));
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("hud").executes(c -> {
                ProfitHud.enabled = !ProfitHud.enabled;
                Chat.msg("Profit HUD " + (ProfitHud.enabled ? "ON" : "OFF"));
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("reset").executes(c -> {
                ProfitTracker.reset();
                Chat.msg("Profit tracker reset.");
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("help").executes(c -> {
                help();
                return 1;
            }));
            root.executes(c -> {
                help();
                return 1;
            });
            dispatcher.register(root);
        });
    }

    private static void help() {
        Chat.msg("Commands:");
        Chat.msg(" /glowing help - this list");
        Chat.msg(" /glowing add - add a waypoint where you stand");
        Chat.msg(" /glowing undo - remove the last waypoint");
        Chat.msg(" /glowing clear - delete the whole route");
        Chat.msg(" /glowing list - route size, particle, mushrooms broken");
        Chat.msg(" /glowing start | stop - run or stop the macro");
        Chat.msg(" /glowing mode path|route - pathfind to mushroom groups (default) or only walk the route");
        Chat.msg(" /glowing nuker - toggle nuker (break all in reach) / aimed mining");
        Chat.msg(" /glowing highlight - toggle mushroom highlight boxes");
        Chat.msg(" /glowing hud - toggle the profit panel (top left)");
        Chat.msg(" /glowing reset - reset profit, counts and time");
        Chat.msg(" /glowing particle [ids|reset|potion] - show/set the marker particle");
        Chat.msg(" /glowing scan - run twice near mushrooms to list particle types");
        Chat.msg(" /glowing speed - show the speed read from the tab list");
    }

    private static <T> RequiredArgumentBuilder<FabricClientCommandSource, T> arg(String name, ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    private boolean highlight = true;

    private static final int GREEN = 0xFF30FF60;
    private static final int GREEN_FILL = 0x4030FF60;
    private static final int YELLOW = 0xFFFFE030;
    private static final int YELLOW_FILL = 0x50FFE030;

    /** Green box on every tracked glowing mushroom, yellow on the one being mined. */
    private void drawMushrooms() {
        if (!highlight) return;
        var target = controller.currentTarget();
        for (var bp : MushroomTracker.MUSHROOMS.keySet()) {
            boolean cur = bp.equals(target);
            Gizmos.cuboid(bp, cur ? GizmoStyle.strokeAndFill(YELLOW, 2.5f, YELLOW_FILL)
                    : GizmoStyle.strokeAndFill(GREEN, 2f, GREEN_FILL)).setAlwaysOnTop();
        }
    }

    private static final int CYAN = 0xFF30E0FF;
    private static final int CYAN_FILL = 0x3030E0FF;

    /** Cyan line along the planned path, cyan box on the standing spot, number of mushrooms above it. */
    private void drawPlan() {
        if (!highlight) return;
        Planner.Leg leg = controller.currentLeg();
        if (leg == null) return;
        var pts = leg.path();
        for (int i = 0; i + 1 < pts.size(); i++)
            Gizmos.line(pts.get(i).add(0, 0.1, 0), pts.get(i + 1).add(0, 0.1, 0), CYAN, 3f).setAlwaysOnTop();
        var st = leg.stand();
        Gizmos.cuboid(new AABB(st.getX() + 0.2, st.getY(), st.getZ() + 0.2, st.getX() + 0.8, st.getY() + 0.1, st.getZ() + 0.8),
                GizmoStyle.strokeAndFill(CYAN, 2f, CYAN_FILL)).setAlwaysOnTop();
        Gizmos.billboardText(leg.targets().size() + "x", new Vec3(st.getX() + 0.5, st.getY() + 2.3, st.getZ() + 0.5),
                TextGizmo.Style.forColorAndCentered(CYAN).withScale(1.3f)).setAlwaysOnTop();
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
