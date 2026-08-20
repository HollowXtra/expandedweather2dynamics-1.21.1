package com.imbet.anemometer;

import com.imbet.Config;
import com.imbet.registry.ModContent;
import com.imbet.weather.AdvancementAwarder;
import com.imbet.weather.vortex.MutableWindSample;
import com.imbet.weather.vortex.VortexEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

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
        MutableWindSample sample = new MutableWindSample();
        VortexEngine.sampleWind(level, Vec3.atCenterOf(pos).x, Vec3.atCenterOf(pos).y, Vec3.atCenterOf(pos).z, sample);
        windMph = sample.speedMph;
        rotationalVelocity = Math.min(55.0F, rotationalVelocity * 0.94F + (float) windMph * 0.055F);
        angle += rotationalVelocity;
        if (angle >= 360.0F) {
            angle -= 360.0F;
            anglePrev -= 360.0F;
        }
        if (!level.isClientSide() && level.getGameTime() % 10L == 0L) {
            int newPower = (int) Math.min(15.0D, windMph / 8.0D);
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

    @Override
    protected void saveAdditional(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putDouble("WindMph", windMph);
        tag.putInt("Power", power);
        tag.putFloat("Angle", angle);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        windMph = tag.getDouble("WindMph");
        power = tag.getInt("Power");
        angle = tag.getFloat("Angle");
        anglePrev = angle;
    }
}
