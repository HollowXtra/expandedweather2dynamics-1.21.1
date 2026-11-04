package com.imbet.survey;

/**
 * The counters a damage survey produces and the rating they add up to.
 *
 * <p>Extracted from the surveyor item so that a player holding it and a surveyor villager walking the
 * same ground cannot report different numbers: they count through this class and they rate through
 * this class, and the arithmetic is the same code rather than the same intention. It is free of
 * Minecraft types - callers classify the blocks and pass booleans - which is what lets the estimator
 * be measured outside the game.
 *
 * <p>The rating is an in-game estimate of the wind that would leave this kind of damage, not an
 * official EF rating, and it is floored by the winds the storm actually logged where a track is known.
 */
public final class DamageScan {
    private final int radius;
    private int scouredSurfaceBlocks;
    private int debarkedLogs;
    private int damagedColumns;
    private int minX = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE;
    private int minZ = Integer.MAX_VALUE;
    private int maxZ = Integer.MIN_VALUE;
    private int pathWidthBlocks;
    private int estimatedMph;
    private String confidence = "low";

    public DamageScan(int radius) {
        this.radius = Math.max(0, radius);
    }

    /**
     * A column's surface: scouring is the surface itself being torn up, and it is the indicator with
     * the widest reach, so a scoured block also marks that column as damaged.
     */
    public void recordGround(boolean scouredSurface, int x, int z) {
        if (scouredSurface) {
            scouredSurfaceBlocks++;
            recordColumn(x, z);
        }
    }

    /**
     * A trunk column: debarking is the violent damage indicator, and leaves only count once there is
     * scouring nearby - a village tree being bare is not evidence of a tornado, but a stripped trunk
     * surrounded by torn ground is.
     */
    public void recordTree(boolean debarkedLog, boolean leaves, int x, int z) {
        if (debarkedLog) {
            debarkedLogs++;
            recordColumn(x, z);
        } else if (leaves && scouredSurfaceBlocks > radius) {
            recordColumn(x, z);
        }
    }

    private void recordColumn(int x, int z) {
        damagedColumns++;
        minX = Math.min(minX, x);
        maxX = Math.max(maxX, x);
        minZ = Math.min(minZ, z);
        maxZ = Math.max(maxZ, z);
    }

    public void finish() {
        if (damagedColumns <= 0) {
            pathWidthBlocks = 0;
            estimatedMph = 0;
            confidence = "none";
            return;
        }
        pathWidthBlocks = Math.max(maxX - minX + 1, maxZ - minZ + 1);
        int score = scouredSurfaceBlocks + debarkedLogs * 8 + Math.max(0, Math.min(radius * 2, pathWidthBlocks));
        if (debarkedLogs >= 20 || scouredSurfaceBlocks >= 120 || pathWidthBlocks >= 80) {
            estimatedMph = 170;
        } else if (debarkedLogs >= 6 || scouredSurfaceBlocks >= 55 || pathWidthBlocks >= 48) {
            estimatedMph = 136;
        } else if (debarkedLogs > 0 || scouredSurfaceBlocks >= 24 || pathWidthBlocks >= 28) {
            estimatedMph = 111;
        } else if (score >= 18) {
            estimatedMph = 86;
        } else {
            estimatedMph = 65;
        }
        confidence = damagedColumns >= 80 || debarkedLogs >= 8 ? "high" : damagedColumns >= 25 || debarkedLogs > 0 ? "medium" : "low";
    }

    /**
     * Confidence including what the storm's own track recorded: a long track carrying violent winds is
     * evidence the ground alone cannot show, so a light damage footprint on a logged-violent path is
     * still worth trusting.
     */
    public String confidenceWithTrack(int trackPoints, int loggedMph) {
        if (damagedColumns >= 80 || debarkedLogs >= 8 || trackPoints >= 18 && loggedMph >= 111) {
            return "high";
        }
        if (damagedColumns >= 25 || debarkedLogs > 0 || trackPoints >= 6) {
            return "medium";
        }
        return "low";
    }

    public int rankedMph(int loggedMph) {
        return Math.max(estimatedMph, loggedMph);
    }

    public String efLabel() {
        return efLabelFor(estimatedMph);
    }

    public static String efLabelFor(int estimatedMph) {
        if (estimatedMph <= 0) {
            return "No EF damage";
        }
        if (estimatedMph > 200) {
            return "EF5";
        }
        if (estimatedMph >= 166) {
            return "EF4";
        }
        if (estimatedMph >= 136) {
            return "EF3";
        }
        if (estimatedMph >= 111) {
            return "EF2";
        }
        if (estimatedMph >= 86) {
            return "EF1";
        }
        return "EF0";
    }

    public int scouredSurfaceBlocks() {
        return scouredSurfaceBlocks;
    }

    public int debarkedLogs() {
        return debarkedLogs;
    }

    public int damagedColumns() {
        return damagedColumns;
    }

    public int pathWidthBlocks() {
        return pathWidthBlocks;
    }

    public int estimatedMph() {
        return estimatedMph;
    }

    public String confidence() {
        return confidence;
    }
}
