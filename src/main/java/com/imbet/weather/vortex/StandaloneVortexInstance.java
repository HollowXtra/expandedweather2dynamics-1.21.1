package com.imbet.weather.vortex;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import weather2.weathersystem.storm.StormObject;

public class StandaloneVortexInstance extends AbstractVortexInstance {
    private final int syntheticStage;

    public StandaloneVortexInstance(long id, VortexMode mode, Vec3 position, Vec3 motion, float radius, float maxWindMph, long gameTime, long expireTick) {
        super(id, Long.MIN_VALUE, mode, position, motion, Math.max(4.0F, radius * 2.0F), maxWindMph, gameTime, expireTick);
        this.syntheticStage = stageForWind(maxWindMph);
        this.targetDamagePathWidth = Math.max(4.0F, radius * 2.0F);
        this.targetVisibleFunnelWidth = Math.max(3.0F, radius);
    }

    protected StandaloneVortexInstance(long id, long parentStormId, VortexMode mode, Vec3 position, Vec3 motion, float radius, float maxWindMph, long gameTime, long expireTick) {
        super(id, parentStormId, mode, position, motion, Math.max(4.0F, radius * 2.0F), maxWindMph, gameTime, expireTick);
        this.syntheticStage = stageForWind(maxWindMph);
        this.targetDamagePathWidth = Math.max(4.0F, radius * 2.0F);
        this.targetVisibleFunnelWidth = Math.max(3.0F, radius);
    }

    @Override
    protected void tickSource(ServerLevel level, long gameTime) {
        x += motionX;
        y += motionY;
        z += motionZ;
        motionX *= mode == VortexMode.WATERSPOUT ? 0.992D : 0.985D;
        motionY *= 0.85D;
        motionZ *= mode == VortexMode.WATERSPOUT ? 0.992D : 0.985D;
        if (expireTick > 0L) {
            long remaining = expireTick - gameTime;
            if (remaining < 120L) {
                targetMaxWindMph = Math.max(8.0F, targetMaxWindMph * 0.985F);
                targetDamagePathWidth = Math.max(3.0F, targetDamagePathWidth * 0.992F);
            }
        }
    }

    @Override
    protected int intensityStage() {
        return syntheticStage;
    }

    private static int stageForWind(float maxWindMph) {
        if (maxWindMph >= 170.0F) {
            return StormObject.STATE_STAGE3;
        }
        if (maxWindMph >= 135.0F) {
            return StormObject.STATE_STAGE2;
        }
        if (maxWindMph >= 105.0F) {
            return StormObject.STATE_STAGE1;
        }
        if (maxWindMph >= 70.0F) {
            return StormObject.STATE_FORMING;
        }
        return Mth.clamp(StormObject.STATE_THUNDER, StormObject.STATE_NORMAL, StormObject.STATE_STAGE1);
    }
}
