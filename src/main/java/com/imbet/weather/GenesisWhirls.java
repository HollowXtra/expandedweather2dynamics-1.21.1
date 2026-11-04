package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * The small vortices that appear on the ground <em>before</em> a tornado touches down.
 *
 * <p>Watch a tornado form and the condensation funnel is rarely the first thing on the ground. Spray
 * or dust starts turning at the surface while the condensation is still hanging under the wall cloud:
 * one whirl, then two, wandering and tightening as the circulation gathers, with the funnel reaching
 * down into the one that wins. Those whirls are what this class models.
 *
 * <p>They are derived, not simulated. Every whirl is a pure function of the storm's own id, its birth
 * tick and the world clock, so the server never has to know they exist and every client draws the
 * identical swarm - the same trick the tornado's other sub-vortices use. They only exist inside the
 * pre-touchdown window: as the condensation approaches the ground the swarm thins out, the survivors
 * spiral inward toward the centre, and the last of them is where the funnel lands.
 *
 * <p>Nothing here touches the wind field. A genesis whirl is a visual: the real circulation is already
 * carried by the storm's ramp, and a whirl that damaged blocks would be a second, contradictory source
 * of truth for where the tornado is.
 */
public final class GenesisWhirls {

    /** Size limits, in blocks, so no storm can produce a giant or an invisible whirl. */
    private static final double MIN_RADIUS = 1.4D;
    private static final double MAX_RADIUS = 7.0D;
    private static final double MIN_HEIGHT = 4.0D;
    private static final double MAX_HEIGHT = 22.0D;

    /** The span of a whirl's life spent fading in, and fading out, as a fraction of its life. */
    private static final float FADE_IN = 0.26F;
    private static final float FADE_OUT = 0.34F;
    /** How much a whirl's own lifetime is varied from the configured average. */
    private static final double LIFE_SPREAD_LOW = 0.75D;
    private static final double LIFE_SPREAD_HIGH = 0.50D;

    /** Whirls start once the wall cloud is lowering, and have thinned out around touchdown. */
    private static final float FIRST_WHIRL_PROGRESS = 0.05F;
    private static final float SWARM_RISE_SPAN = 0.18F;
    private static final float RETIRE_BEFORE_TOUCHDOWN = 0.12F;
    private static final float RETIRE_SPAN = 0.18F;

    /**
     * A whirl alive right now. {@code strength} is its whole visible amplitude - 0 is nothing to draw -
     * and every other value is already in blocks or radians so the renderer does no interpretation.
     */
    public record Whirl(int index, long seed, double bearingRadians, double distance,
                        double radius, double height, float strength) {
    }

    private GenesisWhirls() {
    }

    /**
     * Fills {@code out} with the whirls alive at {@code gameTime} for one storm. Empty whenever the
     * storm has no genesis timeline, the feature is off, or the funnel is on the ground and the swirls
     * have been absorbed by it.
     *
     * @param motherRadius the parent circulation's radius of maximum wind, in blocks: whirls are sized
     *                     and spaced against it so a big wedge gets a wide swarm and a rope gets a tight one.
     */
    public static void collect(long stormId, long birthTick, long gameTime, int durationTicks,
                               double motherRadius, List<Whirl> out) {
        out.clear();
        if (birthTick == Long.MIN_VALUE || gameTime == Long.MIN_VALUE || durationTicks <= 0) {
            return;
        }
        if (!Config.GENESIS_ENABLED.get() || !Config.GENESIS_PRE_TOUCHDOWN_WHIRLS.get()) {
            return;
        }
        int count = Mth.clamp(Config.GENESIS_WHIRL_COUNT.get(), 0, 12);
        if (count <= 0) {
            return;
        }
        float progress = (gameTime - birthTick) / (float) durationTicks;
        float touchdown = (float) Mth.clamp(Config.GENESIS_TOUCHDOWN_PROGRESS.get(), 0.15D, 0.95D);
        if (progress < 0.0F || progress > touchdown + RETIRE_SPAN) {
            return;
        }
        float swarm = swarmStrength(progress, touchdown);
        if (swarm <= 0.01F) {
            return;
        }

        long startTick = birthTick + (long) (FIRST_WHIRL_PROGRESS * durationTicks);
        long endTick = birthTick + (long) ((touchdown + 0.04F) * durationTicks);
        double window = Math.max(60.0D, endTick - startTick);
        double gap = window / (count + 1.0D);
        // The whirls are spread evenly over the descent, so how many are turning at once is the count
        // times the lifetime over the length of the descent. A fast, violent storm reaches the ground
        // soon, its whirls are packed closer together and it churns up more of them at a time; a slow
        // one dribbles them out. The lifetime is capped at half the descent so no whirl can outlast the
        // formation it belongs to.
        double life = Math.min(Mth.clamp(Config.GENESIS_WHIRL_DURATION_SECONDS.get(), 2.0D, 40.0D) * 20.0D, window * 0.5D);
        double maturity = 0.40D + 0.60D * smoothstep(Mth.clamp(progress / Math.max(0.05F, touchdown), 0.0F, 1.0F));
        double reach = Mth.clamp(motherRadius, 6.0D, 120.0D);

        for (int i = 0; i < count; i++) {
            long spawn = startTick + Math.round(gap * (i + 0.45D) + (rand(stormId, i, SALT_SPAWN) - 0.5D) * gap * 0.30D);
            long span = Math.max(24L, Math.round(life * (LIFE_SPREAD_LOW + LIFE_SPREAD_HIGH * rand(stormId, i, SALT_LIFE))));
            if (gameTime < spawn || gameTime >= spawn + span) {
                continue;
            }
            float age = (gameTime - spawn) / (float) span;
            float local = smoothstep(Mth.clamp(age / FADE_IN, 0.0F, 1.0F))
                    * smoothstep(Mth.clamp((1.0F - age) / FADE_OUT, 0.0F, 1.0F));
            float strength = (float) (swarm * local * (0.55D + 0.45D * rand(stormId, i, SALT_STRENGTH)) * maturity);
            if (strength <= 0.02F) {
                continue;
            }

            // Every whirl drifts inward as it turns: inflow feeding the gathering circulation. The
            // drift only closes the gap part way in normal winds, so a whirl that never reaches the
            // centre is the common case and the one that does is the one the funnel lands on.
            double orbitTurns = 0.45D + 1.35D * rand(stormId, i, SALT_ORBIT);
            double bearing = rand(stormId, i, SALT_BEARING) * Math.PI * 2.0D + orbitTurns * age * Math.PI * 2.0D;
            double spawnDistance = Mth.clamp(reach * (0.35D + 1.00D * rand(stormId, i, SALT_DISTANCE)), 1.5D, 80.0D);
            double inward = 0.30D + 0.55D * rand(stormId, i, SALT_INWARD);
            double distance = Mth.clamp(spawnDistance * (1.0D - inward * smoothstep(age)), 1.5D, 80.0D);
            double size = (0.65D + 0.90D * strength) * (0.60D + 0.70D * maturity);
            // A dust whirl is squat: a few blocks wide to a few blocks tall, not a rope. The height stays
            // modest on purpose - these are the little swirls on the ground under the wall cloud, not
            // funnels, so the condensation descending behind them is dozens of blocks taller.
            double radius = Mth.clamp((1.6D + 3.4D * rand(stormId, i, SALT_RADIUS)) * size, MIN_RADIUS, MAX_RADIUS);
            double height = Mth.clamp((7.0D + 11.0D * rand(stormId, i, SALT_HEIGHT)) * size, MIN_HEIGHT, MAX_HEIGHT);

            out.add(new Whirl(i, stormId * 31L + i, bearing, distance, radius, height, strength));
        }
    }

    /**
     * Overall amplitude of the swarm along the timeline: nothing while the storm is only organizing,
     * building through the descent, and thinning to nothing just after the condensation reaches the
     * ground - the swirls are absorbed by the funnel rather than switched off.
     */
    private static float swarmStrength(float progress, float touchdown) {
        float rise = smoothstep(Mth.clamp((progress - FIRST_WHIRL_PROGRESS) / SWARM_RISE_SPAN, 0.0F, 1.0F));
        float retireStart = touchdown - RETIRE_BEFORE_TOUCHDOWN;
        float fall = 1.0F - smoothstep(Mth.clamp((progress - retireStart) / RETIRE_SPAN, 0.0F, 1.0F));
        return Mth.clamp(rise * fall, 0.0F, 1.0F);
    }

    // Independent salts so a whirl's lifetime, bearing, size and drift are uncorrelated: hashing once
    // and reusing the bits would make every short-lived whirl also a small one born in the same place.
    private static final long SALT_SPAWN = 0x1L;
    private static final long SALT_LIFE = 0x2L;
    private static final long SALT_STRENGTH = 0x3L;
    private static final long SALT_ORBIT = 0x4L;
    private static final long SALT_BEARING = 0x5L;
    private static final long SALT_DISTANCE = 0x6L;
    private static final long SALT_INWARD = 0x7L;
    private static final long SALT_RADIUS = 0x8L;
    private static final long SALT_HEIGHT = 0x9L;

    /** Deterministic 0..1 value for one property of one whirl of one storm. */
    private static double rand(long stormId, int index, long salt) {
        long z = stormId * 0x9E3779B97F4A7C15L + index * 0xBF58476D1CE4E5B9L + salt * 0x94D049BB133111EBL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11 & 0xFFFF_FFFFL) / 4294967296.0D;
    }

    private static float smoothstep(float value) {
        float clamped = Mth.clamp(value, 0.0F, 1.0F);
        return clamped * clamped * (3.0F - 2.0F * clamped);
    }
}
