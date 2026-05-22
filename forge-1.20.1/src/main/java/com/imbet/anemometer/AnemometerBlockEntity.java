package com.imbet.anemometer;

import com.imbet.Config;
import com.imbet.registry.ModContent;
import com.imbet.weather.AddonWeatherEventService;
import com.imbet.weather.AdvancementAwarder;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.SupercellStructureService;
import com.imbet.weather.TornadoWindField;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

public class AnemometerBlockEntity extends BlockEntity {
    private float angle;
    private float anglePrev;
    private float rotationalVelocity;
    private double windMph;
    private int power;

    public AnemometerBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModContent.ANEMOMETER_BLOCK_ENTITY.get(), pos, blockState);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, AnemometerBlockEntity entity) {
        entity.tick(level, pos, state);
    }

    private void tick(Level level, BlockPos pos, BlockState state) {
        anglePrev = angle;
        if (!Config.ANEMOMETER_ENABLED.get()) {
            return;
        }
        TornadoWindField.WindSample sample = sampleWind(level, pos);
        windMph = sample.speedMph();
        rotationalVelocity = Math.min(55.0F, rotationalVelocity * 0.94F + (float) windMph * 0.055F);
        angle += rotationalVelocity;
        if (angle >= 360.0F) {
            angle -= 360.0F;
            anglePrev -= 360.0F;
        }
        if (!level.isClientSide() && level.getGameTime() % 10L == 0L) {
            int newPower = sample.redstonePower();
            if (newPower != power) {
                power = newPower;
                setChanged();
                level.updateNeighborsAt(pos, state.getBlock());
            }
            if (windMph >= 95.0D && level instanceof ServerLevel serverLevel) {
                for (ServerPlayer player : serverLevel.players()) {
                    if (player.distanceToSqr(Vec3.atCenterOf(pos)) < 64.0D) {
                        AdvancementAwarder.award(player, AdvancementAwarder.USE_ANEMOMETER);
                    }
                }
            }
        }
    }

    public int getPower() {
        return power;
    }

    public double getWindMph() {
        return windMph;
    }

    public float getRenderAngle(float partialTick) {
        return anglePrev + (angle - anglePrev) * partialTick;
    }

    private TornadoWindField.WindSample sampleWind(Level level, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        TornadoWindField.WindSample strongest = AddonWeatherEventService.sampleWind(level, center);
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return strongest;
        }
        for (WeatherObject object : manager.getStormObjects()) {
            if (object instanceof StormObject storm && storm.pos != null && storm.pos.distanceTo(center) <= 512.0D && !DynamicStormController.isAbsorbedOrDecaying(storm)) {
                TornadoWindField.WindSample sample = TornadoWindField.sample(storm, level, center);
                if (sample.speedMph() > strongest.speedMph()) {
                    strongest = sample;
                }
                TornadoWindField.WindSample supercellSample = SupercellStructureService.sampleWind(storm, level, center);
                if (supercellSample.speedMph() > strongest.speedMph()) {
                    strongest = supercellSample;
                }
            }
        }
        return strongest;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putDouble("WindMph", windMph);
        tag.putInt("Power", power);
        tag.putFloat("Angle", angle);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        windMph = tag.getDouble("WindMph");
        power = tag.getInt("Power");
        angle = tag.getFloat("Angle");
        anglePrev = angle;
    }
}
