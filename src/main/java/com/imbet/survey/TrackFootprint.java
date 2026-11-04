package com.imbet.survey;

import java.util.HashSet;
import java.util.Set;

/**
 * The ground a recent storm's own track covered, as a set of coarse cells.
 *
 * <p>This exists because of one specific, very common false positive: a vanilla village is built out of
 * {@code dirt_path} roads and stripped-log corners, which are exactly the two things a tornado leaves
 * behind, and a repair rule that only looks at a block cannot tell a village's own street from the swath.
 * A track can. Damage is what a storm's track touched, so the builder asks this - and only this - when it
 * is deciding whether a stripped trunk is a fallen tree or a house it is about to take apart.
 *
 * <p>Cells rather than points because a track is a long polyline and a scan asks the question hundreds of
 * times: the track contributes its cells once, and every later question is a set lookup. The cell size is
 * the precision that is deliberately given up - a block within half a cell of the swath counts as inside
 * it, which is the safe direction for a decision about whether to touch somebody's building.
 *
 * <p>Minecraft-free, so the arithmetic is measured rather than trusted.
 */
public final class TrackFootprint {
    private final int cellSize;
    private final Set<Long> cells = new HashSet<>();

    public TrackFootprint(int cellSize) {
        if (cellSize < 1) {
            throw new IllegalArgumentException("a footprint's cells are at least one block");
        }
        this.cellSize = cellSize;
    }

    public int cellSize() {
        return cellSize;
    }

    /** How many cells the footprint covers. Zero means no track: no evidence either way, not a verdict. */
    public int cellCount() {
        return cells.size();
    }

    /**
     * Mark the ground a track point covers: the point itself and, either side of it, half the swath the
     * storm was cutting at that moment - a track records its own width, so a wide wedge leaves a wide
     * footprint and a ropey one leaves a narrow one.
     */
    public void add(double x, double z, double width) {
        double half = Math.max(0.0D, width) * 0.5D;
        int minX = cell(x - half);
        int maxX = cell(x + half);
        int minZ = cell(z - half);
        int maxZ = cell(z + half);
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                cells.add(key(cx, cz));
            }
        }
    }

    /** Whether a block of ground is inside the ground the storm's track covered. */
    public boolean covers(double x, double z) {
        return cells.contains(key(cell(x), cell(z)));
    }

    private int cell(double coordinate) {
        return Math.floorDiv((int) Math.floor(coordinate), cellSize);
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }
}
