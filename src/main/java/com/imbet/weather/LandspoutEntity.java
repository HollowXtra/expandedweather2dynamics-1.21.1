package com.imbet.weather;

import com.imbet.registry.ModContent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class LandspoutEntity extends DustDevilEntity {
    public static final float MAX_EF2_WIND_MPH = 135.0F;

    public LandspoutEntity(EntityType<? extends LandspoutEntity> type, Level level) {
        super(type, level);
    }

    public LandspoutEntity(Level level, Vec3 position, int lifeTicks, float radius, float maxWindMph) {
        super(ModContent.LANDSPOUT_ENTITY.get(), level, position, lifeTicks, radius, Math.min(MAX_EF2_WIND_MPH, maxWindMph));
    }

    @Override
    protected void clampToBoundary(ServerLevel level) {
        if (DynamicStormController.isOverWaterOrOcean(level, getX(), getZ())) {
            discard();
            return;
        }
        clampYAboveTerrain(level, 5.5D, 180.0D);
    }

    @Override
    protected double horizontalForceScale() {
        return 0.00062D;
    }

    @Override
    protected double inflowScale() {
        return 0.42D;
    }

    @Override
    protected double liftScale() {
        return 0.72D;
    }

    @Override
    protected double motionDrag() {
        return 0.996D;
    }
}
