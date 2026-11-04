package com.imbet.instruments;

import com.imbet.Config;
import com.imbet.registry.ModContent;
import com.imbet.weather.TornadoThreatService;
import com.imbet.weather.WeatherInstruments;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The deployable Tornado Probe.
 *
 * <p>You plant it in the ground ahead of a storm and come back for it. While a vortex is near it
 * records the numbers a real instrument pack records: peak wind, the lowest pressure it saw, how much
 * debris passed it, how close the core came and how long it was inside the wind field.
 *
 * <p>The minimum pressure is the headline. {@link WeatherInstruments} subtracts a vortex's depression
 * from the ambient pressure at the probe's own altitude, so the reading is a real drop rather than an
 * absolute number that depends on how high the probe happens to be standing - which is why the ambient
 * value is sampled before the storm arrives and reported alongside.
 *
 * <p>It has states rather than a single on/off, because that is what makes a deployment mean anything:
 * it waits while armed, records while the wind is on it, settles once the wind drops, and can be
 * overrun outright. An overrun probe keeps its block rather than vanishing, so the reading it died
 * with is still there to walk back to.
 */
public class TornadoProbeBlockEntity extends BlockEntity implements PoweredInstrument {
    public static final int STATE_ARMED = TornadoProbeReading.STATE_ARMED;
    public static final int STATE_RECORDING = TornadoProbeReading.STATE_RECORDING;
    public static final int STATE_RECORDED = TornadoProbeReading.STATE_RECORDED;
    public static final int STATE_LOST = TornadoProbeReading.STATE_LOST;

    /** Ticks of calm after recording before the probe stops and waits to be collected. */
    private static final long CALM_TICKS_TO_SETTLE = 100L;
    private static final long SYNC_INTERVAL_TICKS = 20L;

    private int state = STATE_ARMED;
    /** The kit fitted to this probe, which survives both the storm and the recovery. */
    private ProbeUpgrades upgrades = ProbeUpgrades.NONE;
    private double ambientHpa = Double.NaN;
    private double minPressureHpa = Double.NaN;
    private double peakWindMph;
    private double peakDebrisPerSecond;
    private double closestApproachBlocks = -1.0D;
    private double secondsInWind;
    private String stormLabel = "";
    private long calmTicks;
    private boolean readOnce;
    private int power;

    public TornadoProbeBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModContent.TORNADO_PROBE_BLOCK_ENTITY.get(), pos, blockState);
    }

    public int state() {
        return state;
    }

    public String stateLabel() {
        return TornadoProbeReading.stateLabel(state);
    }

    /** The current data, described the one way the item and the block both understand. */
    public TornadoProbeReading reading() {
        return new TornadoProbeReading(state, peakWindMph, minPressureHpa, ambientHpa,
                peakDebrisPerSecond, closestApproachBlocks, secondsInWind, stormLabel);
    }

    public double ambientHpa() {
        return ambientHpa;
    }

    public double minPressureHpa() {
        return minPressureHpa;
    }

    public double peakWindMph() {
        return peakWindMph;
    }

    public double peakDebrisPerSecond() {
        return peakDebrisPerSecond;
    }

    public double closestApproachBlocks() {
        return closestApproachBlocks;
    }

    public double secondsInWind() {
        return secondsInWind;
    }

    public String stormLabel() {
        return stormLabel;
    }

    /** How far the pressure fell below the ambient value the probe sampled before the storm. */
    public double pressureDropHpa() {
        return reading().pressureDropHpa();
    }

    /** True when the probe has finished a deployment and has something worth reading out. */
    public boolean hasReading() {
        return TornadoProbeReading.settled(state) && reading().hasReading();
    }

    /** The spec line a player sees when they read the probe: what is fitted, and what it means. */
    public String upgradeLine() {
        if (upgrades.bare()) {
            return "Stock fit - no upgrades";
        }
        return "Fitted: " + upgrades.label() + " - " + upgrades.effectLabel();
    }

    /**
     * The chaser summary, from the same record the recovered item prints, so a probe read in the world
     * and the same probe read in your inventory cannot report different numbers.
     *
     * <p>The fitted kit is the first line, because it is what decides how the numbers below should be read:
     * the same tornado gives a different peak wind to a stock head than to a grade three one.
     */
    public List<String> summaryLines() {
        List<String> lines = new ArrayList<>();
        lines.add(upgradeLine());
        lines.addAll(reading().summaryLines());
        return lines;
    }

    public void reset() {
        state = STATE_ARMED;
        minPressureHpa = Double.NaN;
        peakWindMph = 0.0D;
        peakDebrisPerSecond = 0.0D;
        closestApproachBlocks = -1.0D;
        secondsInWind = 0.0D;
        stormLabel = "";
        calmTicks = 0L;
        setChanged();
    }

    public void markRead() {
        readOnce = true;
        setChanged();
    }

    public boolean readOnce() {
        return readOnce;
    }

    /** The kit fitted to this probe. */
    public ProbeUpgrades upgrades() {
        return upgrades;
    }


    /**
     * Fits an upgrade kit, in the field.
     *
     * <p>Pushed to the client and re-checked every tick rather than only written, because the fitted kit is
     * visible: the probe's own readout names it, and a probe that is upgraded while its owner is standing
     * there has to say so on the next frame instead of at the next relog.
     */
    public void fitUpgrades(ProbeUpgrades next) {
        upgrades = next;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public int instrumentPower() {
        return power;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, TornadoProbeBlockEntity entity) {
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!Config.TORNADO_PROBE_ENABLED.get() || entity.state == STATE_RECORDED || entity.state == STATE_LOST) {
            return;
        }
        Vec3 center = Vec3.atCenterOf(pos);
        // The fitted kit decides three things about this deployment, and all three are its own physics:
        // how far the head reaches, how much wind the airframe takes before it is overrun, and - below -
        // how often the head actually samples the wind it is standing in.
        double detectRange = Math.max(32.0D, Config.TORNADO_PROBE_DETECT_RANGE_BLOCKS.get())
                * entity.upgrades.detectRangeMultiplier();
        double armMph = Config.TORNADO_PROBE_ARM_MPH.get();
        double shredMph = Math.max(armMph + 1.0D, Config.TORNADO_PROBE_SHRED_MPH.get())
                * entity.upgrades.survivalMultiplier();

        var wind = WeatherInstruments.wind(level, center);
        Optional<TornadoThreatService.Threat> threat = TornadoThreatService.nearestThreat(serverLevel, center, detectRange);
        boolean inWind = wind.speedMph() >= armMph || threat.isPresent();

        if (!inWind) {
            if (entity.state == STATE_ARMED) {
                // The ambient reference is the calm pressure at this spot, which is what the drop is
                // measured against once the vortex arrives.
                entity.ambientHpa = WeatherInstruments.pressure(serverLevel, center).hpa();
                return;
            }
            entity.calmTicks++;
            if (entity.calmTicks >= CALM_TICKS_TO_SETTLE && entity.hasReading()) {
                entity.state = STATE_RECORDED;
                entity.sync(level, state);
            }
            return;
        }

        entity.calmTicks = 0L;
        if (entity.state == STATE_ARMED) {
            // Freeze the ambient reference now, before the pressure starts falling.
            double ambient = WeatherInstruments.pressure(serverLevel, center).hpa();
            entity.ambientHpa = Double.isNaN(entity.ambientHpa) ? ambient : Math.max(entity.ambientHpa, ambient);
            entity.state = STATE_RECORDING;
        }

        entity.secondsInWind += 0.05D;
        // Sampling, not merely reading: a slow head averages, so what it reports is the mean over its
        // interval rather than the gust inside it, and the storm's true peak is missed. Sampling on the
        // head's own interval and scaling by its gust factor is what makes the same tornado read differently
        // on a stock probe and on a grade three one.
        if (level.getGameTime() % entity.upgrades.sampleIntervalTicks() == 0L) {
            entity.peakWindMph = Math.max(entity.peakWindMph, wind.speedMph() * entity.upgrades.gustFactor());
        }
        threat.ifPresent(nearest -> {
            entity.closestApproachBlocks = entity.closestApproachBlocks < 0.0D
                    ? nearest.distanceBlocks()
                    : Math.min(entity.closestApproachBlocks, nearest.distanceBlocks());
        });

        WeatherInstruments.PressureReading reading = WeatherInstruments.pressure(serverLevel, center);
        if (Double.isNaN(entity.minPressureHpa) || reading.hpa() < entity.minPressureHpa) {
            entity.minPressureHpa = reading.hpa();
        }
        if (!Double.isNaN(entity.ambientHpa) && entity.minPressureHpa > entity.ambientHpa) {
            // A reading that never fell below the reference means the ambient sample was taken late.
            entity.ambientHpa = entity.minPressureHpa;
        }

        double loadFactor = WeatherInstruments.debrisLoadFactor(serverLevel, center, detectRange);
        double impacts = Mth.clamp((wind.speedMph() - 40.0D) / 12.0D, 0.0D, 14.0D) * loadFactor;
        entity.peakDebrisPerSecond = Math.max(entity.peakDebrisPerSecond, impacts);

        WeatherInstruments.StormReading storm = WeatherInstruments.nearestStorm(serverLevel, center, detectRange);
        if (storm != null) {
            entity.stormLabel = storm.label();
        }

        if (wind.speedMph() >= shredMph) {
            entity.state = STATE_LOST;
            entity.sync(level, state);
            return;
        }

        int oldPower = entity.power;
        int newPower = Mth.clamp((int) Math.round(entity.peakWindMph / 20.0D), 0, 15);
        if (newPower != oldPower) {
            entity.power = newPower;
            level.updateNeighborsAt(pos, state.getBlock());
            entity.sync(level, state);
            return;
        }
        if (level.getGameTime() % SYNC_INTERVAL_TICKS == 0L) {
            entity.sync(level, state);
        }
    }

    private void sync(Level level, BlockState state) {
        setChanged();
        level.sendBlockUpdated(worldPosition, state, state, 3);
    }

    /**
     * Writes the deployment itself. Kept separate from {@link #saveAdditional} because this same tag is
     * what travels on a recovered item, and it must be byte-identical in both directions: the item path
     * has no {@code HolderLookup.Provider} to hand to {@code saveAdditional}.
     */
    private void writeReading(CompoundTag tag) {
        // The record writes its own fields, then the live-deployment fields that only a placed probe has,
        // and the kit it was fitted with - a recovered probe comes back with its upgrades on it.
        reading().writeTo(tag);
        upgrades.writeTo(tag);
        tag.putLong("CalmTicks", calmTicks);
        tag.putBoolean("ReadOnce", readOnce);
        tag.putInt("Power", power);
    }

    private void readReading(CompoundTag tag) {
        TornadoProbeReading parsed = TornadoProbeReading.of(tag);
        state = parsed.state();
        ambientHpa = parsed.ambientHpa();
        minPressureHpa = parsed.minPressureHpa();
        peakWindMph = parsed.peakWindMph();
        peakDebrisPerSecond = parsed.peakDebrisPerSecond();
        closestApproachBlocks = parsed.closestApproachBlocks();
        secondsInWind = parsed.secondsInWind();
        stormLabel = parsed.stormLabel();
        upgrades = ProbeUpgrades.of(tag);
        calmTicks = tag.getLong("CalmTicks");
        readOnce = tag.getBoolean("ReadOnce");
        power = tag.getInt("Power");
    }

    /**
     * Exposes the deployment as the stack's {@code block_entity_data} component, which is what lets the
     * loot table hand a recovered probe back to the player still holding its reading. This is the same
     * mechanism a shulker box uses to keep its contents, so the reading rides on whatever stack the loot
     * table produces rather than being bolted on in the break path.
     *
     * <p>The {@code id} is not decoration: {@code block_entity_data} persists through
     * {@code CustomData.CODEC_WITH_ID}, which rejects a tag without one, so a recovered probe without it
     * would be silently unreadable the next time the inventory is saved.
     */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        CompoundTag tag = new CompoundTag();
        writeReading(tag);
        BlockEntity.addEntityType(tag, getType());
        components.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(tag));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        writeReading(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        readReading(tag);
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
