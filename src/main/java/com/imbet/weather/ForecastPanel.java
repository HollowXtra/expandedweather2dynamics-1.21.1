package com.imbet.weather;

/**
 * The forecast display's readout: the panel the numbers stand on, and the arithmetic that decides how big
 * it is, which way it faces, and how much of the plate's colour the plate's own fills keep.
 *
 * <p>The readout used to be printed flat on the plate. The plate is a map and a map belongs flat - an
 * outlook's areas, a warning's shape and a storm's position are all read by looking down at them - but text
 * does not: a line lying in the horizontal plane is foreshortened by exactly the angle it is read from, so
 * the same six lines were legible from directly overhead and a smear from anywhere a player actually
 * stands. At the shipped scale the numbers were worse than foreshortened, they were too small to begin
 * with: nine font pixels at a scale of 0.005 is a line about four and a half centimetres tall on a plate
 * three blocks across, which is the size of a fingernail on a table. Standing the text up and turning it
 * to face the player is the whole change: the panel takes the camera's yaw and never its pitch, so it is
 * upright whatever height the player is at, and the glyphs land on the screen instead of being squashed
 * onto a plane.
 *
 * <p>Two numbers decide how it reads, and both are honest compromises rather than physics:
 *
 * <ul>
 *   <li>the scale grows with distance, about a tenth per block past two and no further than two and a half
 *       times the near scale. A panel that held a constant angular size would be legible at any
 *       distance and would also be five blocks across at ten, which is a billboard, not a readout. This one
 *       keeps the same screen size out to about seventeen blocks and then shrinks away, so it is legible
 *       from where a player stands to read it and a coloured shape with a title from where they only glance
 *       at it. That is the stated intent, not a limitation to apologise for;</li>
 *   <li>the lift of 0.62 blocks is not taste either. The plate's own radar icons are billboards centred on
 *       the plate and reach about 0.46 blocks above it, so a panel standing lower would be drawn through
 *       them from most angles.</li>
 * </ul>
 *
 * <p>The last three methods are about the plate rather than the panel: the fills on it used to be solid
 * enough to hide what was under them, which is what a nested outlook wants and also what turned the whole
 * sheet - and the ground around it - into one colour. The fill now sits low and the category's outline
 * carries the reading, so as the wash recedes the edge earns alpha and width back. {@link #edgeAlpha} and
 * {@link #edgeWidth} are that trade written down rather than left in a renderer as two literals.
 *
 * <p>No Minecraft types anywhere in here: the renderer measures the font, hands in pixel widths and
 * distances, and does nothing with the numbers but put vertices where they say.
 */
public final class ForecastPanel {

    /**
     * A font line is nine pixels, and the next one starts eleven down: the nine it occupies and two of air.
     *
     * <p>The two pixels are the whole of the line spacing's job. A row of glyphs drawn nine pixels apart from
     * the next row is a block of text with no gaps in it at all, which is legible in a screenshot taken side-on
     * and a smear at any distance a player reads a panel from, because the glyphs of one line and the ascenders
     * of the next are then the same pixels. The readout this replaces used ten and looked like that.
     */
    public static final int LINE_LEADING_PX = 11;

    /** One font line's own height, which is what the row above it has to clear. */
    public static final int GLYPH_HEIGHT_PX = 9;

    /** The band across the top of the panel that carries the outlook's own colour and its label. */
    public static final int HEADER_BAND_PX = 14;

    /**
     * The rule under that band.
     *
     * <p>The band is a colour and the body is a column of numbers, and on a bright category - the general
     * thunder green the plate itself is drawn in - a band and a first line of body text sit on almost the same
     * value, so the eye has to find the join for itself. One pixel of border colour states it instead.
     */
    public static final int HEADER_RULE_PX = 1;

    /** The gap between that band (and its rule) and the first body line. */
    public static final int BAND_GAP_PX = 3;

    /** Margin inside the panel's edge, on both sides and at the bottom. */
    public static final int PAD_PX = 5;

    /** Where the header's glyphs sit inside its band, so a nine-pixel line is centred in fourteen. */
    public static final int HEADER_TEXT_Y_PX = 3;

    /** A panel never gets narrower than this, so a two-line readout is still a panel rather than a strip. */
    public static final int MIN_WIDTH_PX = 112;

    /** Nor wider than this, which is also the width the lines are fitted to. */
    public static final int MAX_WIDTH_PX = 190;

    /**
     * How many rows the panel draws, header included: the risk readout's six, the track readout's seven, and a
     * row for the lines it could not fit rather than a seventh that is quietly dropped.
     */
    public static final int MAX_LINES = 9;

    /**
     * The near text scale: a seventh up on the 0.0060 this replaced, because a panel is read at arm's length
     * from a plate on the ground and nine pixels of glyph at that scale is about a pixel and a half of screen.
     */
    public static final float TEXT_SCALE_NEAR = 0.0069F;

    /** Distance at which the panel is at its near scale, and the growth past it stops rising. */
    public static final double NEAR_BLOCKS = 2.0D;
    public static final double GROWTH_PER_BLOCK = 0.10D;

    /** Two and a half times the near scale, so a panel glanced at from across a field is still read as words. */
    public static final double MAX_GROWTH = 2.5D;

    /** How far the top of the panel leans away from the viewer, in degrees. */
    public static final double LEAN_DEGREES = 18.0D;

    /** How far the panel's bottom edge stands above the plate, in blocks. */
    public static final float BOTTOM_LIFT_BLOCKS = 0.62F;

    /**
     * How far the panel stands back from the plate's centre, in blocks.
     *
     * <p>It is not centred because the plate is a map: a panel standing on the middle of it covers the
     * middle, which is where a storm being tracked usually is. Standing it back means the player reads the
     * map with the readout behind it rather than through it, and 0.85 blocks keeps the panel inside the
     * plate's own three-block footprint - it is the plate that overhangs the block, not the panel that
     * overhangs the plate.
     */
    public static final float FAR_SIDE_BLOCKS = 0.85F;

    /** The range the scale knob is held to, matching the knob's own bounds. */
    public static final double MIN_MULTIPLIER = 0.5D;
    public static final double MAX_MULTIPLIER = 2.0D;

    /** The alpha a category's edge carries on a solid plate, and on a plate with no fill at all. */
    public static final int EDGE_ALPHA_SOLID_FILL = 210;
    public static final int EDGE_ALPHA_BARE = 252;

    /** How much wider the edge gets, as a fraction of itself, over that same span. */
    public static final double EDGE_WIDTH_GAIN = 0.45D;

    /** Luminance, out of 255, above which a band takes dark ink rather than light. */
    public static final double INK_LUMINANCE_THRESHOLD = 150.0D;

    /** The two inks the header band can carry. */
    public static final int LIGHT_INK = 0xFFF7FBFC;
    public static final int DARK_INK = 0xFF0B1114;

    private ForecastPanel() {
    }

    /**
     * The lines below the header: one fewer than the rows given, since the first is the band's label, and
     * never more than the panel draws.
     */
    public static int bodyLines(int lineCount) {
        return Math.max(0, Math.min(lineCount, MAX_LINES) - 1);
    }

    /**
     * The rows a readout actually puts on the panel, with the ones that did not fit said rather than dropped.
     *
     * <p>The panel used to cut a longer readout off at its own maximum and draw nothing to say so - a readout
     * that had eight lines and a panel that drew seven read exactly like a readout with seven lines. The track
     * readout is the one that could reach that, and a tracker that silently stops listing tracks is worse than
     * one that says how many it left out. The last row counts them, and it is a row like any other: the panel is
     * sized for it by {@link #panelHeightPx}, so nothing here is drawn past the panel's own bottom edge.
     */
    public static String[] rows(String[] lines) {
        if (lines == null || lines.length == 0) {
            return new String[0];
        }
        if (lines.length <= MAX_LINES) {
            return lines.clone();
        }
        String[] rows = new String[MAX_LINES];
        System.arraycopy(lines, 0, rows, 0, MAX_LINES - 1);
        rows[MAX_LINES - 1] = overLine(lines.length - (MAX_LINES - 1));
        return rows;
    }

    /** What that row says, so the count is arithmetic rather than a string built inside a renderer. */
    public static String overLine(int leftOut) {
        return "+" + Math.max(0, leftOut) + " more";
    }

    /** Whether a readout has more lines than the panel draws, and so carries an {@link #overLine} row. */
    public static boolean overflows(int lineCount) {
        return Math.max(0, lineCount) > MAX_LINES;
    }

    /** The panel's width in font pixels, hugging the widest line it has to draw. */
    public static int panelWidthPx(int widestLinePx) {
        return (int) Math.max(MIN_WIDTH_PX, Math.min(MAX_WIDTH_PX, widestLinePx + 2L * PAD_PX));
    }

    /** The panel's height in font pixels: the band, its rule, its gap, the body rows, and the bottom margin. */
    public static int panelHeightPx(int lineCount) {
        return HEADER_BAND_PX + HEADER_RULE_PX + BAND_GAP_PX + bodyLines(lineCount) * LINE_LEADING_PX + PAD_PX;
    }

    /** How tall a body row's own shading is: the leading less the pixel of air it leaves to the next row. */
    public static int rowSpanPx() {
        return LINE_LEADING_PX - 1;
    }

    /** The width the lines are fitted to, which is the panel's own width less its margins. */
    public static int fitWidthPx() {
        return MAX_WIDTH_PX - 2 * PAD_PX;
    }

    /** The y of a body line in the panel, counting the first line below the band and its rule as zero. */
    public static int bodyRowY(int bodyLine) {
        return HEADER_BAND_PX + HEADER_RULE_PX + BAND_GAP_PX + Math.max(0, bodyLine) * LINE_LEADING_PX;
    }

    /**
     * Whether a body row is shaded.
     *
     * <p>Every other row, because the readout's own lines are short - a category, three hazard words and a
     * count - and at a glance the question is which number belongs to which word. A band behind alternate rows
     * answers it without a column of labels that would cost the width the labels need.
     */
    public static boolean stripedRow(int bodyLine) {
        return Math.max(0, bodyLine) % 2 == 1;
    }

    /**
     * The text scale at a distance: the near scale, grown a tenth per block past two and held between one
     * and {@link #MAX_GROWTH} times itself, then scaled by the player's own multiplier.
     *
     * <p>There is no floor on the distance in the growth itself, and there does not need to be: a player
     * standing closer than {@link #NEAR_BLOCKS} makes that term negative, which the hold below clamps back
     * to one - the same answer a floor here would give, without a second copy of the near distance to keep
     * in step with the first.
     */
    public static float scaleFor(double distanceBlocks, double multiplier) {
        double grown = 1.0D + GROWTH_PER_BLOCK * (distanceBlocks - NEAR_BLOCKS);
        double held = Math.max(1.0D, Math.min(MAX_GROWTH, grown));
        double asked = Math.max(MIN_MULTIPLIER, Math.min(MAX_MULTIPLIER, multiplier));
        return (float) (TEXT_SCALE_NEAR * held * asked);
    }

    /** The panel's height in blocks at a given scale. */
    public static float heightBlocks(int lineCount, float scale) {
        return panelHeightPx(lineCount) * scale;
    }

    /** The panel's width in blocks at a given scale. */
    public static float widthBlocks(int widestLinePx, float scale) {
        return panelWidthPx(widestLinePx) * scale;
    }

    /**
     * The lift of the panel's top edge above the plate, in blocks.
     *
     * <p>The lean is why this is not simply the bottom lift plus the height: the panel's local down runs
     * into the world at the cosine of the lean, so a leaning panel's top is lower above the plate than a
     * vertical one's would be - and it is the top that is placed, since the panel is laid out downward from
     * it.
     */
    public static float topLiftBlocks(int lineCount, float scale) {
        return (float) (BOTTOM_LIFT_BLOCKS + Math.cos(Math.toRadians(LEAN_DEGREES)) * heightBlocks(lineCount, scale));
    }

    /**
     * The yaw that turns the panel's own front onto a camera offset horizontally by {@code (dx, dz)}.
     *
     * <p>Degrees anticlockwise from +Z, so a camera due east of the panel reads ninety. This is the
     * compass convention rather than the entity yaw vanilla uses for a camera - {@code atan2(dx, dz)} is the
     * angle whose sine and cosine are the two offsets - and it is what the panel needs, because the
     * composition it goes into is {@code R_Y(faceYaw) * R_X(-lean)} and not a rotation of the view.
     */
    public static double faceYaw(double dx, double dz) {
        double degrees = Math.toDegrees(Math.atan2(dx, dz));
        return degrees < 0.0D ? degrees + 360.0D : degrees;
    }

    /**
     * The x of that standing-back, for a camera offset horizontally by {@code (dx, dz)}: the panel's
     * facing direction runs from its own front towards the viewer, so standing back from the viewer means
     * moving along the negative of it.
     */
    public static double backOffsetX(double faceYaw, double blocks) {
        return -blocks * Math.sin(Math.toRadians(faceYaw));
    }

    /** The z of that standing-back, the same way round as {@link #backOffsetX}. */
    public static double backOffsetZ(double faceYaw, double blocks) {
        return -blocks * Math.cos(Math.toRadians(faceYaw));
    }

    /**
     * The ink a coloured band takes. Both are wrong somewhere and luminance is the cheapest honest way to
     * choose: the outlook's own ladder runs from a pale general-thunder green to a deep marginal green to
     * the high-risk magenta, so no single ink covers it, and a label that vanishes into its own band on two
     * of eight categories is worse than a label that is only right on six.
     */
    public static int headerInk(int bandColor) {
        int red = bandColor >>> 16 & 255;
        int green = bandColor >>> 8 & 255;
        int blue = bandColor & 255;
        double luminance = 0.2126D * red + 0.7152D * green + 0.0722D * blue;
        return luminance >= INK_LUMINANCE_THRESHOLD ? DARK_INK : LIGHT_INK;
    }

    /**
     * The alpha an area's outline carries for a given fill opacity: full strength on a bare plate, and the
     * base strength once the fill is solid enough to read on its own.
     */
    public static int edgeAlpha(double fillOpacity, int solidFill, int bare) {
        double gone = 1.0D - clamp01(fillOpacity);
        return (int) Math.round(solidFill + (bare - solidFill) * gone);
    }

    /** The width that outline carries, in the same units as the plate, for the same reason. */
    public static float edgeWidth(double fillOpacity, float baseWidth) {
        return (float) (baseWidth * (1.0D + EDGE_WIDTH_GAIN * (1.0D - clamp01(fillOpacity))));
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
