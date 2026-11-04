package com.imbet.weather;

/**
 * The footprint one squall-line storm's own cloud deck is spawned over, turned from Weather2's
 * square into the line's band.
 *
 * <p>A Weather2 storm's cloud has exactly one shape, and it is set in one place: {@code
 * StormObject.tickRender} spawns the deck's puffs ({@code size} of them, one line of code)
 * uniformly from a square of half-width {@code size} around the storm's own {@code pos}, at
 * {@code pos.y + 30}. Nothing else draws the shape. The puffs are flat - the storm's own drive
 * then snaps every one of them to {@code pos.y} within two blocks and clamps its vertical motion
 * to a tenth of a block per tick - and they simply drift with the storm. So a lone thunderstorm
 * is a round mass, and a squall line seeded as a row of thunderstorms comes out as a row of round
 * masses: the square is the whole silhouette.
 *
 * <p>That leaves the line's shape one honest place to live, the box the storm object samples.
 * Stretched along the front and thinned across it, the same puffs of the same storm become one
 * section of a shelf instead of a disc. The two fractions are chosen so the section's area stays
 * {@link #weather2Area the storm's own}: the deck is not densified, not scaled and not given more
 * or bigger particles, it is the same cloud drawn on a longer, thinner footprint. Because every
 * cell of a line is seeded on the line, every section then shares one front edge and one rear
 * edge, and the sections meet along the length, so the row of discs reads as the single surface.
 *
 * <p>This class is arithmetic only - no Minecraft types - so the geometry can be checked outside
 * the game. See {@code .freebuff/scratch/LineDeckSim.java}.
 */
public final class SquallLineDeck {
    /**
     * Half the section's length along the line, as a multiple of the storm's own size.
     *
     * <p>Set so neighbouring sections overlap rather than merely meet: the server seeds the line's
     * cells across the line's own span, which is 160 blocks apart on the shortest line and 210 on
     * a long one, so a section 1.55 sizes long covers several seedings at any size a cell can
     * carry (180 to 360) and the length has no seams. See {@link #seamOverlap}.
     */
    public static final double ALONG_HALF_FRACTION = 1.55D;
    /**
     * Half the section's depth across the line - how far its mass reaches in front of and behind
     * the cell - as a multiple of the storm's own size.
     *
     * <p>This is the dial that makes a line a line rather than a ceiling: Weather2's own square
     * spreads each cell's cloud over two sizes, so its front and rear edges stand a size out from
     * the cell in every direction, while the same cloud on this footprint stands 0.62 of a size
     * out across the front. The area the pair of fractions gives back is within 4% of the square's
     * (see {@link #densityRatio}), so the sky along the strip holds the cloud density one of
     * Weather2's thunderstorms would have drawn on its own - the cloud does not thicken into a
     * slab just because the front is a front.
     */
    public static final double ACROSS_HALF_FRACTION = 0.62D;
    /**
     * The server's own seed pitch along the line, in blocks, at the spacing it uses on the longest
     * lines. Only used to state the overlap; the sections are placed by the line, not by this.
     */
    public static final double CELL_SPACING_BLOCKS = 210.0D;

    private SquallLineDeck() {
    }

    /**
     * One cell's section of the line's deck: a box centred on the storm's own position, its length
     * along the line's axis and its depth across it.
     */
    public record Band(double centreX, double centreZ, double axisX, double axisZ, double halfAlong, double halfAcross) {
        /** Unit across the line, to the line's left when the axis runs start to end. */
        public double acrossX() {
            return -axisZ;
        }

        public double acrossZ() {
            return axisX;
        }

        /** Absolute x of the point sitting {@code along} and {@code across} from the cell. */
        public double x(double along, double across) {
            return centreX + axisX * along + acrossX() * across;
        }

        public double z(double along, double across) {
            return centreZ + axisZ * along + acrossZ() * across;
        }

        public double length() {
            return 2.0D * halfAlong;
        }

        public double depth() {
            return 2.0D * halfAcross;
        }

        public double area() {
            return 4.0D * halfAlong * halfAcross;
        }

        /** Signed along offset of a uniform draw in [0, 1). */
        public double alongOffset(double u) {
            return SquallLineDeck.alongOffset(this, u);
        }

        /** Signed across offset of a uniform draw in [0, 1). */
        public double acrossOffset(double v) {
            return SquallLineDeck.acrossOffset(this, v);
        }
    }

    /** One cell's section of a line running along {@code (axisX, axisZ)}, centred on the cell. */
    public static Band band(double centreX, double centreZ, double axisX, double axisZ, int size) {
        double length = Math.hypot(axisX, axisZ);
        double ux = length < 1.0E-6D ? 1.0D : axisX / length;
        double uz = length < 1.0E-6D ? 0.0D : axisZ / length;
        int safeSize = Math.max(1, size);
        return new Band(centreX, centreZ, ux, uz,
                ALONG_HALF_FRACTION * safeSize, ACROSS_HALF_FRACTION * safeSize);
    }

    public static double alongOffset(Band band, double u) {
        return (u * 2.0D - 1.0D) * band.halfAlong();
    }

    public static double acrossOffset(Band band, double v) {
        return (v * 2.0D - 1.0D) * band.halfAcross();
    }

    /** The area Weather2's own deck square spreads the same population of puffs over. */
    public static double weather2Area(int size) {
        int safeSize = Math.max(1, size);
        return 4.0D * safeSize * (double) safeSize;
    }

    /**
     * How much denser the section is than the storm's own square: above 1 the same cloud is packed
     * into less sky, above roughly 3 it reads as a slab rather than cloud. Kept at 1 by the pair of
     * fractions above.
     */
    public static double densityRatio(Band band, int size) {
        return weather2Area(size) / band.area();
    }

    /**
     * How much neighbouring sections overlap along the line at a given seed pitch, in blocks.
     * Negative means the sections leave a gap between the cells.
     */
    public static double seamOverlap(Band band, double spacingBlocks) {
        return band.length() - spacingBlocks;
    }
}
