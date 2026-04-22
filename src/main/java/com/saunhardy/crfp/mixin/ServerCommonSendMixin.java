package com.saunhardy.crfp.mixin;

import com.saunhardy.crfp.fakeplayer.DummyConnection;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Silently drops any outbound packet bound for a fake-player connection.
 *
 * Without this, mods that send their own payloads on PlayerLoggedInEvent or in broadcast
 * ticks (Create's track-graph sync, ServerSpeedProvider, and anything similar) trigger
 * {@code NetworkRegistry.checkPacket} — which throws because our fake connection never
 * completed the handshake that registers modded payloads. That exception cascades out of
 * the event bus and crashes the server.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonSendMixin {

    @Shadow @Final protected Connection connection;

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void crfp$dropForFake(Packet<?> packet, CallbackInfo ci) {
        if (connection instanceof DummyConnection) ci.cancel();
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void crfp$dropForFake(Packet<?> packet, PacketSendListener listener, CallbackInfo ci) {
        if (connection instanceof DummyConnection) ci.cancel();
    }
}
