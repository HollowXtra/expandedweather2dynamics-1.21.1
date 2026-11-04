package com.imbet.weather;

import net.minecraft.util.Mth;
import weather2.weathersystem.storm.StormObject;

/**
 * The tropical ladder from Weather2 1.12.2, as a set of pure functions.
 *
 * <p>Weather2 1.12.2 did not have "a storm that is quite strong over water". It had a second storm
 * type. A storm whose biome at genesis was an ocean came out as {@code StormType.WATER} - a
 * hurricane, not a tornado - and ran its own ladder from the same stage numbers:
 *
 * <pre>
 *   NORMAL(0)  RAIN(1)  THUNDER(2)  SEVERE(3)  TORNADO(4)  ...upward
 *   ...and for a water storm:  stage 2 = Tropical Disturbance, 3 = Tropical Depression,
 *                              4 = Tropical Storm, 5+ = Hurricane Category (stage - 4)
 * </pre>
 *
 * <p>Those thresholds land exactly on this port's own rung numbers - the 1.21 port renumbered the
 * tornado rungs so that {@code STATE_FORMING} is still 4 and the storm stages run 5..9 - so the
 * ladder can be carried across unchanged: a water storm at rung 4 is a tropical storm, and the
 * hurricane ladder begins at rung 5 with category 1, ending at category 5 on rung 9.
 *
 * <p>The <em>category</em> here is read from the live wind rather than the rung. 1.12.2 named the
 * category off the stage, but the category a hurricane actually has is a wind measurement - the
 * Saffir-Simpson thresholds - and this mod already labels tornadoes by the EF class their wind
 * earns rather than by their rung. Doing the same for hurricanes keeps the two honest in the same
 * way: a weak hurricane parked on rung 9 does not get to call itself a category 5.
 *
 * <p>Names are derived from the storm's own persistent ID, so both sides and every reload agree
 * without a single byte of new network traffic or save data.
 */
public final class TropicalCyclone {

    /** Rung a water storm becomes a tropical disturbance. 1.12.2 stage 2. */
    public static final int DISTURBANCE_STAGE = StormObject.STATE_HIGHWIND;
    /** Rung a water storm becomes a tropical depression. 1.12.2 stage 3. */
    public static final int DEPRESSION_STAGE = StormObject.STATE_HAIL;
    /** Rung a water storm becomes a tropical storm. 1.12.2 stage 4. */
    public static final int STORM_STAGE = StormObject.STATE_FORMING;
    /** Rung the hurricane ladder begins: category 1. 1.12.2 stage 5. */
    public static final int HURRICANE_STAGE = StormObject.STATE_STAGE1;
    /** Highest category, on the last rung. */
    public static final int MAX_CATEGORY = 5;

    // Saffir-Simpson sustained-wind thresholds in mph.
    private static final double CATEGORY_1_MPH = 74.0D;
    private static final double CATEGORY_2_MPH = 96.0D;
    private static final double CATEGORY_3_MPH = 111.0D;
    private static final double CATEGORY_4_MPH = 130.0D;
    private static final double CATEGORY_5_MPH = 157.0D;

    /**
     * Storm names, in the manner of the basin lists Weather2 1.12.2 drew its hurricanes from. Any
     * list works as long as it is stable: a name is picked by storm ID, so it never changes for the
     * life of the storm or across a reload.
     */
    private static final String[] NAMES = {
            "Adrian", "Beatrice", "Calder", "Delia", "Ewan", "Fenna", "Gale", "Halcyon",
            "Ilona", "Jasper", "Kaia", "Lorcan", "Maelis", "Nerida", "Orin", "Perrine",
            "Quilla", "Rowan", "Sable", "Torren", "Umbra", "Vesper", "Wren", "Xiomara",
            "Yara", "Zephyr", "Aurelia", "Brann", "Cordelia", "Draven", "Elowen", "Ferran",
            "Gwendolyn", "Halden", "Isolde", "Jorunn", "Kestrel", "Lysander", "Marisel", "Niamh"
    };

    private TropicalCyclone() {
    }

    /** Whether a stage is high enough for the storm to be a tropical system at all. */
    public static boolean isTropical(int stage) {
        return stage >= DISTURBANCE_STAGE;
    }

    /** Whether a stage is in the hurricane half of the ladder. */
    public static boolean isHurricane(int stage) {
        return stage >= HURRICANE_STAGE;
    }

    /** Category 1..5 the wind earns, or 0 below hurricane strength. */
    public static int categoryForWind(double mph) {
        if (mph < CATEGORY_1_MPH) {
            return 0;
        }
        if (mph < CATEGORY_2_MPH) {
            return 1;
        }
        if (mph < CATEGORY_3_MPH) {
            return 2;
        }
        if (mph < CATEGORY_4_MPH) {
            return 3;
        }
        if (mph < CATEGORY_5_MPH) {
            return 4;
        }
        return MAX_CATEGORY;
    }

    /** Category the rung alone allows, used where no wind sample is available. */
    public static int categoryForStage(int stage) {
        return Mth.clamp(stage - STORM_STAGE, 0, MAX_CATEGORY);
    }

    /** The tropical stage's own name, with no storm name attached. */
    public static String stageName(int stage) {
        if (stage >= HURRICANE_STAGE) {
            return "Hurricane";
        }
        if (stage >= STORM_STAGE) {
            return "Tropical Storm";
        }
        if (stage >= DEPRESSION_STAGE) {
            return "Tropical Depression";
        }
        if (stage >= DISTURBANCE_STAGE) {
            return "Tropical Disturbance";
        }
        return "Storm";
    }

    /** The stable name this storm wears for its whole life. */
    public static String nameFor(long stormId) {
        int index = (int) Math.floorMod(stormId * 2654435761L, NAMES.length);
        return NAMES[index];
    }

    /**
     * How a broad rotating storm introduces itself, the way 1.12.2 did it: a named tropical system
     * with a category once it has earned one - {@code "Hurricane Karl - Category 3"} - and just its
     * ladder stage before that, since nothing below a hurricane has a category to print.
     *
     * @param antiCyclonic the anticyclonic sibling this mod adds: same storm, opposite spin, named a
     *                     cyclone rather than a hurricane.
     */
    public static String label(long stormId, int stage, double mph, boolean antiCyclonic) {
        String name = nameFor(stormId);
        boolean hurricane = isHurricane(stage) || categoryForWind(mph) > 0;
        if (!hurricane) {
            if (stage >= STORM_STAGE) {
                return "Tropical Storm " + name;
            }
            return stageName(stage);
        }
        String kind = antiCyclonic ? "Cyclone" : "Hurricane";
        int category = Math.max(categoryForWind(mph), isHurricane(stage) ? 1 : 0);
        return kind + " " + name + " - Category " + Mth.clamp(category, 1, MAX_CATEGORY);
    }

    /**
     * A short tag for panels and map overlays, where the full name will not fit. Weather2 1.12.2
     * used TD1 for a disturbance and TD2 for a depression, so the same tags are carried here: the
     * two are not the same storm and a panel has room for the difference.
     */
    public static String shortTag(int stage, double mph) {
        if (!isTropical(stage)) {
            return null;
        }
        if (stage < DEPRESSION_STAGE) {
            return "TD1";
        }
        if (stage < STORM_STAGE) {
            return "TD2";
        }
        if (stage < HURRICANE_STAGE) {
            return "TS";
        }
        int category = Math.max(categoryForWind(mph), 1);
        return "C" + Mth.clamp(category, 1, MAX_CATEGORY);
    }

    /**
     * Wind a category implies, so a label can never promise more than the storm can deliver. Used
     * to hold a hurricane's stage ceiling honest when the rung and the wind disagree.
     */
    public static double categoryFloorMph(int category) {
        return switch (Mth.clamp(category, 1, MAX_CATEGORY)) {
            case 1 -> CATEGORY_1_MPH;
            case 2 -> CATEGORY_2_MPH;
            case 3 -> CATEGORY_3_MPH;
            case 4 -> CATEGORY_4_MPH;
            default -> CATEGORY_5_MPH;
        };
    }
}
