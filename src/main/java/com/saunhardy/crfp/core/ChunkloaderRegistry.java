package com.saunhardy.crfp.core;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.saunhardy.crfp.CRFP;
import com.saunhardy.crfp.Config;
import com.saunhardy.crfp.fakeplayer.CRFPFakePlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class ChunkloaderRegistry {
    public static final int TICK_INTERVAL_MS = 50;
    public static final String NAME_PREFIX = "Createrington_";
    public static final int MAX_SLOT = 99; // "Createrington_99" is 16 chars, the Mojang limit
    /** First retry delay for a loader that could not be placed; doubles per failure. 100 ticks = 5 s at 20 TPS. */
    public static final int PLACE_RETRY_INTERVAL_TICKS = 100;
    /** Ceiling for the retry delay. 1200 ticks = 60 s. */
    public static final int MAX_PLACE_RETRY_INTERVAL_TICKS = 1200;
    /** Autosave cadence while loaders exist, so a crash or kill loses at most this much countdown. 600 ticks = 30 s. */
    public static final int AUTOSAVE_INTERVAL_TICKS = 600;
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Pattern SLOT_PATTERN = Pattern.compile("^" + Pattern.quote(NAME_PREFIX) + "(\\d+)$");

    private final MinecraftServer server;
    private final ChunkloaderHistory history;
    private final ConcurrentHashMap<String, Chunkloader> byName = new ConcurrentHashMap<>();
    /** Names of fake players currently in the world or being logged in; used to silence join/leave chat. */
    private final Set<String> managedFakeNames = ConcurrentHashMap.newKeySet();
    private long tickCounter;

    public ChunkloaderRegistry(MinecraftServer server) {
        this.server = server;
        this.history = new ChunkloaderHistory(server);
    }

    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    /** Parses the numeric slot out of a loader name, or returns -1 if it doesn't match the scheme. */
    public static int slotOf(String name) {
        if (name == null) return -1;
        var m = SLOT_PATTERN.matcher(name);
        if (!m.matches()) return -1;
        try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return -1; }
    }

    /** Whether the given in-game player name is currently one of our managed fake players. */
    public boolean isManagedFakeName(String playerName) {
        return managedFakeNames.contains(playerName);
    }

    public @Nullable Chunkloader get(String name) {
        return byName.get(key(name));
    }

    public Collection<Chunkloader> all() {
        return byName.values();
    }

    public ChunkloaderHistory history() {
        return history;
    }

    // ---------- lifecycle ----------

    /**
     * Reads persisted loaders into the registry. Nothing is spawned here: fake players are placed
     * from {@link #tick()} on the first server tick, after every other mod's ServerStartedEvent
     * handler has run, because some mods' PlayerLoggedInEvent handlers assume a fully started server.
     */
    public void load() {
        int pending = 0;
        for (Chunkloader c : ChunkloaderPersistence.load(server)) {
            if (c.remainingMs() <= 0) continue;
            if (byName.putIfAbsent(key(c.name()), c) != null) {
                CRFP.LOGGER.warn("Duplicate loader name '{}' in persisted data; skipping", c.name());
                continue;
            }
            pending++;
        }
        if (pending > 0) {
            CRFP.LOGGER.info("Loaded {} persisted chunkloader(s); placing fake players on the first server tick", pending);
        }
    }

    /** Writes the current state to disk and despawns every fake player. Normal shutdown path. */
    public void saveAndShutdown() {
        List<Chunkloader> snapshot = new ArrayList<>(byName.values());
        ChunkloaderPersistence.save(server, snapshot);
        for (Chunkloader c : snapshot) {
            try {
                CRFPFakePlayer fp = c.fakePlayer();
                if (fp != null) fp.removeFromWorld();
            } catch (Exception | LinkageError e) {
                CRFP.LOGGER.warn("Failed to despawn fake player for chunkloader '{}' during shutdown", c.name(), e);
            }
            c.detach();
        }
        byName.clear();
        managedFakeNames.clear();
    }

    /**
     * Writes the current state to disk without touching the world. Also used as a last resort after
     * a crash, when the levels are already closed and there is nothing left to despawn.
     */
    public void save() {
        ChunkloaderPersistence.save(server, new ArrayList<>(byName.values()));
    }

    // ---------- mutations ----------

    public static final class AddResult {
        public final boolean success;
        public final String message;
        public final @Nullable Chunkloader chunkloader;
        private AddResult(boolean ok, String msg, @Nullable Chunkloader c) {
            this.success = ok; this.message = msg; this.chunkloader = c;
        }
        public static AddResult ok(Chunkloader c) { return new AddResult(true, "ok", c); }
        public static AddResult fail(String m) { return new AddResult(false, m, null); }
    }

    public AddResult add(long minutes, String reason, ServerPlayer creator) {
        int maxMin = Config.MAX_DURATION_MINUTES.get();
        if (minutes < 1 || minutes > maxMin) {
            return AddResult.fail("Duration must be between 1 and " + maxMin + " minutes");
        }

        ServerLevel level = creator.serverLevel();
        BlockPos pos = creator.blockPosition();
        if (pos.getY() < level.getMinBuildHeight()) {
            return AddResult.fail("Cannot place a chunkloader below the world (Y=" + pos.getY() + ")");
        }
        String dim = level.dimension().location().toString();

        int slot = nextAvailableSlot();
        if (slot < 0) {
            return AddResult.fail("All " + MAX_SLOT + " chunkloader slots are in use");
        }
        String name = NAME_PREFIX + slot;

        UUID uuid = offlineUuid(name);
        long now = System.currentTimeMillis();

        Property skin = creator.getGameProfile().getProperties().get("textures").stream().findFirst().orElse(null);
        String skinValue = skin != null ? skin.value() : null;
        String skinSignature = skin != null ? skin.signature() : null;

        Chunkloader c = new Chunkloader(
                uuid, name, reason == null ? "" : reason, dim, pos,
                creator.getUUID(), creator.getGameProfile().getName(),
                now, minutes * 60_000L,
                skinValue, skinSignature
        );

        PlaceFailure failure = placeLoader(c);
        if (failure != null) {
            CRFP.LOGGER.error("Failed to spawn fake player for chunkloader '{}': {}", name, failure.reason(), failure.cause());
            return AddResult.fail("Failed to spawn fake player: " + failure.reason());
        }
        byName.put(key(name), c);
        save();
        history.logCreate(c, minutes * 60_000L);
        return AddResult.ok(c);
    }

    /** Admin-initiated removal. Logs a 'remove' event with the executor. */
    public boolean remove(String name, @Nullable String executor) {
        Chunkloader c = byName.remove(key(name));
        if (c == null) return false;
        history.logRemove(c, executor);
        cleanupRemoved(c);
        return true;
    }

    /**
     * Internal cleanup for a Chunkloader already pulled from byName. Despawning fires
     * PlayerLoggedOutEvent into every other mod, so it is guarded: whatever happens there, the
     * loader is detached, its name released and the file rewritten.
     */
    private void cleanupRemoved(Chunkloader c) {
        try {
            CRFPFakePlayer fp = c.fakePlayer();
            if (fp != null) fp.removeFromWorld();
        } catch (Exception | LinkageError e) {
            CRFP.LOGGER.error("Failed to despawn fake player for chunkloader '{}'", c.name(), e);
        } finally {
            c.detach();
            managedFakeNames.remove(c.name());
            save();
        }
    }

    public record ExtendResult(boolean found, long actuallyAddedMs, long newRemainingMs, boolean capped) {
        public static ExtendResult notFound() { return new ExtendResult(false, 0, 0, false); }
    }

    public ExtendResult extend(String name, long addMinutes, @Nullable String executor) {
        Chunkloader c = byName.get(key(name));
        if (c == null) return ExtendResult.notFound();
        long maxMs = Config.MAX_DURATION_MINUTES.get() * 60_000L;
        long requestedMs = addMinutes * 60_000L;
        long newRemaining = Math.min(c.remainingMs() + requestedMs, maxMs);
        long actuallyAdded = newRemaining - c.remainingMs();
        boolean capped = actuallyAdded < requestedMs;
        c.setRemainingMs(newRemaining);
        save();
        history.logExtend(c, actuallyAdded, newRemaining, executor);
        return new ExtendResult(true, actuallyAdded, newRemaining, capped);
    }

    // ---------- tick ----------

    public void tick() {
        if (byName.isEmpty()) return;
        tickCounter++;
        int warnSeconds = Config.WARN_BEFORE_EXPIRY_SECONDS.get();
        long warnThresholdMs = warnSeconds * 1000L;

        List<Chunkloader> expired = null;
        for (Chunkloader c : byName.values()) {
            try {
                CRFPFakePlayer fp = c.fakePlayer();
                if (fp != null && server.getPlayerList().getPlayer(c.uuid()) != fp) {
                    // Something other than us took the fake player out of the player list (another
                    // mod, a partial removeAll, ...). Without this the loader would keep counting
                    // down while loading nothing. Fall back to the pending path and re-place it.
                    c.detach();
                    managedFakeNames.remove(c.name());
                    long delay = retryDelayTicks(c.placeAttempts() + 1);
                    c.recordPlaceFailure(tickCounter + delay,
                            "fake player was removed from the player list by something else", null);
                    CRFP.LOGGER.warn("Fake player for chunkloader '{}' vanished from the player list; re-placing in {}s",
                            c.name(), delay / 20);
                    continue;
                }
                if (fp == null) {
                    // Pending placement. The timer only runs while chunks are actually loaded.
                    if (tickCounter >= c.nextPlaceAttemptTick()) tryPlacePending(c);
                    continue;
                }
                c.decrementRemainingMs(TICK_INTERVAL_MS);
                if (c.remainingMs() <= 0) {
                    (expired == null ? expired = new ArrayList<>() : expired).add(c);
                    continue;
                }
                if (warnSeconds > 0 && !c.warned() && c.remainingMs() <= warnThresholdMs) {
                    notifyCreator(c);
                    c.markWarned();
                }
            } catch (Exception | LinkageError e) {
                CRFP.LOGGER.error("Error while ticking chunkloader '{}'", c.name(), e);
            }
        }
        if (expired != null) {
            for (Chunkloader c : expired) {
                CRFP.LOGGER.info("Chunkloader '{}' expired", c.name());
                byName.remove(key(c.name()));
                try {
                    history.logExpire(c);
                    notifyExpired(c);
                } catch (Exception | LinkageError e) {
                    CRFP.LOGGER.error("Error while expiring chunkloader '{}'", c.name(), e);
                } finally {
                    cleanupRemoved(c);
                }
            }
        }
        if (tickCounter % AUTOSAVE_INTERVAL_TICKS == 0 && !byName.isEmpty()) {
            save();
        }
    }

    private void tryPlacePending(Chunkloader c) {
        PlaceFailure failure = placeLoader(c);
        if (failure == null) {
            CRFP.LOGGER.info("Restored chunkloader '{}' in {} at {} ({} min left)",
                    c.name(), c.dimension(), c.pos().toShortString(), c.remainingMs() / 60_000L);
            history.logRestore(c);
            c.resetPlaceAttempts();
            return;
        }
        long delay = retryDelayTicks(c.placeAttempts() + 1);
        int attempts = c.recordPlaceFailure(tickCounter + delay, failure.reason(), failure.cause());
        // Full stack trace on the first failure and every tenth after that; a one-line cause otherwise.
        boolean trace = failure.cause() != null && (attempts == 1 || attempts % 10 == 0);
        String msg = "Could not place chunkloader '{}' in {} at {} (attempt {}, next try in {}s): {}. "
                + "Run '/crfp remove {}' to discard it.";
        if (trace) {
            CRFP.LOGGER.warn(msg, c.name(), c.dimension(), c.pos().toShortString(), attempts, delay / 20,
                    failure.reason(), c.name(), failure.cause());
        } else {
            CRFP.LOGGER.warn(msg, c.name(), c.dimension(), c.pos().toShortString(), attempts, delay / 20,
                    failure.reason(), c.name());
        }
    }

    /** 5 s, 10 s, 20 s, 40 s, then 60 s for every further attempt. */
    private static long retryDelayTicks(int attempt) {
        int shift = Math.min(Math.max(attempt - 1, 0), 4);
        return Math.min((long) PLACE_RETRY_INTERVAL_TICKS << shift, MAX_PLACE_RETRY_INTERVAL_TICKS);
    }

    private void notifyCreator(Chunkloader c) {
        ServerPlayer creator = server.getPlayerList().getPlayer(c.creatorUuid());
        if (creator == null) return;
        long secs = Math.max(0, c.remainingMs() / 1000);
        creator.displayClientMessage(
                Component.literal("Chunkloader '" + c.name() + "' expires in " + secs + "s"),
                true);
    }

    private void notifyExpired(Chunkloader c) {
        ServerPlayer creator = server.getPlayerList().getPlayer(c.creatorUuid());
        if (creator == null) return;
        String reasonSuffix = c.reason().isEmpty() ? "" : " (" + c.reason() + ")";
        Component msg = Component.literal("Chunkloader ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(c.name()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(reasonSuffix + " has expired").withStyle(ChatFormatting.GRAY));
        creator.sendSystemMessage(msg);
    }

    // ---------- placement ----------

    /** Why a placement attempt failed. {@code cause} is null when nothing was thrown. */
    private record PlaceFailure(String reason, @Nullable Throwable cause) {}

    /**
     * Spawns the fake player for {@code c} and attaches it. Returns null on success, otherwise the
     * failure; the loader itself is left untouched so the caller can decide whether to retry.
     */
    private @Nullable PlaceFailure placeLoader(Chunkloader c) {
        ServerLevel level = resolveLevel(c.dimension());
        if (level == null) {
            return new PlaceFailure("dimension '" + c.dimension() + "' does not exist on this server", null);
        }
        GameProfile profile = new GameProfile(c.uuid(), c.name());
        if (c.skinValue() != null) {
            profile.getProperties().put("textures", new Property("textures", c.skinValue(), c.skinSignature()));
        }

        CRFPFakePlayer fp;
        try {
            fp = new CRFPFakePlayer(level, profile);
        } catch (RuntimeException | LinkageError e) {
            return new PlaceFailure("fake player constructor threw " + e, e);
        }
        fp.moveTo(c.pos().getX() + 0.5, c.pos().getY(), c.pos().getZ() + 0.5, 0f, 0f);

        managedFakeNames.add(c.name()); // silence the join broadcast fired inside placeNewPlayer
        if (!fp.placeInWorld()) {
            managedFakeNames.remove(c.name());
            Throwable cause = fp.lastPlaceError();
            return new PlaceFailure("login threw " + cause, cause);
        }
        // Re-assert position post-placement in case placeNewPlayer moved us to the respawn point.
        fp.teleportTo(level,
                c.pos().getX() + 0.5, c.pos().getY(), c.pos().getZ() + 0.5,
                0f, 0f);
        c.attach(fp);
        return null;
    }

    private @Nullable ServerLevel resolveLevel(String dimension) {
        ResourceLocation loc = ResourceLocation.tryParse(dimension);
        if (loc == null) return null;
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, loc);
        return server.getLevel(key);
    }

    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** Returns the smallest unused slot in [1, MAX_SLOT], or -1 if all are taken. */
    private int nextAvailableSlot() {
        Set<Integer> used = new HashSet<>();
        for (Chunkloader c : byName.values()) {
            int s = slotOf(c.name());
            if (s > 0) used.add(s);
        }
        for (int i = 1; i <= MAX_SLOT; i++) {
            if (!used.contains(i)) return i;
        }
        return -1;
    }
}
