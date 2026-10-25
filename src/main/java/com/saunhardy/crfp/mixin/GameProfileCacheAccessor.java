package com.saunhardy.crfp.mixin;

import net.minecraft.server.players.GameProfileCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Read access to the usercache's name index. {@link GameProfileCache#get(String)} queries Mojang for a
 * name it does not know, which is a blocking network call on the server thread; checking the map first
 * keeps the name check local.
 */
@Mixin(GameProfileCache.class)
public interface GameProfileCacheAccessor {
    /** Keys are lowercase player names. */
    @Accessor("profilesByName")
    Map<String, ?> crfp$profilesByName();
}
