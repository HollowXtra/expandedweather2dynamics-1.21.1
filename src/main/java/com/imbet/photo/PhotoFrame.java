package com.imbet.photo;

/**
 * One camera, one storm, and everything a photograph's score is decided from.
 *
 * <p>Kept as plain numbers because a photograph's composition is geometry: where the subject sits in the frame
 * is the angle between the way the camera is pointing and the way the subject is, and the rest is distance,
 * light and what is between the two. None of that needs a running game to check, and the parts that do - which
 * storms are in the world, what the weather is doing, whether the ray is blocked - are measured once by the
 * service and handed here as numbers.
 *
 * <p>The debris swath is how wide a path the circulation is working on the ground - the mod's own damage
 * swath, which is what a photograph of a tornado in a field is actually showing when it shows trees and soil
 * in the air. It is a measurement rather than a score: {@link PhotoScore#debrisFraction(double)} is where it
 * becomes marks.
 *
 * <p>Where the subject sits in the frame is also available without a frame - see the static
 * {@link #offAxisFraction(double, double, double, double, double, double, double, double, double, double)} and
 * the two signed readings beside it - because the live viewfinder has to measure a subject before there is a
 * frame to measure it in, and a viewfinder whose idea of the frame differed from the score's would be worse
 * than no viewfinder at all.
 *
 * <p>The two derived quantities are the whole point of the class. {@link #offAxisFraction()} is where the
 * subject sits across the frame, measured in half-frames: 0 is dead centre, 1 is exactly on the edge, and
 * anything past 1 is behind the photographer. {@link #occlusionFraction()} is how much of the line of sight
 * is blocked, which is what stops a photograph of a hill with a tornado behind it.
 */
public record PhotoFrame(
        String targetKey,
        String targetLabel,
        double targetX,
        double targetY,
        double targetZ,
        double stormMph,
        double funnelWidthBlocks,
        double debrisSwathBlocks,
        double coreRadiusBlocks,
        double structureStrength,
        double cameraX,
        double cameraY,
        double cameraZ,
        double dirX,
        double dirY,
        double dirZ,
        double fovDegrees,
        double maxDistanceBlocks,
        double sweetSpotMinBlocks,
        double sweetSpotMaxBlocks,
        double minSubjectMph,
        int occludedSamples,
        int totalSamples,
        boolean insideCore,
        boolean raining,
        boolean lightningInFrame,
        long dayTime
) {
    /** Ticks in an hour of Minecraft's clock, which runs 24 hours across 24000 ticks. */
    public static final long TICKS_PER_HOUR = 1000L;
    /** Ticks in the whole day. */
    public static final long TICKS_PER_DAY = 24000L;
    /**
     * The hour Minecraft's clock starts at. Tick 0 is six in the morning, so a curve read straight off the
     * tick count would put sunrise at midnight and golden hour in the middle of the night.
     */
    public static final double DAY_START_HOUR = 6.0D;

    /** How far the subject is from the camera, in blocks. */
    public double distanceBlocks() {
        double dx = targetX - cameraX;
        double dy = targetY - cameraY;
        double dz = targetZ - cameraZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Where the subject sits across the frame, in half-frames: 0 dead centre, 1 on the edge of the view, more
     * than 1 out of shot. A camera pointing exactly at nothing is treated as pointing away from everything.
     */
    public double offAxisFraction() {
        return offAxisFraction(targetX, targetY, targetZ, cameraX, cameraY, cameraZ, dirX, dirY, dirZ, fovDegrees);
    }

    /**
     * The same reading off a frame's own numbers rather than off a frame, so the live viewfinder and the score
     * cannot disagree about where a subject sits: both come through here, and the only things this needs are the
     * camera's aim and the half-angle of its lens. The viewfinder has to measure a subject before there is a
     * frame at all, which is why this is public rather than private.
     */
    public static double offAxisFraction(double targetX, double targetY, double targetZ,
                                         double cameraX, double cameraY, double cameraZ,
                                         double dirX, double dirY, double dirZ, double fovDegrees) {
        double dx = targetX - cameraX;
        double dy = targetY - cameraY;
        double dz = targetZ - cameraZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double length = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (distance < 1.0E-4D || length < 1.0E-6D) {
            return Double.MAX_VALUE;
        }
        double dot = (dx * dirX + dy * dirY + dz * dirZ) / (distance * length);
        return Math.acos(Math.max(-1.0D, Math.min(1.0D, dot))) / halfFrameRadians(fovDegrees);
    }

    /** The half-angle of a lens, in radians, floored so a lens of nothing is not a division by nothing. */
    public static double halfFrameRadians(double fovDegrees) {
        return Math.toRadians(Math.max(1.0D, fovDegrees) * 0.5D);
    }

    /**
     * Where the subject sits across the frame sideways, in half-frames and signed: positive to the right of the
     * aim, negative to the left, past 1 either way off the side of the view. This is the reading a viewfinder can
     * put a mark on, and it is the same cone the score measures - the frame is treated as a square one, so a lens
     * this mod scores through is a lens this mod can draw a reticle for.
     */
    public static double horizontalFraction(double targetX, double targetY, double targetZ,
                                           double cameraX, double cameraY, double cameraZ,
                                           double dirX, double dirY, double dirZ, double fovDegrees) {
        double[] frame = screenComponents(targetX, targetY, targetZ, cameraX, cameraY, cameraZ, dirX, dirY, dirZ);
        return frame == null ? Double.MAX_VALUE : frame[0] / halfFrameRadians(fovDegrees);
    }

    /** The same, vertically: positive above the aim, negative below it. */
    public static double verticalFraction(double targetX, double targetY, double targetZ,
                                          double cameraX, double cameraY, double cameraZ,
                                          double dirX, double dirY, double dirZ, double fovDegrees) {
        double[] frame = screenComponents(targetX, targetY, targetZ, cameraX, cameraY, cameraZ, dirX, dirY, dirZ);
        return frame == null ? Double.MAX_VALUE : frame[1] / halfFrameRadians(fovDegrees);
    }

    /**
     * The subject's angle across the frame and up it, in radians, measured on the camera's own basis, or null
     * when there is no sightline to measure. Both signed readings come from here so they cannot drift apart, and
     * the basis is built rather than assumed: a camera pointing straight up or down has no right hand, so one is
     * chosen for it instead of the arithmetic collapsing to nothing.
     */
    private static double[] screenComponents(double targetX, double targetY, double targetZ,
                                             double cameraX, double cameraY, double cameraZ,
                                             double dirX, double dirY, double dirZ) {
        double length = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (length < 1.0E-6D) {
            return null;
        }
        double forwardX = dirX / length;
        double forwardY = dirY / length;
        double forwardZ = dirZ / length;
        // The aim crossed with the world's own up, which is the camera's right hand: looking east, that is
        // south. Looking straight up or down it collapses, and one is chosen instead.
        double rightX = -forwardZ;
        double rightY = 0.0D;
        double rightZ = forwardX;
        double rightLength = Math.sqrt(rightX * rightX + rightY * rightY + rightZ * rightZ);
        if (rightLength < 1.0E-6D) {
            rightX = 1.0D;
            rightY = 0.0D;
            rightZ = 0.0D;
            rightLength = 1.0D;
        }
        rightX /= rightLength;
        rightY /= rightLength;
        rightZ /= rightLength;
        double upX = rightY * forwardZ - rightZ * forwardY;
        double upY = rightZ * forwardX - rightX * forwardZ;
        double upZ = rightX * forwardY - rightY * forwardX;
        double dx = targetX - cameraX;
        double dy = targetY - cameraY;
        double dz = targetZ - cameraZ;
        double along = dx * forwardX + dy * forwardY + dz * forwardZ;
        if (along <= 1.0E-6D) {
            // Behind the camera: the aim is what decides that, and the score reads it off the angle, so this
            // reports the corner rather than a mirrored position that would look like a subject in frame.
            return new double[] { Math.PI, Math.PI };
        }
        double across = dx * rightX + dy * rightY + dz * rightZ;
        double above = dx * upX + dy * upY + dz * upZ;
        return new double[] { Math.atan2(across, along), Math.atan2(above, along) };
    }

    /** How much of the line of sight is blocked, from 0 (clear) to 1 (the subject is behind something). */
    public double occlusionFraction() {
        if (totalSamples <= 0) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, occludedSamples / (double) totalSamples));
    }

    /** The hour of the Minecraft day this frame was measured at, 0 to 24. */
    public double hourOfDay() {
        return hourOfDay(dayTime);
    }

    /**
     * The hour a day tick count falls on, 0 to 24, with tick 0 landing on six in the morning. Static because a
     * filed photograph keeps its day time and not the frame it was measured in, and there must be exactly one
     * place where the clock is turned into an hour of the day.
     */
    public static double hourOfDay(long dayTime) {
        double hours = (Math.floorMod(dayTime, TICKS_PER_DAY) / (double) TICKS_PER_HOUR + DAY_START_HOUR) % 24.0D;
        return hours < 0.0D ? hours + 24.0D : hours;
    }
}
