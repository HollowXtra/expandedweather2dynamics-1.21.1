package com.imbet.weather;

/**
 * When a squall line stops being a squall line and becomes a derecho.
 *
 * <p>A derecho is not a different storm. It is the same line of thunderstorms, organised the same way, that
 * happens to be long-lived, fast-moving and violent enough to have its own name - and the name matters
 * because the hazard changes with it. An ordinary squall line has a gust front; a derecho is a wall of wind
 * that runs for hours and lays down a swath of flattened forest, and the reason it is worth classifying is
 * that the damage it does is to *stands* rather than to points. That is why this is a classification and not
 * a spawner: nothing new appears in the world, a line is recognised for what it has become, and the systems
 * that already act on lines start acting like it.
 *
 * <p>The real criteria are SPC's: a swath of wind damage at least 400 km long, gusts of 58 mph or more along
 * most of it, and several reports of 75 mph or better. The wind half of that is kept exactly - miles per hour
 * are miles per hour and this mod reports real ones - and it is carried by the ladder alone: a line below
 * {@link #C1_AT_MPH} is not a derecho, and 75 mph contains SPC's own 58 mph gust floor inside it, so there is
 * one wind criterion here rather than two that could ever disagree. The distance half cannot be kept. A 400 km
 * swath is
 * 400,000 blocks, which no Minecraft world contains: the mod's own lines top out at 1,280 blocks of length
 * and its fastest move 0.16 blocks a tick, so the longest swath a line can possibly leave here is about
 * 5,000 blocks. So the swath threshold is set to the largest distance a line in this world can actually
 * cover - 6,000 blocks, the shape of the criterion held to the scale of the world it is measured in - and
 * that is the one place real numbers were not available to copy.
 *
 * <p>This class is arithmetic only - no Minecraft types - so the ladder can be checked outside the game. See
 * {@code .freebuff/scratch/Phase3Sim.java}.
 */
public final class DerechoModel {

    private DerechoModel() {
    }

    /**
     * Which of the three shapes of derecho this line is. The distinction is real and it is about how many
     * bow echoes make up the line: a progressive derecho is one bow on a long line, a serial derecho is
     * several bow echoes strung along it and leaves the longest, widest swath, and a hybrid is the case in
     * between that the forecasters named precisely because it kept happening.
     */
    public enum Type {
        NONE("Squall line"),
        PROGRESSIVE("Progressive derecho"),
        HYBRID("Hybrid derecho"),
        SERIAL("Serial derecho");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** The wind ladder. C1 is a derecho at all; C3 is the one that takes a forest down to stumps. */
    public enum Category {
        NONE(0.0D),
        C1(75.0D),
        C2(95.0D),
        C3(115.0D);

        private final double windFloorMph;

        Category(double windFloorMph) {
            this.windFloorMph = windFloorMph;
        }

        public double windFloorMph() {
            return windFloorMph;
        }

        public int rank() {
            return ordinal();
        }

        public String label() {
            return this == NONE ? "no derecho" : "derecho C" + rank();
        }
    }

    /** The verdict for one line, as of one tick. */
    public record Verdict(Type type, Category category, double swathBlocks, double windFloorMph) {
        public boolean derecho() {
            return type != Type.NONE && category != Category.NONE;
        }

        /** What a warning would say this is. */
        public String label() {
            return derecho() ? type.label() + " (" + category.label() + ")" : Type.NONE.label();
        }
    }

    /**
     * How long a swath has to be. See the class note: this is SPC's 400 km rescaled to the largest distance a
     * line in this world can travel, which is the only number here that had to be chosen rather than copied.
     * It is the default the config ships with; the dial is {@code derecho.swathBlocks}.
     */
    public static final double SWATH_MIN_BLOCKS = 6000.0D;
    /** A line that is barely organised is not a derecho, whatever the numbers say. */
    public static final double MIN_LINE_LENGTH_BLOCKS = 480.0D;

    /** Where the categories split, on the line's own peak wind. */
    public static final double C1_AT_MPH = 75.0D;
    public static final double C2_AT_MPH = 95.0D;
    public static final double C3_AT_MPH = 115.0D;

    /** How many cells make a line serial rather than progressive, and where the length split sits. */
    public static final int SERIAL_CELLS = 6;
    public static final double SERIAL_LENGTH_BLOCKS = 900.0D;
    public static final int PROGRESSIVE_MAX_CELLS = 3;
    public static final double PROGRESSIVE_MAX_LENGTH_BLOCKS = 620.0D;

    /** How much darker a derecho's shelf reads than the same line without the label. */
    public static final double ARCUS_AT_C1 = 1.15D;
    public static final double ARCUS_AT_C2 = 1.30D;
    public static final double ARCUS_AT_C3 = 1.45D;

    /**
     * The wind anything on the ground has to feel before a derecho takes it at all. Bending is not
     * breaking: below this the line blows through a stand or over a roof and leaves both where they are.
     */
    public static final double DAMAGE_WIND_FLOOR_MPH = 70.0D;
    /** The odds one tree in the swath is laid over on one pass, at the category's own floor wind. */
    public static final double FLATTEN_AT_C1 = 0.05D;
    public static final double FLATTEN_AT_C2 = 0.11D;
    public static final double FLATTEN_AT_C3 = 0.18D;

    /**
     * The odds a weak piece of roof covering comes off on one pass at a category's floor wind. Weak is the
     * operative word and it is what this whole hazard is about: a derecho takes shingles, planks, slabs and
     * panes, and leaves a slate or a stone roof alone - which is also the reason it is worth building one.
     */
    public static final double ROOF_AT_C1 = 0.06D;
    public static final double ROOF_AT_C2 = 0.13D;
    public static final double ROOF_AT_C3 = 0.21D;
    /** Thin covering - a pane, a trapdoor, a carpet - goes half again as easily as a plank. */
    public static final double THIN_COVER_MULTIPLIER = 1.5D;

    /** How many cells one pass of each kind reads. */
    public static final int FLATTEN_ATTEMPTS_AT_C1 = 4;
    public static final int FLATTEN_ATTEMPTS_AT_C2 = 8;
    public static final int FLATTEN_ATTEMPTS_AT_C3 = 14;

    /**
     * The two thresholds the classification is written against, so that the config owns the dial and the model
     * owns the arithmetic rather than each holding a copy of the other's number. {@link #DEFAULT} is what this
     * class calls a derecho on its own, and it is what the config's own defaults mirror.
     */
    public record Limits(double swathBlocks, double minLineLengthBlocks) {
        public static final Limits DEFAULT = new Limits(SWATH_MIN_BLOCKS, MIN_LINE_LENGTH_BLOCKS);
    }

    /**
     * The verdict for a line, from measurements of it rather than estimates: how long it is, how hard it is
     * blowing, how far it has actually travelled, and how many storms are in it.
     *
     * <p>{@code travelledBlocks} is the swath measurement and it is accumulated by the line itself as it moves
     * - a line that has crossed six thousand blocks has left a six-thousand-block swath behind it, whatever
     * its speed did in the meantime, which is why this is a distance and not a speed times an age. That also
     * carries the criterion's duration half: a distance cannot be covered without taking the time to cover it,
     * and there is no separate age test here because one would only ever agree with this one.
     */
    public static Verdict classify(Limits limits, double lineLengthBlocks, double windMph, double travelledBlocks,
                                   int cells) {
        Limits bounds = limits == null ? Limits.DEFAULT : limits;
        double swath = Math.max(0.0D, travelledBlocks);
        Type type = typeOf(lineLengthBlocks, cells);
        Category category = categoryOf(windMph);
        boolean derecho = type != Type.NONE
                && category != Category.NONE
                && swath >= bounds.swathBlocks()
                && lineLengthBlocks >= bounds.minLineLengthBlocks();
        if (!derecho) {
            return new Verdict(Type.NONE, Category.NONE, swath, 0.0D);
        }
        return new Verdict(type, category, swath, category.windFloorMph());
    }

    /**
     * The strongest of two verdicts, rung by rung on both ladders. A derecho is recognised once and keeps its
     * name: the swath it has already laid down cannot be un-laid by the line slowing down afterwards, so the
     * classification ratchets and only ever climbs.
     */
    public static Verdict strongest(Verdict first, Verdict second) {
        if (first == null) {
            return second == null ? new Verdict(Type.NONE, Category.NONE, 0.0D, 0.0D) : second;
        }
        if (second == null) {
            return first;
        }
        Type type = second.type().ordinal() > first.type().ordinal() ? second.type() : first.type();
        Category category = second.category().ordinal() > first.category().ordinal() ? second.category() : first.category();
        double swath = Math.max(first.swathBlocks(), second.swathBlocks());
        return new Verdict(type, category, swath, category.windFloorMph());
    }

    /** The shape, from how long the line is and how many storms are in it. */
    public static Type typeOf(double lineLengthBlocks, int cells) {
        if (cells >= SERIAL_CELLS || lineLengthBlocks >= SERIAL_LENGTH_BLOCKS) {
            return Type.SERIAL;
        }
        if (cells <= PROGRESSIVE_MAX_CELLS && lineLengthBlocks <= PROGRESSIVE_MAX_LENGTH_BLOCKS) {
            return Type.PROGRESSIVE;
        }
        return Type.HYBRID;
    }

    /** The rung, from the line's own peak wind. */
    public static Category categoryOf(double windMph) {
        if (windMph >= C3_AT_MPH) {
            return Category.C3;
        }
        if (windMph >= C2_AT_MPH) {
            return Category.C2;
        }
        if (windMph >= C1_AT_MPH) {
            return Category.C1;
        }
        return Category.NONE;
    }

    /** How much a derecho darkens the underside of its own shelf. */
    public static double arcusDarkening(Category category) {
        return switch (category) {
            case NONE -> 1.0D;
            case C1 -> ARCUS_AT_C1;
            case C2 -> ARCUS_AT_C2;
            case C3 -> ARCUS_AT_C3;
        };
    }

    /**
     * The odds a trunk in the swath is laid over on one pass. There is nothing at all below the flatten
     * floor - a derecho bends what it does not break, and the trees it leaves standing are the ones it only
     * blew through - and the odds rise with both the category and how much harder than the floor the local
     * wind is.
     */
    public static double treeFlattenChance(Category category, double localMph, double peakMph) {
        double ladder = switch (category) {
            case NONE -> 0.0D;
            case C1 -> FLATTEN_AT_C1;
            case C2 -> FLATTEN_AT_C2;
            case C3 -> FLATTEN_AT_C3;
        };
        if (ladder <= 0.0D || localMph < DAMAGE_WIND_FLOOR_MPH) {
            return 0.0D;
        }
        return clamp(ladder * windRamp(localMph, peakMph), 0.0D, 1.0D);
    }

    /**
     * The odds a weak roof covering comes off on one pass. It goes by the same wind floor as a tree, for
     * the same reason: a shingle is held down, and a covering that survives the gust is still a covering.
     */
    public static double roofStripChance(Category category, boolean thinCover, double localMph, double peakMph) {
        double ladder = switch (category) {
            case NONE -> 0.0D;
            case C1 -> ROOF_AT_C1;
            case C2 -> ROOF_AT_C2;
            case C3 -> ROOF_AT_C3;
        };
        if (ladder <= 0.0D || localMph < DAMAGE_WIND_FLOOR_MPH) {
            return 0.0D;
        }
        return clamp(ladder * (thinCover ? THIN_COVER_MULTIPLIER : 1.0D) * windRamp(localMph, peakMph), 0.0D, 1.0D);
    }

    /**
     * How much of a rung's odds the local wind is worth, 0.55 at the damage floor rising to 1.0 where the
     * line is blowing its hardest. Both hazards scale by it, so a pass through the edge of a swath is
     * gentler than one under the core for the same reason on a trunk and on a shingle.
     */
    private static double windRamp(double localMph, double peakMph) {
        double span = Math.max(1.0D, peakMph - DAMAGE_WIND_FLOOR_MPH);
        double share = clamp((localMph - DAMAGE_WIND_FLOOR_MPH) / span, 0.0D, 1.0D);
        return 0.55D + 0.45D * share;
    }

    /** How many cells one pass of each hazard reads. */
    public static int flattenAttempts(Category category) {
        return switch (category) {
            case NONE -> 0;
            case C1 -> FLATTEN_ATTEMPTS_AT_C1;
            case C2 -> FLATTEN_ATTEMPTS_AT_C2;
            case C3 -> FLATTEN_ATTEMPTS_AT_C3;
        };
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }
}
