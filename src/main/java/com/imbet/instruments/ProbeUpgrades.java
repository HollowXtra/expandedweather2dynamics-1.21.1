package com.imbet.instruments;

import com.imbet.Config;
import net.minecraft.nbt.CompoundTag;

/**
 * The two upgrades a tornado probe can be fitted with, and what fitting them actually does.
 *
 * <p>Both effects are the physics rather than a made-up bonus, which is what makes them worth crafting:
 *
 * <ul>
 *   <li><b>Reinforced housing.</b> A probe is lost when the wind beats its anchoring and it goes end over
 *       end, and the load that does that is dynamic pressure, which rises with the <em>square</em> of wind
 *       speed. Armouring and anchoring the probe therefore buys survival that is worth less than it sounds
 *       in speed terms: multiplying the speed it can take by 1.75 means it takes about three times the
 *       load. That asymmetry is why the multiplier is applied to speed and not to pressure, and why a
 *       reinforced probe still has an upper limit.</li>
 *   <li><b>Sensor module.</b> An anemometer's peak reading depends on how often it samples. A logger
 *       averaging once a second simply never sees a three-second gust, so it under-reads the event it was
 *       deployed to measure; a fast-sampling head catches it. Each module shortens the sampling interval and
 *       raises the fraction of the true peak that is reported, and a finer instrument also reaches further
 *       into the storm. This is the honest reason a research-grade probe is better than a cheap one, and it
 *       is measurable: the same tornado yields a different peak from each tier.</li>
 * </ul>
 *
 * <p>The upgrades are carried by the block entity and by the recovered item alike, through the same NBT
 * keys, so a probe that survives comes back with the kit it was fitted with still on it.
 */
public record ProbeUpgrades(int housing, int sensorTier) {
    /** One housing, or none: a probe is either armoured or it is not. */
    public static final int MAX_HOUSING = 1;
    /** Three sensor grades, each worth a shorter interval and a longer reach. */
    public static final int MAX_SENSOR_TIER = 3;

    public static final ProbeUpgrades NONE = new ProbeUpgrades(0, 0);

    /** True when nothing has been fitted, which is what an ordinary crafted probe is. */
    public boolean bare() {
        return housing <= 0 && sensorTier <= 0;
    }

    /**
     * How much wind the probe can take before it is overrun, as a multiple of its stock limit.
     *
     * <p>Linear in speed, and the comment above is why: the load it resists goes with the square.
     */
    public double survivalMultiplier() {
        return 1.0D + housingMultiplier() * housing;
    }

    private static double housingMultiplier() {
        return Config.TORNADO_PROBE_HOUSING_SURVIVAL_BONUS.get();
    }

    /** How much further a better instrument reaches into a storm. */
    public double detectRangeMultiplier() {
        return 1.0D + Config.TORNADO_PROBE_SENSOR_RANGE_BONUS.get() * sensorTier;
    }

    /**
     * The fraction of the true peak wind a head at this tier reports.
     *
     * <p>A sampler sees the mean over its interval, and turbulence means the mean over a long interval is
     * below the peak: the gust that matters is the one the instrument was slow to notice. Tier zero is the
     * stock probe, which samples once a second and reads a fifth under the true peak; a tier three head
     * samples every tick and reads it in full.
     */
    public double gustFactor() {
        return switch (sensorTier) {
            case 1 -> 0.90D;
            case 2 -> 0.95D;
            case 3 -> 1.0D;
            default -> 0.80D;
        };
    }

    /** Ticks between samples for a head at this tier. */
    public int sampleIntervalTicks() {
        return switch (sensorTier) {
            case 1 -> 10;
            case 2 -> 4;
            case 3 -> 1;
            default -> 20;
        };
    }

    /** Fits a housing, if one is not already on. */
    public ProbeUpgrades withHousing() {
        return new ProbeUpgrades(Math.min(MAX_HOUSING, housing + 1), sensorTier);
    }

    /** Fits a sensor module, one grade at a time. */
    public ProbeUpgrades withSensorModule() {
        return new ProbeUpgrades(housing, Math.min(MAX_SENSOR_TIER, sensorTier + 1));
    }

    public boolean housingFull() {
        return housing >= MAX_HOUSING;
    }

    public boolean sensorFull() {
        return sensorTier >= MAX_SENSOR_TIER;
    }

    /** The spec line for a tooltip: what is fitted, or that nothing is. */
    public String label() {
        if (bare()) {
            return "Stock fit";
        }
        StringBuilder builder = new StringBuilder();
        if (housing > 0) {
            builder.append("reinforced housing");
        }
        if (sensorTier > 0) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append("sensor grade ").append(sensorTier);
        }
        return builder.toString();
    }

    /** A one-line summary of what the fitted kit does, for the probe's own tooltip. */
    public String effectLabel() {
        if (bare()) {
            return "";
        }
        return String.format(java.util.Locale.ROOT,
                "Survives x%.2f wind, samples every %d ticks, reads %.0f%% of the peak, reaches x%.2f",
                survivalMultiplier(), sampleIntervalTicks(), gustFactor() * 100.0D, detectRangeMultiplier());
    }

    public void writeTo(CompoundTag tag) {
        tag.putInt("UpgradeHousing", housing);
        tag.putInt("UpgradeSensor", sensorTier);
    }

    public static ProbeUpgrades of(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return NONE;
        }
        return new ProbeUpgrades(
                Math.max(0, Math.min(MAX_HOUSING, tag.getInt("UpgradeHousing"))),
                Math.max(0, Math.min(MAX_SENSOR_TIER, tag.getInt("UpgradeSensor"))));
    }
}
