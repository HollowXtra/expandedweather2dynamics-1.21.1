package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import weather2.weathersystem.storm.StormObject;

public final class SupercellStructureService {
    private SupercellStructureService() {
    }

    public static void tick(Level level, StormObject storm, DynamicStormState state) {
        if (level == null || storm == null || state == null || state.isAbsorbedOrDecaying() || !isEligible(storm)) {
            if (level != null && state != null) {
                state.clearSupercellStructure(level.getGameTime());
            }
            return;
        }

        long gameTime = level.getGameTime();
        float stageStrength = stageStrength(storm);
        float mergeBoost = recentMergeBoost(state, gameTime);
        float structure = Mth.clamp(stageStrength + mergeBoost, 0.0F, 1.0F);
        float rotationBonus = storm.isTornadoFormingOrGreater() ? 0.26F : 0.0F;
        float hailBonus = storm.levelCurIntensityStage >= StormObject.STATE_HAIL ? 0.18F : 0.0F;
        float windBonus = storm.levelCurIntensityStage >= StormObject.STATE_HIGHWIND ? 0.12F : 0.0F;

        float mesocyclone = Mth.clamp(structure * (0.34F + rotationBonus + hailBonus * 0.45F) + mergeBoost * 0.45F, 0.0F, 1.0F);
        float inflow = Mth.clamp(0.20F + structure * 0.72F + rotationBonus * 0.35F, 0.0F, 1.0F);
        float rfd = Mth.clamp(structure * (0.30F + windBonus + rotationBonus * 0.55F) + mergeBoost * 0.34F, 0.0F, 1.0F);
        float ffd = Mth.clamp(0.18F + structure * 0.72F + hailBonus + (storm.isPrecipitating() ? 0.08F : 0.0F), 0.0F, 1.0F);
        float hailCore = Mth.clamp((storm.levelCurIntensityStage >= StormObject.STATE_HAIL ? 0.34F : 0.0F) + structure * 0.46F + hailBonus, 0.0F, 1.0F);
        float wallCloudStrength = wallCloudStrength(storm, structure, mesocyclone, inflow, rfd, hailCore);
        float wallCloudRotation = wallCloudStrength <= 0.0F ? 0.0F : Mth.clamp(mesocyclone * 0.72F + inflow * 0.16F + rfd * 0.18F + rotationBonus * 0.70F, 0.0F, 1.0F);
        // The lowering and the structure it hangs from scale together, so a scaled-up supercell keeps
        // its proportions instead of keeping a small storm's wall cloud under a wide anvil.
        float structureScale = Config.SUPERCELL_STRUCTURE_SCALE.get().floatValue();
        float loweringDepth = wallCloudStrength * Config.SUPERCELL_STRUCTURE_WALL_CLOUD_MAX_LOWERING_BLOCKS.get() * structureScale;
        state.setSupercellStructure(structure, mesocyclone, inflow, rfd, ffd, hailCore, wallCloudStrength, loweringDepth, wallCloudRotation, gameTime);
        tickPrecipitationCharacter(storm, state, gameTime);
        tickVirga(storm, state, gameTime);
    }

    /**
     * Precipitation character of a storm: 0 = bone-dry low-precipitation supercell, 0.5 = classic,
     * 1 = torrential high-precipitation storm that rains its tornado into invisibility.
     *
     * Real supercells differ enormously in rainfall, and the difference is not just storm strength:
     * it comes from how much moisture a storm actually has to work with plus its own history. So the
     * character is driven by Weather2's per-storm water level (levelWater, accumulated from biome
     * humidity and burned down as the storm precipitates) blended with a stable per-storm draw, and
     * it is allowed to drift slowly so a storm keeps a recognisable type while still moistening up or
     * drying out as it moves. Scored on demand and cached in the synced state, so both sides agree.
     */
    public static float precipitationCharacter(StormObject storm, DynamicStormState state) {
        if (!Config.SUPERCELL_PRECIPITATION_ENABLED.get() || storm == null || state == null) {
            return 0.5F;
        }
        if (state.precipitationCharacter() < 0.0F) {
            state.setPrecipitationCharacter(environmentalCharacter(storm, state));
        }
        return state.precipitationCharacter();
    }

    /**
     * How much of this storm's precipitation evaporates before it lands: 0 = everything reaches the
     * ground, 1 = the shafts die just below the cloud base as virga.
     *
     * <p>Two independent things cause virga, and a real storm usually has both: not enough
     * precipitation to begin with (a starved storm, which is what the precipitation character
     * already measures) and a dry layer of air under the cloud base that evaporates whatever falls
     * into it. Weather2's per-storm water level is accumulated from biome humidity, so it is the
     * closest thing the mod has to the moisture of the air below the cloud. Both sides score this
     * the same way from synced storm data, so the shafts a client draws are the shafts the server
     * believes in - which is what lets virga also stop the ground from flooding.
     */
    public static float virgaStrength(StormObject storm, DynamicStormState state) {
        if (!Config.VIRGA_ENABLED.get() || storm == null || state == null) {
            return 0.0F;
        }
        if (state.virgaStrength() < 0.0F) {
            state.setVirgaStrength(environmentalVirga(storm, state));
        }
        return state.virgaStrength();
    }

    /**
     * What the storm's airmass currently supports. Hail and established rainfall are nudged the
     * other way: hail is large enough to survive the fall, and a storm already putting rain down is
     * not evaporating all of it.
     */
    private static float environmentalVirga(StormObject storm, DynamicStormState state) {
        float character = precipitationCharacter(storm, state);
        float water = storm.levelWaterStartRaining <= 0
                ? 0.5F
                : Mth.clamp(storm.levelWater / (float) storm.levelWaterStartRaining, 0.0F, 1.0F);
        float starved = 1.0F - Mth.clamp(character, 0.0F, 1.0F);
        float dryAirmass = 1.0F - water;
        float draw = unitHash(storm.ID, 0x51ED270BL);
        float strength = 0.60F * (float) Math.pow(starved, 0.85D) + 0.34F * dryAirmass;
        // Some storms are simply the ones that never reach the ground; others in the same airmass
        // soak it. The draw is per-storm and stable, so a storm keeps its character.
        strength += (draw - 0.5F) * 0.18F;
        if (storm.isPrecipitating()) {
            strength -= 0.14F;
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_HAIL) {
            strength -= 0.06F;
        }
        float scale = Config.VIRGA_STRENGTH_SCALE.get().floatValue();
        return Mth.clamp(strength * scale, 0.0F, 1.0F);
    }

    /** Eases the stored virga strength toward what the airmass currently supports. */
    private static void tickVirga(StormObject storm, DynamicStormState state, long gameTime) {
        if (!Config.VIRGA_ENABLED.get() || state == null) {
            return;
        }
        float target = environmentalVirga(storm, state);
        float current = state.virgaStrength();
        if (current < 0.0F) {
            state.setVirgaStrength(target);
            return;
        }
        state.setVirgaStrength(Mth.lerp(Config.SUPERCELL_PRECIPITATION_DRIFT_PER_TICK.get().floatValue(), current, target));
    }

    public static boolean isLowPrecipitation(StormObject storm, DynamicStormState state) {
        return Config.SUPERCELL_PRECIPITATION_ENABLED.get()
                && precipitationCharacter(storm, state) < lowPrecipitationThreshold();
    }

    public static boolean isHighPrecipitation(StormObject storm, DynamicStormState state) {
        return Config.SUPERCELL_PRECIPITATION_ENABLED.get()
                && precipitationCharacter(storm, state) > highPrecipitationThreshold();
    }

    public static float lowPrecipitationThreshold() {
        return Config.SUPERCELL_PRECIPITATION_LP_THRESHOLD.get().floatValue();
    }

    public static float highPrecipitationThreshold() {
        return Config.SUPERCELL_PRECIPITATION_HP_THRESHOLD.get().floatValue();
    }

    /** Eases the stored character toward what the storm's airmass and history currently support. */
    private static void tickPrecipitationCharacter(StormObject storm, DynamicStormState state, long gameTime) {
        if (!Config.SUPERCELL_PRECIPITATION_ENABLED.get()) {
            return;
        }
        float current = state.precipitationCharacter();
        float target = environmentalCharacter(storm, state);
        if (current < 0.0F) {
            state.setPrecipitationCharacter(target);
            return;
        }
        float drift = Config.SUPERCELL_PRECIPITATION_DRIFT_PER_TICK.get().floatValue();
        state.setPrecipitationCharacter(Mth.lerp(drift, current, target));
    }

    /**
     * What a storm's environment and history support: its own water level (biome humidity driven)
     * blended with a stable per-storm draw, nudged wetter while it rains and after a merge.
     */
    private static float environmentalCharacter(StormObject storm, DynamicStormState state) {
        float water = storm.levelWaterStartRaining <= 0
                ? 0.5F
                : Mth.clamp(storm.levelWater / (float) storm.levelWaterStartRaining, 0.0F, 1.0F);
        float variability = Mth.clamp(Config.SUPERCELL_PRECIPITATION_VARIABILITY.get().floatValue(), 0.0F, 1.0F);
        float draw = unitHash(storm.ID, 0x5DEECE66DL);
        // 0 variability = every storm in the airmass rains alike; 1 = a dry LP and a rain-wrapped HP
        // can sit side by side, which is what chasing a supercell outbreak actually looks like.
        float character = Mth.lerp(variability, water, 0.65F * draw + 0.35F * water);
        if (storm.isPrecipitating()) {
            character += 0.06F;
        }
        if (state != null && storm.manager != null && storm.manager.getWorld() != null) {
            long mergeTick = state.lastSupercellMergeTick();
            long age = storm.manager.getWorld().getGameTime() - mergeTick;
            if (mergeTick > 0L && age >= 0L && age < 20L * 600L) {
                character += 0.05F; // a fresh merge inherits the partner's moisture budget
            }
        }
        return Mth.clamp(character, 0.0F, 1.0F);
    }

    private static float unitHash(long id, long salt) {
        long hash = id * 0x9E3779B97F4A7C15L + salt;
        hash ^= (hash >>> 33);
        hash *= 0xFF51AFD7ED558CCDL;
        hash ^= (hash >>> 29);
        hash *= 0xC4CEB9FE1A85EC53L;
        hash ^= (hash >>> 32);
        return Math.floorMod(hash, 10000L) / 10000.0F;
    }

    public static boolean isEligible(StormObject storm) {
        if (!Config.SUPERCELL_STRUCTURE_ENABLED.get() || storm == null || storm.pos == null || storm.isDead) {
            return false;
        }
        if (storm.stormType != StormObject.TYPE_LAND || storm.attrib_waterSpout || DynamicStormController.isNativeCyclone(storm)) {
            return false;
        }
        if (AddonWeatherEventService.isSquallLineHelperStorm(storm) || AddonWeatherEventService.isHurricaneHelperStorm(storm)) {
            return false;
        }
        return storm.isTornadoFormingOrGreater() || storm.levelCurIntensityStage >= StormObject.STATE_HAIL;
    }

    /**
     * The storm's precipitation field, built from the same anchors as everything else it draws.
     *
     * <p>One source of truth on purpose: the notch this field scours out is the notch the cloud
     * particles are spawned around, the band it wraps is where the hook's cloud sits, and the core is
     * over the forward flank. If the rain were shaped anywhere else the parts would drift apart.
     *
     * <p>Derived rather than synced. Everything it needs is already on both sides - the storm's frame,
     * its stage, and the synced structure and precipitation strengths - so a client can rebuild it
     * exactly and nothing has to be sent.
     */
    public static PrecipitationField.Geometry precipitationGeometry(StormObject storm, DynamicStormState state) {
        if (storm == null || storm.pos == null || !isEligible(storm) || !Config.SUPERCELL_PRECIPITATION_FIELD_ENABLED.get()) {
            // A storm that is not a supercell still rains, and its rain is a plain shield around it.
            return ordinaryStormField(storm == null ? 120.0D : structureRadius(storm, state));
        }
        StructureSample sample = sample(storm, state);
        float character = precipitationCharacter(storm, state);
        Vec3 mesocyclone = sample.mesocyclone();
        Vec3 forwardFlank = sample.forwardFlank();
        Vec3 notch = sample.hookEchoNotch();
        Vec3 wrap = sample.rainWrapCurtain();
        Vec3 rearFlank = sample.rearFlank();
        // Local coordinates are what the field is written in: forward and right from the storm's centre.
        double[] meso = local(sample, storm.pos, mesocyclone);
        double[] core = local(sample, storm.pos, forwardFlank);
        double[] notchLocal = local(sample, storm.pos, notch);
        double[] wrapLocal = local(sample, storm.pos, wrap);
        double[] rear = local(sample, storm.pos, rearFlank);

        float ffd = state == null ? 0.5F : state.ffdStrength();
        float rfd = state == null ? 0.3F : state.rfdStrength();
        float hail = state == null ? 0.0F : state.hailCoreStrength();
        boolean precipitating = storm.isPrecipitating() || storm.attrib_precipitation;
        float wet = Mth.clamp(character, 0.0F, 1.0F);
        // How much rain there is at all. A dry storm still has hail and a thin shield; the moisture it
        // has is the ceiling on everything below.
        double coreStrength = Mth.clamp(0.18F + wet * 0.92F + ffd * 0.30F + (precipitating ? 0.08F : 0.0F), 0.0F, 1.0F);
        // The wrapped rain is the rear-flank downdraft's, so it needs both moisture and an RFD to carry it.
        double hookStrength = Mth.clamp((0.18D + 0.92D * wet) * (0.45D + 0.60D * rfd) * Config.SUPERCELL_PRECIPITATION_HOOK_SCALE.get(), 0.0D, 1.0D);
        // The clear slot is crispest in a classic storm and narrower in a wrapped one, where the rain
        // is closing in on the vortex from more directions.
        double notchStrength = Mth.clamp((0.94D - 0.30D * wet) * Config.SUPERCELL_PRECIPITATION_NOTCH_CLARITY.get(), 0.0D, 1.0D);
        double rearStrength = Mth.clamp((0.18D + 0.62D * wet) * (0.35D + 0.75D * rfd), 0.0D, 1.0D);
        double stratiform = Mth.clamp(0.10D + 0.55D * wet - 0.25D * hail, 0.0D, 1.0D);

        return new PrecipitationField.Geometry(
                sample.radius(),
                meso[0], meso[1],
                core[0], core[1],
                notchLocal[0], notchLocal[1],
                wrapLocal[0], wrapLocal[1],
                rear[0], rear[1],
                coreStrength, hookStrength, notchStrength, rearStrength, stratiform);
    }

    /**
     * How rain-wrapped a vortex is, 0 = in the open and 1 = enclosed: the share of a ring around the
     * tornado that has rain in it.
     *
     * <p>Asked of the geometry rather than of the storm's average character, because that distinction
     * is the difference between a storm that is wet somewhere and a tornado you cannot see. A storm can
     * be pouring rain out of its forward flank and still leave its vortex in clear air, and an otherwise
     * moderate one can bury it.
     */
    public static double rainWrapShare(StormObject storm, DynamicStormState state, double radiusBlocks) {
        if (storm == null || storm.pos == null || !isEligible(storm)) {
            return 0.0D;
        }
        PrecipitationField.Geometry field = precipitationGeometry(storm, state);
        StructureSample sample = sample(storm, state);
        // The funnel sits in the hook, just upshear of the mesocyclone, which is where a tornado has to
        // be for the rain to wrap it at all.
        Vec3 vortex = sample.mesocyclone()
                .add(sample.forward().scale(sample.radius() * 0.14D))
                .add(sample.right().scale(-sample.radius() * 0.14D));
        double[] local = local(sample, storm.pos, vortex);
        return PrecipitationField.wrappedShare(field, local[0], local[1], Math.max(12.0D, radiusBlocks), 16);
    }

    /** The rain of a storm with no supercell structure: a shield around it, densest at its core. */
    private static PrecipitationField.Geometry ordinaryStormField(double radius) {
        return new PrecipitationField.Geometry(radius, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D,
                0.85D, 0.0D, 0.0D, 0.30D, 0.25D);
    }

    /** A point in the storm's own frame: distance along its motion and to its right. */
    private static double[] local(StructureSample sample, Vec3 center, Vec3 point) {
        double dx = point.x - center.x;
        double dz = point.z - center.z;
        return new double[] {
                dx * sample.forward().x + dz * sample.forward().z,
                dx * sample.right().x + dz * sample.right().z
        };
    }

    public static StructureSample sample(StormObject storm, DynamicStormState state) {
        Vec3 center = storm == null || storm.pos == null ? Vec3.ZERO : storm.pos;
        Vec3 forward = motionDirection(storm);
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x).scale(DynamicStormController.visualRotationSign(storm)).normalize();
        double radius = structureRadius(storm, state);
        Vec3 mesocyclone = center.add(forward.scale(-radius * 0.36D)).add(right.scale(radius * 0.48D));
        Vec3 updraft = center.add(forward.scale(-radius * 0.16D)).add(right.scale(radius * 0.26D));
        Vec3 forwardFlank = center.add(forward.scale(radius * 0.46D)).add(right.scale(-radius * 0.16D));
        Vec3 rearFlank = center.add(forward.scale(-radius * 0.50D)).add(right.scale(radius * 0.72D));
        Vec3 inflowSource = center.add(forward.scale(radius * 0.82D)).add(right.scale(radius * 0.96D));
        Vec3 wallCloudCenter = mesocyclone.add(forward.scale(-radius * 0.08D)).add(right.scale(radius * 0.06D));
        // The rain-free base follows the storm's own size, then the cloud-base offset lifts or lowers
        // it further - a high-based supercell keeps its wall cloud and anvil proportional to a taller
        // column rather than flattening them onto the deck.
        double rainFreeBaseY = Math.max(20.0D, Mth.clamp(34.0D + radius * 0.045D, 32.0D, 54.0D)
                + Config.SUPERCELL_STRUCTURE_CLOUD_BASE_OFFSET_BLOCKS.get());
        double wallCloudCap = Config.SUPERCELL_STRUCTURE_WALL_CLOUD_MAX_DIAMETER_BLOCKS.get()
                * Config.SUPERCELL_STRUCTURE_SCALE.get().doubleValue();
        double wallCloudDiameter = Mth.clamp(46.0D + radius * 0.52D, 46.0D, wallCloudCap);
        Vec3 wallCloudBase = wallCloudCenter.add(0.0D, rainFreeBaseY, 0.0D);
        Vec3 clearSlotCenter = rearFlank.add(forward.scale(radius * 0.18D)).add(right.scale(radius * 0.30D));
        Vec3 anvilCore = updraft.add(forward.scale(radius * 0.68D)).add(0.0D, radius * 0.36D, 0.0D);
        Vec3 backShearedAnvil = updraft.add(forward.scale(-radius * 0.74D)).add(right.scale(radius * 0.18D)).add(0.0D, radius * 0.32D, 0.0D);
        Vec3 flankingLineStart = center.add(forward.scale(-radius * 1.32D)).add(right.scale(radius * 0.92D));
        Vec3 flankingLineEnd = mesocyclone.add(forward.scale(-radius * 0.20D)).add(right.scale(radius * 0.10D));
        Vec3 gustFrontLeft = rearFlank.add(forward.scale(radius * 0.20D)).add(right.scale(-radius * 0.84D));
        Vec3 gustFrontRight = rearFlank.add(forward.scale(radius * 0.56D)).add(right.scale(radius * 0.60D));
        SupercellMode mode = modeFor(storm, state);
        double hpBias = mode == SupercellMode.HP ? 1.0D : mode == SupercellMode.CLASSIC ? 0.42D : 0.12D;
        double lpBias = mode == SupercellMode.LP ? 1.0D : 0.0D;
        Vec3 hookEchoNotch = mesocyclone
                .add(forward.scale(-radius * (0.14D + hpBias * 0.08D)))
                .add(right.scale(-radius * (0.16D + hpBias * 0.16D)));
        Vec3 rainWrapCurtain = mesocyclone
                .add(forward.scale(radius * (0.08D + hpBias * 0.24D)))
                .add(right.scale(-radius * (0.14D + hpBias * 0.34D)));
        Vec3 hailShaft = forwardFlank
                .add(forward.scale(radius * (0.10D + lpBias * 0.10D)))
                .add(right.scale(-radius * (0.24D + lpBias * 0.18D)));
        Vec3 inflowTail = inflowSource.lerp(wallCloudCenter, 0.46D)
                .add(right.scale(radius * 0.10D));
        Vec3 rainFreeBase = updraft
                .add(forward.scale(-radius * 0.06D))
                .add(right.scale(radius * 0.10D));
        return new StructureSample(forward, right, updraft, mesocyclone, forwardFlank, rearFlank, inflowSource, anvilCore, backShearedAnvil, flankingLineStart, flankingLineEnd, gustFrontLeft, gustFrontRight, wallCloudCenter, wallCloudBase, clearSlotCenter, hookEchoNotch, rainWrapCurtain, hailShaft, inflowTail, rainFreeBase, mode, radius, wallCloudDiameter, rainFreeBaseY);
    }

    /**
     * The storm's mode now follows its precipitation character - that is what LP/HP actually means
     * on radar and in the sky - with the old structure-based hints only breaking ties in the classic
     * band (a hail-heavy storm with no rain shield reads LP; a full forward-flank rain core reads HP).
     */
    public static SupercellMode modeFor(StormObject storm, DynamicStormState state) {
        float ffd = state == null ? 0.0F : state.ffdStrength();
        float rfd = state == null ? 0.0F : state.rfdStrength();
        float hail = state == null ? 0.0F : state.hailCoreStrength();
        if (Config.SUPERCELL_PRECIPITATION_ENABLED.get() && storm != null && state != null) {
            float character = state.precipitationCharacter() < 0.0F
                    ? environmentalCharacter(storm, state)
                    : state.precipitationCharacter();
            if (character < lowPrecipitationThreshold()) {
                return SupercellMode.LP;
            }
            if (character > highPrecipitationThreshold()) {
                return SupercellMode.HP;
            }
            if (hail >= 0.50F && ffd <= 0.52F && !storm.isPrecipitating()) {
                return SupercellMode.LP;
            }
            if ((storm.isPrecipitating() && ffd >= 0.62F && rfd >= 0.38F) || ffd >= 0.78F) {
                return SupercellMode.HP;
            }
            return SupercellMode.CLASSIC;
        }
        boolean precipitating = storm != null && storm.isPrecipitating();
        boolean hailStage = storm != null && storm.levelCurIntensityStage >= StormObject.STATE_HAIL;
        if ((precipitating && ffd >= 0.62F && rfd >= 0.38F) || ffd >= 0.78F) {
            return SupercellMode.HP;
        }
        if ((hailStage || hail >= 0.44F) && ffd <= 0.60F) {
            return SupercellMode.LP;
        }
        if (!precipitating && hail >= 0.36F) {
            return SupercellMode.LP;
        }
        return SupercellMode.CLASSIC;
    }

    public static TornadoWindField.WindSample sampleWind(StormObject storm, Level level, Vec3 position) {
        if (!Config.SUPERCELL_STRUCTURE_ENABLED.get() || !Config.SUPERCELL_STRUCTURE_WINDFIELD_INFLUENCE.get() || storm == null || level == null || position == null || !isEligible(storm)) {
            return TornadoWindField.WindSample.CALM;
        }
        DynamicStormState state = DynamicStormController.stateFor(storm);
        if (state.supercellStructureStrength() <= 0.05F) {
            return TornadoWindField.WindSample.CALM;
        }

        StructureSample structure = sample(storm, state);
        Vec3 mphVector = Vec3.ZERO;
        double strongest = 0.0D;

        Vec3 inflowDirection = horizontalDirection(structure.inflowSource(), structure.mesocyclone());
        double inflowWidth = 42.0D + structure.radius() * 0.10D;
        double inflowDistance = distanceToSegment2d(position, structure.inflowSource(), structure.mesocyclone());
        double inflowFalloff = Mth.clamp(1.0D - inflowDistance / inflowWidth, 0.0D, 1.0D);
        Vec3 inflow = inflowDirection.scale((18.0D + state.inflowStrength() * 48.0D) * inflowFalloff);
        mphVector = mphVector.add(inflow);
        strongest = Math.max(strongest, inflow.length());

        Vec3 rfdDirection = structure.forward().scale(0.55D).add(structure.right().scale(0.78D)).normalize();
        double rfdDistance = distanceToSegment2d(position, structure.gustFrontLeft(), structure.gustFrontRight());
        double rfdFalloff = Mth.clamp(1.0D - rfdDistance / (34.0D + structure.radius() * 0.08D), 0.0D, 1.0D);
        Vec3 rfd = rfdDirection.scale((14.0D + state.rfdStrength() * 55.0D) * rfdFalloff);
        mphVector = mphVector.add(rfd);
        strongest = Math.max(strongest, rfd.length());

        Vec3 ffdDirection = structure.forward().scale(0.82D).add(structure.right().scale(-0.28D)).normalize();
        double ffdDistance = horizontalDistance(position, structure.forwardFlank());
        double ffdFalloff = Mth.clamp(1.0D - ffdDistance / (structure.radius() * 0.58D), 0.0D, 1.0D);
        Vec3 ffd = ffdDirection.scale((10.0D + state.ffdStrength() * 38.0D) * ffdFalloff);
        mphVector = mphVector.add(ffd);
        strongest = Math.max(strongest, ffd.length());

        double hookDistance = horizontalDistance(position, structure.hookEchoNotch());
        double hookFalloff = Mth.clamp(1.0D - hookDistance / (24.0D + structure.radius() * 0.11D), 0.0D, 1.0D);
        Vec3 hookDirection = structure.right().scale(-0.78D).add(structure.forward().scale(-0.28D)).normalize();
        Vec3 hook = hookDirection.scale((10.0D + state.mesocycloneStrength() * 42.0D + state.wallCloudRotationStrength() * 26.0D) * hookFalloff);
        mphVector = mphVector.add(hook);
        strongest = Math.max(strongest, hook.length());

        if (structure.mode() == SupercellMode.HP) {
            double wrapDistance = distanceToSegment2d(position, structure.hookEchoNotch(), structure.rainWrapCurtain());
            double wrapFalloff = Mth.clamp(1.0D - wrapDistance / (34.0D + structure.radius() * 0.16D), 0.0D, 1.0D);
            Vec3 wrapDirection = structure.forward().scale(0.52D).add(structure.right().scale(-0.42D)).normalize();
            Vec3 wrap = wrapDirection.scale((16.0D + state.ffdStrength() * 34.0D + state.rfdStrength() * 24.0D) * wrapFalloff);
            mphVector = mphVector.add(wrap);
            strongest = Math.max(strongest, wrap.length());
        }

        return new TornadoWindField.WindSample(mphVector, mphVector.length(), strongest, 0.0D, structure.radius(), 0.0D);
    }

    private static float stageStrength(StormObject storm) {
        float stage = Mth.clamp((storm.levelCurIntensityStage - StormObject.STATE_THUNDER) / (float) Math.max(1, StormObject.STATE_STAGE5 - StormObject.STATE_THUNDER), 0.0F, 1.0F);
        float progress = Mth.clamp(storm.levelCurStagesIntensity, 0.0F, 1.0F) * 0.12F;
        float base = storm.isPrecipitating() ? 0.28F : 0.18F;
        return Mth.clamp(base + stage * 0.66F + progress, 0.0F, 1.0F);
    }

    private static float recentMergeBoost(DynamicStormState state, long gameTime) {
        long age = gameTime - state.lastSupercellMergeTick();
        if (age < 0L || age > 20L * 180L) {
            return 0.0F;
        }
        return Mth.clamp(1.0F - age / (20.0F * 180.0F), 0.0F, 1.0F) * 0.24F;
    }

    private static float wallCloudStrength(StormObject storm, float structure, float mesocyclone, float inflow, float rfd, float hailCore) {
        if (!Config.SUPERCELL_STRUCTURE_WALL_CLOUD_LOWERING.get()) {
            return 0.0F;
        }
        float minimumMesocyclone = Config.SUPERCELL_STRUCTURE_WALL_CLOUD_MIN_MESOCYCLONE_STRENGTH.get().floatValue();
        if (mesocyclone < minimumMesocyclone || structure < 0.22F) {
            return 0.0F;
        }
        float mesoExcess = Mth.clamp((mesocyclone - minimumMesocyclone) / Math.max(0.01F, 1.0F - minimumMesocyclone), 0.0F, 1.0F);
        float rainFreeUpdraft = Mth.clamp(inflow * 0.46F + rfd * 0.26F + hailCore * 0.12F + (storm.isPrecipitating() ? 0.10F : 0.0F), 0.0F, 1.0F);
        float tornadicBonus = storm.isTornadoFormingOrGreater() ? 0.16F : 0.0F;
        return Mth.clamp(mesoExcess * 0.58F + rainFreeUpdraft * 0.34F + tornadicBonus, 0.0F, 1.0F);
    }

    private static double structureRadius(StormObject storm, DynamicStormState state) {
        double scale = Config.SUPERCELL_STRUCTURE_SCALE.get().doubleValue();
        if (storm == null) {
            return 120.0D * scale;
        }
        double stage = Math.max(0, storm.levelCurIntensityStage - StormObject.STATE_THUNDER);
        double width = state == null ? 24.0D : state.damagePathWidth();
        return Mth.clamp(92.0D + stage * 24.0D + width * 0.12D, 92.0D, 280.0D) * scale;
    }

    private static Vec3 motionDirection(StormObject storm) {
        if (storm != null && storm.motion != null && storm.motion.horizontalDistanceSqr() > 1.0E-5D) {
            return new Vec3(storm.motion.x, 0.0D, storm.motion.z).normalize();
        }
        float angle = storm == null ? 0.0F : storm.getAdjustedAngle();
        double radians = Math.toRadians(angle);
        Vec3 fallback = new Vec3(Math.cos(radians), 0.0D, Math.sin(radians));
        return fallback.horizontalDistanceSqr() < 1.0E-5D ? new Vec3(1.0D, 0.0D, 0.0D) : fallback.normalize();
    }

    private static Vec3 horizontalDirection(Vec3 from, Vec3 to) {
        Vec3 delta = new Vec3(to.x - from.x, 0.0D, to.z - from.z);
        return delta.horizontalDistanceSqr() < 1.0E-5D ? new Vec3(1.0D, 0.0D, 0.0D) : delta.normalize();
    }

    private static double horizontalDistance(Vec3 first, Vec3 second) {
        double dx = first.x - second.x;
        double dz = first.z - second.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double distanceToSegment2d(Vec3 point, Vec3 start, Vec3 end) {
        double sx = end.x - start.x;
        double sz = end.z - start.z;
        double lenSqr = sx * sx + sz * sz;
        if (lenSqr < 1.0E-5D) {
            return horizontalDistance(point, start);
        }
        double t = Mth.clamp(((point.x - start.x) * sx + (point.z - start.z) * sz) / lenSqr, 0.0D, 1.0D);
        double x = start.x + sx * t;
        double z = start.z + sz * t;
        double dx = point.x - x;
        double dz = point.z - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public record StructureSample(
            Vec3 forward,
            Vec3 right,
            Vec3 updraft,
            Vec3 mesocyclone,
            Vec3 forwardFlank,
            Vec3 rearFlank,
            Vec3 inflowSource,
            Vec3 anvilCore,
            Vec3 backShearedAnvil,
            Vec3 flankingLineStart,
            Vec3 flankingLineEnd,
            Vec3 gustFrontLeft,
            Vec3 gustFrontRight,
            Vec3 wallCloudCenter,
            Vec3 wallCloudBase,
            Vec3 clearSlotCenter,
            Vec3 hookEchoNotch,
            Vec3 rainWrapCurtain,
            Vec3 hailShaft,
            Vec3 inflowTail,
            Vec3 rainFreeBase,
            SupercellMode mode,
            double radius,
            double wallCloudDiameter,
            double rainFreeBaseY
    ) {
    }

    public enum SupercellMode {
        CLASSIC,
        HP,
        LP
    }
}
