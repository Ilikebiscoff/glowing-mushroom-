package com.glowingmushroom;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.network.play.server.S2APacketParticles;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens to S2APacketParticles (like SkyHanni's highlighters do) and remembers the
 * mushroom blocks next to the configured marker particle.
 */
public class MushroomTracker {
    /** Particle that Hypixel spawns on a glowing mushroom. Change live with /gm particle <TYPE>. */
    public volatile EnumParticleTypes markerParticle = EnumParticleTypes.SPELL_MOB;
    /** When true, every particle seen is counted so the right marker can be found. */
    public volatile boolean debug = false;

    private final ConcurrentLinkedQueue<S2APacketParticles> queue = new ConcurrentLinkedQueue<S2APacketParticles>();
    private final Map<EnumParticleTypes, Integer> seen = new EnumMap<EnumParticleTypes, Integer>(EnumParticleTypes.class);
    /** Known mushrooms -> last time (ms) a particle confirmed them. */
    public final Map<BlockPos, Long> mushrooms = new ConcurrentHashMap<BlockPos, Long>();

    private static final long EXPIRE_MS = 8000;

    @SubscribeEvent
    public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent e) {
        try {
            e.manager.channel().pipeline().addBefore("packet_handler", "gm_particles", new ChannelDuplexHandler() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    if (msg instanceof S2APacketParticles) queue.add((S2APacketParticles) msg);
                    super.channelRead(ctx, msg);
                }
            });
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        World w = mc.theWorld;
        S2APacketParticles p;
        while ((p = queue.poll()) != null) {
            if (w == null) continue;
            EnumParticleTypes type = p.getParticleType();
            if (debug) {
                Integer c = seen.get(type);
                seen.put(type, c == null ? 1 : c + 1);
            }
            if (type != markerParticle) continue;
            BlockPos hit = findMushroom(w, p.getXCoordinate(), p.getYCoordinate(), p.getZCoordinate());
            if (hit != null) mushrooms.put(hit, System.currentTimeMillis());
        }
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<BlockPos, Long>> it = mushrooms.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, Long> en = it.next();
            if (now - en.getValue() > EXPIRE_MS || w == null || !isMushroom(w.getBlockState(en.getKey()).getBlock())) {
                it.remove();
            }
        }
    }

    public Map<EnumParticleTypes, Integer> drainSeen() {
        Map<EnumParticleTypes, Integer> copy = new EnumMap<EnumParticleTypes, Integer>(seen);
        seen.clear();
        return copy;
    }

    /** Looks at the particle's block and its neighbours for a mushroom. */
    private BlockPos findMushroom(World w, double x, double y, double z) {
        BlockPos base = new BlockPos(Math.floor(x), Math.floor(y), Math.floor(z));
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos bp = base.add(dx, dy, dz);
                    if (!isMushroom(w.getBlockState(bp).getBlock())) continue;
                    double d = bp.distanceSqToCenter(x, y, z);
                    if (d < bestDist) {
                        bestDist = d;
                        best = bp;
                    }
                }
        return best;
    }

    public static boolean isMushroom(Block b) {
        return b == Blocks.red_mushroom || b == Blocks.brown_mushroom
                || b == Blocks.red_mushroom_block || b == Blocks.brown_mushroom_block;
    }
}
