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

import com.glowingmushroom.pathing.Planner;
import com.glowingmushroom.pathing.WalkCache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Path mode (default): goes to the densest reachable group of known glowing mushrooms using the
 * background {@link Planner}, clears it, and moves straight on to the next group; walks the recorded
 * route as a patrol while no mushrooms are known. Route mode: just loops the route.
 * Breaks mushrooms with the shears (nuker or aimed), and every ~25 s reads the tab list and uses the
 * golden sword's speed ability when speed is below 400.
 */
public class MacroController {
    private static final double REACH = 4.4;
    private static final double ARRIVE_DIST = 1.2;
    /** How far ahead along the route the camera aims while walking (pure-pursuit "carrot"). */
    private static final double LOOKAHEAD = 3.0;
    /** Camera turn speed cap while walking, deg/s. */
    private static final float WALK_TURN_SPEED = 240f;
    private static final long MINE_TIMEOUT_MS = 3500;
    private static final long BLACKLIST_MS = 15000;
    private static final int SPEED_TARGET = 400;
    private static final long SPEED_CHECK_MS = 25_000;
    private static final Pattern SPEED = Pattern.compile("Speed:\\s*\\D*?(\\d+)");


    private enum Phase { NONE, TO_SWORD, USED, TO_TOOL }

    private final Route route;
    private final WalkCache cache;
    private final Planner planner = new Planner();
    private final HumanAim aim = new HumanAim();

    /** A list of points being walked, with progress. */
    private static final class Cursor {
        final List<Vec3> pts;
        final boolean loop;
        int idx;

        Cursor(List<Vec3> pts, boolean loop) {
            this.pts = pts;
            this.loop = loop;
        }
    }

    private enum Follow { MOVING, ARRIVED, STUCK }

    /** true = pathfind to mushroom groups (route is a patrol fallback); false = only walk the route. */
    public volatile boolean pathMode = true;
    private Planner.Leg leg, nextLeg;
    private Cursor legCursor, routeCursor;
    private long arrivedAt, lastPlanRequest;
    private int legStuck, seenVersion = -1;
    private boolean planDirty;
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

    public MacroController(Route route, WalkCache cache) {
        this.route = route;
        this.cache = cache;
    }

    /** Called every rendered frame so the camera moves smoothly instead of 20 times a second. */
    public void frame(Minecraft mc) {
        if (!running || mc.player == null || mc.screen != null) return;
        aim.frame(mc, mc.player);
    }

    public boolean isRunning() {
        return running;
    }

    /** Returns true if the macro actually started. */
    public boolean start(Minecraft mc) {
        if (mc.player == null) return false;
        if (!pathMode && route.size() == 0) {
            Chat.msg("Route mode needs a route: record one with /glowing add.");
            return false;
        }
        if (findSlot(mc.player, Items.SHEARS) < 0) {
            Chat.msg("No shears in your hotbar. Not started.");
            return false;
        }
        target = null;
        leg = nextLeg = null;
        legCursor = routeCursor = null;
        planDirty = true;
        lastPlanRequest = 0;
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
            navigate(mc, p, now);
            return;
        }
        if (!ensureTool(p)) {
            return;
        }

        if (nuker) {
            nuke(mc, p, now);
            mc.options.keyAttack.setDown(false);
            navigate(mc, p, now);
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
                aim.retarget();
            }
        }

        if (target != null) {
            mine(mc, p);
        } else {
            mc.options.keyAttack.setDown(false);
            navigate(mc, p, now);
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
        aim.setTarget(ang[0], ang[1]);
        boolean aimed = aim.settled(p, 2.5f);
        boolean onTarget = aimed && mc.hitResult instanceof BlockHitResult bhr
                && bhr.getType() == HitResult.Type.BLOCK && bhr.getBlockPos().equals(target);
        mc.options.keyAttack.setDown(onTarget);
    }

    // ---- navigation --------------------------------------------------------------------------

    private void navigate(Minecraft mc, LocalPlayer p, long now) {
        if (!pathMode) {
            patrol(mc, p);
            return;
        }

        Planner.Plan plan = planner.poll();
        if (plan != null) accept(plan, p);

        int v = MushroomTracker.version();
        if (v != seenVersion) {
            seenVersion = v;
            planDirty = true;
        }
        long interval = leg == null ? 600 : 2000;
        if ((planDirty || now - lastPlanRequest > interval) && now - lastPlanRequest > 250) requestPlan(p, now);

        // current group cleared -> take the pre-planned next one immediately
        if (leg != null && !alive(leg)) {
            leg = nextLeg != null && alive(nextLeg) ? nextLeg : null;
            nextLeg = null;
            if (leg != null) beginLeg(leg, p);
            planDirty = true;
        }

        if (leg == null) {
            patrol(mc, p);
            return;
        }
        routeCursor = null; // re-pick the nearest waypoint next time we patrol

        Follow r = follow(mc, p, legCursor, 20);
        if (r == Follow.ARRIVED) {
            if (arrivedAt == 0) arrivedAt = now;
            else if (now - arrivedAt > 1500) { // standing here but they won't break
                for (BlockPos t : leg.targets()) blacklist.put(t, now);
                leg = null;
                planDirty = true;
            }
        } else if (r == Follow.STUCK) {
            if (++legStuck >= 2) {
                for (BlockPos t : leg.targets()) blacklist.put(t, now);
                leg = null;
            }
            planDirty = true;
        }
    }

    private void requestPlan(LocalPlayer p, long now) {
        List<BlockPos> ms = new ArrayList<>();
        for (BlockPos m : MushroomTracker.snapshot()) if (!blacklist.containsKey(m)) ms.add(m);
        Set<BlockPos> keep = leg == null ? Set.of() : new HashSet<>(leg.targets());
        if (planner.request(cache.grid(), p.blockPosition(), ms, keep)) {
            lastPlanRequest = now;
            planDirty = false;
        }
    }

    private void accept(Planner.Plan plan, LocalPlayer p) {
        if (plan.legs().isEmpty()) {
            if (leg != null && !alive(leg)) leg = null;
            nextLeg = null;
            return;
        }
        Planner.Leg first = plan.legs().get(0);
        nextLeg = plan.legs().size() > 1 ? plan.legs().get(1) : null;
        if (leg != null && first.stand().equals(leg.stand())) return; // same goal, keep walking smoothly
        leg = first;
        beginLeg(first, p);
    }

    private void beginLeg(Planner.Leg l, LocalPlayer p) {
        legCursor = new Cursor(l.path(), false);
        // continue from the path point nearest to us
        List<Vec3> pts = l.path();
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < pts.size(); i++) {
            double d = pts.get(i).distanceToSqr(p.position());
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        legCursor.idx = Math.min(best + 1, pts.size() - 1);
        arrivedAt = 0;
        legStuck = 0;
        stuckTicks = 0;
    }

    /** Some of the leg's mushrooms are still there to break. */
    private boolean alive(Planner.Leg l) {
        for (BlockPos t : l.targets())
            if (MushroomTracker.MUSHROOMS.containsKey(t) && !blacklist.containsKey(t)) return true;
        return false;
    }

    /** Walks the recorded route in a loop; idles if there is none. */
    private void patrol(Minecraft mc, LocalPlayer p) {
        if (route.size() == 0) {
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
            mc.options.keyJump.setDown(false);
            return;
        }
        if (routeCursor == null || routeCursor.pts.size() != route.size()) {
            routeCursor = new Cursor(route.points(), true);
            double best = Double.MAX_VALUE;
            for (int i = 0; i < routeCursor.pts.size(); i++) {
                double d = routeCursor.pts.get(i).distanceToSqr(p.position());
                if (d < best) {
                    best = d;
                    routeCursor.idx = i;
                }
            }
        }
        waypoint = routeCursor.idx;
        if (follow(mc, p, routeCursor, 60) == Follow.STUCK) {
            Chat.msg("Stuck at waypoint " + (routeCursor.idx + 1) + " - macro stopped.");
            stop(mc);
        }
    }

    /**
     * Walks along {@code c}: pure-pursuit steering at a point ahead on the path, speed-scaled arrival,
     * turn-before-walk on sharp corners, jump only when a block must be climbed.
     */
    private Follow follow(Minecraft mc, LocalPlayer p, Cursor c, int stuckLimit) {
        int n = c.pts.size();
        Vec3 pos = p.position();
        if (n == 0 || (!c.loop && c.idx >= n)) return arrive(mc);

        Vec3 wp = c.pts.get(c.idx);
        boolean hasPrev = c.loop ? n > 1 : c.idx > 0;
        Vec3 prev = hasPrev ? c.pts.get((c.idx - 1 + n) % n) : pos;

        double speed = Math.hypot(p.getDeltaMovement().x, p.getDeltaMovement().z); // blocks per tick
        boolean last = !c.loop && c.idx == n - 1;
        double arrive = last ? 0.6 : Math.max(ARRIVE_DIST, speed * 4);
        if (hDist(pos, wp) < arrive || (hasPrev && segmentT(prev, wp, pos) >= 1.0)) {
            c.idx++;
            if (!c.loop && c.idx >= n) return arrive(mc);
            c.idx %= n;
            wp = c.pts.get(c.idx);
            hasPrev = c.loop ? n > 1 : c.idx > 0;
            prev = hasPrev ? c.pts.get((c.idx - 1 + n) % n) : pos;
            last = !c.loop && c.idx == n - 1;
        }

        Vec3 carrot = hasPrev ? carrot(c, prev, pos) : wp;
        float[] ang = anglesTo(p, new Vec3(carrot.x, p.getEyeY(), carrot.z));
        aim.setTarget(ang[0], 6f, WALK_TURN_SPEED, 0.6f); // a little downward, like a person walking

        float headingErr = Math.abs(net.minecraft.util.Mth.wrapDegrees(ang[0] - p.getYRot()));
        boolean finalApproach = last && hDist(pos, wp) < 2.5;
        mc.options.keyUp.setDown(headingErr < 75f);
        mc.options.keySprint.setDown(headingErr < 35f && !finalApproach);
        mc.options.keyJump.setDown(p.horizontalCollision && p.onGround() && needsStepUp(mc, p));

        if (lastPos != null && pos.distanceToSqr(lastPos) < 0.0004 && headingErr < 75f) stuckTicks++;
        else stuckTicks = 0;
        lastPos = pos;
        if (stuckTicks > stuckLimit) {
            stuckTicks = 0;
            return Follow.STUCK;
        }
        return Follow.MOVING;
    }

    private Follow arrive(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(false);
        stuckTicks = 0;
        return Follow.ARRIVED;
    }

    /** Point LOOKAHEAD blocks further along the points from our projection onto the current segment. */
    private static Vec3 carrot(Cursor c, Vec3 prev, Vec3 pos) {
        int n = c.pts.size();
        Vec3 a = prev, b = c.pts.get(c.idx);
        double t = Math.max(0, Math.min(1, segmentT(a, b, pos)));
        Vec3 cur = a.add(b.subtract(a).scale(t));
        double left = LOOKAHEAD;
        int idx = c.idx;
        for (int k = 0; k < n; k++) {
            Vec3 target = c.pts.get(idx);
            double d = hDist(cur, target);
            if (d >= left) return cur.add(target.subtract(cur).scale(left / d));
            left -= d;
            cur = target;
            if (!c.loop && idx == n - 1) return cur;
            idx = (idx + 1) % n;
        }
        return cur;
    }

    /** Horizontal position of {@code pos} along segment a→b: 0 at a, 1 at b. */
    private static double segmentT(Vec3 a, Vec3 b, Vec3 pos) {
        double sx = b.x - a.x, sz = b.z - a.z, len2 = sx * sx + sz * sz;
        if (len2 < 1e-6) return 1;
        return ((pos.x - a.x) * sx + (pos.z - a.z) * sz) / len2;
    }

    private static double hDist(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    /**
     * True only when a block in front blocks the feet and there is head room above it, i.e. a jump
     * actually gets us up. Walking down a step or off a ledge never needs one.
     */
    private static boolean needsStepUp(Minecraft mc, LocalPlayer p) {
        double yaw = Math.toRadians(p.getYRot());
        double fx = -Math.sin(yaw) * 0.7, fz = Math.cos(yaw) * 0.7;
        BlockPos feet = BlockPos.containing(p.getX() + fx, p.getY() + 0.1, p.getZ() + fz);
        return isSolid(mc, feet) && !isSolid(mc, feet.above()) && !isSolid(mc, feet.above(2));
    }

    private static boolean isSolid(Minecraft mc, BlockPos pos) {
        return !mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty();
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

    /** The leg being walked (for rendering), or null. */
    public Planner.Leg currentLeg() {
        return running && pathMode ? leg : null;
    }

    public BlockPos currentTarget() {
        return target;
    }

    public int waypointIndex() {
        return waypoint;
    }
}
