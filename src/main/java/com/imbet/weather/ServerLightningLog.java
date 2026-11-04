package com.imbet.weather;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The last few seconds of lightning, kept server-side as places rather than as entities.
 *
 * <p>A bolt is not something a server can be asked about. It is an entity that lives two ticks and is gone, so
 * "was there lightning in that photograph" asked of the entity list has a window of about a tenth of a second
 * - far shorter than anybody can time a shutter, which would make the bonus a matter of luck instead of
 * timing. What a camera actually has is a slightly slow shutter, so the question worth asking is the one this
 * class answers: was a bolt in frame within the last half second, and if so, where.
 *
 * <p>Per dimension, because a level's game time is its own: a strike in the Overworld says nothing about the
 * Nether, and one clock cannot date both. Each dimension keeps a bounded ring of strikes, so a storm producing
 * a bolt a tick cannot grow this without limit, and entries older than the window are dropped as they are read
 * rather than by a sweep of their own.
 */
public final class ServerLightningLog {
    /** How many strikes per dimension are remembered before the oldest is dropped. */
    public static final int MAX_STRIKES = 64;

    private static final Map<ResourceKey<Level>, Deque<Strike>> STRIKES = new HashMap<>();

    private ServerLightningLog() {
    }

    /** One bolt: where it struck and the tick it was seen. */
    public record Strike(Vec3 position, long tick) {
        /** Whether this strike is inside the window ending at {@code nowTick}. */
        public boolean within(long nowTick, long windowTicks) {
            long age = nowTick - tick;
            return age >= 0L && age <= Math.max(0L, windowTicks);
        }
    }

    /** Remembers a strike. Called from the entity-join hook, which is the first tick a bolt exists. */
    public static void record(ServerLevel level, double x, double y, double z) {
        if (level == null) {
            return;
        }
        Deque<Strike> strikes = STRIKES.computeIfAbsent(level.dimension(), key -> new ArrayDeque<>());
        synchronized (strikes) {
            strikes.addLast(new Strike(new Vec3(x, y, z), level.getGameTime()));
            while (strikes.size() > MAX_STRIKES) {
                strikes.removeFirst();
            }
        }
    }

    /**
     * The strikes in a level within the window, newest last. A level that has never been struck answers with
     * an empty list rather than null, so a camera in a quiet world has nothing to check and no special case.
     */
    public static List<Strike> recent(ServerLevel level, long windowTicks) {
        if (level == null) {
            return List.of();
        }
        Deque<Strike> strikes = STRIKES.get(level.dimension());
        if (strikes == null) {
            return List.of();
        }
        long now = level.getGameTime();
        long window = Math.max(0L, windowTicks);
        List<Strike> recent = new ArrayList<>();
        synchronized (strikes) {
            while (!strikes.isEmpty() && now - strikes.peekFirst().tick() > window) {
                strikes.removeFirst();
            }
            for (Strike strike : strikes) {
                if (strike.within(now, window)) {
                    recent.add(strike);
                }
            }
        }
        return recent;
    }

    /** Forgets everything, which a level that unloads its strikes has no need of but a harness does. */
    public static void clear() {
        STRIKES.clear();
    }
}
