package com.glowingmushroom.mixin;

import com.glowingmushroom.MushroomTracker;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sees every particle packet, including ones inside bundle packets. */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    @Inject(method = "handleParticleEvent", at = @At("HEAD"))
    private void glowingmushroom$onParticle(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        MushroomTracker.offer(packet);
    }
}
