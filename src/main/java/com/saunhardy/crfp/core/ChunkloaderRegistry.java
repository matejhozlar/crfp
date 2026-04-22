package com.saunhardy.crfp.core;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.saunhardy.crfp.CRFP;
import com.saunhardy.crfp.Config;
import com.saunhardy.crfp.fakeplayer.CRFPFakePlayer;
import net.minecraft.core.BlockPos;
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
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Pattern SLOT_PATTERN = Pattern.compile("^" + Pattern.quote(NAME_PREFIX) + "(\\d+)$");

    private final MinecraftServer server;
    private final ChunkloaderHistory history;
    private final ConcurrentHashMap<String, Chunkloader> byName = new ConcurrentHashMap<>();
    private final Set<String> managedFakeNames = ConcurrentHashMap.newKeySet();

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

    public void load() {
        List<Chunkloader> loaded = ChunkloaderPersistence.load(server);
        for (Chunkloader c : loaded) {
            if (c.remainingMs() <= 0) continue;
            if (byName.containsKey(key(c.name()))) {
                CRFP.LOGGER.warn("Duplicate loader name '{}' in persisted data; skipping", c.name());
                continue;
            }
            if (!placeLoader(c)) {
                CRFP.LOGGER.warn("Could not restore chunkloader {}; dropping", c.name());
                continue;
            }
            byName.put(key(c.name()), c);
            managedFakeNames.add(c.name());
        }
        CRFP.LOGGER.info("Restored {} chunkloader(s)", byName.size());
    }

    public void saveAndShutdown() {
        List<Chunkloader> snapshot = new ArrayList<>(byName.values());
        ChunkloaderPersistence.save(server, snapshot);
        for (Chunkloader c : snapshot) {
            CRFPFakePlayer fp = c.fakePlayer();
            if (fp != null) fp.removeFromWorld();
            c.detach();
        }
        byName.clear();
        managedFakeNames.clear();
    }

    private void saveQuietly() {
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

        if (!placeLoader(c)) {
            return AddResult.fail("Failed to spawn fake player");
        }
        byName.put(key(name), c);
        managedFakeNames.add(name);
        saveQuietly();
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

    /** Internal cleanup for a Chunkloader already pulled from byName. */
    private void cleanupRemoved(Chunkloader c) {
        try {
            CRFPFakePlayer fp = c.fakePlayer();
            // removeFromWorld triggers the vanilla "left the game" broadcast — keep the
            // name in managedFakeNames until after it fires so the mixin can suppress it.
            if (fp != null) fp.removeFromWorld();
            c.detach();
        } finally {
            managedFakeNames.remove(c.name());
            saveQuietly();
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
        saveQuietly();
        history.logExtend(c, actuallyAdded, newRemaining, executor);
        return new ExtendResult(true, actuallyAdded, newRemaining, capped);
    }

    // ---------- tick ----------

    public void tick() {
        if (byName.isEmpty()) return;
        int warnSeconds = Config.WARN_BEFORE_EXPIRY_SECONDS.get();
        long warnThresholdMs = warnSeconds * 1000L;

        List<Chunkloader> expired = null;
        for (Chunkloader c : byName.values()) {
            c.decrementRemainingMs(TICK_INTERVAL_MS);
            if (c.remainingMs() <= 0) {
                (expired == null ? expired = new ArrayList<>() : expired).add(c);
                continue;
            }
            if (warnSeconds > 0 && !c.warned() && c.remainingMs() <= warnThresholdMs) {
                notifyCreator(c);
                c.markWarned();
            }
        }
        if (expired != null) {
            for (Chunkloader c : expired) {
                CRFP.LOGGER.info("Chunkloader '{}' expired", c.name());
                history.logExpire(c);
                notifyExpired(c);
                byName.remove(key(c.name()));
                cleanupRemoved(c);
            }
        }
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
        Component msg = Component.literal("Chunkloader ").withStyle(net.minecraft.ChatFormatting.GRAY)
                .append(Component.literal(c.name()).withStyle(net.minecraft.ChatFormatting.AQUA))
                .append(Component.literal(reasonSuffix + " has expired").withStyle(net.minecraft.ChatFormatting.GRAY));
        creator.sendSystemMessage(msg);
    }

    // ---------- placement ----------

    private boolean placeLoader(Chunkloader c) {
        ServerLevel level = resolveLevel(c.dimension());
        if (level == null) {
            CRFP.LOGGER.error("Unknown dimension '{}' for loader {}", c.dimension(), c.name());
            return false;
        }
        GameProfile profile = new GameProfile(c.uuid(), c.name());
        if (c.skinValue() != null) {
            profile.getProperties().put("textures", new Property("textures", c.skinValue(), c.skinSignature()));
        }
        CRFPFakePlayer fp = new CRFPFakePlayer(level, profile);
        fp.moveTo(c.pos().getX() + 0.5, c.pos().getY(), c.pos().getZ() + 0.5, 0f, 0f);

        managedFakeNames.add(c.name()); // suppress join broadcast
        fp.placeInWorld();
        if (!fp.isPlaced()) {
            managedFakeNames.remove(c.name());
            return false;
        }
        // Re-assert position post-placement in case placeNewPlayer moved us to the respawn point.
        fp.teleportTo(level,
                c.pos().getX() + 0.5, c.pos().getY(), c.pos().getZ() + 0.5,
                0f, 0f);
        c.attach(fp);
        return true;
    }

    private @Nullable ServerLevel resolveLevel(String dimension) {
        ResourceLocation loc = ResourceLocation.tryParse(dimension);
        if (loc == null) return null;
        ResourceKey<Level> key = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, loc);
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
        Set<Integer> used = new java.util.HashSet<>();
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
