package com.imbet.weather;

public record DailySpcOutlook(
        long day,
        SpcRisk risk,
        ThermodynamicProfile profile,
        double tornadoProbability,
        double windProbability,
        double hailProbability,
        double supercellSpawnMultiplier,
        double qlcsSpawnMultiplier,
        double hurricaneSpawnMultiplier,
        double dustDevilSpawnMultiplier,
        double parentRotationThreshold
) {
    public static DailySpcOutlook neutral(long day) {
        return new DailySpcOutlook(day, SpcRisk.MARGINAL, ThermodynamicProfile.calm(), 0.01D, 0.03D, 0.02D, 0.65D, 0.0D, 0.0D, 1.0D, 0.92D);
    }

    public boolean allowsSupercells() {
        return risk.severity() >= SpcRisk.ENHANCED.severity();
    }

    public boolean allowsQlcs() {
        return risk.severity() >= SpcRisk.MODERATE.severity();
    }

    public boolean allowsViolentOutbreak() {
        return risk == SpcRisk.HIGH;
    }

    public boolean allowsLandspouts() {
        return risk.severity() >= SpcRisk.SLIGHT.severity() && risk.severity() <= SpcRisk.ENHANCED.severity();
    }

    public boolean favorsDustDevils() {
        return risk.severity() <= SpcRisk.SLIGHT.severity();
    }
}
