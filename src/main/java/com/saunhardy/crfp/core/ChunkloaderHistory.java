package com.saunhardy.crfp.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.saunhardy.crfp.CRFP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Append-only JSONL audit log for every chunkloader lifecycle event.
 *
 * Intended for after-the-fact questions like "who set up that loader I just found three
 * months ago, and what reason did they give?" or "how long did that witch farm actually
 * run last weekend?". One line per event, grep-friendly, never rewritten in place.
 *
 * Writes use a literal "\n" terminator so the file is portable between OSes — if the
 * world folder is ever moved between Windows and Linux, lines stay parseable.
 */
public final class ChunkloaderHistory {
    public static final String FILENAME = "crfp_history.jsonl";
    private static final Gson GSON = new Gson();

    private final Path file;

    public ChunkloaderHistory(MinecraftServer server) {
        this.file = server.getWorldPath(LevelResource.ROOT).resolve(FILENAME);
    }

    public Path file() { return file; }

    public void logCreate(Chunkloader c, long durationMs) {
        append("create", c, null, o -> o.addProperty("durationMs", durationMs));
    }

    public void logExtend(Chunkloader c, long addedMs, long newRemainingMs, @Nullable String executor) {
        append("extend", c, executor, o -> {
            o.addProperty("addedMs", addedMs);
            o.addProperty("remainingMs", newRemainingMs);
        });
    }

    public void logRemove(Chunkloader c, @Nullable String executor) {
        append("remove", c, executor, o -> o.addProperty("elapsedMs", elapsed(c)));
    }

    public void logExpire(Chunkloader c) {
        append("expire", c, null, o -> o.addProperty("elapsedMs", elapsed(c)));
    }

    /** A persisted loader was placed back into the world after a server restart. */
    public void logRestore(Chunkloader c) {
        append("restore", c, null, o -> o.addProperty("remainingMs", c.remainingMs()));
    }

    private void append(String event, Chunkloader c, @Nullable String executor, @Nullable Consumer<JsonObject> extra) {
        JsonObject o = new JsonObject();
        o.addProperty("timestamp", Instant.now().toString());
        o.addProperty("event", event);
        o.addProperty("name", c.name());
        o.addProperty("creator", c.creatorName());
        o.addProperty("creatorUuid", c.creatorUuid().toString());
        if (!c.reason().isEmpty()) o.addProperty("reason", c.reason());
        o.addProperty("dimension", c.dimension());
        JsonArray pos = new JsonArray(3);
        pos.add(c.pos().getX()); pos.add(c.pos().getY()); pos.add(c.pos().getZ());
        o.add("pos", pos);
        if (executor != null) o.addProperty("executor", executor);
        if (extra != null) extra.accept(o);

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(o) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            CRFP.LOGGER.warn("Failed to append history entry: {}", e.toString());
        }
    }

    /**
     * Returns the last {@code limit} history lines, newest last.
     *
     * Streaming ring-buffer read: keeps memory use at O(limit) and disk IO at O(file size)
     * single-pass, so this scales to history files of arbitrary length without the
     * readAllLines spike that a naive implementation hits on long-running servers.
     */
    public List<String> tail(int limit) {
        if (limit <= 0 || !Files.exists(file)) return List.of();
        try (Stream<String> stream = Files.lines(file, StandardCharsets.UTF_8)) {
            Deque<String> ring = new ArrayDeque<>(limit);
            stream.forEach(line -> {
                if (ring.size() == limit) ring.pollFirst();
                ring.addLast(line);
            });
            return new ArrayList<>(ring);
        } catch (IOException e) {
            CRFP.LOGGER.warn("Failed to read history file: {}", e.toString());
            return List.of();
        }
    }

    private static long elapsed(Chunkloader c) {
        return Math.max(0, System.currentTimeMillis() - c.createdAtEpochMs());
    }
}
