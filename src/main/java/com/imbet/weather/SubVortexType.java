package com.imbet.weather;

/**
 * The kinds of sub-vortices a multi-vortex tornado can throw at once.
 *
 * <p>Real multi-vortex tornadoes are not just "several of the same thing". Chaser and radar
 * studies separate them into distinct classes with their own size, lifetime, intensity and
 * effect on the damage path:</p>
 *
 * <ul>
 *   <li>{@link #SUCTION} - the classic suction vortex: tight, fast, intense, orbiting inside the
 *       damage path and cutting the cycloidal scour marks that define a multi-vortex tornado.</li>
 *   <li>{@link #CORE} - an inner-core suction spot: tiny and violently fast, hugging the very
 *       centre of the parent circulation, very short lived. These are the little vortices you can
 *       see inside the condensation funnel of a violent tornado.</li>
 *   <li>{@link #PERIPHERAL} - an outer-circulation vortex: larger, slower and weaker, orbiting out
 *       near the edge of the damage path. Common on wide wedge tornadoes, where they read as
 *       smaller funnels trailing around the main one.</li>
 *   <li>{@link #BREAKDOWN} - a vortex breakdown bubble: it does not orbit at all. It forms high in
 *       the funnel and travels <em>down</em> it, pinching the condensation funnel as it goes and
 *       briefly choking the parent - the mechanism behind a tornado's temporary weakening.</li>
 *   <li>{@link #SATELLITE_FUNNEL} - a hanging satellite funnel: a detached secondary condensation
 *       funnel that dangles from the cloud base beside the parent. It stays <em>aloft</em>, so it
 *       is plainly visible but never scours or damages the ground.</li>
 *   <li>{@link #DUST_WHIRL} - a dust whirl: a tight ground swirl that lifts debris but never
 *       condenses, so it is visible only through its dust. These are the filler vortices of a
 *       weak or roping multi-vortex episode.</li>
 * </ul>
 */
public enum SubVortexType {
    SUCTION("Suction Vortex", 1.0F, 1.0F, 1.0F, true, true, true, false,
            new Rails(0.30F, 0.76F, 4.2F, 10.0F, 90, 280, 0.30F, 0.64F, 0.26F, 0.54F), 0.0F, true, 1.00F),
    CORE("Inner-Core Vortex", 1.14F, 0.72F, 0.55F, true, true, true, false,
            new Rails(0.10F, 0.26F, 8.0F, 14.0F, 50, 150, 0.14F, 0.28F, 0.42F, 0.74F), 0.0F, true, 1.55F),
    PERIPHERAL("Peripheral Vortex", 0.72F, 1.35F, 1.25F, true, true, true, false,
            new Rails(0.70F, 1.06F, 2.4F, 5.0F, 200, 420, 0.42F, 0.78F, 0.18F, 0.40F), 0.0F, true, 0.55F),
    SATELLITE_FUNNEL("Satellite Funnel", 0.86F, 0.78F, 0.90F, true, false, false, false,
            new Rails(0.62F, 1.05F, 2.0F, 4.2F, 200, 460, 0.62F, 1.05F, 0.40F, 0.78F), 0.30F, true, 0.50F),
    DUST_WHIRL("Dust Whirl", 0.55F, 0.85F, 0.60F, true, true, true, false,
            new Rails(0.28F, 0.86F, 5.0F, 11.0F, 60, 190, 0.22F, 0.46F, 0.22F, 0.55F), 0.0F, false, 1.35F),
    BREAKDOWN("Vortex Breakdown", 0.55F, 0.55F, 1.60F, false, false, true, true,
            new Rails(0.60F, 1.05F, 0.0F, 0.0F, 120, 260, 0.50F, 0.92F, 0.35F, 0.62F), 0.0F, true, 0.0F);

    /** Parameter bands a vortex of this type is spawned within. */
    public record Rails(
            float minOrbitFraction,
            float maxOrbitFraction,
            float minOrbitSpeed,
            float maxOrbitSpeed,
            int minLife,
            int maxLife,
            float minSize,
            float maxSize,
            float minIntensity,
            float maxIntensity
    ) {
    }

    private final String label;
    private final float windScale;
    private final float damageScale;
    private final float coreScale;
    private final boolean orbits;
    private final boolean marksGround;
    private final boolean damagesGround;
    private final boolean descends;
    private final Rails rails;
    /** Lowest height fraction the vortex reaches; anything above 0 hangs aloft and never touches down. */
    private final float minHeightFraction;
    /** Whether the vortex shows a condensation funnel at all - a dust whirl is visible only as debris. */
    private final boolean visibleAsFunnel;
    /** Orbit rate relative to the episode's shared rate, following the real falloff with radius. */
    private final float orbitSpeedFactor;

    SubVortexType(String label, float windScale, float damageScale, float coreScale,
                  boolean orbits, boolean marksGround, boolean damagesGround, boolean descends, Rails rails,
                  float minHeightFraction, boolean visibleAsFunnel, float orbitSpeedFactor) {
        this.label = label;
        this.windScale = windScale;
        this.damageScale = damageScale;
        this.coreScale = coreScale;
        this.orbits = orbits;
        this.marksGround = marksGround;
        this.damagesGround = damagesGround;
        this.descends = descends;
        this.rails = rails;
        this.minHeightFraction = minHeightFraction;
        this.visibleAsFunnel = visibleAsFunnel;
        this.orbitSpeedFactor = orbitSpeedFactor;
    }

    /**
     * Lowest height this vortex reaches, as a fraction of the funnel (0 = ground, 1 = cloud base).
     * A hanging satellite funnel sits at 0.30 and so never contacts the ground; every other type
     * starts at the ground.
     */
    public float minHeightFraction() {
        return minHeightFraction;
    }

    /** Dust whirls never condense, so they are rendered through their debris alone. */
    public boolean visibleAsFunnel() {
        return visibleAsFunnel;
    }

    /**
     * Orbit rate relative to the episode's shared rate. Real vortices orbiting further from the
     * radius of maximum wind circle slower, so an inner-core spot whips around while a peripheral
     * or hanging vortex drifts.
     */
    public float orbitSpeedFactor() {
        return orbitSpeedFactor;
    }

    public String label() {
        return label;
    }

    public Rails rails() {
        return rails;
    }

    /** Scales the wind this vortex adds to the field - peripheral vortices are weaker, cores stronger. */
    public float windScale() {
        return windScale;
    }

    /** Scales how wide a swath this vortex damages. */
    public float damageScale() {
        return damageScale;
    }

    /** Scales the vortex's tight core radius in the wind field. */
    public float coreScale() {
        return coreScale;
    }

    /** Breakdown bubbles hang in the funnel instead of circling the parent. */
    public boolean orbits() {
        return orbits;
    }

    /** Whether this type cuts cycloidal scour marks into the ground. */
    public boolean marksGround() {
        return marksGround;
    }

    /** Whether this type damages terrain and entities at all (breakdowns do only when low). */
    public boolean damagesGround() {
        return damagesGround;
    }

    /** Breakdown bubbles travel down the funnel over their life. */
    public boolean descends() {
        return descends;
    }

    public static SubVortexType byName(String name) {
        for (SubVortexType type : values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return SUCTION;
    }
}
