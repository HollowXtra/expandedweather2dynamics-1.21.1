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
    private static final float WEDGE_EXIT_WIDTH_BLOCKS = 1609.344F * 0.38F;
    private static final float WEDGE_FORCED_VISIBLE_CAP_BLOCKS = 1609.344F;
    /** How long a storm must have no funnel before its birth tick is forgotten (see
     * {@link #updateFunnelLifecycle}). */
    private static final long FUNNEL_ABSENT_RESET_TICKS = 100L;

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
    /**
     * Game tick this storm's funnel last began forming. The birth and rope-out animation runs on the
     * storm's own timeline because of it: anchoring the animation on the client instead meant every
     * world reload replayed the eight-second birth ramp, so a mature funnel came back thin - the
     * reported "dynamic size resets when I leave the world". Long.MIN_VALUE until a funnel forms.
     */
    private long funnelBirthTick = Long.MIN_VALUE;
    /**
     * Real-time length of this storm's genesis, in ticks, rolled once when the funnel first appears and
     * then persisted and synced so the server and every client run the identical formation timeline.
     * -1 until the roll happens / for a save written before the timeline existed, which keeps the old
     * fixed eight-second build-in.
     */
    private int genesisDurationTicks = -1;
    /** Game tick the funnel first went missing, so a brief stage flicker cannot re-anchor it. */
    private long funnelAbsentSinceTick = Long.MIN_VALUE;
    /**
     * Whether this client has ever applied a synced state for this storm. The smoothing in
     * {@link #loadClientSync} keeps the client's own values across syncs on purpose - letting a
     * packet overwrite them saw-tooths the funnel between server snapshots. But the very first
     * application has no local continuity to protect, only the client's defaults (a 12 block
     * visible funnel, a 24 block damage path), so that one adopts the server's values outright.
     * That is what makes a player who has just joined, or a storm that has just appeared, render
     * at its real width and filament phase on the first tick instead of easing up to them.
     */
    private boolean clientSyncApplied;
    private long blockDamageTick = -1L;
    private int blockDamageCount;
    private long lastAlertTick = -1_000_000L;
    private StormSubtype subtype = StormSubtype.NONE;
    private boolean rotationAssigned;
    private boolean antiCyclonic;
    private final List<SubVortex> subVortices = new ArrayList<>();
    private final List<HorizontalVortex> horizontalVortices = new ArrayList<>();
    /** Horizontal vortices live between these fractions of the funnel height (0 = ground, 1 = cloud base). */
    private static final float MIN_TENTACLE_HEIGHT_FRACTION = 0.26F;
    private static final float MAX_TENTACLE_HEIGHT_FRACTION = 0.96F;
    private final List<TornadoCirculationState> satelliteTornadoes = new ArrayList<>();
    private long nextSubVortexRetargetTick;
    private long nextSubVortexId = 1L;
    /** Game tick this multi-vortex episode began, so the episode can develop, peak and decay. */
    private long multiVortexEpisodeStartTick;
    /**
     * Bearing the episode's first vortex was placed at. Successive vortices fill in evenly around
     * it, which is what makes an episode read as one circulation with embedded vortices rather
     * than several unrelated columns.
     */
    private float multiVortexBaseBearing;
    /** Angular rate shared by every orbiting vortex this episode, in degrees per tick. */
    private float multiVortexOrbitSpeed = 5.6F;
    private long nextHorizontalVortexRetargetTick;
    private long nextHorizontalVortexId = 1L;
    private boolean multiVortexEpisodeActive;
    /**
     * How this episode wears its sub-vortices - lifted out of the way, working under a parent that
     * stops short of the ground, or turning inside a planted parent's base. Rolled once when the
     * episode starts and held for its whole length, so the tornado does not change type halfway
     * through: the parent's funnel visibly switching between planted and lifted mid-episode would
     * read as two different tornadoes.
     */
    private MultiVortexFlavour multiVortexFlavour = MultiVortexFlavour.LIFTED;
    /** How far above the ground this ground swarm's parent condensation gives up, as a layer fraction. */
    private float multiVortexTipFraction = 0.16F;
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
    /**
     * Precipitation character of this storm: 0 = bone-dry low-precipitation supercell,
     * 0.5 = classic, 1 = torrential high-precipitation supercell that rains its tornado
     * into invisibility. Negative until the storm has been scored once.
     */
    private float precipitationCharacter = -1.0F;
    /**
     * How much of this storm's precipitation evaporates before it lands, 0 = everything reaches the
     * ground, 1 = shafts that die just below the cloud base. Negative until first scored.
     */
    private float virgaStrength = -1.0F;
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
        // Order matters: the funnel lifecycle anchors the birth tick and rolls the genesis timeline, and
        // the wind gate below reads it. Retargeting the wind first let a storm snap to full stage
        // strength for the one tick before its timeline existed, and the smoothing then decayed down
        // from there - a tornado that started at full strength and formed in reverse.
        updateFunnelLifecycle(storm, gameTime);
        retargetLiveWind(level, storm, gameTime);
        enforceExclusiveVisualTags();
        mergeWidthBoost *= 0.985F;
        tickVisual(gameTime);
    }

    /**
     * Records when the funnel started forming, so its birth ramp is a property of the storm rather
     * than of whichever client happens to be watching it. The anchor is only forgotten after the
     * storm has spent a while with no funnel at all: re-anchoring on a stage flicker at the FORMING
     * boundary would replay the birth ramp on a mature funnel, which looks exactly like the size
     * resetting.
     */
    void updateFunnelLifecycle(StormObject storm, long gameTime) {
        if (storm == null) {
            return;
        }
        if (storm.isTornadoFormingOrGreater()) {
            funnelAbsentSinceTick = Long.MIN_VALUE;
            if (funnelBirthTick == Long.MIN_VALUE) {
                funnelBirthTick = gameTime;
                // Roll the formation timeline once, at birth, from the storm's stage right now: this is
                // the length the whole storm - server and every client - will form over.
                genesisDurationTicks = TornadoGenesis.rollDuration(stormId, storm.levelCurIntensityStage, gameTime);
            }
            return;
        }
        if (funnelAbsentSinceTick == Long.MIN_VALUE) {
            funnelAbsentSinceTick = gameTime;
        } else if (gameTime - funnelAbsentSinceTick > FUNNEL_ABSENT_RESET_TICKS) {
            funnelBirthTick = Long.MIN_VALUE;
            genesisDurationTicks = -1;
        }
    }

    public long funnelBirthTick() {
        return funnelBirthTick;
    }

    public int genesisDurationTicks() {
        return genesisDurationTicks;
    }

    /** 0 at funnel birth, 1 when the tornado is fully formed, and beyond 1 for the rest of its life. */
    public float genesisProgress(long gameTime) {
        return TornadoGenesis.progress(funnelBirthTick, gameTime, genesisDurationTicks);
    }

    /** Fraction of the storm's stage wind it may currently produce. 1 once it has finished forming. */
    public float genesisWindRamp(long gameTime) {
        return TornadoGenesis.windRamp(genesisProgress(gameTime));
    }

    /** Fraction of its mature funnel width the storm may currently show. */
    public float genesisWidthRamp(long gameTime) {
        return TornadoGenesis.widthRamp(genesisProgress(gameTime));
    }

    /** How far down the condensation has reached: 0 at the cloud base, 1 on the ground. */
    public float genesisCondensationReach(long gameTime) {
        return TornadoGenesis.condensationReach(genesisProgress(gameTime));
    }

    /**
     * Surface dust whirl strength, which turns before the condensation reaches the ground. Returns 0
     * for a storm with no funnel at all: a storm that has never formed one reads as "timeline
     * finished" (progress 1), and without this guard that would mean a full-strength dust whirl under
     * a storm that is not even tornadic.
     */
    public float genesisSurfaceWhirl(long gameTime) {
        if (funnelBirthTick == Long.MIN_VALUE) {
            return 0.0F;
        }
        return TornadoGenesis.surfaceWhirl(genesisProgress(gameTime));
    }

    public boolean genesisOnTheGround(long gameTime) {
        return TornadoGenesis.onTheGround(genesisProgress(gameTime));
    }

    /**
     * The small ground vortices alive before this storm's funnel touches down. Emptied by the model
     * whenever there is no timeline to read or the funnel is already on the ground.
     */
    public void collectGenesisWhirls(long gameTime, double motherRadius, List<GenesisWhirls.Whirl> out) {
        GenesisWhirls.collect(stormId, funnelBirthTick, gameTime, genesisDurationTicks, motherRadius, out);
    }

    void tickVisual(long gameTime) {
        subVortices.removeIf(vortex -> vortex.isExpired(gameTime));
        horizontalVortices.removeIf(vortex -> vortex.isExpired(gameTime));
        bendPhase += bendPhaseRate;
        trunkCurvePhase += 0.012F + bendPhaseRate * 0.55F;
        float rotationDirection = antiCyclonic ? -1.0F : 1.0F;
        for (SubVortex vortex : subVortices) {
            if (vortex.type().orbits()) {
                vortex.orbitDegrees = wrapDegrees(vortex.orbitDegrees + vortex.orbitSpeedDegrees * rotationDirection);
            }
            vortex.orbitRadius += (vortex.targetOrbitRadius - vortex.orbitRadius) * 0.035F;
            vortex.pulse += vortex.pulseRate;
        }
        for (HorizontalVortex vortex : horizontalVortices) {
            // Tentacles are anchored to the tornado at a FIXED bearing - they must NOT
            // orbit the funnel like sub-vortices. Like real horizontal vortices they
            // stick out of the tornado's side and only ride along with the parent
            // storm; the renderer's tube wave animates them so they writhe in place.
            vortex.orbitRadius += (vortex.targetOrbitRadius - vortex.orbitRadius) * 0.045F;
            // ...but they DO crawl along the wall: the height is derived from world time
            // (never accumulated) so client and server agree on every tick and a state
            // sync can't snap it. As the tentacle slides up the flaring wall its anchor
            // is pushed outward with it, so it visibly hugs and rides the taper.
            vortex.heightFraction = Mth.clamp(
                    vortex.spawnHeightFraction + vortex.heightDrift * (float) (gameTime - vortex.spawnTick),
                    MIN_TENTACLE_HEIGHT_FRACTION, MAX_TENTACLE_HEIGHT_FRACTION);
            vortex.pulse += vortex.pulseRate;
        }
        for (TornadoCirculationState satellite : satelliteTornadoes) {
            satellite.tickVisual(gameTime, rotationDirection);
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
        float maxDamageWidth = Math.max((float) Config.effectiveMaxDamageWidthBlocks(), WEDGE_ENTER_WIDTH_BLOCKS);
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

    void smooth(StormObject storm, long gameTime) {
        enforceExclusiveVisualTags();
        // Continuous sinuous heading drift instead of stepwise random retargets: tracks meander smoothly,
        // and weak/rope tornadoes dance harder than violent wedges, which track nearly straight.
        double phase = (stormId % 97L) * 0.271D;
        double slow = Math.sin(gameTime * 0.0016D + phase) * 0.45D;
        double medium = Math.sin(gameTime * 0.0043D + phase * 2.31D) * 0.28D;
        double fast = Math.sin(gameTime * 0.0120D + phase * 1.63D) * 0.10D;
        double curve = Math.sin(gameTime * 0.00011D + phase * 0.67D) * 0.17D;
        double wanderRange = Config.TORNADO_WANDER_MAX_DEGREES.get();
        double widthWeakness = Mth.clamp(1.0D - (damagePathWidth - 12.0D) / 450.0D, 0.0D, 1.0D);
        // The meander above is symmetric, so on its own it snakes. Real tracks arc: add the
        // deviant right veer, the slow asymmetric curvature and the multi-vortex scallop on top,
        // so the unsteered path still behaves like a real damage path.
        double trackOffset = TornadoTrackModel.headingOffsetDegrees(storm, gameTime);
        wanderAngle = (float) ((slow + medium + fast + curve) * wanderRange * (0.35D + 0.65D * widthWeakness) + trackOffset);
        // Genesis width gate: a forming funnel is a thin rope that thickens as it matures. Because the
        // damage swath, the core radius and the radius of maximum wind are all derived from these, the
        // early damage path is a narrow strip that widens with the funnel, then settles at its mature
        // width once the timeline finishes. The stored targets stay mature, so nothing is lost.
        float genesisWidth = genesisWidthRamp(gameTime);
        float targetWidth = Math.max(1.5F, targetVisibleFunnelWidth * genesisWidth);
        float targetDamageWidth = Math.max(3.0F, targetDamagePathWidth * genesisWidth);
        float widthSmoothing = Config.TORNADO_WIDTH_SMOOTHING.get().floatValue();
        widthMultiplier += (targetWidthMultiplier * genesisWidth - widthMultiplier) * widthSmoothing;
        damagePathWidth += (targetDamageWidth - damagePathWidth) * widthSmoothing;
        visibleFunnelWidth += (targetWidth - visibleFunnelWidth) * widthSmoothing;
        coreRadius = Math.max(2.0F, damagePathWidth * 0.16F);
        radiusOfMaxWind = Math.max(3.0F, damagePathWidth * 0.28F);
        smoothLiveWind(gameTime);
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
        // Genesis gate. This used to snap straight to the full stage value on the first tornadic tick,
        // which is what made a tornado arrive at full strength with only its pixels easing in. Now a
        // forming storm may produce only a fraction of its stage wind, so suction, scouring, block
        // pick-up, the damage swath, the radar signature and the alerts all build with the funnel.
        if (liveMaxWindMph <= 0.0F) {
            liveMaxWindMph = (float) (stageWind * genesisWindRamp(gameTime));
        }
        if (!Config.WINDFIELD_DYNAMIC_WIND_SPEEDS.get()) {
            targetLiveMaxWindMph = (float) stageWind;
            liveMaxWindMph = (float) (stageWind * genesisWindRamp(gameTime));
            return;
        }
        if (gameTime < nextLiveWindRetargetTick) {
            return;
        }
        targetLiveMaxWindMph = (float) TornadoWindField.estimateDynamicMaxWindMph(storm, level, this, gameTime);
        nextLiveWindRetargetTick = gameTime + nextIntInclusive(8, 26);
    }

    /**
     * Smooths the live maximum wind toward its target, gated by the genesis timeline. The gate lives
     * here as well as on the target because it is the last step before every consumer reads the value,
     * and because both sides run this with the same synced birth tick and timeline length, so a client
     * and the server always agree on how strong a forming tornado currently is.
     */
    private void smoothLiveWind(long gameTime) {
        float target = Math.max(0.0F, targetLiveMaxWindMph * genesisWindRamp(gameTime));
        if (target <= 0.0F && liveMaxWindMph <= 0.0F) {
            return;
        }
        if (!Config.WINDFIELD_DYNAMIC_WIND_SPEEDS.get()) {
            liveMaxWindMph = target;
            return;
        }
        float smoothing = Config.WINDFIELD_DYNAMIC_WIND_SMOOTHING.get().floatValue();
        liveMaxWindMph += (target - liveMaxWindMph) * smoothing;
        if (Math.abs(target - liveMaxWindMph) < 0.05F) {
            liveMaxWindMph = target;
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
        return tryConsumeBlockDamage(gameTime, null);
    }

    /**
     * Per-tick block damage budget. The base cap scales with tornado stage to
     * match Weather2's own attempt budget (its block loop grows 300 -> 750 per
     * tick from EF1 to EF5), so violent tornadoes rip far more than weak ones
     * instead of sharing the same flat cap.
     */
    public boolean tryConsumeBlockDamage(long gameTime, StormObject storm) {
        if (blockDamageTick != gameTime) {
            blockDamageTick = gameTime;
            blockDamageCount = 0;
        }
        int cap = Config.DAMAGE_MAX_BLOCKS_PER_STORM_PER_TICK.get();
        if (storm != null) {
            double factor;
            if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE5) {
                factor = 2.8D;
            } else if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE4) {
                factor = 2.1D;
            } else if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE3) {
                factor = 1.55D;
            } else if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE2) {
                factor = 1.2D;
            } else {
                factor = 1.0D;
            }
            cap = (int) Math.round(cap * factor);
        }
        if (blockDamageCount >= cap) {
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
            if (!multiVortexTagLocked && (sidewinderTagLocked || (funnelMorphologyLocked && !wedgeMorphologyActive()))) {
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
            multiVortexEpisodeStartTick = gameTime;
            multiVortexEpisodeEndTick = gameTime + nextIntInclusive(minEpisode, maxEpisode);
            // One shared angular rate and one base bearing for the whole episode. Real suction
            // vortices rotate with the parent circulation, so they keep their spacing and sweep
            // past a fixed point together instead of drifting through each other.
            multiVortexOrbitSpeed = 4.6F + random.nextFloat() * 4.4F;
            multiVortexBaseBearing = random.nextFloat() * 360.0F;
            // Decide the episode's flavour once, here, and hold it for its whole length. The three
            // options are drawn against one roll, so the chances read as shares of all episodes: the
            // ground swarm first (rarest), then the planted parent with vortices inside its base -
            // the ordinary multi-vortex tornado, where the parent is on the ground as usual - and
            // whatever is left over keeps the parent lifted out of the way entirely.
            double groundSwarmChance = Config.MULTIVORTEX_GROUND_SWARM_ENABLED.get()
                    ? Mth.clamp(Config.MULTIVORTEX_GROUND_SWARM_CHANCE.get(), 0.0D, 1.0D)
                    : 0.0D;
            multiVortexFlavour = MultiVortexFlavour.roll(
                    random.nextDouble(), groundSwarmChance, Config.MULTIVORTEX_PLANTED_SUCTION_CHANCE.get());
            float tip = (float) Mth.clamp(Config.MULTIVORTEX_GROUND_SWARM_TIP_HEIGHT.get(), 0.02D, 0.45D);
            multiVortexTipFraction = Mth.clamp(tip + (random.nextFloat() - 0.5F) * 0.10F, 0.02F, 0.45F);
            nextSubVortexRetargetTick = gameTime;
            clearSidewinderTag();
            clearNonWedgeFunnelMorphologyTag();
        }
        // A ground swarm's surface work is done entirely by its vortices, so it carries more of them
        // than the parent's own strength would call for. Capped by the same max count as any episode.
        if (groundSwarmActive() && Config.MULTIVORTEX_GROUND_SWARM_EXTRA_VORTICES.get() > 0) {
            int cap = Mth.clamp(Config.MULTIVORTEX_MAX_COUNT.get(), 0, 10);
            desiredCount = Math.min(cap, desiredCount + Config.MULTIVORTEX_GROUND_SWARM_EXTRA_VORTICES.get());
        }
        desiredCount = episodeRampedCount(gameTime, desiredCount);
        if (gameTime < nextSubVortexRetargetTick && subVortices.size() >= desiredCount) {
            return;
        }
        nextSubVortexRetargetTick = gameTime + nextIntInclusive(70, 170);
        int slot = subVortices.size();
        while (subVortices.size() < desiredCount) {
            subVortices.add(createSubVortex(gameTime, parentRadius, parentStage, parentStageIntensity, slot, desiredCount));
            slot++;
        }
        while (subVortices.size() > desiredCount) {
            // Retire whichever vortex is closest to its OWN expiry rather than the last in the list,
            // so a shrinking episode lets each vortex finish its life instead of cutting one off.
            retireClosestToExpiry(gameTime);
        }
        for (SubVortex vortex : subVortices) {
            // Each vortex drifts inside its OWN type's orbit band, so a peripheral vortex stays out
            // toward the edge of the damage path while an inner-core vortex stays pinned to the centre.
            SubVortexType.Rails rails = vortex.type().rails();
            float band = rails.maxOrbitFraction() - rails.minOrbitFraction();
            float fraction = rails.minOrbitFraction() + (0.20F + random.nextFloat() * 0.80F) * band;
            vortex.targetOrbitRadius = Math.max(2.0F, parentRadius * fraction);
        }
        enforceExclusiveVisualTags();
    }

    void updateHorizontalVortices(long gameTime, int desiredCount, float wallRadiusGround, float wallRadiusTop, boolean multiVortexActive) {
        horizontalVortices.removeIf(vortex -> vortex.isExpired(gameTime));
        if (desiredCount <= 0) {
            horizontalVortices.clear();
            nextHorizontalVortexRetargetTick = 0L;
            return;
        }
        desiredCount = Mth.clamp(desiredCount, 0, 10);
        if (gameTime >= nextHorizontalVortexRetargetTick || horizontalVortices.size() < desiredCount) {
            nextHorizontalVortexRetargetTick = gameTime + nextIntInclusive(45, multiVortexActive ? 115 : 155);
            while (horizontalVortices.size() < desiredCount) {
                horizontalVortices.add(createHorizontalVortex(gameTime, wallRadiusGround, wallRadiusTop, multiVortexActive));
            }
            while (horizontalVortices.size() > desiredCount) {
                horizontalVortices.remove(horizontalVortices.size() - 1);
            }
        }
        for (HorizontalVortex vortex : horizontalVortices) {
            // Keep the anchor on the ACTUAL funnel wall - and re-solve it EVERY tick, not only
            // on retarget, so the tentacle tracks the wall as it slides up or down the taper
            // and as the funnel flares, narrows and changes morphology. The per-tentacle hug
            // factor is fixed at spawn, so following the wall never jitters the radius.
            float wallRadius = Mth.lerp(vortex.heightFraction(), wallRadiusGround, wallRadiusTop);
            vortex.targetOrbitRadius = Math.max(2.0F, wallRadius * vortex.wallHugFactor());
        }
    }

    private void clearSubVortexEpisode() {
        subVortices.clear();
        multiVortexEpisodeActive = false;
        multiVortexTagLocked = false;
        multiVortexFlavour = MultiVortexFlavour.LIFTED;
        multiVortexEpisodeStartTick = 0L;
        multiVortexEpisodeEndTick = 0L;
        nextSubVortexRetargetTick = 0L;
    }

    /**
     * How many vortices the episode should hold right now. A real multi-vortex episode develops:
     * vortices appear over the first quarter of it, hold through the middle, and drop away as it
     * decays - instead of a flat count that pops in and out at full strength.
     */
    private int episodeRampedCount(long gameTime, int desiredCount) {
        if (!Config.SUBVORTEX_EPISODE_RAMP.get() || !multiVortexEpisodeActive || desiredCount <= 0) {
            return desiredCount;
        }
        // An episode restored from a save that predates the start tick has no ramp to apply.
        if (multiVortexEpisodeStartTick <= 0L || multiVortexEpisodeEndTick <= multiVortexEpisodeStartTick) {
            return desiredCount;
        }
        long length = multiVortexEpisodeEndTick - multiVortexEpisodeStartTick;
        float progress = Mth.clamp((gameTime - multiVortexEpisodeStartTick) / (float) length, 0.0F, 1.0F);
        float build = rampSmoothstep(progress / 0.25F);
        float decay = 1.0F - rampSmoothstep((progress - 0.62F) / 0.38F);
        float envelope = build * decay;
        // Never collapse to nothing mid-episode: at least one vortex circulates while it lasts.
        return Math.max(1, Math.round(desiredCount * (0.45F + 0.55F * envelope)));
    }

    private void retireClosestToExpiry(long gameTime) {
        int index = -1;
        long best = Long.MAX_VALUE;
        for (int i = 0; i < subVortices.size(); i++) {
            long remaining = subVortices.get(i).expireTick() - gameTime;
            if (remaining < best) {
                best = remaining;
                index = i;
            }
        }
        if (index >= 0) {
            subVortices.remove(index);
        }
    }

    private static float rampSmoothstep(float t) {
        float x = Mth.clamp(t, 0.0F, 1.0F);
        return x * x * (3.0F - 2.0F * x);
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

    /**
     * True while this storm is running a ground swarm: the parent's condensation only reaches toward
     * the surface and everything doing work on the ground is a sub-vortex. The config is re-checked
     * here rather than trusted from the roll, so turning the type off takes effect immediately instead
     * of waiting for the current episode to end.
     */
    public boolean groundSwarmActive() {
        return multiVortexFlavour == MultiVortexFlavour.GROUND_SWARM
                && multiVortexVisualActive()
                && Config.MULTIVORTEX_GROUND_SWARM_ENABLED.get();
    }

    /**
     * True while the parent funnel is planted on the ground as usual and the sub-vortices are turning
     * inside its base - the ordinary multi-vortex tornado. Nothing about the parent is suppressed in
     * this arrangement; it is only drawn back a touch at its very base. The config is re-checked here
     * for the same reason as the ground swarm's.
     */
    public boolean plantedVortexActive() {
        return multiVortexFlavour == MultiVortexFlavour.EMBEDDED
                && multiVortexVisualActive()
                && Config.MULTIVORTEX_PLANTED_SUCTION_CHANCE.get() > 0.0D;
    }

    /** How this episode is wearing its sub-vortices. LIFTED whenever no episode is running. */
    public MultiVortexFlavour multiVortexFlavour() {
        return multiVortexVisualActive() ? multiVortexFlavour : MultiVortexFlavour.LIFTED;
    }

    /** How far above the ground the parent condensation gives up, as a fraction of the funnel height. */
    public float parentTipFraction() {
        return multiVortexTipFraction;
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

    private void clearNonWedgeFunnelMorphologyTag() {
        if (!wedgeMorphologyActive()) {
            clearFunnelMorphologyTag();
            return;
        }
        targetFunnelMorphology = FunnelMorphology.WEDGE;
        if (funnelMorphology != FunnelMorphology.WEDGE) {
            funnelMorphology = FunnelMorphology.DEFAULT;
        }
        funnelMorphologyLocked = true;
        targetTrunkNeckFraction = 0.78F;
        trunkCurveStrength = 0.35F;
    }

    private void clearWedgeMorphologyTag() {
        funnelMorphologyLocked = false;
        if (targetFunnelMorphology == FunnelMorphology.WEDGE) {
            targetFunnelMorphology = FunnelMorphology.DEFAULT;
        }
        if (funnelMorphology == FunnelMorphology.WEDGE) {
            funnelMorphology = FunnelMorphology.DEFAULT;
        }
        if (targetFunnelMorphology == FunnelMorphology.DEFAULT && funnelMorphology == FunnelMorphology.DEFAULT) {
            funnelMorphologyBlend = 0.0F;
        }
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
        funnelMorphologyLocked = true;
        targetTrunkNeckFraction = 0.78F;
        trunkCurveStrength = 0.35F;
    }

    private SubVortex createSubVortex(long gameTime, float parentRadius, int parentStage, float parentStageIntensity, int slot, int slotCount) {
        SubVortexType type = pickSubVortexType(parentStage, parentStageIntensity);
        SubVortexType.Rails rails = type.rails();
        float orbitFraction = rails.minOrbitFraction() + random.nextFloat() * (rails.maxOrbitFraction() - rails.minOrbitFraction());
        float orbitRadius = Math.max(2.0F, parentRadius * orbitFraction);
        float orbitSpeed;
        if (Config.SUBVORTEX_SHARED_ORBIT_SPEED.get()) {
            // Shared episode rate scaled per type: the angular falloff with distance is what real
            // vortices show about the radius of maximum wind, so inner-core spots whip around while
            // peripheral and hanging funnels drift slowly.
            orbitSpeed = Math.max(0.6F, multiVortexOrbitSpeed * type.orbitSpeedFactor() * (0.93F + random.nextFloat() * 0.14F));
        } else {
            orbitSpeed = Mth.lerp(random.nextFloat(), rails.minOrbitSpeed(), rails.maxOrbitSpeed());
        }
        int life = nextIntInclusive(rails.minLife(), rails.maxLife());
        float size = Mth.lerp(random.nextFloat(), rails.minSize(), rails.maxSize());
        float intensity = Mth.lerp(random.nextFloat(), rails.minIntensity(), rails.maxIntensity());
        float spawnHeight = 0.0F;
        float descentRate = 0.0F;
        if (type.descends()) {
            // A breakdown bubble is born near the cloud base and falls the whole height of the
            // funnel over its life, so it visibly travels down the condensation funnel.
            spawnHeight = 0.86F + random.nextFloat() * 0.14F;
            descentRate = spawnHeight / Math.max(20.0F, life);
        }
        return new SubVortex(
                type,
                allocateSubVortexId(),
                gameTime,
                gameTime + life,
                spawnBearing(slot, slotCount),
                orbitSpeed,
                orbitRadius,
                orbitRadius,
                size,
                intensity,
                random.nextFloat() * (float) Math.PI * 2.0F,
                0.030F + random.nextFloat() * 0.052F,
                spawnHeight,
                descentRate
        );
    }

    /**
     * Weights the mix of vortex types for this tornado. Suction vortices are the baseline of any
     * multi-vortex episode; inner-core spots need a genuinely violent circulation, peripheral
     * vortices show up on wide (wedge) tornadoes, and a descending breakdown bubble is rare and
     * only ever one at a time.
     */
    private SubVortexType pickSubVortexType(int parentStage, float parentStageIntensity) {
        if (!Config.SUBVORTEX_TYPES_ENABLED.get()) {
            return SubVortexType.SUCTION;
        }
        float stageBlend = Mth.clamp((parentStage - StormObject.STATE_STAGE2) / (float) Math.max(1, StormObject.STATE_STAGE5 - StormObject.STATE_STAGE2), 0.0F, 1.0F);
        float intensity = Mth.clamp(parentStageIntensity, 0.0F, 1.0F);
        float suction = 1.0F;
        float core = 0.18F + intensity * 1.15F * (0.40F + stageBlend * 0.60F);
        float peripheral = 0.12F + stageBlend * 0.22F + (wedgeMorphologyActive() ? 0.85F : 0.0F);
        // A ground swarm is defined by its vortices being ON the ground, so the two types that live up
        // in the funnel - the hanging satellite funnel and the descending breakdown bubble - are out.
        // A column hanging beside the parent, or a pinch travelling down a parent that barely reaches
        // the ground, would both contradict what the storm is: everything here works the surface.
        boolean groundSwarm = groundSwarmActive();
        // A hanging satellite funnel is the detached column visible beside a violent parent, and
        // only ever one at a time - two would read as two parents rather than one circulation.
        boolean satelliteAvailable = !groundSwarm && Config.SUBVORTEX_SATELLITE_FUNNELS.get()
                && subVortices.stream().noneMatch(vortex -> vortex.type() == SubVortexType.SATELLITE_FUNNEL);
        float satellite = satelliteAvailable ? 0.10F + intensity * 0.34F * (0.45F + stageBlend * 0.55F) : 0.0F;
        // Dust whirls are the filler of weaker episodes: they scale up as intensity falls away. In a
        // ground swarm they are the signature, so they get the weight of the whole mix.
        float whirl = Config.SUBVORTEX_DUST_WHIRLS.get()
                ? (groundSwarm ? 1.05F : 0.18F + (1.0F - intensity) * 0.34F)
                : 0.0F;
        boolean breakdownAvailable = !groundSwarm && Config.SUBVORTEX_BREAKDOWN_ENABLED.get()
                && subVortices.stream().noneMatch(vortex -> vortex.type() == SubVortexType.BREAKDOWN);
        float breakdown = breakdownAvailable ? 0.08F + intensity * 0.34F : 0.0F;
        float roll = random.nextFloat() * (suction + core + satellite + peripheral + whirl + breakdown);
        if ((roll -= suction) < 0.0F) {
            return SubVortexType.SUCTION;
        }
        if ((roll -= core) < 0.0F) {
            return SubVortexType.CORE;
        }
        if ((roll -= satellite) < 0.0F) {
            return SubVortexType.SATELLITE_FUNNEL;
        }
        if ((roll -= peripheral) < 0.0F) {
            return SubVortexType.PERIPHERAL;
        }
        if ((roll -= whirl) < 0.0F) {
            return SubVortexType.DUST_WHIRL;
        }
        return SubVortexType.BREAKDOWN;
    }

    /**
     * Bearing a new vortex is born at. With even spacing on, the orbiting vortices are spread
     * around the parent rather than clustered, so an episode reads as a single circulation with
     * vortices embedded in it; new vortices drop into the gaps left by expired ones.
     */
    private float spawnBearing(int slot, int slotCount) {
        if (!Config.SUBVORTEX_EVEN_SPACING.get() || slotCount <= 0) {
            return random.nextFloat() * 360.0F;
        }
        float step = 360.0F / Math.max(1, slotCount);
        float jitter = (random.nextFloat() - 0.5F) * step * 0.35F;
        return wrapDegrees(multiVortexBaseBearing + slot * step + jitter);
    }

    /**
     * Radius multiplier the funnel gets from any descending vortex breakdown bubble (1 = untouched).
     * A breakdown chokes the circulation it passes through, which is what makes a violent tornado
     * suddenly pinch and narrow for a minute before it recovers.
     */
    public float funnelPinchAt(float layerFraction, long gameTime) {
        float pinch = 1.0F;
        for (SubVortex vortex : subVortices) {
            if (vortex.type() != SubVortexType.BREAKDOWN) {
                continue;
            }
            float center = vortex.heightFraction(gameTime);
            float delta = Math.abs(layerFraction - center);
            float width = 0.20F;
            if (delta >= width) {
                continue;
            }
            float depth = 1.0F - delta / width;
            float strength = Config.SUBVORTEX_BREAKDOWN_PINCH.get().floatValue() * (0.55F + vortex.intensityFraction() * 0.90F);
            pinch = Math.min(pinch, 1.0F - depth * strength);
        }
        return Mth.clamp(pinch, 0.35F, 1.0F);
    }

    private HorizontalVortex createHorizontalVortex(long gameTime, float wallRadiusGround, float wallRadiusTop, boolean multiVortexActive) {
        // Anchor the tentacle to the ACTUAL funnel wall: Weather2's funnel flares upward
        // (radiusOfBase + radiusIncreasePerLayer * layer), so the wall at the tentacle's
        // height is interpolated between the live base and top wall radii and the tube
        // base sits right on it with a small fixed outward hug.
        float spawnHeightFraction = 0.38F + random.nextFloat() * 0.40F;
        // Most tentacles ride UP the taper and curl up with it; the rest sink and droop,
        // which is what real horizontal vortices do as they tighten into the wall.
        float driftDirection = random.nextFloat() < 0.72F ? 1.0F : -1.0F;
        float heightDrift = driftDirection * (0.0010F + random.nextFloat() * 0.0018F);
        float wallHugFactor = 0.82F + random.nextFloat() * 0.26F;
        float wallRadius = Mth.lerp(spawnHeightFraction, wallRadiusGround, wallRadiusTop);
        float orbitRadius = Math.max(2.0F, wallRadius * wallHugFactor);
        int life = nextIntInclusive(55, multiVortexActive ? 190 : 145);
        // Some tubes TEAR OFF. At a randomised point in their life they lose their grip on the
        // wall and are flung clear of the tornado, drifting away as a free-roaming tube before
        // they die - what chaser footage actually shows horizontal vortices doing. A torn-off
        // tube gets extra life on top of its attached phase so the break-off is a visible event
        // rather than something that only happens in the last few ticks of its existence.
        float breakOffChance = Mth.clamp(Config.HORIZONTAL_VORTICES_BREAK_OFF_CHANCE.get().floatValue()
                * (multiVortexActive ? 1.25F : 1.0F), 0.0F, 1.0F);
        long detachTick = 0L;
        long freeTicks = 0L;
        if (random.nextFloat() < breakOffChance) {
            detachTick = gameTime + Math.round(life * (0.42F + random.nextFloat() * 0.34F));
            freeTicks = nextIntInclusive(Math.max(24, Config.HORIZONTAL_VORTICES_BREAK_OFF_FREE_TICKS.get() / 2),
                    Math.max(25, Config.HORIZONTAL_VORTICES_BREAK_OFF_FREE_TICKS.get()));
        }
        return new HorizontalVortex(
                allocateHorizontalVortexId(),
                gameTime,
                gameTime + life + freeTicks,
                detachTick,
                random.nextFloat() * 360.0F,
                3.4F + random.nextFloat() * (multiVortexActive ? 7.8F : 5.2F),
                orbitRadius,
                orbitRadius,
                0.40F + random.nextFloat() * (multiVortexActive ? 0.55F : 0.42F),
                0.085F + random.nextFloat() * 0.075F,
                spawnHeightFraction,
                heightDrift,
                wallHugFactor,
                0.28F + random.nextFloat() * (multiVortexActive ? 0.38F : 0.26F),
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
        for (TornadoCirculationState satellite : satelliteTornadoes) {
            satellite.tickServer(gameTime);
        }
        steerSatelliteTornadoes(parentPos, parentMotion);
        int activeLimit = Mth.clamp(desiredMax, 0, 4);
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
                false,
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

    /** Precipitation character (0 = LP/dry, 1 = HP/torrential), or -1 before it is first scored. */
    public float precipitationCharacter() {
        return precipitationCharacter;
    }

    public void setPrecipitationCharacter(float character) {
        this.precipitationCharacter = Mth.clamp(character, 0.0F, 1.0F);
    }

    /** Virga strength (0 = rain reaches the ground, 1 = evaporates just below the cloud), or -1 before it is first scored. */
    public float virgaStrength() {
        return virgaStrength;
    }

    public void setVirgaStrength(float strength) {
        this.virgaStrength = Mth.clamp(strength, 0.0F, 1.0F);
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
        tag.putString("MultiVortexFlavour", multiVortexFlavour.name());
        tag.putFloat("MultiVortexTipFraction", multiVortexTipFraction);
        tag.putBoolean("MultiVortexTagLocked", multiVortexTagLocked);
        tag.putLong("NextMultiVortexEpisodeAttemptTick", nextMultiVortexEpisodeAttemptTick);
        tag.putLong("MultiVortexEpisodeEndTick", multiVortexEpisodeEndTick);
        tag.putLong("MultiVortexEpisodeStartTick", multiVortexEpisodeStartTick);
        tag.putFloat("MultiVortexBaseBearing", multiVortexBaseBearing);
        tag.putFloat("MultiVortexOrbitSpeed", multiVortexOrbitSpeed);
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
        tag.putLong("FunnelBirthTick", funnelBirthTick);
        tag.putInt("GenesisDurationTicks", genesisDurationTicks);
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
        tag.putFloat("PrecipitationCharacter", precipitationCharacter);
        tag.putFloat("VirgaStrength", virgaStrength);
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
        // Older saves carried the ground swarm as a flag rather than a flavour; translate it so an
        // episode that is in flight across the update keeps the arrangement it was already showing.
        multiVortexFlavour = tag.contains("MultiVortexFlavour")
                ? MultiVortexFlavour.byName(tag.getString("MultiVortexFlavour"))
                : tag.getBoolean("MultiVortexGroundSwarm") ? MultiVortexFlavour.GROUND_SWARM : MultiVortexFlavour.LIFTED;
        multiVortexTipFraction = tag.contains("MultiVortexTipFraction") ? tag.getFloat("MultiVortexTipFraction") : multiVortexTipFraction;
        multiVortexTagLocked = tag.contains("MultiVortexTagLocked") ? tag.getBoolean("MultiVortexTagLocked") : multiVortexEpisodeActive;
        nextMultiVortexEpisodeAttemptTick = tag.contains("NextMultiVortexEpisodeAttemptTick") ? tag.getLong("NextMultiVortexEpisodeAttemptTick") : nextMultiVortexEpisodeAttemptTick;
        multiVortexEpisodeEndTick = tag.contains("MultiVortexEpisodeEndTick") ? tag.getLong("MultiVortexEpisodeEndTick") : multiVortexEpisodeEndTick;
        multiVortexEpisodeStartTick = tag.contains("MultiVortexEpisodeStartTick") ? tag.getLong("MultiVortexEpisodeStartTick") : multiVortexEpisodeStartTick;
        multiVortexBaseBearing = tag.contains("MultiVortexBaseBearing") ? tag.getFloat("MultiVortexBaseBearing") : multiVortexBaseBearing;
        multiVortexOrbitSpeed = tag.contains("MultiVortexOrbitSpeed") ? Math.max(0.5F, tag.getFloat("MultiVortexOrbitSpeed")) : multiVortexOrbitSpeed;
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
        funnelBirthTick = tag.contains("FunnelBirthTick") ? tag.getLong("FunnelBirthTick") : funnelBirthTick;
        genesisDurationTicks = tag.contains("GenesisDurationTicks") ? tag.getInt("GenesisDurationTicks") : genesisDurationTicks;
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
        precipitationCharacter = tag.contains("PrecipitationCharacter") ? tag.getFloat("PrecipitationCharacter") : precipitationCharacter;
        virgaStrength = tag.contains("VirgaStrength") ? tag.getFloat("VirgaStrength") : virgaStrength;
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
        if (multiVortexVisualActive()) {
            clearSidewinderTag();
            clearNonWedgeFunnelMorphologyTag();
            nextMorphologyRetargetTick = gameTime + nextIntInclusive(180, 420);
            return;
        }
        if (sidewinderVisualActive()) {
            clearNonWedgeFunnelMorphologyTag();
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
        boolean canAdditionalShapes = Config.TORNADO_ADDITIONAL_SHAPES_ENABLED.get()
                && Config.TORNADO_ADDITIONAL_SHAPES_CHANCE.get() > 0.0D
                && storm.levelCurIntensityStage >= StormObject.STATE_FORMING;
        if (!canElephantTrunk && !canDrillBit && !canFloater && !canAdditionalShapes) {
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
        if (selectedMorphology == FunnelMorphology.DEFAULT && canAdditionalShapes) {
            // additional shape pack: ropes favor weak narrow tornadoes, corkscrews
            // favor violent ones, stovepipes and bowls favor wider paths, cones
            // occur across the board
            float packChance = Math.min(1.0F, Config.TORNADO_ADDITIONAL_SHAPES_CHANCE.get().floatValue() * 0.55F);
            float weak = storm.levelCurIntensityStage <= StormObject.STATE_STAGE2 ? 1.0F : 0.45F;
            float violent = storm.levelCurIntensityStage >= StormObject.STATE_STAGE3 ? 1.0F : 0.5F;
            float wide = Mth.clamp(widthFraction * 1.15F, 0.30F, 1.35F);
            float narrow = Mth.clamp((1.0F - widthFraction) * 1.15F, 0.30F, 1.35F);
            float ropeW = 0.85F * weak * narrow;
            float coneW = 1.25F;
            float pipeW = 0.90F * wide;
            float corkW = 0.80F * violent * narrow;
            float bowlW = 0.70F * wide;
            float total = ropeW + coneW + pipeW + corkW + bowlW;
            if (random.nextFloat() < packChance) {
                float roll = random.nextFloat() * total;
                if (roll < ropeW) {
                    selectedMorphology = FunnelMorphology.ROPE;
                } else if (roll < ropeW + coneW) {
                    selectedMorphology = FunnelMorphology.CONE;
                } else if (roll < ropeW + coneW + pipeW) {
                    selectedMorphology = FunnelMorphology.STOVEPIPE;
                } else if (roll < ropeW + coneW + pipeW + corkW) {
                    selectedMorphology = FunnelMorphology.CORKSCREW;
                } else {
                    selectedMorphology = FunnelMorphology.BOWL;
                }
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
        } else if (targetFunnelMorphology == FunnelMorphology.ROPE) {
            funnelMorphologyLocked = true;
            clearSidewinderTag();
            targetTrunkNeckFraction = 0.55F + random.nextFloat() * 0.20F;
            trunkCurveStrength = Mth.clamp(1.10F + random.nextFloat() * 0.50F, 0.0F, 1.60F);
            float ropeWidth = Math.max(2.5F, Math.min(targetVisibleFunnelWidth, targetDamagePathWidth * (0.12F + random.nextFloat() * 0.06F)));
            targetVisibleFunnelWidth = ropeWidth;
            if (storm != null) {
                targetWidthMultiplier = ropeWidth / Math.max(3.0F, DynamicStormController.rawBaseRadiusForWind(storm));
            }
            nextMorphologyRetargetTick = gameTime + nextIntInclusive(400, 1000);
        } else if (targetFunnelMorphology == FunnelMorphology.CONE) {
            funnelMorphologyLocked = true;
            clearSidewinderTag();
            targetTrunkNeckFraction = 0.42F + random.nextFloat() * 0.16F;
            trunkCurveStrength = Mth.clamp(0.45F + random.nextFloat() * 0.25F, 0.0F, 0.90F);
            nextMorphologyRetargetTick = gameTime + nextIntInclusive(700, 1600);
        } else if (targetFunnelMorphology == FunnelMorphology.STOVEPIPE) {
            funnelMorphologyLocked = true;
            clearSidewinderTag();
            targetTrunkNeckFraction = 0.50F + random.nextFloat() * 0.14F;
            trunkCurveStrength = Mth.clamp(0.30F + random.nextFloat() * 0.22F, 0.0F, 0.70F);
            float pipeWidth = Math.max(6.0F, Math.min(targetVisibleFunnelWidth, targetDamagePathWidth * (0.50F + random.nextFloat() * 0.20F)));
            targetVisibleFunnelWidth = pipeWidth;
            if (storm != null) {
                targetWidthMultiplier = pipeWidth / Math.max(3.0F, DynamicStormController.rawBaseRadiusForWind(storm));
            }
            nextMorphologyRetargetTick = gameTime + nextIntInclusive(800, 1800);
        } else if (targetFunnelMorphology == FunnelMorphology.CORKSCREW) {
            funnelMorphologyLocked = true;
            clearSidewinderTag();
            targetTrunkNeckFraction = 0.40F + random.nextFloat() * 0.15F;
            trunkCurveStrength = Mth.clamp(0.85F + random.nextFloat() * 0.35F, 0.0F, 1.25F);
            float corkWidth = Math.max(3.0F, Math.min(targetVisibleFunnelWidth, targetDamagePathWidth * (0.20F + random.nextFloat() * 0.10F)));
            targetVisibleFunnelWidth = corkWidth;
            if (storm != null) {
                targetWidthMultiplier = corkWidth / Math.max(3.0F, DynamicStormController.rawBaseRadiusForWind(storm));
            }
            nextMorphologyRetargetTick = gameTime + nextIntInclusive(600, 1400);
        } else if (targetFunnelMorphology == FunnelMorphology.BOWL) {
            funnelMorphologyLocked = true;
            clearSidewinderTag();
            targetTrunkNeckFraction = 0.40F + random.nextFloat() * 0.14F;
            trunkCurveStrength = Mth.clamp(0.40F + random.nextFloat() * 0.25F, 0.0F, 0.85F);
            float bowlWidth = Math.max(6.0F, Math.min(targetVisibleFunnelWidth, targetDamagePathWidth * (0.48F + random.nextFloat() * 0.20F)));
            targetVisibleFunnelWidth = bowlWidth;
            if (storm != null) {
                targetWidthMultiplier = bowlWidth / Math.max(3.0F, DynamicStormController.rawBaseRadiusForWind(storm));
            }
            nextMorphologyRetargetTick = gameTime + nextIntInclusive(700, 1600);
        } else {
            nextMorphologyRetargetTick = gameTime + nextIntInclusive(180, 440);
        }
    }

    private boolean refreshWedgeMorphology(StormObject storm) {
        if (storm == null || storm.attrib_waterSpout || !storm.isTornadoFormingOrGreater()) {
            if (wedgeMorphologyActive()) {
                clearWedgeMorphologyTag();
            }
            return false;
        }
        float currentWidth = Math.max(damagePathWidth, targetDamagePathWidth);
        float threshold = wedgeMorphologyActive() ? WEDGE_EXIT_WIDTH_BLOCKS : WEDGE_ENTER_WIDTH_BLOCKS;
        if (currentWidth < threshold) {
            if (wedgeMorphologyActive()) {
                clearWedgeMorphologyTag();
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
        float maxDamageWidth = Math.max((float) Config.effectiveMaxDamageWidthBlocks(), WEDGE_ENTER_WIDTH_BLOCKS);
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
        // These animation values are accumulated or smoothed locally every tick. If a sync
        // packet overwrites them, the phase/ramp jumps back to the server's captured value
        // and saw-tooths until the next sync - which makes the funnel's top layer (the
        // largest bend/size lever) visibly jitter. Keep local continuity; the synced
        // TARGET values below still steer them toward server truth.
        // The one exception is the first sync a client ever applies: that is what defines the storm
        // for this client, so it takes the server's values as sent instead of easing up from defaults.
        boolean preserveLocal = clientSyncApplied;
        float localBendPhase = bendPhase;
        float localTrunkCurvePhase = trunkCurvePhase;
        float localBendAngle = bendAngle;
        float localBendStrength = bendStrength;
        float localSidewindingBendBlend = sidewindingBendBlend;
        float localFunnelMorphologyBlend = funnelMorphologyBlend;
        float localTrunkNeckFraction = trunkNeckFraction;
        float localWidthMultiplier = widthMultiplier;
        float localDamagePathWidth = damagePathWidth;
        float localVisibleFunnelWidth = visibleFunnelWidth;
        boolean preserveLocalSpin = preserveLocal && Config.TORNADO_WIND_SPEED_SPIN_ENABLED.get();
        float localVisualSpinSpeed = visualSpinSpeed;
        float localTargetVisualSpinSpeed = targetVisualSpinSpeed;
        load(tag, true);
        enforceExclusiveVisualTags();
        if (preserveLocal) {
            bendPhase = localBendPhase;
            trunkCurvePhase = localTrunkCurvePhase;
            bendAngle = localBendAngle;
            bendStrength = localBendStrength;
            sidewindingBendBlend = localSidewindingBendBlend;
            funnelMorphologyBlend = localFunnelMorphologyBlend;
            trunkNeckFraction = localTrunkNeckFraction;
            widthMultiplier = localWidthMultiplier;
            damagePathWidth = localDamagePathWidth;
            visibleFunnelWidth = localVisibleFunnelWidth;
        }
        if (preserveLocalSpin) {
            visualSpinSpeed = localVisualSpinSpeed;
            targetVisualSpinSpeed = localTargetVisualSpinSpeed;
        }
        clientSyncApplied = true;
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
        /** Height fraction at or below which a descending breakdown bubble counts as on the ground. */
        private static final float GROUND_CONTACT_HEIGHT = 0.22F;

        private final long id;
        private final long spawnTick;
        private final long expireTick;
        private final SubVortexType type;
        private float orbitDegrees;
        private float orbitSpeedDegrees;
        private float orbitRadius;
        private float targetOrbitRadius;
        private final float radiusFraction;
        private final float intensityFraction;
        private float pulse;
        private final float pulseRate;
        /** Height fraction (0 = ground, 1 = cloud base) a descending breakdown starts at. */
        private final float spawnHeightFraction;
        /** How much height a breakdown gives up per tick while it falls down the funnel. */
        private final float descentRate;

        private SubVortex(SubVortexType type, long id, long spawnTick, long expireTick, float orbitDegrees, float orbitSpeedDegrees, float orbitRadius, float targetOrbitRadius, float radiusFraction, float intensityFraction, float pulse, float pulseRate, float spawnHeightFraction, float descentRate) {
            this.type = type == null ? SubVortexType.SUCTION : type;
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
            this.spawnHeightFraction = spawnHeightFraction;
            this.descentRate = descentRate;
        }

        public SubVortexType type() {
            return type;
        }

        /**
         * Height of the vortex as a fraction of the funnel (0 = ground, 1 = cloud base). Only
         * breakdown bubbles leave the ground; every other type sits on it. Derived from world
         * time rather than accumulated, so client and server agree on every tick.
         */
        public float heightFraction(long gameTime) {
            float floor = type.minHeightFraction();
            if (!type.descends()) {
                // A vortex with a floor above 0 hangs aloft and never reaches the ground - that is
                // exactly what a satellite funnel does, and it is why it cannot scour or damage.
                return floor;
            }
            return Mth.clamp(spawnHeightFraction - descentRate * (gameTime - spawnTick), floor, 1.0F);
        }

        /** Whether this vortex is low enough to touch the ground and do damage. */
        public boolean isGroundContact(long gameTime) {
            return type.damagesGround() && heightFraction(gameTime) <= GROUND_CONTACT_HEIGHT;
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
            tag.putString("Type", type.name());
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
            tag.putFloat("SpawnHeightFraction", spawnHeightFraction);
            tag.putFloat("DescentRate", descentRate);
            return tag;
        }

        private static SubVortex load(CompoundTag tag) {
            float orbitRadius = Math.max(3.0F, tag.getFloat("OrbitRadius"));
            return new SubVortex(
                    tag.contains("Type") ? SubVortexType.byName(tag.getString("Type")) : SubVortexType.SUCTION,
                    tag.getLong("Id"),
                    tag.getLong("SpawnTick"),
                    tag.getLong("ExpireTick"),
                    tag.getFloat("OrbitDegrees"),
                    tag.getFloat("OrbitSpeedDegrees"),
                    orbitRadius,
                    tag.contains("TargetOrbitRadius") ? Math.max(3.0F, tag.getFloat("TargetOrbitRadius")) : orbitRadius,
                    Mth.clamp(tag.getFloat("RadiusFraction"), 0.10F, 1.10F),
                    Mth.clamp(tag.getFloat("IntensityFraction"), 0.05F, 1.0F),
                    tag.getFloat("Pulse"),
                    tag.contains("PulseRate") ? tag.getFloat("PulseRate") : 0.035F,
                    tag.contains("SpawnHeightFraction") ? tag.getFloat("SpawnHeightFraction") : 0.0F,
                    tag.contains("DescentRate") ? tag.getFloat("DescentRate") : 0.0F
            );
        }
    }

    public static final class HorizontalVortex {
        private final long id;
        private final long spawnTick;
        private final long expireTick;
        /** Tick this tube tears off the funnel wall, or 0 when it stays anchored for its whole life. */
        private final long detachTick;
        private float orbitDegrees;
        private final float orbitSpeedDegrees;
        private float orbitRadius;
        private float targetOrbitRadius;
        private final float lengthFraction;
        private final float radiusFraction;
        private final float spawnHeightFraction;
        private final float heightDrift;
        private final float wallHugFactor;
        private float heightFraction;
        private final float intensityFraction;
        private final float pitchDegrees;
        private float pulse;
        private final float pulseRate;

        private HorizontalVortex(long id, long spawnTick, long expireTick, long detachTick, float orbitDegrees, float orbitSpeedDegrees, float orbitRadius, float targetOrbitRadius, float lengthFraction, float radiusFraction, float spawnHeightFraction, float heightDrift, float wallHugFactor, float intensityFraction, float pitchDegrees, float pulse, float pulseRate) {
            this.id = id;
            this.spawnTick = spawnTick;
            this.expireTick = expireTick;
            this.detachTick = detachTick > 0L && detachTick < expireTick ? detachTick : 0L;
            this.orbitDegrees = orbitDegrees;
            this.orbitSpeedDegrees = orbitSpeedDegrees;
            this.orbitRadius = orbitRadius;
            this.targetOrbitRadius = targetOrbitRadius;
            this.lengthFraction = lengthFraction;
            this.radiusFraction = radiusFraction;
            this.spawnHeightFraction = spawnHeightFraction;
            this.heightDrift = heightDrift;
            this.wallHugFactor = wallHugFactor;
            this.heightFraction = spawnHeightFraction;
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

        /** How much of the wall radius the tube base is offset by; fixed per tentacle. */
        public float wallHugFactor() {
            return wallHugFactor;
        }

        /** Tick this tube tore off the wall, or 0 when it stays anchored for its whole life. */
        public long detachTick() {
            return detachTick;
        }

        /** True once the tube has ripped free of the wall and is drifting on its own. */
        public boolean isDetached(long gameTime) {
            return detachTick > 0L && gameTime >= detachTick;
        }

        /**
         * 0 while attached, ramping to 1 across the free drift. The renderer uses it to thin the
         * tube out and shed its debris as it disintegrates away from the tornado.
         */
        public float freeProgress(long gameTime) {
            if (detachTick <= 0L || gameTime < detachTick) {
                return 0.0F;
            }
            long freeLife = Math.max(1L, expireTick - detachTick);
            return Mth.clamp((float) (gameTime - detachTick) / (float) freeLife, 0.0F, 1.0F);
        }

        /** How hard the torn-off tube is thrown outward, as a fraction of the wall radius per tick. */
        public float freeOutwardDrift() {
            return 0.020F + hashUnit(0x7A1C9E37L) * 0.032F;
        }

        /** Blocks per tick a free tube sinks or rises; most sink slowly, a few lift. */
        public float freeVerticalDrift() {
            return (hashUnit(0x3B7D1A55L) - 0.62F) * 0.040F;
        }

        /** Degrees per tick the free tube tumbles as it is thrown; signed so they tumble both ways. */
        public float freeTumbleRate() {
            float direction = hashUnit(0x11C4F09BL) < 0.5F ? -1.0F : 1.0F;
            return direction * (0.30F + hashUnit(0x5E2A88C1L) * 0.80F);
        }

        /** Where the free tumble starts, so no two torn-off tubes swing in step. */
        public float freeTumblePhase() {
            return hashUnit(0x6D2F71A3L) * (float) Math.PI * 2.0F;
        }

        /**
         * How far the tube wraps around the funnel over its length, in degrees. Horizontal
         * vortices are tubes wrapping the funnel - sometimes right around the condensation
         * funnel - not straight arms poking out of it, so every tube gets its own wrap span.
         */
        public float wrapDegrees() {
            return 135.0F + hashUnit(0x51ED270BL) * 185.0F;
        }

        /** How much of the funnel's height the tube climbs while it wraps around it. */
        public float climbFraction() {
            return 0.08F + hashUnit(0x2F1B3C7DL) * 0.16F;
        }

        /** Stable per-tentacle random in [0,1) derived from its id, so both sides agree. */
        private float hashUnit(long salt) {
            long h = id * 0x9E3779B97F4A7C15L + salt;
            h ^= (h >>> 33);
            h *= 0xFF51AFD7ED558CCDL;
            h ^= (h >>> 29);
            h *= 0xC4CEB9FE1A85EC53L;
            h ^= (h >>> 32);
            return Math.floorMod(h, 10000L) / 10000.0F;
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
            tag.putFloat("SpawnHeightFraction", spawnHeightFraction);
            tag.putFloat("HeightDrift", heightDrift);
            tag.putLong("DetachTick", detachTick);
            tag.putFloat("WallHugFactor", wallHugFactor);
            tag.putFloat("HeightFraction", heightFraction);
            tag.putFloat("IntensityFraction", intensityFraction);
            tag.putFloat("PitchDegrees", pitchDegrees);
            tag.putFloat("Pulse", pulse);
            tag.putFloat("PulseRate", pulseRate);
            return tag;
        }

        private static HorizontalVortex load(CompoundTag tag) {
            float orbitRadius = Math.max(4.0F, tag.getFloat("OrbitRadius"));
            // Height fractions are fractions of the FUNNEL's height (0 = ground, 1 = cloud
            // base). Saves written before that change stored a small "height above base"
            // value (max ~0.11), so migrate those to a sane mid-funnel height instead of
            // dropping the tentacle onto the ground.
            float rawHeight = tag.contains("SpawnHeightFraction")
                    ? tag.getFloat("SpawnHeightFraction")
                    : tag.getFloat("HeightFraction");
            float spawnHeight = rawHeight < MIN_TENTACLE_HEIGHT_FRACTION - 0.05F
                    ? 0.60F
                    : Mth.clamp(rawHeight, MIN_TENTACLE_HEIGHT_FRACTION, MAX_TENTACLE_HEIGHT_FRACTION);
            return new HorizontalVortex(
                    tag.getLong("Id"),
                    tag.getLong("SpawnTick"),
                    tag.getLong("ExpireTick"),
                    tag.contains("DetachTick") ? tag.getLong("DetachTick") : 0L,
                    tag.getFloat("OrbitDegrees"),
                    tag.getFloat("OrbitSpeedDegrees"),
                    orbitRadius,
                    tag.contains("TargetOrbitRadius") ? Math.max(4.0F, tag.getFloat("TargetOrbitRadius")) : orbitRadius,
                    Mth.clamp(tag.getFloat("LengthFraction"), 0.24F, 1.00F),
                    Mth.clamp(tag.getFloat("RadiusFraction"), 0.060F, 0.20F),
                    spawnHeight,
                    Mth.clamp(tag.getFloat("HeightDrift"), -0.0032F, 0.0032F),
                    Mth.clamp(tag.contains("WallHugFactor") ? tag.getFloat("WallHugFactor") : 0.95F, 0.60F, 1.30F),
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
        private float orbitSpeedDegrees;
        private float orbitRadius;
        private float baseOrbitRadius;
        private float baseOrbitSpeedDegrees;
        private float damagePathWidth;
        private float visibleFunnelWidth;
        private float coreRadius;
        private float radiusOfMaxWind;
        private final float fullDamagePathWidth;
        private final float fullVisibleFunnelWidth;
        private final float windFraction;
        private float currentWindFraction;
        private float lifecycle;
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
            this.baseOrbitRadius = Math.max(6.0F, orbitRadius);
            this.baseOrbitSpeedDegrees = Math.max(0.6F, orbitSpeedDegrees);
            this.damagePathWidth = damagePathWidth;
            this.visibleFunnelWidth = visibleFunnelWidth;
            this.fullDamagePathWidth = damagePathWidth;
            this.fullVisibleFunnelWidth = visibleFunnelWidth;
            this.coreRadius = Math.max(2.0F, damagePathWidth * 0.16F);
            this.radiusOfMaxWind = Math.max(3.0F, damagePathWidth * 0.28F);
            this.windFraction = windFraction;
            this.currentWindFraction = windFraction;
            this.touchedDown = touchedDown;
            this.spawnTick = spawnTick;
            this.expireTick = expireTick;
        }

        private void tickVisual(long gameTime, float rotationDirection) {
            updateLifecycle(gameTime);
            // Kepler-ish spin-up as the orbit tightens, with a slow speed wobble for organic motion.
            float radiusScale = baseOrbitRadius / Math.max(4.0F, orbitRadius);
            float speedWobble = 0.88F + 0.12F * (float) Math.sin(gameTime * 0.021D + id * 1.31D);
            orbitSpeedDegrees = Mth.clamp(baseOrbitSpeedDegrees * (0.35F + 0.65F * radiusScale) * speedWobble, 0.6F, 14.0F);
            // Orbit breathes: radius wanders organically and tightens as the vortex decays into the parent.
            float wander = 0.82F + 0.18F * (float) Math.sin(gameTime * 0.013D + id * 1.7D);
            float decayScale = 0.16F + 0.84F * lifecycle;
            float targetRadius = Math.max(4.0F, baseOrbitRadius * wander * decayScale);
            orbitRadius += (targetRadius - orbitRadius) * 0.02F;
            orbitDegrees = wrapOrbit(orbitDegrees + orbitSpeedDegrees * rotationDirection);
            motionX *= 0.82D;
            motionY *= 0.78D;
            motionZ *= 0.82D;
        }

        private void tickServer(long gameTime) {
            updateLifecycle(gameTime);
            float scale = Math.max(0.25F, lifecycle);
            float targetDamage = Math.max(6.0F, fullDamagePathWidth * scale);
            float targetVisible = Math.max(3.0F, fullVisibleFunnelWidth * scale);
            damagePathWidth += (targetDamage - damagePathWidth) * 0.06F;
            visibleFunnelWidth += (targetVisible - visibleFunnelWidth) * 0.06F;
            coreRadius = Math.max(2.0F, damagePathWidth * 0.16F);
            radiusOfMaxWind = Math.max(3.0F, damagePathWidth * 0.28F);
            currentWindFraction = windFraction * Math.max(0.15F, lifecycle);
            if (!touchedDown && lifecycle > 0.85F) {
                touchedDown = true;
            }
        }

        private void updateLifecycle(long gameTime) {
            lifecycle = lifecycleAt(gameTime);
        }

        public float lifecycleAt(long gameTime) {
            long lifetime = Math.max(1L, expireTick - spawnTick);
            float formDuration = Math.min(Math.max(40.0F, lifetime * 0.14F), lifetime * 0.5F);
            float decayDuration = Math.min(Math.max(60.0F, lifetime * 0.18F), lifetime * 0.5F);
            float formPhase = Mth.clamp((float) (gameTime - spawnTick) / formDuration, 0.0F, 1.0F);
            float remaining = Math.max(0.0F, (float) (expireTick - gameTime));
            float decayPhase = 1.0F - Mth.clamp(remaining / decayDuration, 0.0F, 1.0F);
            return Mth.clamp(Math.min(0.30F + 0.70F * smoothstep(formPhase), 1.0F - smoothstep(decayPhase)), 0.0F, 1.0F);
        }

        private static float smoothstep(float t) {
            return t * t * (3.0F - 2.0F * t);
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
            orbitRadius = Mth.lerp(0.30F, orbitRadius, incoming.orbitRadius);
            orbitSpeedDegrees = Mth.lerp(0.30F, orbitSpeedDegrees, incoming.orbitSpeedDegrees);
            damagePathWidth = Mth.lerp(0.35F, damagePathWidth, incoming.damagePathWidth);
            visibleFunnelWidth = Mth.lerp(0.35F, visibleFunnelWidth, incoming.visibleFunnelWidth);
            coreRadius = Mth.lerp(0.35F, coreRadius, incoming.coreRadius);
            radiusOfMaxWind = Mth.lerp(0.35F, radiusOfMaxWind, incoming.radiusOfMaxWind);
            currentWindFraction = Mth.lerp(0.35F, currentWindFraction, incoming.currentWindFraction);
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
            return currentWindFraction;
        }

        public float fullDamagePathWidth() {
            return fullDamagePathWidth;
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
            tag.putFloat("BaseOrbitRadius", baseOrbitRadius);
            tag.putFloat("BaseOrbitSpeedDegrees", baseOrbitSpeedDegrees);
            tag.putFloat("DamagePathWidth", damagePathWidth);
            tag.putFloat("VisibleFunnelWidth", visibleFunnelWidth);
            tag.putFloat("FullDamagePathWidth", fullDamagePathWidth);
            tag.putFloat("FullVisibleFunnelWidth", fullVisibleFunnelWidth);
            tag.putFloat("CoreRadius", coreRadius);
            tag.putFloat("RadiusOfMaxWind", radiusOfMaxWind);
            tag.putFloat("WindFraction", windFraction);
            tag.putFloat("CurrentWindFraction", currentWindFraction);
            tag.putBoolean("TouchedDown", touchedDown);
            tag.putBoolean("Absorbed", absorbed);
            tag.putLong("SpawnTick", spawnTick);
            tag.putLong("ExpireTick", expireTick);
            return tag;
        }

        private static TornadoCirculationState load(CompoundTag tag) {
            float damageWidth = tag.contains("DamagePathWidth") ? tag.getFloat("DamagePathWidth") : Math.max(12.0F, tag.getFloat("OrbitRadius") * 0.55F);
            float visibleWidth = tag.contains("VisibleFunnelWidth") ? tag.getFloat("VisibleFunnelWidth") : Math.max(5.0F, damageWidth * Math.max(0.25F, tag.getFloat("WidthMultiplier")));
            float fullDamage = tag.contains("FullDamagePathWidth") ? tag.getFloat("FullDamagePathWidth") : damageWidth;
            float fullVisible = tag.contains("FullVisibleFunnelWidth") ? tag.getFloat("FullVisibleFunnelWidth") : visibleWidth;
            float orbitRadius = tag.getFloat("OrbitRadius");
            float orbitSpeed = tag.getFloat("OrbitSpeedDegrees");
            float baseOrbitRadius = tag.contains("BaseOrbitRadius") ? tag.getFloat("BaseOrbitRadius") : Math.max(6.0F, orbitRadius);
            float baseOrbitSpeed = tag.contains("BaseOrbitSpeedDegrees") ? tag.getFloat("BaseOrbitSpeedDegrees") : Math.max(0.6F, orbitSpeed);
            TornadoCirculationState state = new TornadoCirculationState(
                    tag.getLong("Id"),
                    tag.contains("X") ? tag.getDouble("X") : Double.NaN,
                    tag.contains("Y") ? tag.getDouble("Y") : Double.NaN,
                    tag.contains("Z") ? tag.getDouble("Z") : Double.NaN,
                    tag.getDouble("MotionX"),
                    tag.getDouble("MotionY"),
                    tag.getDouble("MotionZ"),
                    tag.getFloat("OrbitDegrees"),
                    orbitSpeed,
                    orbitRadius,
                    fullDamage,
                    fullVisible,
                    tag.getFloat("WindFraction"),
                    tag.contains("TouchedDown") ? tag.getBoolean("TouchedDown") : true,
                    tag.getLong("SpawnTick"),
                    tag.getLong("ExpireTick")
            );
            state.damagePathWidth = damageWidth;
            state.visibleFunnelWidth = visibleWidth;
            state.orbitRadius = orbitRadius;
            state.orbitSpeedDegrees = orbitSpeed;
            state.baseOrbitRadius = baseOrbitRadius;
            state.baseOrbitSpeedDegrees = baseOrbitSpeed;
            state.coreRadius = tag.contains("CoreRadius") ? tag.getFloat("CoreRadius") : Math.max(2.0F, damageWidth * 0.16F);
            state.radiusOfMaxWind = tag.contains("RadiusOfMaxWind") ? tag.getFloat("RadiusOfMaxWind") : Math.max(3.0F, damageWidth * 0.28F);
            state.currentWindFraction = tag.contains("CurrentWindFraction") ? tag.getFloat("CurrentWindFraction") : state.windFraction;
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
