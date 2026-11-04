package com.imbet.volcano;

import com.imbet.network.VolcanoSyncPayload;

import java.util.List;

/**
 * The client's copy of the world's volcanoes.
 *
 * <p>Everything the volcano draws from - the plume, the crater glow, the ash haze, the camera shake - reads
 * this and nothing else, so a client that has just joined or just been pushed a fresh state renders the
 * eruption that is actually happening rather than an empty sky. The records expire if they stop arriving, for
 * the same reason the storm state does: a stale eruption drawn forever after the server stopped sending is
 * worse than no eruption.
 */
public final class ClientVolcanoStore {
    private static final long EXPIRE_MILLIS = 15_000L;

    private static List<VolcanoSyncPayload.VolcanoSnapshot> volcanoes = List.of();
    private static long expiresAtMillis;

    private ClientVolcanoStore() {
    }

    public static void accept(VolcanoSyncPayload payload) {
        volcanoes = payload == null || payload.volcanoes() == null ? List.of() : payload.volcanoes();
        expiresAtMillis = System.currentTimeMillis() + EXPIRE_MILLIS;
    }

    public static List<VolcanoSyncPayload.VolcanoSnapshot> active() {
        if (System.currentTimeMillis() > expiresAtMillis) {
            volcanoes = List.of();
        }
        return volcanoes;
    }

    /** The closest volcano to a point, or null when there is none in range. */
    public static VolcanoSyncPayload.VolcanoSnapshot nearest(double x, double z, double maxDistance) {
        VolcanoSyncPayload.VolcanoSnapshot closest = null;
        double closestSqr = maxDistance * maxDistance;
        for (VolcanoSyncPayload.VolcanoSnapshot snapshot : active()) {
            double dx = snapshot.x() + 0.5D - x;
            double dz = snapshot.z() + 0.5D - z;
            double distanceSqr = dx * dx + dz * dz;
            if (distanceSqr < closestSqr) {
                closestSqr = distanceSqr;
                closest = snapshot;
            }
        }
        return closest;
    }

    /** The phase a snapshot is in, decoded back out of the index it travels as. */
    public static VolcanoPhase phaseOf(VolcanoSyncPayload.VolcanoSnapshot snapshot) {
        return snapshot == null ? VolcanoPhase.DORMANT : VolcanoPhase.byIndex(snapshot.phase());
    }

    public static void clear() {
        volcanoes = List.of();
        expiresAtMillis = 0L;
    }
}
