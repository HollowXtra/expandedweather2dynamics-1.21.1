package com.imbet.weather;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the ground under the weather is made of, and what that does to the air above it.
 *
 * <p>Every weather system in this addon already reads one thing before it does anything: the air
 * sampled around a position. This is that reading, taken one step further down - the air is not the
 * same everywhere, because the land under it is not. A desert hands the sky a dry boundary layer that
 * caps convection, a jungle hands it a saturated one, an ocean hands it marine air that is warm and
 * wet and almost impossible to make a supercell out of, and the most violent tornadoes in the real
 * world happen where two of those airs meet: dry air aloft running over moist air at the surface, the
 * boundary a dryline marks. Nothing here draws that boundary - it is a reading of the biome mixture
 * around a point, and it is what makes one part of a map tornado country and the next part quiet.
 *
 * <p><b>Families, not biomes.</b> A biome is classified into one of twelve families from what the
 * biome itself reports and what it calls itself - its base temperature, whether it precipitates,
 * whether it is cold enough to snow, and a few keywords in its own name - so every biome that exists,
 * including every modded one, lands in a family without anybody listing it. A pack's biome with a name
 * nobody has seen still gets its temperature and precipitation read, which is enough to place it.
 *
 * <p><b>Families give two numbers and the biome gives the third.</b> Heat comes from the biome's own
 * base temperature, because a jungle in a cold world is a cold jungle. Moisture and instability come
 * from the family, because those are properties of the kind of country it is: a swamp is wet whether
 * or not it happens to be cool, and dry air caps convection wherever it is. A player tunes the second of
 * those per family.
 *
 * <p>This class is arithmetic only - no Minecraft and no Weather2 types - so the whole of it can be
 * loaded, called and measured without a world, which is how the numbers in the README were taken.
 */
public final class BiomeClimate {

    /** Temperature at which the air is taken to hold nothing: below this, heat reads zero. */
    public static final double COLD_FLOOR = 0.30D;
    /** Temperature span from no heat to all of it, in vanilla's own units. */
    public static final double HEAT_SPAN = 1.35D;
    /** The CAPE a perfectly loaded air makes, before its own instability and the day's roll. */
    public static final double CAPE_CEILING_JKG = 5500.0D;
    /** The CAPE floor: a calm sky is not quite a dead one. */
    public static final double CAPE_FLOOR_JKG = 220.0D;
    /** The CAPE a hot, wet, fully unstable air is worth. */
    public static final double CAPE_FULL_JKG = 4200.0D;
    /** What standing water around the sample adds to CAPE, per unit of water share. */
    public static final double WATER_CAPE_JKG = 420.0D;
    /** What that water adds to the air's moisture. */
    public static final double WATER_LIFT = 0.18D;
    /** The moisture gap between the wettest and driest quarter of a sample that counts as a full dryline. */
    public static final double CONTRAST_FULL_GAP = 0.45D;
    /** How much of a full dryline's contrast lands on the storm mode index, before the setting. */
    public static final double CONTRAST_MODE_WEIGHT = 0.12D;
    /** How much of the CAPE index the mode is made of, with shear taking the rest. */
    public static final double MODE_CAPE_SHARE = 0.54D;
    public static final double MODE_SHEAR_SHARE = 0.46D;

    /**
     * The twelve kinds of country a biome can be. The two numbers on each are the family's own air:
     * the moisture it hands the boundary layer, and how readily that air converts into convection
     * once it has heat. They are deliberately familiar rather than fitted - a desert is dry, an ocean
     * is moist and stable, plains are the loaded-and-sheared middle - and the family weights in the
     * config scale the second of them.
     */
    public enum Family {
        OCEAN("Ocean", 1.00D, 0.60D),
        COAST("Coast", 0.85D, 0.85D),
        DESERT("Desert", 0.10D, 0.55D),
        BADLANDS("Badlands", 0.18D, 0.65D),
        SAVANNA("Savanna", 0.45D, 1.05D),
        PLAINS("Plains", 0.55D, 1.05D),
        FOREST("Forest", 0.80D, 0.95D),
        JUNGLE("Jungle", 1.00D, 0.75D),
        SWAMP("Swamp", 1.00D, 0.70D),
        TAIGA("Taiga", 0.65D, 0.60D),
        SNOWY("Snowy", 0.55D, 0.35D),
        MOUNTAIN("Mountain", 0.50D, 0.75D);

        private final String label;
        private final double moisture;
        private final double instability;

        Family(String label, double moisture, double instability) {
            this.label = label;
            this.moisture = moisture;
            this.instability = instability;
        }

        public String label() {
            return label;
        }

        /** The moisture this family's boundary layer holds, 0 to 1. */
        public double moisture() {
            return moisture;
        }

        /** How readily this family's air makes convection, before the config's own weight. */
        public double instability() {
            return instability;
        }

        public static Family byName(String name) {
            if (name == null || name.isBlank()) {
                return PLAINS;
            }
            try {
                return Family.valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return PLAINS;
            }
        }
    }

    /**
     * The named belts: what the mixture around a point amounts to, in the language of the real thing.
     * This is the label a player reads, and it is derived from the same four numbers the air is built
     * from - it is not a second opinion about the weather, it is the same reading said differently.
     */
    public enum Alley {
        TEMPERATE("Temperate", 1.00D),
        DRYLINE("Dryline Alley", 1.15D),
        WARM_WET("Warm Wet Alley", 1.05D),
        MONSOON("Monsoon Belt", 1.05D),
        MARINE("Marine Air", 0.85D),
        DESERT_BELT("Dry Belt", 0.80D),
        FROZEN("Frozen Belt", 0.60D),
        TROPICAL_COAST("Tropical Coast", 1.00D);

        private final String label;
        private final double tornadoBias;

        Alley(String label, double tornadoBias) {
            this.label = label;
            this.tornadoBias = tornadoBias;
        }

        public String label() {
            return label;
        }

        /**
         * How this belt compares with plain temperate country for tornadoes, as a display figure. The
         * physics rides on the air itself - a dryline's contrast is in the storm mode index below - so
         * this is what the belt is worth when everything else is equal, not a second multiplier.
         */
        public double tornadoBias() {
            return tornadoBias;
        }
    }

    /**
     * What one biome reports about itself. {@code biomePath} is the biome's own name, which is how the
     * families a temperature cannot express - swamp, jungle, badlands, coast - are read.
     */
    public record Traits(double baseTemperature, boolean precipitation, boolean snow, String biomePath) {
    }

    /** One biome sample: what it is, and whether the surface there is water. */
    public record Sample(Traits traits, boolean water) {
    }

    /**
     * The air over a neighbourhood: its heat, its moisture, how readily it makes convection, how sharp
     * the moisture boundary across it is, and which family and belt it comes out as.
     */
    public record Reading(double heat, double moisture, double instability, double contrast,
                          Family dominant, double dominantShare, Alley alley, double waterShare) {

        /** A short description for a readout: "plains air, dryline alley". */
        public String label() {
            return (dominant == null ? "unknown" : dominant.label().toLowerCase(Locale.ROOT))
                    + " air, " + (alley == null ? "temperate" : alley.label().toLowerCase(Locale.ROOT));
        }

        /** Whether this air is one a tornado is at home in. */
        public boolean tornadic() {
            return alley == Alley.DRYLINE || alley == Alley.WARM_WET || alley == Alley.MONSOON;
        }
    }

    private BiomeClimate() {
    }

    /**
     * Which family a biome belongs to. The name is read first, because it is the only thing that can
     * tell a swamp from a forest or a badlands from a desert; then the numbers, so anything unnamed -
     * every modded biome - is placed by its own temperature and precipitation.
     */
    public static Family classify(Traits traits) {
        if (traits == null) {
            return Family.PLAINS;
        }
        String path = traits.biomePath() == null ? "" : traits.biomePath().toLowerCase(Locale.ROOT);
        if (containsAny(path, "ocean", "river")) {
            return Family.OCEAN;
        }
        if (containsAny(path, "beach", "shore", "stony_shore", "island")) {
            return Family.COAST;
        }
        if (containsAny(path, "desert")) {
            return Family.DESERT;
        }
        if (containsAny(path, "badlands", "mesa", "canyon")) {
            return Family.BADLANDS;
        }
        if (containsAny(path, "jungle", "bamboo")) {
            return Family.JUNGLE;
        }
        if (containsAny(path, "swamp", "mangrove", "bayou", "marsh")) {
            return Family.SWAMP;
        }
        if (containsAny(path, "savanna", "shrubland", "steppe")) {
            return Family.SAVANNA;
        }
        if (traits.snow() || containsAny(path, "snow", "frozen", "glacier", "ice_spikes", "ice_")) {
            return Family.SNOWY;
        }
        if (containsAny(path, "taiga", "tundra", "old_growth_pine", "old_growth_spruce")) {
            return Family.TAIGA;
        }
        if (containsAny(path, "mountain", "peak", "slope", "cliff", "windswept", "hill")) {
            return Family.MOUNTAIN;
        }
        if (containsAny(path, "forest", "woods", "woodland", "grove")) {
            return Family.FOREST;
        }
        if (containsAny(path, "plains", "meadow", "prairie", "field", "savanna_plateau")) {
            return Family.PLAINS;
        }
        return fromNumbers(traits);
    }

    /**
     * The fallback for a biome whose name says nothing: its temperature and whether it rains are
     * enough to tell a frozen belt from a dry one, and a wet warm one from a dry warm one.
     */
    private static Family fromNumbers(Traits traits) {
        double temperature = traits.baseTemperature();
        if (traits.snow() || temperature < 0.15D) {
            return Family.SNOWY;
        }
        if (temperature < 0.50D) {
            return traits.precipitation() ? Family.TAIGA : Family.MOUNTAIN;
        }
        if (!traits.precipitation()) {
            return temperature >= 0.90D ? Family.DESERT : Family.SAVANNA;
        }
        if (temperature >= 0.90D) {
            return Family.JUNGLE;
        }
        return temperature >= 0.70D ? Family.FOREST : Family.PLAINS;
    }

    /**
     * Reads the air over a neighbourhood.
     *
     * <p>Heat, moisture and instability are area averages, because the air over a piece of country is
     * what the country under it is made of on average. The contrast is not: it is the gap between the
     * wettest quarter of the samples and the driest quarter, scaled by how warm the neighbourhood is,
     * because a moisture boundary only means anything where there is heat to work with it - a dryline
     * through a frozen belt is not a dryline. That gap is the whole reason a boundary between two
     * biomes is more dangerous than either of them.
     *
     * @param familyWeights one weight per family ordinal, or null for all of them at 1
     */
    public static Reading read(List<Sample> samples, double[] familyWeights) {
        if (samples == null || samples.isEmpty()) {
            return new Reading(0.5D, 0.35D, 0.90D, 0.0D, Family.PLAINS, 0.0D, Alley.TEMPERATE, 0.0D);
        }
        int size = samples.size();
        double heatSum = 0.0D;
        double moistureSum = 0.0D;
        double instabilitySum = 0.0D;
        double waterShare = 0.0D;
        int[] familyCounts = new int[Family.values().length];
        double[] moistures = new double[size];
        int index = 0;
        for (Sample sample : samples) {
            Family family = classify(sample.traits());
            double heat = heatOf(sample.traits());
            double moisture = family.moisture();
            double instability = family.instability() * weightOf(familyWeights, family);
            heatSum += heat;
            moistureSum += moisture;
            instabilitySum += instability;
            moistures[index++] = moisture;
            familyCounts[family.ordinal()]++;
            if (sample.water()) {
                waterShare += 1.0D;
            }
        }
        double heat = heatSum / size;
        double moisture = clamp01(moistureSum / size + WATER_LIFT * (waterShare / size));
        double instability = clamp(instabilitySum / size, 0.10D, 2.0D);
        double contrast = contrastOf(moistures, heat);
        Family dominant = Family.PLAINS;
        int best = -1;
        for (Family family : Family.values()) {
            if (familyCounts[family.ordinal()] > best) {
                best = familyCounts[family.ordinal()];
                dominant = family;
            }
        }
        Alley alley = classifyAlley(heat, moisture, instability, contrast, dominant);
        return new Reading(heat, moisture, instability, contrast, dominant, best / (double) size, alley, waterShare / size);
    }

    /** Heat from a biome's own temperature, on the same scale the profile has always used. */
    public static double heatOf(Traits traits) {
        if (traits == null) {
            return 0.5D;
        }
        return clamp01((traits.baseTemperature() - COLD_FLOOR) / HEAT_SPAN);
    }

    /**
     * How sharp the moisture boundary across a neighbourhood is, 0 to 1: the difference between the
     * wettest and the driest quarter of it, over the gap that counts as a full dryline, scaled by how
     * warm the place is.
     */
    public static double contrastOf(double[] moistures, double heat) {
        if (moistures == null || moistures.length < 4) {
            return 0.0D;
        }
        double[] sorted = moistures.clone();
        java.util.Arrays.sort(sorted);
        int quarter = Math.max(1, sorted.length / 4);
        double driest = 0.0D;
        double wettest = 0.0D;
        for (int i = 0; i < quarter; i++) {
            driest += sorted[i];
            wettest += sorted[sorted.length - 1 - i];
        }
        double gap = (wettest - driest) / quarter;
        return clamp01(gap / CONTRAST_FULL_GAP) * clamp01(heat);
    }

    /**
     * Which belt a reading amounts to. The order is the order these belts nest in: nothing is anything
     * in a frozen belt, the sea's own air is the sea's before it is anything else, a boundary is read
     * before the uniform country on either side of it, and the dry belt is what is left of hot country
     * with no moisture in it at all.
     */
    public static Alley classifyAlley(double heat, double moisture, double instability, double contrast, Family dominant) {
        if (heat < 0.30D) {
            return Alley.FROZEN;
        }
        if (dominant == Family.OCEAN || dominant == Family.COAST) {
            return heat >= 0.60D ? Alley.TROPICAL_COAST : Alley.MARINE;
        }
        if (heat >= 0.55D && contrast >= 0.45D && moisture >= 0.30D && moisture <= 0.78D) {
            return Alley.DRYLINE;
        }
        if (heat >= 0.60D && moisture >= 0.80D) {
            return Alley.WARM_WET;
        }
        if (heat >= 0.65D
                && contrast >= 0.22D
                && (dominant == Family.SAVANNA || dominant == Family.BADLANDS || dominant == Family.DESERT)) {
            return Alley.MONSOON;
        }
        if (moisture < 0.30D && heat >= 0.65D) {
            return Alley.DESERT_BELT;
        }
        return Alley.TEMPERATE;
    }

    /** The CAPE an air makes, from its own heat, moisture and instability plus the day's roll. */
    public static double capeJkg(double heat, double moisture, double instability, double waterInfluence, double dailyRoll) {
        double loaded = CAPE_FULL_JKG * clamp01(heat) * clamp01(moisture) * clamp(instability, 0.0D, 2.0D);
        return clamp(CAPE_FLOOR_JKG + loaded + waterInfluence * WATER_CAPE_JKG + dailyRoll, 0.0D, CAPE_CEILING_JKG);
    }

    /**
     * The storm mode index: how much of a storm day it is, from CAPE, deep-layer shear and the
     * contrast across the ground. Shear is what organises an updraft, so it carries nearly half of it;
     * the contrast is the dryline, and it is worth up to {@link #CONTRAST_MODE_WEIGHT} of the index -
     * enough to matter at the top of the scale, where bright boundaries decide between a marginal and
     * a violent day, and not enough to make a boundary look like a loaded air.
     */
    public static double stormModeIndex(double capeJkg, double shearMps, double contrast, double contrastStrength) {
        double capeNorm = clamp01(capeJkg / 5000.0D);
        double shearNorm = clamp01((shearMps - 5.0D) / 34.0D);
        double contrastTerm = clamp01(contrast) * clamp(contrastStrength, 0.0D, 2.0D) * CONTRAST_MODE_WEIGHT;
        return clamp01(capeNorm * MODE_CAPE_SHARE + shearNorm * MODE_SHEAR_SHARE + contrastTerm);
    }

    /**
     * The phenomena this mod spawns on their own, as things one piece of country can be more or less
     * prone to. Each name is one of the mod's own spawn chances, so an odds figure is a percentage of
     * that chance rather than the head of a second, shadow weather system.
     */
    public enum Phenomenon {
        TORNADO("Tornadoes"),
        LANDSPOUT("Landspouts"),
        WATERSPOUT("Waterspouts"),
        DUST_DEVIL("Dust Devils"),
        SNOW_DEVIL("Snow Devils"),
        GUSTNADO("Gustnadoes"),
        FUNNEL_CLOUD("Cold-Air Funnels"),
        HURRICANE("Hurricanes");

        private final String label;

        Phenomenon(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * What one place's air is worth for each phenomenon, as a multiplier on that phenomenon's own
     * configured spawn chance. 1.0 is air that neither helps nor hurts; dryline country runs near 2.0
     * for tornadoes and a frozen belt under 0.4.
     *
     * <p>Every term is written around one reference air - ordinary temperate plains country, half heat
     * and half moisture, neutral instability, no boundary and no water - so that reference air comes out
     * at exactly 1.0 for every phenomenon the land can make: tornadoes, landspouts, dust devils, snow
     * devils, gustnadoes and cold-air funnels. That is deliberate, and it is a property the ClimateSim
     * harness measures, because the mod's spawn chances were tuned as national averages and a table that
     * averaged 0.8 dust devils would quietly thin the world's dust devils by a fifth. What varies is
     * <em>where</em> the weather is, not how much weather the world makes.
     *
     * <p>Waterspouts and hurricanes are the two deliberate exceptions. They read the water and are not
     * neutral over dry land at all, because inland country really does almost never produce either.
     */
    public record Odds(double tornado, double landspout, double waterspout, double dustDevil,
                       double snowDevil, double gustnado, double funnelCloud, double hurricane) {

        /** Air that neither helps nor hurts anything: every place, before any of this existed. */
        public static final Odds NONE = new Odds(1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D);

        public double of(Phenomenon phenomenon) {
            if (phenomenon == null) {
                return 1.0D;
            }
            return switch (phenomenon) {
                case TORNADO -> tornado;
                case LANDSPOUT -> landspout;
                case WATERSPOUT -> waterspout;
                case DUST_DEVIL -> dustDevil;
                case SNOW_DEVIL -> snowDevil;
                case GUSTNADO -> gustnado;
                case FUNNEL_CLOUD -> funnelCloud;
                case HURRICANE -> hurricane;
            };
        }

        /**
         * The same odds pulled back towards 1.0. The strength knob exists because the air has already
         * moved every one of these numbers by itself - a dryline makes CAPE, storm mode and therefore
         * tornado odds without any help - and this is the belt on top of that physics, not a
         * replacement for it. At 0 only the air differs from region to region.
         */
        public Odds blended(double strength) {
            double s = clamp(strength, 0.0D, 1.0D);
            if (s >= 1.0D) {
                return this;
            }
            return new Odds(blended(tornado, s), blended(landspout, s), blended(waterspout, s),
                    blended(dustDevil, s), blended(snowDevil, s), blended(gustnado, s),
                    blended(funnelCloud, s), blended(hurricane, s));
        }

        /** The phenomenon this place is most unusually prone to, for a readout. */
        public Phenomenon strongest() {
            Phenomenon best = Phenomenon.TORNADO;
            double highest = 0.0D;
            for (Phenomenon phenomenon : Phenomenon.values()) {
                double value = of(phenomenon);
                if (value > highest) {
                    highest = value;
                    best = phenomenon;
                }
            }
            return best;
        }

        /** The strongest figure in the table, for a readout or a diagnostic. */
        public double strongestValue() {
            return of(strongest());
        }

        private static double blended(double value, double strength) {
            return 1.0D + (value - 1.0D) * strength;
        }
    }

    /**
     * What the air over a place is prone to, from the same reading that sets its CAPE and storm mode.
     *
     * <p>The physics is already in the air - a dryline makes a loaded, sharply bounded air and the
     * profile turns that into CAPE and storm mode - so this table is the country's own character on top
     * of it: the dryline's tornadoes, the sea's waterspouts, the desert's dust devils, the cold shower's
     * funnel clouds. It is what makes a boundary between two kinds of country its own place to live,
     * rather than a line where two uniform airs happen to meet.
     */
    public static Odds odds(Reading air) {
        if (air == null) {
            return Odds.NONE;
        }
        double heat = clamp01(air.heat());
        double moisture = clamp01(air.moisture());
        double instability = clamp(air.instability(), 0.0D, 2.0D);
        double contrast = clamp01(air.contrast());
        double water = clamp01(air.waterShare());
        Alley alley = air.alley() == null ? Alley.TEMPERATE : air.alley();

        // Air that is all moisture and no boundary: maritime or rainforest air, which is loaded but
        // stable and has none of the contrast a supercell needs. It damps tornadoes, dust devils and
        // gustnadoes alike, and is what keeps a wet coast from reading as a tornado alley.
        double soaked = clamp01((moisture - 0.80D) / 0.20D);

        // A tornado wants warm, loaded air with a boundary across it, and the belt it is in decides how
        // much of that its country supplies. The dryline term is the boundary itself: dry country meeting
        // wet is worth up to 40% on its own, on top of what the same boundary has already done to the air.
        double tornado = alley.tornadoBias()
                * (0.60D + 0.80D * heat)
                * (0.40D + 1.20D * moisture)
                * (1.0D + 0.40D * contrast)
                * (1.0D - 0.45D * soaked);

        // A landspout is the high-based kind: hot ground with a dry column over it, and none of the
        // moisture load that turns a storm into a rain machine instead.
        double landspout = (0.50D + 1.00D * heat)
                * (1.30D - 0.60D * moisture)
                * (0.55D + 0.45D * instability)
                * (1.0D - 0.40D * water);

        // Waterspouts are the sea's and the coast's: this table is neutral nowhere but over water, which
        // is the point of it.
        double waterspout = (0.30D + 1.40D * water) * (0.60D + 0.80D * heat);

        // A dust devil wants bare hot ground and nothing in the air above it, so it reads the dryness
        // the landspout does, harder, and switches off in loaded air. Both terms are written to meet at
        // 1.0 on reference air, so the desert's devils are not paid for by quietly thinning everyone
        // else's.
        double dustDevil = (1.45D - 0.90D * moisture)
                * (0.55D + 0.90D * heat)
                * (1.0D - 0.60D * soaked);

        // A snow devil is the dust devil's cold-country twin: bare dry ground, no rain, and cold air over
        // it, which is why this row is the dust devil's heat term turned upside down.
        double snowDevil = (0.35D + 1.30D * (1.0D - heat)) * (0.40D + 0.60D * clamp01(instability));

        // A gustnado is a front's own outflow, so it wants a boundary without needing the loaded air: it
        // is the most nearly neutral row here - exactly 1.0 in uniform country - and the one that
        // survives where the air is too dry for a storm.
        double gustnado = (0.70D + 0.60D * heat)
                * (1.00D + 0.40D * contrast)
                * (1.0D - 0.30D * soaked);

        // A cold-air funnel is what marginal cool-season air does instead of making a tornado, so this
        // row is the opposite of the dust devil's.
        double funnelCloud = (1.30D - 0.60D * heat) * (0.50D + 1.00D * moisture);

        // A hurricane is a coast's weather: nothing in the middle of a continent ever sees one, and the
        // season and the warm sea are still the parents of the ones that do happen.
        double hurricane = (0.30D + 1.30D * water) * (0.50D + 1.00D * heat);

        return new Odds(tornado, landspout, waterspout, dustDevil, snowDevil, gustnado, funnelCloud, hurricane);
    }

    /**
     * The keys of a per-region cache: a position's cell, as one long, so a region is one map entry.
     */
    public static long cellKey(int x, int z, int cellSizeBlocks) {
        int size = Math.max(64, cellSizeBlocks);
        long cellX = Math.floorDiv(x, size);
        long cellZ = Math.floorDiv(z, size);
        return (cellX & 0xffff_ffffL) << 32 | (cellZ & 0xffff_ffffL);
    }

    /** The x of the centre of a cell, which is what a cell's air is sampled around. */
    public static int cellCentreX(long cellKey, int cellSizeBlocks) {
        int size = Math.max(64, cellSizeBlocks);
        int cellX = (int) (cellKey >> 32);
        return cellX * size + size / 2;
    }

    /** The z of the centre of a cell. */
    public static int cellCentreZ(long cellKey, int cellSizeBlocks) {
        int size = Math.max(64, cellSizeBlocks);
        int cellZ = (int) (cellKey & 0xffff_ffffL);
        return cellZ * size + size / 2;
    }

    private static double weightOf(double[] weights, Family family) {
        if (weights == null || family.ordinal() >= weights.length) {
            return 1.0D;
        }
        double weight = weights[family.ordinal()];
        if (!Double.isFinite(weight)) {
            return 1.0D;
        }
        return Math.max(0.0D, Math.min(2.0D, weight));
    }

    private static boolean containsAny(String path, String... needles) {
        for (String needle : needles) {
            if (path.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0D, 1.0D);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    /** Every family in ordinal order, for a config builder or a harness that wants them all. */
    public static List<Family> families() {
        List<Family> families = new ArrayList<>(Family.values().length);
        for (Family family : Family.values()) {
            families.add(family);
        }
        return families;
    }
}
