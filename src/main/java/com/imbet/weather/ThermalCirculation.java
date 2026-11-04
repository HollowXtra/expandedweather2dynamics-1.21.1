package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * The world's belts: where air goes up, where it comes back down, and where that makes a low.
 *
 * <p>A synoptic low is not a free-floating dice roll. In the real atmosphere it is the end of a chain
 * that starts with uneven heating: warm air rises where the sun is strongest, returns poleward aloft,
 * sinks again over the subtropics, and the boundary where the warm air it carried runs into cold air
 * is a baroclinic zone - which is the only place a low of this kind is born. A Minecraft world has no
 * latitude and no uneven heating, so the chain does not exist and lows had to be faked: either a
 * cluster of storms was declared to be one, or the sky was sampled for a gradient at a random bearing
 * from a random player. This class puts the chain in.
 *
 * <p>Latitude is distance from the same equator line the tropical families already use, so the
 * hurricane/cyclone split and the belts can never disagree about where the tropics are. Reading out
 * from the line, the belts are:
 *
 * <ul>
 *   <li><b>rising belt</b> - warm, moist, sun-heated air lifting. This is the source: it is where the
 *   water and heat in every low poleward of it came from, and its supply rises and falls with the sun
 *   over the day rather than sitting at a constant.
 *   <li><b>subtropical sinking ridge</b> - the same air returning and subsiding. Dry, stable, and the
 *   reason nothing forms here: air coming down does not make clouds.
 *   <li><b>baroclinic zone</b> - the corridor. Warm air on one side, cold on the other, and a low is
 *   born inside it from the supply the rising belt delivered and the sharpness of that boundary.
 *   <li><b>polar side</b> - cold and stable.
 * </ul>
 *
 * <p>The belt edges are ordered and can never cross, whatever is configured: the sinking ridge starts
 * where the rising belt ends, the corridor starts after that, and each boundary is at least a block
 * wide. A wildly configured belt offset therefore produces a cramped or lopsided world, never a
 * latitude that is in two belts at once.
 *
 * <p>Everything here is written to take its numbers as arguments. The belt maths has no game state in
 * it at all, so a harness can run the real ladder outside the game; only the sampling methods at the
 * bottom touch a level, and those are the ones that need a profiled sky.
 */
public final class ThermalCirculation {
    /** The four rungs of the ladder, read outward from the equator line. */
    public enum Belt {
        RISING_BELT("Rising belt", "warm air lifting - convection, and the supply every low is built from"),
        SINKING_RIDGE("Subtropical ridge", "the air that rose lifting is coming back down - dry and suppressed"),
        BAROCLINIC_ZONE("Baroclinic zone", "warm air meeting cold - the low genesis corridor"),
        POLAR_SIDE("Polar side", "cold and stable - nothing forms here");

        private final String label;
        private final String note;

        Belt(String label, String note) {
            this.label = label;
            this.note = note;
        }

        public String label() {
            return label;
        }

        /** One short clause, for a readout that has room for one line. */
        public String note() {
            return note;
        }
    }

    /** How much of a day's supply survives at night: the belt never actually stops, it quiets down. */
    private static final double NIGHT_SUPPLY_FLOOR = 0.15D;
    /** Surface heating lags noon, so the supply peaks in the afternoon: 05:00 to 21:00, peak 13:00. */
    private static final double SUPPLY_DAWN_HOUR = 5.0D;
    private static final double SUPPLY_DUSK_HOUR = 21.0D;
    /**
     * Where midnight sits on Minecraft's clock. Its day starts at tick 0 on 06:00 and puts midnight at
     * 18000, so a supply curve read straight off the tick count would peak at dawn and be dark all
     * afternoon - which is the one thing a diurnal supply must not do.
     */
    private static final double MINECRAFT_DAY_START_HOUR = 6.0D;

    private ThermalCirculation() {
    }

    // ------------------------------------------------------------------ the ladder

    /**
     * The latitude of a Z coordinate: how far from the equator line, in blocks. Only Z carries
     * latitude, which is what makes the belts run east-west across the world the way they do here -
     * a belt has no ends, so a low born in the corridor is born anywhere along it.
     */
    public static double latitude(double equatorZ, double z) {
        return Math.abs(z - equatorZ);
    }

    /**
     * The belt a latitude is in, with the configured boundaries normalised into a ladder that cannot
     * invert. A user who sets the genesis corridor inside the rising belt gets a corridor that starts
     * where the belt ends and a compressed ridge, rather than a latitude that reports two belts.
     */
    public static Belt beltAt(double equatorZ, double z, double risingHalfWidth, double genesisOffset, double transition) {
        double latitude = latitude(equatorZ, z);
        double[] edges = normalizedEdges(risingHalfWidth, genesisOffset, transition);
        if (latitude <= edges[0]) {
            return Belt.RISING_BELT;
        }
        if (latitude <= edges[1]) {
            return Belt.SINKING_RIDGE;
        }
        if (latitude <= edges[2]) {
            return Belt.BAROCLINIC_ZONE;
        }
        return Belt.POLAR_SIDE;
    }

    /**
     * The three boundaries of the ladder in order: end of the rising belt, start of the corridor, end
     * of the corridor. Every one is at least a block past the previous, so the ladder is monotone for
     * any configuration.
     */
    private static double[] normalizedEdges(double risingHalfWidth, double genesisOffset, double transition) {
        double width = Math.max(1.0D, transition);
        double risingEdge = Math.max(1.0D, risingHalfWidth);
        double corridorStart = Math.max(risingEdge + 1.0D, genesisOffset - width * 0.5D);
        double corridorEnd = corridorStart + width;
        return new double[]{risingEdge, corridorStart, corridorEnd};
    }

    /**
     * How much of the air at a latitude is going up: 1 in the rising belt, fading to 0 across the
     * transition beyond it. This is the weight on the supply term, so genesis is strongest in the belt
     * itself and weaker the further out the pair of belts it has to cross.
     */
    public static double risingWeight(double equatorZ, double z, double risingHalfWidth, double transition) {
        double latitude = latitude(equatorZ, z);
        double edge = Math.max(1.0D, risingHalfWidth);
        if (latitude <= edge) {
            return 1.0D;
        }
        return 1.0D - clamp01((latitude - edge) / Math.max(1.0D, transition));
    }

    /**
     * How much of a latitude is inside the genesis corridor: 1 at the heart of it, 0 at its edges. A
     * low is born where this is at its highest, which is what keeps every low on the same line rather
     * than scattered by latitude.
     */
    public static double genesisWeight(double equatorZ, double z, double genesisOffset, double transition) {
        double latitude = latitude(equatorZ, z);
        return 1.0D - clamp01(Math.abs(latitude - genesisOffset) / Math.max(1.0D, transition));
    }

    // ------------------------------------------------------------------ the supply

    /**
     * The sun's forcing through a Minecraft day, 0.15 to 1: the belt quiets at night but never stops,
     * and it peaks in the afternoon rather than at noon because surface heating lags the sun.
     */
    public static double solarFactor(long dayTime) {
        double hour = (Math.floorMod(dayTime, 24000L) / 1000.0D + MINECRAFT_DAY_START_HOUR) % 24.0D;
        double phase = (hour - SUPPLY_DAWN_HOUR) / (SUPPLY_DUSK_HOUR - SUPPLY_DAWN_HOUR);
        if (phase <= 0.0D || phase >= 1.0D) {
            return NIGHT_SUPPLY_FLOOR;
        }
        return NIGHT_SUPPLY_FLOOR + (1.0D - NIGHT_SUPPLY_FLOOR) * Math.sin(Math.PI * phase);
    }

    /**
     * What the rising belt is delivering at a place, 0..1: how much of its latitude is rising, how warm
     * the air is, how moist it is, and what the sun is doing right now. Dry air does not rise whatever
     * its temperature, which is why moisture is a factor rather than an addend.
     */
    public static double supply(double risingWeight, double warmth, double moisture, double solarFactor) {
        double thermal = clamp01(warmth) * (0.40D + 0.60D * clamp01(moisture));
        return clamp01(risingWeight) * clamp01(thermal) * clamp01(solarFactor);
    }

    /**
     * The score that decides whether a low is born at a sampled place, 0..1: how sharp the air-mass
     * boundary is, how moist the air either side of it is, how much energy the warm side has, and how
     * much supply the rising belt is delivering into it.
     *
     * <p>There is deliberately no storm term. Storms are what a low organises once it exists; a cluster
     * of them must never be able to father a system, which is exactly what the old score allowed by
     * giving storm load a share of the weight. The supply term takes that share instead, so a boundary
     * with nothing convective already in it is a better genesis site now than one that is merely busy.
     */
    public static double genesisScore(double capeSpread, double moistureSpread, double warmth,
                                      double risingSupply, double supplyStrength) {
        return clamp01(capeSpread) * 0.34D
                + clamp01(moistureSpread) * 0.22D
                + clamp01(warmth) * 0.16D
                + clamp01(risingSupply) * 0.28D * clamp01(supplyStrength);
    }

    /**
     * The belt's own contribution to the wind, as a Z velocity in blocks per tick.
     *
     * <p>It is the field's shape and nothing else: air converges on the rising belt and on the corridor
     * that feeds off it - which is what a low pressure belt is - and diverges over the sinking ridge.
     * The belt has no ends, so there is no along-belt direction to add; latitude is the only axis that
     * means anything, and this is the flow along it. The sign is chosen from which side of the equator
     * line the place is on, so the two hemispheres mirror each other the way a real circulation does.
     */
    public static double beltDriftBlocksPerTick(double equatorZ, double z, double risingHalfWidth,
                                                double genesisOffset, double transition,
                                                double strength, double maxBlocksPerTick) {
        Belt belt = beltAt(equatorZ, z, risingHalfWidth, genesisOffset, transition);
        double magnitude = clamp01(strength) * Math.max(0.0D, maxBlocksPerTick);
        double poleward = z >= equatorZ ? 1.0D : -1.0D;
        return switch (belt) {
            // Inflow at the surface: the belt pulls air toward itself, so the trades blow equatorward.
            case RISING_BELT -> -poleward * magnitude;
            // The descending branch: air coming down has to go somewhere, so it spreads poleward.
            case SINKING_RIDGE -> poleward * magnitude;
            // The corridor is convergence, not a wind: both sides feed it, so it pulls weakly inward.
            case BAROCLINIC_ZONE -> -poleward * magnitude * 0.45D;
            case POLAR_SIDE -> -poleward * magnitude * 0.20D;
        };
    }

    // ------------------------------------------------------------------ against the real world

    /** The belt a place is in, using the configured belt geometry. */
    public static Belt belt(double z) {
        return beltAt(Config.TROPICAL_HEMISPHERE_EQUATOR_Z.get(), z,
                Config.THERMAL_RISING_BELT_WIDTH_BLOCKS.get(),
                Config.THERMAL_GENESIS_BELT_OFFSET_BLOCKS.get(),
                Config.THERMAL_BELT_TRANSITION_BLOCKS.get());
    }

    /** The belt's name at a place, for a readout. */
    public static String beltLabel(double z) {
        return belt(z).label();
    }

    /** The belt's one-line explanation at a place, for a readout that has room. */
    public static String beltNote(double z) {
        return belt(z).note();
    }

    /** Whether the belts exist at all: with the field off nothing reads latitude. */
    public static boolean enabled() {
        return Config.THERMAL_CIRCULATION_ENABLED.get();
    }

    /** The field's own push on something drifting at a place, in blocks per tick. */
    public static double driftAt(double z) {
        if (!enabled()) {
            return 0.0D;
        }
        return beltDriftBlocksPerTick(Config.TROPICAL_HEMISPHERE_EQUATOR_Z.get(), z,
                Config.THERMAL_RISING_BELT_WIDTH_BLOCKS.get(),
                Config.THERMAL_GENESIS_BELT_OFFSET_BLOCKS.get(),
                Config.THERMAL_BELT_TRANSITION_BLOCKS.get(),
                Config.THERMAL_TRADE_WIND_STRENGTH.get(),
                MAX_DRIFT_BLOCKS_PER_TICK);
    }

    /**
     * The field's push as a vector. Only Z moves: latitude only exists on that axis, so a belt cannot
     * push anything sideways without inventing a direction a belt does not have.
     */
    public static Vec3 driftVector(double z) {
        return new Vec3(0.0D, 0.0D, driftAt(z));
    }

    /**
     * The supply the rising belt is delivering into a place, 0..1, sampled from the real sky: the air's
     * own warmth and moisture from the thermodynamic profile, and the sun overhead.
     */
    public static double supply(ServerLevel level, double x, double z) {
        if (level == null || !enabled()) {
            return 0.0D;
        }
        double weight = risingWeight(Config.TROPICAL_HEMISPHERE_EQUATOR_Z.get(), z,
                Config.THERMAL_RISING_BELT_WIDTH_BLOCKS.get(), Config.THERMAL_BELT_TRANSITION_BLOCKS.get());
        if (weight <= 0.0D) {
            return 0.0D;
        }
        BlockPos column = surfaceColumn(level, x, z);
        if (column == null) {
            return 0.0D;
        }
        ThermodynamicProfile profile = ThermodynamicProfileService.compute(level, column);
        return supply(weight, profile.temperatureModifier(), profile.moisture(), solarFactor(level.getDayTime()));
    }

    /**
     * Whether, and how strongly, a low could be born at a place, 0..1.
     *
     * <p>Sampled from the atmosphere itself: the spread of instability and moisture over a
     * synoptic-scale window (a low is born on a gradient, not in uniform air), how much energy the air
     * at the centre has to work with, and how much the rising belt is supplying into that boundary. The
     * sampling is the same shape it always was - the storm-count term it used to carry is gone, and the
     * supply term has taken its share.
     */
    public static double genesisScore(ServerLevel level, double x, double z) {
        if (level == null || !enabled()) {
            return 0.0D;
        }
        double window = Config.LOW_PRESSURE_GENESIS_WINDOW_BLOCKS.get();
        double minCape = Double.MAX_VALUE;
        double maxCape = -1.0D;
        double minMoisture = 2.0D;
        double maxMoisture = -1.0D;
        int sampled = 0;
        for (int index = 0; index < 4; index++) {
            double angle = Math.PI * 0.5D * index;
            BlockPos column = surfaceColumn(level, x + Math.cos(angle) * window, z + Math.sin(angle) * window);
            if (column == null) {
                continue;
            }
            ThermodynamicProfile profile = ThermodynamicProfileService.compute(level, column);
            minCape = Math.min(minCape, profile.capeJkg());
            maxCape = Math.max(maxCape, profile.capeJkg());
            minMoisture = Math.min(minMoisture, profile.moisture());
            maxMoisture = Math.max(maxMoisture, profile.moisture());
            sampled++;
        }
        if (sampled < 3) {
            return 0.0D;
        }
        BlockPos centre = surfaceColumn(level, x, z);
        double centreCape = centre == null ? 0.0D : ThermodynamicProfileService.compute(level, centre).capeJkg();

        return genesisScore(
                (maxCape - minCape) / 2600.0D,
                (maxMoisture - minMoisture) / 0.38D,
                centreCape / 3600.0D,
                supply(level, x, z),
                Config.THERMAL_RISING_SUPPLY_STRENGTH.get()
        );
    }

    /**
     * The belt's own steering push at a place, as a vector. Bounded by construction: it is a share of a
     * modest synoptic flow, never a gale, because the belts steer systems rather than drive them.
     */
    public static Vec3 steeringBias(double z) {
        return driftVector(z);
    }

    /**
     * The most the belts are ever allowed to push something, in blocks per tick - about 5.6 mph.
     *
     * <p>Public because it is a promise rather than an implementation detail: the field is a bias on a
     * track, never a driver of one, and a harness should be able to hold the code to that ceiling
     * without knowing anything about how the drift is computed.
     */
    public static final double MAX_DRIFT_BLOCKS_PER_TICK = 0.125D;

    private static BlockPos surfaceColumn(ServerLevel level, double x, double z) {
        BlockPos column = BlockPos.containing(x, level.getMinBuildHeight() + 1, z);
        if (!level.isLoaded(column)) {
            return null;
        }
        BlockPos surface = BlockGrabSafety.safeSurface(level, column, Heightmap.Types.MOTION_BLOCKING);
        return surface == null ? column : surface;
    }

    /** This class's own clamp, so the belt maths stands on its own outside the game. */
    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0D;
        }
        return value < 0.0D ? 0.0D : Math.min(value, 1.0D);
    }
}
