package com.saunhardy.crfp.mixin;

import com.saunhardy.crfp.fakeplayer.CRFPFakePlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.GameType;
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
 *
 * Rather than returning null from load, we hand back a minimal CompoundTag that just
 * pins the dimension and gamemode to what the constructor set. PlayerList#placeNewPlayer
 * reads Dimension from the tag to decide which ServerLevel to put the player into —
 * returning null would default that to overworld even when the creator ran /crfp add
 * in the nether or end. Encoding our target dim here keeps the placement path
 * dimension-correct from the first tick.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListPersistenceMixin {

    @Inject(method = "save(Lnet/minecraft/server/level/ServerPlayer;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void crfp$skipSave(ServerPlayer player, CallbackInfo ci) {
        if (player instanceof CRFPFakePlayer) ci.cancel();
    }

    @Inject(method = "load(Lnet/minecraft/server/level/ServerPlayer;)Lnet/minecraft/nbt/CompoundTag;",
            at = @At("HEAD"),
            cancellable = true)
    private void crfp$skipLoad(ServerPlayer player, CallbackInfoReturnable<CompoundTag> cir) {
        if (player instanceof CRFPFakePlayer) {
            CompoundTag tag = new CompoundTag();
            tag.putString("Dimension", player.serverLevel().dimension().location().toString());
            // Vanilla's readPlayerMode uses tag.getInt(...) + GameType.byId(...), so the int
            // form matches the on-disk format exactly. A string value would fail the numeric
            // type check and fall back to the server default gamemode.
            tag.putInt("playerGameType", GameType.SURVIVAL.getId());
            tag.putInt("previousPlayerGameType", GameType.SURVIVAL.getId());
            cir.setReturnValue(tag);
        }
    }
}
