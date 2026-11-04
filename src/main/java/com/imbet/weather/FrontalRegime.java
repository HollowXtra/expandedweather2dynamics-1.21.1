package com.imbet.weather;

/**
 * Where a place sits in a synoptic low's air masses, and what that does to the day's hazards.
 *
 * <p>A low is not a storm with fronts drawn on it: the fronts <em>are</em> the low's weather, and which
 * one is over you decides what kind of severe weather the day can produce. The same CAPE and shear
 * forecast a different day depending on whether they are sitting in the warm sector, being undercut by
 * the cold front, or strung out along the warm front on the cool side - so the outlook has to know
 * which of those it is forecasting, not just how unstable the air is.
 *
 * <p>What each regime does here, and why:
 *
 * <ul>
 *   <li><b>Warm front</b> - the front slopes gently over the cool air ahead of it, so the convection on
 *   it is <em>elevated</em>: high-based cells rooted above the frontal inversion, with hail and, in the
 *   high-shear low-CAPE air just on the cool side, some of the smallest and deadliest tornadoes there
 *   are. Tornadoes and hail are both lifted; straight-line wind is not, because elevated storms do not
 *   put a downdraft on the ground. Linear organisation is suppressed outright.
 *   <li><b>Warm sector</b> - the buoyant, strongly sheared air between the fronts, which is where
 *   discrete supercells and every hazard at once come from. Supercells are favoured over lines.
 *   <li><b>Cold front</b> - forced ascent along a sharp density boundary, which squalls a line rather
 *   than a scatter of cells. Linear wind is the hazard (derecho class), with QLCS spin-ups and large
 *   hail from the stronger embedded cells. Tornado probability is not cut, because a line does produce
 *   tornadoes - but the cells are forced, so discrete supercells are suppressed and violent tornadoes
 *   are rare, which is a shape of convection rather than a number.
 *   <li><b>Cool sector</b> - ahead of the warm front, under stable cool air. Low-topped and mostly
 *   harmless; the whole day is damped.
 *   <li><b>Post-frontal</b> - behind the cold front, in the cold advection. This is where convection
 *   stops: everything is damped hard, and the significant-severe conditional intensity is knocked back
 *   a level because a stable air mass does not produce significant severe weather whatever the morning
 *   numbers said.
 *   <li><b>No front</b> - no low within reach, so nothing here applies and the outlook is the
 *   environment's own.
 * </ul>
 *
 * <p>The factors are multipliers on the environment's own hazard probabilities and spawn chances, and
 * the conditional intensity biases are in CIG levels. Both are scaled by how strongly the low is
 * actually influencing the place being forecast (see {@link FrontalEnvironment}), so a front only
 * matters where it is.
 *
 * <p>The CIG biases deliberately cannot reach the extension columns: a front can sharpen a hazard the
 * environment already supports by one published level, and can never invent a significant-severe
 * threat on its own. Extreme and hyper extreme stay earned by the environment at issuance, which is
 * what kept hunting the high-risk rung from being swallowed by them in the first place.
 */
public enum FrontalRegime {
    WARM_FRONT("Warm front", "tornadoes and elevated hail storms", false,
            1.55D, 0.78D, 1.40D, 1, 0, 1, 1.05D, 0.35D, 1.35D),
    WARM_SECTOR("Warm sector", "discrete supercells, every hazard at once", false,
            1.20D, 1.05D, 1.15D, 0, 0, 0, 1.35D, 0.70D, 1.20D),
    COLD_FRONT("Cold front", "linear damaging wind, QLCS tornadoes, large hail", true,
            0.95D, 1.45D, 1.15D, 0, 1, 0, 0.55D, 2.10D, 0.85D),
    COOL_SECTOR("Cool sector", "low-topped showers, little organised severe", false,
            0.72D, 0.82D, 0.70D, 0, 0, 0, 0.60D, 0.50D, 0.75D),
    POST_FRONTAL("Post-frontal", "cold advection behind the cold front, convection shutting down", false,
            0.40D, 0.55D, 0.45D, -1, -1, -1, 0.40D, 0.30D, 0.45D),
    NONE("No front", "the ambient air mass", false,
            1.0D, 1.0D, 1.0D, 0, 0, 0, 1.0D, 1.0D, 1.0D);

    private final String label;
    private final String hazardSummary;
    private final boolean linearForced;
    private final double tornadoFactor;
    private final double windFactor;
    private final double hailFactor;
    private final int tornadoCigBias;
    private final int windCigBias;
    private final int hailCigBias;
    private final double supercellFactor;
    private final double qlcsFactor;
    private final double tornadoSpawnFactor;

    FrontalRegime(String label, String hazardSummary, boolean linearForced,
                  double tornadoFactor, double windFactor, double hailFactor,
                  int tornadoCigBias, int windCigBias, int hailCigBias,
                  double supercellFactor, double qlcsFactor, double tornadoSpawnFactor) {
        this.label = label;
        this.hazardSummary = hazardSummary;
        this.linearForced = linearForced;
        this.tornadoFactor = tornadoFactor;
        this.windFactor = windFactor;
        this.hailFactor = hailFactor;
        this.tornadoCigBias = tornadoCigBias;
        this.windCigBias = windCigBias;
        this.hailCigBias = hailCigBias;
        this.supercellFactor = supercellFactor;
        this.qlcsFactor = qlcsFactor;
        this.tornadoSpawnFactor = tornadoSpawnFactor;
    }

    public String label() {
        return label;
    }

    /** One short clause, for readouts and the issuance log. */
    public String hazardSummary() {
        return hazardSummary;
    }

    public double tornadoFactor() {
        return tornadoFactor;
    }

    public double windFactor() {
        return windFactor;
    }

    public double hailFactor() {
        return hailFactor;
    }

    public double cigBias(SpcHazard hazard) {
        return switch (hazard) {
            case TORNADO -> tornadoCigBias;
            case WIND -> windCigBias;
            case HAIL -> hailCigBias;
        };
    }

    public double supercellFactor() {
        return supercellFactor;
    }

    public double qlcsFactor() {
        return qlcsFactor;
    }

    public double tornadoSpawnFactor() {
        return tornadoSpawnFactor;
    }

    /**
     * True for a front whose organisation is forced rather than buoyant. A forced line still produces
     * tornadoes, but they are the short-lived QLCS kind, so the tornado intensity roll is capped back
     * instead of being drawn from the same distribution as a warm-sector supercell's.
     */
    public boolean linearForced() {
        return linearForced;
    }
}
