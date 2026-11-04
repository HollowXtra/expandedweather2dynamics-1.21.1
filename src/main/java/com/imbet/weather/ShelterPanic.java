package com.imbet.weather;

/**
 * How frightened a villager behaves, and what it does with the doors.
 *
 * <p>The shelter drill already exists and already fixes the hard part: a villager scans for a roofed place,
 * refuses one the funnel is about to cross, avoids the doorway its neighbour claimed, and runs clear when
 * there is nowhere to go. What it did not do was behave like something that is frightened. It walked. It
 * left every door in the village standing open, which is precisely the thing a real household does first -
 * and in this mod it matters, because a door left open is the difference between a house that keeps the
 * debris out and one that does not. And it was silent: a village under a tornado warning sounded exactly
 * like a village on a Tuesday.
 *
 * <p>So the three decisions that were missing live here, and each is a function of one input - how far the
 * funnel is - because that is the only thing a villager can know:
 *
 * <ul>
 *   <li><b>Doors.</b> A villager closes the doors around it once the funnel is close enough that the
 *       warning is not hypothetical, and it reaches further the closer the funnel is, because the doors
 *       that matter are the ones the wind will come through.</li>
 *   <li><b>Panic.</b> A sound every so often while it is exposed, faster and higher as the funnel closes
 *       and stopping once it is under a roof. This is the audible half of the drill: the point is that a
 *       player hears the aisle go quiet and then hears the village, which is the warning the mod's own
 *       sirens are named after.</li>
 *   <li><b>A run rather than a walk.</b> Sprinting is what turns a shelter that is 30 blocks away into one
 *       that is reachable at all.</li>
 * </ul>
 *
 * <p>This class is arithmetic only - no Minecraft types - so the thresholds can be checked outside the
 * game. See {@code .freebuff/scratch/Phase2Sim.java}.
 */
public final class ShelterPanic {

    private ShelterPanic() {
    }

    /**
     * The distance inside which a villager starts behaving as though it is in danger at all. This is
     * deliberately smaller than the threat radius the drill scans: the drill plans while there is time, the
     * panic starts when there is not much.
     */
    public static final double PANIC_RADIUS_BLOCKS = 200.0D;

    /** Inside this, the villagers are as worked up as they get. */
    public static final double CLOSE_RADIUS_BLOCKS = 40.0D;

    /** The doors are shut inside this distance, and not before: a village does not bolt itself in at dawn. */
    public static final double DOOR_RADIUS_TRIGGER_BLOCKS = 240.0D;
    public static final double DOOR_REACH_NEAR_BLOCKS = 9.0D;
    public static final double DOOR_REACH_FAR_BLOCKS = 3.0D;

    /** How often a frightened villager makes a noise, at the far edge and at the near edge. */
    public static final int PANIC_INTERVAL_FAR_TICKS = 150;
    public static final int PANIC_INTERVAL_NEAR_TICKS = 50;
    public static final float PANIC_PITCH_FAR = 1.05F;
    public static final float PANIC_PITCH_NEAR = 1.35F;

    /** How worked up a villager is, 0 to 1. */
    public static double alarm(double threatDistanceBlocks) {
        if (threatDistanceBlocks >= PANIC_RADIUS_BLOCKS) {
            return 0.0D;
        }
        double span = Math.max(1.0D, PANIC_RADIUS_BLOCKS - CLOSE_RADIUS_BLOCKS);
        return clamp((PANIC_RADIUS_BLOCKS - threatDistanceBlocks) / span, 0.0D, 1.0D);
    }

    /** Whether villagers are shutting the village's doors at this distance. */
    public static boolean shouldCloseDoors(double threatDistanceBlocks) {
        return threatDistanceBlocks < DOOR_RADIUS_TRIGGER_BLOCKS;
    }

    /**
     * How far around itself a villager looks for doors to shut. It reaches further as the funnel closes,
     * because with a minute left there is time to shut the whole street and with ten seconds there is only
     * time for the doorway it is standing in.
     */
    public static double doorReachBlocks(double threatDistanceBlocks) {
        return lerp(DOOR_REACH_FAR_BLOCKS, DOOR_REACH_NEAR_BLOCKS, alarm(threatDistanceBlocks));
    }

    /**
     * Ticks between panic sounds, or 0 for silence. A villager that is already under a roof is quiet: the
     * noise is the exposure, and a barricaded household making warning noises at itself would be noise for
     * its own sake.
     */
    public static int panicSoundIntervalTicks(double threatDistanceBlocks, boolean sheltered) {
        if (sheltered) {
            return 0;
        }
        double worked = alarm(threatDistanceBlocks);
        if (worked <= 0.0D) {
            return 0;
        }
        return (int) Math.round(lerp(PANIC_INTERVAL_FAR_TICKS, PANIC_INTERVAL_NEAR_TICKS, worked));
    }

    public static float panicPitch(double threatDistanceBlocks) {
        double worked = alarm(threatDistanceBlocks);
        return (float) lerp(PANIC_PITCH_FAR, PANIC_PITCH_NEAR, worked);
    }

    /**
     * Whether a villager that is out in the open should run. It runs to shelter and it runs when it is
     * fleeing, both of which are the same thing from here.
     */
    public static boolean shouldSprint(double threatDistanceBlocks) {
        return alarm(threatDistanceBlocks) > 0.0D;
    }

    /** Whether the door of a house is worth shutting, given how far the funnel is and how far the door is. */
    public static boolean shutDoor(double threatDistanceBlocks, double doorDistanceBlocks, boolean open) {
        if (!open || !shouldCloseDoors(threatDistanceBlocks)) {
            return false;
        }
        return doorDistanceBlocks <= doorReachBlocks(threatDistanceBlocks);
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }
}
