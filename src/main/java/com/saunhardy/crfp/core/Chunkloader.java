package com.saunhardy.crfp.core;

import com.saunhardy.crfp.fakeplayer.CRFPFakePlayer;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A single active chunkloader. The identifying fields are immutable; runtime state
 * ({@code remainingMs}, the attached fake player, placement bookkeeping) mutates during
 * the server session.
 *
 * Name is also the fake player's username — unique per active loader.
 *
 * A loader with no attached fake player is <em>pending placement</em>: it was read from
 * disk, a placement attempt failed, or its fake player disappeared from the player list.
 * The registry keeps trying to put it into the world; its timer does not run while pending.
 */
public final class Chunkloader {
    private final UUID uuid;
    private final String name;
    private final String reason;
    private final String dimension;
    private final BlockPos pos;
    private final UUID creatorUuid;
    private final String creatorName;
    private final long createdAtEpochMs;
    private final @Nullable String skinValue;
    private final @Nullable String skinSignature;

    private long remainingMs;
    private @Nullable CRFPFakePlayer fakePlayer;
    private boolean warned;
    private long nextPlaceAttemptTick;
    private int placeAttempts;
    private @Nullable String lastPlaceFailure;
    private @Nullable Throwable lastPlaceCause;

    public Chunkloader(UUID uuid, String name, String reason, String dimension, BlockPos pos,
                       UUID creatorUuid, String creatorName, long createdAtEpochMs, long remainingMs,
                       @Nullable String skinValue, @Nullable String skinSignature) {
        this.uuid = uuid;
        this.name = name;
        this.reason = reason;
        this.dimension = dimension;
        this.pos = pos;
        this.creatorUuid = creatorUuid;
        this.creatorName = creatorName;
        this.createdAtEpochMs = createdAtEpochMs;
        this.remainingMs = remainingMs;
        this.skinValue = skinValue;
        this.skinSignature = skinSignature;
    }

    public UUID uuid() { return uuid; }
    public String name() { return name; }
    public String reason() { return reason; }
    public String dimension() { return dimension; }
    public BlockPos pos() { return pos; }
    public UUID creatorUuid() { return creatorUuid; }
    public String creatorName() { return creatorName; }
    public long createdAtEpochMs() { return createdAtEpochMs; }
    public long remainingMs() { return remainingMs; }
    public @Nullable String skinValue() { return skinValue; }
    public @Nullable String skinSignature() { return skinSignature; }
    public @Nullable CRFPFakePlayer fakePlayer() { return fakePlayer; }
    public boolean warned() { return warned; }

    /** True while a fake player is attached for this loader. */
    public boolean isPlaced() { return fakePlayer != null; }
    public long nextPlaceAttemptTick() { return nextPlaceAttemptTick; }
    public int placeAttempts() { return placeAttempts; }
    /** Human-readable reason for the most recent placement failure, or null if none. */
    public @Nullable String lastPlaceFailure() { return lastPlaceFailure; }
    public @Nullable Throwable lastPlaceCause() { return lastPlaceCause; }

    public void setRemainingMs(long ms) { this.remainingMs = ms; }
    public void decrementRemainingMs(long by) { this.remainingMs -= by; }
    public void attach(CRFPFakePlayer fp) { this.fakePlayer = fp; }
    public void detach() { this.fakePlayer = null; }
    public void markWarned() { this.warned = true; }

    /** Records a failed placement and schedules the next try. Returns the failure count so far. */
    public int recordPlaceFailure(long nextAttemptTick, String reason, @Nullable Throwable cause) {
        this.nextPlaceAttemptTick = nextAttemptTick;
        this.lastPlaceFailure = reason;
        this.lastPlaceCause = cause;
        return ++placeAttempts;
    }

    public void resetPlaceAttempts() {
        this.placeAttempts = 0;
        this.nextPlaceAttemptTick = 0;
        this.lastPlaceFailure = null;
        this.lastPlaceCause = null;
    }
}
