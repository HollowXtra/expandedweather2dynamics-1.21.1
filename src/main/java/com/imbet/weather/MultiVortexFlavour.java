package com.imbet.weather;

import net.minecraft.util.Mth;

/**
 * The three ways a violent tornado can wear its sub-vortices, decided once when a multi-vortex
 * episode starts and held for that episode's whole length.
 *
 * <p>They differ only in what the <em>parent</em> does. The vortices themselves are the same small
 * embedded circulations in every case; what changes is whether they are the only thing on the ground,
 * whether they are working under a parent that cannot quite reach it, or whether they are turning
 * inside the base of a parent that is planted on it as usual - which is the ordinary multi-vortex
 * tornado, and the one this list exists to make possible.
 */
public enum MultiVortexFlavour {

    /**
     * The parent's condensation has retracted up off the surface: its foot is missing and the
     * sub-vortices turn in the clear air beneath it, while the column above the retraction is the
     * parent it always was. The base lifts and settles back with the episode, so the tornado is never
     * taken off the screen - only off the ground.
     */
    LIFTED("Suction Vortices"),

    /**
     * The parent's condensation stops short of the ground, leaving clear air under its tip, and the
     * sub-vortices scour the surface in that gap - the ground swarm.
     */
    GROUND_SWARM("Ground Swarm Tornado"),

    /**
     * The parent is planted on the ground exactly as it normally is, and the sub-vortices turn inside
     * its base. Its lowest layers are thinned and drawn back slightly so a vortex orbiting inside them
     * can be seen, but the parent itself is untouched: full height, full width, full strength.
     */
    EMBEDDED("Multi-Vortex Tornado");

    private final String label;

    MultiVortexFlavour(String label) {
        this.label = label;
    }

    /** Name for a tornado wearing this arrangement, used by radar, surveys and damage reports. */
    public String label() {
        return label;
    }

    /** Whether the parent funnel is on the ground during this arrangement. */
    public boolean parentOnTheGround() {
        return this == EMBEDDED;
    }

    /**
     * Picks the arrangement for one episode from a single roll, so the two chances read as shares of
     * all episodes and can never add up to more than certainty: the ground swarm is taken first, then
     * the planted parent, and everything left over comes out with the parent's base lifted. Kept here,
     * as arithmetic on a roll, so the shares can be checked rather than assumed - see the scratch
     * harness.
     */
    public static MultiVortexFlavour roll(double roll, double groundSwarmShare, double plantedShare) {
        double groundSwarm = Mth.clamp(groundSwarmShare, 0.0D, 1.0D);
        double planted = Mth.clamp(plantedShare, 0.0D, 1.0D - groundSwarm);
        if (roll < groundSwarm) {
            return GROUND_SWARM;
        }
        return roll < groundSwarm + planted ? EMBEDDED : LIFTED;
    }

    public static MultiVortexFlavour byName(String name) {
        for (MultiVortexFlavour flavour : values()) {
            if (flavour.name().equalsIgnoreCase(name)) {
                return flavour;
            }
        }
        return LIFTED;
    }
}
