package com.saunhardy.crfp.mixin;

import com.saunhardy.crfp.CRFP;
import com.saunhardy.crfp.core.ChunkloaderRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses the vanilla "X joined the game" / "X left the game" chat broadcasts when
 * X is one of our managed fake players. Everything else about the join/leave flow
 * (events, tab list, etc.) runs as normal.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListBroadcastMixin {

    @Inject(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V",
            at = @At("HEAD"),
            cancellable = true)
    private void crfp$suppressJoinLeave(Component message, boolean overlay, CallbackInfo ci) {
        if (shouldSuppress(message)) ci.cancel();
    }

    private static boolean shouldSuppress(Component message) {
        if (message == null) return false;
        if (!(message.getContents() instanceof TranslatableContents tc)) return false;
        String key = tc.getKey();
        // "joined.renamed" is used when usercache.json knows the UUID under a different name.
        if (!"multiplayer.player.joined".equals(key)
                && !"multiplayer.player.joined.renamed".equals(key)
                && !"multiplayer.player.left".equals(key)) {
            return false;
        }
        ChunkloaderRegistry reg = CRFP.registry();
        if (reg == null) return false;

        Object[] args = tc.getArgs();
        if (args == null || args.length == 0) return false;
        String name = extractName(args[0]);
        return name != null && reg.isManagedFakeName(name);
    }

    private static String extractName(Object arg) {
        if (arg instanceof Component c) return c.getString();
        if (arg instanceof String s) return s;
        return arg == null ? null : arg.toString();
    }
}
