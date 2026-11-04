package com.imbet.village;

/**
 * How much of a load a builder still has, expressed as something you can see.
 *
 * <p>The crossed-arms layer draws whatever a villager holds in its main hand, and an item stack drawn in
 * the world shows no count - so a builder carrying eight blocks looks exactly like a builder carrying
 * one. The one thing the renderer will honour is size, so that is what this turns the tally into: a full
 * load is drawn at its own size and the last block is drawn at {@link #LAST_BLOCK_SCALE}, steadily
 * shrinking in between. A village spending its supplies is then something you can watch rather than
 * something you have to know.
 *
 * <p>Minecraft-free and pure, so the arithmetic is measured in the harness rather than trusted: the goal
 * and the client's render layer ask the same function, and a change to the curve shows up as a number
 * instead of as a judgement on a screen.
 */
public final class SuppliesCarry {
    /** The size a lone remaining block is drawn at: unmistakably a villager about to run out. */
    public static final float LAST_BLOCK_SCALE = 0.45F;

    /** What one held stack can hold. Past this the stack is drawn the same either way. */
    public static final int MOST_SHOWN = 64;

    private SuppliesCarry() {
    }

    /**
     * How many of a tally goes into the held stack.
     *
     * <p>A villager's purse is eight slots, so a builder that has been at this for a while can be holding
     * more of one block than a stack - and an item stack over a stack is not the same thing as a large
     * stack: it is a count vanilla refuses to write, and the equipment update that puts it in the villager's
     * arms is read back off a buffer. A held stack is a stack, and nothing can see the difference in the
     * arms, so the count shown stops at one.
     */
    public static int shownCount(int carried) {
        return Math.max(0, Math.min(carried, MOST_SHOWN));
    }

    /**
     * The scale a carried stack of {@code remaining} is drawn at, given the load that counts as full.
     *
     * <p>Monotone by construction: a bigger load is never drawn smaller. An empty hand is not drawn at
     * all, so it answers 1 rather than dividing by nothing; and a stack at or over the full load is full
     * size, which is what happens when a villager's purse holds more than a trip is worth carrying.
     */
    public static float displayScale(int remaining, int fullLoad) {
        if (remaining <= 0) {
            return 1.0F;
        }
        int load = Math.max(2, fullLoad);
        if (remaining >= load) {
            return 1.0F;
        }
        // remaining - 1 counts from the last block up to the load below full: 0 is the last one, and a
        // load of 1 is drawn full, so the first step down is one block away from full rather than two.
        float left = (float) (remaining - 1) / (float) (load - 1);
        return LAST_BLOCK_SCALE + (1.0F - LAST_BLOCK_SCALE) * left;
    }

    /** The same number as "how much of a load is left", for anything that wants to read rather than draw. */
    public static float loadFraction(int remaining, int fullLoad) {
        int load = Math.max(2, fullLoad);
        if (remaining <= 0) {
            return 0.0F;
        }
        if (remaining >= load) {
            return 1.0F;
        }
        return (float) remaining / (float) load;
    }
}
