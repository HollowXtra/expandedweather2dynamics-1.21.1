package com.imbet.anemometer;

import com.imbet.Config;
import com.imbet.registry.ModContent;
import com.imbet.weather.AdvancementAwarder;
import com.imbet.weather.TornadoWindField;
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
        TornadoWindField.WindSample sample = sampleWind(level, pos);
        windMph = sample.speedMph();
        // Cap the drawn spin well below where it would turn into a shimmer. The rotor has fourfold
        // symmetry, so at ninety degrees a tick it would look perfectly still - and a tornado's wind can
        // ask for far more than that. Thirty-six keeps a violent vortex obviously faster than a breeze
        // while still reading as a spin.
        rotationalVelocity = Math.min(36.0F, rotationalVelocity * 0.94F + (float) windMph * 0.055F);
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
        // Shared with the weather balloon so both instruments always agree on the local wind.
        return TornadoWindField.sampleStrongest(level, Vec3.atCenterOf(pos));
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
