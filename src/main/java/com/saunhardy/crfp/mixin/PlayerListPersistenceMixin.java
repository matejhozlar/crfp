package com.saunhardy.crfp.mixin;

import com.saunhardy.crfp.fakeplayer.CRFPFakePlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Skip vanilla playerdata IO for fake players. We have our own JSON persistence,
 * and letting vanilla write per-loader dat files has two downsides:
 *
 *   1. They accumulate in world/playerdata/ because nothing deletes them on removal.
 *   2. On restore, the reload reads stale NBT (modded capabilities, saved abilities,
 *      old position) and silently overrides the defaults set in CRFPFakePlayer's
 *      constructor. Cleaner to skip the round-trip entirely.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListPersistenceMixin {

    @Inject(method = "save(Lnet/minecraft/server/level/ServerPlayer;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private void crfp$skipSave(ServerPlayer player, CallbackInfo ci) {
        if (player instanceof CRFPFakePlayer) ci.cancel();
    }

    @Inject(method = "load(Lnet/minecraft/server/level/ServerPlayer;)Lnet/minecraft/nbt/CompoundTag;",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private void crfp$skipLoad(ServerPlayer player, CallbackInfoReturnable<CompoundTag> cir) {
        if (player instanceof CRFPFakePlayer) cir.setReturnValue(null);
    }
}
