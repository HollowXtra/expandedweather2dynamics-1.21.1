package com.imbet.photo;

/**
 * What a photograph is worth, as six measured things and two exposures.
 *
 * <p>The score is built out of what a severe-weather photographer actually judges a frame on, so that the
 * number can be explained rather than felt, and the six parts are spelled out here with their own maxima:
 *
 * <ul>
 *   <li><b>Framing</b> (25) - where the storm sits in the frame. Full marks for a subject off to one side and
 *       clear of the edge; a little less dead centre, because a photograph with the subject in the exact middle
 *       reads as a snapshot; nothing at all once it is outside the view.</li>
 *   <li><b>Proximity</b> (20) - distance. Full marks across a band, decaying to nothing at the far end because a
 *       tornado four hundred blocks away is a smudge, and decaying towards zero at the near end because standing
 *       on one gets you a grey wall.</li>
 *   <li><b>Subject</b> (25) - the storm itself: half how strong it is, then how much of it you can see and how
 *       much ground it is working on. A rain-wrapped monster and a clear condensation funnel are different
 *       photographs of the same tornado, and only one of them shows the tornado; a rope tearing up a field and
 *       a rope over clean grass are the same wind and different photographs too, which is what the damage swath
 *       is measuring. It is a third reading the other two cannot see: wind is the vortex and width is what it
 *       looks like, and neither of them is whether it is picking anything up.</li>
 *   <li><b>Structure</b> (12) - the mesocyclone and wall cloud above the funnel, straight off the supercell
 *       structure the mod already computes. A bare updraft is a different photograph, and worth less.</li>
 *   <li><b>Atmosphere</b> (14) - the light. Dawn and dusk are worth the most, and a bolt in the frame is worth
 *       the rest.</li>
 *   <li><b>Depth</b> (4) - something in the foreground, which is what turns a storm into a place.</li>
 * </ul>
 *
 * <p>Three exposures then scale the total rather than being part of it: how much of the line of sight is
 * blocked - because a funnel behind a hill is not a photograph, however well framed the hill is - rain between
 * the camera and the storm, and being inside the tornado, which is a whiteout and the one photograph in this
 * mod that cannot be taken. All three are multiplicative because they are not mistakes in judgement, they are
 * things in the way. Being inside the core also suppresses the depth marks' usual reading of obstruction, so a
 * whiteout is reported as a whiteout rather than as a storm behind a very good foreground.
 *
 * <p>Foreground is rewarded and obstruction is punished by two different mechanisms on purpose. The four depth
 * marks are at their best with <em>something</em> in front of the storm, which is what gives a photograph its
 * scale; {@link #visibilityFraction(double)} is what takes the photograph away once too much is in front. One
 * number cannot do both jobs, and a score that paid depth marks for a subject nobody can see would be wrong in
 * the direction that matters most.
 *
 * <p>Everything here is arithmetic on a {@link PhotoFrame}, so a score can be explained line by line and
 * checked without a camera.
 */
public record PhotoScore(
        int framing,
        int proximity,
        int subject,
        int structure,
        int atmosphere,
        int depth,
        int total,
        Grade grade,
        double offAxisFraction,
        double distanceBlocks,
        double occlusionFraction,
        boolean insideCore,
        boolean raining,
        boolean lightningInFrame
) {
    public static final int FRAMING_MAX = 25;
    public static final int PROXIMITY_MAX = 20;
    public static final int SUBJECT_MAX = 25;
    public static final int STRUCTURE_MAX = 12;
    public static final int ATMOSPHERE_MAX = 14;
    public static final int DEPTH_MAX = 4;
    /** The best possible score, as a number the record can be printed against. */
    public static final int MAX = FRAMING_MAX + PROXIMITY_MAX + SUBJECT_MAX + STRUCTURE_MAX + ATMOSPHERE_MAX + DEPTH_MAX;

    /** Below this fraction of the half-frame a subject is called dead centre. */
    public static final double DEAD_CENTRE = 0.25D;
    /** What a dead-centre subject keeps of its framing marks. */
    public static final double DEAD_CENTRE_KEEP = 0.86D;
    /** Up to here the subject is comfortably inside the frame and keeps every mark. */
    public static final double COMFORTABLE_MAX = 0.62D;
    /** The wind at which a storm is worth full subject marks. */
    public static final double SUBJECT_FULL_MPH = 200.0D;
    /** The visible funnel width, in blocks, that is worth full marks for the visible half of the subject. */
    public static final double SUBJECT_FULL_FUNNEL_BLOCKS = 20.0D;
    /**
     * The damage swath, in blocks, that is worth full marks for the debris third of the subject. A path this
     * wide is a circulation taking trees and soil with it rather than one that has merely reached the ground,
     * and it is deliberately a swath rather than a wind because the wind is already half the subject: repeating
     * it here would count the same fact twice and make this part a second, quieter strength score.
     */
    public static final double DEBRIS_FULL_SWATH_BLOCKS = 240.0D;
    /** How much of the subject's marks are strength, visibility and debris. They add up to one. */
    public static final double SUBJECT_WIND_WEIGHT = 0.5D;
    public static final double SUBJECT_VISIBLE_WEIGHT = 0.3D;
    public static final double SUBJECT_DEBRIS_WEIGHT = 0.2D;
    /** Sunrise and sunset, in hours of the Minecraft day. */
    public static final double DAWN_HOUR = 6.5D;
    public static final double DUSK_HOUR = 18.5D;
    /** How far either side of dawn or dusk the golden light still counts, in hours. */
    public static final double GOLDEN_SPREAD_HOURS = 1.5D;
    /**
     * Up to this much of the line of sight may be blocked with the subject still wholly visible. It is also the
     * occlusion that earns every depth mark: a little foreground is what a storm photograph wants, and this is
     * where the two curves meet, at the last point where what is in front is scale rather than concealment.
     */
    public static final double VISIBLE_MAX = 0.35D;
    /** The occlusion fraction that earns every depth mark. */
    public static final double DEPTH_FULL_OCCLUSION = VISIBLE_MAX;
    /** What a photograph taken from inside the tornado's core is multiplied by: a whiteout, not a photo. */
    public static final double INSIDE_CORE_EXPOSURE = 0.5D;
    /** What standing in rain costs: the light goes flat and everything beyond it goes grey. */
    public static final double RAIN_EXPOSURE = 0.85D;

    /** The grade ladder, weakest first. */
    public enum Grade {
        POOR(0, "POOR"),
        FAIR(35, "FAIR"),
        GOOD(55, "GOOD"),
        GREAT(70, "GREAT"),
        EXCELLENT(85, "EXCELLENT");

        private final int floor;
        private final String label;

        Grade(int floor, String label) {
            this.floor = floor;
            this.label = label;
        }

        public int floor() {
            return floor;
        }

        public String label() {
            return label;
        }

        /** The grade a total has earned, never null. */
        public static Grade of(int total) {
            Grade best = POOR;
            for (Grade grade : values()) {
                if (total >= grade.floor) {
                    best = grade;
                }
            }
            return best;
        }
    }

    /** Scores one frame. A subject outside the view is not a photograph and scores nothing. */
    public static PhotoScore of(PhotoFrame frame) {
        if (frame == null) {
            return new PhotoScore(0, 0, 0, 0, 0, 0, 0, Grade.POOR, 0.0D, 0.0D, 0.0D, false, false, false);
        }
        double offAxis = frame.offAxisFraction();
        double distance = frame.distanceBlocks();
        if (offAxis > 1.0D) {
            return new PhotoScore(0, 0, 0, 0, 0, 0, 0, Grade.POOR, offAxis, distance, 0.0D, false, false, false);
        }
        int framing = (int) Math.round(FRAMING_MAX * composition(offAxis));
        double proximityFraction = proximityFraction(frame, distance);
        int proximity = (int) Math.round(PROXIMITY_MAX * proximityFraction);
        int subject = (int) Math.round(SUBJECT_MAX * subjectFraction(frame));
        int structure = (int) Math.round(STRUCTURE_MAX * clamp01(frame.structureStrength()));
        int atmosphere = (int) Math.round(ATMOSPHERE_MAX * atmosphereFraction(frame));
        int depth = (int) Math.round(DEPTH_MAX * depthFraction(frame.occlusionFraction()));
        int raw = framing + proximity + subject + structure + atmosphere + depth;
        double exposure = visibilityFraction(frame.occlusionFraction());
        if (frame.insideCore()) {
            exposure *= INSIDE_CORE_EXPOSURE;
        }
        if (frame.raining()) {
            exposure *= RAIN_EXPOSURE;
        }
        int total = (int) Math.round(raw * exposure);
        return new PhotoScore(framing, proximity, subject, structure, atmosphere, depth, total, Grade.of(total),
                offAxis, distance, frame.occlusionFraction(), frame.insideCore(), frame.raining(),
                frame.lightningInFrame());
    }

    /**
     * How well the subject sits in the frame, from 0 to 1. Dead centre is marked down a little rather than
     * rewarded: the subject in the middle of the frame is the one thing every snapshot has in common.
     */
    public static double composition(double offAxis) {
        if (offAxis < DEAD_CENTRE) {
            return DEAD_CENTRE_KEEP;
        }
        if (offAxis <= COMFORTABLE_MAX) {
            return 1.0D;
        }
        // Clamped, because a subject beyond the rim is not a photograph: the fraction keeps growing past 1
        // while the marks must not go on falling below nothing, which is what a negative framing mark would be.
        return clamp01(1.0D - (offAxis - COMFORTABLE_MAX) / (1.0D - COMFORTABLE_MAX));
    }

    /**
     * How much of the proximity marks a distance keeps: flat across the sweet spot, decaying to nothing at the
     * far limit, and decaying towards nothing as the camera closes on the subject.
     */
    public static double proximityFraction(PhotoFrame frame, double distance) {
        return proximityFraction(distance, frame.sweetSpotMinBlocks(), frame.sweetSpotMaxBlocks(),
                frame.maxDistanceBlocks());
    }

    /**
     * The same curve off a frame's own numbers, which is what the live viewfinder reads: it has a distance and
     * the band before there is a frame, and a viewfinder whose distance marks disagreed with the score's would
     * be telling a player to shoot from somewhere the score does not reward.
     */
    public static double proximityFraction(double distance, double sweetSpotMin, double sweetSpotMax,
                                           double maxDistance) {
        double near = Math.max(1.0D, sweetSpotMin);
        double far = Math.max(near, sweetSpotMax);
        double limit = Math.max(far, maxDistance);
        if (distance >= near && distance <= far) {
            return 1.0D;
        }
        if (distance > far) {
            return clamp01((limit - distance) / Math.max(1.0D, limit - far));
        }
        return clamp01(distance / near);
    }

    /**
     * How good the storm is as a subject, from 0 to 1: strength first, then how much of it is visible, then how
     * much ground it is working on. Nothing is subtracted for a clean funnel - it is simply not credited with
     * debris it is not lifting - so the debris reading can only ever raise a subject that is there.
     */
    public static double subjectFraction(PhotoFrame frame) {
        double floor = Math.max(0, frame.minSubjectMph());
        double wind = clamp01((frame.stormMph() - floor) / Math.max(1.0D, SUBJECT_FULL_MPH - floor));
        double visible = clamp01(frame.funnelWidthBlocks() / SUBJECT_FULL_FUNNEL_BLOCKS);
        double debris = debrisFraction(frame.debrisSwathBlocks());
        return clamp01(SUBJECT_WIND_WEIGHT * wind + SUBJECT_VISIBLE_WEIGHT * visible + SUBJECT_DEBRIS_WEIGHT * debris);
    }

    /**
     * How much of the debris marks a damage swath earns, from 0 to 1. A circulation with no swath is not
     * marked down for the ground it has not reached, and one wider than the full-mark swath is not credited
     * past it, so this curve can only ever fill a part of the subject score that the other two left empty.
     */
    public static double debrisFraction(double swathBlocks) {
        return clamp01(swathBlocks / DEBRIS_FULL_SWATH_BLOCKS);
    }

    /** How good the light is, from 0 to 1: golden hour, plus a bolt in the frame. */
    public static double atmosphereFraction(PhotoFrame frame) {
        return clamp01(goldenHourFraction(frame.hourOfDay()) + (frame.lightningInFrame() ? 1.0D : 0.0D));
    }

    /**
     * How golden an hour of the day is, from 0 to 1. Full marks on dawn and dusk and nothing at noon or
     * midnight, falling away over the configured spread.
     */
    public static double goldenHourFraction(double hour) {
        double dawn = 1.0D - Math.min(1.0D, Math.abs(hour - DAWN_HOUR) / GOLDEN_SPREAD_HOURS);
        double dusk = 1.0D - Math.min(1.0D, Math.abs(hour - DUSK_HOUR) / GOLDEN_SPREAD_HOURS);
        return clamp01(Math.max(dawn, dusk));
    }

    /** How much a bit of foreground is worth, from 0 to 1. Nothing in front and nothing to show for it. */
    public static double depthFraction(double occlusion) {
        if (occlusion <= 0.0D) {
            return 0.0D;
        }
        return clamp01(occlusion / DEPTH_FULL_OCCLUSION);
    }

    /**
     * How much of a photograph survives what is in front of the subject, from 0 to 1. Clear up to
     * {@link #VISIBLE_MAX}, then falling away to nothing as the subject disappears behind whatever is there -
     * so a funnel with a hill across its foot is still a photograph, and one behind a whole hillside is not.
     */
    public static double visibilityFraction(double occlusion) {
        double blocked = clamp01(occlusion);
        if (blocked <= VISIBLE_MAX) {
            return 1.0D;
        }
        return clamp01(1.0D - (blocked - VISIBLE_MAX) / (1.0D - VISIBLE_MAX));
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    /** The sum of the parts before the exposures were applied, which is what the explanations add up to. */
    public int rawTotal() {
        return framing + proximity + subject + structure + atmosphere + depth;
    }
}
