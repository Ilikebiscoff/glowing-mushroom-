package com.glowingmushroom.mixin;

import com.glowingmushroom.ProfitTracker;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every block the client breaks goes through here; the profit tracker confirms it a moment later. */
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void glowingmushroom$onDestroy(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        ProfitTracker.onBreakAttempt(pos);
    }
}
