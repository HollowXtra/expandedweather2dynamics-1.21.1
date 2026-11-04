package com.imbet.client;

import com.imbet.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * The ocean's wave field: how big the swell is, and how high the water surface is at any point.
 *
 * <p>This is the 1.21.1 answer to the old Weather2 wave system, which kept a per-column height grid and
 * stepped it as a physical simulation. That grid had to be built and stepped on both sides and every wave
 * had to be synced; the sea here is instead a <em>pure function of position and time</em> fed by the wind,
 * so every client computes the identical surface from the wind it already samples and nothing about the
 * waves ever crosses the wire. There is no wave state to keep alive across a world load either.
 *
 * <p>The physics is deliberately shallow-water-free: Minecraft's ocean is deep everywhere it matters, so the
 * trains use the deep-water dispersion relation, {@code T = sqrt(2 * pi * lambda / g)}, which is why a long
 * swell travels fast and a short chop crawls. Three octaves of that are summed, the trailing two fanned off
 * the wind direction the way real wind-sea is spread around it, and the whole sum is normalized so the
 * surface can never exceed its configured peak.
 *
 * <p><b>The sea state is held, and changes are blended.</b> A travelling train's height is
 * {@code sin(k * (dir . p) - omega * t)}, so both the wave number {@code k} and the heading {@code dir} sit
 * inside the phase, multiplied by the world position. That makes any live change to either of them a
 * whole-surface move: the wind the sea is fed is smoothed and slightly noisy, so re-deriving the wavelength
 * and heading every tick used to shift every crest by several radians per tick at a hundred blocks out,
 * which is what reads as the sea shimmering whenever the wind moves. The fix is to stop re-parameterizing
 * the running surface: the surface is drawn at a <em>held</em> state (a fixed wavelength and heading), and
 * when the wind has drifted far enough from it the field takes a fresh state and blends the two over a few
 * seconds. Because a state's own phase keeps running while it is held, and the blend weight is continuous,
 * the surface never jumps - the change is a slow crossfade between two seas rather than a snap. The
 * wavelength and heading are consequently quantized to the tolerance below, which is far finer than an eye
 * can pick out of a swell.
 *
 * <p>The swell does not jump to its wind speed. Real seas take hours to build and much less time to decay,
 * so the amplitude chases its target with an asymmetric time constant - a hurricane arriving builds the sea
 * over about twenty seconds of game time and the swell eases off over about ten once it has gone. That lag
 * is the point of having any state here at all: it is what makes the sea read as having been blowing rather
 * than as being switched on.
 */
public final class OceanWaveField {

    /** Gravitational acceleration in blocks per second squared - the mod's blocks are metres. */
    private static final double GRAVITY = 9.81D;

    private static final float[] OCTAVE_WEIGHT = { 1.0F, 0.52F, 0.27F };
    private static final float[] OCTAVE_WAVELENGTH = { 1.0F, 0.47F, 0.24F };
    private static final float[] OCTAVE_FAN = { 0.0F, 0.30F, -0.38F };

    /** Prevailing direction when nothing is blowing, so calm water still has a chop to look at. */
    private static final double PREVAILING_X = 0.94D;
    private static final double PREVAILING_Z = 0.34D;

    /** The theoretical breaking limit: a wave steeper than about a seventh of its length breaks. */
    private static final double BREAKING_STEEPNESS = 1.0D / 7.0D;

    /** How fast the wind reading is followed, per tick. Slower than a tick so a gust does not whip the sea. */
    private static final float WIND_SMOOTHING = 0.06F;

    /** How steeply the swell rises with wind speed; a fully developed sea grows faster than linearly. */
    private static final double SWELL_EXPONENT = 1.5D;

    /**
     * How far the wind may drift from the state the surface is drawn at before a new state is taken, as a
     * fraction of the wavelength and as an angle in radians. Anything under this is ignored outright, which is
     * what keeps the small tick-to-tick wander of the sampled wind from moving the sea at all.
     */
    private static final double STATE_WAVELENGTH_TOLERANCE = 0.05D;
    private static final double STATE_DIRECTION_TOLERANCE = 0.07D;

    /**
     * How long a change of state is blended for, in seconds. Long enough that the crossfade is a swell
     * arriving rather than a cut, short enough that the sea still answers a gust within one build-up.
     */
    private static final double STATE_TRANSITION_SECONDS = 3.0D;

    private static final int OCTAVE_COUNT = OCTAVE_WEIGHT.length;

    /**
     * The sum of the octave weights. It is the same number every frame because the weights are constants, so it
     * is added up once here rather than once per sample.
     */
    private static final double OCTAVE_WEIGHT_TOTAL = octaveWeightTotal();

    private static Vec3 windDirection = new Vec3(PREVAILING_X, 0.0D, PREVAILING_Z).normalize();
    private static Vec3 targetDirection = new Vec3(PREVAILING_X, 0.0D, PREVAILING_Z).normalize();
    private static double windSpeedMph;
    private static float swell;
    private static float peakHeight;
    private static float wavelength;
    private static double targetWavelength;

    /*
     * The per-train constants of the surface.
     *
     * Every term of the sum below is a function of the sea state - the wavelength and the wind direction - and
     * not of the point being sampled, but the renderer asks for thousands of points per frame: the lattice is
     * 57 by 57 corners at the shipped radius, plus once per block of every cell the shoreline refines. Working
     * the constants out per sample meant an atan2, three square roots and a handful of divisions per corner for
     * an answer that had not changed since the tick began. They are worked out here instead, whenever the state
     * they come from moves, and the sample loop below is left with three sines and a few multiplies.
     *
     * With the state held, the cache only moves when a transition commits, which is the other half of why the
     * sea stopped shimmering: the loop is now sampling the identical constants for seconds at a time.
     */
    private static final double[] TRAIN_K = new double[OCTAVE_COUNT];
    private static final double[] TRAIN_COS = new double[OCTAVE_COUNT];
    private static final double[] TRAIN_SIN = new double[OCTAVE_COUNT];
    private static final double[] TRAIN_OMEGA = new double[OCTAVE_COUNT];
    private static double trainWavelength = Float.NaN;
    private static double trainDirectionX = Double.NaN;
    private static double trainDirectionZ = Double.NaN;

    /*
     * The state being blended out of, held for the length of a transition. Built once when a transition
     * commits and then read by the sample loop, so a crossfade costs a second set of sines and nothing else.
     * The names carry an OLD_ prefix rather than a TRAIN_ one so a sweep that patches the live cache's
     * arithmetic can never rewrite the previous one by accident.
     */
    private static final double[] OLD_K = new double[OCTAVE_COUNT];
    private static final double[] OLD_COS = new double[OCTAVE_COUNT];
    private static final double[] OLD_SIN = new double[OCTAVE_COUNT];
    private static final double[] OLD_OMEGA = new double[OCTAVE_COUNT];

    /** How far the crossfade has run, 1 being settled on the held state. */
    private static float transition = 1.0F;

    /** The wave time the transition was last advanced at, so it runs on the frame clock and not the tick one. */
    private static float lastSampleTime = Float.NaN;

    private OceanWaveField() {
    }

    /** Adds the octave weights up in the order the sample loop used to, so the sum is the same double. */
    private static double octaveWeightTotal() {
        double total = 0.0D;
        for (int i = 0; i < OCTAVE_COUNT; i++) {
            total += OCTAVE_WEIGHT[i];
        }
        return total;
    }

    /**
     * Recomputes the trains' wave numbers, headings and angular frequencies if the held sea state has moved.
     *
     * <p>The check is three comparisons against the values the cache was built from, which is what makes it safe
     * to call from the sample loop itself: whatever else changes the wavelength or the wind - a tick, a config
     * reload, a harness poking the fields - the next sample rebuilds the cache rather than reading a stale one.
     * The arithmetic below is the same expression, in the same order, that the sample loop carried inline, so a
     * sample taken after the move is the identical double it would have been before it.
     */
    private static void ensureGeometry() {
        if (wavelength == trainWavelength && windDirection.x == trainDirectionX && windDirection.z == trainDirectionZ) {
            return;
        }
        trainWavelength = wavelength;
        trainDirectionX = windDirection.x;
        trainDirectionZ = windDirection.z;
        double baseAngle = Math.atan2(windDirection.z, windDirection.x);
        for (int i = 0; i < OCTAVE_COUNT; i++) {
            double length = Math.max(0.05D, wavelength * OCTAVE_WAVELENGTH[i]);
            double theta = baseAngle + OCTAVE_FAN[i];
            TRAIN_K[i] = Math.PI * 2.0D / length;
            TRAIN_COS[i] = Math.cos(theta);
            TRAIN_SIN[i] = Math.sin(theta);
            TRAIN_OMEGA[i] = Math.PI * 2.0D / periodSeconds(length);
        }
    }

    /**
     * Builds the trains of the state a transition is blending out of.
     *
     * <p>It mirrors {@link #ensureGeometry()} on purpose and keeps the same arithmetic: the previous state is a
     * frozen copy of a state the live cache once built, so a sample blended toward it is the same sample the
     * sea was drawing a moment ago. Unlike the live cache there is no guard here - this is rebuilt once, at the
     * instant a transition commits, and never asked whether it is stale.
     */
    private static void buildPreviousGeometry(float previousWavelength, Vec3 previousDirection) {
        double baseAngle = Math.atan2(previousDirection.z, previousDirection.x);
        for (int i = 0; i < OCTAVE_COUNT; i++) {
            double length = Math.max(0.05D, previousWavelength * OCTAVE_WAVELENGTH[i]);
            double oldTheta = baseAngle + OCTAVE_FAN[i];
            OLD_K[i] = Math.PI * 2.0D / length;
            OLD_COS[i] = Math.cos(oldTheta);
            OLD_SIN[i] = Math.sin(oldTheta);
            OLD_OMEGA[i] = Math.PI * 2.0D / periodSeconds(length);
        }
    }

    /** A surface sample: how far above the flat water plane the surface is, and where in the wave it is. */
    public record Surface(float height, float crest) {
        public static final Surface FLAT = new Surface(0.0F, 0.5F);
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        float build = Math.max(0.5F, Config.OCEAN_WAVES_SWELL_BUILD_SECONDS.get().floatValue());
        float decay = Math.max(0.5F, Config.OCEAN_WAVES_SWELL_DECAY_SECONDS.get().floatValue());
        if (minecraft.level == null || minecraft.player == null || !Config.OCEAN_WAVES_ENABLED.get() || !enabled()) {
            // No world, or the feature is off: let an existing swell run out rather than snapping flat.
            swell = approach(swell, 0.0F, decay);
            targetWavelength = wavelength;
            updateMetrics();
            return;
        }

        ClientWindSampler.Wind reading = ClientWindSampler.sample(minecraft.level, minecraft.player.position(),
                Config.OCEAN_WAVES_WIND_RANGE_BLOCKS.get());
        double speed = reading.speedMph();
        Vec3 direction;
        if (speed <= 0.01D || reading.vectorMph().horizontalDistanceSqr() <= 1.0E-6D) {
            direction = new Vec3(PREVAILING_X, 0.0D, PREVAILING_Z);
            speed = 0.0D;
        } else {
            direction = reading.vectorMph();
        }
        // The sea is never a mirror: a floor under the wind speed gives the open ocean its light chop in
        // settled weather, and it takes the storm's own direction whenever there is one to take.
        speed = Math.max(speed, Config.OCEAN_WAVES_BASELINE_WIND_MPH.get());

        // The wind the surface is heading for is smoothed here rather than being written straight into the
        // held state: the small wander of the sampled wind has to be absorbed, not turned into a re-parameterized
        // surface every tick, which is what used to shimmer.
        if (direction.horizontalDistanceSqr() > 1.0E-6D) {
            targetDirection = targetDirection.lerp(direction.normalize(), WIND_SMOOTHING);
            if (targetDirection.horizontalDistanceSqr() <= 1.0E-6D) {
                targetDirection = new Vec3(PREVAILING_X, 0.0D, PREVAILING_Z);
            }
            targetDirection = targetDirection.normalize();
        }
        windSpeedMph = Mth.lerp(WIND_SMOOTHING, (float) windSpeedMph, (float) speed);

        double full = Math.max(1.0D, Config.OCEAN_WAVES_FULL_WIND_MPH.get());
        float target = (float) Mth.clamp(Math.pow(Mth.clamp(windSpeedMph / full, 0.0D, 1.0D), SWELL_EXPONENT), 0.0D, 1.0D);
        // Asymmetric on purpose: the sea builds slowly under a rising wind and eases off faster once it drops.
        swell = approach(swell, target, target >= swell ? build : decay);
        updateMetrics();
        commitState();
    }

    /**
     * Takes the wind's own sea state when it has drifted past the tolerance, blending into it over
     * {@link #STATE_TRANSITION_SECONDS}.
     *
     * <p>Nothing happens while a transition is still running, so a gust cannot start a second crossfade on top
     * of the first; the field simply checks again once the current one has settled. The old state is copied into
     * the previous trains <em>before</em> the held state moves, so the blend runs from the sea that was on
     * screen to the sea the wind now wants, and at no point is either of them read half-updated.
     */
    private static void commitState() {
        if (transition < 1.0F) {
            return;
        }
        double moved = Math.abs(targetWavelength - wavelength);
        boolean wavelengthMoved = moved > STATE_WAVELENGTH_TOLERANCE * Math.max(1.0D, wavelength);
        boolean directionMoved = angleBetween(windDirection, targetDirection) > STATE_DIRECTION_TOLERANCE;
        if (!wavelengthMoved && !directionMoved) {
            return;
        }
        buildPreviousGeometry(wavelength, windDirection);
        wavelength = (float) targetWavelength;
        windDirection = targetDirection;
        transition = 0.0F;
    }

    /** The angle between two directions, both taken as unit vectors. */
    private static double angleBetween(Vec3 first, Vec3 second) {
        return Math.acos(Mth.clamp(first.dot(second), -1.0D, 1.0D));
    }

    /** One tick of an exponential approach to a target, from a time constant in seconds. */
    private static float approach(float current, float target, float seconds) {
        float alpha = (float) (1.0D - Math.exp(-1.0D / (20.0D * Math.max(0.5D, seconds))));
        return current + (target - current) * alpha;
    }

    /**
     * Recomputes the two numbers the renderer reads per frame: the peak height of the surface and the
     * wavelength of its dominant train.
     *
     * <p>The steepness cap is not cosmetic. Wavelength grows with the wind, so without it a strong wind at a
     * short wavelength would draw a spiky, impossible sea - waves far past the point where real water
     * breaks. Capping the height at a seventh of the length is the same limit a real wave breaks at.
     */
    private static void updateMetrics() {
        float cell = Math.max(1.0F, Config.OCEAN_WAVES_CELL_SIZE.get().floatValue());
        float full = Math.max(1.0F, Config.OCEAN_WAVES_FULL_WIND_MPH.get().floatValue());
        float windFraction = Mth.clamp((float) windSpeedMph / full, 0.12F, 1.0F);
        // Never shorter than a couple of cells: a wave the lattice cannot resolve is a wave that flickers.
        targetWavelength = Math.max(cell * 2.2F, Config.OCEAN_WAVES_WAVELENGTH_BLOCKS.get().floatValue() * windFraction);

        double steepness = Mth.clamp(Config.OCEAN_WAVES_MAX_STEEPNESS.get(), 0.02D, BREAKING_STEEPNESS);
        float requested = Config.OCEAN_WAVES_MAX_AMPLITUDE_BLOCKS.get().floatValue() * swell;
        // peakHeight is crest to flat plane, which is half the trough-to-crest height a wave is measured by.
        float capped = (float) (targetWavelength * steepness * 0.5D);
        peakHeight = Math.max(0.0F, Math.min(requested, capped));
    }

    /**
     * Runs the crossfade forward on the frame clock.
     *
     * <p>The renderer calls {@link #surfaceAt} thousands of times a frame with the same time, so the elapsed
     * time is taken once, on the first sample of a frame, and the transition is left alone for the rest. The
     * clock is the renderer's own wave time rather than the tick counter, so a crossfade looks the same at any
     * frame rate. A time that does not move - the same frame's later samples - is not an elapsed zero, and is
     * ignored rather than being allowed to stall or rewind the blend.
     *
     * <p>The step is capped at a tenth of a second, the same guard the sun's own surface clock uses: a hitch -
     * a resource reload, a breakpoint, a window dragged back into focus - makes the game time jump by seconds,
     * and without the cap that jump would spend a third of the crossfade in one frame and put back exactly the
     * step the crossfade exists to remove. The excess is dropped rather than banked, so a hitch delays the sea
     * instead of lurching it.
     */
    private static void advanceTransition(float timeSeconds) {
        if (transition >= 1.0F) {
            lastSampleTime = timeSeconds;
            return;
        }
        if (!Float.isNaN(lastSampleTime) && timeSeconds > lastSampleTime) {
            double step = Math.min(timeSeconds - lastSampleTime, 0.1D);
            transition = (float) Math.min(1.0D, transition + step / STATE_TRANSITION_SECONDS);
        }
        lastSampleTime = timeSeconds;
    }

    /**
     * The water surface at a point, in blocks above the flat water plane.
     *
     * <p>Never negative, which is deliberate: a displaced surface that dips below the water it is drawn over
     * would sink inside the flat vanilla surface at the troughs and be swallowed by it, so the whole wave
     * rides above the plane the way the legacy renderer's did.
     *
     * <p>When a transition is running the sample is the blend of the two held states. Both states are valid
     * surfaces in their own right, so the blend is a surface at every weight, and because the weight only moves
     * on the frame clock the sea crosses from one to the other without a step.
     */
    public static Surface surfaceAt(double x, double z, float timeSeconds) {
        if (peakHeight <= 0.0005F) {
            return Surface.FLAT;
        }
        advanceTransition(timeSeconds);
        ensureGeometry();
        double amplitude = peakHeight * 0.5D;
        double total = 0.0D;
        for (int i = 0; i < OCTAVE_COUNT; i++) {
            double phase = TRAIN_K[i] * (TRAIN_COS[i] * x + TRAIN_SIN[i] * z) - TRAIN_OMEGA[i] * timeSeconds;
            total += OCTAVE_WEIGHT[i] * Math.sin(phase);
        }
        if (transition < 1.0F) {
            double previous = 0.0D;
            for (int i = 0; i < OCTAVE_COUNT; i++) {
                double oldPhase = OLD_K[i] * (OLD_COS[i] * x + OLD_SIN[i] * z) - OLD_OMEGA[i] * timeSeconds;
                previous += OCTAVE_WEIGHT[i] * Math.sin(oldPhase);
            }
            total = previous + (total - previous) * transition;
        }
        float crest = (float) (total / OCTAVE_WEIGHT_TOTAL * 0.5D + 0.5D);
        return new Surface((float) (crest * 2.0D * amplitude), crest);
    }

    /** Deep-water period of a train, in seconds. A long swell outruns a short chop, as it does in life. */
    public static double periodSeconds(double length) {
        return Math.sqrt(Math.PI * 2.0D * Math.max(0.05D, length) / GRAVITY);
    }

    /** The dominant train's period, for readouts and for the surface's own animation rate. */
    public static double periodSeconds() {
        return periodSeconds(wavelength);
    }

    public static float peakHeight() {
        return peakHeight;
    }

    public static float wavelength() {
        return wavelength;
    }

    public static float swell() {
        return swell;
    }

    public static double windSpeedMph() {
        return windSpeedMph;
    }

    public static Vec3 windDirection() {
        return windDirection;
    }

    /**
     * Whether the field is running at all.
     *
     * <p>A shader pack is stood down from the same way every other custom-program feature in the mod is: with
     * a pack active the vanilla program our surface is drawn through is replaced, and the pack's own water
     * program would be fed a vertex layout it was not written for.
     */
    public static boolean enabled() {
        return Config.VISUALS_ENABLED.get() && !RenderingCompat.isShaderPackActive();
    }
}
