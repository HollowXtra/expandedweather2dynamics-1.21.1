package com.imbet.weather.vortex;

import com.imbet.Config;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import weather2.weathersystem.storm.StormObject;

public final class Weather2VortexInstance extends AbstractVortexInstance {
    private StormObject storm;
    private boolean rotationAssigned;

    Weather2VortexInstance(StormObject storm, ServerLevel level, long gameTime) {
        super(storm.ID, storm.ID, initialMode(storm), storm.pos, storm.motion, 24.0F, windForStage(storm), gameTime, 0L);
        this.storm = storm;
        this.rotationAssigned = false;
    }

    void attach(StormObject storm, ServerLevel level, long gameTime) {
        this.storm = storm;
        markSeen(gameTime);
        if (!rotationAssigned && Config.ANTI_CYCLONIC_TORNADOES_ENABLED.get() && storm.isTornadoFormingOrGreater() && !storm.attrib_waterSpout && !storm.weatherMachineControlled && !storm.isPlayerControlled() && !storm.isPet()) {
            rotationAssigned = true;
            setAntiCyclonic(level.random.nextDouble() < Config.ANTI_CYCLONIC_TORNADO_CHANCE.get());
        }
    }

    @Override
    protected void tickSource(ServerLevel level, long gameTime) {
        if (storm == null || storm.isDead || storm.pos == null) {
            if (lastSeenTick + 60L < gameTime) {
                dead = true;
            }
            return;
        }
        previousX = x;
        previousY = y;
        previousZ = z;
        x = storm.pos.x;
        y = storm.pos.y;
        z = storm.pos.z;
        motionX = storm.motion == null ? x - previousX : storm.motion.x;
        motionY = storm.motion == null ? y - previousY : storm.motion.y;
        motionZ = storm.motion == null ? z - previousZ : storm.motion.z;
        targetMaxWindMph = windForStage(storm);
        if (storm.attrib_waterSpout && mode != VortexMode.FIRENADO) {
            transitionTo(VortexMode.WATERSPOUT, gameTime);
        } else if (storm.isTornadoFormingOrGreater() && mode != VortexMode.FIRENADO && mode != VortexMode.LAND_TORNADO) {
            transitionTo(VortexMode.LAND_TORNADO, gameTime);
        }
        float stage = Math.max(0.0F, (storm.levelCurIntensityStage - StormObject.STATE_FORMING) / 5.0F);
        targetDamagePathWidth = Math.max(targetDamagePathWidth, 18.0F + stage * 180.0F);
        if (Config.TORNADO_REALISTIC_WIDTH_MODEL.get()) {
            targetDamagePathWidth = Math.min(targetDamagePathWidth, (float) Config.effectiveMaxDamageWidthBlocks());
        }
        if (Config.ANTI_CYCLONIC_INVERT_WEATHER2_SPIN.get()) {
            storm.spinSpeed = Math.max(0.055D, visualSpinSpeed) * (antiCyclonic ? -1.0D : 1.0D);
        }
    }

    @Override
    protected int intensityStage() {
        return storm == null ? StormObject.STATE_FORMING : storm.levelCurIntensityStage;
    }

    static VortexMode initialMode(StormObject storm) {
        return storm != null && storm.attrib_waterSpout ? VortexMode.WATERSPOUT : VortexMode.LAND_TORNADO;
    }

    static float windForStage(StormObject storm) {
        if (storm == null) {
            return 0.0F;
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE5) {
            return Config.WINDFIELD_STAGE5_MPH.get().floatValue();
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE4) {
            return Config.WINDFIELD_STAGE4_MPH.get().floatValue();
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE3) {
            return Config.WINDFIELD_STAGE3_MPH.get().floatValue();
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE2) {
            return Config.WINDFIELD_STAGE2_MPH.get().floatValue();
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE1) {
            return Config.WINDFIELD_STAGE1_MPH.get().floatValue();
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_FORMING || storm.attrib_waterSpout) {
            return Config.WINDFIELD_FORMING_MPH.get().floatValue();
        }
        return 0.0F;
    }
}
