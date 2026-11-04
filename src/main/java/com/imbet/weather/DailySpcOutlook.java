package com.imbet.weather;

/**
 * One issuance of the fictional daily SPC-style Day 1 outlook.
 *
 * <p>It carries two layers: the continuous internal drivers (probabilities and spawn multipliers that
 * gate how storms actually spawn) and the issued product itself - the probability levels and
 * conditional intensity groups that a player reads on the panel, exactly as SPC issues them. The
 * product layer is chosen so that the SPC decision matrix converts it back to {@link #risk()}, so the
 * numbers on the pane and the category beside them can never disagree.
 */
public record DailySpcOutlook(
        long day,
        SpcRisk risk,
        ThermodynamicProfile profile,
        FrontalRegime frontalRegime,
        double frontalProximity,
        double tornadoProbability,
        double windProbability,
        double hailProbability,
        double supercellSpawnMultiplier,
        double qlcsSpawnMultiplier,
        double hurricaneSpawnMultiplier,
        double dustDevilSpawnMultiplier,
        double parentRotationThreshold,
        String issuance,
        long issuedTick,
        int tornadoPercent,
        int windPercent,
        int hailPercent,
        int tornadoCig,
        int windCig,
        int hailCig
) {
    public DailySpcOutlook {
        issuance = issuance == null || issuance.isBlank() ? "D1 0600Z" : issuance;
        frontalRegime = frontalRegime == null ? FrontalRegime.NONE : frontalRegime;
        frontalProximity = Math.max(0.0D, Math.min(1.0D, frontalProximity));
        tornadoPercent = Math.max(0, tornadoPercent);
        windPercent = Math.max(0, windPercent);
        hailPercent = Math.max(0, hailPercent);
        tornadoCig = Math.max(0, tornadoCig);
        windCig = Math.max(0, windCig);
        hailCig = Math.max(0, hailCig);
    }

    public static DailySpcOutlook neutral(long day) {
        return new DailySpcOutlook(day, SpcRisk.MARGINAL, ThermodynamicProfile.calm(),
                FrontalRegime.NONE, 0.0D,
                0.01D, 0.03D, 0.02D, 0.65D, 0.0D, 0.0D, 1.0D, 0.92D,
                "D1 0600Z", 0L, 2, 5, 5, 0, 0, 0);
    }

    /** The air mass the day is being forecast in, one short line for a readout. */
    public String frontalLabel() {
        if (frontalRegime == FrontalRegime.NONE || frontalProximity <= 0.01D) {
            return "No front in range";
        }
        return frontalRegime.label() + ", " + Math.round(frontalProximity * 100.0D) + "% influence";
    }

    /** A regime factor on a spawn chance, faded in from 1 by how strongly the front governs here. */
    private double frontScaled(double factor) {
        return 1.0D + (factor - 1.0D) * frontalProximity;
    }

    public double frontalSupercellFactor() {
        return frontScaled(frontalRegime.supercellFactor());
    }

    public double frontalQlcsFactor() {
        return frontScaled(frontalRegime.qlcsFactor());
    }

    public double frontalTornadoSpawnFactor() {
        return frontScaled(frontalRegime.tornadoSpawnFactor());
    }

    /**
     * True when a cold front is close enough to be forcing the convection into a line. A forced line
     * still spins up tornadoes, but they are the short-lived QLCS kind rather than the discrete
     * supercell kind, so this is what caps the intensity roll back.
     */
    public boolean linearForcedConvection() {
        return frontalRegime.linearForced() && frontalProximity >= 0.25D;
    }

    /** The issued probability level for a hazard, as it appears on the product. */
    public int percent(SpcHazard hazard) {
        return switch (hazard) {
            case TORNADO -> tornadoPercent;
            case WIND -> windPercent;
            case HAIL -> hailPercent;
        };
    }

    /** The issued conditional intensity group for a hazard. */
    public int cig(SpcHazard hazard) {
        return switch (hazard) {
            case TORNADO -> tornadoCig;
            case WIND -> windCig;
            case HAIL -> hailCig;
        };
    }

    /** The category this hazard's issued probability and intensity convert to. */
    public SpcRisk hazardRisk(SpcHazard hazard) {
        return SpcRisk.fromHazard(hazard, percent(hazard), cig(hazard));
    }

    /** The categorical risk implied by the issued probabilities; equals {@link #risk()} by design. */
    public SpcRisk categoricalFromProduct() {
        SpcRisk result = SpcRisk.NONE;
        for (SpcHazard hazard : SpcHazard.values()) {
            result = SpcRisk.max(result, hazardRisk(hazard));
        }
        return result;
    }

    public String productLine() {
        return SpcHazard.TORNADO.productLine(tornadoPercent, tornadoCig)
                + " / " + SpcHazard.WIND.productLine(windPercent, windCig)
                + " / " + SpcHazard.HAIL.productLine(hailPercent, hailCig);
    }

    public boolean allowsSupercells() {
        return risk.severity() >= SpcRisk.ENHANCED.severity();
    }

    public boolean allowsQlcs() {
        return risk.severity() >= SpcRisk.MODERATE.severity();
    }

    /** True from high risk upward, so the extension tiers unlock violent tornadoes as well. */
    public boolean allowsViolentOutbreak() {
        return risk.severity() >= SpcRisk.HIGH.severity();
    }

    /** True only on the addon's extension tiers, past the published SPC ladder. */
    public boolean isExtremeOutbreak() {
        return risk.isExtended();
    }

    public boolean allowsLandspouts() {
        return risk.severity() >= SpcRisk.SLIGHT.severity() && risk.severity() <= SpcRisk.ENHANCED.severity();
    }

    public boolean favorsDustDevils() {
        return risk.severity() <= SpcRisk.SLIGHT.severity();
    }
}
