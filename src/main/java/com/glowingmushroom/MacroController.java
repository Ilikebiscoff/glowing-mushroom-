package com.glowingmushroom;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Walks the route in a loop, breaks tracked mushrooms with the Mooby shears and, every ~25 s,
 * reads the tab list and uses the Rogue Sword's speed ability when speed is below 400.
 */
public class MacroController {
    private static final double REACH = 4.4;
    private static final double ARRIVE_DIST = 0.7;
    private static final long MINE_TIMEOUT_MS = 3500;
    private static final long BLACKLIST_MS = 15000;
    private static final int SPEED_TARGET = 400;
    private static final long SPEED_CHECK_MS = 25_000;
    private static final Pattern SPEED = Pattern.compile("Speed:\\s*\\D*?(\\d+)");


    private enum Phase { NONE, TO_SWORD, USED, TO_TOOL }

    private final Route route;
    private final HumanAim aim = new HumanAim();
    private final Random rand = new Random();
    private final Map<BlockPos, Long> blacklist = new HashMap<>();

    private boolean running;
    private int waypoint;
    private BlockPos target;
    private Vec3 targetPoint;
    private long targetSince;
    private int stuckTicks;
    private Vec3 lastPos;

    private Phase phase = Phase.NONE;
    private int wait;
    private long nextSpeedCheck;
    private boolean warnedNoSpeed;

    /** Nuker: break every tracked mushroom in reach at once, without aiming or line-of-sight checks. */
    public volatile boolean nuker = true;
    private static final double NUKE_REACH = 5.0;
    private static final long NUKE_RETRY_MS = 250;
    private final Map<BlockPos, Long> nukeAttempts = new HashMap<>();
    private final Map<BlockPos, Long> nukeFirst = new HashMap<>();

    public int broken;

    public MacroController(Route route) {
        this.route = route;
    }

    public boolean isRunning() {
        return running;
    }

    /** Returns true if the macro actually started. */
    public boolean start(Minecraft mc) {
        if (route.size() == 0 || mc.player == null) return false;
        if (findSlot(mc.player, Items.SHEARS) < 0) {
            Chat.msg("No shears in your hotbar. Not started.");
            return false;
        }
        double best = Double.MAX_VALUE;
        for (int i = 0; i < route.size(); i++) {
            double d = route.get(i).distanceToSqr(mc.player.position());
            if (d < best) {
                best = d;
                waypoint = i;
            }
        }
        target = null;
        stuckTicks = 0;
        phase = Phase.NONE;
        aim.reset();
        nextSpeedCheck = System.currentTimeMillis() + 2000; // check shortly after start
        running = true;
        return true;
    }

    public void stop(Minecraft mc) {
        running = false;
        releaseKeys(mc);
    }

    public void tick(Minecraft mc) {
        if (!running) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.screen != null) {
            releaseKeys(mc);
            return;
        }
        long now = System.currentTimeMillis();
        blacklist.values().removeIf(t -> now - t > BLACKLIST_MS);

        if (handleSpeedBoost(mc, p, now)) {
            mc.options.keyAttack.setDown(false);
            walk(mc, p);
            return;
        }
        if (!ensureTool(p)) {
            return;
        }

        if (nuker) {
            nuke(mc, p, now);
            mc.options.keyAttack.setDown(false);
            walk(mc, p);
            return;
        }

        if (target != null) {
            if (!MushroomTracker.isMushroom(mc.level.getBlockState(target).getBlock())) {
                broken++;
                target = null;
            } else if (now - targetSince > MINE_TIMEOUT_MS) {
                blacklist.put(target, now);
                target = null;
            }
        }
        if (target == null) {
            target = pickTarget(mc, p);
            targetSince = now;
            if (target != null) {
                // aim somewhere inside the block, not the exact middle
                targetPoint = target.getCenter().add((rand.nextDouble() - 0.5) * 0.4,
                        (rand.nextDouble() - 0.5) * 0.4, (rand.nextDouble() - 0.5) * 0.4);
                aim.reset();
            }
        }

        if (target != null) {
            mine(mc, p);
        } else {
            mc.options.keyAttack.setDown(false);
            walk(mc, p);
        }
    }

    /** Sends a break for every tracked mushroom within reach; the route keeps walking meanwhile. */
    private void nuke(Minecraft mc, LocalPlayer p, long now) {
        Vec3 eyes = p.getEyePosition();
        boolean swung = false;
        for (BlockPos bp : MushroomTracker.MUSHROOMS.keySet()) {
            if (blacklist.containsKey(bp)) continue;
            if (eyes.distanceToSqr(bp.getCenter()) > NUKE_REACH * NUKE_REACH) continue;
            Long first = nukeFirst.get(bp);
            if (first == null) nukeFirst.put(bp, now);
            else if (now - first > MINE_TIMEOUT_MS) {
                blacklist.put(bp, now);
                continue;
            }
            Long last = nukeAttempts.get(bp);
            if (last != null && now - last < NUKE_RETRY_MS) continue;
            nukeAttempts.put(bp, now);
            mc.gameMode.startDestroyBlock(bp, Direction.UP);
            if (!swung) {
                p.swing(InteractionHand.MAIN_HAND);
                swung = true;
            }
        }
        // count and forget the ones that are gone
        nukeFirst.keySet().removeIf(bp -> {
            if (MushroomTracker.isMushroom(mc.level.getBlockState(bp).getBlock())) return false;
            broken++;
            nukeAttempts.remove(bp);
            return true;
        });
    }

    // ---- tool / speed handling ------------------------------------------------------------

    /** Returns true while the sword sequence is in progress (mining is paused). */
    private boolean handleSpeedBoost(Minecraft mc, LocalPlayer p, long now) {
        if (phase == Phase.NONE) {
            if (now < nextSpeedCheck || target != null) return false;
            nextSpeedCheck = now + SPEED_CHECK_MS + rand.nextInt(1500);
            int speed = readTabSpeed(mc);
            if (speed < 0) {
                if (!warnedNoSpeed) {
                    Chat.msg("Can't find \"Speed:\" in the tab list; speed check skipped.");
                    warnedNoSpeed = true;
                }
                return false;
            }
            if (speed >= SPEED_TARGET) return false;
            int slot = findSlot(p, Items.GOLDEN_SWORD);
            if (slot < 0) {
                Chat.msg("Speed is " + speed + " but no golden sword in hotbar.");
                return false;
            }
            p.getInventory().setSelectedSlot(slot);
            wait = 3 + rand.nextInt(4);
            phase = Phase.TO_SWORD;
            return true;
        }
        if (wait-- > 0) return true;
        switch (phase) {
            case TO_SWORD -> {
                mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                p.swing(InteractionHand.MAIN_HAND);
                wait = 4 + rand.nextInt(5);
                phase = Phase.USED;
            }
            case USED -> {
                int tool = findSlot(p, Items.SHEARS);
                if (tool >= 0) p.getInventory().setSelectedSlot(tool);
                wait = 2 + rand.nextInt(3);
                phase = Phase.TO_TOOL;
            }
            default -> phase = Phase.NONE;
        }
        return phase != Phase.NONE;
    }

    /** Makes sure the Mooby shears are in hand. Returns false if they can't be found. */
    private boolean ensureTool(LocalPlayer p) {
        int slot = findSlot(p, Items.SHEARS);
        if (slot < 0) return false;
        if (p.getInventory().getSelectedSlot() != slot) p.getInventory().setSelectedSlot(slot);
        return true;
    }

    /** First hotbar slot holding the given item type, or -1. */
    private static int findSlot(LocalPlayer p, Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty() && st.is(item)) return i;
        }
        return -1;
    }

    /** Reads the "Speed: ✦400" widget from the tab list; -1 if it isn't there. */
    public static int readTabSpeed(Minecraft mc) {
        var conn = mc.getConnection();
        if (conn == null) return -1;
        for (var info : conn.getOnlinePlayers()) {
            Component name = info.getTabListDisplayName();
            if (name == null) continue;
            Matcher m = SPEED.matcher(name.getString());
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return -1;
    }

    // ---- mining / walking -------------------------------------------------------------------

    private BlockPos pickTarget(Minecraft mc, LocalPlayer p) {
        Vec3 eyes = p.getEyePosition();
        BlockPos best = null;
        double bestD = REACH * REACH;
        for (BlockPos bp : MushroomTracker.MUSHROOMS.keySet()) {
            if (blacklist.containsKey(bp)) continue;
            Vec3 c = bp.getCenter();
            double d = eyes.distanceToSqr(c);
            if (d > bestD) continue;
            BlockHitResult hit = mc.level.clip(new ClipContext(eyes, c,
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
            if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(bp)) continue;
            bestD = d;
            best = bp;
        }
        return best;
    }

    private void mine(Minecraft mc, LocalPlayer p) {
        mc.options.keyUp.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(false);
        float[] ang = anglesTo(p, targetPoint);
        boolean aimed = aim.update(mc, p, ang[0], ang[1], 2.5f);
        boolean onTarget = aimed && mc.hitResult instanceof BlockHitResult bhr
                && bhr.getType() == HitResult.Type.BLOCK && bhr.getBlockPos().equals(target);
        mc.options.keyAttack.setDown(onTarget);
    }

    private void walk(Minecraft mc, LocalPlayer p) {
        if (route.size() == 0) {
            stop(mc);
            return;
        }
        Vec3 wp = route.get(waypoint);
        double dx = wp.x - p.getX(), dz = wp.z - p.getZ();
        if (Math.sqrt(dx * dx + dz * dz) < ARRIVE_DIST && Math.abs(wp.y - p.getY()) < 2.5) {
            waypoint = (waypoint + 1) % route.size();
            return;
        }
        float[] ang = anglesTo(p, new Vec3(wp.x, p.getEyeY(), wp.z));
        // people look a little downward while walking rather than dead level
        aim.update(mc, p, ang[0], 6f, 3.5f);
        mc.options.keyUp.setDown(true);
        mc.options.keySprint.setDown(true);
        mc.options.keyJump.setDown(p.horizontalCollision && p.onGround());

        Vec3 pos = p.position();
        if (lastPos != null && pos.distanceToSqr(lastPos) < 0.0004) stuckTicks++;
        else stuckTicks = 0;
        lastPos = pos;
        if (stuckTicks > 60) {
            Chat.msg("Stuck at waypoint " + (waypoint + 1) + " - macro stopped.");
            stop(mc);
        }
    }

    private static float[] anglesTo(LocalPlayer p, Vec3 to) {
        Vec3 eyes = p.getEyePosition();
        double dx = to.x - eyes.x, dy = to.y - eyes.y, dz = to.z - eyes.z;
        float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        float pitch = (float) -(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI);
        return new float[]{yaw, pitch};
    }

    private void releaseKeys(Minecraft mc) {
        for (KeyMapping k : new KeyMapping[]{mc.options.keyUp, mc.options.keySprint,
                mc.options.keyJump, mc.options.keyAttack}) {
            k.setDown(false);
        }
    }

    public int waypointIndex() {
        return waypoint;
    }
}
