package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.util.Mth;

/**
 * A deterministic gust event train layered on top of the tornado's live maximum wind.
 *
 * <p>The estimator's smooth lifecycle waves model the storm's <em>average</em> strength. Real
 * tornado wind arrives as discrete events: a sharp attack over a second or two, a ragged decay,
 * lulls between events, and all of it riding a slow envelope that brings whole gusty and calm
 * periods. Anemometers next to real damage traces register exactly this character, and gameplay
 * systems that key on short wind peaks (block pick-up, debris, launch) read gusts off this model
 * instead of the calm sustained value.
 *
 * <p>Everything is a pure function of world time, so the server and every client compute the
 * identical train with no syncing, and it cannot reset on a reload. No per-tick state is stored,
 * so any number of samples per tick stay consistent.
 */
public final class TornadoGustModel {

    private TornadoGustModel() {
    }

    /** Marginal value noise in [-1, 1]: two hashed randoms, cosine-blended. */
    private static double noise(long seed, double t) {
        long i = (long) Math.floor(t);
        double f = t - i;
        double a = hashToSigned(seed, i);
        double b = hashToSigned(seed, i + 1);
        double k = (1.0D - Math.cos(f * Math.PI)) * 0.5D;
        return a + (b - a) * k;
    }

    private static double hashToSigned(long seed, long n) {
        long h = seed;
        h ^= n * 0x9E3779B97F4A7C15L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 27;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 41;
        // 53 bits after the shift, so p-53 maps to [0,1) and the result to [-1,1).
        return ((h >>> 11) * 0x1.0p-53) * 2.0D - 1.0D;
    }

    /**
     * The storm's gust multiplier: 1.0 is its sustained wind, higher is a gust, lower a lull.
     * Deterministic in (stormId, gameTime).
     */
    public static double multiplier(long stormId, long gameTime) {
        double depth = Config.WINDFIELD_DYNAMIC_GUSTINESS.get();
        if (depth <= 0.0D) {
            return 1.0D;
        }
        if (!Config.WINDFIELD_DYNAMIC_GUST_FRONTS.get()) {
            // Legacy behaviour: smooth multi-octave pulse folded around 1.
            double a = Math.sin(gameTime * 0.041D + stormId * 0.173D);
            double b = Math.sin(gameTime * 0.017D + stormId * 0.731D);
            double c = Math.sin(gameTime * 0.093D + stormId * 0.097D);
            return 1.0D + depth * (a * 0.46D + b * 0.34D + c * 0.20D);
        }

        // Slow envelope in [0.25, 1]: gusty periods and calm periods lasting ~1-2 minutes.
        double envelope = 0.625D + 0.375D * noise(stormId ^ 0x5DEECE66DL, gameTime / 1400.0D);

        // Event train: at any moment we are inside the event whose attack edge passed most
        // recently. Attack ~0.8-2.0s (sharp), then a ragged decay over ~6-14s, then quiet
        // until the next event. Rate/depth are hashed per storm; attack time is hashed per
        // event so intervals feel irregular (roughly one event every 8-16s).
        double rate = 0.75D + (double) ((stormId * 0x2545F4914F6CDD1DL >>> 45) % 1000L) / 2000.0D; // 0.75-1.25
        double scaled = gameTime * 0.0125D * rate; // 1 scaled unit = 4 seconds of world time
        long index = (long) Math.floor(scaled);
        double phase = scaled - index;
        double interval = 2.0D + (double) (mix(stormId, index) % 1000L) / 1000.0D * 2.0D; // 2-4 units = 8-16 s
        double attackEnd = 0.8D / (interval * 4.0D) + 0.03D; // ~0.8-2.0 s sharp attack
        double decayEnd = attackEnd + (4.0D + (double) (mix(stormId, index + 7919L) % 1000L) / 1000.0D * 5.0D) / (interval * 4.0D); // 4-9 s ragged decay

        double depthJitter = 0.55D + (double) (mix(stormId, index + 104729L) % 1000L) / 1000.0D * 0.75D; // 0.55-1.3
        double magnitude = depth * (0.9D + depthJitter);

        double gust;
        if (phase < attackEnd) {
            gust = (phase / attackEnd) * (0.85D + 0.3D * (double) (mix(stormId, index + 3L) % 1000L) / 1000.0D);
        } else if (phase < decayEnd) {
            double k = (phase - attackEnd) / Math.max(1.0e-4D, decayEnd - attackEnd);
            double peak = 0.85D + 0.3D * (double) (mix(stormId, index + 3L) % 1000L) / 1000.0D;
            // Ragged decay: exponential fall with a value-noise wobble on top.
            double wobble = 1.0D + 0.22D * noise(stormId ^ 0xA5A5A5A5L, k * 6.0D + index);
            gust = Math.min(1.1D, peak * Math.exp(-2.6D * k) * wobble);
        } else {
            // Lull, with a small residual ripple so the wind never goes synthetic-flat.
            gust = 0.06D * noise(stormId ^ 0x33CD33CDL, gameTime / 90.0D);
        }

        double v = envelope * gust;
        return 1.0D + depth * v;
    }

    private static long mix(long a, long b) {
        long h = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return h;
    }

    /** Small fast turbulence for point samples, in [-1, 1]. Deterministic in (stormId, gameTime). */
    public static double pointTurbulence(long stormId, long gameTime) {
        double t = gameTime / 26.0D;
        return 0.6D * noise(stormId ^ 0x1F123BB5L, t)
                + 0.4D * noise(stormId ^ 0x2545F491L, t * 2.317D + 13.7D);
    }

    /**
     * The sustained wind of the storm: the gust train averaged out. Used wherever gameplay needs
     * the calm value - structural failure gates, alert thresholds, spawn rarity.
     */
    public static double sustainedMph(double liveMaxWindMph, long stormId, long gameTime) {
        return liveMaxWindMph / Math.max(0.20D, multiplier(stormId, gameTime));
    }

    public static double clampedFraction(double value) {
        return Mth.clamp(value, 0.0D, 1.0D);
    }
}
