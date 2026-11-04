package com.imbet.weather;

import java.util.List;

/**
 * Where a villager should go when a tornado is coming, as arithmetic.
 *
 * <p>This is deliberately free of Minecraft types so the behaviour can be measured without the game
 * running. The goal does the world reading - it walks a radius, checks roofs and floors, and hands
 * the candidates here - and this class decides, from the same numbers a person would use: how far it
 * is, what kind of shelter it is, whether the tornado is going to run over it, whether another
 * villager already has it, and whether reaching it means crossing the funnel's path.
 *
 * <p>The old goal simply took the nearest covered block, which produced the three things you see when
 * a tornado hits a village: everyone packed into the same doorway, villagers walking *into* the
 * funnel because that was the close shelter, and villagers standing in the path because nothing ever
 * asked where the storm was going.
 */
public final class ShelterChoice {
    /** A candidate spot the world reading found. {@code roofed} is already true for every spot here. */
    public record Spot(double x, double y, double z, Kind kind) {
    }

    /**
     * What kind of shelter a spot is, best first. The order is the order villagers prefer them, which
     * is also roughly the order a person would: a bed is under a roof in a house, a door is a way in,
     * a bell means the middle of the village, a workstation is at least indoors, and plain cover is
     * whatever else has something solid overhead.
     */
    public enum Kind {
        BED(0.0D),
        DOOR(1.0D),
        BELL(2.0D),
        WORKSTATION(4.0D),
        COVER(14.0D);

        private final double penalty;

        Kind(double penalty) {
            this.penalty = penalty;
        }
    }

    /** What a spot nobody can use scores: the funnel is going to run over it. */
    public static final double REFUSE = Double.POSITIVE_INFINITY;

    /** Distance cost of taking a spot another villager is already heading for. */
    public static final double SHARED_PENALTY = 12.0D;

    /** Distance cost of a spot that can only be reached by crossing the funnel's projected path. */
    public static final double CROSSING_PENALTY = 25.0D;

    private ShelterChoice() {
    }

    /**
     * How far a point is from the stretch of ground the funnel is about to travel over: the segment
     * from where the storm is now to where it will be after the lead time. A point at the storm's
     * current position is zero, a point it passed over a minute ago is its distance behind, and a
     * point it has not reached yet is its distance ahead - which is the number that matters, because
     * a shelter in front of the funnel is not shelter.
     */
    public static double trackDistance(double px, double pz, double stormX, double stormZ, double leadX, double leadZ) {
        double lengthSqr = leadX * leadX + leadZ * leadZ;
        if (lengthSqr < 1.0E-6D) {
            return Math.hypot(px - stormX, pz - stormZ);
        }
        double t = ((px - stormX) * leadX + (pz - stormZ) * leadZ) / lengthSqr;
        t = Math.max(0.0D, Math.min(1.0D, t));
        return Math.hypot(px - (stormX + leadX * t), pz - (stormZ + leadZ * t));
    }

    /**
     * The signed side of the funnel's path a point is on, or zero when the lead is too short to name a
     * side. Villagers use it to avoid running across the track: two points can be equidistant from the
     * funnel and be completely different places to stand.
     */
    public static int sideOfTrack(double px, double pz, double stormX, double stormZ, double leadX, double leadZ) {
        double cross = leadX * (pz - stormZ) - leadZ * (px - stormX);
        if (Math.abs(cross) < 1.0E-6D) {
            return 0;
        }
        return cross > 0.0D ? 1 : -1;
    }

    /**
     * A prospect's cost, lower being better, or {@link #REFUSE} when the spot is in the funnel's way.
     *
     * @param coreRadius      the radius the funnel will destroy, from the widest damage width
     * @param leadX,leadZ     where the storm will be, relative to where it is now, over the lead time
     * @param claimed         whether another villager already picked this spot this cycle
     */
    public static double score(Spot spot, double fromX, double fromZ,
                               double stormX, double stormZ, double leadX, double leadZ, double coreRadius,
                               boolean claimed) {
        double track = trackDistance(spot.x(), spot.z(), stormX, stormZ, leadX, leadZ);
        if (track < coreRadius) {
            return REFUSE;
        }
        double cost = Math.hypot(spot.x() - fromX, spot.z() - fromZ) + spot.kind().penalty;
        if (claimed) {
            cost += SHARED_PENALTY;
        }
        int mine = sideOfTrack(fromX, fromZ, stormX, stormZ, leadX, leadZ);
        int theirs = sideOfTrack(spot.x(), spot.z(), stormX, stormZ, leadX, leadZ);
        if (mine != 0 && theirs != 0 && mine != theirs) {
            cost += CROSSING_PENALTY;
        }
        // Right at the edge of the core is survivable but it is where the debris is; a shelter with a
        // little margin left over is worth a few blocks of walking.
        if (track < coreRadius * 1.5D) {
            cost += (coreRadius * 1.5D - track) * 0.5D;
        }
        return cost;
    }

    /**
     * The best spot, as an index into {@code spots}, or -1 when every one of them is in the funnel's
     * way or is the ground the villager is already standing on.
     */
    public static int pick(List<Spot> spots, boolean[] claimed, double fromX, double fromZ,
                           double stormX, double stormZ, double leadX, double leadZ, double coreRadius) {
        int best = -1;
        double bestCost = REFUSE;
        for (int i = 0; i < spots.size(); i++) {
            Spot spot = spots.get(i);
            double cost = score(spot, fromX, fromZ, stormX, stormZ, leadX, leadZ, coreRadius,
                    claimed != null && i < claimed.length && claimed[i]);
            // Strictly-better keeps the first of two equal spots, so the choice does not wander.
            if (cost < bestCost) {
                bestCost = cost;
                best = i;
            }
        }
        return best;
    }

    /**
     * Whether standing here is already good enough, which is what keeps a sheltering villager from
     * re-pathing every scan: covered, and not about to be run over.
     */
    public static boolean isSafe(double px, double pz, boolean roofed,
                                 double stormX, double stormZ, double leadX, double leadZ, double coreRadius) {
        return roofed && trackDistance(px, pz, stormX, stormZ, leadX, leadZ) >= coreRadius;
    }

    /**
     * How alarmed a villager is, from 0 when the funnel is a lead time away to 1 when it is on top of
     * them. Distance is measured to the funnel itself rather than to the shelter, so the run sharpens
     * as the storm closes instead of being a flat jog the whole way.
     */
    public static double urgency(double distanceToStorm, double coreRadius, double scareRange) {
        if (scareRange <= coreRadius) {
            return distanceToStorm <= coreRadius ? 1.0D : 0.0D;
        }
        double t = (distanceToStorm - coreRadius) / (scareRange - coreRadius);
        return Math.max(0.0D, Math.min(1.0D, 1.0D - t));
    }

    /** Walking speed for an urgency. A frightened villager runs, but not faster than a walking player. */
    public static double walkSpeed(double urgency) {
        double clamped = Math.max(0.0D, Math.min(1.0D, urgency));
        return 1.05D + 0.30D * clamped;
    }
}
