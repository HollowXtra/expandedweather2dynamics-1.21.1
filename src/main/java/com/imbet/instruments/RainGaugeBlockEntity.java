package com.imbet.instruments;

import com.imbet.Config;
import com.imbet.alert.SensorNetwork;
import com.imbet.registry.ModContent;
import com.imbet.weather.WeatherInstruments;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The Rain Gauge: how much water actually reached the ground here.
 *
 * <p>It integrates the rain rate on a real clock - twenty ticks to the second - because millimetres of
 * rainfall only mean anything against real time. Ten minutes of a forty millimetre per hour downpour
 * leaves about seven millimetres in the tube, which is exactly what a real gauge would show.
 *
 * <p>The interesting part is what it does <em>not</em> collect. The rate it integrates is already
 * gated by the storm's virga strength, so a dry storm with a visibly raining shaft evaporating under
 * its cloud base leaves the gauge empty. That makes the gauge the one instrument that can prove a
 * storm is all show.
 *
 * <p>Dry time is reported in in-game hours, matching the clock the weather runs on.
 */
public class RainGaugeBlockEntity extends BlockEntity implements PoweredInstrument, SensorSite {
    /** Millimetres per hour to millimetres per tick: 3600 seconds an hour, twenty ticks a second. */
    private static final double MM_PER_TICK_PER_RATE = 1.0D / (3600.0D * 20.0D);
    private static final double RAIN_THRESHOLD_MM_PER_HOUR = 0.05D;
    private static final double MAX_ACCUMULATED_MM = 9999.0D;
    private static final long SYNC_INTERVAL_TICKS = 20L;
    /** A thousand ticks to the in-game hour, which is what the dry-for readout counts in. */
    private static final double TICKS_PER_HOUR = 1000.0D;

    private double accumulatedMm;
    private double rateMmPerHour;
    private long lastRainTick = -1L;
    private boolean everRained;
    private int stage = -1;
    private String stormLabel = "";
    private double stormDistance = -1.0D;
    private int power;

    public RainGaugeBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModContent.RAIN_GAUGE_BLOCK_ENTITY.get(), pos, blockState);
    }

    public double accumulatedMm() {
        return accumulatedMm;
    }

    public double rateMmPerHour() {
        return rateMmPerHour;
    }

    /**
     * The rain gauge's contribution to a sensor network: what has landed here, and how fast it is landing now.
     *
     * <p>Both numbers, because they are two different readings of the same shaft: the total is what has already
     * passed over, and the rate is what is overhead. Only rain that reached the ground counts, which is why a
     * gauge reading is worth having on a network at all - the radar sees the shaft, and the gauge sees the
     * arrival.
     */
    @Override
    public SensorNetwork.Kind sensorKind() {
        return SensorNetwork.Kind.RAIN;
    }

    @Override
    public String sensorLine() {
        return raining()
                ? String.format(java.util.Locale.ROOT, "%.1f mm collected, %.1f mm/h now", accumulatedMm, rateMmPerHour)
                : String.format(java.util.Locale.ROOT, "%.1f mm collected, nothing falling", accumulatedMm);
    }

    @Override
    public double sensorValue() {
        return raining() ? rateMmPerHour : 0.0D;
    }

    public boolean raining() {
        return rateMmPerHour > RAIN_THRESHOLD_MM_PER_HOUR;
    }

    /** Hours since rain last fell here, or -1 when it never has. */
    public double dryHours(Level level) {
        if (!everRained || lastRainTick < 0L) {
            return -1.0D;
        }
        return Math.max(0.0D, (level.getGameTime() - lastRainTick) / TICKS_PER_HOUR);
    }

    public int stage() {
        return stage;
    }

    public String stormLabel() {
        return stormLabel;
    }

    public double stormDistance() {
        return stormDistance;
    }

    public void reset() {
        accumulatedMm = 0.0D;
        lastRainTick = -1L;
        everRained = false;
        setChanged();
    }

    @Override
    public int instrumentPower() {
        return power;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, RainGaugeBlockEntity entity) {
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!Config.RAIN_GAUGE_ENABLED.get()) {
            if (entity.clear()) {
                entity.sync(level, state);
            }
            return;
        }
        Vec3 center = Vec3.atCenterOf(pos);
        double rate = WeatherInstruments.rainfallRateMmPerHour(serverLevel, center);
        boolean landing = rate > RAIN_THRESHOLD_MM_PER_HOUR;
        if (landing) {
            entity.accumulatedMm = Math.min(MAX_ACCUMULATED_MM, entity.accumulatedMm + rate * MM_PER_TICK_PER_RATE);
            entity.lastRainTick = level.getGameTime();
            entity.everRained = true;
        }
        // The panel rate is smoothed so a sputtering shower does not flicker, but the accumulation
        // above is not: the tube holds every drop that actually landed.
        entity.rateMmPerHour += (rate - entity.rateMmPerHour) * 0.05D;

        int oldPower = entity.power;
        int newPower = Config.RAIN_GAUGE_REDSTONE_PULSE.get() && landing ? 15 : 0;
        if (newPower != oldPower) {
            entity.power = newPower;
            level.updateNeighborsAt(pos, state.getBlock());
        }
        if (level.getGameTime() % SYNC_INTERVAL_TICKS != 0L) {
            return;
        }
        WeatherInstruments.StormReading storm = WeatherInstruments.nearestStorm(serverLevel, center, 4096.0D);
        int newStage = storm == null ? -1 : storm.stage();
        String newLabel = storm == null ? "" : storm.label();
        double newDistance = storm == null ? -1.0D : storm.distanceBlocks();
        if (newPower != oldPower || newStage != entity.stage || !newLabel.equals(entity.stormLabel)
                || newDistance != entity.stormDistance || landing) {
            entity.stage = newStage;
            entity.stormLabel = newLabel;
            entity.stormDistance = newDistance;
            entity.sync(level, state);
        }
    }

    private boolean clear() {
        boolean changed = power != 0 || rateMmPerHour != 0.0D || stage != -1;
        power = 0;
        rateMmPerHour = 0.0D;
        stage = -1;
        stormLabel = "";
        stormDistance = -1.0D;
        return changed;
    }

    private void sync(Level level, BlockState state) {
        setChanged();
        level.sendBlockUpdated(worldPosition, state, state, 3);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putDouble("AccumulatedMm", accumulatedMm);
        tag.putDouble("RateMmPerHour", rateMmPerHour);
        tag.putLong("LastRainTick", lastRainTick);
        tag.putBoolean("EverRained", everRained);
        tag.putInt("Stage", stage);
        tag.putString("StormLabel", stormLabel);
        tag.putDouble("StormDistance", stormDistance);
        tag.putInt("Power", power);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        accumulatedMm = Mth.clamp(tag.getDouble("AccumulatedMm"), 0.0D, MAX_ACCUMULATED_MM);
        rateMmPerHour = Math.max(0.0D, tag.getDouble("RateMmPerHour"));
        lastRainTick = tag.getLong("LastRainTick");
        everRained = tag.getBoolean("EverRained");
        stage = tag.contains("Stage") ? tag.getInt("Stage") : -1;
        stormLabel = tag.getString("StormLabel");
        stormDistance = tag.contains("StormDistance") ? tag.getDouble("StormDistance") : -1.0D;
        power = tag.getInt("Power");
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        return tag;
    }
}
