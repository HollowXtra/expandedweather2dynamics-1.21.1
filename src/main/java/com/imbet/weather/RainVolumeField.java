package com.imbet.weather;

/**
 * The vertical shape of a storm's rain, and the structure of the falling water inside it.
 *
 * <p>{@link PrecipitationField} says where the rain is on the ground plane - the core, the hook, the
 * slot. This says how it is stacked in the air: dense under the cloud base, thinning as it falls, gone
 * above the column's top, and eaten from the bottom when there is virga in the air below. It also carries
 * the visible structure of the fall, which is what makes the difference between a grey solid and rain.
 *
 * <p>Kept free of Minecraft on purpose, and mirrored exactly by {@code rain_volume.fsh}, so the two can be
 * measured against each other: the volumetric rain is drawn either by that program, per fragment, or - when
 * a shader pack has replaced the program - by sampling these functions per vertex on the client thread.
 * Those two paths have to agree about where the rain is, or the same storm would be one shape with a pack
 * on and another with it off.
 *
 * <p>The mirrored constants are asserted against the shader's own text by {@code RainVolumeSim}, which
 * counts each number in each function rather than checking that it appears somewhere.
 */
public final class RainVolumeField {
    private RainVolumeField() {
    }

    /** How fine the falling structure is: one lane of heavier rain every twenty-odd blocks. */
    public static final double LANE_SCALE = 0.045D;

    /** How far below the cloud base the column starts thinning, as a fraction of its height. */
    private static final double FOOT_SHAPE = 0.40D;

    /** What share of the column virga takes away when it is at full strength. */
    private static final double VIRGA_REACH = 0.62D;

    /**
     * The vertical profile of the column at one height: 0 above it, 1 at its densest, less at the foot.
     *
     * <p>Three things shape it, and each is here because of what it looks like when it is wrong. Rain
     * leaves the cloud compressed and thins as it falls, so the column is denser at the top. It has to end
     * somewhere, and on a soft shoulder rather than a line, or every storm has a lid drawn across the sky.
     * And virga has to take the bottom away <em>only when there is virga</em> - a cut that applies at zero
     * lands entirely on the one height a player ever looks at a tornado from, where it silently removes
     * three quarters of the rain they are looking through.
     */
    public static double heightProfile(double worldY, double ground, double top, double virga) {
        double span = Math.max(1.0D, top - ground);
        double hf = clamp01((worldY - ground) / span);
        double shape = mix(FOOT_SHAPE, 1.0D, smoothstep(0.0D, 0.86D, hf));
        shape *= 1.0D - smoothstep(0.90D, 1.0D, hf);
        double eaten = clamp01(virga);
        double cut = eaten * VIRGA_REACH;
        shape *= mix(1.0D, smoothstep(cut, cut + 0.16D, hf), eaten);
        return shape;
    }

    /**
     * The falling structure of the rain at a point, as a multiplier around 1.
     *
     * <p>Lanes of heavier rain in world XZ with brightness running down them, so the pattern is anchored
     * in the world rather than on the screen: look at the same rain from two places and it is the same
     * rain, and flying through it has parallax. The swing is deliberately about one and a half to one
     * rather than nothing to everything, because the per-slice sampling randomises a sparse pattern into
     * grain - measured at 37% for threads in empty air against 0.7% for this.
     *
     * <p>The mean is around 0.93, so a density of 1 in the field means very nearly 1 on screen.
     */
    public static double fallStructure(double x, double z, double worldY, double time, double fallSpeed, double laneScale) {
        // A slow lateral warp, so the lanes do not march in a grid.
        double warpX = valueNoise(x * 0.045D, z * 0.045D) - 0.5D;
        double warpZ = valueNoise(z * 0.045D, x * 0.045D) - 0.5D;
        double u = (x + warpX * 7.0D) * laneScale;
        double v = (z + warpZ * 7.0D) * laneScale;
        double cellU = Math.floor(u);
        double cellV = Math.floor(v);
        double laneU = u - cellU - 0.5D;
        double laneV = v - cellV - 0.5D;
        double jitter = hash21(cellU, cellV);
        double laneWeight = 1.0D - smoothstep(0.16D, 0.62D, Math.hypot(laneU, laneV));
        // Each lane falls at its own rate, which is what stops the whole sky pulsing in time.
        double fall = fract(worldY * 0.055D - time * fallSpeed * (0.85D + jitter * 0.45D) + jitter);
        double pulse = smoothstep(0.0D, 0.30D, fall) * (1.0D - smoothstep(0.62D, 1.0D, fall));
        return 0.70D + 0.42D * (0.30D + 0.70D * laneWeight) * (0.50D + 0.50D * pulse);
    }

    /**
     * The rain at a world point, 0 to 1: the horizontal field, thinned by height and structured by the
     * fall.
     *
     * <p>This is the whole density the volume is made of, and the single place the two drawing paths get
     * it from.
     */
    public static double density(PrecipitationField.Geometry field, double stormX, double stormZ,
                                 double forwardX, double forwardZ, double rightX, double rightZ,
                                 double x, double z, double worldY, double ground, double top,
                                 double virga, double time, double fallSpeed, double laneScale) {
        double horizontal = PrecipitationField.densityAt(field, stormX, stormZ,
                forwardX, forwardZ, rightX, rightZ, x, z);
        if (horizontal <= 0.0D) {
            return 0.0D;
        }
        double profile = heightProfile(worldY, ground, top, virga);
        if (profile <= 0.0D) {
            return 0.0D;
        }
        return clamp01(horizontal * profile * fallStructure(x, z, worldY, time, fallSpeed, laneScale));
    }

    /**
     * The light one slab of rain takes out, and therefore the alpha to draw it with.
     *
     * <p>Beer-Lambert over the slab rather than a straight product, so the total does not change with the
     * number of slices: twelve thin slabs and two thick ones take out the same light, which is what makes
     * the slice count a quality dial instead of a density one.
     */
    public static double slabAlpha(double density, double extinctionPerBlock, double slabThickness) {
        return 1.0D - Math.exp(-Math.max(0.0D, density) * extinctionPerBlock * slabThickness);
    }

    // -------------------------------------------------------------------------------------------- fog

    /**
     * The share of a fragment that is the world's fog colour at a distance: vanilla's own curve.
     *
     * <p>Transcribed from {@code fog.glsl}, because the rain is drawn alongside everything the world's fog
     * already reaches and has to agree with it about where the horizon is. Vanilla's is a smoothstep from
     * {@code FogStart} to {@code FogEnd}, nothing at all before the start, and everything at the end, with
     * the fog colour's own alpha scaling the mix.
     *
     * <p>Reading the live fog - rather than deriving one - is what makes the rain follow whatever the
     * world's fog is actually doing: the mod's own collapsed fog inside a tornado, morning fog, a mist
     * bank, a shader pack's height fog, or the vanilla underwater fog. The volume is drawn after all of
     * those have been chosen for the frame.
     */
    public static double worldFog(double distance, double fogStart, double fogEnd) {
        if (distance <= fogStart) {
            return 0.0D;
        }
        return distance < fogEnd ? smoothstep(fogStart, fogEnd, distance) : 1.0D;
    }

    /**
     * The fog the rain itself is in: the world's fog and the storm's own mist together.
     *
     * <p>Transmittances multiply, which is what makes this a physical statement rather than a second fade
     * stacked on the first: the light reaching the eye from a fragment is what the world's fog passed, times
     * what the storm's own air passed. Two consequences are the point of doing it this way. The mist vanishes
     * <em>exactly</em> at zero, so a storm in clear air fogs no differently from anything else in it - a cut
     * that applied at zero would grey the rain everywhere and hide the world's fog behind it. And the two
     * can never double-count: at full fog the result is full fog, not twice over.
     */
    public static double fog(double distance, double fogStart, double fogEnd, double mistPerBlock) {
        double world = worldFog(distance, fogStart, fogEnd);
        double travelled = Math.max(0.0D, distance);
        double mist = Math.exp(-Math.max(0.0D, mistPerBlock) * travelled);
        return clamp01(1.0D - (1.0D - world) * mist);
    }

    /**
     * The per-block mist coefficient for a share of the light lost over a hundred blocks.
     *
     * <p>Phrased as a share over a hundred blocks because that is what can be judged by looking: at the
     * default, a storm's own air takes half the light out of what is a hundred blocks away inside it.
     * Exponential rather than linear, so half over a hundred is three quarters over two hundred and seven
     * eighths over three, the way fog actually thickens.
     */
    public static double mistPerBlock(double lostOverHundredBlocks) {
        double lost = clamp01(lostOverHundredBlocks);
        if (lost <= 1.0E-4D) {
            return 0.0D;
        }
        return -Math.log(1.0D - lost) / 100.0D;
    }

    /**
     * How misty the air is where the rain is: 1 in the thickest part of the storm, less where it is thin.
     *
     * <p>A curtain of rain is its own fog, so the mist cannot be a constant across a storm - it would grey
     * the rain-free notch along with everything else and take the clear slot with it, which is the one place
     * a supercell's rain deliberately is not. Following the field's own density means the slot stays a slot
     * and the core is the blindest place in the storm.
     */
    public static double mistScale(double density, double densityWeight) {
        double weight = clamp01(densityWeight);
        return 1.0D - weight + weight * clamp01(density);
    }

    // -------------------------------------------------------------------------------------------- light

    /**
     * How lit the rain is: the sky it is standing against, floored so a night storm is still visible.
     *
     * <p>The sky colour is the whole of the sun here, and deliberately so. Vanilla builds it from the sun's
     * angle on the day cycle, the rain and thunder levels, and the flash of a strike - and this mod drives
     * the last three, so {@code getSkyColor} is already the answer to "how bright and what colour is the
     * light on this storm, right now": warm and full at midday, orange at sunset, near-black at midnight,
     * flattened toward grey under a heavy deck, and pushed to blue-white by a strike. Reading one value
     * means the rain cannot disagree with the sky above it, which is what "the light touches it" has to mean
     * to look right.
     *
     * <p>The floor is applied by <em>scaling</em> rather than by taking a maximum against a grey: a
     * maximum would wash the colour out, and rain at night under a storm should be dark blue, not grey. Only
     * a sky with no light in it at all - which the game can produce with a black sky during a total eclipse
     * of a storm - falls back to a flat floor.
     */
    public static double[] light(double skyRed, double skyGreen, double skyBlue,
                                 double floor, double localFlash, double flashGain) {
        double red = clamp01(skyRed);
        double green = clamp01(skyGreen);
        double blue = clamp01(skyBlue);
        double brightest = Math.max(red, Math.max(green, blue));
        double minimum = clamp01(floor);
        if (brightest < minimum) {
            if (brightest <= 1.0E-4D) {
                red = minimum;
                green = minimum;
                blue = minimum;
            } else {
                double scale = minimum / brightest;
                red *= scale;
                green *= scale;
                blue *= scale;
            }
        }
        // A strike nearer to the rain than to the eye lights that rain and not the rest of the storm: the
        // channel's own light, on the water it is falling through. Vanilla's flash colour, which is what the
        // sky is pushed toward for the same strike.
        double flash = Math.max(0.0D, localFlash) * Math.max(0.0D, flashGain);
        return new double[] {
                clamp01(red + 0.80D * flash),
                clamp01(green + 0.80D * flash),
                clamp01(blue + 1.00D * flash)
        };
    }

    /**
     * How much of a strike's light reaches a place, by how far it is from the channel.
     *
     * <p>A floor rather than a fade to nothing: lightning twenty kilometres away still lights the sky, it
     * just does not light it much. Shared with the sky's own flash, so the light on the rain and the light
     * on the sky are the same light.
     */
    public static double flashFalloff(double distance) {
        return FLASH_FALLOFF_FLOOR
                + (1.0D - FLASH_FALLOFF_FLOOR) / (1.0D + Math.max(0.0D, distance) * FLASH_FALLOFF_RATE);
    }

    /** How much of a strike's light is still seen a kilometre away. */
    public static final double FLASH_FALLOFF_FLOOR = 0.30D;

    /** How fast a strike's light falls away: 1.0 overhead, 0.51 at a kilometre. */
    public static final double FLASH_FALLOFF_RATE = 0.0022D;

    /** GLSL's mix. */
    public static double mix(double first, double second, double amount) {
        return first + (second - first) * amount;
    }

    /** GLSL's smoothstep. */
    public static double smoothstep(double edge0, double edge1, double x) {
        double t = clamp01((x - edge0) / Math.max(1.0E-9D, edge1 - edge0));
        return t * t * (3.0D - 2.0D * t);
    }

    public static double clamp01(double value) {
        return value < 0.0D ? 0.0D : Math.min(value, 1.0D);
    }

    private static double fract(double value) {
        return value - Math.floor(value);
    }

    /** The same value hash the shader uses, so the two patterns are the same pattern. */
    private static double hash21(double x, double y) {
        return fract(Math.sin(x * 127.1D + y * 311.7D) * 43758.5453123D);
    }

    /** The same value noise the shader uses. */
    private static double valueNoise(double x, double y) {
        double ix = Math.floor(x);
        double iy = Math.floor(y);
        double fx = x - ix;
        double fy = y - iy;
        double a = hash21(ix, iy);
        double b = hash21(ix + 1.0D, iy);
        double c = hash21(ix, iy + 1.0D);
        double d = hash21(ix + 1.0D, iy + 1.0D);
        double ux = fx * fx * (3.0D - 2.0D * fx);
        double uy = fy * fy * (3.0D - 2.0D * fy);
        return a + (b - a) * ux + (c - a) * uy * (1.0D - ux) + (d - b) * ux * uy;
    }
}
