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
        float loweringDepth = wallCloudStrength * Config.SUPERCELL_STRUCTURE_WALL_CLOUD_MAX_LOWERING_BLOCKS.get();
        state.setSupercellStructure(structure, mesocyclone, inflow, rfd, ffd, hailCore, wallCloudStrength, loweringDepth, wallCloudRotation, gameTime);
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
        double rainFreeBaseY = Mth.clamp(34.0D + radius * 0.045D, 32.0D, 54.0D);
        double wallCloudDiameter = Mth.clamp(46.0D + radius * 0.52D, 46.0D, Config.SUPERCELL_STRUCTURE_WALL_CLOUD_MAX_DIAMETER_BLOCKS.get());
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

    public static SupercellMode modeFor(StormObject storm, DynamicStormState state) {
        float ffd = state == null ? 0.0F : state.ffdStrength();
        float rfd = state == null ? 0.0F : state.rfdStrength();
        float hail = state == null ? 0.0F : state.hailCoreStrength();
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
        if (storm == null) {
            return 120.0D;
        }
        double stage = Math.max(0, storm.levelCurIntensityStage - StormObject.STATE_THUNDER);
        double width = state == null ? 24.0D : state.damagePathWidth();
        return Mth.clamp(92.0D + stage * 24.0D + width * 0.12D, 92.0D, 280.0D);
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
