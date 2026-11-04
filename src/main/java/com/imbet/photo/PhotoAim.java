package com.imbet.photo;

import com.imbet.chase.ChaseLogReadout;

import java.util.List;

/**
 * What the camera is pointed at right now, and what the two parts of a score that need no world say about it.
 *
 * <p>A viewfinder is only worth having if it agrees with the score, so this reads its marks from the scorer's own
 * curves rather than from a second opinion: the framing marks are {@link PhotoScore#composition(double)} and the
 * distance marks are {@link PhotoScore#proximityFraction(double, double, double, double)}, both of which the
 * shot itself will be scored with a moment later. What the viewfinder therefore shows is not an estimate of the
 * photograph, it is the two parts of it that are decided by geometry - which is also why it shows exactly those
 * two and claims nothing about the light, the foreground or the line of sight, none of which a client can know
 * from a camera's own numbers.
 *
 * <p>Both of those parts are measured from the aim and the lens, so turning the camera moves them: that is the
 * whole feature. The subject is chosen by the shutter's own rule, {@link PhotoService#chooseIndex}, so the
 * reticle and the photograph can never be pointing at different storms.
 *
 * @param subjectKey        the subject's identity, empty when there is nothing to point at
 * @param subjectLabel      what it is called, as the mod names it
 * @param subjectMph        the wind that earns its EF class
 * @param offAxisFraction   how far off the aim axis it sits, in half-frames: 0 dead centre, 1 on the rim
 * @param horizontalFraction where it sits across the frame, signed: positive right, negative left
 * @param verticalFraction  where it sits up the frame, signed: positive above the aim
 * @param distanceBlocks    how far away it is, in blocks
 * @param framingMarks      the marks a shot taken now would be given for framing, out of {@link PhotoScore#FRAMING_MAX}
 * @param proximityMarks    the marks it would be given for distance, out of {@link PhotoScore#PROXIMITY_MAX}
 * @param inSweetSpot       whether it is inside the band that earns full distance marks
 * @param tooClose          whether it is nearer than that band, which is a wall of grey rather than a subject
 * @param deadCentre        whether it sits close enough to the middle to be marked down for it
 * @param maxDistanceBlocks the range the camera refuses to photograph past, so a viewfinder can say why
 * @param sweetSpotMinBlocks the near end of the band that earns full distance marks
 * @param sweetSpotMaxBlocks the far end of it
 */
public record PhotoAim(
        String subjectKey,
        String subjectLabel,
        double subjectMph,
        double offAxisFraction,
        double horizontalFraction,
        double verticalFraction,
        double distanceBlocks,
        int framingMarks,
        int proximityMarks,
        boolean inSweetSpot,
        boolean tooClose,
        boolean deadCentre,
        double maxDistanceBlocks,
        double sweetSpotMinBlocks,
        double sweetSpotMaxBlocks
) {
    /** An aim with nothing in it: no circulation is in frame, and the range is still worth reporting. */
    public static PhotoAim none(double maxDistanceBlocks) {
        return new PhotoAim("", "", 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0, 0, false, false, false,
                maxDistanceBlocks, 0.0D, 0.0D);
    }

    /** Whether the camera has anything to point at. */
    public boolean present() {
        return subjectKey != null && !subjectKey.isBlank();
    }

    /**
     * Measures the frame the camera is holding: which target the shutter's own rule picks, where it sits, and
     * what framing and distance would be worth. Everything here is geometry and two curves, so it costs nothing
     * and can be called every frame.
     */
    public static PhotoAim of(List<PhotoTarget> targets, double eyeX, double eyeY, double eyeZ,
                              double dirX, double dirY, double dirZ, double fovDegrees,
                              double minSubjectMph, double maxDistanceBlocks,
                              double sweetSpotMinBlocks, double sweetSpotMaxBlocks) {
        double maxDistance = Math.max(8.0D, maxDistanceBlocks);
        double sweetMin = Math.max(1.0D, sweetSpotMinBlocks);
        double sweetMax = Math.max(sweetMin, sweetSpotMaxBlocks);
        PhotoAim empty = none(maxDistance);
        if (targets == null || targets.isEmpty()) {
            return empty;
        }
        double halfFrame = PhotoFrame.halfFrameRadians(fovDegrees);
        int count = targets.size();
        double[] angles = new double[count];
        double[] distances = new double[count];
        double[] strengths = new double[count];
        for (int index = 0; index < count; index++) {
            PhotoTarget target = targets.get(index);
            // Off the axis in radians, which is the unit the shutter's own rule works in: the reading this class
            // shows is in half-frames, but the pick between two targets is made in the same numbers the shutter
            // made it with, so a subject on the rim is off a hundredth of a degree the same way for both.
            angles[index] = PhotoFrame.offAxisFraction(target.x(), target.y(), target.z(),
                    eyeX, eyeY, eyeZ, dirX, dirY, dirZ, fovDegrees) * halfFrame;
            distances[index] = Math.sqrt(sqr(target.x() - eyeX) + sqr(target.y() - eyeY) + sqr(target.z() - eyeZ));
            strengths[index] = target.stormMph();
        }
        int chosen = PhotoService.chooseIndex(angles, distances, halfFrame, maxDistance, minSubjectMph, strengths);
        if (chosen < 0) {
            return empty;
        }
        PhotoTarget target = targets.get(chosen);
        double offAxis = angles[chosen] / halfFrame;
        double distance = distances[chosen];
        double proximity = PhotoScore.proximityFraction(distance, sweetMin, sweetMax, maxDistance);
        return new PhotoAim(
                target.key(), target.label(), target.stormMph(),
                offAxis,
                PhotoFrame.horizontalFraction(target.x(), target.y(), target.z(),
                        eyeX, eyeY, eyeZ, dirX, dirY, dirZ, fovDegrees),
                PhotoFrame.verticalFraction(target.x(), target.y(), target.z(),
                        eyeX, eyeY, eyeZ, dirX, dirY, dirZ, fovDegrees),
                distance,
                (int) Math.round(PhotoScore.FRAMING_MAX * PhotoScore.composition(offAxis)),
                (int) Math.round(PhotoScore.PROXIMITY_MAX * proximity),
                distance >= sweetMin && distance <= sweetMax,
                distance < sweetMin,
                offAxis < PhotoScore.DEAD_CENTRE,
                maxDistance, sweetMin, sweetMax);
    }

    /** What the camera is pointed at, as a viewfinder line. Empty when there is nothing in frame. */
    public String subjectLine() {
        if (!present()) {
            return "";
        }
        return subjectLabel.toUpperCase(java.util.Locale.ROOT) + " \u00b7 "
                + ChaseLogReadout.round(subjectMph) + " MPH";
    }

    /** The framing part of a shot taken now, and what to do about it. */
    public String framingLine() {
        if (!present()) {
            return "";
        }
        String hint;
        if (deadCentre) {
            hint = "DEAD CENTRE";
        } else if (framingMarks >= PhotoScore.FRAMING_MAX) {
            hint = "FRAMED";
        } else {
            hint = "TO THE EDGE";
        }
        return "FRAMING " + framingMarks + "/" + PhotoScore.FRAMING_MAX + " \u00b7 " + hint;
    }

    /** The distance part of a shot taken now, and which way to move. */
    public String distanceLine() {
        if (!present()) {
            return "";
        }
        String hint;
        if (inSweetSpot) {
            hint = "SWEET SPOT";
        } else if (tooClose) {
            hint = "TOO CLOSE";
        } else {
            hint = "TOO FAR";
        }
        return "DISTANCE " + ChaseLogReadout.round(distanceBlocks) + " BLK \u00b7 " + hint
                + " (" + proximityMarks + "/" + PhotoScore.PROXIMITY_MAX + ")";
    }

    /** What the viewfinder says when there is nothing to photograph, which is worth saying at all. */
    public String statusLine() {
        if (present()) {
            return "SUBJECT LOCKED";
        }
        return "NO CIRCULATION IN FRAME \u00b7 " + ChaseLogReadout.round(maxDistanceBlocks) + " BLK";
    }

    /**
     * Whether this is the best the geometry can be, which is what a reticle turns green for: every framing mark
     * and every distance mark, with nothing to fix.
     */
    public boolean ideal() {
        return present() && framingMarks >= PhotoScore.FRAMING_MAX && proximityMarks >= PhotoScore.PROXIMITY_MAX;
    }

    private static double sqr(double value) {
        return value * value;
    }
}
