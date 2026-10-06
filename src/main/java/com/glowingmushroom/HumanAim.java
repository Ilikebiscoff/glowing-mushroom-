package com.glowingmushroom;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

import java.util.Random;

/**
 * Moves the camera like a hand on a mouse instead of snapping or lerping at a constant rate:
 * <ul>
 *   <li>reaction delay before a movement starts</li>
 *   <li>minimum-jerk speed profile (slow-fast-slow) with duration scaled to distance (Fitts-like)</li>
 *   <li>curved path, endpoint error and occasional overshoot followed by a small correction</li>
 *   <li>slow hand drift while idle</li>
 *   <li>every step is quantised to the real mouse-sensitivity step, so rotations look like pixel input</li>
 * </ul>
 */
public class HumanAim {
    private final Random rand = new Random();

    private boolean moving;
    private int tick, duration, delay = -1;
    private float totalYaw, totalPitch, appliedYaw, appliedPitch, curve;
    private float plannedGoalYaw, plannedGoalPitch;
    private final float phase1 = rand.nextFloat() * 6.28f, phase2 = rand.nextFloat() * 6.28f;
    private long age;

    /** Forget the current motion (call when the target changes completely). */
    public void reset() {
        moving = false;
        delay = -1;
    }

    /**
     * Steps the camera toward the wanted angles for one tick.
     * @return true when the crosshair is settled within {@code tolerance} degrees
     */
    public boolean update(Minecraft mc, LocalPlayer p, float wantYaw, float wantPitch, float tolerance) {
        age++;
        // slow wandering of the hand
        wantYaw += 0.30f * (float) Math.sin(age * 0.045 + phase1);
        wantPitch += 0.20f * (float) Math.sin(age * 0.061 + phase2);

        float errYaw = Mth.wrapDegrees(wantYaw - p.getYRot());
        float errPitch = wantPitch - p.getXRot();
        float dist = (float) Math.hypot(errYaw, errPitch);

        if (!moving) {
            if (dist <= tolerance) {
                delay = -1;
                return true;
            }
            if (delay < 0) delay = 1 + rand.nextInt(4); // reaction time, 50-200 ms
            if (delay > 0) {
                delay--;
                return false;
            }
            plan(errYaw, errPitch, dist);
            delay = -1;
        } else {
            // target walked away from where this motion is heading: aim the rest of it at the new spot
            float gy = Mth.wrapDegrees(wantYaw - plannedGoalYaw);
            float gp = wantPitch - plannedGoalPitch;
            if (Math.hypot(gy, gp) > Math.max(6f, dist * 0.3f)) {
                float remYaw = errYaw, remPitch = errPitch;
                float done = progress();
                totalYaw = appliedYaw + remYaw;
                totalPitch = appliedPitch + remPitch;
                plannedGoalYaw = wantYaw;
                plannedGoalPitch = wantPitch;
                duration = Math.max(duration, tick + 3);
                if (done > 0.95f) moving = false;
            }
        }

        if (moving) step(mc, p);
        return !moving && dist <= tolerance;
    }

    private void plan(float errYaw, float errPitch, float dist) {
        // endpoint error grows a little with distance, like imprecise flicks
        float noise = 0.12f + dist * 0.01f;
        float ey = errYaw + (float) rand.nextGaussian() * noise;
        float ep = errPitch + (float) rand.nextGaussian() * noise * 0.6f;
        // large flicks sometimes overshoot, the follow-up motion then corrects it
        if (dist > 12f && rand.nextFloat() < 0.35f) {
            float over = 1f + 0.04f + rand.nextFloat() * 0.08f;
            ey *= over;
            ep *= over;
        }
        totalYaw = ey;
        totalPitch = ep;
        appliedYaw = appliedPitch = 0f;
        float ms = (110f + 40f * (float) Math.sqrt(dist)) * (0.85f + rand.nextFloat() * 0.4f);
        duration = Math.max(3, Math.round(ms / 50f));
        tick = 0;
        curve = (rand.nextFloat() - 0.5f) * 0.18f * dist; // sideways bulge in degrees
        plannedGoalYaw = Mth.wrapDegrees(Minecraft.getInstance().player.getYRot() + errYaw);
        plannedGoalPitch = Minecraft.getInstance().player.getXRot() + errPitch;
        moving = true;
    }

    private float progress() {
        return Math.min(1f, tick / (float) duration);
    }

    private void step(Minecraft mc, LocalPlayer p) {
        tick++;
        float t = progress();
        float s = t * t * t * (10f + t * (-15f + 6f * t)); // minimum jerk
        float len = (float) Math.hypot(totalYaw, totalPitch);
        float perpYaw = len > 1e-3f ? -totalPitch / len : 0f;
        float perpPitch = len > 1e-3f ? totalYaw / len : 0f;
        float bulge = (float) Math.sin(Math.PI * t) * curve;
        float wantedYaw = totalYaw * s + perpYaw * bulge;
        float wantedPitch = totalPitch * s + perpPitch * bulge;

        float stepDeg = sensitivityStep(mc);
        float dy = snap(wantedYaw - appliedYaw, stepDeg);
        float dp = snap(wantedPitch - appliedPitch, stepDeg);
        appliedYaw += dy;
        appliedPitch += dp;

        p.setYRot(p.getYRot() + dy);
        p.setXRot(Mth.clamp(p.getXRot() + dp, -90f, 90f));
        if (tick >= duration) moving = false;
    }

    /** Degrees turned by one pixel of mouse movement at the player's sensitivity setting. */
    private static float sensitivityStep(Minecraft mc) {
        double sens = mc.options.sensitivity().get();
        double f = sens * 0.6 + 0.2;
        return (float) (f * f * f * 8.0 * 0.15);
    }

    private static float snap(float v, float step) {
        return step <= 0f ? v : Math.round(v / step) * step;
    }
}
