package com.imbet.instruments;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One tornado probe's recorded deployment.
 *
 * <p>This is the single description of a probe's data, and everything that reports it goes through
 * here: the block reads it to answer a right-click, and the recovered item reads it back out of its
 * own {@code block_entity_data} component to draw its tooltip. Sharing one record is what stops the
 * numbers in your inventory from drifting away from the numbers on the block.
 *
 * <p>The state constants live here too rather than on the block entity, so the item can name a state
 * without needing a block entity to exist.
 */
public record TornadoProbeReading(
        int state,
        double peakWindMph,
        double minPressureHpa,
        double ambientHpa,
        double peakDebrisPerSecond,
        double closestApproachBlocks,
        double secondsInWind,
        String stormLabel
) {
    public static final int STATE_ARMED = 0;
    public static final int STATE_RECORDING = 1;
    public static final int STATE_RECORDED = 2;
    public static final int STATE_LOST = 3;

    public static String stateLabel(int state) {
        return switch (state) {
            case STATE_RECORDING -> "RECORDING";
            case STATE_RECORDED -> "READY TO READ";
            case STATE_LOST -> "OVERRUN";
            default -> "ARMED - WAITING";
        };
    }

    /** A probe that has nothing recorded: armed, with no pressure and no wind. */
    public static final TornadoProbeReading EMPTY = new TornadoProbeReading(
            STATE_ARMED, 0.0D, Double.NaN, Double.NaN, 0.0D, -1.0D, 0.0D, "");

    /** True once the deployment is finished, whether it survived or was overrun. */
    public static boolean settled(int state) {
        return state == STATE_RECORDED || state == STATE_LOST;
    }

    /** True when this reading holds numbers worth reporting. */
    public boolean hasReading() {
        return !Double.isNaN(minPressureHpa) || peakWindMph > 0.0D || secondsInWind > 0.0D;
    }

    public boolean overrun() {
        return state == STATE_LOST;
    }

    /** How far the pressure fell below the ambient value sampled before the storm arrived. */
    public double pressureDropHpa() {
        if (Double.isNaN(ambientHpa) || Double.isNaN(minPressureHpa)) {
            return 0.0D;
        }
        return Math.max(0.0D, ambientHpa - minPressureHpa);
    }

    /**
     * The summary a chaser would write down, without any indent, so a chat readout and an item
     * tooltip can present the same lines in their own style.
     */
    public List<String> summaryLines() {
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "Peak wind %.0f mph", peakWindMph));
        if (!Double.isNaN(minPressureHpa)) {
            lines.add(String.format(Locale.ROOT, "Minimum pressure %.1f hPa, a drop of %.1f hPa",
                    minPressureHpa, pressureDropHpa()));
        }
        lines.add(String.format(Locale.ROOT, "Peak debris %.0f impacts per second", peakDebrisPerSecond));
        if (closestApproachBlocks >= 0.0D) {
            lines.add(String.format(Locale.ROOT, "Closest approach %.0f blocks", closestApproachBlocks));
        }
        lines.add(String.format(Locale.ROOT, "%.1f seconds inside the wind field", secondsInWind));
        if (stormLabel != null && !stormLabel.isBlank()) {
            lines.add("Storm: " + stormLabel);
        }
        return lines;
    }

    /**
     * Writes the reading into a tag. This and {@link #of} are the only pair that touches these keys, so
     * the numbers a probe saves, the numbers it sends to the client and the numbers a recovered item
     * carries are all written and read by the same code.
     */
    public void writeTo(CompoundTag tag) {
        tag.putInt("State", state);
        tag.putDouble("AmbientHpa", ambientHpa);
        tag.putDouble("MinPressureHpa", minPressureHpa);
        tag.putDouble("PeakWindMph", peakWindMph);
        tag.putDouble("PeakDebris", peakDebrisPerSecond);
        tag.putDouble("ClosestApproach", closestApproachBlocks);
        tag.putDouble("SecondsInWind", secondsInWind);
        tag.putString("StormLabel", stormLabel == null ? "" : stormLabel);
    }

    /**
     * Parses a reading out of a block entity's saved tag. An empty tag - a probe that has never been out
     * in a storm - reads as {@link #EMPTY} rather than as an error, so every caller can stay branch-free
     * and simply ask {@link #hasReading()}.
     */
    public static TornadoProbeReading of(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return EMPTY;
        }
        return new TornadoProbeReading(
                tag.getInt("State"),
                tag.getDouble("PeakWindMph"),
                tag.contains("MinPressureHpa") ? tag.getDouble("MinPressureHpa") : Double.NaN,
                tag.contains("AmbientHpa") ? tag.getDouble("AmbientHpa") : Double.NaN,
                tag.getDouble("PeakDebris"),
                tag.contains("ClosestApproach") ? tag.getDouble("ClosestApproach") : -1.0D,
                tag.getDouble("SecondsInWind"),
                tag.getString("StormLabel")
        );
    }

    /** Reads the reading a recovered probe is carrying, or {@link #EMPTY} when it carries none. */
    public static TornadoProbeReading fromItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return EMPTY;
        }
        CustomData data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data == null || data.isEmpty()) {
            return EMPTY;
        }
        return of(data.copyTag());
    }

    /** The reading to show for an item, but only when there is something to show. */
    public static TornadoProbeReading recordedFromItem(ItemStack stack) {
        TornadoProbeReading reading = fromItem(stack);
        return reading != null && reading.hasReading() ? reading : null;
    }
}
