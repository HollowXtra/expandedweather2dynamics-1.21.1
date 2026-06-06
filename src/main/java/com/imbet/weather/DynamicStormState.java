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
    private static final float WEDGE_ENTER_WIDTH_BLOCKS = 1609.344F * 0.5F;
    private static final float WEDGE_EXIT_WIDTH_BLOCKS = 1609.344F * 0.44F;
    private static final float WEDGE_FORCED_VISIBLE_CAP_BLOCKS = 1609.344F;

    private final long stormId;
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
    private float sidewindingBendBlend;
    private float targetSidewindingBendBlend;
    private boolean sidewinderTagLocked;
    private FunnelMorphology funnelMorphology = FunnelMorphology.DEFAULT;
    private FunnelMorphology targetFunnelMorphology = FunnelMorphology.DEFAULT;
    private boolean funnelMorphologyLocked;
    private float funnelMorphologyBlend;
    private float trunkNeckFraction = 0.50F;
    private float targetTrunkNeckFraction = 0.50F;
    private float trunkCurvePhase;
    private float trunkCurveStrength = 1.0F;
    private long nextMorphologyRetargetTick;
    private float visualSpinSpeed = 0.055F;
    private float targetVisualSpinSpeed = 0.055F;
    private float liveMaxWindMph;
    private float targetLiveMaxWindMph;
    private long nextLiveWindRetargetTick;
    private boolean clientVisualSpinInitialized;
    private long blockDamageTick = -1L;
    private int blockDamageCount;
    private long lastAlertTick = -1_000_000L;
    private StormSubtype subtype = StormSubtype.NONE;
    private boolean rotationAssigned;
    private boolean antiCyclonic;
    private final List<SubVortex> subVortices = new ArrayList<>();
    private final List<HorizontalVortex> horizontalVortices = new ArrayList<>();
    private final List<TornadoCirculationState> satelliteTornadoes = new ArrayList<>();
    private long nextSubVortexRetargetTick;
    private long nextSubVortexId = 1L;
    private long nextHorizontalVortexRetargetTick;
    private long nextHorizontalVortexId = 1L;
    private boolean multiVortexEpisodeActive;
    private boolean multiVortexTagLocked;
    private long nextMultiVortexEpisodeAttemptTick;
    private long multiVortexEpisodeEndTick;
    private long nextSatelliteAttemptTick;
    private long nextSatelliteId = 1L;
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
        this.stormId = stormId;
        this.random = new Random(stormId * 31L + 17L);
    }

    void tickServer(ServerLevel level, StormObject storm, long gameTime) {
        if (gameTime >= nextWanderRetargetTick) {
            retargetWander(gameTime);
        }
        if (gameTime >= nextWidthRetargetTick) {
            retargetWidth(level, storm, gameTime);
        }
        refreshWedgeMorphology(storm);
        if (gameTime >= nextBendRetargetTick) {
            retargetBend(storm, gameTime);
        }
        if (gameTime >= nextMorphologyRetargetTick) {
            retargetFunnelMorphology(level, storm, gameTime);
        }
        retargetLiveWind(level, storm, gameTime);
        enforceExclusiveVisualTags();
        mergeWidthBoost *= 0.985F;
        tickVisual(gameTime);
    }

    void tickVisual(long gameTime) {
        subVortices.removeIf(vortex -> vortex.isExpired(gameTime));
        horizontalVortices.removeIf(vortex -> vortex.isExpired(gameTime));
        bendPhase += bendPhaseRate;
        trunkCurvePhase += 0.012F + bendPhaseRate * 0.55F;
        float rotationDirection = antiCyclonic ? -1.0F : 1.0F;
        for (SubVortex vortex : subVortices) {
            vortex.orbitDegrees = wrapDegrees(vortex.orbitDegrees + vortex.orbitSpeedDegrees * rotationDirection);
            vortex.orbitRadius += (vortex.targetOrbitRadius - vortex.orbitRadius) * 0.035F;
            vortex.pulse += vortex.pulseRate;
        }
        for (HorizontalVortex vortex : horizontalVortices) {
            vortex.orbitDegrees = wrapDegrees(vortex.orbitDegrees + vortex.orbitSpeedDegrees * rotationDirection);
            vortex.orbitRadius += (vortex.targetOrbitRadius - vortex.orbitRadius) * 0.045F;
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

    void steerSatelliteTornadoes(Vec3 parentPos, Vec3 parentMotion) {
        for (TornadoCirculationState satellite : satelliteTornadoes) {
            satellite.steerAroundParent(parentPos, parentMotion);
        }
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
            preserveWedgeWidthTargets(storm);
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
        preserveWedgeWidthTargets(storm);
        nextWidthRetargetTick = gameTime + nextIntInclusive(minTicks, maxTicks);
    }

    void smooth() {
        enforceExclusiveVisualTags();
        wanderAngle += (targetWanderAngle - wanderAngle) * Config.TORNADO_WANDER_SMOOTHING.get().floatValue();
        widthMultiplier += (targetWidthMultiplier - widthMultiplier) * Config.TORNADO_WIDTH_SMOOTHING.get().floatValue();
        damagePathWidth += (targetDamagePathWidth - damagePathWidth) * Config.TORNADO_WIDTH_SMOOTHING.get().floatValue();
        visibleFunnelWidth += (targetVisibleFunnelWidth - visibleFunnelWidth) * Config.TORNADO_WIDTH_SMOOTHING.get().floatValue();
        coreRadius = Math.max(2.0F, damagePathWidth * 0.16F);
        radiusOfMaxWind = Math.max(3.0F, damagePathWidth * 0.28F);
        smoothLiveWind();
        float bendSmoothing = Math.max(0.085F, Config.TORNADO_FUNNEL_BEND_SMOOTHING.get().floatValue());
        float bendDelta = wrapDegreesSigned(targetBendAngle - bendAngle);
        bendAngle = wrapDegrees(bendAngle + bendDelta * bendSmoothing);
        bendStrength += (targetBendStrength - bendStrength) * bendSmoothing;
        sidewindingBendBlend += (targetSidewindingBendBlend - sidewindingBendBlend) * 0.040F;
        float targetMorphBlend = targetFunnelMorphology.shaped() ? 1.0F : 0.0F;
        float transitionStep = 1.0F / Math.max(1, Math.min(Config.TORNADO_ELEPHANT_TRUNK_TRANSITION_TICKS.get(), 55));
        if (funnelMorphologyBlend < targetMorphBlend) {
            funnelMorphologyBlend = Math.min(targetMorphBlend, funnelMorphologyBlend + transitionStep);
        } else if (funnelMorphologyBlend > targetMorphBlend) {
            funnelMorphologyBlend = Math.max(targetMorphBlend, funnelMorphologyBlend - transitionStep);
        }
        trunkNeckFraction += (targetTrunkNeckFraction - trunkNeckFraction) * 0.045F;
        if (funnelMorphologyBlend <= 0.001F && !funnelMorphologyLocked) {
            funnelMorphology = FunnelMorphology.DEFAULT;
        } else if (funnelMorphologyBlend >= 0.999F) {
            funnelMorphology = targetFunnelMorphology;
        }
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

    private void retargetLiveWind(ServerLevel level, StormObject storm, long gameTime) {
        double stageWind = TornadoWindField.stageMaxWindMph(storm);
        if (stageWind <= 0.0D) {
            targetLiveMaxWindMph = 0.0F;
            return;
        }
        if (liveMaxWindMph <= 0.0F) {
            liveMaxWindMph = (float) stageWind;
        }
        if (!Config.WINDFIELD_DYNAMIC_WIND_SPEEDS.get()) {
            targetLiveMaxWindMph = (float) stageWind;
            liveMaxWindMph = targetLiveMaxWindMph;
            return;
        }
        if (gameTime < nextLiveWindRetargetTick) {
            return;
        }
        targetLiveMaxWindMph = (float) TornadoWindField.estimateDynamicMaxWindMph(storm, level, this, gameTime);
        nextLiveWindRetargetTick = gameTime + nextIntInclusive(8, 26);
    }

    private void smoothLiveWind() {
        if (targetLiveMaxWindMph <= 0.0F && liveMaxWindMph <= 0.0F) {
            return;
        }
        if (!Config.WINDFIELD_DYNAMIC_WIND_SPEEDS.get()) {
            liveMaxWindMph = Math.max(0.0F, targetLiveMaxWindMph);
            return;
        }
        float smoothing = Config.WINDFIELD_DYNAMIC_WIND_SMOOTHING.get().floatValue();
        liveMaxWindMph += (targetLiveMaxWindMph - liveMaxWindMph) * smoothing;
        if (Math.abs(targetLiveMaxWindMph - liveMaxWindMph) < 0.05F) {
            liveMaxWindMph = targetLiveMaxWindMph;
        }
        liveMaxWindMph = Math.max(0.0F, liveMaxWindMph);
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

    public float sidewindingBendBlend() {
        return sidewindingBendBlend;
    }

    public FunnelMorphology funnelMorphology() {
        return funnelMorphology;
    }

    public FunnelMorphology targetFunnelMorphology() {
        return targetFunnelMorphology;
    }

    public float funnelMorphologyBlend() {
        return funnelMorphologyBlend;
    }

    public float trunkNeckFraction() {
        return trunkNeckFraction;
    }

    public float trunkCurvePhase() {
        return trunkCurvePhase;
    }

    public float trunkCurveStrength() {
        return trunkCurveStrength;
    }

    public float visualSpinSpeed() {
        return visualSpinSpeed;
    }

    public float targetVisualSpinSpeed() {
        return targetVisualSpinSpeed;
    }

    public float liveMaxWindMph() {
        return liveMaxWindMph;
    }

    public float targetLiveMaxWindMph() {
        return targetLiveMaxWindMph;
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

    public void assignCyclonicIfUnset() {
        if (!rotationAssigned) {
            setAntiCyclonic(false);
        }
    }

    public List<SubVortex> subVortices() {
        return Collections.unmodifiableList(subVortices);
    }

    public List<HorizontalVortex> horizontalVortices() {
        return Collections.unmodifiableList(horizontalVortices);
    }

    public List<TornadoCirculationState> satelliteTornadoes() {
        return Collections.unmodifiableList(satelliteTornadoes);
    }

    void updateSubVortices(long gameTime, int desiredCount, float parentRadius, int parentStage, float parentStageIntensity) {
        subVortices.removeIf(vortex -> vortex.isExpired(gameTime));
        if (desiredCount <= 0) {
            clearSubVortexEpisode();
            return;
        }
        if (!multiVortexEpisodeActive || gameTime >= multiVortexEpisodeEndTick) {
            subVortices.clear();
            multiVortexEpisodeActive = false;
            multiVortexEpisodeEndTick = 0L;
            if (!multiVortexTagLocked && (sidewinderTagLocked || funnelMorphologyLocked)) {
                return;
            }
            if (!multiVortexTagLocked && gameTime < nextMultiVortexEpisodeAttemptTick) {
                return;
            }
            int minAttempt = Math.min(Config.MULTIVORTEX_MIN_ATTEMPT_INTERVAL_TICKS.get(), Config.MULTIVORTEX_MAX_ATTEMPT_INTERVAL_TICKS.get());
            int maxAttempt = Math.max(Config.MULTIVORTEX_MIN_ATTEMPT_INTERVAL_TICKS.get(), Config.MULTIVORTEX_MAX_ATTEMPT_INTERVAL_TICKS.get());
            nextMultiVortexEpisodeAttemptTick = gameTime + nextIntInclusive(minAttempt, maxAttempt);
            int effectiveMinStage = Math.max(Config.MULTIVORTEX_MIN_STAGE.get(), StormObject.STATE_STAGE2);
            float stageBlend = Mth.clamp((parentStage - effectiveMinStage) / (float) Math.max(1, StormObject.STATE_STAGE5 - effectiveMinStage), 0.0F, 1.0F);
            float intensityBlend = Mth.clamp(parentStageIntensity, 0.0F, 1.0F);
            double chance = Mth.clamp(Config.MULTIVORTEX_EPISODE_CHANCE.get() + stageBlend * 0.10D + Math.max(0.0F, intensityBlend - 0.55F) * 0.10D, 0.0D, 0.42D);
            if (!multiVortexTagLocked && random.nextDouble() >= chance) {
                return;
            }
            int minEpisode = Math.min(Config.MULTIVORTEX_MIN_EPISODE_TICKS.get(), Config.MULTIVORTEX_MAX_EPISODE_TICKS.get());
            int maxEpisode = Math.max(Config.MULTIVORTEX_MIN_EPISODE_TICKS.get(), Config.MULTIVORTEX_MAX_EPISODE_TICKS.get());
            multiVortexEpisodeActive = true;
            multiVortexTagLocked = true;
            multiVortexEpisodeEndTick = gameTime + nextIntInclusive(minEpisode, maxEpisode);
            nextSubVortexRetargetTick = gameTime;
            clearSidewinderTag();
            clearFunnelMorphologyTag();
        }
        if (gameTime < nextSubVortexRetargetTick && subVortices.size() >= desiredCount) {
            return;
        }
        nextSubVortexRetargetTick = gameTime + nextIntInclusive(70, 170);
        while (subVortices.size() < desiredCount) {
            subVortices.add(createSubVortex(gameTime, parentRadius));
        }
        while (subVortices.size() > desiredCount) {
            subVortices.remove(subVortices.size() - 1);
        }
        for (SubVortex vortex : subVortices) {
            vortex.targetOrbitRadius = Math.max(3.0F, parentRadius * (0.30F + random.nextFloat() * 0.46F));
        }
        enforceExclusiveVisualTags();
    }

    void updateHorizontalVortices(long gameTime, int desiredCount, float parentRadius, boolean multiVortexActive) {
        horizontalVortices.removeIf(vortex -> vortex.isExpired(gameTime));
        if (desiredCount <= 0) {
            horizontalVortices.clear();
            nextHorizontalVortexRetargetTick = 0L;
            return;
        }
        desiredCount = Mth.clamp(desiredCount, 0, 10);
        if (gameTime < nextHorizontalVortexRetargetTick && horizontalVortices.size() >= desiredCount) {
            return;
        }
        nextHorizontalVortexRetargetTick = gameTime + nextIntInclusive(45, multiVortexActive ? 115 : 155);
        while (horizontalVortices.size() < desiredCount) {
            horizontalVortices.add(createHorizontalVortex(gameTime, parentRadius, multiVortexActive));
        }
        while (horizontalVortices.size() > desiredCount) {
            horizontalVortices.remove(horizontalVortices.size() - 1);
        }
        for (HorizontalVortex vortex : horizontalVortices) {
            float outerBias = multiVortexActive ? 0.52F : 0.42F;
            vortex.targetOrbitRadius = Math.max(4.0F, parentRadius * (0.30F + random.nextFloat() * outerBias));
        }
    }

    private void clearSubVortexEpisode() {
        subVortices.clear();
        multiVortexEpisodeActive = false;
        multiVortexTagLocked = false;
        multiVortexEpisodeEndTick = 0L;
        nextSubVortexRetargetTick = 0L;
    }

    private void enforceExclusiveVisualTags() {
        if (multiVortexVisualActive()) {
            clearSidewinderTag();
            if (!wedgeMorphologyActive()) {
                clearFunnelMorphologyTag();
            }
            return;
        }
        if (funnelMorphologyVisualActive()) {
            clearSidewinderTag();
            return;
        }
        if (sidewinderVisualActive()) {
            clearFunnelMorphologyTag();
        }
    }

    public boolean multiVortexVisualActive() {
        return multiVortexEpisodeActive || !subVortices.isEmpty();
    }

    private boolean funnelMorphologyVisualActive() {
        return targetFunnelMorphology.shaped() || funnelMorphology.shaped() || funnelMorphologyBlend > 0.03F;
    }

    public boolean wedgeMorphologyActive() {
        return targetFunnelMorphology == FunnelMorphology.WEDGE
                || funnelMorphology == FunnelMorphology.WEDGE
                || (funnelMorphologyBlend > 0.03F && targetFunnelMorphology == FunnelMorphology.WEDGE);
    }

    private boolean sidewinderVisualActive() {
        return sidewinderTagLocked || targetSidewindingBendBlend > 0.5F || sidewindingBendBlend > 0.10F;
    }

    private void clearSidewinderTag() {
        sidewinderTagLocked = false;
        targetSidewindingBendBlend = 0.0F;
        sidewindingBendBlend = 0.0F;
    }

    private void clearFunnelMorphologyTag() {
        funnelMorphologyLocked = false;
        targetFunnelMorphology = FunnelMorphology.DEFAULT;
        funnelMorphology = FunnelMorphology.DEFAULT;
        funnelMorphologyBlend = 0.0F;
        targetTrunkNeckFraction = 0.50F;
        trunkNeckFraction = 0.50F;
        trunkCurveStrength = 1.0F;
    }

    private void activateWedgeMorphology() {
        clearSidewinderTag();
        if (targetFunnelMorphology != FunnelMorphology.WEDGE) {
            targetFunnelMorphology = FunnelMorphology.WEDGE;
        }
        if (funnelMorphology != FunnelMorphology.DEFAULT && funnelMorphology != FunnelMorphology.WEDGE) {
            funnelMorphology = FunnelMorphology.DEFAULT;
            funnelMorphologyBlend = 0.0F;
        }
        funnelMorphologyLocked = false;
        targetTrunkNeckFraction = 0.78F;
        trunkCurveStrength = 0.35F;
    }

    private SubVortex createSubVortex(long gameTime, float parentRadius) {
        float orbitRadius = Math.max(3.0F, parentRadius * (0.30F + random.nextFloat() * 0.46F));
        return new SubVortex(
                allocateSubVortexId(),
                gameTime,
                gameTime + nextIntInclusive(90, 280),
                random.nextFloat() * 360.0F,
                4.2F + random.nextFloat() * 5.8F,
                orbitRadius,
                orbitRadius,
                0.30F + random.nextFloat() * 0.34F,
                0.26F + random.nextFloat() * 0.28F,
                random.nextFloat() * (float) Math.PI * 2.0F,
                0.030F + random.nextFloat() * 0.052F
        );
    }

    private HorizontalVortex createHorizontalVortex(long gameTime, float parentRadius, boolean multiVortexActive) {
        float orbitRadius = Math.max(4.0F, parentRadius * (0.30F + random.nextFloat() * (multiVortexActive ? 0.52F : 0.42F)));
        return new HorizontalVortex(
                allocateHorizontalVortexId(),
                gameTime,
                gameTime + nextIntInclusive(55, multiVortexActive ? 190 : 145),
                random.nextFloat() * 360.0F,
                3.4F + random.nextFloat() * (multiVortexActive ? 7.8F : 5.2F),
                orbitRadius,
                orbitRadius,
                0.16F + random.nextFloat() * (multiVortexActive ? 0.30F : 0.22F),
                0.045F + random.nextFloat() * 0.055F,
                0.025F + random.nextFloat() * 0.050F,
                0.25F + random.nextFloat() * (multiVortexActive ? 0.36F : 0.24F),
                -8.0F + random.nextFloat() * 16.0F,
                random.nextFloat() * (float) Math.PI * 2.0F,
                0.045F + random.nextFloat() * 0.070F
        );
    }

    private long allocateSubVortexId() {
        long id = nextSubVortexId++;
        if (nextSubVortexId <= 0L) {
            nextSubVortexId = 1L;
        }
        return id;
    }

    private long allocateHorizontalVortexId() {
        long id = nextHorizontalVortexId++;
        if (nextHorizontalVortexId <= 0L) {
            nextHorizontalVortexId = 1L;
        }
        return id;
    }

    void updateSatelliteTornadoes(long gameTime, int desiredMax, int parentStage, int minParentStage, float parentRadius, Vec3 parentPos, Vec3 parentMotion, double maxWindFraction) {
        expireSatelliteTornadoes(gameTime);
        steerSatelliteTornadoes(parentPos, parentMotion);
        int activeLimit = Mth.clamp(desiredMax, 0, 1);
        while (satelliteTornadoes.size() > activeLimit) {
            satelliteTornadoes.remove(satelliteTornadoes.size() - 1);
        }
        if (activeLimit <= 0 || parentPos == null) {
            satelliteTornadoes.clear();
            return;
        }
        if (parentStage < minParentStage) {
            return;
        }
        if (gameTime < nextSatelliteAttemptTick || satelliteTornadoes.size() >= activeLimit) {
            return;
        }
        nextSatelliteAttemptTick = gameTime + nextIntInclusive(900, 2400);
        float spawnChance = Mth.clamp(Config.SATELLITE_TORNADOES_SPAWN_CHANCE.get().floatValue(), 0.0F, 0.08F);
        if (random.nextFloat() > spawnChance) {
            return;
        }
        long id = allocateSatelliteId();
        long lifetime = nextIntInclusive(400, 1200);
        float orbit = Math.max(12.0F, parentRadius * (1.08F + random.nextFloat() * 0.56F) + 8.0F + random.nextFloat() * 12.0F);
        float orbitDegrees = nextSatelliteOrbitDegrees();
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
                1.45F + random.nextFloat() * 2.75F,
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
        enforceExclusiveVisualTags();
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
        tag.putFloat("SidewindingBendBlend", sidewindingBendBlend);
        tag.putFloat("TargetSidewindingBendBlend", targetSidewindingBendBlend);
        tag.putBoolean("SidewinderTagLocked", sidewinderTagLocked);
        tag.putLong("NextSubVortexId", nextSubVortexId);
        tag.putLong("NextHorizontalVortexId", nextHorizontalVortexId);
        tag.putLong("NextHorizontalVortexRetargetTick", nextHorizontalVortexRetargetTick);
        tag.putBoolean("MultiVortexEpisodeActive", multiVortexEpisodeActive);
        tag.putBoolean("MultiVortexTagLocked", multiVortexTagLocked);
        tag.putLong("NextMultiVortexEpisodeAttemptTick", nextMultiVortexEpisodeAttemptTick);
        tag.putLong("MultiVortexEpisodeEndTick", multiVortexEpisodeEndTick);
        tag.putInt("SubVortexCount", subVortices.size());
        for (int i = 0; i < subVortices.size(); i++) {
            tag.put("SubVortex" + i, subVortices.get(i).save());
        }
        tag.putInt("HorizontalVortexCount", horizontalVortices.size());
        for (int i = 0; i < horizontalVortices.size(); i++) {
            tag.put("HorizontalVortex" + i, horizontalVortices.get(i).save());
        }
        tag.putString("FunnelMorphology", funnelMorphology.name());
        tag.putString("TargetFunnelMorphology", targetFunnelMorphology.name());
        tag.putBoolean("FunnelMorphologyLocked", funnelMorphologyLocked);
        tag.putFloat("FunnelMorphologyBlend", funnelMorphologyBlend);
        tag.putFloat("TrunkNeckFraction", trunkNeckFraction);
        tag.putFloat("TargetTrunkNeckFraction", targetTrunkNeckFraction);
        tag.putFloat("TrunkCurvePhase", trunkCurvePhase);
        tag.putFloat("TrunkCurveStrength", trunkCurveStrength);
        tag.putLong("NextMorphologyRetargetTick", nextMorphologyRetargetTick);
        tag.putFloat("VisualSpinSpeed", visualSpinSpeed);
        tag.putFloat("TargetVisualSpinSpeed", targetVisualSpinSpeed);
        tag.putFloat("LiveMaxWindMph", liveMaxWindMph);
        tag.putFloat("TargetLiveMaxWindMph", targetLiveMaxWindMph);
        tag.putLong("NextLiveWindRetargetTick", nextLiveWindRetargetTick);
        tag.putLong("LastAlertTick", lastAlertTick);
        tag.putBoolean("RotationAssigned", rotationAssigned);
        tag.putBoolean("AntiCyclonic", antiCyclonic);
        tag.putLong("NextSatelliteAttemptTick", nextSatelliteAttemptTick);
        tag.putLong("NextSatelliteId", nextSatelliteId);
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
        load(tag, false);
    }

    private void load(CompoundTag tag, boolean mergeSatelliteState) {
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
        sidewindingBendBlend = tag.contains("SidewindingBendBlend") ? Mth.clamp(tag.getFloat("SidewindingBendBlend"), 0.0F, 1.0F) : sidewindingBendBlend;
        targetSidewindingBendBlend = tag.contains("TargetSidewindingBendBlend") ? Mth.clamp(tag.getFloat("TargetSidewindingBendBlend"), 0.0F, 1.0F) : targetSidewindingBendBlend;
        sidewinderTagLocked = tag.contains("SidewinderTagLocked") ? tag.getBoolean("SidewinderTagLocked") : sidewinderVisualActive();
        nextSubVortexId = tag.contains("NextSubVortexId") ? Math.max(1L, tag.getLong("NextSubVortexId")) : nextSubVortexId;
        nextHorizontalVortexId = tag.contains("NextHorizontalVortexId") ? Math.max(1L, tag.getLong("NextHorizontalVortexId")) : nextHorizontalVortexId;
        nextHorizontalVortexRetargetTick = tag.contains("NextHorizontalVortexRetargetTick") ? tag.getLong("NextHorizontalVortexRetargetTick") : nextHorizontalVortexRetargetTick;
        multiVortexEpisodeActive = tag.getBoolean("MultiVortexEpisodeActive");
        multiVortexTagLocked = tag.contains("MultiVortexTagLocked") ? tag.getBoolean("MultiVortexTagLocked") : multiVortexEpisodeActive;
        nextMultiVortexEpisodeAttemptTick = tag.contains("NextMultiVortexEpisodeAttemptTick") ? tag.getLong("NextMultiVortexEpisodeAttemptTick") : nextMultiVortexEpisodeAttemptTick;
        multiVortexEpisodeEndTick = tag.contains("MultiVortexEpisodeEndTick") ? tag.getLong("MultiVortexEpisodeEndTick") : multiVortexEpisodeEndTick;
        replaceSubVortices(tag);
        replaceHorizontalVortices(tag);
        funnelMorphology = tag.contains("FunnelMorphology") ? FunnelMorphology.byName(tag.getString("FunnelMorphology")) : funnelMorphology;
        targetFunnelMorphology = tag.contains("TargetFunnelMorphology") ? FunnelMorphology.byName(tag.getString("TargetFunnelMorphology")) : funnelMorphology;
        funnelMorphologyLocked = tag.contains("FunnelMorphologyLocked") ? tag.getBoolean("FunnelMorphologyLocked") : targetFunnelMorphology.shaped();
        funnelMorphologyBlend = tag.contains("FunnelMorphologyBlend") ? Mth.clamp(tag.getFloat("FunnelMorphologyBlend"), 0.0F, 1.0F) : funnelMorphologyBlend;
        trunkNeckFraction = tag.contains("TrunkNeckFraction") ? Mth.clamp(tag.getFloat("TrunkNeckFraction"), 0.22F, 0.78F) : trunkNeckFraction;
        targetTrunkNeckFraction = tag.contains("TargetTrunkNeckFraction") ? Mth.clamp(tag.getFloat("TargetTrunkNeckFraction"), 0.22F, 0.78F) : trunkNeckFraction;
        trunkCurvePhase = tag.contains("TrunkCurvePhase") ? tag.getFloat("TrunkCurvePhase") : trunkCurvePhase;
        trunkCurveStrength = tag.contains("TrunkCurveStrength") ? Mth.clamp(tag.getFloat("TrunkCurveStrength"), 0.0F, 3.0F) : trunkCurveStrength;
        nextMorphologyRetargetTick = tag.contains("NextMorphologyRetargetTick") ? tag.getLong("NextMorphologyRetargetTick") : nextMorphologyRetargetTick;
        visualSpinSpeed = tag.contains("VisualSpinSpeed") ? tag.getFloat("VisualSpinSpeed") : visualSpinSpeed;
        targetVisualSpinSpeed = tag.contains("TargetVisualSpinSpeed") ? tag.getFloat("TargetVisualSpinSpeed") : visualSpinSpeed;
        liveMaxWindMph = tag.contains("LiveMaxWindMph") ? Math.max(0.0F, tag.getFloat("LiveMaxWindMph")) : liveMaxWindMph;
        targetLiveMaxWindMph = tag.contains("TargetLiveMaxWindMph") ? Math.max(0.0F, tag.getFloat("TargetLiveMaxWindMph")) : Math.max(targetLiveMaxWindMph, liveMaxWindMph);
        nextLiveWindRetargetTick = tag.contains("NextLiveWindRetargetTick") ? tag.getLong("NextLiveWindRetargetTick") : nextLiveWindRetargetTick;
        lastAlertTick = tag.contains("LastAlertTick") ? tag.getLong("LastAlertTick") : lastAlertTick;
        rotationAssigned = tag.getBoolean("RotationAssigned");
        antiCyclonic = tag.getBoolean("AntiCyclonic");
        nextSatelliteAttemptTick = tag.getLong("NextSatelliteAttemptTick");
        nextSatelliteId = tag.contains("NextSatelliteId") ? Math.max(1L, tag.getLong("NextSatelliteId")) : nextSatelliteId;
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
        if (mergeSatelliteState) {
            mergeSatelliteTornadoes(tag);
        } else {
            replaceSatelliteTornadoes(tag);
        }
        ensureNextSatelliteIdBeyondActive();
        ensureNextSubVortexIdBeyondActive();
        ensureNextHorizontalVortexIdBeyondActive();
        enforceExclusiveVisualTags();
    }

    private void replaceSubVortices(CompoundTag tag) {
        subVortices.clear();
        int count = Mth.clamp(tag.getInt("SubVortexCount"), 0, 10);
        for (int i = 0; i < count; i++) {
            CompoundTag vortexTag = tag.getCompound("SubVortex" + i);
            if (!vortexTag.isEmpty()) {
                subVortices.add(SubVortex.load(vortexTag));
            }
        }
    }

    private void replaceHorizontalVortices(CompoundTag tag) {
        horizontalVortices.clear();
        int count = Mth.clamp(tag.getInt("HorizontalVortexCount"), 0, 10);
        for (int i = 0; i < count; i++) {
            CompoundTag vortexTag = tag.getCompound("HorizontalVortex" + i);
            if (!vortexTag.isEmpty()) {
                horizontalVortices.add(HorizontalVortex.load(vortexTag));
            }
        }
    }

    private void ensureNextSubVortexIdBeyondActive() {
        long max = 0L;
        for (SubVortex vortex : subVortices) {
            max = Math.max(max, vortex.id);
        }
        if (max >= nextSubVortexId) {
            nextSubVortexId = max + 1L;
        }
    }

    private void ensureNextHorizontalVortexIdBeyondActive() {
        long max = 0L;
        for (HorizontalVortex vortex : horizontalVortices) {
            max = Math.max(max, vortex.id);
        }
        if (max >= nextHorizontalVortexId) {
            nextHorizontalVortexId = max + 1L;
        }
    }

    private void replaceSatelliteTornadoes(CompoundTag tag) {
        satelliteTornadoes.clear();
        int satellites = Mth.clamp(tag.getInt("SatelliteCount"), 0, 8);
        for (int i = 0; i < satellites; i++) {
            CompoundTag satelliteTag = tag.getCompound("Satellite" + i);
            if (!satelliteTag.isEmpty()) {
                satelliteTornadoes.add(TornadoCirculationState.load(satelliteTag));
            }
        }
    }

    private void mergeSatelliteTornadoes(CompoundTag tag) {
        List<TornadoCirculationState> incomingSatellites = new ArrayList<>();
        int satellites = Mth.clamp(tag.getInt("SatelliteCount"), 0, 8);
        for (int i = 0; i < satellites; i++) {
            CompoundTag satelliteTag = tag.getCompound("Satellite" + i);
            if (!satelliteTag.isEmpty()) {
                incomingSatellites.add(TornadoCirculationState.load(satelliteTag));
            }
        }
        satelliteTornadoes.removeIf(existing -> incomingSatellites.stream().noneMatch(incoming -> incoming.id == existing.id));
        for (TornadoCirculationState incoming : incomingSatellites) {
            TornadoCirculationState existing = findSatellite(incoming.id);
            if (existing == null) {
                satelliteTornadoes.add(incoming);
            } else {
                existing.mergeFromSync(incoming);
            }
        }
    }

    private TornadoCirculationState findSatellite(long id) {
        for (TornadoCirculationState satellite : satelliteTornadoes) {
            if (satellite.id == id) {
                return satellite;
            }
        }
        return null;
    }

    private long allocateSatelliteId() {
        long stormPart = (stormId & 0xffff_ffffL) << 32;
        for (int attempts = 0; attempts < 64; attempts++) {
            long sequence = nextSatelliteId++ & 0xffff_ffffL;
            if (sequence == 0L) {
                sequence = nextSatelliteId++ & 0xffff_ffffL;
            }
            long id = stormPart | sequence;
            if (findSatellite(id) == null) {
                return id;
            }
        }
        long fallback = stormPart | (random.nextLong() & 0xffff_ffffL);
        while (findSatellite(fallback) != null) {
            fallback = stormPart | (random.nextLong() & 0xffff_ffffL);
        }
        return fallback;
    }

    private void ensureNextSatelliteIdBeyondActive() {
        long stormPart = (stormId & 0xffff_ffffL) << 32;
        long maxSequence = 0L;
        for (TornadoCirculationState satellite : satelliteTornadoes) {
            if ((satellite.id & 0xffff_ffff_0000_0000L) == stormPart) {
                maxSequence = Math.max(maxSequence, satellite.id & 0xffff_ffffL);
            }
        }
        if (maxSequence >= nextSatelliteId) {
            nextSatelliteId = maxSequence + 1L;
        }
    }

    private float nextSatelliteOrbitDegrees() {
        if (satelliteTornadoes.isEmpty()) {
            return random.nextFloat() * 360.0F;
        }
        if (satelliteTornadoes.size() == 1) {
            return wrapDegrees(satelliteTornadoes.get(0).orbitDegrees() + 150.0F + random.nextFloat() * 60.0F);
        }
        List<Float> angles = new ArrayList<>();
        for (TornadoCirculationState satellite : satelliteTornadoes) {
            angles.add(wrapDegrees(satellite.orbitDegrees()));
        }
        Collections.sort(angles);
        float bestStart = angles.get(0);
        float bestGap = -1.0F;
        for (int i = 0; i < angles.size(); i++) {
            float current = angles.get(i);
            float next = i == angles.size() - 1 ? angles.get(0) + 360.0F : angles.get(i + 1);
            float gap = next - current;
            if (gap > bestGap) {
                bestGap = gap;
                bestStart = current;
            }
        }
        float jitter = (random.nextFloat() - 0.5F) * Math.min(42.0F, Math.max(10.0F, bestGap * 0.28F));
        return wrapDegrees(bestStart + bestGap * 0.5F + jitter);
    }

    private int nextIntInclusive(int min, int max) {
        if (max <= min) {
            return min;
        }
        return min + random.nextInt(max - min + 1);
    }

    private void retargetBend(StormObject storm, long gameTime) {
        if (multiVortexVisualActive() || funnelMorphologyVisualActive()) {
            targetSidewindingBendBlend = 0.0F;
            targetBendAngle = wrapDegrees(bendAngle + (-32.0F + random.nextFloat() * 64.0F));
            targetBendStrength = 0.18F + random.nextFloat() * 0.22F;
            bendPhaseRate = 0.010F + random.nextFloat() * 0.016F;
            nextBendRetargetTick = gameTime + nextIntInclusive(100, 220);
            return;
        }
        boolean keepSidewinding = sidewinderTagLocked || targetSidewindingBendBlend > 0.5F && random.nextFloat() < 0.58F;
        float widthRatio = damagePathWidth <= 1.0F ? 1.0F : visibleFunnelWidth / damagePathWidth;
        float narrowFavor = Mth.clamp((0.78F - widthRatio) / 0.78F, 0.0F, 1.0F);
        float stageFavor = storm != null && storm.levelCurIntensityStage >= StormObject.STATE_STAGE2 ? 0.025F : 0.0F;
        boolean sidewinding = keepSidewinding || random.nextFloat() < 0.012F + narrowFavor * 0.060F + stageFavor;
        targetSidewindingBendBlend = sidewinding ? 1.0F : 0.0F;
        if (sidewinding) {
            sidewinderTagLocked = true;
            clearFunnelMorphologyTag();
        }
        float sweepRange = sidewinding ? 115.0F : 42.0F;
        float sweep = -sweepRange + random.nextFloat() * (sweepRange * 2.0F);
        targetBendAngle = wrapDegrees(bendAngle + sweep);
        targetBendStrength = sidewinding
                ? 0.74F + random.nextFloat() * 0.65F
                : 0.22F + random.nextFloat() * 0.24F;
        bendPhaseRate = sidewinding
                ? 0.028F + random.nextFloat() * 0.052F
                : 0.010F + random.nextFloat() * 0.018F;
        nextBendRetargetTick = gameTime + (sidewinding ? nextIntInclusive(35, 105) : nextIntInclusive(90, 210));
    }

    private void retargetFunnelMorphology(ServerLevel level, StormObject storm, long gameTime) {
        if (storm == null
                || level == null
                || storm.attrib_waterSpout
                || !storm.isTornadoFormingOrGreater()) {
            funnelMorphologyLocked = false;
            targetFunnelMorphology = FunnelMorphology.DEFAULT;
            nextMorphologyRetargetTick = gameTime + nextIntInclusive(180, 420);
            return;
        }
        if (refreshWedgeMorphology(storm)) {
            nextMorphologyRetargetTick = gameTime + nextIntInclusive(80, 180);
            return;
        }
        if (multiVortexVisualActive() || sidewinderVisualActive()) {
            funnelMorphologyLocked = false;
            targetFunnelMorphology = FunnelMorphology.DEFAULT;
            funnelMorphology = FunnelMorphology.DEFAULT;
            funnelMorphologyBlend = 0.0F;
            nextMorphologyRetargetTick = gameTime + nextIntInclusive(180, 420);
            return;
        }
        if (!Config.FLOATER_TORNADOES_ENABLED.get()
                && (targetFunnelMorphology == FunnelMorphology.FLOATER || funnelMorphology == FunnelMorphology.FLOATER)) {
            clearFunnelMorphologyTag();
            nextMorphologyRetargetTick = gameTime + nextIntInclusive(180, 420);
            return;
        }
        if (funnelMorphologyLocked && targetFunnelMorphology.shaped()) {
            nextMorphologyRetargetTick = gameTime + nextIntInclusive(900, 1800);
            return;
        }
        boolean canElephantTrunk = Config.TORNADO_ELEPHANT_TRUNK_ENABLED.get()
                && storm.levelCurIntensityStage >= Config.TORNADO_ELEPHANT_TRUNK_MIN_STAGE.get()
                && DynamicStormController.canUseElephantTrunkVisuals(this);
        boolean canDrillBit = Config.TORNADO_DRILL_BIT_ENABLED.get()
                && storm.levelCurIntensityStage >= Config.TORNADO_DRILL_BIT_MIN_STAGE.get();
        boolean canFloater = Config.FLOATER_TORNADOES_ENABLED.get()
                && storm.levelCurIntensityStage >= StormObject.STATE_FORMING
                && Math.max(damagePathWidth, targetDamagePathWidth) < WEDGE_ENTER_WIDTH_BLOCKS * 0.72F;
        if (!canElephantTrunk && !canDrillBit && !canFloater) {
            targetFunnelMorphology = FunnelMorphology.DEFAULT;
            nextMorphologyRetargetTick = gameTime + nextIntInclusive(180, 420);
            return;
        }

        float widthFraction = damagePathWidth <= 1.0F ? 1.0F : visibleFunnelWidth / damagePathWidth;
        float maxFraction = Math.max(0.70F, Config.TORNADO_ELEPHANT_TRUNK_MAX_VISIBLE_WIDTH_FRACTION.get().floatValue());
        float narrowFavor = Mth.clamp((maxFraction - widthFraction) / Math.max(0.05F, maxFraction), 0.0F, 1.0F);
        float stageFavor = storm.levelCurIntensityStage <= StormObject.STATE_STAGE2 ? 1.0F : 0.68F;
        FunnelMorphology selectedMorphology = FunnelMorphology.DEFAULT;
        if (canDrillBit) {
            float drillChance = Config.TORNADO_DRILL_BIT_CHANCE.get().floatValue()
                    * (0.30F + narrowFavor * 1.35F)
                    * (storm.levelCurIntensityStage >= StormObject.STATE_STAGE3 ? 1.18F : 1.0F);
            if (random.nextFloat() < drillChance) {
                selectedMorphology = FunnelMorphology.DRILL_BIT;
            }
        }
        if (selectedMorphology == FunnelMorphology.DEFAULT && canElephantTrunk) {
            float chance = Config.TORNADO_ELEPHANT_TRUNK_CHANCE.get().floatValue() * (0.35F + narrowFavor * 1.15F) * stageFavor;
            if (random.nextFloat() < chance) {
                selectedMorphology = FunnelMorphology.ELEPHANT_TRUNK;
            }
        }
        if (selectedMorphology == FunnelMorphology.DEFAULT && canFloater) {
            float floaterChance = Config.FLOATER_TORNADO_SPAWN_CHANCE.get().floatValue()
                    * (0.55F + narrowFavor * 0.90F)
                    * (storm.isGrowing ? 1.18F : 0.72F);
            if (random.nextFloat() < floaterChance) {
                selectedMorphology = FunnelMorphology.FLOATER;
            }
        }
        targetFunnelMorphology = selectedMorphology;
        if (targetFunnelMorphology == FunnelMorphology.DRILL_BIT) {
            funnelMorphologyLocked = true;
            clearSidewinderTag();
            targetTrunkNeckFraction = 0.34F + random.nextFloat() * 0.18F;
            trunkCurveStrength = Mth.clamp(0.56F + random.nextFloat() * 0.28F, 0.0F, 1.05F);
            float drillWidth = Math.max(2.75F, Math.min(targetVisibleFunnelWidth, targetDamagePathWidth * (0.14F + random.nextFloat() * 0.08F)));
            targetVisibleFunnelWidth = drillWidth;
            if (storm != null) {
                targetWidthMultiplier = drillWidth / Math.max(3.0F, DynamicStormController.rawBaseRadiusForWind(storm));
            }
            nextMorphologyRetargetTick = gameTime + nextIntInclusive(900, 2400);
        } else if (targetFunnelMorphology == FunnelMorphology.ELEPHANT_TRUNK) {
            funnelMorphologyLocked = true;
            clearSidewinderTag();
            targetTrunkNeckFraction = 0.38F + random.nextFloat() * 0.24F;
            trunkCurveStrength = Mth.clamp(1.02F + random.nextFloat() * 0.58F, 0.0F, Math.max(1.55F, Config.TORNADO_ELEPHANT_TRUNK_CURVE_STRENGTH.get().floatValue() * 1.55F));
            nextMorphologyRetargetTick = gameTime + nextIntInclusive(460, 1040);
        } else if (targetFunnelMorphology == FunnelMorphology.FLOATER) {
            funnelMorphologyLocked = true;
            clearSidewinderTag();
            targetTrunkNeckFraction = 0.46F + random.nextFloat() * 0.16F;
            trunkCurveStrength = Mth.clamp(0.52F + random.nextFloat() * 0.36F, 0.0F, 1.05F);
            float floaterWidth = Math.max(3.0F, Math.min(targetVisibleFunnelWidth, targetDamagePathWidth * (0.28F + random.nextFloat() * 0.10F)));
            targetVisibleFunnelWidth = floaterWidth;
            if (storm != null) {
                targetWidthMultiplier = floaterWidth / Math.max(3.0F, DynamicStormController.rawBaseRadiusForWind(storm));
            }
            nextMorphologyRetargetTick = gameTime + nextIntInclusive(700, 1700);
        } else {
            nextMorphologyRetargetTick = gameTime + nextIntInclusive(180, 440);
        }
    }

    private boolean refreshWedgeMorphology(StormObject storm) {
        if (storm == null || storm.attrib_waterSpout || !storm.isTornadoFormingOrGreater()) {
            if (wedgeMorphologyActive()) {
                funnelMorphologyLocked = false;
                targetFunnelMorphology = FunnelMorphology.DEFAULT;
            }
            return false;
        }
        float currentWidth = Math.max(damagePathWidth, targetDamagePathWidth);
        float threshold = wedgeMorphologyActive() ? WEDGE_EXIT_WIDTH_BLOCKS : WEDGE_ENTER_WIDTH_BLOCKS;
        if (currentWidth < threshold) {
            if (wedgeMorphologyActive()) {
                funnelMorphologyLocked = false;
                targetFunnelMorphology = FunnelMorphology.DEFAULT;
            }
            return false;
        }

        activateWedgeMorphology();
        preserveWedgeWidthTargets(storm);

        currentWidth = Math.max(damagePathWidth, targetDamagePathWidth);
        float forcedCap = Math.max((float) Config.effectiveMaxVisibleFunnelWidthBlocks(), Math.min(currentWidth, WEDGE_FORCED_VISIBLE_CAP_BLOCKS));
        float desiredVisibleWidth = Mth.clamp(Math.max(currentWidth, WEDGE_ENTER_WIDTH_BLOCKS), 3.0F, forcedCap);
        targetVisibleFunnelWidth = Math.max(targetVisibleFunnelWidth, desiredVisibleWidth);
        targetWidthMultiplier = targetVisibleFunnelWidth / Math.max(3.0F, DynamicStormController.rawBaseRadiusForWind(storm));
        return true;
    }

    private void preserveWedgeWidthTargets(StormObject storm) {
        if (!wedgeMorphologyActive() || storm == null) {
            return;
        }
        float minDamageWidth = (float) Config.effectiveMinDamageWidthBlocks();
        float maxDamageWidth = (float) Config.effectiveMaxDamageWidthBlocks();
        float wedgeDamageFloor = Mth.clamp(Math.max(WEDGE_ENTER_WIDTH_BLOCKS, damagePathWidth), minDamageWidth, maxDamageWidth);
        targetDamagePathWidth = Math.max(targetDamagePathWidth, wedgeDamageFloor);

        float forcedCap = Math.max((float) Config.effectiveMaxVisibleFunnelWidthBlocks(), Math.min(targetDamagePathWidth, WEDGE_FORCED_VISIBLE_CAP_BLOCKS));
        float visibleFloor = Mth.clamp(Math.max(WEDGE_ENTER_WIDTH_BLOCKS, targetDamagePathWidth), 3.0F, forcedCap);
        targetVisibleFunnelWidth = Math.max(targetVisibleFunnelWidth, visibleFloor);
        targetWidthMultiplier = targetVisibleFunnelWidth / Math.max(3.0F, DynamicStormController.rawBaseRadiusForWind(storm));
    }

    public void loadClientSync(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return;
        }
        boolean preserveLocalSpin = clientVisualSpinInitialized && Config.TORNADO_WIND_SPEED_SPIN_ENABLED.get();
        float localVisualSpinSpeed = visualSpinSpeed;
        float localTargetVisualSpinSpeed = targetVisualSpinSpeed;
        load(tag, true);
        enforceExclusiveVisualTags();
        if (preserveLocalSpin) {
            visualSpinSpeed = localVisualSpinSpeed;
            targetVisualSpinSpeed = localTargetVisualSpinSpeed;
        }
        clientVisualSpinInitialized = true;
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
                BlockPos surface = BlockGrabSafety.safeSurface(level, sample, Heightmap.Types.MOTION_BLOCKING);
                if (surface == null) {
                    continue;
                }
                int y = surface.getY();
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
        private final long id;
        private final long spawnTick;
        private final long expireTick;
        private float orbitDegrees;
        private float orbitSpeedDegrees;
        private float orbitRadius;
        private float targetOrbitRadius;
        private final float radiusFraction;
        private final float intensityFraction;
        private float pulse;
        private final float pulseRate;

        private SubVortex(long id, long spawnTick, long expireTick, float orbitDegrees, float orbitSpeedDegrees, float orbitRadius, float targetOrbitRadius, float radiusFraction, float intensityFraction, float pulse, float pulseRate) {
            this.id = id;
            this.spawnTick = spawnTick;
            this.expireTick = expireTick;
            this.orbitDegrees = orbitDegrees;
            this.orbitSpeedDegrees = orbitSpeedDegrees;
            this.orbitRadius = orbitRadius;
            this.targetOrbitRadius = targetOrbitRadius;
            this.radiusFraction = radiusFraction;
            this.intensityFraction = intensityFraction;
            this.pulse = pulse;
            this.pulseRate = pulseRate;
        }

        public long id() {
            return id;
        }

        public long spawnTick() {
            return spawnTick;
        }

        public long expireTick() {
            return expireTick;
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

        public boolean isExpired(long gameTime) {
            return gameTime >= expireTick;
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("Id", id);
            tag.putLong("SpawnTick", spawnTick);
            tag.putLong("ExpireTick", expireTick);
            tag.putFloat("OrbitDegrees", orbitDegrees);
            tag.putFloat("OrbitSpeedDegrees", orbitSpeedDegrees);
            tag.putFloat("OrbitRadius", orbitRadius);
            tag.putFloat("TargetOrbitRadius", targetOrbitRadius);
            tag.putFloat("RadiusFraction", radiusFraction);
            tag.putFloat("IntensityFraction", intensityFraction);
            tag.putFloat("Pulse", pulse);
            tag.putFloat("PulseRate", pulseRate);
            return tag;
        }

        private static SubVortex load(CompoundTag tag) {
            float orbitRadius = Math.max(3.0F, tag.getFloat("OrbitRadius"));
            return new SubVortex(
                    tag.getLong("Id"),
                    tag.getLong("SpawnTick"),
                    tag.getLong("ExpireTick"),
                    tag.getFloat("OrbitDegrees"),
                    tag.getFloat("OrbitSpeedDegrees"),
                    orbitRadius,
                    tag.contains("TargetOrbitRadius") ? Math.max(3.0F, tag.getFloat("TargetOrbitRadius")) : orbitRadius,
                    Mth.clamp(tag.getFloat("RadiusFraction"), 0.12F, 1.0F),
                    Mth.clamp(tag.getFloat("IntensityFraction"), 0.05F, 1.0F),
                    tag.getFloat("Pulse"),
                    tag.contains("PulseRate") ? tag.getFloat("PulseRate") : 0.035F
            );
        }
    }

    public static final class HorizontalVortex {
        private final long id;
        private final long spawnTick;
        private final long expireTick;
        private float orbitDegrees;
        private final float orbitSpeedDegrees;
        private float orbitRadius;
        private float targetOrbitRadius;
        private final float lengthFraction;
        private final float radiusFraction;
        private final float heightFraction;
        private final float intensityFraction;
        private final float pitchDegrees;
        private float pulse;
        private final float pulseRate;

        private HorizontalVortex(long id, long spawnTick, long expireTick, float orbitDegrees, float orbitSpeedDegrees, float orbitRadius, float targetOrbitRadius, float lengthFraction, float radiusFraction, float heightFraction, float intensityFraction, float pitchDegrees, float pulse, float pulseRate) {
            this.id = id;
            this.spawnTick = spawnTick;
            this.expireTick = expireTick;
            this.orbitDegrees = orbitDegrees;
            this.orbitSpeedDegrees = orbitSpeedDegrees;
            this.orbitRadius = orbitRadius;
            this.targetOrbitRadius = targetOrbitRadius;
            this.lengthFraction = lengthFraction;
            this.radiusFraction = radiusFraction;
            this.heightFraction = heightFraction;
            this.intensityFraction = intensityFraction;
            this.pitchDegrees = pitchDegrees;
            this.pulse = pulse;
            this.pulseRate = pulseRate;
        }

        public long id() {
            return id;
        }

        public long spawnTick() {
            return spawnTick;
        }

        public long expireTick() {
            return expireTick;
        }

        public float orbitDegrees() {
            return orbitDegrees;
        }

        public float orbitRadius() {
            return orbitRadius;
        }

        public float lengthFraction() {
            return lengthFraction;
        }

        public float radiusFraction() {
            return radiusFraction;
        }

        public float heightFraction() {
            return heightFraction;
        }

        public float intensityFraction() {
            return intensityFraction * (0.80F + 0.20F * Mth.sin(pulse));
        }

        public float pitchDegrees() {
            return pitchDegrees;
        }

        public boolean isExpired(long gameTime) {
            return gameTime >= expireTick;
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("Id", id);
            tag.putLong("SpawnTick", spawnTick);
            tag.putLong("ExpireTick", expireTick);
            tag.putFloat("OrbitDegrees", orbitDegrees);
            tag.putFloat("OrbitSpeedDegrees", orbitSpeedDegrees);
            tag.putFloat("OrbitRadius", orbitRadius);
            tag.putFloat("TargetOrbitRadius", targetOrbitRadius);
            tag.putFloat("LengthFraction", lengthFraction);
            tag.putFloat("RadiusFraction", radiusFraction);
            tag.putFloat("HeightFraction", heightFraction);
            tag.putFloat("IntensityFraction", intensityFraction);
            tag.putFloat("PitchDegrees", pitchDegrees);
            tag.putFloat("Pulse", pulse);
            tag.putFloat("PulseRate", pulseRate);
            return tag;
        }

        private static HorizontalVortex load(CompoundTag tag) {
            float orbitRadius = Math.max(4.0F, tag.getFloat("OrbitRadius"));
            return new HorizontalVortex(
                    tag.getLong("Id"),
                    tag.getLong("SpawnTick"),
                    tag.getLong("ExpireTick"),
                    tag.getFloat("OrbitDegrees"),
                    tag.getFloat("OrbitSpeedDegrees"),
                    orbitRadius,
                    tag.contains("TargetOrbitRadius") ? Math.max(4.0F, tag.getFloat("TargetOrbitRadius")) : orbitRadius,
                    Mth.clamp(tag.getFloat("LengthFraction"), 0.12F, 0.55F),
                    Mth.clamp(tag.getFloat("RadiusFraction"), 0.035F, 0.16F),
                    Mth.clamp(tag.getFloat("HeightFraction"), 0.015F, 0.11F),
                    Mth.clamp(tag.getFloat("IntensityFraction"), 0.05F, 0.82F),
                    Mth.clamp(tag.getFloat("PitchDegrees"), -16.0F, 16.0F),
                    tag.getFloat("Pulse"),
                    tag.contains("PulseRate") ? tag.getFloat("PulseRate") : 0.055F
            );
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
            motionX *= 0.82D;
            motionY *= 0.78D;
            motionZ *= 0.82D;
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
            if (parentMotion != null) {
                targetX += parentMotion.x * 0.65D;
                targetY += parentMotion.y * 0.35D;
                targetZ += parentMotion.z * 0.65D;
            }
            double snapDistanceSqr = Math.max(65_536.0D, orbitRadius * orbitRadius * 36.0D);
            boolean snapped = !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || new Vec3(x - targetX, y - targetY, z - targetZ).horizontalDistanceSqr() > snapDistanceSqr;
            if (snapped) {
                x = targetX;
                y = targetY;
                z = targetZ;
            } else {
                double smoothing = 0.24D;
                x += (targetX - x) * smoothing;
                y += (targetY - y) * 0.30D;
                z += (targetZ - z) * smoothing;
            }
            motionX = snapped ? (parentMotion == null ? 0.0D : parentMotion.x) : x - oldX;
            motionY = snapped ? (parentMotion == null ? 0.0D : parentMotion.y) : y - oldY;
            motionZ = snapped ? (parentMotion == null ? 0.0D : parentMotion.z) : z - oldZ;
            touchedDown = true;
        }

        private void mergeFromSync(TornadoCirculationState incoming) {
            if (incoming == null || incoming.id != id) {
                return;
            }
            if (Double.isFinite(incoming.x) && Double.isFinite(incoming.y) && Double.isFinite(incoming.z)) {
                boolean invalid = !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z);
                double deltaSqr = invalid ? Double.POSITIVE_INFINITY : new Vec3(x - incoming.x, y - incoming.y, z - incoming.z).horizontalDistanceSqr();
                double snapDistanceSqr = Math.max(65_536.0D, orbitRadius * orbitRadius * 36.0D);
                if (invalid || deltaSqr > snapDistanceSqr) {
                    x = incoming.x;
                    y = incoming.y;
                    z = incoming.z;
                } else {
                    x += (incoming.x - x) * 0.20D;
                    y += (incoming.y - y) * 0.18D;
                    z += (incoming.z - z) * 0.20D;
                }
            }
            motionX = motionX * 0.72D + incoming.motionX * 0.28D;
            motionY = motionY * 0.72D + incoming.motionY * 0.28D;
            motionZ = motionZ * 0.72D + incoming.motionZ * 0.28D;
            float angularDelta = Math.abs(Mth.wrapDegrees(incoming.orbitDegrees - orbitDegrees));
            if (angularDelta > 90.0F) {
                orbitDegrees = Mth.rotLerp(0.10F, orbitDegrees, incoming.orbitDegrees);
            }
            damagePathWidth = Mth.lerp(0.35F, damagePathWidth, incoming.damagePathWidth);
            visibleFunnelWidth = Mth.lerp(0.35F, visibleFunnelWidth, incoming.visibleFunnelWidth);
            coreRadius = Mth.lerp(0.35F, coreRadius, incoming.coreRadius);
            radiusOfMaxWind = Mth.lerp(0.35F, radiusOfMaxWind, incoming.radiusOfMaxWind);
            touchedDown = touchedDown || incoming.touchedDown;
            absorbed = absorbed || incoming.absorbed;
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
                return new Vec3(x, fallbackY, z);
            }
            if (parent == null) {
                return new Vec3(0.0D, fallbackY, 0.0D);
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
