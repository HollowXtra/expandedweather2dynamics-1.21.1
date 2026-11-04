package com.imbet.instruments;

import com.imbet.Config;
import com.imbet.registry.ModContent;
import com.imbet.weather.TornadoWindField;
import com.imbet.weather.WeatherInstruments;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Weather Vane: which way the wind is blowing, readable at a glance.
 *
 * <p>The arrow points into the wind - toward the direction the wind is coming <em>from</em> - which is
 * the meteorological convention and the same direction the readout names. It is deliberately the one
 * instrument with no panel and no number on its face: its job is to be understood from across a base
 * without being walked up to.
 *
 * <p>Like the anemometer it samples on both sides and syncs nothing, because the wind field is derived
 * from storm data that both sides already have. Nothing about the arrow's position is authoritative,
 * so there is nothing to keep in step.
 */
public class WeatherVaneBlockEntity extends BlockEntity {
    private float heading;
    private float headingPrev;
    private double windMph;
    private String fromLabel = "CALM";

    public WeatherVaneBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModContent.WEATHER_VANE_BLOCK_ENTITY.get(), pos, blockState);
    }

    public double getWindMph() {
        return windMph;
    }

    public String getFromLabel() {
        return fromLabel;
    }

    /** The heading in degrees the arrow should point, interpolated for smooth rendering. */
    public float getRenderHeading(float partialTick) {
        return headingPrev + (heading - headingPrev) * partialTick;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, WeatherVaneBlockEntity entity) {
        entity.headingPrev = entity.heading;
        if (!Config.WEATHER_VANE_ENABLED.get()) {
            return;
        }
        TornadoWindField.WindSample sample = WeatherInstruments.wind(level, Vec3.atCenterOf(pos));
        entity.windMph = sample.speedMph();
        entity.fromLabel = WeatherInstruments.windFromLabel(sample.mphVector());
        entity.heading = approach(entity.heading, (float) WeatherInstruments.windFromDegrees(sample.mphVector()), 0.10F);
        // Keep both ends of the interpolation bounded, or a vane left running for days would slowly
        // lose float precision and start stuttering.
        if (Math.abs(entity.heading) > 720.0F) {
            float turns = (float) Math.floor(entity.heading / 360.0F) * 360.0F;
            entity.heading -= turns;
            entity.headingPrev -= turns;
        }
    }

    /** Moves a heading toward a target the short way round, so it never unwinds the long way. */
    private static float approach(float current, float target, float rate) {
        float delta = ((target - current + 540.0F) % 360.0F) - 180.0F;
        return current + delta * rate;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putFloat("Heading", heading);
        tag.putDouble("WindMph", windMph);
        tag.putString("From", fromLabel);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        heading = tag.getFloat("Heading");
        headingPrev = heading;
        windMph = tag.getDouble("WindMph");
        fromLabel = tag.contains("From") ? tag.getString("From") : "CALM";
    }
}
