package com.imbet.volcano;

/**
 * The arithmetic of an eruption, with no Minecraft in it.
 *
 * <p>Every number here is anchored to something observed rather than chosen for feel, because the whole point
 * of this system over the one Weather2 shipped is that a volcano does what a volcano does: it warns first, it
 * throws a column up, the column collapses, the collapse is what kills, and the lava that follows is slow and
 * long-lived. Keeping the rules in one class with no game references also means they can be measured directly -
 * {@code VolcanoSim} calls these methods and checks the answers against the real world's numbers.
 *
 * <p>Units: one block is one metre, one tick is one twentieth of a second, wind is mph, ash thickness is
 * millimetres.
 */
public final class VolcanoModel {

    /** Volcanic Explosivity Index, the scale this whole system is built on. */
    public static final double MIN_INTENSITY = 0.0D;
    public static final double MAX_INTENSITY = 6.0D;

    /**
     * Plume heights by VEI, in metres. These are the observed rungs of the scale, not a curve fitted to them:
     * a VEI 1 is a puff a few hundred metres up, a VEI 3 tops the troposphere over a mountain range, and a
     * VEI 5 puts ash into the stratosphere. Everything between the rungs is interpolated.
     */
    private static final double[] PLUME_HEIGHT_BY_VEI = {
            100.0D, 500.0D, 2000.0D, 6000.0D, 12000.0D, 25000.0D, 40000.0D
    };

    /**
     * The part of a column that is dense enough to collapse and run out as a pyroclastic density current.
     * A Plinian column is buoyant most of the way up; what comes back down is its lower, denser section, so
     * the collapse height is a fraction of the plume rather than the plume.
     */
    public static final double COLLAPSE_HEIGHT_FRACTION = 0.25D;

    /**
     * The energy-cone angle the runout is computed with, in degrees. Real collapse runouts are bracketed by
     * the angle of repose of the deposit, and 14 degrees reproduces the observed ladder: about 12 km for a
     * VEI 4 and about 25 km for a VEI 5, which is what the deposits say.
     */
    public static final double COLLAPSE_CONE_DEGREES = 14.0D;

    /** The reference distance the ash deposit is stated at, and the thickness a VEI 4 leaves there. */
    public static final double ASH_REFERENCE_DISTANCE_BLOCKS = 5000.0D;
    public static final double ASH_REFERENCE_THICKNESS_MM = 100.0D;

    private VolcanoModel() {
    }

    // ------------------------------------------------------------------ column

    /** How high the ash column stands, in metres, for an eruption of this intensity. */
    public static double plumeHeightMetres(double intensity) {
        double clamped = clamp(intensity, MIN_INTENSITY, MAX_INTENSITY);
        int low = (int) Math.floor(clamped);
        int high = Math.min(low + 1, PLUME_HEIGHT_BY_VEI.length - 1);
        double fraction = clamped - low;
        return PLUME_HEIGHT_BY_VEI[low] + (PLUME_HEIGHT_BY_VEI[high] - PLUME_HEIGHT_BY_VEI[low]) * fraction;
    }

    /** The height the dense base of the column reaches before it comes back down. */
    public static double collapseHeightMetres(double intensity) {
        return plumeHeightMetres(intensity) * COLLAPSE_HEIGHT_FRACTION;
    }

    /**
     * How far a collapsing column's density current runs out, in blocks: the energy-cone rule, the collapse
     * height divided by the tangent of the cone angle. Height and runout are coupled this way in the real
     * deposits - a bigger column does not merely throw further, it throws proportionally further.
     */
    public static double surgeRunoutBlocks(double intensity) {
        return collapseHeightMetres(intensity) / Math.tan(Math.toRadians(COLLAPSE_CONE_DEGREES));
    }

    /**
     * How fast the current travels, in metres per second. Density currents run between about 100 and 300 km/h
     * depending on how much of the column came down and how far it has spread, which this reproduces as a
     * straight line in intensity from 60 km/h at VEI 0 to 330 km/h at VEI 6.
     */
    public static double surgeMetresPerSecond(double intensity) {
        double kmh = 60.0D + 45.0D * clamp(intensity, MIN_INTENSITY, MAX_INTENSITY);
        return kmh / 3.6D;
    }

    /** The same speed in the units the game moves things in. */
    public static double surgeBlocksPerTick(double intensity) {
        return surgeMetresPerSecond(intensity) / 20.0D;
    }

    // ------------------------------------------------------------------ ash fall

    /**
     * The exponent the ash deposit thins with distance, from the half-distance model real isopach maps are
     * drawn from: thickness falls off as a power of distance, and a stronger wind stretches the deposit along
     * its own axis rather than merely moving it, which is why the exponent drops as the wind rises.
     */
    public static double ashThinningExponent(double windMph) {
        return clamp(2.6D - Math.abs(windMph) / 60.0D * 0.9D, 1.6D, 2.6D);
    }

    /**
     * How thick the ash is at a distance downwind, in millimetres.
     *
     * <p>The reference is a VEI 4 leaving 100 mm at 5 km, and the deposit then thins as a power law. That puts
     * a few millimetres at 20 km and a fraction of one at 60 km, which is the shape of a real VEI 4 isopach.
     * Intensity scales the reference thickness by 10^(VEI-4), so each rung of the scale is ten times the ash.
     */
    public static double ashThicknessMm(double intensity, double distanceBlocks, double windMph) {
        double distance = Math.max(250.0D, distanceBlocks);
        double reference = ASH_REFERENCE_THICKNESS_MM * Math.pow(10.0D, clamp(intensity, MIN_INTENSITY, MAX_INTENSITY) - 4.0D);
        double exponent = ashThinningExponent(windMph);
        return reference * Math.pow(distance / ASH_REFERENCE_DISTANCE_BLOCKS, -exponent);
    }

    /**
     * Whether ash deep enough to be a hazard is falling, and by how much it threatens a living thing: below a
     * millimetre it is a dusting, a centimetre is an irritant that ruins crops, and a hand's depth is enough to
     * bring a roof down.
     */
    public static double ashHazardFactor(double ashMm) {
        if (ashMm <= 0.0D) {
            return 0.0D;
        }
        return clamp(Math.pow(ashMm / 10.0D, 0.45D), 0.0D, 1.0D);
    }

    // ------------------------------------------------------------------ lava

    /**
     * How long a lava flow gets, in blocks, from its effusion rate: the empirical relation for basaltic flows,
     * where length goes as roughly the square root of the rate. A 5 m3/s flow reaches about 2 km and a 50 m3/s
     * flow about 7 km, which is the range real eruptions show.
     */
    public static double lavaFlowLengthBlocks(double effusionRateCubicMetresPerSecond) {
        double rate = clamp(effusionRateCubicMetresPerSecond, 0.1D, 500.0D);
        double reference = 2000.0D * Math.sqrt(rate / 5.0D);
        return clamp(reference, 200.0D, 12000.0D);
    }

    /**
     * How fast the front of a flow advances, in blocks per tick.
     *
     * <p>A real flow front crawls: metres per minute near the vent, and a standstill once the flow is long
     * enough to have cooled. This returns a physical speed, and the service multiplies it by a configurable
     * acceleration - because at one-to-one a two kilometre flow would take a real-time day to cross, and a
     * player would never see it move.
     */
    public static double lavaFrontBlocksPerTick(double effusionRate, double distanceFromVent, double flowLength) {
        if (flowLength <= 0.0D || distanceFromVent >= flowLength) {
            return 0.0D;
        }
        // Fastest at the vent where the channel is hottest, slowing as it cools and the flow thickens, and
        // stopping at the cooling-limited length.
        double metresPerMinute = 30.0D * Math.sqrt(clamp(effusionRate, 0.1D, 500.0D) / 5.0D)
                * (1.0D - 0.85D * clamp(distanceFromVent / flowLength, 0.0D, 1.0D));
        return metresPerMinute / 60.0D / 20.0D;
    }

    // ------------------------------------------------------------------ the mountain

    /**
     * The size a volcano is summoned at when the caller does not name one: radius 58, height 29 blocks, which is
     * a stratovolcano you can see from a distance rather than the bump that scale 0 builds.
     */
    public static final int SUMMONED_SCALE = 8;

    /**
     * The size a volcano that forms on its own starts at. Small - it is young, and its eruptions are what make
     * it big - but still an edifice rather than a lump in the ground.
     */
    public static final int NATURAL_START_SCALE = 3;

    /** The flank radius of the edifice, in blocks, for a volcano of this size. */
    public static double edificeRadiusBlocks(int scale) {
        return 10.0D + 6.0D * clamp(scale, 0, 40);
    }

    /**
     * The height of the edifice above its base, in blocks. Real stratovolcanoes sit between 1:2 and 1:3
     * height against radius, so this is deliberately not a spike: a radius of 70 gives a 35 block cone.
     */
    public static double edificeHeightBlocks(int scale) {
        return 5.0D + 3.0D * clamp(scale, 0, 40);
    }

    /** The summit crater's radius, in blocks: about a seventh of the flank radius for a stratovolcano. */
    public static double craterRadiusBlocks(int scale) {
        return Math.max(2.0D, edificeRadiusBlocks(scale) * 0.14D);
    }

    /**
     * The flank radius of the cone at a height above its base, for building the thing one layer at a time.
     *
     * <p>The profile is a straight cone rather than a dome, because that is what a pile of ejected material
     * makes, with a slight outward belly near the base from the spread of older flows. Above the crater rim the
     * radius is the crater's.
     */
    public static double coneRadiusAt(int scale, double heightAboveBase) {
        double height = edificeHeightBlocks(scale);
        double radius = edificeRadiusBlocks(scale);
        if (heightAboveBase <= 0.0D) {
            return radius;
        }
        if (heightAboveBase >= height) {
            return craterRadiusBlocks(scale);
        }
        double fraction = heightAboveBase / height;
        double straight = radius * (1.0D - fraction);
        double bulge = radius * 0.12D * Math.sin(Math.PI * Math.min(1.0D, fraction * 1.6D));
        return Math.max(craterRadiusBlocks(scale), straight + bulge);
    }

    /**
     * How thick the shell is at one layer: the radial distance down to the layer below, and therefore how far
     * in from its own rim this layer has to be filled.
     *
     * <p>This is the difference between a mountain and a set of hoops. Each layer of the cone is one block
     * higher than the one below, and its rim is roughly three blocks further in, so a layer built as a single
     * ring leaves a three block trench between it and the next ring down - which is what a staircase of
     * concentric circular walls looks like, and it is emphatically not a volcano. Filling each layer inwards by
     * this distance closes the surface: the block at radius {@code r - thickness + 1} of one layer sits directly
     * under the rim of the layer below.
     */
    public static int shellThickness(int scale, int layer) {
        if (layer >= coneLayers(scale)) {
            // The top layer meets the crater's own radius, which the cone cannot go inside; one ring is the
            // whole summit.
            return 1;
        }
        double outer = coneRadiusAt(scale, layer);
        double inner = coneRadiusAt(scale, layer + 1);
        return Math.max(1, (int) Math.ceil(outer - inner));
    }

    /**
     * How many blocks the cone's own surface shell is made of: the sum of its annuli.
     *
     * <p>The mountain is built as a shell rather than a solid, because the volume behind the surface is either
     * the terrain that was already there or air nobody can see, and filling it would cost a hundred thousand
     * blocks for no visible difference. What the count has to be is the surface, and the surface is the sum of
     * its rings times how thick each ring is.
     */
    public static int coneShellCount(int scale) {
        int total = 0;
        for (int layer = 0; layer <= coneLayers(scale); layer++) {
            total += ringPoints(scale, layer) * shellThickness(scale, layer);
        }
        return total;
    }

    /**
     * The n-th block of the cone's shell, as {layer, pointOnRing, pointsOnRing, inward}, or null when past the
     * end.
     *
     * <p>Addressing the shell by a single number is what lets a mountain be built a few hundred blocks per tick
     * and resumed exactly where it stopped, on any tick, in any session. {@code inward} is how far in from the
     * layer's own rim the block sits, which is what fills each layer rather than only drawing its edge.
     */
    public static int[] coneShellPoint(int scale, int n) {
        if (n < 0) {
            return null;
        }
        int remaining = n;
        for (int layer = 0; layer <= coneLayers(scale); layer++) {
            int points = ringPoints(scale, layer);
            int thickness = shellThickness(scale, layer);
            int cells = points * thickness;
            if (remaining < cells) {
                return new int[] {layer, remaining / thickness, points, remaining % thickness};
            }
            remaining -= cells;
        }
        return null;
    }

    /**
     * The world position of one shell block, as {x, z}: its ring point's angle at its own radius.
     *
     * <p>Every block of a layer lies on the ray of the ring point it belongs to, pulled inward by the layer's
     * thickness, so a layer is a solid annulus rather than a hoop and the surface has no trenches in it.
     */
    public static int[] shellBlockAt(int scale, int layer, int point, int points, int inward, int centerX, int centerZ) {
        double angle = 2.0D * Math.PI * point / Math.max(1, points);
        double rim = coneRadiusAt(scale, layer);
        // Never inside the crater: the summit is the narrowest the cone gets.
        double radius = Math.max(craterRadiusBlocks(scale) * 0.5D, rim - inward);
        return new int[] {
                centerX + (int) Math.round(Math.cos(angle) * radius),
                centerZ + (int) Math.round(Math.sin(angle) * radius)
        };
    }

    /**
     * The next size of shell to lay, or 0 when the edifice has reached the size it is supposed to be.
     *
     * <p>A mountain is grown rather than conjured: the shell is laid one size at a time, each one wide enough to
     * contain the last, because a single pass at the final size would have to be built in one go. This is the
     * rule that says how far a partly built cone has left to go - and it is separated out from the builder
     * precisely because getting it wrong is invisible: laying one size and then declaring the whole edifice done
     * leaves a small mound wearing a crater cut into the air above it, which reads as anything but a volcano.
     */
    public static int nextConeScale(int builtScale, int target) {
        int wanted = Math.max(1, target);
        int next = Math.max(1, builtScale + 1);
        return next <= wanted ? next : 0;
    }

    /** Whether the edifice is finished: every size of shell laid, and the crater cut. */
    public static boolean edificeComplete(int builtScale, int buildStage, int target) {
        return builtScale >= Math.max(1, target) && buildStage >= 2;
    }

    /** How many layers the cone's shell has, from its base to its summit. */
    public static int coneLayers(int scale) {
        return Math.max(1, (int) Math.ceil(edificeHeightBlocks(scale)));
    }

    /** How many blocks the ring at one layer holds, walking its circumference two blocks at a time. */
    public static int ringPoints(int scale, int layer) {
        double radius = coneRadiusAt(scale, layer);
        return Math.max(1, (int) Math.round(Math.PI * radius));
    }

    /** The world position of one point on a ring, as {x, z}, given the vent's ground position. */
    public static int[] ringPointAt(int scale, int layer, int point, int points, int centerX, int centerZ) {
        double angle = 2.0D * Math.PI * point / Math.max(1, points);
        double radius = coneRadiusAt(scale, layer);
        return new int[] {
                centerX + (int) Math.round(Math.cos(angle) * radius),
                centerZ + (int) Math.round(Math.sin(angle) * radius)
        };
    }

    /** How much a single eruption episode adds to the edifice's size, before terrain and config intervene. */
    public static int edificeGrowthPerEpisode(double intensity) {
        return Math.max(1, (int) Math.round(0.6D + intensity * 0.45D));
    }

    /** The size an episode of this intensity leaves behind, capped so a mountain cannot grow forever. */
    public static int scaleAfterEpisode(int scale, double intensity) {
        return clamp(scale + edificeGrowthPerEpisode(intensity), 0, 40);
    }

    // ------------------------------------------------------------------ where they are

    /**
     * Whether a spot lies in a volcanic province, as a number in 0..1 that only depends on the world seed and
     * the cell the spot is in.
     *
     * <p>A volcano is not weather: it does not wander in from a neighbouring biome, so it has to be somewhere
     * permanent and plausible. Deriving the answer from the seed rather than rolling dice means the same
     * ground is volcanic every time the world is loaded, and that a province is a region rather than a point -
     * two volcanoes can be neighbours without the world remembering anything about them.
     */
    public static double provincePotential(long worldSeed, int blockX, int blockZ) {
        long mixed = mix(worldSeed, blockX >> 7, blockZ >> 7);
        return (mixed >>> 11) * 0x1.0p-53D;
    }

    /** The seed of a province cell, so a province's own character is stable too. */
    public static long provinceSeed(long worldSeed, int blockX, int blockZ) {
        return mix(worldSeed, blockX >> 7 | 0x5F3AL, blockZ >> 7 | 0x91B7L);
    }

    private static long mix(long seed, long x, long z) {
        long value = seed;
        value ^= x * 0x9E3779B97F4A7C15L;
        value = Long.rotateLeft(value * 0xBF58476D1CE4E5B9L, 27);
        value ^= z * 0x94D049BB133111EBL;
        value ^= value >>> 31;
        value *= 0xD6E8FEB86659FD93L;
        value ^= value >>> 29;
        return value;
    }

    // ------------------------------------------------------------------ shaking and mud

    /**
     * How hard the ground shakes at a distance from a volcano of this intensity, 0..1.
     *
     * <p>Shaking falls off with distance faster than the square root and slower than the square, and an
     * eruption's own magnitude sets the scale. This is the number the client turns into camera movement, and
     * the service turns into a chance of a block coming loose.
     */
    public static double quakeShake(double intensity, double distanceBlocks) {
        double magnitude = 2.4D + intensity * 0.45D;
        double distance = Math.max(8.0D, distanceBlocks);
        double acceleration = Math.pow(10.0D, magnitude - 3.0D) / Math.pow(distance / 64.0D, 1.5D);
        return clamp(acceleration, 0.0D, 1.0D);
    }

    /** How often the ground shakes, in ticks, for a phase of this intensity. */
    public static int quakeIntervalTicks(double intensity, int cadenceTicks) {
        double scaled = cadenceTicks / (0.6D + 0.4D * clamp(intensity, MIN_INTENSITY, MAX_INTENSITY));
        return Math.max(10, (int) Math.round(scaled));
    }

    /**
     * Whether rainfall on a slope of fresh ash will come down as a lahar.
     *
     * <p>This is the volcano's second act, and the one most people have never heard of: unconsolidated ash
     * turns to a moving slurry of concrete the first time it rains, and it does so for years after the
     * eruption, while the ash is still loose. All four conditions have to hold - water, enough ash, ash that
     * is still fresh, and a slope to run down.
     */
    public static boolean laharTrigger(double rainRate, double ashMm, double ashAgeDays, double slopeDegrees, double minRainRate, double minAshMm, double maxAshAgeDays, double minSlopeDegrees) {
        return rainRate >= minRainRate
                && ashMm >= minAshMm
                && ashAgeDays <= maxAshAgeDays
                && slopeDegrees >= minSlopeDegrees;
    }

    /** How fast a lahar runs, in blocks per tick: a debris flow on a volcano moves like a river of mud. */
    public static double laharBlocksPerTick(double slopeDegrees, double acceleration) {
        double metresPerSecond = clamp(2.0D + slopeDegrees * 0.35D, 2.0D, 12.0D);
        return metresPerSecond / 20.0D * clamp(acceleration, 1.0D, 64.0D);
    }

    /** The slope between two heights over a horizontal run, in degrees. */
    public static double slopeDegrees(double riseBlocks, double runBlocks) {
        if (runBlocks <= 0.0D) {
            return 90.0D;
        }
        return Math.toDegrees(Math.atan2(Math.abs(riseBlocks), runBlocks));
    }

    /**
     * The size of a volcano's next eruption, as a VEI, drawn from its own seed and episode number.
     *
     * <p>Most eruptions are small and the biggest ones are rare, and the shape of that rarity is the ladder
     * itself: the draw is raised to a power so the low rungs are common and a VEI 5 is something a world sees
     * once. Deriving it rather than rolling a die means an episode's size survives a reload - a volcano that
     * has already decided to go big does not get a reroll because the server restarted.
     */
    public static double episodeIntensity(long volcanoId, int episode, long worldSeed, double maxIntensity) {
        long mixed = mix(worldSeed ^ volcanoId * 0x2545F4914F6CDD1DL, episode, 0x5EEDL);
        double unit = (mixed >>> 11) * 0x1.0p-53D;
        return clamp(clamp(maxIntensity, MIN_INTENSITY, MAX_INTENSITY) * Math.pow(unit, 1.8D), 0.0D, MAX_INTENSITY);
    }

    /** How long a volcano sleeps between episodes, in ticks, from its own seed and episode number. */
    public static long quiescenceTicks(long volcanoId, int episode, long worldSeed, long minTicks, long maxTicks) {
        long mixed = mix(worldSeed ^ volcanoId * 0x9E3779B97F4A7C15L, episode, 0xA11CE5L);
        double unit = (mixed >>> 11) * 0x1.0p-53D;
        return minTicks + (long) ((maxTicks - minTicks) * unit);
    }

    public static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
