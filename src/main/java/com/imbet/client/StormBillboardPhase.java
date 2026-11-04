package com.imbet.client;

/**
 * Which frame of its sheet's animation a storm billboard starts on.
 *
 * <p>The problem this exists for is a property of how Minecraft animates a texture, not of the art: each frame
 * of a strip is uploaded into the <em>same</em> atlas region in turn, and the sprite's UVs never move. Every
 * quad drawing that sprite therefore shows the same frame at the same instant, and since the storm's deck, the
 * layered funnel's rings and the dust all draw one sheet per role, a whole storm breathes in lockstep - a
 * thousand billboards swinging between the same eight poses together, which reads as one large texture flipping
 * rather than as cloud turning over. There is no per-quad phase to reach for inside that: the frame is not in
 * the UVs, it is in the upload, so the only lever is <em>which sheet a billboard draws</em>.
 *
 * <p>So the deck's sheet and the funnel's used to ship as {@link #PHASES} siblings each: the same eight-frame
 * boil with its frame order rotated, variant <em>k</em> being that animation started {@link #frameOffset}
 * frames later. A billboard picked a variant when it was made and kept it for its whole life, and the two ways
 * of picking are the whole of this class:
 *
 * <p><strong>Dormant.</strong> None of that is shipping now. The art is the author's own puffs, each supplied
 * as a single still frame and used as it was given - see {@code GenStormArt} - so there is no animation to
 * phase: the three sibling files per role are gone, their atlas entries are gone, and every phase resolves to
 * its own role's sheet through the provider's own fallback. The offsets are written down anyway, and the model still
 * answers them, because they are the only thing that ever made a storm stop breathing in lockstep and they
 * would have to be cut the same way a second time if an animated set is shipped again.
 *
 * <ul>
 *   <li>{@link #forKey} for something that lives on across ticks and can be asked about itself - a funnel puff
 *       is re-sheeted every tick, so the choice has to come from the particle rather than from where it happens
 *       to be standing, or the puff would flicker between variants as it moved;</li>
 *   <li>{@link #forPoint} for something that is being made right now and has no identity to ask - a deck puff
 *       gets its sprite at the spawn call, and hashing the spawn point spreads neighbours across phases, which
 *       is the point: adjacent puffs out of step is what turns a sheet flipping into cloud churning.</li>
 * </ul>
 *
 * <p>Both are stable by construction - the same key or the same block always answers the same phase - because
 * a phase that changed under a particle would be worse than no phase at all: the billboard would jump between
 * frames instead of advancing through them. Neither is random, either: nothing here reads a clock or a random
 * source, so a storm behaves the same way twice and the harness can check the arithmetic rather than a seed.
 */
public final class StormBillboardPhase {
    /** How many phase-shifted sibling sheets the deck and the funnel ship per role. */
    public static final int PHASES = 4;
    /** The frames a phase rotates, which is the frame count of the sheets it is applied to. */
    public static final int FRAMES = 8;

    private StormBillboardPhase() {
    }

    /**
     * The phase for a key that identifies the billboard itself, such as its identity hash.
     *
     * <p>Mixed before it is folded down, because the keys are handed in by something else's hash and a run of
     * them can share low bits: folding first would put a whole ring of a funnel on one variant and give back
     * exactly the lockstep this class is for.
     */
    public static int forKey(int key) {
        return Math.floorMod(mix(key), PHASES);
    }

    /** The phase for a point in the world, rounded down to the block, for a billboard being spawned there. */
    public static int forPoint(double x, double y, double z) {
        return forKey(cell(x) * 73856093 ^ cell(y) * 19349663 ^ cell(z) * 83492791);
    }

    /**
     * How far a variant's frame order is rotated against the sheet it was cut from.
     *
     * <p>Derived rather than listed, and it is the one number the art and the code have to agree on: the
     * generator cuts the variants with it and the harness proves each file is that rotation of the master, so a
     * sheet that was cut by any other rule fails rather than being quietly drawn out of phase.
     */
    public static int frameOffset(int phase) {
        return phase * FRAMES / PHASES;
    }

    private static int cell(double coordinate) {
        return (int) Math.floor(coordinate);
    }

    /** A 32-bit avalanche mix, so a run of nearby keys lands across all {@link #PHASES} phases. */
    private static int mix(int key) {
        int hash = key * 0x9E3779B1;
        hash ^= hash >>> 15;
        hash *= 0x85EBCA6B;
        hash ^= hash >>> 13;
        return hash;
    }
}
