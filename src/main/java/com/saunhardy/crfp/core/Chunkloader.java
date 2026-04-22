package com.saunhardy.crfp.core;

import com.saunhardy.crfp.fakeplayer.CRFPFakePlayer;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A single active chunkloader. The identifying fields are immutable; runtime state
 * ({@code remainingMs}, the attached fake player) mutates during the server session.
 *
 * Name is also the fake player's username — unique per active loader.
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

    private long remainingMs;
    private @Nullable CRFPFakePlayer fakePlayer;
    private boolean warned;

    public Chunkloader(UUID uuid, String name, String reason, String dimension, BlockPos pos,
                       UUID creatorUuid, String creatorName, long createdAtEpochMs, long remainingMs) {
        this.uuid = uuid;
        this.name = name;
        this.reason = reason;
        this.dimension = dimension;
        this.pos = pos;
        this.creatorUuid = creatorUuid;
        this.creatorName = creatorName;
        this.createdAtEpochMs = createdAtEpochMs;
        this.remainingMs = remainingMs;
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
    public @Nullable CRFPFakePlayer fakePlayer() { return fakePlayer; }
    public boolean warned() { return warned; }

    public void setRemainingMs(long ms) { this.remainingMs = ms; }
    public void decrementRemainingMs(long by) { this.remainingMs -= by; }
    public void attach(CRFPFakePlayer fp) { this.fakePlayer = fp; }
    public void detach() { this.fakePlayer = null; }
    public void markWarned() { this.warned = true; }
}
