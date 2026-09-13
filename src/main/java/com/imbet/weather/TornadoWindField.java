package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

public final class TornadoWindField {
    /** Storms further than this from the sample point contribute nothing to the local wind. */
    private static final double STORM_SAMPLE_RANGE_BLOCKS = 512.0D;

    private TornadoWindField() {
    }

    /**
     * Strongest wind at a point from every source the addon models together: ambient addon events
     * (dust devils, landspouts, squall lines, cyclones), each nearby Weather2 storm's tornado
     * windfield, and that storm's supercell structure wind.
     *
     * <p>Shared by the anemometer and the weather balloon so every instrument in the addon reads the
     * same wind instead of each one growing its own slightly different sampling loop. Storms already
     * absorbed or decaying are skipped, matching the rest of the storm code.
     */
    public static WindSample sampleStrongest(Level level, Vec3 position) {
        if (level == null || position == null) {
            return WindSample.CALM;
        }
        WindSample strongest = AddonWeatherEventService.sampleWind(level, position);
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return strongest;
        }
        for (WeatherObject object : manager.getStormObjects()) {
            if (!(object instanceof StormObject storm) || storm.pos == null) {
                continue;
            }
            if (storm.pos.distanceTo(position) > STORM_SAMPLE_RANGE_BLOCKS || DynamicStormController.isAbsorbedOrDecaying(storm)) {
                continue;
            }
            WindSample tornado = sample(storm, level, position);
            if (tornado.speedMph() > strongest.speedMph()) {
                strongest = tornado;
            }
            WindSample supercell = SupercellStructureService.sampleWind(storm, level, position);
            if (supercell.speedMph() > strongest.speedMph()) {
                strongest = supercell;
            }
        }
        return strongest;
    }

    public static WindSample sample(StormObject storm, Level level, Vec3 position) {
        if (!Config.WINDFIELD_ENABLED.get() || storm == null || storm.pos == null) {
            return WindSample.CALM;
        }

        DynamicStormState state = DynamicStormController.stateFor(storm);
        Vec3 center = new Vec3(storm.pos.x, position.y, storm.pos.z);
        Vec3 fromCenter = position.subtract(center);
        double radius = Math.max(0.5D, Math.sqrt(fromCenter.x * fromCenter.x + fromCenter.z * fromCenter.z));
        double baseRadius = Math.max(6.0D, DynamicStormController.baseRadiusForWind(storm));
        double radiusMaxWind = Math.max(3.0D, DynamicStormController.radiusOfMaxWind(storm));
        double maxWindMph = maxWindMph(storm);

        double tangentialMph;
        if (radius <= radiusMaxWind) {
            tangentialMph = maxWindMph * (radius / radiusMaxWind);
        } else {
            tangentialMph = maxWindMph * Math.pow(radiusMaxWind / radius, 0.72D);
        }

        // A ground swarm barely puts its parent circulation on the ground: the condensation stops short
        // and the violent surfaces are the sub-vortices turning underneath it. So the parent's broad
        // swath is weakened here, and the swarm's own contributions below are what scour the ground
        // in patches. The storm's reported strength is untouched - radar still sees the parent.
        double parentSurfaceFraction = state.groundSwarmActive()
                ? Mth.clamp(Config.MULTIVORTEX_GROUND_SWARM_PARENT_WIND_FRACTION.get(), 0.05D, 1.0D)
                : 1.0D;
        tangentialMph *= parentSurfaceFraction;
        double inflowMph = tangentialMph * Config.WINDFIELD_RADIAL_INFLOW_FRACTION.get();
        double verticalMph = maxWindMph * Config.WINDFIELD_VERTICAL_LIFT_FRACTION.get()
                * Mth.clamp((float) (1.0D - radius / (baseRadius * 1.8D)), 0.0F, 1.0F)
                * parentSurfaceFraction;

        double rotationSign = DynamicStormController.rotationSign(storm);
        Vec3 radialOut = fromCenter.scale(1.0D / radius);
        Vec3 inward = radialOut.scale(-1.0D);
        Vec3 tangent = new Vec3(-radialOut.z * rotationSign, 0.0D, radialOut.x * rotationSign);
        Vec3 mphVector = tangent.scale(tangentialMph).add(inward.scale(inflowMph)).add(0.0D, verticalMph, 0.0D);
        double strongestLocalMph = mphVector.length();

        long subVortexGameTime = level == null ? 0L : level.getGameTime();
        for (DynamicStormState.SubVortex vortex : state.subVortices()) {
            // A breakdown bubble contributes nothing to the surface wind while it is still aloft.
            if (!vortex.isGroundContact(subVortexGameTime)) {
                continue;
            }
            Vec3 groundedCenter = subVortexGroundCenter(level, storm, vortex, position.y);
            Vec3 vortexCenter = new Vec3(groundedCenter.x, position.y, groundedCenter.z);
            Vec3 fromSub = new Vec3(position.x - vortexCenter.x, 0.0D, position.z - vortexCenter.z);
            double subRadius = Math.max(0.35D, Math.sqrt(fromSub.x * fromSub.x + fromSub.z * fromSub.z));
            double subCoreRadius = Math.max(1.2D, baseRadius * vortex.radiusFraction() * 0.28D * vortex.type().coreScale());
            if (subRadius > subCoreRadius * 3.5D) {
                continue;
            }
            Vec3 subRadialOut = fromSub.scale(1.0D / subRadius);
            Vec3 subTangent = new Vec3(-subRadialOut.z * rotationSign, 0.0D, subRadialOut.x * rotationSign);
            double subMaxWindMph = DynamicStormController.subVortexWindMph(storm, vortex);
            double falloff = Math.max(0.0D, 1.0D - (subRadius / (subCoreRadius * 3.5D)));
            double subMph = subMaxWindMph * falloff;
            double subLiftMph = subMaxWindMph * Config.WINDFIELD_VERTICAL_LIFT_FRACTION.get() * 1.55D * Mth.clamp(falloff * 1.35D, 0.0D, 1.0D);
            Vec3 subVector = subTangent.scale(subMph)
                    .add(subRadialOut.scale(-subMph * 0.34D))
                    .add(0.0D, subLiftMph, 0.0D);
            mphVector = mphVector.add(subVector);
            strongestLocalMph = Math.max(strongestLocalMph, subVector.length());
        }
        for (DynamicStormState.TornadoCirculationState satellite : state.satelliteTornadoes()) {
            Vec3 satelliteCenter = DynamicStormController.satelliteCenter(storm, satellite, position.y);
            Vec3 fromSatellite = position.subtract(satelliteCenter);
            double satelliteRadius = Math.max(0.35D, Math.sqrt(fromSatellite.x * fromSatellite.x + fromSatellite.z * fromSatellite.z));
            double satelliteCore = Math.max(2.0D, satellite.radiusOfMaxWind());
            if (satelliteRadius > satelliteCore * 4.2D) {
                continue;
            }
            Vec3 satelliteRadial = fromSatellite.scale(1.0D / satelliteRadius);
            Vec3 satelliteTangent = new Vec3(-satelliteRadial.z * rotationSign, 0.0D, satelliteRadial.x * rotationSign);
            double satelliteMax = DynamicStormController.satelliteWindMph(storm, satellite);
            double satelliteMph = satelliteRadius <= satelliteCore
                    ? satelliteMax * (satelliteRadius / satelliteCore)
                    : satelliteMax * Math.pow(satelliteCore / satelliteRadius, 0.82D);
            double liftFalloff = satelliteRadius <= satelliteCore
                    ? 1.0D
                    : Mth.clamp(Math.pow(satelliteCore / satelliteRadius, 0.60D), 0.0D, 1.0D);
            double satelliteLiftMph = satelliteMax * Config.WINDFIELD_VERTICAL_LIFT_FRACTION.get() * 1.35D * liftFalloff;
            Vec3 satelliteVector = satelliteTangent.scale(satelliteMph)
                    .add(satelliteRadial.scale(-satelliteMph * 0.32D))
                    .add(0.0D, satelliteLiftMph, 0.0D);
            mphVector = mphVector.add(satelliteVector);
            strongestLocalMph = Math.max(strongestLocalMph, satelliteVector.length());
        }

        return new WindSample(mphVector, mphVector.length(), strongestLocalMph, radius, baseRadius, radiusMaxWind);
    }

    public static Vec3 subVortexCenter(StormObject storm, DynamicStormState.SubVortex vortex, double y) {
        double angle = Math.toRadians(vortex.orbitDegrees());
        return new Vec3(
                storm.pos.x + Math.cos(angle) * vortex.orbitRadius(),
                y,
                storm.pos.z + Math.sin(angle) * vortex.orbitRadius()
        );
    }

    /**
     * Where a sub-vortex should be drawn. Ground-based vortices render at their ring position on the
     * ground, but a descending vortex breakdown bubble instead sits on the funnel wall at whatever
     * height it has fallen to, so it visibly travels down the condensation funnel.
     */
    public static Vec3 subVortexVisualCenter(Level level, StormObject storm, DynamicStormState.SubVortex vortex, double fallbackY) {
        if (storm == null || vortex == null || !vortex.type().descends()) {
            return subVortexGroundCenter(level, storm, vortex, fallbackY);
        }
        long gameTime = level == null ? 0L : level.getGameTime();
        Vec3 funnelCenter = DynamicStormController.funnelLayerCenterAt(storm, vortex.heightFraction(gameTime));
        if (funnelCenter == null) {
            return new Vec3(storm.pos.x, fallbackY, storm.pos.z);
        }
        return funnelCenter;
    }

    public static Vec3 subVortexGroundCenter(Level level, StormObject storm, DynamicStormState.SubVortex vortex, double fallbackY) {
        Vec3 center = subVortexCenter(storm, vortex, fallbackY);
        if (level == null) {
            return center;
        }
        BlockPos sample = BlockPos.containing(center.x, fallbackY, center.z);
        if (!level.isLoaded(sample)) {
            return center;
        }
        BlockPos ground = BlockGrabSafety.vortexGroundSurface(level, sample);
        if (ground == null) {
            return center;
        }
        return new Vec3(center.x, ground.getY() + 0.35D, center.z);
    }

    public static Vec3 horizontalVortexCenter(StormObject storm, DynamicStormState.HorizontalVortex vortex, double y) {
        double angle = Math.toRadians(vortex.orbitDegrees());
        return new Vec3(
                storm.pos.x + Math.cos(angle) * vortex.orbitRadius(),
                y,
                storm.pos.z + Math.sin(angle) * vortex.orbitRadius()
        );
    }

    public static Vec3 horizontalVortexGroundCenter(Level level, StormObject storm, DynamicStormState.HorizontalVortex vortex, double fallbackY) {
        // Anchor on the ACTUAL funnel wall: the live layer centre line plus the wall radius at
        // that height. A circle drawn around the storm position lands in open air whenever the
        // funnel leans, bends or flares, which is why the tentacles used to float beside it.
        if (storm != null && storm.getTornadoFunnelSimple() != null) {
            Vec3 layerCenter = DynamicStormController.funnelLayerCenterAt(storm, vortex.heightFraction());
            if (layerCenter != null) {
                double angle = Math.toRadians(vortex.orbitDegrees());
                double wallRadius = Math.max(2.0D, DynamicStormController.funnelWallRadiusAt(storm, vortex.heightFraction()))
                        * vortex.wallHugFactor();
                return new Vec3(
                        layerCenter.x + Math.cos(angle) * wallRadius,
                        layerCenter.y,
                        layerCenter.z + Math.sin(angle) * wallRadius);
            }
        }
        Vec3 center = horizontalVortexCenter(storm, vortex, fallbackY);
        if (level == null) {
            return center;
        }
        BlockPos sample = BlockPos.containing(center.x, fallbackY, center.z);
        if (!level.isLoaded(sample)) {
            return center;
        }
        BlockPos ground = BlockGrabSafety.vortexGroundSurface(level, sample);
        if (ground == null) {
            return center;
        }
        // Tentacles curl off the UPPER part of the funnel: a fraction of the funnel's
        // height (cloud base down to the ground), like real horizontal vortices that
        // wrap around the top of the tornado rather than hugging the ground.
        double groundY = ground.getY();
        double funnelTop = storm.posBaseFormationPos != null && Double.isFinite(storm.posBaseFormationPos.y)
                ? storm.posBaseFormationPos.y
                : groundY + Math.max(24.0D, DynamicStormController.baseRadiusForWind(storm) * 6.0D);
        double height = Math.max(4.0D, (funnelTop - groundY) * vortex.heightFraction());
        return new Vec3(center.x, groundY + 0.45D + height, center.z);
    }

    public static double maxWindMph(StormObject storm) {
        if (storm == null) {
            return 0.0D;
        }
        if (Config.WINDFIELD_DYNAMIC_WIND_SPEEDS.get()) {
            DynamicStormState state = DynamicStormController.stateFor(storm);
            if (state.liveMaxWindMph() > 0.05F) {
                return state.liveMaxWindMph();
            }
        }
        return stageMaxWindMph(storm);
    }

    public static double stageMaxWindMph(StormObject storm) {
        if (storm == null) {
            return 0.0D;
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE5) {
            return Config.WINDFIELD_STAGE5_MPH.get();
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE4) {
            return Config.WINDFIELD_STAGE4_MPH.get();
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE3) {
            return Config.WINDFIELD_STAGE3_MPH.get();
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE2) {
            return Config.WINDFIELD_STAGE2_MPH.get();
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE1) {
            return Config.WINDFIELD_STAGE1_MPH.get();
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_FORMING || storm.attrib_waterSpout) {
            return Config.WINDFIELD_FORMING_MPH.get();
        }
        return 0.0D;
    }

    public static double estimateDynamicMaxWindMph(StormObject storm, Level level, DynamicStormState state, long gameTime) {
        if (storm == null) {
            return 0.0D;
        }
        double stageCeiling = stageMaxWindMph(storm);
        if (stageCeiling <= 0.0D) {
            return 0.0D;
        }
        double stageFloor = stageFloorWindMph(storm);
        double intensity = Mth.clamp(storm.levelCurStagesIntensity, 0.0F, 1.0F);
        double curve = smoothstep(intensity);
        double base = Mth.lerp(curve, stageFloor, stageCeiling);

        DynamicStormState liveState = state == null ? DynamicStormController.stateFor(storm) : state;
        double structureBoost = 1.0D
                + liveState.supercellStructureStrength() * 0.045D
                + liveState.mesocycloneStrength() * 0.085D
                + liveState.wallCloudRotationStrength() * 0.075D
                + liveState.inflowStrength() * 0.060D
                + liveState.rfdStrength() * 0.050D;

        double cloudWindMph = 0.0D;
        if (storm.manager != null && storm.manager.getWindManager() != null) {
            cloudWindMph = Math.max(0.0D, storm.manager.getWindManager().getWindSpeedForClouds() * 18.0D);
        }
        double environmentalBoost = 1.0D + Mth.clamp((float) (cloudWindMph / 120.0D), 0.0F, 1.0F) * 0.080D;
        double weakeningPressure = weakeningPressure(storm, liveState, intensity);
        double lifecycleBoost = storm.isGrowing ? 1.035D : 1.0D;
        if (storm.hasStormPeaked) {
            lifecycleBoost *= lerp(weakeningPressure, 0.94D, 0.62D);
        } else if (!storm.isGrowing) {
            lifecycleBoost *= lerp(weakeningPressure, 1.0D, 0.84D);
        }
        double widthRatio = liveState.damagePathWidth() / Math.max(1.0D, Config.effectiveMaxDamageWidthBlocks());
        double widthBoost = Mth.clamp((float) (1.08D - widthRatio * 0.18D), 0.86F, 1.10F);
        double multiVortexBoost = liveState.multiVortexVisualActive()
                ? 1.03D + Math.min(0.12D, liveState.subVortices().size() * 0.035D)
                : 1.0D;
        boolean drillBit = liveState.targetFunnelMorphology() == FunnelMorphology.DRILL_BIT || liveState.funnelMorphology() == FunnelMorphology.DRILL_BIT;
        double morphologyBoost = drillBit ? 1.08D : 1.0D;
        double gustiness = Config.WINDFIELD_DYNAMIC_GUSTINESS.get();
        double pulseA = Math.sin(gameTime * 0.041D + storm.ID * 0.173D);
        double pulseB = Math.sin(gameTime * 0.017D + storm.ID * 0.731D);
        double pulseC = Math.sin(gameTime * 0.093D + storm.ID * 0.097D) * liveState.rfdStrength();
        double gustPulse = 1.0D + gustiness * (pulseA * 0.46D + pulseB * 0.34D + pulseC * 0.20D);

        double target = base * structureBoost * environmentalBoost * lifecycleBoost * widthBoost * multiVortexBoost * morphologyBoost * gustPulse;
        if (storm.attrib_waterSpout) {
            target = Math.min(target, Math.max(Config.WINDFIELD_STAGE1_MPH.get(), stageCeiling * 1.08D));
        }
        if (DynamicStormController.isNativeCyclone(storm)) {
            target = Mth.clamp(target * 0.84D, 45.0D, Math.max(90.0D, stageCeiling * 1.08D));
        }

        double lowerBoundFactor = lerp(weakeningPressure, 0.82D, liveState.isAbsorbedOrDecaying() ? 0.0D : 0.42D);
        double lowerBound = Math.max(0.0D, stageFloor * lowerBoundFactor);
        double upperBound = Math.max(stageCeiling, stageCeiling * (storm.levelCurIntensityStage >= StormObject.STATE_STAGE5 ? 1.10D : 1.08D) * (drillBit ? 1.06D : 1.0D));
        return Mth.clamp(target, lowerBound, upperBound);
    }

    private static double weakeningPressure(StormObject storm, DynamicStormState state, double stageIntensity) {
        if (storm == null) {
            return 1.0D;
        }
        if (state != null && state.isAbsorbedOrDecaying()) {
            return 1.0D;
        }
        double stageFalloff = 1.0D - Mth.clamp(stageIntensity, 0.0D, 1.0D);
        double pressure = 0.0D;
        if (storm.hasStormPeaked) {
            pressure = 0.38D + stageFalloff * 0.62D;
        } else if (!storm.isGrowing) {
            pressure = stageFalloff * 0.34D;
        }
        if (storm.levelCurIntensityStage <= StormObject.STATE_FORMING) {
            pressure = Math.max(pressure, 0.65D);
        }
        return Mth.clamp(pressure, 0.0D, 1.0D);
    }

    private static double lerp(double amount, double min, double max) {
        return min + (max - min) * Mth.clamp(amount, 0.0D, 1.0D);
    }

    private static double stageFloorWindMph(StormObject storm) {
        if (storm == null) {
            return 0.0D;
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE5) {
            return Config.WINDFIELD_STAGE4_MPH.get();
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE4) {
            return Config.WINDFIELD_STAGE3_MPH.get();
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE3) {
            return Config.WINDFIELD_STAGE2_MPH.get();
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE2) {
            return Config.WINDFIELD_STAGE1_MPH.get();
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE1) {
            return Config.WINDFIELD_FORMING_MPH.get();
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_FORMING || storm.attrib_waterSpout) {
            return Math.max(45.0D, Config.WINDFIELD_FORMING_MPH.get() * 0.76D);
        }
        return 0.0D;
    }

    private static double smoothstep(double value) {
        double clamped = Mth.clamp(value, 0.0D, 1.0D);
        return clamped * clamped * (3.0D - 2.0D * clamped);
    }

    public static Vec3 mphToEntityDelta(Vec3 mphVector) {
        return mphVector.scale(Config.WINDFIELD_ENTITY_PUSH_SCALE.get());
    }

    public record WindSample(Vec3 mphVector, double speedMph, double strongestLocalMph, double distanceFromCore, double baseRadius, double radiusMaxWind) {
        public static final WindSample CALM = new WindSample(Vec3.ZERO, 0.0D, 0.0D, Double.MAX_VALUE, 0.0D, 0.0D);

        public int redstonePower() {
            return Mth.clamp((int) Math.round(speedMph / 20.0D), 0, 15);
        }
    }
}
