package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.util.Mth;
import weather2.weathersystem.storm.StormObject;

/**
 * The 1.12.2 hurricane shield, as pure functions: the wide flat disc with an eye punched in it, the
 * spiral bands that coil through it, the doubled spin, and the broad eye-wall wind profile that goes
 * with them.
 *
 * <p>Weather2 1.12.2 did not draw a hurricane as a stronger supercell. A storm whose biome at genesis
 * was an ocean came out as {@code StormType.WATER}, and the renderer gave it a completely different
 * body: {@code gapRadius = size / 1.75} with no deck particle allowed inside it (any that drifted in
 * was expired), a disc twice a land storm's width, spin doubled ({@code if (stormType == WATER)
 * speed *= 2.0}) and radial angle offsets at +30/+40/+10 degrees turning the population into spiral
 * bands. The eye, the width and the bands are exactly what makes a hurricane read as a hurricane, so
 * they are carried across here as geometry rather than as a re-skin.
 *
 * <p>Two of 1.12.2's numbers are deliberately not carried across, because they were only ever right
 * for a renderer that drew its eye far above the player. {@code size / 1.75} makes the eye 0.57 of the
 * disc's radius, which is a <em>third of the disc's area</em> - with a Minecraft deck a few dozen blocks
 * overhead, that is a hole in the sky, not an eye, and it reads as one from under the storm. A real
 * storm's eye runs about 0.10 to 0.20 of its shield. The cleared circle is therefore much smaller by
 * default, and the deck's floor closes in to a mouth even inside that, out of which the eye wall rises:
 * see {@link #wallRadiusAt(double, double)}.
 *
 * <p>Everything in this class is a pure function of the storm and the config, so the same numbers
 * drive the client's deck placement and the server's wind field: the eye the player can see is the
 * eye the wind is calm in, and a shield that visibly spins up does so in the air the probes measure.
 */
public final class HurricaneShield {

    /** Blocks. Below this the shield would be an eye with no storm around it; above it, a storm the
     * server cannot iterate. */
    public static final double MIN_SHIELD_RADIUS = 160.0D;
    public static final double MAX_SHIELD_RADIUS = 2400.0D;
    /** The eye is nearly calm, not dead calm: 6% of the eyewall wind at the very centre. */
    private static final double EYE_CALM_FRACTION = 0.06D;
    /**
     * How fast the wind decays outside the eye wall. A tornado falls off at 0.72 - tight and violent
     * right at the core - while a hurricane's field is broad by definition: at twice the eye radius a
     * 1.12.2 hurricane still carried most of its strength, which is 0.5 here.
     */
    private static final double OUTER_DECAY = 0.50D;
    /** Full turn in radians. */
    private static final double TAU = Math.PI * 2.0D;

    private HurricaneShield() {
    }

    /** Whether the shield feature may run at all: the knob is on and one of the two families is allowed. */
    public static boolean supported() {
        return Config.HURRICANE_SHIELD_ENABLED.get()
                && (Config.HURRICANES_ENABLED.get() || Config.CYCLONES_ENABLED.get());
    }

    /**
     * Whether this storm wears the hurricane shield.
     *
     * <p>The storm's own addon state says which family it belongs to, and that state is synced to the
     * client with the storm, so both sides agree. The fallback covers the first frames after a client
     * comes into range - or a save whose addon state never made it across - using only what Weather2
     * itself syncs: a water storm that has climbed the ladder. A waterspout is also a water storm, and
     * it is excluded first, because a waterspout is a funnel and must keep looking like one.
     */
    public static boolean isShieldStorm(StormObject storm) {
        if (storm == null || storm.pos == null || !supported()) {
            return false;
        }
        StormSubtype subtype = DynamicStormController.stateFor(storm).subtype();
        if (subtype == StormSubtype.HURRICANE || subtype == StormSubtype.CYCLONE) {
            return true;
        }
        // The waterspout tag is only consulted after the tropical branch, and that order matters: Weather2
        // sets it on water storms, so read first it stripped the shield - disc, eye, eye wall, bands - off a
        // hurricane and left a bare funnel standing in its middle, which is the one storm this mod must not
        // draw. A waterspout that is genuinely one is still excluded here and keeps its funnel.
        if (storm.attrib_waterSpout) {
            return false;
        }
        return storm.stormType == StormObject.TYPE_WATER
                && TropicalCyclone.isTropical(storm.levelCurIntensityStage);
    }

    /** The anticyclonic twin: same shield, opposite rotation. */
    public static boolean antiCyclonic(StormObject storm) {
        return storm != null && DynamicStormController.stateFor(storm).isAntiCyclonic();
    }

    /** Which way the shield turns: +1 cyclonic, -1 anticyclonic. */
    public static double spinSign(StormObject storm) {
        return antiCyclonic(storm) ? -1.0D : 1.0D;
    }

    /**
     * mph expressed as blocks per tick, which is how fast the air the shield is made of is actually
     * moving. One mph is 0.447 m/s and a block is a metre, so 150 mph is about 3 blocks a tick.
     */
    private static final double MPH_TO_BLOCKS_PER_TICK = 0.02D;
    /** A shield always turns: even a dying depression's deck should not stand still. */
    private static final double MIN_RIM_SPEED_BLOCKS_PER_TICK = 1.2D;
    /** And never strobes, however violent the storm gets. */
    private static final double MAX_RIM_SPEED_BLOCKS_PER_TICK = 12.0D;

    /**
     * Radians the shield turns per tick.
     *
     * <p>The rim is driven at the speed of the storm's OWN wind, doubled by default - 1.12.2's
     * {@code speed *= 2.0} for a water storm - rather than at Weather2's raw spin rate. A spin rate is a
     * rate for a small funnel: applied rigidly to a shield hundreds of blocks across it would throw the
     * rim around at tens of blocks a tick and read as a strobing blur. Driving the rim at the wind speed
     * means the cloud sweeps at the speed of the air carrying it, so a stronger storm visibly turns
     * faster, a wider storm turns slower (same rim speed, longer circumference), and the bands of a
     * category 5 wheel around in about twenty seconds instead of three.
     */
    public static double spinPerTick(StormObject storm) {
        double radius = Math.max(MIN_SHIELD_RADIUS, shieldRadius(storm));
        double windMph = storm == null ? 0.0D : TornadoWindField.maxWindMph(storm);
        double rimSpeed = Mth.clamp(windMph * MPH_TO_BLOCKS_PER_TICK,
                MIN_RIM_SPEED_BLOCKS_PER_TICK, MAX_RIM_SPEED_BLOCKS_PER_TICK)
                * Config.HURRICANE_DISC_SPIN_MULTIPLIER.get();
        return rimSpeed / radius;
    }

    /** The shield's radius in blocks. Genesis grows the storm's own footprint to the shield size. */
    public static double shieldRadius(StormObject storm) {
        return Mth.clamp(storm == null ? MIN_SHIELD_RADIUS : (double) storm.size, MIN_SHIELD_RADIUS, MAX_SHIELD_RADIUS);
    }

    /**
     * Radius of the calm eye: the circle the wind is quiet in and the deck's floor closes down to. It is
     * configurable rather than fixed at 1.12.2's {@code size / 1.75} - see the class note on why.
     */
    public static double eyeRadius(double shieldRadius) {
        return shieldRadius * eyeFraction();
    }

    public static double eyeFraction() {
        return Mth.clamp(Config.HURRICANE_EYE_FRACTION.get(), 0.05D, 0.95D);
    }

    /** Where the eye wall stands at its widest: just outside the calm eye itself. */
    public static double deckInnerRadius(double shieldRadius) {
        return eyeRadius(shieldRadius) * 1.04D;
    }

    /**
     * The aperture: the radius of the shaft's mouth at the storm's floor, as a fraction of the eye wall's
     * width.
     *
     * <p>1.12.2 cleared the eye at every height, which is only an eye if you never look at it from
     * underneath: its deck was a slab with a circle punched through it, and the whole eye was sky from
     * below. A real eye is a vault - the cloud floor closes in to a much smaller mouth and the wall climbs
     * from that mouth - so the sky a viewer under the storm can actually see is the aperture, not the eye.
     */
    public static final double WALL_MOUTH_FRACTION = 0.34D;

    /** Radius of the mouth the deck's floor closes in to, and the ring the eye wall stands on. */
    public static double wallMouthRadius(double shieldRadius) {
        return deckInnerRadius(shieldRadius) * WALL_MOUTH_FRACTION;
    }

    /** Where the wall's flare tops out, as a fraction of the way up the tower. */
    public static final double WALL_RISE = 0.70D;
    /** Shape of the flare below that: slightly convex, so the foot opens fast and the top eases. */
    private static final double WALL_RISE_CURVE = 0.85D;

    /**
     * The eye wall's inner radius at a height through the tower: the surface the cloud's inner boundary
     * follows, and therefore the surface that decides how much sky is left to a viewer below.
     *
     * <p>It flares out of the mouth and then <b>holds its ring</b>. It used to close back in over the top and
     * seal to the axis, and that crown is exactly what made the middle of a hurricane read as a tornado: the
     * deck's inner band climbs the tower, so a wall that pinches to the axis draws a solid cone of cloud
     * rising a hundred blocks out of the disc and tapering to a point - the silhouette of a funnel, standing
     * in the one place a hurricane must not have one.
     *
     * <p>An eye wall is a ring and the eye inside it is open to the sky, so the ring is held. The window a
     * viewer below can actually see through does not grow because of it: the floor still closes in to a
     * narrow mouth ({@link #WALL_MOUTH_FRACTION}), and it is the ring's own surface that oblique sight lines
     * meet - the same job the crown was doing, done by geometry that is round instead of pointed.
     *
     * @param heightFraction 0 on the deck's floor, 1 at the top of the tower.
     */
    public static double wallRadiusAt(double shieldRadius, double heightFraction) {
        double mouth = wallMouthRadius(shieldRadius);
        double inner = deckInnerRadius(shieldRadius);
        double u = Mth.clamp(heightFraction, 0.0D, 1.0D);
        if (u <= WALL_RISE) {
            return mouth + (inner - mouth) * Math.pow(u / WALL_RISE, WALL_RISE_CURVE);
        }
        // Above the flare the wall keeps its ring. Nothing is drawn inside it, so the shaft the eye wall
        // stands around stays hollow all the way to the top of the tower.
        return inner;
    }

    public static int bandCount() {
        return Mth.clamp(Config.HURRICANE_BAND_COUNT.get(), 2, 12);
    }

    /** How far the bands coil between the eye wall and the shield edge, in full turns. */
    public static double bandShearTurns() {
        return Mth.clamp(Config.HURRICANE_BAND_SHEAR.get(), 0.0D, 3.0D);
    }

    /**
     * Wind at a distance from the eye, as a fraction of the storm's own maximum.
     *
     * <p>Inside the eye it is nearly calm and climbs smoothly to the wall; outside it decays as
     * {@code (wall / radius) ^ 0.5}. At the shield edge (1.75 eye radii at the default fraction) that
     * leaves about three quarters of the peak, which is the point: a hurricane's danger is not a thin
     * ring but a wide field, and a player standing a quarter of the way out is still in eyewall wind.
     */
    public static double windFraction(double radius, double eyeRadius) {
        if (eyeRadius <= 1.0E-3D) {
            return 1.0D;
        }
        if (radius < eyeRadius) {
            double t = Mth.clamp(radius / eyeRadius, 0.0D, 1.0D);
            return EYE_CALM_FRACTION + (1.0D - EYE_CALM_FRACTION) * t * t;
        }
        return Math.pow(eyeRadius / radius, OUTER_DECAY);
    }

    /**
     * Stable per-particle radial fraction across the shield, uniform in <em>area</em> rather than in
     * radius so the disc is filled evenly instead of crowding its middle. {@code unit} must be a stable
     * hash of the particle's identity, so a particle keeps its ring as the storm turns.
     *
     * <p>The floor is sampled from the mouth out, not from the eye out. Sampled from the eye the deck can
     * only ever be a ring with a window in the middle of it, and everything under the eye is sky; sampled
     * from the mouth the floor is closed all the way in to the aperture, and it is the wall climbing out
     * of that aperture - not a hole in a plate - that opens the eye.
     */
    public static double deckRadialFraction(double unit, double shieldRadius) {
        double inner = wallMouthRadius(shieldRadius);
        double outer = Math.max(inner + 1.0D, shieldRadius);
        double squared = inner * inner + (outer * outer - inner * inner) * Mth.clamp(unit, 0.0D, 1.0D);
        return Math.sqrt(squared) / outer;
    }

    /**
     * Angle of a shield particle: its band's own arm, the storm's rotation, a jitter that gives the arm
     * thickness, and the coil that wraps it further around the closer it sits to the eye - which is what
     * turns evenly spaced arms into the spiral bands of a mature hurricane. The coil follows the spin
     * sign so an anticyclonic cyclone's bands trail the other way.
     */
    public static double bandAngle(double armUnit, double radialFraction, double phase, double spinSign) {
        int bands = bandCount();
        double armPosition = Mth.clamp(armUnit, 0.0D, 0.9999D) * bands;
        double arm = Math.floor(armPosition);
        double armWidth = TAU / bands;
        double jitter = (armPosition - arm - 0.5D) * armWidth * 0.55D;
        double eyeF = eyeFraction();
        double coilProgress = Mth.clamp((radialFraction - eyeF) / Math.max(1.0E-3D, 1.0D - eyeF), 0.0D, 1.0D);
        double coil = bandShearTurns() * TAU * coilProgress * spinSign;
        return arm * armWidth + jitter + coil + phase;
    }
}
