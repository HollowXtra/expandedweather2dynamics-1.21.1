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
 * The Barometer: station pressure and, more importantly, which way it is going.
 *
 * <p>The absolute number is mostly a function of how high the block is standing, which is why the
 * panel breaks it into its altitude base and the weather deficit subtracted from it. The tendency is
 * the part that predicts anything: a barometer falling at a couple of hPa per hour is the oldest sign
 * there is that a low is closing in.
 *
 * <p>Tendency is reported per <em>in-game</em> hour (a thousand ticks), not per real hour. A mod storm
 * evolves in minutes of real time, so a real-hour rate would pin the needle at "falling rapidly" and
 * stay there, which tells the player nothing. The in-game clock is the one the weather actually runs
 * on, so the labels spread out across it the way they do in real life.
 */
public class BarometerBlockEntity extends BlockEntity implements PoweredInstrument, SensorSite {
    /** The clock the tendency is measured against: one in-game hour. */
    private static final double TICKS_PER_HOUR = 1000.0D;
    private static final long SAMPLE_INTERVAL_TICKS = 20L;
    /** Keeps a wild first reading or a teleporting player from producing an absurd rate. */
    private static final double MAX_RATE_HPA_PER_HOUR = 60.0D;

    private double hpa = 1013.25D;
    private double altitudeHpa = 1013.25D;
    private double lowDeficitHpa;
    private double coreDeficitHpa;
    private double previousHpa = Double.NaN;
    private long previousSampleTick = -1L;
    private double rateHpaPerHour;
    private String tendencyLabel = "STEADY";
    private boolean falling;
    private boolean rapidlyFalling;
    private String lowLabel = "";
    private double lowDistance = -1.0D;
    private String lowBearing = "";
    private int stage = -1;
    private String stormLabel = "";
    private int power;

    public BarometerBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModContent.BAROMETER_BLOCK_ENTITY.get(), pos, blockState);
    }

    public double hpa() {
        return hpa;
    }

    /**
     * The barometer's contribution to a sensor network: the column, its band and which way it is going.
     *
     * <p>The tendency is the half of a barometer reading that predicts anything, so a network line without it
     * would be a barometer that tells a player where they are rather than what is coming.
     */
    @Override
    public SensorNetwork.Kind sensorKind() {
        return SensorNetwork.Kind.PRESSURE;
    }

    @Override
    public String sensorLine() {
        return String.format(java.util.Locale.ROOT, "%.1f hPa, %s, %s", hpa,
                HandheldInstrument.pressureBand(hpa), tendencyLabel);
    }

    @Override
    public double sensorValue() {
        return hpa;
    }

    public double altitudeHpa() {
        return altitudeHpa;
    }

    public double lowDeficitHpa() {
        return lowDeficitHpa;
    }

    public double coreDeficitHpa() {
        return coreDeficitHpa;
    }

    public double rateHpaPerHour() {
        return rateHpaPerHour;
    }

    public String tendencyLabel() {
        return tendencyLabel;
    }

    public boolean falling() {
        return falling;
    }

    public boolean rapidlyFalling() {
        return rapidlyFalling;
    }

    public String lowLabel() {
        return lowLabel;
    }

    public double lowDistance() {
        return lowDistance;
    }

    public String lowBearing() {
        return lowBearing;
    }

    public int stage() {
        return stage;
    }

    public String stormLabel() {
        return stormLabel;
    }

    @Override
    public int instrumentPower() {
        return power;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, BarometerBlockEntity entity) {
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (level.getGameTime() % SAMPLE_INTERVAL_TICKS != 0L) {
            return;
        }
        if (!Config.BAROMETER_ENABLED.get()) {
            if (entity.clear()) {
                entity.sync(level, state);
            }
            return;
        }
        Vec3 center = Vec3.atCenterOf(pos);
        WeatherInstruments.PressureReading reading = WeatherInstruments.pressure(serverLevel, center);
        long now = level.getGameTime();

        double rate = 0.0D;
        if (entity.previousSampleTick > 0L && now > entity.previousSampleTick && !Double.isNaN(entity.previousHpa)) {
            double hours = (now - entity.previousSampleTick) / TICKS_PER_HOUR;
            rate = Mth.clamp((reading.hpa() - entity.previousHpa) / hours,
                    -MAX_RATE_HPA_PER_HOUR, MAX_RATE_HPA_PER_HOUR);
        }
        entity.previousHpa = reading.hpa();
        entity.previousSampleTick = now;
        WeatherInstruments.Tendency tendency = WeatherInstruments.tendency(rate);

        WeatherInstruments.StormReading storm = WeatherInstruments.nearestStorm(serverLevel, center, 4096.0D);

        boolean changed = entity.hpa != reading.hpa()
                || entity.altitudeHpa != reading.altitudeHpa()
                || entity.lowDeficitHpa != reading.lowDeficitHpa()
                || entity.coreDeficitHpa != reading.coreDeficitHpa()
                || entity.rateHpaPerHour != tendency.hPaPerHour()
                || !entity.tendencyLabel.equals(tendency.label())
                || entity.falling != tendency.falling()
                || entity.rapidlyFalling != tendency.rapidlyFalling()
                || !entity.lowLabel.equals(reading.lowLabel())
                || entity.lowDistance != reading.lowDistanceBlocks()
                || !entity.lowBearing.equals(reading.lowBearing())
                || (storm == null ? entity.stage != -1 : (entity.stage != storm.stage() || !entity.stormLabel.equals(storm.label())));

        entity.hpa = reading.hpa();
        entity.altitudeHpa = reading.altitudeHpa();
        entity.lowDeficitHpa = reading.lowDeficitHpa();
        entity.coreDeficitHpa = reading.coreDeficitHpa();
        entity.rateHpaPerHour = tendency.hPaPerHour();
        entity.tendencyLabel = tendency.label();
        entity.falling = tendency.falling();
        entity.rapidlyFalling = tendency.rapidlyFalling();
        entity.lowLabel = reading.lowLabel();
        entity.lowDistance = reading.lowDistanceBlocks();
        entity.lowBearing = reading.lowBearing();
        entity.stage = storm == null ? -1 : storm.stage();
        entity.stormLabel = storm == null ? "" : storm.label();

        int oldPower = entity.power;
        entity.refreshPower(level, state);
        if (changed || oldPower != entity.power) {
            entity.sync(level, state);
        }
    }

    /**
     * Redstone follows the weather deficit rather than the absolute reading, so a barometer on a
     * mountain does not sit at full power forever. A deep low alone gets partway up the scale; a
     * rapidly falling needle and a vortex core push it to the top.
     */
    private void refreshPower(Level level, BlockState state) {
        double deficit = lowDeficitHpa + coreDeficitHpa;
        int fromDeficit = (int) Math.round(deficit / 6.0D);
        int fromFall = (int) Math.round(Mth.clamp(-rateHpaPerHour, 0.0D, 6.0D));
        int newPower = Mth.clamp(fromDeficit + fromFall, 0, 15);
        if (newPower != power) {
            power = newPower;
            level.updateNeighborsAt(worldPosition, state.getBlock());
        }
    }

    private boolean clear() {
        boolean changed = power != 0 || !"STEADY".equals(tendencyLabel)
                || lowDeficitHpa != 0.0D || coreDeficitHpa != 0.0D || stage != -1;
        power = 0;
        tendencyLabel = "STEADY";
        falling = false;
        rapidlyFalling = false;
        rateHpaPerHour = 0.0D;
        lowDeficitHpa = 0.0D;
        coreDeficitHpa = 0.0D;
        lowLabel = "";
        lowDistance = -1.0D;
        lowBearing = "";
        stage = -1;
        stormLabel = "";
        return changed;
    }

    private void sync(Level level, BlockState state) {
        setChanged();
        level.sendBlockUpdated(worldPosition, state, state, 3);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putDouble("Hpa", hpa);
        tag.putDouble("AltitudeHpa", altitudeHpa);
        tag.putDouble("LowDeficit", lowDeficitHpa);
        tag.putDouble("CoreDeficit", coreDeficitHpa);
        tag.putDouble("PreviousHpa", previousHpa);
        tag.putLong("PreviousSampleTick", previousSampleTick);
        tag.putDouble("Rate", rateHpaPerHour);
        tag.putString("Tendency", tendencyLabel);
        tag.putBoolean("Falling", falling);
        tag.putBoolean("RapidlyFalling", rapidlyFalling);
        tag.putString("LowLabel", lowLabel);
        tag.putDouble("LowDistance", lowDistance);
        tag.putString("LowBearing", lowBearing);
        tag.putInt("Stage", stage);
        tag.putString("StormLabel", stormLabel);
        tag.putInt("Power", power);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        hpa = tag.contains("Hpa") ? tag.getDouble("Hpa") : 1013.25D;
        altitudeHpa = tag.contains("AltitudeHpa") ? tag.getDouble("AltitudeHpa") : hpa;
        lowDeficitHpa = tag.getDouble("LowDeficit");
        coreDeficitHpa = tag.getDouble("CoreDeficit");
        previousHpa = tag.contains("PreviousHpa") ? tag.getDouble("PreviousHpa") : Double.NaN;
        previousSampleTick = tag.getLong("PreviousSampleTick");
        rateHpaPerHour = tag.getDouble("Rate");
        tendencyLabel = tag.contains("Tendency") ? tag.getString("Tendency") : "STEADY";
        falling = tag.getBoolean("Falling");
        rapidlyFalling = tag.getBoolean("RapidlyFalling");
        lowLabel = tag.getString("LowLabel");
        lowDistance = tag.contains("LowDistance") ? tag.getDouble("LowDistance") : -1.0D;
        lowBearing = tag.getString("LowBearing");
        stage = tag.contains("Stage") ? tag.getInt("Stage") : -1;
        stormLabel = tag.getString("StormLabel");
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
