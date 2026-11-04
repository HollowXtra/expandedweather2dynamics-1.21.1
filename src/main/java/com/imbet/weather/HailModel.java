package com.imbet.weather;

/**
 * How big the stones are, and what each size is allowed to break.
 *
 * <p>The mod has drawn hail since it grew a supercell structure - a hail core strength is computed every
 * tick, a hail shaft is placed in the forward flank, and the volumetric renderer tints the downdraft white
 * with it - but the ice itself has never landed on anything. That is the whole of what this class fixes: a
 * hail core was a number the sky used, and it is now a number the ground reads.
 *
 * <p>Size is the axis, because that is how hail is actually reported and because every consequence falls
 * out of it rather than out of a separate switch. A pea bruises nothing; marble knocks the heads off
 * seedling crops; a dime-star (18 mm) is the first size that reliably breaks a pane and the first that
 * stings; a quarter (25 mm, an inch - the threshold every storm report is written against) damages a bare
 * head and shatters thin glass; golf balls break windows and trample ground; baseballs (70 mm, the largest
 * stones that survive the fall in one piece) take crops to the ground and do real damage to anyone caught
 * out in them. So each verdict here is a function of {@link Size} and nothing else, and the only two inputs
 * anywhere are the strength of the core overhead and the storm's precipitation character.
 *
 * <p>Two things are deliberately *not* modelled as damage. Stones that land on a roof do nothing, which is
 * the shelter rule the rest of this mod already honours - exposure is what matters and exposure is
 * `canSeeSky` at the entity's own column. And a helmet is not immunity: a cap turns a quarter into a
 * bruise, which is why {@link #playerDamage} takes a protection factor rather than a boolean.
 *
 * <p>This class is arithmetic only - no Minecraft types - so the ladder can be checked outside the game.
 * See {@code .freebuff/scratch/Phase2Sim.java}.
 */
public final class HailModel {

    private HailModel() {
    }

    /** The sizes hail is reported in, in the order they are reached. */
    public enum Size {
        NONE(0.0D, 0),
        PEA(6.0D, 1),
        MARBLE(13.0D, 2),
        DIME(18.0D, 3),
        QUARTER(25.0D, 4),
        GOLF_BALL(44.0D, 5),
        BASEBALL(70.0D, 6);

        private final double diameterMm;
        private final int tier;

        Size(double diameterMm, int tier) {
            this.diameterMm = diameterMm;
            this.tier = tier;
        }

        public double diameterMm() {
            return diameterMm;
        }

        public int tier() {
            return tier;
        }

        /** The inch fraction a spotter would write down, for a chat line. */
        public String label() {
            return switch (this) {
                case NONE -> "no hail";
                case PEA -> "pea-sized hail";
                case MARBLE -> "marble-sized hail";
                case DIME -> "dime-sized hail";
                case QUARTER -> "quarter-sized hail";
                case GOLF_BALL -> "golf-ball-sized hail";
                case BASEBALL -> "baseball-sized hail";
            };
        }
    }

    /** What a stone of a given size may do to glass. */
    public enum GlassVerdict {
        /** Nothing: the ice is too small to mark it. */
        NONE,
        /** Thin glass - panes - breaks; a full block of glass holds. */
        CRACK_PANES,
        /** Both panes and glass blocks break. */
        SHATTER
    }

    /**
     * Structure strength below which there is no organised updraft to hold ice aloft, so there is nothing
     * to grow a stone in however hard it is raining.
     */
    public static final double MIN_STRUCTURE_STRENGTH = 0.12D;

    /**
     * The size thresholds, on effective core strength after the character bias. The gaps widen as the sizes
     * grow, because that is how hail sizes are distributed: pea and marble are the ordinary case in any
     * storm with hail in it, a quarter is the size a report is filed about, and a baseball is the outlier
     * that only a storm with a strong, deep, dry updraft produces at all.
     */
    public static final double PEA_AT = 0.06D;
    public static final double MARBLE_AT = 0.16D;
    public static final double DIME_AT = 0.30D;
    public static final double QUARTER_AT = 0.46D;
    public static final double GOLF_BALL_AT = 0.64D;
    public static final double BASEBALL_AT = 0.82D;

    /**
     * How far the storm's precipitation character swings the size. A dry, low-precipitation updraft lets a
     * stone fall the whole way without collecting a coat of water and melting, and a rain-wrapped high
     * precipitation core does the opposite, so character is worth about a third of the size either way.
     */
    public static final double CHARACTER_LOW_BIAS = 0.72D;
    public static final double CHARACTER_SWING = 0.56D;

    /** The footprint of the shaft, as a fraction of the storm's own radius. */
    public static final double SHAFT_RADIUS_BASE = 0.24D;
    public static final double SHAFT_RADIUS_SWING = 0.46D;
    public static final double SHAFT_RADIUS_MIN_BLOCKS = 10.0D;
    public static final double SHAFT_RADIUS_MAX_BLOCKS = 160.0D;

    /** Bare-headed damage per size, in half-hearts. Bigger stones are both bigger and heavier. */
    public static final float QUARTER_DAMAGE = 1.0F;
    public static final float GOLF_BALL_DAMAGE = 2.5F;
    public static final float BASEBALL_DAMAGE = 4.5F;

    /** Mobs take this share of a bare-headed hit: no helmet, and they do not put their arms up. */
    public static final float MOB_DAMAGE_SHARE = 0.6F;

    /**
     * How much a helmet is worth at full config. It is a reduction rather than an immunity: 0.65 leaves a
     * baseball hurting at a third of what it would to a bare head, which is the honest reading of a metal
     * cap in a hailstorm.
     */
    public static final double HELMET_REDUCTION_AT_FULL = 0.65D;

    /** Stones that land on an exposed head roughly this often, per size, in ticks. */
    public static final double QUARTER_HIT_INTERVAL_TICKS = 45.0D;
    public static final double GOLF_BALL_HIT_INTERVAL_TICKS = 30.0D;
    public static final double BASEBALL_HIT_INTERVAL_TICKS = 18.0D;

    /** Above these sizes crops are knocked about rather than bruising; the odds are the chance per pass. */
    public static final double CROP_BREAK_AT_MARBLE = 0.05D;
    public static final double CROP_BREAK_AT_DIME = 0.25D;
    public static final double CROP_BREAK_AT_QUARTER = 0.55D;
    public static final double CROP_BREAK_AT_GOLF_BALL = 0.80D;
    public static final double CROP_BREAK_AT_BASEBALL = 1.00D;

    /** Farmland beaten flat enough to lose its tilth. Nothing below a quarter does this at all. */
    public static final double FARMLAND_LOSS_AT_QUARTER = 0.25D;
    public static final double FARMLAND_LOSS_AT_GOLF_BALL = 0.50D;
    public static final double FARMLAND_LOSS_AT_BASEBALL = 0.75D;

    /** Canopy shredded by the barrage: hail strips foliage long before it takes trunks. */
    public static final double LEAF_SHRED_AT_MARBLE = 0.06D;
    public static final double LEAF_SHRED_AT_DIME = 0.18D;
    public static final double LEAF_SHRED_AT_QUARTER = 0.35D;
    public static final double LEAF_SHRED_AT_GOLF_BALL = 0.50D;
    public static final double LEAF_SHRED_AT_BASEBALL = 0.65D;

    /** Whether this storm has ice in it at all. */
    public static boolean hasHail(double structureStrength, double hailCoreStrength) {
        return structureStrength >= MIN_STRUCTURE_STRENGTH && hailCoreStrength >= PEA_AT;
    }

    /** The size a core of this strength is putting down, given the storm's own precipitation character. */
    public static Size sizeFor(double hailCoreStrength, double character) {
        double effective = effectiveStrength(hailCoreStrength, character);
        if (effective >= BASEBALL_AT) {
            return Size.BASEBALL;
        }
        if (effective >= GOLF_BALL_AT) {
            return Size.GOLF_BALL;
        }
        if (effective >= QUARTER_AT) {
            return Size.QUARTER;
        }
        if (effective >= DIME_AT) {
            return Size.DIME;
        }
        if (effective >= MARBLE_AT) {
            return Size.MARBLE;
        }
        if (effective >= PEA_AT) {
            return Size.PEA;
        }
        return Size.NONE;
    }

    /** The core strength after the character bias, which is what the ladder is written against. */
    public static double effectiveStrength(double hailCoreStrength, double character) {
        double core = clamp(hailCoreStrength, 0.0D, 1.0D);
        double bias = CHARACTER_LOW_BIAS + CHARACTER_SWING * clamp(character, 0.0D, 1.0D);
        return clamp(core * bias, 0.0D, 1.0D);
    }

    /**
     * How wide a swathe the stones land over. Hail falls in the core's own column rather than under the
     * whole storm, and a stronger core has both a bigger shaft and, more importantly, a bigger storm, so
     * this scales with both.
     */
    public static double footprintRadiusBlocks(double hailCoreStrength, double stormRadiusBlocks) {
        double scale = SHAFT_RADIUS_BASE + SHAFT_RADIUS_SWING * clamp(hailCoreStrength, 0.0D, 1.0D);
        return clamp(Math.max(0.0D, stormRadiusBlocks) * scale, SHAFT_RADIUS_MIN_BLOCKS, SHAFT_RADIUS_MAX_BLOCKS);
    }

    public static double diameterMm(Size size) {
        return size.diameterMm();
    }

    /** The chance a crop in the shaft is knocked down on one pass. */
    public static double cropBreakChance(Size size) {
        return switch (size) {
            case NONE, PEA -> 0.0D;
            case MARBLE -> CROP_BREAK_AT_MARBLE;
            case DIME -> CROP_BREAK_AT_DIME;
            case QUARTER -> CROP_BREAK_AT_QUARTER;
            case GOLF_BALL -> CROP_BREAK_AT_GOLF_BALL;
            case BASEBALL -> CROP_BREAK_AT_BASEBALL;
        };
    }

    /** The chance farmland in the shaft loses its tilth on one pass. */
    public static double farmlandLossChance(Size size) {
        return switch (size) {
            case NONE, PEA, MARBLE, DIME -> 0.0D;
            case QUARTER -> FARMLAND_LOSS_AT_QUARTER;
            case GOLF_BALL -> FARMLAND_LOSS_AT_GOLF_BALL;
            case BASEBALL -> FARMLAND_LOSS_AT_BASEBALL;
        };
    }

    /** The chance a leaf block in the shaft is shredded on one pass. */
    public static double leafShredChance(Size size) {
        return switch (size) {
            case NONE, PEA -> 0.0D;
            case MARBLE -> LEAF_SHRED_AT_MARBLE;
            case DIME -> LEAF_SHRED_AT_DIME;
            case QUARTER -> LEAF_SHRED_AT_QUARTER;
            case GOLF_BALL -> LEAF_SHRED_AT_GOLF_BALL;
            case BASEBALL -> LEAF_SHRED_AT_BASEBALL;
        };
    }

    /**
     * What this size does to glass. Thin glass is the weak one and it is the one that goes first: a pane is
     * a couple of millimetres thick with nothing behind it, a glass block is the same sheet but every face
     * of it is supported. So a dime breaks panes only, and it takes a golf ball to break a glass block.
     */
    public static GlassVerdict glassVerdict(Size size) {
        return switch (size) {
            case NONE, PEA, MARBLE -> GlassVerdict.NONE;
            case DIME, QUARTER -> GlassVerdict.CRACK_PANES;
            case GOLF_BALL, BASEBALL -> GlassVerdict.SHATTER;
        };
    }

    /** Damage a stone does to a bare head; zero below the size that stings at all. */
    public static float baseDamage(Size size) {
        return switch (size) {
            case NONE, PEA, MARBLE, DIME -> 0.0F;
            case QUARTER -> QUARTER_DAMAGE;
            case GOLF_BALL -> GOLF_BALL_DAMAGE;
            case BASEBALL -> BASEBALL_DAMAGE;
        };
    }

    /** Damage to a player, with whatever is on their head taken into account. */
    public static float playerDamage(Size size, double helmetProtection) {
        return (float) (baseDamage(size) * damageFactor(helmetProtection));
    }

    /** Damage to anything else caught in the open: no helmet, no arms over the head. */
    public static float mobDamage(Size size) {
        return baseDamage(size) * MOB_DAMAGE_SHARE;
    }

    /** How much of a hit gets through, given the configured worth of a helmet. */
    public static double damageFactor(double helmetProtection) {
        return 1.0D - HELMET_REDUCTION_AT_FULL * clamp(helmetProtection, 0.0D, 1.0D);
    }

    /**
     * The chance that an entity standing in the open is hit on a pass of this length. Written as an
     * exponential per-tick rate rather than a flat per-pass chance so that the number stays honest if the
     * caller's pass length changes.
     */
    public static double hitChancePerPass(Size size, int passTicks) {
        double interval = switch (size) {
            case NONE, PEA, MARBLE, DIME -> 0.0D;
            case QUARTER -> QUARTER_HIT_INTERVAL_TICKS;
            case GOLF_BALL -> GOLF_BALL_HIT_INTERVAL_TICKS;
            case BASEBALL -> BASEBALL_HIT_INTERVAL_TICKS;
        };
        if (interval <= 0.0D || passTicks <= 0) {
            return 0.0D;
        }
        return 1.0D - Math.exp(-passTicks / interval);
    }

    /** Whether this size stings a bare head at all - read by the readout and by the damage pass. */
    public static boolean hurtsPlayers(Size size) {
        return baseDamage(size) > 0.0F;
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }
}
