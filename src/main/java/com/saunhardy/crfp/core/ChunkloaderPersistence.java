package com.saunhardy.crfp.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.saunhardy.crfp.CRFP;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JSON persistence for active chunkloaders, stored next to level.dat.
 *
 * Timers are paused across server restarts: only {@code remainingMs} is saved,
 * never absolute expiry. On load, decrementing resumes from whatever was last written.
 */
public final class ChunkloaderPersistence {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILENAME = "crfp_loaders.json";

    private ChunkloaderPersistence() {}

    public static Path resolveFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(FILENAME);
    }

    public static List<Chunkloader> load(MinecraftServer server) {
        Path file = resolveFile(server);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray arr = root.has("loaders") ? root.getAsJsonArray("loaders") : new JsonArray();

            List<Chunkloader> out = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                try {
                    out.add(parse(arr.get(i).getAsJsonObject()));
                } catch (Exception e) {
                    CRFP.LOGGER.warn("Skipping corrupt chunkloader entry at index {}: {}", i, e.toString());
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            CRFP.LOGGER.error("Failed to read {}: {}", file, e.toString());
            return List.of();
        }
    }

    public static void save(MinecraftServer server, List<Chunkloader> loaders) {
        Path file = resolveFile(server);
        Path tmp = file.resolveSibling(FILENAME + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            JsonArray arr = new JsonArray();
            for (Chunkloader c : loaders) {
                arr.add(serialize(c));
            }
            JsonObject root = new JsonObject();
            root.add("loaders", arr);
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                // Some filesystems (e.g. across FS boundaries) don't support ATOMIC_MOVE.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            CRFP.LOGGER.error("Failed to write {}: {}", file, e.toString());
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    private static JsonObject serialize(Chunkloader c) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", c.uuid().toString());
        o.addProperty("name", c.name());
        o.addProperty("reason", c.reason());
        o.addProperty("dimension", c.dimension());
        JsonArray pos = new JsonArray(3);
        pos.add(c.pos().getX());
        pos.add(c.pos().getY());
        pos.add(c.pos().getZ());
        o.add("pos", pos);
        o.addProperty("creatorUuid", c.creatorUuid().toString());
        o.addProperty("creatorName", c.creatorName());
        o.addProperty("createdAtEpochMs", c.createdAtEpochMs());
        o.addProperty("remainingMs", c.remainingMs());
        if (c.skinValue() != null) {
            o.addProperty("skinValue", c.skinValue());
            if (c.skinSignature() != null) {
                o.addProperty("skinSignature", c.skinSignature());
            }
        }
        return o;
    }

    private static Chunkloader parse(JsonObject o) {
        UUID uuid = UUID.fromString(o.get("uuid").getAsString());
        String name = o.get("name").getAsString();
        String reason = o.has("reason") ? o.get("reason").getAsString() : "";
        String dimension = o.get("dimension").getAsString();
        JsonArray p = o.getAsJsonArray("pos");
        BlockPos pos = new BlockPos(p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt());
        UUID creatorUuid = UUID.fromString(o.get("creatorUuid").getAsString());
        String creatorName = o.get("creatorName").getAsString();
        long createdAt = o.get("createdAtEpochMs").getAsLong();
        long remaining = o.get("remainingMs").getAsLong();
        String skinValue = o.has("skinValue") ? o.get("skinValue").getAsString() : null;
        String skinSignature = o.has("skinSignature") ? o.get("skinSignature").getAsString() : null;
        return new Chunkloader(uuid, name, reason, dimension, pos, creatorUuid, creatorName,
                createdAt, remaining, skinValue, skinSignature);
    }
}
