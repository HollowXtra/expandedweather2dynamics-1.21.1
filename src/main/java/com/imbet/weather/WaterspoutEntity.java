package com.imbet.weather;

import com.imbet.registry.ModContent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class WaterspoutEntity extends DustDevilEntity {
    public static final float MAX_FAIR_WEATHER_WIND_MPH = 110.0F;

    public WaterspoutEntity(EntityType<? extends WaterspoutEntity> type, Level level) {
        super(type, level);
    }

    public WaterspoutEntity(Level level, Vec3 position, int lifeTicks, float radius, float maxWindMph) {
        super(ModContent.WATERSPOUT_ENTITY.get(), level, position, lifeTicks, radius, Math.min(MAX_FAIR_WEATHER_WIND_MPH, maxWindMph));
    }

    @Override
    protected void clampToBoundary(ServerLevel level) {
        if (!DynamicStormController.isOverWaterOrOcean(level, getX(), getZ())) {
            discard();
            return;
        }
        clampYAboveTerrain(level, 0.0D, 180.0D);
    }

    @Override
    protected double horizontalForceScale() {
        return 0.00060D;
    }

    @Override
    protected double inflowScale() {
        return 0.48D;
    }

    @Override
    protected double liftScale() {
        return 0.82D;
    }

    @Override
    protected double motionDrag() {
        return 0.9975D;
    }
}
