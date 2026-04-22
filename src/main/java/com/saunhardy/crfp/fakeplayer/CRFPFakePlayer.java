package com.saunhardy.crfp.fakeplayer;

import com.mojang.authlib.GameProfile;
import com.saunhardy.crfp.CRFP;
import com.saunhardy.crfp.core.Chunkloader;
import com.saunhardy.crfp.core.ChunkloaderRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
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

    public void placeInWorld() {
        if (placed) return;
        MinecraftServer server = getServer();
        if (server == null) {
            CRFP.LOGGER.warn("Cannot place {} — no server", getGameProfile().getName());
            return;
        }
        try {
            server.getPlayerList().placeNewPlayer(dummyConnection, this, cookie);
            placed = true;
        } catch (Exception e) {
            CRFP.LOGGER.error("Failed to place fake player {}", getGameProfile().getName(), e);
        }
    }

    public void removeFromWorld() {
        if (!placed) return;
        MinecraftServer server = getServer();
        try {
            if (server != null) {
                server.getPlayerList().remove(this);
            }
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
        MutableComponent line = Component.literal("[CRFP] ").withStyle(ChatFormatting.DARK_AQUA)
                .append(Component.literal("Createrington" + displayNumber).withStyle(ChatFormatting.GRAY));

        ChunkloaderRegistry reg = CRFP.registry();
        if (reg != null) {
            Chunkloader c = reg.get(getGameProfile().getName());
            if (c != null && !c.reason().isEmpty()) {
                line.append(Component.literal(" · " + c.reason()).withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        return line;
    }

    public void killSilently() {
        removeFromWorld();
        this.remove(Entity.RemovalReason.DISCARDED);
    }
}
