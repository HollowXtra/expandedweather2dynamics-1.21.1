package com.imbet.weather;

import com.imbet.registry.ModContent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class GustnadoEntity extends DustDevilEntity {
    public GustnadoEntity(EntityType<? extends GustnadoEntity> type, Level level) {
        super(type, level);
    }

    public GustnadoEntity(Level level, Vec3 position, int lifeTicks, float radius, float maxWindMph) {
        super(ModContent.GUSTNADO_ENTITY.get(), level, position, lifeTicks, radius, maxWindMph);
    }

    @Override
    protected void clampToBoundary(ServerLevel level) {
        if (DynamicStormController.isOverWaterOrOcean(level, getX(), getZ())) {
            discard();
            return;
        }
        clampYAboveTerrain(level, 0.0D, 200.0D);
    }

    @Override
    protected double horizontalForceScale() {
        return 0.00175D;
    }

    @Override
    protected double inflowScale() {
        return 0.38D;
    }

    @Override
    protected double liftScale() {
        return 0.74D;
    }

    @Override
    protected double suctionRangeMultiplier() {
        return 6.4D;
    }

    @Override
    protected double verticalSuctionRange() {
        return 9.0D;
    }

    @Override
    protected double entityForceMultiplier() {
        return 3.0D;
    }

    @Override
    protected double motionDrag() {
        return 0.992D;
    }

    @Override
    protected double minimumHorizontalMotion() {
        return 0.030D;
    }
}
