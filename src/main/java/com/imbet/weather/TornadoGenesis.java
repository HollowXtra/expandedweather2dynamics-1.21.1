package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.util.Mth;
import weather2.weathersystem.storm.StormObject;

/**
 * Real-time tornado formation.
 *
 * <p>Weather2 hands the storm a full-strength tornado the tick its stage reaches forming, and this
 * addon's wind field used to snap straight to the stage wind on that same tick: full suction, full
 * damage swath, full damage intensity, full radar signature and the alert, all at once. Only the
 * funnel's <em>pixels</em> eased in, over a hard-coded eight seconds. That is why a tornado read as
 * appearing instantly.
 *
 * <p>Real genesis is a process, and this class is the clock for it. Every storm with a funnel gets a
 * genesis timeline, rolled once at birth and carried in its state so the server and every client run
 * the same one. Along that timeline the storm passes through the four stages a real tornado does:
 *
 * <ol>
 *   <li><b>Organizing</b> - the mesocyclone tightens and the wall cloud lowers. There is no
 *       condensation funnel at all yet, only inflow and a gusty surface circulation, and the wind
 *       field sits at a fraction of the storm's strength.</li>
 *   <li><b>Descent</b> - condensation extends downward out of the cloud base while a dust whirl turns
 *       at the surface underneath it. The whirl is doing real but light work: scuffing the ground,
 *       pulling at loose things. The funnel is still hanging in the air.</li>
 *   <li><b>Touchdown</b> - the condensation docks with the surface whirl and the tornado is on the
 *       ground, still narrow and still building.</li>
 *   <li><b>Mature</b> - the funnel widens to its stage width and the wind climbs to the storm's full
 *       stage strength, after which it simply runs until it decays.</li>
 * </ol>
 *
 * <p>Because the wind ramp is applied to the storm's live maximum wind, everything downstream of it
 * follows the timeline for free: the wind field samples, entity motion, ground scouring, block
 * pick-up, structure demolition, the damage swath width, the radar signature, the anemometer, and the
 * siren alerts all build with the funnel instead of arriving with it.
 */
public final class TornadoGenesis {

    public enum Phase {
        /** Mesocyclone tightening and the wall cloud lowering. No condensation yet. */
        ORGANIZING,
        /** Condensation descending, with a surface dust whirl turning below it. */
        DESCENT,
        /** The condensation reached the ground. On the ground, still narrow and still building. */
        TOUCHDOWN,
        /** Widening and strengthening to the storm's full stage width and wind. */
        MATURE,
        /** Timeline finished: the tornado runs at full strength until it decays. */
        COMPLETE
    }

    /** Where inside the descent the dust whirl has fully formed, as a fraction of the whole timeline. */
    private static final float ORGANIZING_FRACTION_OF_TOUCHDOWN = 0.48F;
    /** How long the touchdown stage lasts after the condensation arrives, as a fraction of the timeline. */
    private static final float TOUCHDOWN_STAGE_LENGTH = 0.13F;
    /** Fallback build-in length for a funnel whose genesis length is not known. */
    private static final float LEGACY_BUILD_TICKS = 160.0F;

    private TornadoGenesis() {
    }

    /**
     * Timeline position: 0 at birth, 1 when the tornado is fully formed, beyond 1 for the rest of its
     * life. A storm with no funnel returns 1 so every ramp is a no-op for it.
     */
    public static float progress(long birthTick, long gameTime, int durationTicks) {
        if (birthTick == Long.MIN_VALUE || gameTime == Long.MIN_VALUE || durationTicks <= 0) {
            return 1.0F;
        }
        return Math.max(0.0F, (gameTime - birthTick) / (float) durationTicks);
    }

    public static Phase phase(float progress) {
        if (!Config.GENESIS_ENABLED.get()) {
            return Phase.COMPLETE;
        }
        float touchdown = touchdownProgress();
        if (progress < touchdown * ORGANIZING_FRACTION_OF_TOUCHDOWN) {
            return Phase.ORGANIZING;
        }
        if (progress < touchdown) {
            return Phase.DESCENT;
        }
        if (progress < touchdown + TOUCHDOWN_STAGE_LENGTH) {
            return Phase.TOUCHDOWN;
        }
        return progress < 1.0F ? Phase.MATURE : Phase.COMPLETE;
    }

    /**
     * Fraction of the storm's stage wind it may actually produce right now. This is the gate that
     * makes the tornado form in real time rather than appearing: a storm that has just started
     * organizing produces a gust, not an EF3.
     */
    public static float windRamp(float progress) {
        if (!Config.GENESIS_ENABLED.get()) {
            return 1.0F;
        }
        float touchdown = touchdownProgress();
        float organizingEnd = touchdown * ORGANIZING_FRACTION_OF_TOUCHDOWN;
        float matureStart = touchdown + TOUCHDOWN_STAGE_LENGTH;
        float start = (float) Mth.clamp(Config.GENESIS_START_WIND_FRACTION.get(), 0.0F, 0.9F);
        if (progress <= 0.0F) {
            return start;
        }
        if (progress >= 1.0F) {
            return 1.0F;
        }
        // Exponential, then saturating. A tornado spins up the way any instability grows: slowly while it is organising,
        // then faster and faster as it tightens - stretching makes it spin faster, and spinning faster stretches it more -
        // until it runs out of what is feeding it and levels off at the storm's own strength. That is the logistic curve,
        // whose early part is a plain exponential. The midpoint sits a little after touchdown, so the wind is still low at
        // the moment the funnel reaches the ground (an EF5's stage wind is 321 mph, and this is about a third of it there)
        // and the rush comes as the vortex matures. The old ramp was four smoothsteps end to end, each flat at both ends,
        // so a forming tornado grew, paused, grew and paused again.
        float midpoint = Math.min(0.92F, touchdown + 0.12F);
        return Mth.lerp(logisticGrowth(progress, midpoint, WIND_GROWTH_RATE), start, 1.0F);
    }

    /** How sharply the wind's growth turns from slow to fast, per whole timeline. */
    private static final float WIND_GROWTH_RATE = 9.0F;
    /** The same for the width, which grows more gently and a little later than the wind. */
    private static final float WIDTH_GROWTH_RATE = 7.0F;

    /**
     * A logistic curve over the timeline, rescaled so that it is exactly 0 at the start and 1 at the end: exponential
     * growth at first, turning over at {@code midpoint} and levelling off toward the end.
     */
    static float logisticGrowth(float progress, float midpoint, float rate) {
        double low = 1.0D / (1.0D + Math.exp(rate * midpoint));
        double high = 1.0D / (1.0D + Math.exp(-rate * (1.0D - midpoint)));
        double value = 1.0D / (1.0D + Math.exp(-rate * (Mth.clamp(progress, 0.0F, 1.0F) - midpoint)));
        return (float) Mth.clamp((value - low) / (high - low), 0.0D, 1.0D);
    }

    /**
     * Visible funnel width as a fraction of its mature width. A forming funnel is a thin rope that
     * thickens as it matures, and because the damage swath is derived from this, the early damage path
     * is a narrow strip that widens with the funnel.
     */
    public static float widthRamp(float progress) {
        if (!Config.GENESIS_ENABLED.get()) {
            return 1.0F;
        }
        float touchdown = touchdownProgress();
        float organizingEnd = touchdown * ORGANIZING_FRACTION_OF_TOUCHDOWN;
        float matureStart = touchdown + TOUCHDOWN_STAGE_LENGTH;
        if (progress <= 0.0F) {
            return 0.22F;
        }
        if (progress >= 1.0F) {
            return 1.0F;
        }
        // The same exponential-then-saturating growth as the wind, a little later and gentler: a funnel thickens as the
        // vortex it is the visible part of strengthens, and goes on widening after the wind has done most of its growing.
        float midpoint = Math.min(0.94F, touchdown + 0.18F);
        return Mth.lerp(logisticGrowth(progress, midpoint, WIDTH_GROWTH_RATE), 0.22F, 1.0F);
    }

    /**
     * How far down the condensation has reached: 0 = still inside the cloud base, 1 = on the ground.
     * The funnel renderer uses this to draw condensation extending downward out of the wall cloud while
     * clear air is still below it, which is the single most recognisable moment of a tornado forming.
     */
    public static float condensationReach(float progress) {
        if (!Config.GENESIS_ENABLED.get()) {
            return 1.0F;
        }
        float touchdown = touchdownProgress();
        float organizingEnd = touchdown * ORGANIZING_FRACTION_OF_TOUCHDOWN;
        float matureStart = touchdown + TOUCHDOWN_STAGE_LENGTH;
        if (progress <= 0.0F) {
            return 0.0F;
        }
        if (progress < organizingEnd) {
            return Mth.lerp(smoothstep(progress / organizingEnd), 0.0F, 0.06F);
        }
        if (progress < touchdown) {
            return Mth.lerp(smoothstep(rampWithin(progress, organizingEnd, touchdown)), 0.06F, 0.86F);
        }
        if (progress < matureStart) {
            return Mth.lerp(smoothstep(rampWithin(progress, touchdown, matureStart)), 0.86F, 1.0F);
        }
        return 1.0F;
    }

    /**
     * Surface dust whirl strength. It exists from early in the organizing stage - before there is any
     * condensation to see and while the surface wind is still far too weak to raise dust on its own -
     * and stays at full strength once the funnel is on the ground, where the ordinary wind-driven dust
     * cloud takes over.
     */
    public static float surfaceWhirl(float progress) {
        if (!Config.GENESIS_ENABLED.get() || !Config.GENESIS_PRE_TOUCHDOWN_DUST_WHIRL.get()) {
            return 0.0F;
        }
        float touchdown = touchdownProgress();
        float organizingEnd = touchdown * ORGANIZING_FRACTION_OF_TOUCHDOWN;
        if (progress <= 0.0F) {
            return 0.10F;
        }
        if (progress < organizingEnd) {
            return Mth.lerp(smoothstep(progress / organizingEnd), 0.10F, 0.55F);
        }
        if (progress < touchdown) {
            return Mth.lerp(smoothstep(rampWithin(progress, organizingEnd, touchdown)), 0.55F, 1.0F);
        }
        return 1.0F;
    }

    /** True once the timeline's touchdown phase has begun. */
    public static boolean onTheGround(float progress) {
        return progress >= touchdownProgress();
    }

    /**
     * The condensation reach at which the <em>drawn</em> funnel first touches the ground. The renderer
     * holds the bottom layer clear of the surface until the descending sheath has passed 78% of the way
     * down (see {@code DynamicStormController.condensationDescent}), so this - not the timeline's
     * touchdown phase - is the frame on which the player actually sees a grounded tornado. It lands
     * slightly earlier than {@link #onTheGround}, and wind, labels and pixels must all switch together:
     * a funnel that is visibly touching the ground cannot be the one reading like a gust, and a wind
     * floor that only engaged a few percent later left a visible window where it was.
     */
    public static final float VISUAL_GROUND_REACH = 0.78F;

    /** True when the funnel the player is looking at is on the ground. */
    public static boolean visuallyOnTheGround(float progress) {
        return condensationReach(progress) >= VISUAL_GROUND_REACH;
    }

    /** True while the funnel is on the ground but has not yet reached full stage strength. */
    public static boolean stillIntensifying(float progress) {
        return progress < 1.0F;
    }

    /**
     * The same reach as {@link #condensationReach}, but for a funnel that has no genesis timeline to
     * read: the timeline is switched off, the length is unknown (a save written before it existed), or
     * the storm's state has not synced yet. Those keep the legacy eight-second build-in, so disabling
     * this feature leaves the old forming animation exactly as it was instead of popping the funnel in.
     */
    public static float condensationReachOrLegacy(long birthTick, long gameTime, int durationTicks, float ageTicks) {
        if (!Config.GENESIS_ENABLED.get() || birthTick == Long.MIN_VALUE || durationTicks <= 0 || gameTime == Long.MIN_VALUE) {
            return Mth.clamp(ageTicks / LEGACY_BUILD_TICKS, 0.0F, 1.0F);
        }
        return condensationReach(progress(birthTick, gameTime, durationTicks));
    }

    /**
     * Rolls this storm's genesis length in ticks. Rolled once, at birth, and carried in the storm's
     * state so the server and every client run the identical timeline. Violent tornadoes are biased
     * shorter - real violent tornadoes tend to tighten up and reach the ground faster than the weak
     * ones that churn for many minutes and never put a visible funnel down - and a deterministic
     * per-storm jitter keeps two storms from forming in lockstep.
     */
    public static int rollDuration(long stormId, int startStage, long birthTick) {
        double seconds = Math.max(5.0D, Config.GENESIS_DURATION_SECONDS.get());
        double stageScale = 1.0D;
        if (Config.GENESIS_INTENSITY_SCALE.get()) {
            stageScale = Mth.clamp(1.18D - 0.10D * (startStage - StormObject.STATE_FORMING), 0.62D, 1.18D);
        }
        double jitterRange = Mth.clamp(Config.GENESIS_JITTER.get(), 0.0D, 0.9D);
        double roll = ((stormId * 0x9E3779B97F4A7C15L + birthTick) >>> 11 & 0xFFFFL) / 65535.0D;
        double jitter = 1.0D + (roll - 0.5D) * 2.0D * jitterRange;
        return Math.max(40, (int) Math.round(seconds * 20.0D * stageScale * jitter));
    }

    private static float touchdownProgress() {
        return (float) Mth.clamp(Config.GENESIS_TOUCHDOWN_PROGRESS.get(), 0.15D, 0.95D);
    }

    private static float rampWithin(float progress, float from, float to) {
        float span = Math.max(0.0001F, to - from);
        return Mth.clamp((progress - from) / span, 0.0F, 1.0F);
    }

    private static float smoothstep(float value) {
        float clamped = Mth.clamp(value, 0.0F, 1.0F);
        return clamped * clamped * (3.0F - 2.0F * clamped);
    }
}
