package com.saunhardy.crfp.fakeplayer;

import com.mojang.authlib.GameProfile;
import com.saunhardy.crfp.CRFP;
import com.saunhardy.crfp.core.ChunkloaderRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;

/**
 * A {@link FakePlayer} that is actually placed into the player list so the chunk manager
 * loads chunks around it and natural mob-spawning treats it as a player. Immortal, silent,
 * gravity-locked so it never drifts off its spawn block.
 *
 * <p>Extending {@link FakePlayer} lets any NeoForge-aware mod filter us out via
 * {@code instanceof FakePlayer}. That's the whole reason we inherit from it instead of
 * {@code ServerPlayer} directly.
 */
public final class CRFPFakePlayer extends FakePlayer {
    private final Connection dummyConnection;
    private final CommonListenerCookie cookie;
    private boolean placed;
    private @Nullable Throwable lastPlaceError;

    public CRFPFakePlayer(ServerLevel level, GameProfile profile) {
        super(level, profile);
        this.dummyConnection = new DummyConnection();
        this.cookie = CommonListenerCookie.createInitial(profile, false);

        // Side effect: the constructor installs itself onto this.connection.
        // We never read it, but the server expects it to be non-null once the player is placed.
        new ServerGamePacketListenerImpl(level.getServer(), dummyConnection, this, cookie);

        this.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        this.setInvulnerable(true);
        this.getAbilities().invulnerable = true;
        this.setSilent(true);
        this.setNoGravity(true);
        this.setHealth(this.getMaxHealth());
    }

    public boolean isPlaced() {
        return placed;
    }

    /** The exception thrown by the most recent failed {@link #placeInWorld()}, if any. */
    public @Nullable Throwable lastPlaceError() {
        return lastPlaceError;
    }

    /**
     * Logs this player into the server as if a client had connected. Returns true on success.
     *
     * <p>{@code placeNewPlayer} registers us in the player list and the level before it fires
     * PlayerLoggedInEvent, so if another mod's login handler throws we would otherwise be left
     * standing in the world with nobody managing us. Undo the registration in that case.
     */
    public boolean placeInWorld() {
        if (placed) return true;
        MinecraftServer server = serverLevel().getServer();
        PlayerList playerList = server.getPlayerList();
        try {
            playerList.placeNewPlayer(dummyConnection, this, cookie);
            placed = true;
            lastPlaceError = null;
        } catch (Exception e) {
            lastPlaceError = e;
            if (playerList.getPlayer(getUUID()) == this) {
                try {
                    playerList.remove(this);
                } catch (Exception cleanup) {
                    CRFP.LOGGER.warn("Failed to undo partial login of fake player {}", getGameProfile().getName(), cleanup);
                }
            }
        }
        return placed;
    }

    public void removeFromWorld() {
        if (!placed) return;
        MinecraftServer server = serverLevel().getServer();
        try {
            this.disconnect();
            server.getPlayerList().remove(this);
        } finally {
            try {
                dummyConnection.disconnect(Component.literal("crfp despawn"));
            } catch (Exception ignored) {
            }
            placed = false;
        }
    }

    @Override
    public void tick() {
        super.tick();
        this.fallDistance = 0.0f;
        if (this.getHealth() < this.getMaxHealth()) {
            this.setHealth(this.getMaxHealth());
        }
    }

    // NeoForge's FakePlayer overrides position() and blockPosition() to return ZERO
    // because it's designed for headless action simulation, not for being placed in the
    // world. That breaks chunk tracking — ChunkMap.addPlayer registers tickets at (0,0)
    // regardless of where we spawned. Restore vanilla Entity behavior.
    @Override
    public Vec3 position() {
        return new Vec3(this.getX(), this.getY(), this.getZ());
    }

    @Override
    public BlockPos blockPosition() {
        return new BlockPos(Mth.floor(this.getX()), Mth.floor(this.getY()), Mth.floor(this.getZ()));
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canHarmPlayer(Player player) {
        return false;
    }

    @Override
    public void sendSystemMessage(Component message, boolean overlay) {
        // swallow
    }

    @Override
    public boolean isSpectator() {
        return false;
    }

    @Override
    public boolean isCreative() {
        return false;
    }

    @Override
    public @Nullable Component getTabListDisplayName() {
        int slot = ChunkloaderRegistry.slotOf(getGameProfile().getName());
        String displayNumber = slot > 0 ? "#" + slot : getGameProfile().getName();
        return Component.literal("[CRFP] ").withStyle(ChatFormatting.DARK_AQUA)
                .append(Component.literal("Createrington" + displayNumber).withStyle(ChatFormatting.GRAY));
    }

    public void killSilently() {
        removeFromWorld();
        this.remove(Entity.RemovalReason.DISCARDED);
    }
}
