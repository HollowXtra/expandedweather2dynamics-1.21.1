package com.imbet.photo;

import com.imbet.chase.ChaseScale;

/**
 * One filed photograph: what was in the frame, where the camera stood, and what the six parts of the score
 * came to.
 *
 * <p>The parts are stored beside the total rather than the total alone, because a score that cannot be
 * explained is a score nobody tries to beat - "framing 12, proximity 4" tells a player they were too far away
 * and half out of frame, and "41" tells them nothing. The three measurements the parts came from are kept for
 * the same reason: they are what the album prints and what the advice lines are written from.
 *
 * <p>The grade is deliberately <em>not</em> stored. It is a pure function of the total - see
 * {@link PhotoScore.Grade#of(int)} - and a record that carried both could be read back with a grade that
 * disagrees with its own score, which is the one thing a ladder must never do.
 *
 * <p>Nothing here is Minecraft-side: a shot is numbers, two labels, a clock reading and three flags, so a
 * score can be explained line by line without a camera or a world.
 *
 * @param targetKey        the subject's stable identity, so a gallery line and a storm can be matched up
 * @param targetLabel      what the subject was called when the shutter went, e.g. "EF3 Tornado"
 * @param stormMph         the subject's own strength in mph, which is what earns its EF class
 * @param distanceBlocks   how far the camera was from the subject, in blocks
 * @param offAxisFraction  where the subject sat in the frame, in half-frames: 0 dead centre, 1 on the rim
 * @param occlusionFraction how much of the line of sight was blocked, 0 clear to 1 hidden
 * @param framing          composition marks, out of {@link PhotoScore#FRAMING_MAX}
 * @param proximity        distance marks, out of {@link PhotoScore#PROXIMITY_MAX}
 * @param subject          the storm's own marks, out of {@link PhotoScore#SUBJECT_MAX}
 * @param structure        mesocyclone and wall cloud marks, out of {@link PhotoScore#STRUCTURE_MAX}
 * @param atmosphere       light marks, out of {@link PhotoScore#ATMOSPHERE_MAX}
 * @param depth            foreground marks, out of {@link PhotoScore#DEPTH_MAX}
 * @param total            the parts scaled by the exposures, out of {@link PhotoScore#MAX}
 * @param gameTime         the world tick the shutter went, which is what the album ages a shot from
 * @param dayTime          the level's own day time, kept so the hour of the shot survives the world moving on
 * @param lightning        whether a bolt was in the frame
 * @param raining          whether the camera was standing in rain
 * @param insideCore       whether the camera was inside the tornado, which is the one photograph nobody takes
 */
public record PhotoShot(
        String targetKey,
        String targetLabel,
        double stormMph,
        double distanceBlocks,
        double offAxisFraction,
        double occlusionFraction,
        int framing,
        int proximity,
        int subject,
        int structure,
        int atmosphere,
        int depth,
        int total,
        long gameTime,
        long dayTime,
        boolean lightning,
        boolean raining,
        boolean insideCore
) {
    /**
     * Folds a scored frame into a record. This is the one place the two halves of a photograph meet - the
     * measurement and the verdict - so a stored shot is always a scored frame rather than a score that was
     * assembled somewhere else.
     */
    public static PhotoShot of(PhotoFrame frame, PhotoScore score, long gameTime) {
        return new PhotoShot(
                frame.targetKey(),
                frame.targetLabel(),
                frame.stormMph(),
                score.distanceBlocks(),
                score.offAxisFraction(),
                score.occlusionFraction(),
                score.framing(),
                score.proximity(),
                score.subject(),
                score.structure(),
                score.atmosphere(),
                score.depth(),
                score.total(),
                gameTime,
                frame.dayTime(),
                score.lightningInFrame(),
                score.raining(),
                score.insideCore()
        );
    }

    /** What the total earns on the published ladder. Derived, never stored. */
    public PhotoScore.Grade grade() {
        return PhotoScore.Grade.of(total);
    }

    /** The hour of the Minecraft day the shutter went, read off the stored day time. */
    public double hourOfDay() {
        return PhotoFrame.hourOfDay(dayTime);
    }

    /** What the subject's own wind earns on the EF scale, which is the same ladder the chase log counts on. */
    public String subjectClass() {
        return ChaseScale.efClass(stormMph);
    }

    /** The parts as they were before the exposures scaled them, which is the number the breakdown adds to. */
    public int rawTotal() {
        return framing + proximity + subject + structure + atmosphere + depth;
    }

    /**
     * Whether this shot is the better photograph than another, which is the whole ranking rule the album uses:
     * the higher score wins, then the closer camera, then the stronger storm, then the earlier shot. The last
     * two rungs are what make an album stable rather than arbitrary - a photograph of a monster beats a
     * photograph of a breeze at the same score, and two identical shots keep the order they were taken in
     * instead of swapping places as the list is re-sorted.
     */
    public boolean outranks(PhotoShot other) {
        if (other == null) {
            return true;
        }
        if (total != other.total) {
            return total > other.total;
        }
        if (distanceBlocks != other.distanceBlocks) {
            return distanceBlocks < other.distanceBlocks;
        }
        if (stormMph != other.stormMph) {
            return stormMph > other.stormMph;
        }
        return gameTime < other.gameTime;
    }
}
