package com.imbet.weather;

/**
 * What kind of convection a synoptic low's front is making, and what that means for the cell it seeds.
 *
 * <p>Which front a cell is born on decides what the cell can become, and it decides it in the real
 * world for reasons this models directly:
 *
 * <ul>
 *   <li>A <b>cold front</b> forces air up along a sharp density boundary. The ascent is not buoyant and
 *   not selective, so the cells are forced, capped, and get organised into a squall line by the addon's
 *   own line detection once enough of them are in the air. They still spin up tornadoes, but the
 *   short-lived QLCS kind.
 *   <li>The <b>warm sector</b> between the fronts is the buoyant, strongly sheared air, where discrete
 *   supercells live. These are the only cells this system seeds that are left their whole ladder, and
 *   therefore the only ones that can reach violent strength.
 *   <li>A <b>warm front</b> slopes gently over the cool air ahead of it, so its convection is elevated:
 *   high-based cells rooted above the frontal inversion, hail-bearing, capped well below a supercell -
 *   and, in the high-shear low-CAPE air just on the cool side, the small short-lived tornadoes that make
 *   warm fronts dangerous out of proportion to how impressive they look.
 * </ul>
 *
 * <p>The cap is the whole point. A warm-sector cell is what an outbreak is made of, so a cell seeded
 * anywhere else must not quietly become one: capping a forced or elevated cell is what keeps the day's
 * extremes earned by the extreme days rather than by every front on the map.
 *
 * <p>The rungs here are this mod's own words for the stages, not Weather2's numbers, so the kind of a
 * cell can be reasoned about and checked outside the game. The seeder is what translates a rung into
 * the storm's own ladder.
 */
public enum FrontalCellKind {
    /** Forced ascent along a sharp boundary: a line's own cells, capped at a thunderstorm. */
    COLD_FRONT_LINE("cold front", Rung.THUNDERSTORM, false, 0.58F),
    /** Discrete supercells in the buoyant wedge between the fronts: left their whole ladder. */
    WARM_SECTOR_SUPERCELL("warm sector", Rung.THUNDERSTORM, true, 0.66F),
    /** Elevated, high-based cells over the cool air ahead of a warm front: hail and small tornadoes. */
    WARM_FRONT_ELEVATED("warm front", Rung.HAILSTORM, false, 0.52F);

    /** The rung a cell of this kind starts on. */
    public enum Rung {
        THUNDERSTORM,
        HAILSTORM
    }

    private final String label;
    private final Rung startRung;
    private final boolean fullLadder;
    private final float startIntensity;

    FrontalCellKind(String label, Rung startRung, boolean fullLadder, float startIntensity) {
        this.label = label;
        this.startRung = startRung;
        this.fullLadder = fullLadder;
        this.startIntensity = startIntensity;
    }

    /** The air mass this kind of cell belongs to, for a readout or a log line. */
    public String label() {
        return label;
    }

    /** The rung the cell is already on when it is seeded: a front's own updraft is running first. */
    public Rung startRung() {
        return startRung;
    }

    /** How hard the cell is already going when it is seeded, 0..1. */
    public float startIntensity() {
        return startIntensity;
    }

    /**
     * True for a cell that may climb the whole ladder. Only a warm sector's discrete supercells may:
     * everything else is being driven by the front, and a front that is driving the air up is not the
     * kind of lift that makes a violent tornado.
     */
    public boolean fullLadder() {
        return fullLadder;
    }

    /** True for a cell whose growth is being driven by the front rather than by its own buoyancy. */
    public boolean forced() {
        return !fullLadder;
    }

    /** Elevated convection is high-based convection, which is what starting on the hail rung means. */
    public boolean elevated() {
        return startRung == Rung.HAILSTORM;
    }
}
