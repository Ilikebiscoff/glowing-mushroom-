package com.glowingmushroom;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** Walks the route in a loop and breaks any tracked mushroom that is in reach. */
public class MacroController {
    private static final double REACH = 4.4;
    private static final double ARRIVE_DIST = 0.7;
    private static final long MINE_TIMEOUT_MS = 3500;
    private static final long BLACKLIST_MS = 15000;

    private final Minecraft mc = Minecraft.getMinecraft();
    private final Route route;
    private final MushroomTracker tracker;
    private final Random rand = new Random();

    private boolean running;
    private int waypoint;
    private BlockPos target;
    private long targetSince;
    private final Map<BlockPos, Long> blacklist = new HashMap<BlockPos, Long>();
    private int stuckTicks;
    private Vec3 lastPos;

    public int broken;

    public MacroController(Route route, MushroomTracker tracker) {
        this.route = route;
        this.tracker = tracker;
    }

    public boolean isRunning() {
        return running;
    }

    public void start() {
        if (route.size() == 0) return;
        EntityPlayerSP p = mc.thePlayer;
        // Begin at the nearest waypoint
        double best = Double.MAX_VALUE;
        for (int i = 0; i < route.size(); i++) {
            double d = route.get(i).squareDistanceTo(p.getPositionVector());
            if (d < best) {
                best = d;
                waypoint = i;
            }
        }
        target = null;
        running = true;
    }

    public void stop() {
        running = false;
        releaseKeys();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.START || !running) return;
        EntityPlayerSP p = mc.thePlayer;
        if (p == null || mc.theWorld == null || mc.currentScreen != null) {
            releaseKeys();
            return;
        }

        long now = System.currentTimeMillis();
        blacklist.values().removeIf(t -> now - t > BLACKLIST_MS);

        // Drop the current target if it was mined, expired or took too long
        if (target != null) {
            boolean gone = !MushroomTracker.isMushroom(mc.theWorld.getBlockState(target).getBlock());
            if (gone) {
                broken++;
                target = null;
            } else if (now - targetSince > MINE_TIMEOUT_MS) {
                blacklist.put(target, now);
                target = null;
            }
        }
        if (target == null) {
            target = pickTarget(p);
            targetSince = now;
        }

        if (target != null) {
            mine(p);
        } else {
            setKey(mc.gameSettings.keyBindAttack, false);
            walk(p);
        }
    }

    /** Nearest tracked mushroom within reach that we have line of sight to. */
    private BlockPos pickTarget(EntityPlayerSP p) {
        Vec3 eyes = p.getPositionEyes(1f);
        BlockPos best = null;
        double bestD = REACH * REACH;
        for (BlockPos bp : tracker.mushrooms.keySet()) {
            if (blacklist.containsKey(bp)) continue;
            Vec3 c = new Vec3(bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5);
            double d = eyes.squareDistanceTo(c);
            if (d > bestD) continue;
            MovingObjectPosition mop = mc.theWorld.rayTraceBlocks(eyes, c, false, true, false);
            if (mop != null && !mop.getBlockPos().equals(bp)) continue;
            bestD = d;
            best = bp;
        }
        return best;
    }

    private void mine(EntityPlayerSP p) {
        setKey(mc.gameSettings.keyBindForward, false);
        setKey(mc.gameSettings.keyBindJump, false);
        Vec3 c = new Vec3(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        boolean aimed = aimAt(p, c, 14f);
        MovingObjectPosition over = mc.objectMouseOver;
        boolean onTarget = aimed && over != null
                && over.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && over.getBlockPos().equals(target);
        if (onTarget && !mc.gameSettings.keyBindAttack.isKeyDown()) {
            KeyBinding.onTick(mc.gameSettings.keyBindAttack.getKeyCode());
        }
        setKey(mc.gameSettings.keyBindAttack, onTarget);
    }

    private void walk(EntityPlayerSP p) {
        if (route.size() == 0) {
            stop();
            return;
        }
        Vec3 wp = route.get(waypoint);
        double dx = wp.xCoord - p.posX, dz = wp.zCoord - p.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < ARRIVE_DIST && Math.abs(wp.yCoord - p.posY) < 2.5) {
            waypoint = (waypoint + 1) % route.size();
            return;
        }
        // Look at the waypoint horizontally, keeping the head level
        Vec3 look = new Vec3(wp.xCoord, p.posY + p.getEyeHeight(), wp.zCoord);
        aimAt(p, look, 9f);
        setKey(mc.gameSettings.keyBindForward, true);
        setKey(mc.gameSettings.keyBindSprint, true);

        // Jump over steps; detect being stuck
        boolean jump = p.isCollidedHorizontally && p.onGround;
        setKey(mc.gameSettings.keyBindJump, jump);
        Vec3 pos = p.getPositionVector();
        if (lastPos != null && pos.squareDistanceTo(lastPos) < 0.0004) stuckTicks++;
        else stuckTicks = 0;
        lastPos = pos;
        if (stuckTicks > 60) {
            Chat.msg("Stuck at waypoint " + (waypoint + 1) + " - macro stopped.");
            stop();
        }
    }

    /** Rotates smoothly toward a point. Returns true once the crosshair is (almost) there. */
    private boolean aimAt(EntityPlayerSP p, Vec3 to, float maxStep) {
        Vec3 eyes = p.getPositionEyes(1f);
        double dx = to.xCoord - eyes.xCoord, dy = to.yCoord - eyes.yCoord, dz = to.zCoord - eyes.zCoord;
        float wantYaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        float wantPitch = (float) -(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI);

        float dYaw = MathHelper.wrapAngleTo180_float(wantYaw - p.rotationYaw);
        float dPitch = wantPitch - p.rotationPitch;
        float step = maxStep * (0.7f + rand.nextFloat() * 0.3f);
        p.rotationYaw += clamp(dYaw * 0.45f, -step, step);
        p.rotationPitch = MathHelper.clamp_float(p.rotationPitch + clamp(dPitch * 0.45f, -step, step), -90f, 90f);
        return Math.abs(dYaw) < 4f && Math.abs(dPitch) < 4f;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void setKey(KeyBinding k, boolean down) {
        KeyBinding.setKeyBindState(k.getKeyCode(), down);
    }

    private void releaseKeys() {
        setKey(mc.gameSettings.keyBindForward, false);
        setKey(mc.gameSettings.keyBindSprint, false);
        setKey(mc.gameSettings.keyBindJump, false);
        setKey(mc.gameSettings.keyBindAttack, false);
    }

    public int waypointIndex() {
        return waypoint;
    }

    public BlockPos currentTarget() {
        return target;
    }
}
