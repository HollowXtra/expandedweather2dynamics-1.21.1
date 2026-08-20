package com.imbet.weather.vortex;

import com.imbet.network.VortexSyncPayload;

import java.util.List;

public final class ClientVortexStore {
    private static List<VortexSnapshot> snapshots = List.of();

    private ClientVortexStore() {
    }

    public static void accept(VortexSyncPayload payload) {
        snapshots = payload == null || payload.snapshots() == null ? List.of() : List.copyOf(payload.snapshots());
    }

    public static List<VortexSnapshot> snapshots() {
        return snapshots;
    }

    public static VortexSnapshot byParentStormId(long stormId) {
        for (VortexSnapshot snapshot : snapshots) {
            if (snapshot.parentStormId() == stormId || snapshot.id() == stormId) {
                return snapshot;
            }
        }
        return null;
    }

    public static void sampleWind(double x, double y, double z, MutableWindSample out) {
        for (VortexSnapshot snapshot : snapshots) {
            sample(snapshot, x, y, z, out);
        }
    }

    private static void sample(VortexSnapshot snapshot, double px, double py, double pz, MutableWindSample out) {
        double dx = px - snapshot.x();
        double dz = pz - snapshot.z();
        double radius = Math.max(0.4D, Math.sqrt(dx * dx + dz * dz));
        double baseRadius = Math.max(4.0D, snapshot.damagePathWidth() * 0.5D);
        if (radius > baseRadius * 4.2D) {
            return;
        }
        double rmw = Math.max(3.0D, snapshot.radiusOfMaxWind());
        double radialX = dx / radius;
        double radialZ = dz / radius;
        double sign = snapshot.antiCyclonic() ? -1.0D : 1.0D;
        double tangential = radius <= rmw ? snapshot.maxWindMph() * (radius / rmw) : snapshot.maxWindMph() * Math.pow(rmw / radius, 0.78D);
        double inflow = snapshot.mode() == VortexMode.DUST_DEVIL ? 0.10D : snapshot.mode() == VortexMode.WATERSPOUT ? 0.24D : 0.36D;
        double falloff = Math.max(0.0D, Math.min(1.0D, 1.0D - radius / Math.max(1.0D, baseRadius * 1.8D)));
        double mphX = -radialZ * sign * tangential - radialX * tangential * inflow;
        double mphY = snapshot.maxWindMph() * 0.22D * falloff;
        double mphZ = radialX * sign * tangential - radialZ * tangential * inflow;
        double speed = Math.sqrt(mphX * mphX + mphY * mphY + mphZ * mphZ);
        out.setIfStronger(snapshot.id(), mphX, mphY, mphZ, speed, speed, radius, baseRadius, rmw);
    }
}