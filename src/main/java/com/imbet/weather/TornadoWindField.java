package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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
    /** Metres per second to miles per hour, for reading a CAPE-derived updraft as a wind speed. */
    private static final double MPS_TO_MPH = 2.23694D;
    /** The share of the updraft a tornado reaches with no shear at all, and the share each m/s adds. */
    private static final double SHEAR_MULTIPLE_BASE = 0.80D;
    private static final double SHEAR_MULTIPLE_PER_MPS = 0.035D;

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
            // The gate has to be measured to the same place the profile is centred on: a probe standing
            // under the funnel but a few hundred blocks from the storm cell was skipped entirely and
            // read the ambient wind instead of the tornado it was deployed for.
            // A hurricane's field IS its size, so the gate has to grow with the shield: a probe sitting
            // in the eye wall of a storm whose shield reaches a thousand blocks would otherwise be
            // skipped for being far from its centre, and read the ambient wind instead.
            double sampleRange = HurricaneShield.isShieldStorm(storm)
                    ? Math.max(STORM_SAMPLE_RANGE_BLOCKS, HurricaneShield.shieldRadius(storm) * 1.25D)
                    : STORM_SAMPLE_RANGE_BLOCKS;
            if (samplingAnchor(storm).distanceTo(position) > sampleRange
                    || DynamicStormController.isAbsorbedOrDecaying(storm)) {
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
        // A vortex has to be sampled around the vortex, not around the storm cell it lives in. Weather2's
        // storm.pos is the middle of the whole system - the mesocyclone and precipitation shield - and a
        // tornado's base can sit hundreds of blocks from it. Centring the profile on storm.pos put the
        // peak-wind ring in the wrong place twice over: a probe standing under the funnel read the outer
        // tail of a profile centred far away (a violent tornado reporting a gust), and a probe parked at
        // the calm cell centre read the eyewall of a vortex it was nowhere near.
        Vec3 anchorPos = samplingAnchor(storm);
        Vec3 center = new Vec3(anchorPos.x, position.y, anchorPos.z);
        Vec3 fromCenter = position.subtract(center);
        double radius = Math.max(0.5D, Math.sqrt(fromCenter.x * fromCenter.x + fromCenter.z * fromCenter.z));
        double maxWindMph = maxWindMph(storm);
        // The field is not a circle, so the two radii the whole profile hangs off are read per bearing:
        // the isotachs come out lobed and trailed along the storm's own track instead of drawn with a
        // compass, and everything downstream of this profile - the scoured swath, the damage ring, the
        // dust the client lifts - inherits the same shape. What is deliberately NOT touched is the
        // distance the sample is at: this is about where the wind reaches, not about pretending the
        // player is standing somewhere else. A shield storm is left alone, since a hurricane's eye and
        // its shield are the storm's own published geometry rather than this model's to bend.
        double reach = HurricaneShield.isShieldStorm(storm)
                ? 1.0D
                : TornadoFootprint.reachShare(storm.ID, Math.atan2(fromCenter.z, fromCenter.x),
                        TornadoFootprint.motionBearing(storm.motion), level == null ? 0L : level.getGameTime());
        double baseRadius = Math.max(6.0D, DynamicStormController.baseRadiusForWind(storm)) * reach;
        double radiusMaxWind = Math.max(3.0D, DynamicStormController.radiusOfMaxWind(storm)) * reach;

        double tangentialMph;
        // How the lifted air is shaped with distance: 1 right over the core, fading outward. A tornado's
        // is the usual core falloff; a hurricane's is the eyewall ring, with the calm eye subsiding
        // instead of lifting.
        double verticalProfile;
        if (HurricaneShield.isShieldStorm(storm)) {
            // A hurricane is not a wide tornado: the peak wind is the eye WALL, the eye is nearly calm,
            // and the field outside decays slowly across the whole shield instead of dying within a few
            // core radii. The eye the player can see (the deck is kept out of the same circle) is the
            // eye this profile is calm in, because both read the same eye radius.
            double shieldRadius = HurricaneShield.shieldRadius(storm);
            double eyeRadius = Math.max(24.0D, HurricaneShield.eyeRadius(shieldRadius));
            radiusMaxWind = eyeRadius;
            baseRadius = shieldRadius;
            tangentialMph = maxWindMph * HurricaneShield.windFraction(radius, eyeRadius);
            double ringDistance = Math.abs(radius - eyeRadius) / eyeRadius;
            verticalProfile = Mth.clamp(1.0D - ringDistance * 0.85D, 0.0D, 1.0D);
        } else if (radius <= radiusMaxWind) {
            tangentialMph = maxWindMph * (radius / radiusMaxWind);
            verticalProfile = Mth.clamp(1.0D - radius / (baseRadius * 1.8D), 0.0D, 1.0D);
        } else {
            // The Rankine outer branch: Vmax * (Rmax / r)^a. The exponent is the profile's own dial,
            // so a wide wedge can hold its wind further out and a tight core can drop it sharply.
            tangentialMph = maxWindMph * Math.pow(radiusMaxWind / radius, Config.WINDFIELD_OUTSIDE_PROFILE_EXPONENT.get());
            verticalProfile = Mth.clamp(1.0D - radius / (baseRadius * 1.8D), 0.0D, 1.0D);
        }
        // Point turbulence: the measured wind at any one spot flickers faster and harder than
        // the storm-wide gust train, because the sample sits in eddies riding the circulation.
        // The gust train itself is applied in maxWindMph, so this adds on top of it.
        if (Config.WINDFIELD_DYNAMIC_WIND_SPEEDS.get() && !state.groundSwarmActive()) {
            double turbulence = 1.0D + TornadoGustModel.pointTurbulence(storm.ID, level == null ? 0L : level.getGameTime())
                    * Config.WINDFIELD_DYNAMIC_GUSTINESS.get() * 0.55D;
            tangentialMph *= turbulence;
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
                * verticalProfile
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

    /**
     * Where this storm's surface circulation actually is: the funnel's base for a tornado, and the storm
     * cell itself for anything without one (a plain supercell, a cyclone, a particle storm). Everything
     * that samples or gates the vortex profile goes through here, so wind, probes and anemometers all
     * agree with the funnel the player can see.
     */
    public static Vec3 samplingAnchor(StormObject storm) {
        if (storm == null || storm.pos == null) {
            return Vec3.ZERO;
        }
        if (DynamicStormController.isDynamicVortex(storm)) {
            Vec3 circulation = DynamicStormController.tornadoCirculationCenter(storm);
            if (circulation != null && Double.isFinite(circulation.x) && Double.isFinite(circulation.z)) {
                return circulation;
            }
        }
        return storm.pos;
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

    /**
     * The wind a stage's own band starts at, with the strength dial on it: the rung below's ceiling,
     * which is the ladder's own answer for "what this storm is worth from the bottom".
     *
     * <p>Kept beside {@link #stageMaxWindMph} for the same reason: a reader that wants a band has to come
     * through the ladder, so the floor of one rung cannot drift away from the ceiling of the last. It is
     * the number the ground-contact floor is held to, so a tornado sitting on the ground cannot read under
     * its own class while its rung says otherwise.
     */
    public static double stageFloorWindMph(StormObject storm) {
        if (storm == null) {
            return 0.0D;
        }
        return Config.windMph(rawStageFloorWindMph(storm));
    }

    public static double maxWindMph(StormObject storm) {
        if (storm == null) {
            return 0.0D;
        }
        if (Config.WINDFIELD_DYNAMIC_WIND_SPEEDS.get()) {
            DynamicStormState state = DynamicStormController.stateFor(storm);
            if (state.liveMaxWindMph() > 0.05F) {
                // The live value is the sustained wind; the gust event train rides on top. The
                // multiplier is advanced once per tick in smooth() on both sides, so every read
                // this tick agrees and nothing is evaluated per call. A ground swarm's
                // deliberately calm parent reads no gusts there either.
                double live = state.liveMaxWindMph() * state.gustMultiplier();
                // The on-ground floor rides on top of the gust train, so a lull in a tornado that is
                // visibly sitting on the ground cannot read as a dead calm either.
                return Math.max(live, state.groundWindFloorMph());
            }
        }
        return stageMaxWindMph(storm);
    }

    /**
     * The wind a stage is worth, with the simple settings' strength dial on it.
     *
     * <p>This and {@link #estimateDynamicMaxWindMph} are the class's only two ways to get a wind out, and both
     * of them put the dial on their answer - this one on the rung's ceiling, that one on the blended estimate.
     * Everything else that reads a band, a floor or a threshold is inside the arithmetic between them, where
     * the numbers are raw and therefore comparable with each other; a reader there that took the dial would be
     * scaling one term of a sum, and a reader that skipped it while comparing against a dialed wind would be
     * measuring two different worlds.
     *
     * <p>What it deliberately does not move is which stage a storm reaches: a strength of half still gives an
     * EF4 its stage and its look, and halves the wind that stage is worth.
     */
    public static double stageMaxWindMph(StormObject storm) {
        if (storm == null) {
            return 0.0D;
        }
        double stage = rawStageMaxWindMph(storm);
        return Config.windMph(stage);
    }

    /** The ladder's rungs, lowest first: the order Weather2's own stages sit in. */
    private static final int[] BAND_RUNGS = {
            StormObject.STATE_FORMING, StormObject.STATE_STAGE1, StormObject.STATE_STAGE2,
            StormObject.STATE_STAGE3, StormObject.STATE_STAGE4, StormObject.STATE_STAGE5};

    /** The flotsam floor: what a circulation reads at when nothing below it has a band of its own. */
    private static final double FLOTSAM_FLOOR_MPH = 45.0D;

    /**
     * The raw wind that ends each rung's band, in rung order.
     *
     * <p>Every read of the ladder's settings lives in this one method, which is what makes "the raw ladder is
     * unreachable from outside" a property of the file rather than a promise about it. A reader that wants a
     * band has to come through here, and a reader that comes through here is one the strength dial can be put
     * on. Written as a switch on the rung rather than a cascade of {@code >=} tests for the same reason: the
     * ladder's shape is then stated once, in one direction, instead of being restated by every reader.
     */
    private static double rawBandCeilingMph(int rung) {
        return switch (rung) {
            case 5 -> Config.WINDFIELD_STAGE5_MPH.get();
            case 4 -> Config.WINDFIELD_STAGE4_MPH.get();
            case 3 -> Config.WINDFIELD_STAGE3_MPH.get();
            case 2 -> Config.WINDFIELD_STAGE2_MPH.get();
            case 1 -> Config.WINDFIELD_STAGE1_MPH.get();
            case 0 -> Config.WINDFIELD_FORMING_MPH.get();
            default -> 0.0D;
        };
    }

    /** Which rung a Weather2 stage sits on, or -1 for a storm that has no band at all. */
    private static int rungOf(int stage) {
        int rung = -1;
        for (int i = 0; i < BAND_RUNGS.length; i++) {
            if (stage >= BAND_RUNGS[i]) {
                rung = i;
            }
        }
        return rung;
    }

    private static double rawStageMaxWindMph(StormObject storm) {
        if (storm == null) {
            return 0.0D;
        }
        int rung = rungOf(storm.levelCurIntensityStage);
        if (rung < 0 && storm.attrib_waterSpout) {
            rung = 0;
        }
        return rawBandCeilingMph(rung);
    }

    public static double estimateDynamicMaxWindMph(StormObject storm, Level level, DynamicStormState state, long gameTime) {
        if (storm == null) {
            return 0.0D;
        }
        // Everything in this method is a RAW wind - the rung's band, the air mass's own potential, the health
        // floor, the caps - and the strength dial is put on the single answer at the end of it. That is not a
        // style choice: the band was the one term the dial used to touch, so a violent storm standing in
        // violent air read half of its rung plus all of the air, which is a storm that barely noticed the
        // setting. One unit for the arithmetic, one multiplier on the result.
        double stageCeiling = rawStageMaxWindMph(storm);
        if (stageCeiling <= 0.0D) {
            return 0.0D;
        }
        double stageFloor = rawStageFloorWindMph(storm);
        double intensity = Mth.clamp(storm.levelCurStagesIntensity, 0.0F, 1.0F);
        double curve = smoothstep(intensity);
        // Where the storm's own rung says the wind should be. This used to BE the answer, bracketed
        // between the band's floor and its ceiling, which is what made a tornado's wind a function of
        // its stage label: a stage-3 storm could not blow harder than its band no matter what air it
        // stood in. It is now one of two readings, carrying a weight rather than a bracket.
        double bandMph = Mth.lerp(curve, stageFloor, stageCeiling);
        DynamicStormState liveState = state == null ? DynamicStormController.stateFor(storm) : state;
        // The other reading is the air mass itself, from the same CAPE and deep-layer shear the outlook
        // is built from. It never looks at the rung, so a loaded air mass can carry a stage-1 tornado to
        // a violent wind and a marginal one can hold a stage-5 storm down. Held for ten seconds at a time
        // per storm, because a biome's air does not change between two retargets.
        double potentialMph = liveState.environmentPotentialMph(storm, level, gameTime);
        double airWeight = 1.0D - Mth.clamp(Config.WINDFIELD_BAND_WEIGHT.get(), 0.0D, 1.0D);
        double base = potentialMph <= 0.0D
                ? bandMph
                : bandMph + (potentialMph - bandMph) * airWeight;
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
        // Wide tornadoes are not weaker storms - the widest tornado ever observed cracked an F5.
        // A large damage path now rewards wind (the storm's momentum is spread over a wider
        // inflow sleeve) up to +45% at the configured maximum width, instead of quietly taxing
        // it by up to -14%. The grown RMW already spreads the profile, so this widens the
        // region of violent wind rather than inventing force.
        double widthRatio = liveState.damagePathWidth() / Math.max(1.0D, Config.effectiveMaxDamageWidthBlocks());
        double widthBoost = Mth.clamp(1.0D + widthRatio * 0.45D, 1.0D, 1.45D);
        double multiVortexBoost = liveState.multiVortexVisualActive()
                ? 1.03D + Math.min(0.12D, liveState.subVortices().size() * 0.035D)
                : 1.0D;
        boolean drillBit = liveState.targetFunnelMorphology() == FunnelMorphology.DRILL_BIT || liveState.funnelMorphology() == FunnelMorphology.DRILL_BIT;
        double morphologyBoost = drillBit ? 1.08D : 1.0D;
        // Gusts no longer live here: the estimate is the storm's SUSTAINED wind, and the gust
        // event train rides on top at read time (see TornadoGustModel / maxWindMph). Gates that
        // must not flap with gusts read the sustained value directly.
        double target = base * structureBoost * environmentalBoost * lifecycleBoost * widthBoost * multiVortexBoost * morphologyBoost;
        // The multiplicative stack above could legally park a healthy stage-5 storm near its
        // stage-3 floor: the label said violent while the probe read dead air. A storm that is
        // not actually dying now holds a hard fraction of its stage base; only a storm that has
        // peaked AND is draining intensity may sink toward the old decayed value.
        boolean healthy = !storm.hasStormPeaked && !liveState.isAbsorbedOrDecaying();
        double healthFloor = base * (healthy ? 0.78D : lerp(weakeningPressure, 0.78D, 0.55D));
        target = Math.max(target, healthFloor);
        if (storm.attrib_waterSpout) {
            target = Math.min(target, Math.max(rawBandCeilingMph(1), stageCeiling * 1.08D));
        }
        if (DynamicStormController.isNativeCyclone(storm)) {
            target = Mth.clamp(target * 0.84D, 45.0D, Math.max(90.0D, stageCeiling * 1.08D));
        }

        // The window the wind may move in is the storm's room, not its rung's band. The top is whichever
        // reaches higher - the air mass or the band - so the environment can carry a storm past its stage
        // while the band still guarantees a stage-5 storm is not read as calm, and the only absolute cap
        // left is the configured ceiling.
        //
        // The band's own floor is deliberately gone from the bottom of that window. It was a floor on the
        // WIND, which is why a formed tornado could not read below its class no matter how marginal the air
        // it was in. What holds the number up now is the health floor above (the storm's own base, which is
        // mostly the air mass) and the grounded EF0 floor in smoothLiveWind, and both of those are about a
        // funnel that is actually on the ground.
        double roomMph = potentialMph > 0.0D ? Math.max(bandMph, potentialMph) : stageCeiling;
        double upperBound = Math.min(Config.WINDFIELD_ENVIRONMENT_CEILING_MPH.get(),
                Math.max(stageCeiling, roomMph * 1.45D * (drillBit ? 1.06D : 1.0D)));
        // The one place the strength dial is applied to a dynamic storm's wind. A setting of half makes the
        // whole number half, because it multiplies the answer instead of one of its terms.
        return Config.windMph(Mth.clamp(target, 0.0D, upperBound));
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

    /**
     * The fastest wind the air around this storm can support, from the profile the outlook already
     * computes for the same position.
     *
     * <p>Calibrated against the two things that decide how hard a tornado can blow. CAPE sets the
     * strongest updraft the air can build - the theoretical maximum is {@code sqrt(2 * CAPE)} in m/s - and
     * deep-layer shear sets what the vortex can do with it: shear is what organises the updraft and tilts
     * it over, which is why a marginal-CAPE day with 30 m/s of shear can still make a violent tornado and
     * a 3000 CAPE day with no shear makes nothing. So the potential is the updraft speed with a shear
     * multiple on it: about 100 mph for a weak setup (CAPE 800, shear 10), 240 for a strong one (CAPE 2500,
     * shear 20) and the configured ceiling for a loaded one (CAPE 4000, shear 28).
     *
     * <p>Returns 0 when there is no profile to read - which is what the client and any offline caller gets
     * - so the estimate falls back to the band alone rather than to a zero wind.
     */
    public static double environmentPotentialMph(StormObject storm, Level level) {
        if (storm == null || !(level instanceof ServerLevel server)) {
            return 0.0D;
        }
        try {
            Vec3 center = DynamicStormController.tornadoCirculationCenter(storm);
            ThermodynamicProfile profile =
                    ThermodynamicProfileService.compute(server, BlockPos.containing(center));
            return environmentPotentialMph(profile);
        } catch (RuntimeException ignored) {
            return 0.0D;
        }
    }

    /** The profile-only half of the above, so the rule can be read and checked without a level. */
    public static double environmentPotentialMph(ThermodynamicProfile profile) {
        if (profile == null) {
            return 0.0D;
        }
        double cape = Math.max(0.0D, profile.capeJkg());
        double shear = Math.max(0.0D, profile.deepLayerShearMps());
        if (cape <= 0.0D) {
            return 0.0D;
        }
        double updraftMps = Math.sqrt(2.0D * cape);
        double shearMultiple = SHEAR_MULTIPLE_BASE + shear * SHEAR_MULTIPLE_PER_MPS;
        double mph = updraftMps * shearMultiple * MPS_TO_MPH;
        return Mth.clamp(mph, 0.0D, Config.WINDFIELD_ENVIRONMENT_CEILING_MPH.get());
    }

    /**
     * The tornado stage whose own band a given sustained wind has reached - the inverse of the band
     * ladder, compared against each band's floor, which is the previous band's ceiling.
     *
     * <p>Used by the wind-driven stage: a storm blowing harder than its rung's band is wearing the wrong
     * label, and this is the one it has earned. Anything under the forming band is still FORMING, which is
     * also the answer for a wind too low to have a rating at all.
     *
     * <p>The thresholds carry the strength dial, exactly as the wind being judged does. They have to: the
     * setting scales how hard a rung blows, and a wind of half strength compared against a raw ladder would
     * no longer reach any rung above its own, so turning the dial down would quietly decide which stages
     * storms wear - which is the one thing the setting promises not to touch.
     */
    public static int earnedTornadoStage(double mph) {
        int earned = StormObject.STATE_FORMING;
        for (int rung = 1; rung < BAND_RUNGS.length; rung++) {
            if (mph >= Config.windMph(rawBandCeilingMph(rung - 1))) {
                earned = BAND_RUNGS[rung];
            }
        }
        return earned;
    }

    /**
     * The raw wind the stage below is worth: the floor the live wind is ramped up from, and the bottom of the
     * range the wind is read as a fraction of the way up.
     *
     * <p>Asked of the same ladder as the ceiling - a rung's floor is the band below it - so the two cannot
     * drift apart. Under the bottom rung there is nothing to inherit, and the ladder ends on the flat flotsam
     * floor instead; below even that, a storm that is not a circulation at all has no floor.
     */
    private static double rawStageFloorWindMph(StormObject storm) {
        if (storm == null) {
            return 0.0D;
        }
        int rung = rungOf(storm.levelCurIntensityStage);
        if (rung < 0 && !storm.attrib_waterSpout) {
            return 0.0D;
        }
        if (rung <= 0) {
            return Math.max(FLOTSAM_FLOOR_MPH, rawBandCeilingMph(0) * 0.76D);
        }
        return rawBandCeilingMph(rung - 1);
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
