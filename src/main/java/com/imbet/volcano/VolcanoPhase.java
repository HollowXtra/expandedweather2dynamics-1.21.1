package com.imbet.volcano;

/**
 * The stages a volcano actually goes through, in the order it goes through them.
 *
 * <p>The 1.12.2 version had seven numeric states and no vocabulary: it built a cone, filled its throat with
 * lava, put a lava blob on top and let it set to stone, forever. What that misses is everything a volcano is
 * dangerous for. A real eruption announces itself weeks ahead, the first explosions are steam rather than
 * magma, the column that follows can fall back down on itself faster than anything can run, the lava comes
 * after the collapse rather than instead of it, and the ash keeps killing for years in the rain. Each of those
 * is a phase here, with its own hazards attached, so a player can read what is about to happen and the systems
 * behind it can fan out from one ladder.
 *
 * <p>The order is the observed one. A single episode walks {@link #UNREST} to {@link #WANING} and back to
 * {@link #DORMANT}, and a volcano erupts again later at whatever intensity its own history gives it.
 */
public enum VolcanoPhase {
    /** Asleep. A cold cone, and what the world looks like most of the time. */
    DORMANT("Dormant", 0.0D, false, false, false, 0),
    /**
     * The warning. Hundreds of small earthquakes, fumaroles opening on the flanks, ground cracking, animals
     * leaving. Almost nothing is being thrown out, and this is the phase a warning is actually useful in.
     */
    UNREST("Volcanic unrest", 0.0D, false, false, false, 220),
    /**
     * Steam explosions. Groundwater meets hot rock and flashes, blasting existing rock apart and making ash out
     * of it rather than out of fresh magma, so this can happen on a volcano that never erupts lava at all.
     */
    PHREATIC("Phreatic eruption", 0.18D, false, false, true, 140),
    /**
     * The main event: lava fountains at the vent and a sustained column of ash and gas. This is the phase that
     * spreads ash over a region, and the one that throws bombs at anything standing on the flanks.
     */
    MAGMATIC("Sustained eruption column", 1.0D, true, true, true, 90),
    /**
     * The column loses its buoyancy and comes back down. Nothing in this system is faster or less survivable:
     * the current hugs the ground and runs out for kilometres.
     */
    COLLAPSE("Eruption column collapse", 0.55D, true, true, false, 60),
    /**
     * Effusive after the explosive peak: lava out of the vent, flowing downslope for hours or days. Slower than
     * a surge and just as final to anything it reaches, but it can be walked away from.
     */
    FLOW("Effusive eruption", 0.22D, true, false, false, 400),
    /**
     * Cooling down. Fumaroles, and the ash is still loose, so this is when rain starts turning it into lahars.
     */
    WANING("Waning activity", 0.08D, false, false, false, 320);

    private final String label;
    private final double ashColumnFraction;
    private final boolean lava;
    private final boolean bombs;
    private final boolean explosionSounds;
    private final int quakeCadenceTicks;

    VolcanoPhase(String label, double ashColumnFraction, boolean lava, boolean bombs, boolean explosionSounds, int quakeCadenceTicks) {
        this.label = label;
        this.ashColumnFraction = ashColumnFraction;
        this.lava = lava;
        this.bombs = bombs;
        this.explosionSounds = explosionSounds;
        this.quakeCadenceTicks = quakeCadenceTicks;
    }

    /** What a person watching this volcano from a hill would call what it is doing. */
    public String label() {
        return label;
    }

    /** The column's height as a fraction of this eruption's own plume height at its peak. */
    public double ashColumnFraction() {
        return ashColumnFraction;
    }

    /** Whether magma is at the vent, and so whether lava flows, lakes and fountains belong in this phase. */
    public boolean lava() {
        return lava;
    }

    /** Whether the vent is throwing solid rock and lava out of it. */
    public boolean bombs() {
        return bombs;
    }

    /** Whether the explosions in this phase are loud enough to be heard out of sight. */
    public boolean explosive() {
        return explosionSounds;
    }

    /** How many ticks apart its small earthquakes are, before intensity and config are applied. */
    public int quakeCadenceTicks() {
        return quakeCadenceTicks;
    }

    /** Whether anything is coming out of the ground at all. */
    public boolean erupting() {
        return this != DORMANT;
    }

    /** Whether the column is feeding ash downwind, which is the phase's own ashfall switch. */
    public boolean ashFall() {
        return ashColumnFraction > 0.0D;
    }

    /** Whether this phase runs density currents, which is only ever the collapse. */
    public boolean pyroclastic() {
        return this == COLLAPSE;
    }

    /** The next phase in a single episode, with the ladder wrapping back to dormancy. */
    public VolcanoPhase next() {
        return switch (this) {
            case DORMANT -> UNREST;
            case UNREST -> PHREATIC;
            case PHREATIC -> MAGMATIC;
            case MAGMATIC -> COLLAPSE;
            case COLLAPSE -> FLOW;
            case FLOW -> WANING;
            case WANING -> DORMANT;
        };
    }

    /** True while an episode is under way, which is every phase but dormancy. */
    public boolean active() {
        return this != DORMANT;
    }

    public static VolcanoPhase byIndex(int index) {
        VolcanoPhase[] values = values();
        return values[VolcanoModel.clamp(index, 0, values.length - 1)];
    }
}
