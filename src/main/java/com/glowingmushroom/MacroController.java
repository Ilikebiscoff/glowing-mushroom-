package com.glowingmushroom;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** Walks the route in a loop and breaks any tracked mushroom that is in reach. */
public class MacroController {
    private static final double REACH = 4.4;
    private static final double ARRIVE_DIST = 0.7;
    private static final long MINE_TIMEOUT_MS = 3500;
    private static final long BLACKLIST_MS = 15000;

    private final Route route;
    private final Random rand = new Random();
    private final Map<BlockPos, Long> blacklist = new HashMap<>();

    private boolean running;
    private int waypoint;
    private BlockPos target;
    private long targetSince;
    private int stuckTicks;
    private Vec3 lastPos;

    public int broken;

    public MacroController(Route route) {
        this.route = route;
    }

    public boolean isRunning() {
        return running;
    }

    public void start(Minecraft mc) {
        if (route.size() == 0 || mc.player == null) return;
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
        running = true;
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
        }

        if (target != null) {
            mine(mc, p);
        } else {
            mc.options.keyAttack.setDown(false);
            walk(mc, p);
        }
    }

    /** Nearest tracked mushroom within reach that we can see. */
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
        mc.options.keyJump.setDown(false);
        boolean aimed = aimAt(p, target.getCenter(), 14f);
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
        aimAt(p, new Vec3(wp.x, p.getEyeY(), wp.z), 9f);
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

    /** Rotates smoothly toward a point. Returns true once the crosshair is (almost) there. */
    private boolean aimAt(LocalPlayer p, Vec3 to, float maxStep) {
        Vec3 eyes = p.getEyePosition();
        double dx = to.x - eyes.x, dy = to.y - eyes.y, dz = to.z - eyes.z;
        float wantYaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        float wantPitch = (float) -(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI);

        float dYaw = Mth.wrapDegrees(wantYaw - p.getYRot());
        float dPitch = wantPitch - p.getXRot();
        float step = maxStep * (0.7f + rand.nextFloat() * 0.3f);
        p.setYRot(p.getYRot() + Mth.clamp(dYaw * 0.45f, -step, step));
        p.setXRot(Mth.clamp(p.getXRot() + Mth.clamp(dPitch * 0.45f, -step, step), -90f, 90f));
        return Math.abs(dYaw) < 4f && Math.abs(dPitch) < 4f;
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
