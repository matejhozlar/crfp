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
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private final MinecraftServer server;
    private final ConcurrentHashMap<String, Chunkloader> byName = new ConcurrentHashMap<>();
    private final Set<String> managedFakeNames = ConcurrentHashMap.newKeySet();

    public ChunkloaderRegistry(MinecraftServer server) {
        this.server = server;
    }

    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
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

    public AddResult add(String name, long minutes, String reason, ServerPlayer creator) {
        if (!isValidName(name)) {
            return AddResult.fail("Name must be 3-16 chars, [A-Za-z0-9_] only");
        }
        int maxMin = Config.MAX_DURATION_MINUTES.get();
        if (minutes < 1 || minutes > maxMin) {
            return AddResult.fail("Duration must be between 1 and " + maxMin + " minutes");
        }
        if (byName.containsKey(key(name))) {
            return AddResult.fail("A chunkloader named '" + name + "' already exists");
        }

        ServerLevel level = creator.serverLevel();
        BlockPos pos = creator.blockPosition();
        String dim = level.dimension().location().toString();

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
        return AddResult.ok(c);
    }

    public boolean remove(String name) {
        Chunkloader c = byName.remove(key(name));
        if (c == null) return false;
        managedFakeNames.remove(c.name());
        CRFPFakePlayer fp = c.fakePlayer();
        if (fp != null) fp.removeFromWorld();
        c.detach();
        saveQuietly();
        return true;
    }

    public boolean extend(String name, long addMinutes) {
        Chunkloader c = byName.get(key(name));
        if (c == null) return false;
        long maxMs = Config.MAX_DURATION_MINUTES.get() * 60_000L;
        long newRemaining = Math.min(c.remainingMs() + addMinutes * 60_000L, maxMs);
        c.setRemainingMs(newRemaining);
        saveQuietly();
        return true;
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
                remove(c.name());
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
}
