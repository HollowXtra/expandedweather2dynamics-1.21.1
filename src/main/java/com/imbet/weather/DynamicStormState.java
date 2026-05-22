package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import weather2.weathersystem.storm.StormObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public final class DynamicStormState {
    private final Random random;
    private float wanderAngle;
    private float targetWanderAngle;
    private long nextWanderRetargetTick;
    private float widthMultiplier = 1.0F;
    private float targetWidthMultiplier = 1.0F;
    private float damagePathWidth = 24.0F;
    private float targetDamagePathWidth = 24.0F;
    private float visibleFunnelWidth = 12.0F;
    private float targetVisibleFunnelWidth = 12.0F;
    private float coreRadius = 4.0F;
    private float radiusOfMaxWind = 5.0F;
    private float mergeWidthBoost;
    private long nextWidthRetargetTick;
    private float bendAngle;
    private float targetBendAngle;
    private float bendStrength;
    private float targetBendStrength;
    private float bendPhase;
    private float bendPhaseRate = 0.015F;
    private long nextBendRetargetTick;
    private float visualSpinSpeed = 0.055F;
    private float targetVisualSpinSpeed = 0.055F;
    private long blockDamageTick = -1L;
    private int blockDamageCount;
    private long lastAlertTick = -1_000_000L;
    private StormSubtype subtype = StormSubtype.NONE;
    private boolean rotationAssigned;
    private boolean antiCyclonic;
    private final List<SubVortex> subVortices = new ArrayList<>();
    private final List<TornadoCirculationState> satelliteTornadoes = new ArrayList<>();
    private long nextSubVortexRetargetTick;
    private long nextSatelliteAttemptTick;
    private long nextIntensityEvaluationTick;
    private long lastIntensityChangeTick = -1_000_000L;
    private long lastMergeTick = -1_000_000L;
    private long lastSupercellMergeTick = -1_000_000L;
    private long supercellAbsorbedTick = -1_000_000L;
    private long absorbedByStormId = Long.MIN_VALUE;
    private boolean absorbedBySupercell;
    private boolean recentlyReintensified;
    private boolean cycloidalMarksRecorded;
    private float supercellStructureStrength;
    private float mesocycloneStrength;
    private float inflowStrength;
    private float rfdStrength;
    private float ffdStrength;
    private float hailCoreStrength;
    private float wallCloudStrength;
    private float wallCloudLoweringDepth;
    private float wallCloudRotationStrength;
    private int wallCloudPersistenceTicks;
    private long lastStructureTick = -1_000_000L;
    private long mergeOrbitPartnerId = Long.MIN_VALUE;
    private long mergeOrbitStartTick = -1_000_000L;
    private double mergeOrbitCenterX;
    private double mergeOrbitCenterY;
    private double mergeOrbitCenterZ;
    private float mergeOrbitRadius;
    private float mergeOrbitPhase;
    private float mergeOrbitDirection = 1.0F;

    DynamicStormState(long stormId) {
        this.random = new Random(stormId * 31L + 17L);
    }

    void tickServer(ServerLevel level, StormObject storm, long gameTime) {
        if (gameTime >= nextWanderRetargetTick) {
            retargetWander(gameTime);
        }
        if (gameTime >= nextWidthRetargetTick) {
            retargetWidth(level, storm, gameTime);
        }
        if (gameTime >= nextBendRetargetTick) {
            retargetBend(gameTime);
        }
        mergeWidthBoost *= 0.985F;
        tickVisual(gameTime);
    }

    void tickVisual(long gameTime) {
        bendPhase += bendPhaseRate;
        float rotationDirection = antiCyclonic ? -1.0F : 1.0F;
        for (SubVortex vortex : subVortices) {
            vortex.orbitDegrees = wrapDegrees(vortex.orbitDegrees + vortex.orbitSpeedDegrees * rotationDirection);
            vortex.pulse += vortex.pulseRate;
        }
        for (TornadoCirculationState satellite : satelliteTornadoes) {
            satellite.tickVisual(rotationDirection);
        }
        if (mergeOrbitPartnerId != Long.MIN_VALUE) {
            mergeOrbitPhase = wrapDegrees(mergeOrbitPhase + mergeOrbitDirection * 4.5F);
        }
        expireSatelliteTornadoes(gameTime);
    }

    void expireSatelliteTornadoes(long gameTime) {
        satelliteTornadoes.removeIf(satellite -> satellite.isExpired(gameTime) || satellite.absorbed());
    }

    void retargetWander(long gameTime) {
        int min = Math.min(Config.TORNADO_WANDER_MIN_RETARGET_TICKS.get(), Config.TORNADO_WANDER_MAX_RETARGET_TICKS.get());
        int max = Math.max(Config.TORNADO_WANDER_MIN_RETARGET_TICKS.get(), Config.TORNADO_WANDER_MAX_RETARGET_TICKS.get());
        float range = Config.TORNADO_WANDER_MAX_DEGREES.get().floatValue();
        targetWanderAngle = -range + random.nextFloat() * (range * 2.0F);
        nextWanderRetargetTick = gameTime + nextIntInclusive(min, max);
    }

    void retargetWidth(ServerLevel level, StormObject storm, long gameTime) {
        int minTicks = Math.min(Config.TORNADO_WIDTH_MIN_RETARGET_TICKS.get(), Config.TORNADO_WIDTH_MAX_RETARGET_TICKS.get());
        int maxTicks = Math.max(Config.TORNADO_WIDTH_MIN_RETARGET_TICKS.get(), Config.TORNADO_WIDTH_MAX_RETARGET_TICKS.get());
        float minDamageWidth = (float) Config.effectiveMinDamageWidthBlocks();
        float maxDamageWidth = (float) Config.effectiveMaxDamageWidthBlocks();
        float legacyMin = Math.min(Config.TORNADO_WIDTH_MIN_MULTIPLIER.get().floatValue(), Config.TORNADO_WIDTH_MAX_MULTIPLIER.get().floatValue());
        float legacyMax = Math.max(Config.TORNADO_WIDTH_MIN_MULTIPLIER.get().floatValue(), Config.TORNADO_WIDTH_MAX_MULTIPLIER.get().floatValue());

        if (!Config.TORNADO_REALISTIC_WIDTH_MODEL.get() || storm == null || level == null) {
            targetWidthMultiplier = legacyMin + random.nextFloat() * (legacyMax - legacyMin);
            targetVisibleFunnelWidth = Math.min((float) Config.effectiveMaxVisibleFunnelWidthBlocks(), Math.max(3.0F, DynamicStormController.rawBaseRadiusForWind(storm) * targetWidthMultiplier));
            targetDamagePathWidth = Mth.clamp(targetVisibleFunnelWidth / 0.55F, minDamageWidth, maxDamageWidth);
            nextWidthRetargetTick = gameTime + nextIntInclusive(minTicks, maxTicks);
            return;
        }

        float stage = Mth.clamp((storm.levelCurIntensityStage - StormObject.STATE_FORMING) / 5.0F, 0.0F, 1.0F);
        BlockPos pos = BlockPos.containing(storm.pos);
        float temperature = level.getBiome(pos).value().getBaseTemperature();
        float warmth = Mth.clamp((temperature + 0.15F) / 1.45F, 0.0F, 1.0F);
        float moisture = DynamicStormController.isOverWaterOrOcean(level, storm.pos.x, storm.pos.z) || storm.isPrecipitating() ? 1.0F : 0.45F;
        float terrainPenalty = terrainRoughnessPenalty(level, pos);
        float environment = Mth.clamp(0.55F + warmth * 0.17F + moisture * 0.18F - terrainPenalty, 0.35F, 1.15F);
        float stageCeiling = Mth.lerp((float) Math.pow(stage, 1.2D), minDamageWidth * 2.0F, maxDamageWidth);
        float stageFloor = Mth.lerp(stage, minDamageWidth, Math.min(maxDamageWidth * 0.42F, minDamageWidth + 90.0F));
        float draw = (float) Math.pow(random.nextFloat(), 1.65D);
        float boostedTarget = Mth.lerp(draw, stageFloor, stageCeiling) * environment + mergeWidthBoost;
        targetDamagePathWidth = Mth.clamp(boostedTarget, minDamageWidth, maxDamageWidth);

        float visibleMin = Config.TORNADO_VISIBLE_FUNNEL_FRACTION_MIN.get().floatValue();
        float visibleMax = Config.TORNADO_VISIBLE_FUNNEL_FRACTION_MAX.get().floatValue();
        float visibleFraction = visibleMin + random.nextFloat() * Math.max(0.0F, visibleMax - visibleMin);
        targetVisibleFunnelWidth = Mth.clamp(targetDamagePathWidth * visibleFraction, 3.0F, (float) Config.effectiveMaxVisibleFunnelWidthBlocks());
        targetWidthMultiplier = targetVisibleFunnelWidth / Math.max(3.0F, DynamicStormController.rawBaseRadiusForWind(storm));
        nextWidthRetargetTick = gameTime + nextIntInclusive(minTicks, maxTicks);
    }

    void smooth() {
        wanderAngle += (targetWanderAngle - wanderAngle) * Config.TORNADO_WANDER_SMOOTHING.get().floatValue();
        widthMultiplier += (targetWidthMultiplier - widthMultiplier) * Config.TORNADO_WIDTH_SMOOTHING.get().floatValue();
        damagePathWidth += (targetDamagePathWidth - damagePathWidth) * Config.TORNADO_WIDTH_SMOOTHING.get().floatValue();
        visibleFunnelWidth += (targetVisibleFunnelWidth - visibleFunnelWidth) * Config.TORNADO_WIDTH_SMOOTHING.get().floatValue();
        coreRadius = Math.max(2.0F, damagePathWidth * 0.16F);
        radiusOfMaxWind = Math.max(3.0F, damagePathWidth * 0.28F);
        float bendDelta = wrapDegreesSigned(targetBendAngle - bendAngle);
        bendAngle = wrapDegrees(bendAngle + bendDelta * Config.TORNADO_FUNNEL_BEND_SMOOTHING.get().floatValue());
        bendStrength += (targetBendStrength - bendStrength) * Config.TORNADO_FUNNEL_BEND_SMOOTHING.get().floatValue();
    }

    void updateVisualSpin(double maxWindMph) {
        if (!Config.TORNADO_WIND_SPEED_SPIN_ENABLED.get()) {
            return;
        }
        float min = Math.min(Config.TORNADO_WIND_SPEED_SPIN_MIN.get().floatValue(), Config.TORNADO_WIND_SPEED_SPIN_MAX.get().floatValue());
        float max = Math.max(Config.TORNADO_WIND_SPEED_SPIN_MIN.get().floatValue(), Config.TORNADO_WIND_SPEED_SPIN_MAX.get().floatValue());
        float refMin = Math.min(Config.TORNADO_WIND_SPEED_SPIN_REFERENCE_MIN_MPH.get().floatValue(), Config.TORNADO_WIND_SPEED_SPIN_REFERENCE_MAX_MPH.get().floatValue());
        float refMax = Math.max(Config.TORNADO_WIND_SPEED_SPIN_REFERENCE_MIN_MPH.get().floatValue(), Config.TORNADO_WIND_SPEED_SPIN_REFERENCE_MAX_MPH.get().floatValue());
        float windNorm = refMax <= refMin ? 1.0F : Mth.clamp((float) ((maxWindMph - refMin) / (refMax - refMin)), 0.0F, 1.0F);
        float windCurve = windNorm * windNorm * (3.0F - 2.0F * windNorm);
        targetVisualSpinSpeed = Mth.lerp(windCurve, min, max);
        visualSpinSpeed += (targetVisualSpinSpeed - visualSpinSpeed) * Config.TORNADO_WIND_SPEED_SPIN_SMOOTHING.get().floatValue();
    }

    public float wanderAngle() {
        return wanderAngle;
    }

    public float widthMultiplier() {
        return widthMultiplier;
    }

    public float damagePathWidth() {
        return damagePathWidth;
    }

    public float visibleFunnelWidth() {
        return visibleFunnelWidth;
    }

    public float coreRadius() {
        return coreRadius;
    }

    public float radiusOfMaxWind() {
        return radiusOfMaxWind;
    }

    public float bendAngle() {
        return bendAngle;
    }

    public float bendStrength() {
        return bendStrength;
    }

    public float bendPhase() {
        return bendPhase;
    }

    public float visualSpinSpeed() {
        return visualSpinSpeed;
    }

    public float targetVisualSpinSpeed() {
        return targetVisualSpinSpeed;
    }

    public boolean tryConsumeBlockDamage(long gameTime) {
        if (blockDamageTick != gameTime) {
            blockDamageTick = gameTime;
            blockDamageCount = 0;
        }
        if (blockDamageCount >= Config.DAMAGE_MAX_BLOCKS_PER_STORM_PER_TICK.get()) {
            return false;
        }
        blockDamageCount++;
        return true;
    }

    public long lastAlertTick() {
        return lastAlertTick;
    }

    public void setLastAlertTick(long lastAlertTick) {
        this.lastAlertTick = lastAlertTick;
    }

    public StormSubtype subtype() {
        return subtype;
    }

    public void setSubtype(StormSubtype subtype) {
        this.subtype = subtype;
    }

    public boolean rotationAssigned() {
        return rotationAssigned;
    }

    public boolean isAntiCyclonic() {
        return antiCyclonic;
    }

    public void setAntiCyclonic(boolean antiCyclonic) {
        this.rotationAssigned = true;
        this.antiCyclonic = antiCyclonic;
    }

    public List<SubVortex> subVortices() {
        return Collections.unmodifiableList(subVortices);
    }

    public List<TornadoCirculationState> satelliteTornadoes() {
        return Collections.unmodifiableList(satelliteTornadoes);
    }

    void updateSubVortices(long gameTime, int desiredCount, float parentRadius) {
        if (gameTime < nextSubVortexRetargetTick && subVortices.size() == desiredCount) {
            return;
        }
        nextSubVortexRetargetTick = gameTime + nextIntInclusive(100, 240);
        while (subVortices.size() < desiredCount) {
            subVortices.add(new SubVortex(
                    random.nextFloat() * 360.0F,
                    3.0F + random.nextFloat() * 4.5F,
                    Math.max(3.0F, parentRadius * (0.34F + random.nextFloat() * 0.38F)),
                    0.42F + random.nextFloat() * 0.38F,
                    0.18F + random.nextFloat() * 0.22F,
                    random.nextFloat() * (float) Math.PI * 2.0F,
                    0.025F + random.nextFloat() * 0.04F
            ));
        }
        while (subVortices.size() > desiredCount) {
            subVortices.remove(subVortices.size() - 1);
        }
        for (SubVortex vortex : subVortices) {
            vortex.orbitRadius = Math.max(3.0F, parentRadius * (0.34F + random.nextFloat() * 0.38F));
        }
    }

    void updateSatelliteTornadoes(long gameTime, int desiredMax, int parentStage, int minParentStage, float parentRadius, Vec3 parentPos, Vec3 parentMotion, double maxWindFraction) {
        expireSatelliteTornadoes(gameTime);
        for (TornadoCirculationState satellite : satelliteTornadoes) {
            satellite.steerAroundParent(parentPos, parentMotion);
        }
        if (desiredMax <= 0 || parentStage < minParentStage || parentPos == null) {
            satelliteTornadoes.clear();
            return;
        }
        if (gameTime < nextSatelliteAttemptTick || satelliteTornadoes.size() >= desiredMax) {
            return;
        }
        nextSatelliteAttemptTick = gameTime + nextIntInclusive(240, 700);
        if (random.nextFloat() > Config.SATELLITE_TORNADOES_SPAWN_CHANCE.get().floatValue()) {
            return;
        }
        long id = gameTime * 31L + random.nextInt(100_000);
        long lifetime = nextIntInclusive(400, 1200);
        float orbit = Math.max(18.0F, parentRadius * (1.55F + random.nextFloat() * 1.20F));
        float orbitDegrees = random.nextFloat() * 360.0F;
        float windFraction = 0.32F + random.nextFloat() * (float) Math.max(0.05D, maxWindFraction - 0.32D);
        float damageWidth = Math.max(12.0F, damagePathWidth * (0.24F + random.nextFloat() * 0.22F));
        float visibleWidth = Math.max(5.0F, damageWidth * (0.42F + random.nextFloat() * 0.18F));
        double radians = Math.toRadians(orbitDegrees);
        Vec3 initial = new Vec3(parentPos.x + Math.cos(radians) * orbit, parentPos.y, parentPos.z + Math.sin(radians) * orbit);
        satelliteTornadoes.add(new TornadoCirculationState(
                id,
                initial.x,
                initial.y,
                initial.z,
                parentMotion == null ? 0.0D : parentMotion.x,
                parentMotion == null ? 0.0D : parentMotion.y,
                parentMotion == null ? 0.0D : parentMotion.z,
                orbitDegrees,
                1.6F + random.nextFloat() * 2.4F,
                orbit,
                damageWidth,
                visibleWidth,
                windFraction,
                true,
                gameTime,
                gameTime + lifetime
        ));
    }

    boolean shouldEvaluateIntensity(long gameTime) {
        return gameTime >= nextIntensityEvaluationTick;
    }

    void scheduleNextIntensityEvaluation(long gameTime, int interval) {
        nextIntensityEvaluationTick = gameTime + interval;
    }

    boolean canChangeIntensity(long gameTime) {
        return lastIntensityChangeTick + 20L * 20L <= gameTime;
    }

    void markIntensityChanged(long gameTime, boolean reintensified) {
        lastIntensityChangeTick = gameTime;
        recentlyReintensified = reintensified;
    }

    public boolean recentlyReintensified() {
        return recentlyReintensified;
    }

    public void clearRecentlyReintensified() {
        recentlyReintensified = false;
    }

    public boolean cycloidalMarksRecorded() {
        return cycloidalMarksRecorded;
    }

    public void setCycloidalMarksRecorded() {
        cycloidalMarksRecorded = true;
    }

    void recordMerge(long gameTime, float absorbedWidth, float absorbedWindFraction) {
        lastMergeTick = gameTime;
        float boost = Math.max(8.0F, absorbedWidth * (0.32F + absorbedWindFraction * 0.22F));
        mergeWidthBoost = Math.max(mergeWidthBoost, boost);
        targetDamagePathWidth = Mth.clamp(Math.max(targetDamagePathWidth, damagePathWidth + boost), (float) Config.effectiveMinDamageWidthBlocks(), (float) Config.effectiveMaxDamageWidthBlocks());
        targetVisibleFunnelWidth = Mth.clamp(Math.max(targetVisibleFunnelWidth, visibleFunnelWidth + boost * 0.45F), 3.0F, (float) Config.effectiveMaxVisibleFunnelWidthBlocks());
    }

    public void recordSupercellMerge(long gameTime, float absorbedWidth, float absorbedWindFraction) {
        lastSupercellMergeTick = gameTime;
        absorbedBySupercell = false;
        absorbedByStormId = Long.MIN_VALUE;
        recordMerge(gameTime, absorbedWidth, absorbedWindFraction);
    }

    public void markAbsorbedBySupercell(long gameTime, long absorberId) {
        absorbedBySupercell = true;
        supercellAbsorbedTick = gameTime;
        absorbedByStormId = absorberId;
        subVortices.clear();
        satelliteTornadoes.clear();
        targetBendStrength = 0.0F;
        targetDamagePathWidth = Math.max(8.0F, damagePathWidth * 0.45F);
        targetVisibleFunnelWidth = Math.max(3.0F, visibleFunnelWidth * 0.35F);
    }

    public boolean isAbsorbedOrDecaying() {
        return absorbedBySupercell;
    }

    public long lastSupercellMergeTick() {
        return lastSupercellMergeTick;
    }

    public long supercellAbsorbedTick() {
        return supercellAbsorbedTick;
    }

    public void setSupercellStructure(float structureStrength, float mesocycloneStrength, float inflowStrength, float rfdStrength, float ffdStrength, float hailCoreStrength, float wallCloudStrength, float wallCloudLoweringDepth, float wallCloudRotationStrength, long gameTime) {
        long deltaTicks = lastStructureTick < 0L ? 1L : Math.max(1L, Math.min(40L, gameTime - lastStructureTick));
        this.supercellStructureStrength = Mth.clamp(structureStrength, 0.0F, 1.0F);
        this.mesocycloneStrength = Mth.clamp(mesocycloneStrength, 0.0F, 1.0F);
        this.inflowStrength = Mth.clamp(inflowStrength, 0.0F, 1.0F);
        this.rfdStrength = Mth.clamp(rfdStrength, 0.0F, 1.0F);
        this.ffdStrength = Mth.clamp(ffdStrength, 0.0F, 1.0F);
        this.hailCoreStrength = Mth.clamp(hailCoreStrength, 0.0F, 1.0F);
        this.wallCloudStrength = Mth.clamp(wallCloudStrength, 0.0F, 1.0F);
        this.wallCloudLoweringDepth = Mth.clamp(wallCloudLoweringDepth, 0.0F, Config.SUPERCELL_STRUCTURE_WALL_CLOUD_MAX_LOWERING_BLOCKS.get());
        this.wallCloudRotationStrength = Mth.clamp(wallCloudRotationStrength, 0.0F, 1.0F);
        if (this.wallCloudStrength > 0.05F) {
            wallCloudPersistenceTicks = Math.min(20 * 60 * 30, wallCloudPersistenceTicks + (int) deltaTicks);
        } else {
            wallCloudPersistenceTicks = Math.max(0, wallCloudPersistenceTicks - (int) (deltaTicks * 2L));
        }
        this.lastStructureTick = gameTime;
    }

    public void clearSupercellStructure(long gameTime) {
        supercellStructureStrength = 0.0F;
        mesocycloneStrength = 0.0F;
        inflowStrength = 0.0F;
        rfdStrength = 0.0F;
        ffdStrength = 0.0F;
        hailCoreStrength = 0.0F;
        wallCloudStrength = 0.0F;
        wallCloudLoweringDepth = 0.0F;
        wallCloudRotationStrength = 0.0F;
        wallCloudPersistenceTicks = 0;
        lastStructureTick = gameTime;
    }

    public float supercellStructureStrength() {
        return supercellStructureStrength;
    }

    public float mesocycloneStrength() {
        return mesocycloneStrength;
    }

    public float inflowStrength() {
        return inflowStrength;
    }

    public float rfdStrength() {
        return rfdStrength;
    }

    public float ffdStrength() {
        return ffdStrength;
    }

    public float hailCoreStrength() {
        return hailCoreStrength;
    }

    public float wallCloudStrength() {
        return wallCloudStrength;
    }

    public float wallCloudLoweringDepth() {
        return wallCloudLoweringDepth;
    }

    public float wallCloudRotationStrength() {
        return wallCloudRotationStrength;
    }

    public int wallCloudPersistenceTicks() {
        return wallCloudPersistenceTicks;
    }

    public long lastStructureTick() {
        return lastStructureTick;
    }

    public void beginMergeOrbit(long gameTime, long partnerId, Vec3 center, double radius, float direction) {
        if (center == null) {
            return;
        }
        if (mergeOrbitPartnerId != partnerId) {
            mergeOrbitStartTick = gameTime;
            mergeOrbitPhase = direction >= 0.0F ? 0.0F : 180.0F;
        }
        mergeOrbitPartnerId = partnerId;
        mergeOrbitCenterX = center.x;
        mergeOrbitCenterY = center.y;
        mergeOrbitCenterZ = center.z;
        mergeOrbitRadius = (float) Mth.clamp(radius, 6.0D, 512.0D);
        mergeOrbitDirection = direction >= 0.0F ? 1.0F : -1.0F;
    }

    public void clearMergeOrbit() {
        mergeOrbitPartnerId = Long.MIN_VALUE;
        mergeOrbitStartTick = -1_000_000L;
    }

    public boolean mergeOrbitActive(long gameTime) {
        return mergeOrbitPartnerId != Long.MIN_VALUE && gameTime <= mergeOrbitStartTick + Config.MERGE_ORBIT_TICKS.get() + Config.MERGE_OVERLAP_TICKS.get() + 80L;
    }

    public Vec3 mergeOrbitTarget(double fallbackY) {
        double radians = Math.toRadians(mergeOrbitPhase);
        return new Vec3(
                mergeOrbitCenterX + Math.cos(radians) * mergeOrbitRadius,
                Double.isFinite(mergeOrbitCenterY) ? mergeOrbitCenterY : fallbackY,
                mergeOrbitCenterZ + Math.sin(radians) * mergeOrbitRadius
        );
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("WanderAngle", wanderAngle);
        tag.putFloat("TargetWanderAngle", targetWanderAngle);
        tag.putLong("NextWanderRetargetTick", nextWanderRetargetTick);
        tag.putFloat("WidthMultiplier", widthMultiplier);
        tag.putFloat("TargetWidthMultiplier", targetWidthMultiplier);
        tag.putFloat("DamagePathWidth", damagePathWidth);
        tag.putFloat("TargetDamagePathWidth", targetDamagePathWidth);
        tag.putFloat("VisibleFunnelWidth", visibleFunnelWidth);
        tag.putFloat("TargetVisibleFunnelWidth", targetVisibleFunnelWidth);
        tag.putFloat("CoreRadius", coreRadius);
        tag.putFloat("RadiusOfMaxWind", radiusOfMaxWind);
        tag.putFloat("MergeWidthBoost", mergeWidthBoost);
        tag.putLong("NextWidthRetargetTick", nextWidthRetargetTick);
        tag.putFloat("BendAngle", bendAngle);
        tag.putFloat("TargetBendAngle", targetBendAngle);
        tag.putFloat("BendStrength", bendStrength);
        tag.putFloat("TargetBendStrength", targetBendStrength);
        tag.putFloat("BendPhase", bendPhase);
        tag.putFloat("BendPhaseRate", bendPhaseRate);
        tag.putLong("NextBendRetargetTick", nextBendRetargetTick);
        tag.putFloat("VisualSpinSpeed", visualSpinSpeed);
        tag.putFloat("TargetVisualSpinSpeed", targetVisualSpinSpeed);
        tag.putLong("LastAlertTick", lastAlertTick);
        tag.putBoolean("RotationAssigned", rotationAssigned);
        tag.putBoolean("AntiCyclonic", antiCyclonic);
        tag.putLong("NextSatelliteAttemptTick", nextSatelliteAttemptTick);
        tag.putLong("LastMergeTick", lastMergeTick);
        tag.putLong("LastSupercellMergeTick", lastSupercellMergeTick);
        tag.putLong("SupercellAbsorbedTick", supercellAbsorbedTick);
        tag.putLong("AbsorbedByStormId", absorbedByStormId);
        tag.putBoolean("AbsorbedBySupercell", absorbedBySupercell);
        tag.putBoolean("CycloidalMarksRecorded", cycloidalMarksRecorded);
        tag.putFloat("SupercellStructureStrength", supercellStructureStrength);
        tag.putFloat("MesocycloneStrength", mesocycloneStrength);
        tag.putFloat("InflowStrength", inflowStrength);
        tag.putFloat("RfdStrength", rfdStrength);
        tag.putFloat("FfdStrength", ffdStrength);
        tag.putFloat("HailCoreStrength", hailCoreStrength);
        tag.putFloat("WallCloudStrength", wallCloudStrength);
        tag.putFloat("WallCloudLoweringDepth", wallCloudLoweringDepth);
        tag.putFloat("WallCloudRotationStrength", wallCloudRotationStrength);
        tag.putInt("WallCloudPersistenceTicks", wallCloudPersistenceTicks);
        tag.putLong("LastStructureTick", lastStructureTick);
        tag.putLong("MergeOrbitPartnerId", mergeOrbitPartnerId);
        tag.putLong("MergeOrbitStartTick", mergeOrbitStartTick);
        tag.putDouble("MergeOrbitCenterX", mergeOrbitCenterX);
        tag.putDouble("MergeOrbitCenterY", mergeOrbitCenterY);
        tag.putDouble("MergeOrbitCenterZ", mergeOrbitCenterZ);
        tag.putFloat("MergeOrbitRadius", mergeOrbitRadius);
        tag.putFloat("MergeOrbitPhase", mergeOrbitPhase);
        tag.putFloat("MergeOrbitDirection", mergeOrbitDirection);
        tag.putInt("SatelliteCount", satelliteTornadoes.size());
        for (int i = 0; i < satelliteTornadoes.size(); i++) {
            tag.put("Satellite" + i, satelliteTornadoes.get(i).save());
        }
        return tag;
    }

    public void load(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return;
        }
        wanderAngle = tag.getFloat("WanderAngle");
        targetWanderAngle = tag.getFloat("TargetWanderAngle");
        nextWanderRetargetTick = tag.getLong("NextWanderRetargetTick");
        widthMultiplier = tag.contains("WidthMultiplier") ? tag.getFloat("WidthMultiplier") : widthMultiplier;
        targetWidthMultiplier = tag.contains("TargetWidthMultiplier") ? tag.getFloat("TargetWidthMultiplier") : widthMultiplier;
        damagePathWidth = tag.contains("DamagePathWidth") ? tag.getFloat("DamagePathWidth") : Math.max(24.0F, visibleFunnelWidth / 0.55F);
        targetDamagePathWidth = tag.contains("TargetDamagePathWidth") ? tag.getFloat("TargetDamagePathWidth") : damagePathWidth;
        visibleFunnelWidth = tag.contains("VisibleFunnelWidth") ? tag.getFloat("VisibleFunnelWidth") : Math.max(3.0F, damagePathWidth * 0.55F);
        targetVisibleFunnelWidth = tag.contains("TargetVisibleFunnelWidth") ? tag.getFloat("TargetVisibleFunnelWidth") : visibleFunnelWidth;
        coreRadius = tag.contains("CoreRadius") ? tag.getFloat("CoreRadius") : Math.max(2.0F, damagePathWidth * 0.16F);
        radiusOfMaxWind = tag.contains("RadiusOfMaxWind") ? tag.getFloat("RadiusOfMaxWind") : Math.max(3.0F, damagePathWidth * 0.28F);
        mergeWidthBoost = tag.contains("MergeWidthBoost") ? tag.getFloat("MergeWidthBoost") : 0.0F;
        nextWidthRetargetTick = tag.getLong("NextWidthRetargetTick");
        bendAngle = tag.getFloat("BendAngle");
        targetBendAngle = tag.getFloat("TargetBendAngle");
        bendStrength = tag.getFloat("BendStrength");
        targetBendStrength = tag.getFloat("TargetBendStrength");
        bendPhase = tag.getFloat("BendPhase");
        bendPhaseRate = tag.contains("BendPhaseRate") ? tag.getFloat("BendPhaseRate") : bendPhaseRate;
        nextBendRetargetTick = tag.getLong("NextBendRetargetTick");
        visualSpinSpeed = tag.contains("VisualSpinSpeed") ? tag.getFloat("VisualSpinSpeed") : visualSpinSpeed;
        targetVisualSpinSpeed = tag.contains("TargetVisualSpinSpeed") ? tag.getFloat("TargetVisualSpinSpeed") : visualSpinSpeed;
        lastAlertTick = tag.contains("LastAlertTick") ? tag.getLong("LastAlertTick") : lastAlertTick;
        rotationAssigned = tag.getBoolean("RotationAssigned");
        antiCyclonic = tag.getBoolean("AntiCyclonic");
        nextSatelliteAttemptTick = tag.getLong("NextSatelliteAttemptTick");
        lastMergeTick = tag.contains("LastMergeTick") ? tag.getLong("LastMergeTick") : lastMergeTick;
        lastSupercellMergeTick = tag.contains("LastSupercellMergeTick") ? tag.getLong("LastSupercellMergeTick") : lastSupercellMergeTick;
        supercellAbsorbedTick = tag.contains("SupercellAbsorbedTick") ? tag.getLong("SupercellAbsorbedTick") : supercellAbsorbedTick;
        absorbedByStormId = tag.contains("AbsorbedByStormId") ? tag.getLong("AbsorbedByStormId") : absorbedByStormId;
        absorbedBySupercell = tag.getBoolean("AbsorbedBySupercell");
        cycloidalMarksRecorded = tag.getBoolean("CycloidalMarksRecorded");
        supercellStructureStrength = tag.contains("SupercellStructureStrength") ? tag.getFloat("SupercellStructureStrength") : supercellStructureStrength;
        mesocycloneStrength = tag.contains("MesocycloneStrength") ? tag.getFloat("MesocycloneStrength") : mesocycloneStrength;
        inflowStrength = tag.contains("InflowStrength") ? tag.getFloat("InflowStrength") : inflowStrength;
        rfdStrength = tag.contains("RfdStrength") ? tag.getFloat("RfdStrength") : rfdStrength;
        ffdStrength = tag.contains("FfdStrength") ? tag.getFloat("FfdStrength") : ffdStrength;
        hailCoreStrength = tag.contains("HailCoreStrength") ? tag.getFloat("HailCoreStrength") : hailCoreStrength;
        wallCloudStrength = tag.contains("WallCloudStrength") ? tag.getFloat("WallCloudStrength") : wallCloudStrength;
        wallCloudLoweringDepth = tag.contains("WallCloudLoweringDepth") ? tag.getFloat("WallCloudLoweringDepth") : wallCloudLoweringDepth;
        wallCloudRotationStrength = tag.contains("WallCloudRotationStrength") ? tag.getFloat("WallCloudRotationStrength") : wallCloudRotationStrength;
        wallCloudPersistenceTicks = tag.contains("WallCloudPersistenceTicks") ? tag.getInt("WallCloudPersistenceTicks") : wallCloudPersistenceTicks;
        lastStructureTick = tag.contains("LastStructureTick") ? tag.getLong("LastStructureTick") : lastStructureTick;
        mergeOrbitPartnerId = tag.contains("MergeOrbitPartnerId") ? tag.getLong("MergeOrbitPartnerId") : mergeOrbitPartnerId;
        mergeOrbitStartTick = tag.contains("MergeOrbitStartTick") ? tag.getLong("MergeOrbitStartTick") : mergeOrbitStartTick;
        mergeOrbitCenterX = tag.contains("MergeOrbitCenterX") ? tag.getDouble("MergeOrbitCenterX") : mergeOrbitCenterX;
        mergeOrbitCenterY = tag.contains("MergeOrbitCenterY") ? tag.getDouble("MergeOrbitCenterY") : mergeOrbitCenterY;
        mergeOrbitCenterZ = tag.contains("MergeOrbitCenterZ") ? tag.getDouble("MergeOrbitCenterZ") : mergeOrbitCenterZ;
        mergeOrbitRadius = tag.contains("MergeOrbitRadius") ? tag.getFloat("MergeOrbitRadius") : mergeOrbitRadius;
        mergeOrbitPhase = tag.contains("MergeOrbitPhase") ? tag.getFloat("MergeOrbitPhase") : mergeOrbitPhase;
        mergeOrbitDirection = tag.contains("MergeOrbitDirection") ? tag.getFloat("MergeOrbitDirection") : mergeOrbitDirection;
        satelliteTornadoes.clear();
        int satellites = Mth.clamp(tag.getInt("SatelliteCount"), 0, 8);
        for (int i = 0; i < satellites; i++) {
            CompoundTag satelliteTag = tag.getCompound("Satellite" + i);
            if (!satelliteTag.isEmpty()) {
                satelliteTornadoes.add(TornadoCirculationState.load(satelliteTag));
            }
        }
    }

    private int nextIntInclusive(int min, int max) {
        if (max <= min) {
            return min;
        }
        return min + random.nextInt(max - min + 1);
    }

    private void retargetBend(long gameTime) {
        targetBendAngle = random.nextFloat() * 360.0F;
        targetBendStrength = 0.35F + random.nextFloat() * 0.65F;
        bendPhaseRate = 0.010F + random.nextFloat() * 0.026F;
        nextBendRetargetTick = gameTime + nextIntInclusive(80, 220);
    }

    private static float terrainRoughnessPenalty(ServerLevel level, BlockPos center) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int x = -24; x <= 24; x += 12) {
            for (int z = -24; z <= 24; z += 12) {
                BlockPos sample = center.offset(x, 0, z);
                if (!level.isLoaded(sample)) {
                    continue;
                }
                int y = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, sample).getY();
                min = Math.min(min, y);
                max = Math.max(max, y);
            }
        }
        if (min == Integer.MAX_VALUE) {
            return 0.0F;
        }
        return Mth.clamp((max - min) / 90.0F, 0.0F, 0.30F);
    }

    private float wrapDegrees(float value) {
        while (value >= 360.0F) {
            value -= 360.0F;
        }
        while (value < 0.0F) {
            value += 360.0F;
        }
        return value;
    }

    private float wrapDegreesSigned(float value) {
        while (value > 180.0F) {
            value -= 360.0F;
        }
        while (value < -180.0F) {
            value += 360.0F;
        }
        return value;
    }

    public static final class SubVortex {
        private float orbitDegrees;
        private float orbitSpeedDegrees;
        private float orbitRadius;
        private final float radiusFraction;
        private final float intensityFraction;
        private float pulse;
        private final float pulseRate;

        private SubVortex(float orbitDegrees, float orbitSpeedDegrees, float orbitRadius, float radiusFraction, float intensityFraction, float pulse, float pulseRate) {
            this.orbitDegrees = orbitDegrees;
            this.orbitSpeedDegrees = orbitSpeedDegrees;
            this.orbitRadius = orbitRadius;
            this.radiusFraction = radiusFraction;
            this.intensityFraction = intensityFraction;
            this.pulse = pulse;
            this.pulseRate = pulseRate;
        }

        public float orbitDegrees() {
            return orbitDegrees;
        }

        public float orbitRadius() {
            return orbitRadius;
        }

        public float radiusFraction() {
            return radiusFraction;
        }

        public float intensityFraction() {
            return intensityFraction * (0.82F + 0.18F * Mth.sin(pulse));
        }
    }

    public static final class TornadoCirculationState {
        private final long id;
        private double x;
        private double y;
        private double z;
        private double motionX;
        private double motionY;
        private double motionZ;
        private float orbitDegrees;
        private final float orbitSpeedDegrees;
        private final float orbitRadius;
        private float damagePathWidth;
        private float visibleFunnelWidth;
        private float coreRadius;
        private float radiusOfMaxWind;
        private final float windFraction;
        private boolean touchedDown;
        private boolean absorbed;
        private final long spawnTick;
        private final long expireTick;
        private long damageTick = -1L;
        private int damageCount;

        private TornadoCirculationState(long id, double x, double y, double z, double motionX, double motionY, double motionZ, float orbitDegrees, float orbitSpeedDegrees, float orbitRadius, float damagePathWidth, float visibleFunnelWidth, float windFraction, boolean touchedDown, long spawnTick, long expireTick) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.motionX = motionX;
            this.motionY = motionY;
            this.motionZ = motionZ;
            this.orbitDegrees = orbitDegrees;
            this.orbitSpeedDegrees = orbitSpeedDegrees;
            this.orbitRadius = orbitRadius;
            this.damagePathWidth = damagePathWidth;
            this.visibleFunnelWidth = visibleFunnelWidth;
            this.coreRadius = Math.max(2.0F, damagePathWidth * 0.16F);
            this.radiusOfMaxWind = Math.max(3.0F, damagePathWidth * 0.28F);
            this.windFraction = windFraction;
            this.touchedDown = touchedDown;
            this.spawnTick = spawnTick;
            this.expireTick = expireTick;
        }

        private void tickVisual(float rotationDirection) {
            orbitDegrees = wrapOrbit(orbitDegrees + orbitSpeedDegrees * rotationDirection);
            x += motionX;
            y += motionY;
            z += motionZ;
            motionX *= 0.92D;
            motionY *= 0.88D;
            motionZ *= 0.92D;
        }

        private void steerAroundParent(Vec3 parentPos, Vec3 parentMotion) {
            if (parentPos == null) {
                return;
            }
            double oldX = x;
            double oldY = y;
            double oldZ = z;
            double radians = Math.toRadians(orbitDegrees);
            double targetX = parentPos.x + Math.cos(radians) * orbitRadius;
            double targetY = parentPos.y;
            double targetZ = parentPos.z + Math.sin(radians) * orbitRadius;
            x += (targetX - x) * 0.18D + (parentMotion == null ? 0.0D : parentMotion.x * 0.45D);
            y += (targetY - y) * 0.20D;
            z += (targetZ - z) * 0.18D + (parentMotion == null ? 0.0D : parentMotion.z * 0.45D);
            motionX = x - oldX;
            motionY = y - oldY;
            motionZ = z - oldZ;
            touchedDown = true;
        }

        public boolean tryConsumeDamage(long gameTime, int cap) {
            if (damageTick != gameTime) {
                damageTick = gameTime;
                damageCount = 0;
            }
            if (damageCount >= cap) {
                return false;
            }
            damageCount++;
            return true;
        }

        public boolean isExpired(long gameTime) {
            return gameTime >= expireTick;
        }

        public long id() {
            return id;
        }

        public float orbitDegrees() {
            return orbitDegrees;
        }

        public float orbitRadius() {
            return orbitRadius;
        }

        public float widthMultiplier() {
            return visibleFunnelWidth / Math.max(1.0F, damagePathWidth);
        }

        public float windFraction() {
            return windFraction;
        }

        public float damagePathWidth() {
            return damagePathWidth;
        }

        public float visibleFunnelWidth() {
            return visibleFunnelWidth;
        }

        public float coreRadius() {
            return coreRadius;
        }

        public float radiusOfMaxWind() {
            return radiusOfMaxWind;
        }

        public boolean touchedDown() {
            return touchedDown;
        }

        public boolean absorbed() {
            return absorbed;
        }

        public void absorb() {
            absorbed = true;
        }

        public long spawnTick() {
            return spawnTick;
        }

        public long expireTick() {
            return expireTick;
        }

        public Vec3 motion() {
            return new Vec3(motionX, motionY, motionZ);
        }

        public Vec3 center(Vec3 parent, double fallbackY) {
            if (Double.isFinite(x) && Double.isFinite(z)) {
                return new Vec3(x, Double.isFinite(y) ? y : fallbackY, z);
            }
            double angle = Math.toRadians(orbitDegrees);
            return new Vec3(parent.x + Math.cos(angle) * orbitRadius, fallbackY, parent.z + Math.sin(angle) * orbitRadius);
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("Id", id);
            tag.putDouble("X", x);
            tag.putDouble("Y", y);
            tag.putDouble("Z", z);
            tag.putDouble("MotionX", motionX);
            tag.putDouble("MotionY", motionY);
            tag.putDouble("MotionZ", motionZ);
            tag.putFloat("OrbitDegrees", orbitDegrees);
            tag.putFloat("OrbitSpeedDegrees", orbitSpeedDegrees);
            tag.putFloat("OrbitRadius", orbitRadius);
            tag.putFloat("DamagePathWidth", damagePathWidth);
            tag.putFloat("VisibleFunnelWidth", visibleFunnelWidth);
            tag.putFloat("CoreRadius", coreRadius);
            tag.putFloat("RadiusOfMaxWind", radiusOfMaxWind);
            tag.putFloat("WindFraction", windFraction);
            tag.putBoolean("TouchedDown", touchedDown);
            tag.putBoolean("Absorbed", absorbed);
            tag.putLong("SpawnTick", spawnTick);
            tag.putLong("ExpireTick", expireTick);
            return tag;
        }

        private static TornadoCirculationState load(CompoundTag tag) {
            float damageWidth = tag.contains("DamagePathWidth") ? tag.getFloat("DamagePathWidth") : Math.max(12.0F, tag.getFloat("OrbitRadius") * 0.55F);
            float visibleWidth = tag.contains("VisibleFunnelWidth") ? tag.getFloat("VisibleFunnelWidth") : Math.max(5.0F, damageWidth * Math.max(0.25F, tag.getFloat("WidthMultiplier")));
            TornadoCirculationState state = new TornadoCirculationState(
                    tag.getLong("Id"),
                    tag.contains("X") ? tag.getDouble("X") : Double.NaN,
                    tag.contains("Y") ? tag.getDouble("Y") : Double.NaN,
                    tag.contains("Z") ? tag.getDouble("Z") : Double.NaN,
                    tag.getDouble("MotionX"),
                    tag.getDouble("MotionY"),
                    tag.getDouble("MotionZ"),
                    tag.getFloat("OrbitDegrees"),
                    tag.getFloat("OrbitSpeedDegrees"),
                    tag.getFloat("OrbitRadius"),
                    damageWidth,
                    visibleWidth,
                    tag.getFloat("WindFraction"),
                    tag.contains("TouchedDown") ? tag.getBoolean("TouchedDown") : true,
                    tag.getLong("SpawnTick"),
                    tag.getLong("ExpireTick")
            );
            state.coreRadius = tag.contains("CoreRadius") ? tag.getFloat("CoreRadius") : Math.max(2.0F, damageWidth * 0.16F);
            state.radiusOfMaxWind = tag.contains("RadiusOfMaxWind") ? tag.getFloat("RadiusOfMaxWind") : Math.max(3.0F, damageWidth * 0.28F);
            state.absorbed = tag.getBoolean("Absorbed");
            return state;
        }

        private static float wrapOrbit(float value) {
            while (value >= 360.0F) {
                value -= 360.0F;
            }
            while (value < 0.0F) {
                value += 360.0F;
            }
            return value;
        }
    }
}
