package com.imbet.village;

/**
 * Where a building can stand.
 *
 * <p>The two ways a villager's building goes wrong are the two this rules out: a footprint that straddles
 * a slope, so half the walls hang over a drop, and a footprint full of something already there, so the
 * builder either buries a tree or stops half way with a wall behind it. So the site is asked for its
 * ground under every column and whether the space above that ground is free, and the answer is one of a
 * handful of numbers rather than a judgement.
 *
 * <p>Minecraft-free: the goal probes the world into these arrays and everything after that is arithmetic.
 */
public final class BuildSite {
    private BuildSite() {
    }

    /**
     * The ground under a footprint, in row-major order: {@code heights[dz * width + dx]} is the Y of the top
     * solid block under that column, and zero means the column could not be read at all.
     */
    public record Ground(int width, int depth, int[] heights, boolean[] blocked) {
        public Ground {
            if (heights.length != width * depth || blocked.length != width * depth) {
                throw new IllegalArgumentException("a footprint is width * depth cells");
            }
        }

        public int height(int dx, int dz) {
            return heights[dz * width + dx];
        }

        public boolean isBlocked(int dx, int dz) {
            return blocked[dz * width + dx];
        }

        public int lowest() {
            int lowest = Integer.MAX_VALUE;
            for (int height : heights) {
                lowest = Math.min(lowest, height);
            }
            return lowest;
        }

        public int highest() {
            int highest = Integer.MIN_VALUE;
            for (int height : heights) {
                highest = Math.max(highest, height);
            }
            return highest;
        }
    }

    /**
     * Whether a building fits here: every column read, the ground rising or falling no more than
     * {@code maxRise} across the whole footprint, and nothing standing in the space it needs.
     *
     * <p>The rise matters more than it sounds. A house laid on a slope is a house with one wall buried and
     * another floating, and neither is something a builder can fix later by adding blocks.
     */
    public static boolean fits(Ground ground, int maxRise) {
        if (ground.lowest() <= 0) {
            return false;
        }
        if (ground.highest() - ground.lowest() > Math.max(0, maxRise)) {
            return false;
        }
        for (boolean blocked : ground.blocked()) {
            if (blocked) {
                return false;
            }
        }
        return true;
    }

    /**
     * The Y the building's own ground layer goes at: the lowest ground under the footprint, so the
     * foundation always has something under it and any soil that ends up inside it is soil that was
     * already there.
     */
    public static int originY(Ground ground) {
        return ground.lowest();
    }

    /** Whether two footprints share a column, so a village does not build one house inside another. */
    public static boolean overlaps(int ax, int az, int aw, int ad, int bx, int bz, int bw, int bd) {
        boolean apartX = ax + aw <= bx || bx + bw <= ax;
        boolean apartZ = az + ad <= bz || bz + bd <= az;
        return !apartX && !apartZ;
    }
}
