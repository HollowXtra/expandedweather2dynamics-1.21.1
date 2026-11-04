package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

public final class DynamicStormState {
    private static final float WEDGE_ENTER_WIDTH_BLOCKS = 1609.344F * 0.5F;
    private static final float WEDGE_EXIT_WIDTH_BLOCKS = 1609.344F * 0.38F;
    /** A wedge may render up to three miles wide, matching the raised config ceilings. */
    private static final float WEDGE_FORCED_VISIBLE_CAP_BLOCKS = 1609.344F * 3.0F;
    /** How long a storm must have no funnel before its birth tick is forgotten (see
     * {@link #updateFunnelLifecycle}). */
    private static final long FUNNEL_ABSENT_RESET_TICKS = 100L;
    /** Three seconds between wind-driven rung steps: fast enough to watch, slow enough not to flap. */
    private static final long WIND_DRIVEN_STAGE_INTERVAL_TICKS = 60L;
    /** Ten seconds between air-mass readings: the air a tornado is standing in is not a per-tick value. */
    private static final long ENVIRONMENT_POTENTIAL_INTERVAL_TICKS = 200L;

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
    /** Current gust-train multiplier: 1 = sustained wind, higher in a gust, lower in a lull. */
    private float gustMultiplier = 1.0F;
    /** Slowly-tracked sustained wind, i.e. the live value with the gust train averaged out. */
    private float sustainedMaxWindMph;
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
    /**
     * The wedge decision for the current wide episode: rolled once when the storm crosses into the wedge
     * width, then held until it shrinks back below the exit width. Rolling on every retarget instead would
     * strobe the funnel, because the tag also sets the trunk profile - the storm would re-shape itself
     * every few hundred ticks.
     */
    private boolean wedgeEpisodeRolled;
    private boolean wedgeEpisodeIsWedge;
    private float funnelMorphologyBlend;
    private float trunkNeckFraction = 0.50F;
    private float targetTrunkNeckFraction = 0.50F;
    private float trunkCurvePhase;
    /**
     * How much of the gap to Weather2's real funnel layer count closes per tick. Weather2 derives the
     * count from the tornado's height and appends a layer as it grows, and every shape profile is read
     * against that count - so it is a moving denominator that re-shapes every ring at once. Gliding it
     * turns that step into the shape moving through the growth. 0.22 closes a one-layer step in about a
     * second, which is slow enough to read as the funnel widening and fast enough that the profile is
     * never visibly lagging the geometry it is drawn on.
     */
    private static final float LAYER_COUNT_GLIDE_RATE = 0.22F;
    /**
     * A count that moves by more than this in one tick is not growth - the funnel was replaced (the storm
     * re-formed, or another tornado took the storm object). There is no shape continuity to preserve
     * across that, so the glide snaps.
     */
    private static final float LAYER_COUNT_GLIDE_SNAP = 2.5F;
    /** Display smoothing only: never persisted or synced, and re-seeded from the real count. */
    private float glidedLayerCount;
    private long glidedLayerCountTick = Long.MIN_VALUE;
    private float trunkCurveStrength = 1.0F;
    /**
     * Where the trunk curve strength is heading. It eases toward it in {@code smooth()} like every other
     * trunk value, because setting it directly on a shape change snapped the funnel's own bend in one tick
     * when a shape ended - the same class of step the blend is eased to avoid.
     */
    private float targetTrunkCurveStrength = 1.0F;
    /**
     * True while one shape is easing OUT before another eases in. A shape change cannot be a straight
     * swap - the profile reads whichever shape is in force, so moving from one to the other in one tick
     * steps the funnel - so the outgoing shape keeps the profile while its blend falls to plain, and the
     * new shape takes over at zero and eases up from there.
     */
    private boolean morphologyHandoff;
    private long nextMorphologyRetargetTick;
    private float visualSpinSpeed = 0.055F;
    private float targetVisualSpinSpeed = 0.055F;
    private float liveMaxWindMph;
    /** The mph floor in force because this storm's condensation is sitting on the ground; 0 when none. */
    private float groundWindFloorMph;
    private float targetLiveMaxWindMph;
    private long nextLiveWindRetargetTick;
    /**
     * The air mass reading behind the dynamic wind, held for ten seconds at a time. The profile samples the
     * biome and the season at a position, which does not change between two wind retargets, and there can be
     * several tornadoes alive at once - so it is sampled on its own clock. Held per storm rather than in a
     * shared map, so two storms in two dimensions can never read each other's air.
     */
    private float environmentPotentialMph;
    private long nextEnvironmentPotentialTick;
    /**
     * Game tick the wind-driven stage check may run again. The rung follows the wind at human pace - one
     * step every few seconds - rather than on every wind retarget, because a rung is a classification and
     * everything that reads it reads it as one: the radar signature, the rainfall rate, the fear.
     */
    private long nextWindDrivenStageTick;
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
    /**
     * When the parent first became too weak for the satellites it already has, or 0 while it can keep them.
     * The count has to stay short for a grace period before anything is retired, because the wind that
     * decides it is dynamic and dips for seconds at a time.
     */
    private long satelliteWeakSinceTick;
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
    /**
     * Whether THIS storm carries horizontal vortices, and whether that has been decided yet. Real
     * tornadoes are not uniform about them - only a minority of funnels throw off the low-level
     * horizontal vortices that read as tentacles - so the trait is rolled once, the first time the
     * storm is strong enough to have them, and then held for the storm's whole life the way its
     * multi-vortex flavour and funnel morphology are. A tornado that grew tentacles and then lost
     * them would read as two different storms, which is exactly what rolling it every tick did.
     */
    private boolean horizontalVortexCapable;
    private boolean horizontalVortexRolled;
    private boolean multiVortexEpisodeActive;
    /**
     * How this episode wears its sub-vortices - the parent's base pulled up off the surface, working
     * under a parent that stops short of the ground, or turning inside a planted parent's base. Rolled
     * once when the episode starts and held for its whole length, so the tornado does not change type
     * halfway through: the parent's funnel visibly switching between planted and lifted mid-episode
     * would read as two different tornadoes.
     */
    private MultiVortexFlavour multiVortexFlavour = MultiVortexFlavour.LIFTED;
    /** How far above the ground this ground swarm's parent condensation gives up, as a layer fraction. */
    private float multiVortexTipFraction = 0.16F;
    /**
     * How far up a lifted episode's parent condensation retracts, as a layer fraction, before the
     * episode's own ramp is applied. Rolled and held with the flavour; the live retraction is
     * {@link #parentLiftFraction(long)}, which also brings it in and back out over the episode.
     */
    private float multiVortexLiftFraction = 0.28F;
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
    /**
     * Rungs this storm has earned past the maximum it rolled at birth.
     *
     * <p>Persisted, not merely remembered, and deliberately so: it is the only thing bounding how far a
     * monster can grow, so a reload in the middle of a strengthening tornado must not hand it a fresh
     * allowance to climb another two rungs.
     */
    private int strengtheningRungs;
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

    /**
     * The effective wind gate: the genesis timeline unified with Weather2's stage ladder. The two
     * clocks advance independently - the ladder is Weather2's and races up within a couple of
     * minutes, while our timeline paces the formation over real minutes - and nothing tied them
     * together, so a storm whose gauge already read 7/8 could still be wind-gated at 50% of its
     * stage base by a young timeline. The compromise: the timeline fully owns the early rungs
     * (FORMING through STAGE1), but once the ladder shows the storm has climbed, the gate can only
     * deepen the wind, never hold it far below what the label promises.
     *
     * <p>Rung semantics matter here: Weather2's STAGE1 is rung <b>5</b>, not rung 1, so "rung 5" is
     * the moment a tornado touches down rather than a mature storm. One thing is therefore never
     * negotiable at any rung: a funnel the player can watch sitting on the ground is not dead air.
     * Once the condensation has docked with the surface whirl, the timeline may still deepen the
     * wind but it cannot park a visibly grounded tornado at its organizing-phase fraction - which
     * is what produced the "20 mph" reading on a funnel that was already down.
     */
    public float effectiveWindRamp(StormObject storm, long gameTime) {
        // The genesis timeline is now the sole authority on how much of its stage wind a tornado may
        // produce. This used to be lifted by the storm's rung (0.45 at STAGE2, 0.72 at STAGE3 and
        // above) and by the funnel merely being on the ground (0.70), and because Weather2's ladder
        // races up within a couple of minutes those floors carried a young storm straight past 150
        // mph the moment its label said so. A storm no longer gets to blow near its class just for
        // wearing it: the wind climbs with the funnel the player is watching.
        //
        // What replaces the grounded floor is absolute, not a fraction, and lives in
        // groundContactWindFloorMph: 65 mph (the bottom of the EF scale), clamped to the storm's own
        // stage so a world that configures weak tornadoes keeps them weak. That keeps a funnel visibly
        // on the ground off dead air without scaling with a 321 mph rung.
        return genesisWindRamp(gameTime);
    }

    /**
     * The absolute wind floor for a tornado whose condensation is on the ground, or 0 when none is due.
     *
     * <p>The genesis gate is a <em>fraction</em> of the storm's estimated wind and the estimate itself
     * can be low - a young storm still on Weather2's FORMING rung estimates near its stage floor - so the
     * two multiplied together let a grounded funnel read in the twenties. A fraction cannot fix that:
     * 70% of a weak estimate is still weak. Once the condensation has docked with the surface the vortex
     * <em>is</em> a tornado, and the bottom of the published EF scale is where a tornado begins, so the
     * guarantee has to be an absolute number of miles per hour rather than another fraction.
     *
     * <p>The floor is the bottom of the published EF scale and nothing more. It used to be raised to the
     * storm's own rung's floor - the rung below's ceiling, and so exactly the value the wind-driven stage
     * promotes at. Floor and promotion threshold were the same number, and because the ladder read the
     * floored value back, each held the other in place: a grounded TOR1 sat on 70 for its whole life while
     * the funnel the player was watching grew, and the forecast display never moved. A floor is a promise
     * that a funnel visibly on the ground is not dead air, and the EF0 bottom keeps that promise on its
     * own; the ladder is judged from the storm's own estimate - never from the floor - which is what
     * <em>lets</em> a grounded tornado's number climb with the tornado. Nothing is capped by this: the air
     * mass still carries a tornado past its band, and this only ever lifts the bottom.
     *
     * <p>Three exemptions, all deliberate. A storm that is already decaying is allowed to die quietly; a
     * ground swarm's parent is calm on purpose, since everything doing work down there is a sub-vortex
     * and flooring the parent would erase the whole arrangement; and a storm past its peak keeps the old
     * flat floor, because a tornado roping out is walked down the ladder by Weather2 and holding it at
     * its own class would leave a dying vortex reading its full rank. The floor also never exceeds the
     * storm's own stage ceiling, so a player who configures weak tornadoes keeps them weak.
     */
    private float groundContactWindFloorMph(StormObject storm, long gameTime) {
        // A wind, so it carries the strength dial like every other one the mod reads off a storm: the floor is
        // compared against the rung's ceiling a few lines down and then against the live wind, and a raw floor
        // under a dialed ceiling would mean a world at half strength keeps its full-strength lull floor while a
        // world at double strength keeps half of the one it asked for.
        double floor = Config.windMph(Config.WINDFIELD_GROUND_CONTACT_FLOOR_MPH.get());
        if (floor <= 0.0D || storm == null || funnelBirthTick == Long.MIN_VALUE) {
            return 0.0F;
        }
        if (groundSwarmActive() || isAbsorbedOrDecaying() || !genesisVisuallyOnTheGround(gameTime)) {
            return 0.0F;
        }
        double ceiling = TornadoWindField.stageMaxWindMph(storm);
        if (ceiling <= 0.0D) {
            return 0.0F;
        }
        // The configured EF0 bottom alone, clamped to the storm's own rung so a player who configures weak
        // tornadoes keeps them weak. It deliberately no longer climbs to the rung's own floor: that value
        // is the rung below's ceiling, which is exactly where the wind-driven stage promotes, so a floor
        // there held the reading on the promotion threshold and the rung it granted held the floor right
        // back - the forecast number never moved. The estimate carries the class; this floor only keeps a
        // funnel visibly on the ground off the dead-air bottom of the scale.
        return (float) Math.max(0.0D, Math.min(floor, ceiling));
    }

    /** The mph floor currently in force because the funnel is on the ground, or 0 when none is. */
    public float groundWindFloorMph() {
        return groundWindFloorMph;
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
     * Whether the funnel the player can see is on the ground. This is the renderer's own threshold, not
     * the timeline's touchdown phase, so the wind gate and the pixels agree about the single most
     * important question a tornado asks: is it down yet?
     */
    public boolean genesisVisuallyOnTheGround(long gameTime) {
        return TornadoGenesis.visuallyOnTheGround(genesisProgress(gameTime));
    }

    /**
     * The small ground vortices alive before this storm's funnel touches down. Emptied by the model
     * whenever there is no timeline to read or the funnel is already on the ground.
     */
    public void collectGenesisWhirls(long gameTime, double motherRadius, List<GenesisWhirls.Whirl> out) {
        GenesisWhirls.collect(stormId, funnelBirthTick, gameTime, genesisDurationTicks, motherRadius, out);
    }

    /**
     * The layer count the funnel's shape profiles should be read against right now, glided toward the
     * real one so a layer appearing or disappearing re-shapes the column over a few ticks instead of
     * snapping every ring in a single tick. Advanced at most once per tick no matter how many layers ask.
     */
    float glideLayerCount(int realCount, long gameTime) {
        float target = Math.max(1, realCount);
        if (glidedLayerCount <= 0.0F || gameTime == Long.MIN_VALUE
                || Math.abs(target - glidedLayerCount) > LAYER_COUNT_GLIDE_SNAP) {
            glidedLayerCount = target;
            glidedLayerCountTick = gameTime;
            return glidedLayerCount;
        }
        if (glidedLayerCountTick != gameTime) {
            glidedLayerCountTick = gameTime;
            glidedLayerCount += (target - glidedLayerCount) * LAYER_COUNT_GLIDE_RATE;
            if (Math.abs(target - glidedLayerCount) < 0.02F) {
                glidedLayerCount = target;
            }
        }
        return glidedLayerCount;
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

    /** How many satellites the parent is actually keeping, not counting the ones already retiring. */
    private int liveSatellites() {
        int live = 0;
        for (TornadoCirculationState satellite : satelliteTornadoes) {
            if (!satellite.retiring()) {
                live++;
            }
        }
        return live;
    }

    /**
     * Takes back the satellites the parent can no longer keep, by ending their own lives rather than
     * deleting them.
     *
     * <p>This used to be a clear: every satellite the parent had was dropped the instant the allowed count
     * fell, which made a satellite's life a function of its parent's wind on one particular tick instead of
     * the life it was born with. The wind that decides the count is the storm's dynamic wind, so it dips
     * below a rung for seconds at a time as a matter of course, and each dip emptied the sky. A parent now
     * has to stay too weak for {@link VortexLife#CULL_GRACE_TICKS} before anything is retired, and the ones
     * that go past the limit are the ones closest to their own end, given
     * {@link VortexLife#RETIRE_FADE_TICKS} to thin out and spiral in - the death they were going to have,
     * just sooner.
     */
    private void retireUnwantedSatellites(long gameTime, int activeLimit, boolean parentGone) {
        List<TornadoCirculationState> keepable = new ArrayList<>();
        for (TornadoCirculationState satellite : satelliteTornadoes) {
            if (!satellite.retiring()) {
                keepable.add(satellite);
            }
        }
        VortexLife.Cull cull = VortexLife.cull(keepable.size(), activeLimit, parentGone, satelliteWeakSinceTick,
                gameTime, VortexLife.CULL_GRACE_TICKS);
        satelliteWeakSinceTick = cull.weakSinceTick();
        if (cull.retireCount() <= 0) {
            return;
        }
        keepable.sort(Comparator.comparingLong(satellite -> satellite.expireTick() - gameTime));
        for (int i = 0; i < cull.retireCount() && i < keepable.size(); i++) {
            keepable.get(i).retire(gameTime, VortexLife.RETIRE_FADE_TICKS);
        }
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
            preserveWideWidthTargets(storm);
            nextWidthRetargetTick = gameTime + nextIntInclusive(minTicks, maxTicks);
            return;
        }

        float stage = Mth.clamp((storm.levelCurIntensityStage - StormObject.STATE_FORMING) / 5.0F, 0.0F, 1.0F);
        BlockPos pos = BlockPos.containing(storm.pos);
        float temperature = level.getBiome(pos).value().getBaseTemperature();
        float warmth = Mth.clamp((temperature + 0.15F) / 1.45F, 0.0F, 1.0F);
        boolean overWater = DynamicStormController.isOverWaterOrOcean(level, storm.pos.x, storm.pos.z);
        boolean raining = storm.isPrecipitating();
        float moisture = overWater || raining ? 1.0F : 0.45F;
        float terrainPenalty = terrainRoughnessPenalty(level, pos);
        float environment = Mth.clamp(0.55F + warmth * 0.17F + moisture * 0.18F - terrainPenalty, 0.35F, 1.15F);
        // The width itself. With the size lifecycle on this is a position on a geometric ladder that the
        // storm's own temperament, life arc and environment decide (see TornadoSizeModel) - a property of
        // the storm rather than a fresh roll of the dice - and the small spread here only keeps two
        // retargets of a storm holding still from being the same number twice.
        //
        // With it off this is the old memoryless draw: a fresh width every retarget from the stage ladder
        // alone, biased small by the power curve, which is why a storm could be a rope at one update and a
        // wedge at the next.
        float damageTarget;
        if (Config.TORNADO_SIZE_LIFECYCLE.get()) {
            double bone = Config.TORNADO_SIZE_TEMPERAMENT.get()
                    ? TornadoSizeModel.bone(stormId)
                    : random.nextDouble();
            double temperament = TornadoSizeModel.temperament(bone, Config.TORNADO_SIZE_TEMPERAMENT_SKEW.get());
            double arc = TornadoSizeModel.lifeArc(stage, Mth.clamp(storm.levelCurStagesIntensity, 0.0F, 1.0F),
                    sizeContracting(storm), Config.TORNADO_SIZE_ROPE_OUT.get());
            double reach = TornadoSizeModel.reach(stage, temperament, arc, environment);
            float spread = 0.92F + random.nextFloat() * 0.16F;
            damageTarget = (float) TornadoSizeModel.ladder(reach, minDamageWidth, maxDamageWidth) * spread;
        } else {
            float stageCeiling = Mth.lerp((float) Math.pow(stage, 1.2D), minDamageWidth * 2.0F, maxDamageWidth);
            float stageFloor = Mth.lerp(stage, minDamageWidth, Math.min(maxDamageWidth * 0.42F, minDamageWidth + 90.0F));
            float draw = (float) Math.pow(random.nextFloat(), 1.65D);
            damageTarget = Mth.lerp(draw, stageFloor, stageCeiling) * environment;
        }
        targetDamagePathWidth = Mth.clamp(damageTarget + mergeWidthBoost, minDamageWidth, maxDamageWidth);

        // How much of that swath the condensation funnel itself covers. From the air when the setting is
        // on - a dryline tornado and a rain-wrapped one can scour the same ground, and only the moisture
        // separates the thin funnel from the wall of cloud - and a flat random roll when it is off.
        float visibleMin = Config.TORNADO_VISIBLE_FUNNEL_FRACTION_MIN.get().floatValue();
        float visibleMax = Config.TORNADO_VISIBLE_FUNNEL_FRACTION_MAX.get().floatValue();
        float visibleFraction = Config.TORNADO_VISIBLE_FRACTION_FROM_MOISTURE.get()
                ? (float) TornadoSizeModel.visibleShare(
                        TornadoSizeModel.hydration(temperature, raining, overWater), visibleMin, visibleMax)
                : visibleMin + random.nextFloat() * Math.max(0.0F, visibleMax - visibleMin);
        targetVisibleFunnelWidth = Mth.clamp(targetDamagePathWidth * visibleFraction, 3.0F, (float) Config.effectiveMaxVisibleFunnelWidthBlocks());
        targetVisibleFunnelWidth = Math.max(3.0F, targetVisibleFunnelWidth);
        targetWidthMultiplier = targetVisibleFunnelWidth / Math.max(3.0F, DynamicStormController.rawBaseRadiusForWind(storm));
        preserveWideWidthTargets(storm);
        nextWidthRetargetTick = gameTime + nextIntInclusive(minTicks, maxTicks);
    }

    /**
     * Whether the funnel is past its peak and giving width back, which is the one moment a wide tornado is
     * allowed to contract. Every width floor below exists to stop a storm shrinking at random when nothing
     * about it changed; holding one through a rope-out would pin a dying wedge at half a mile until it
     * vanished instead of letting it visibly come apart.
     */
    private boolean sizeContracting(StormObject storm) {
        if (storm == null || !Config.TORNADO_SIZE_LIFECYCLE.get()) {
            return false;
        }
        float stage = Mth.clamp((storm.levelCurIntensityStage - StormObject.STATE_FORMING) / 5.0F, 0.0F, 1.0F);
        return stage >= 0.35F && (isAbsorbedOrDecaying() || (storm.hasStormPeaked && !storm.isGrowing));
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
        // Width surges on the visible funnel, folded into the easing TARGET so they run through the
        // exponential smoothly instead of fighting it. It is a pure function of world time and storm id,
        // so the server and every client compute the identical surge with no extra syncing, and it cannot
        // reset on a reload. The damage path, and so the wind field and scouring, keeps its calm target
        // and does not move with the surge at all.
        //
        // A train of HELD levels rather than a wave: the funnel arrives at a width, keeps it for the
        // better part of a minute, and later moves to another one (see TornadoWidthSurge). The version
        // this replaced was three summed sines folded through a sine, and no fold could stop that being
        // periodic - the width swelled and shrank on a beat, on every storm at once, which is the pulsing
        // this removes. Every level is hashed from the storm's own id, so no two funnels surge together,
        // and because the levels never repeat there is no rhythm for the eye to lock onto. The rendered
        // size the funnel's own layers are scaled by is this same width, so the top ring - the widest ring
        // of a flared shape, and so the one that showed the pulse most - holds with it.
        double turbulence = Config.TORNADO_WIDTH_TURBULENCE.get().doubleValue();
        double turb = TornadoWidthSurge.value(stormId, gameTime) * turbulence;
        // Weak rope funnels churn hardest; a violent wedge stays stately.
        double turbScale = 0.75D + 0.75D * widthWeakness;
        // The surge ceiling has to allow for a wide tornado. One is deliberately permitted a visible funnel
        // wider than the ordinary configured maximum, up to three miles, and its target already carries
        // that widening - clamping to the plain config cap here squashed it flat. This keys off the WIDTH
        // and not the wedge tag, because the tag is now only one of the shapes a wide tornado can take: a
        // wide cone, barrel or multiple-vortex needs exactly the same headroom, and leaving them out meant a
        // mile-wide damage path with a needle standing over it.
        float ceiling = wideTornado()
                ? Math.max((float) Config.effectiveMaxVisibleFunnelWidthBlocks(), WEDGE_FORCED_VISIBLE_CAP_BLOCKS)
                : (float) Config.effectiveMaxVisibleFunnelWidthBlocks();
        float boundTarget = Mth.clamp((float) (targetWidth * (1.0D + turb * turbScale)), 1.5F, ceiling);
        float widthSmoothing = Config.TORNADO_WIDTH_SMOOTHING.get().floatValue();
        widthMultiplier += (targetWidthMultiplier * genesisWidth - widthMultiplier) * widthSmoothing;
        damagePathWidth += (targetDamageWidth - damagePathWidth) * widthSmoothing;
        coreRadius = Math.max(2.0F, damagePathWidth * 0.16F);
        visibleFunnelWidth += (boundTarget - visibleFunnelWidth) * widthSmoothing;
        // The eased value is deliberately NOT clamped to its target envelope: it has to stay free to
        // travel down from a wide target, and clamping it would snap it instantly on a downward retarget
        // - exactly the reset the easing exists to avoid. The renderer clamps what it actually draws.
        visibleFunnelWidth = Math.max(1.5F, visibleFunnelWidth);
        // The radius of maximum wind used to hang off the damage-path width, not the funnel the
        // player actually sees. Damage path covers several times the visible funnel on big storms, so
        // tying the RMW to it pushed the wind peak far outside the visible wall - at the wall the        // Rankine ramp was still climbing, and the storm read as a stage 7/8 tornado with no wind.
        // The RMW is now the funnel's own wall radius: half the visible width, so the peak wind ring
        // sits exactly where the condensation meets the inflow and everything the player can reach
        // rides the decaying outer branch. The damage width only sanity-caps the anchor.
        float rmwAnchor = Mth.clamp(visibleFunnelWidth * 0.5F, 3.0F, Math.max(3.0F, damagePathWidth * 0.9F));
        radiusOfMaxWind = rmwAnchor;
        groundWindFloorMph = groundContactWindFloorMph(storm, gameTime);
        smoothLiveWind(storm, gameTime);
        // Gust state advance: retargetLiveWind eases the SUSTAINED value; the gust multiplier is a
        // deterministic read of the event train, advanced here once per tick on both server and
        // client (smooth runs on both sides), so every consumer sees the same gust without any
        // extra syncing. A ground swarm's deliberately calm parent reads no gusts.
        if (Config.WINDFIELD_DYNAMIC_GUST_FRONTS.get() && !groundSwarmActive() && liveMaxWindMph > 0.05F) {
            float rawSustained = (float) TornadoGustModel.sustainedMph(liveMaxWindMph, stormId, gameTime);
            if (sustainedMaxWindMph <= 0.05F) {
                sustainedMaxWindMph = rawSustained;
            } else {
                sustainedMaxWindMph += (rawSustained - sustainedMaxWindMph) * 0.10F;
            }
            gustMultiplier = (float) TornadoGustModel.multiplier(stormId, gameTime);
        } else {
            if (liveMaxWindMph <= 0.05F) {
                sustainedMaxWindMph = 0.0F;
            }
            gustMultiplier = 1.0F;
        }
        float bendSmoothing = Math.max(0.085F, Config.TORNADO_FUNNEL_BEND_SMOOTHING.get().floatValue());
        float bendDelta = wrapDegreesSigned(targetBendAngle - bendAngle);
        bendAngle = wrapDegrees(bendAngle + bendDelta * bendSmoothing);
        bendStrength += (targetBendStrength - bendStrength) * bendSmoothing;
        sidewindingBendBlend += (targetSidewindingBendBlend - sidewindingBendBlend) * 0.040F;
        // Shape transition, all of it in one place.
        //
        // The shape in force is latched as soon as its blend is non-zero and released only once the blend
        // is back to zero, so a shape is always eased out over the same transition it eased in over. A tag
        // used to drop it on the spot instead, and since the profile reads whichever shape is in force,
        // that snapped the whole column back to plain between two ticks - worst at the top of a flared
        // shape, because the flare is the largest scale the profile has: the ring at the cloud base swelled
        // past every layer under it and then reset.
        //
        // A change from one shape to another is a handoff through plain rather than a swap, because there
        // is only one blend and it fades a shape against plain: the outgoing shape holds the profile while
        // its blend falls, then the new one takes over at zero. Without that, a bowl that became a wedge
        // stepped from one profile to the other in a single tick.
        float targetMorphBlend = targetFunnelMorphology.shaped() && !morphologyHandoff ? 1.0F : 0.0F;
        float transitionStep = 1.0F / Math.max(1, Math.min(Config.TORNADO_ELEPHANT_TRUNK_TRANSITION_TICKS.get(), 55));
        if (funnelMorphologyBlend < targetMorphBlend) {
            funnelMorphologyBlend = Math.min(targetMorphBlend, funnelMorphologyBlend + transitionStep);
        } else if (funnelMorphologyBlend > targetMorphBlend) {
            funnelMorphologyBlend = Math.max(targetMorphBlend, funnelMorphologyBlend - transitionStep);
        }
        trunkNeckFraction += (targetTrunkNeckFraction - trunkNeckFraction) * 0.045F;
        trunkCurveStrength += (targetTrunkCurveStrength - trunkCurveStrength) * 0.045F;
        if (morphologyHandoff) {
            if (funnelMorphologyBlend <= 0.001F) {
                morphologyHandoff = false;
                funnelMorphology = targetFunnelMorphology;
            }
        } else if (targetFunnelMorphology.shaped() && funnelMorphology.shaped()
                && targetFunnelMorphology != funnelMorphology && funnelMorphologyBlend > 0.001F) {
            morphologyHandoff = true;
        } else if (funnelMorphologyBlend > 0.001F && targetFunnelMorphology.shaped()) {
            funnelMorphology = targetFunnelMorphology;
        } else if (funnelMorphologyBlend <= 0.001F && !funnelMorphologyLocked) {
            funnelMorphology = FunnelMorphology.DEFAULT;
        }
    }

    void updateVisualSpin(double maxWindMph) {
        if (!Config.TORNADO_WIND_SPEED_SPIN_ENABLED.get()) {
            return;
        }
        float min = Math.min(Config.TORNADO_WIND_SPEED_SPIN_MIN.get().floatValue(), Config.TORNADO_WIND_SPEED_SPIN_MAX.get().floatValue());
        float max = Math.max(Config.TORNADO_WIND_SPEED_SPIN_MIN.get().floatValue(), Config.TORNADO_WIND_SPEED_SPIN_MAX.get().floatValue());
        // The reference winds are the storm's own winds and go through the same dial, so the spin stays a
        // function of what the storm IS rather than of the setting: unscaled references against a dialed wind
        // would make every tornado in a half-strength world turn like its weakest third, and every tornado in a
        // doubled one turn like a wedge.
        float refMin = (float) Config.windMph(Math.min(Config.TORNADO_WIND_SPEED_SPIN_REFERENCE_MIN_MPH.get(), Config.TORNADO_WIND_SPEED_SPIN_REFERENCE_MAX_MPH.get()));
        float refMax = (float) Config.windMph(Math.max(Config.TORNADO_WIND_SPEED_SPIN_REFERENCE_MIN_MPH.get(), Config.TORNADO_WIND_SPEED_SPIN_REFERENCE_MAX_MPH.get()));
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
            liveMaxWindMph = (float) (stageWind * effectiveWindRamp(storm, gameTime));
        }
        if (!Config.WINDFIELD_DYNAMIC_WIND_SPEEDS.get()) {
            targetLiveMaxWindMph = (float) stageWind;
            liveMaxWindMph = (float) (stageWind * effectiveWindRamp(storm, gameTime));
            return;
        }
        updateWindDrivenStage(storm, gameTime);
        if (gameTime < nextLiveWindRetargetTick) {
            return;
        }
        targetLiveMaxWindMph = (float) TornadoWindField.estimateDynamicMaxWindMph(storm, level, this, gameTime);
        nextLiveWindRetargetTick = gameTime + nextIntInclusive(8, 26);
    }

    /**
     * The fastest wind this storm's air can support, cached per storm for ten seconds.
     *
     * <p>Only the server can read a profile - it needs a real level - so the client gets 0 and the wind
     * estimate falls back to the band alone there. That is not a problem: the wind target is computed and
     * smoothed on the server and synced, so a client never has to work this out for itself.
     */
    float environmentPotentialMph(StormObject storm, Level level, long gameTime) {
        if (!Config.WINDFIELD_ENVIRONMENT_WIND.get()) {
            return 0.0F;
        }
        if (gameTime < nextEnvironmentPotentialTick) {
            return environmentPotentialMph;
        }
        nextEnvironmentPotentialTick = gameTime + ENVIRONMENT_POTENTIAL_INTERVAL_TICKS;
        environmentPotentialMph = (float) TornadoWindField.environmentPotentialMph(storm, level);
        return environmentPotentialMph;
    }

    /**
     * Lets the storm's own rung follow the wind it is actually making.
     *
     * <p>The rung and the wind used to be one decision made twice: the outlook rolled a maximum stage, and
     * the wind was then bracketed inside that stage's band, so the label was always right by construction
     * and never learned anything from what the tornado did. With the wind free of the band that is no
     * longer true - the air mass can carry a stage-1 storm past the top of the ladder's first band - and a
     * storm wearing a rung its own wind has left behind is the exact contradiction players report. So the
     * stage is raised to the one the wind has earned, and its ceilings with it so the ladder does not fight
     * the promotion on the next tick.
     *
     * <p>Four rules keep it honest. It only ever promotes: Weather2 drains intensity after the peak and
     * steps the stage back down itself, so demotion here would be two systems arguing. It skips anything
     * whose stage is not an estimate of the wind - a commanded stage from the command, a pet, a weather
     * machine. It skips the rungs that have their own published ceilings (landspouts, gustnadoes, devils,
     * funnel clouds) and the water ladders. And it reads the SUSTAINED wind, not the gust train, so a
     * single two-second gust cannot rewrite the storm's class.
     */
    private void updateWindDrivenStage(StormObject storm, long gameTime) {
        if (!Config.WINDFIELD_WIND_DRIVEN_STAGE.get()) {
            return;
        }
        // The storm's own estimate, deliberately NOT the live value the grounded floor is applied to. The
        // floor exists so a lull under a funnel on the ground does not read as calm; letting the ladder
        // read through it meant the floor could grant its own rung back, which is how a grounded tornado
        // stayed on the bottom of its class for its whole life while its number sat still. The estimate is
        // the sustained wind - the gusts ride on top of it at read time - so a single gust still cannot
        // rewrite the storm's class.
        double reading = Math.max(0.0D, targetLiveMaxWindMph * effectiveWindRamp(storm, gameTime));
        if (reading <= 0.05D) {
            return;
        }
        if (storm.weatherMachineControlled || storm.isPlayerControlled() || storm.isPet()) {
            return;
        }
        if (storm.attrib_waterSpout || storm.stormType == StormObject.TYPE_WATER
                || isAbsorbedOrDecaying() || !DynamicStormController.isDynamicVortex(storm)) {
            return;
        }
        if (subtype == StormSubtype.LANDSPOUT || subtype == StormSubtype.GUSTNADO
                || subtype == StormSubtype.DUST_DEVIL || subtype == StormSubtype.SNOW_DEVIL
                || subtype == StormSubtype.FUNNEL_CLOUD) {
            return;
        }
        if (gameTime < nextWindDrivenStageTick) {
            return;
        }
        nextWindDrivenStageTick = gameTime + WIND_DRIVEN_STAGE_INTERVAL_TICKS;
        int earned = TornadoWindField.earnedTornadoStage(reading);
        if (earned <= storm.levelCurIntensityStage) {
            return;
        }
        storm.levelCurIntensityStage = earned;
        storm.maxIntensityStage = Math.max(storm.maxIntensityStage, earned);
        storm.levelStormIntensityMax = Math.max(storm.levelStormIntensityMax, earned);
        if (storm.manager instanceof WeatherManagerServer manager) {
            manager.syncStormUpdate(storm);
        }
    }

    /**
     * Smooths the live maximum wind toward its target, gated by the genesis timeline. The gate lives
     * here as well as on the target because it is the last step before every consumer reads the value,
     * and because both sides run this with the same synced birth tick and timeline length, so a client
     * and the server always agree on how strong a forming tornado currently is.
     */
    private void smoothLiveWind(StormObject storm, long gameTime) {
        float target = Math.max(0.0F, targetLiveMaxWindMph * effectiveWindRamp(storm, gameTime));
        // A funnel the player can watch sitting on the ground is a tornado, and a tornado does not
        // read like a stiff breeze. The floor rides on the eased TARGET - not on the value after
        // the ease - so a touchdown still spins up smoothly over a second or two, and because this
        // is the last step before every consumer reads liveMaxWindMph, the label, the HUD, the
        // radar, the probe, the sirens and the damage gates all see the same floored number.
        if (groundWindFloorMph > 0.0F) {
            target = Math.max(target, groundWindFloorMph);
        }
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
        // Coming up to the on-ground floor is bounded rather than left to the exponential ease, which
        // would leave the reading under EF0 for a couple of seconds after touchdown. A twentieth of the
        // floor per tick reaches it in at most one second: a spin-up the player can watch, never a pop,
        // and never a grounded tornado parked below the bottom of the scale once that second is up.
        if (groundWindFloorMph > 0.0F && liveMaxWindMph < groundWindFloorMph) {
            liveMaxWindMph = Math.min(groundWindFloorMph, liveMaxWindMph + groundWindFloorMph / 20.0F);
        }
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

    /** Gust-train multiplier advanced in smooth(): 1 = sustained, higher in a gust, lower in a lull. */
    public float gustMultiplier() {
        return gustMultiplier;
    }

    /** Sustained (gust-averaged) maximum wind, as eased in smooth(); 0 while no live wind. */
    public float sustainedMaxWindMph() {
        return sustainedMaxWindMph;
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

    /**
     * The shape the funnel profile is actually drawn from right now: the target while it is shaped, unless
     * a handoff is running - then it is the outgoing shape, which holds the profile while its blend eases
     * back to plain before the new shape takes over. Every consumer of the profile reads this instead of
     * picking between the two fields itself, so nothing can be drawing a different shape from the rest of
     * the funnel during a transition.
     */
    public FunnelMorphology funnelMorphologyInForce() {
        if (morphologyHandoff) {
            return funnelMorphology;
        }
        return targetFunnelMorphology.shaped() ? targetFunnelMorphology : funnelMorphology;
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
     *
     * <p>With {@code widthScaledBudget} on, the cap ALSO scales with the damage path's width:
     * a monster wedge's footprint is a town, and a budget tuned for a 24-block rope would take
     * real time to clear it block by block even at full violence. The width term is the square
     * root of the width ratio (area growth, sub-linear so a 3-mile storm tops out near 6x, not
     * the naive 200x), so the ladder reads: rope 1x, ~350-block stovepipe ~2.4x, mile-wide ~5x,
     * 3-mile capped at 6x - multiplied with the stage factor.</p>
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
            if (Config.DAMAGE_WIDTH_SCALED_BUDGET.get()) {
                // 24 blocks is the default starting damage path: a rope.
                factor *= Mth.clamp(Math.sqrt(Math.max(1.0D, damagePathWidth) / 24.0D), 1.0D, 6.0D);
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

    void updateSubVortices(long gameTime, int desiredCount, float parentRadius, int parentStage, float parentStageIntensity, boolean holdEpisode) {
        subVortices.removeIf(vortex -> vortex.isExpired(gameTime));
        if (desiredCount <= 0) {
            if (holdEpisode && multiVortexEpisodeActive && gameTime < multiVortexEpisodeEndTick) {
                // The parent's stage breathed below the episode floor mid-episode. Weather2's ladder
                // oscillates with intensity, and clearing here used to kill the whole arrangement -
                // vortices gone and, during a ground swarm whose parent is deliberately a faint
                // culled rope, the tornado visually gone - until a fresh roll passed minutes later.
                // Episodes are held for their whole length by design, so a live one now holds at a
                // single survivor vortex until the stage recovers or the episode ends on schedule.
                if (subVortices.isEmpty()) {
                    subVortices.add(createSubVortex(gameTime, parentRadius, parentStage, parentStageIntensity, 0, 1));
                }
                return;
            }
            clearSubVortexEpisode();
            return;
        }
        if (!multiVortexEpisodeActive || gameTime >= multiVortexEpisodeEndTick) {
            subVortices.clear();
            multiVortexEpisodeActive = false;
            multiVortexEpisodeEndTick = 0L;
            // A hold call exists only to keep an already-running episode alive through a stage dip;
            // it must never START one, or a stage-dipped tornado could roll an episode that the
            // stage gate exists to prevent.
            if (holdEpisode) {
                return;
            }
            // A locked funnel shape or a live sidewinder is deliberately NOT a veto on starting an episode,
            // even though the shape pack and the multi-vortex episode are exclusive looks. The exclusion is
            // settled by the episode itself: starting one clears both tags a few lines below, and the shape
            // eases back out through plain rather than being cut. Gating on them here instead dead-ended the
            // whole feature - a non-wedge shape is held for the storm's life and nothing ever releases it, so
            // any tornado that had rolled a cone, a rope or a barrel could never start an episode at all, and
            // the clearing below could never run for the one case it exists for. A wedge is not special-cased
            // either way: it was never vetoed before, and it still is not.
            if (!multiVortexTagLocked && gameTime < nextMultiVortexEpisodeAttemptTick) {
                return;
            }
            int minAttempt = Math.min(Config.MULTIVORTEX_MIN_ATTEMPT_INTERVAL_TICKS.get(), Config.MULTIVORTEX_MAX_ATTEMPT_INTERVAL_TICKS.get());
            int maxAttempt = Math.max(Config.MULTIVORTEX_MIN_ATTEMPT_INTERVAL_TICKS.get(), Config.MULTIVORTEX_MAX_ATTEMPT_INTERVAL_TICKS.get());
            nextMultiVortexEpisodeAttemptTick = gameTime + nextIntInclusive(minAttempt, maxAttempt);
            int effectiveMinStage = Math.max(Config.MULTIVORTEX_MIN_STAGE.get(), StormObject.STATE_STAGE2);
            float stageBlend = Mth.clamp((parentStage - effectiveMinStage) / (float) Math.max(1, StormObject.STATE_STAGE5 - effectiveMinStage), 0.0F, 1.0F);
            float intensityBlend = Mth.clamp(parentStageIntensity, 0.0F, 1.0F);
            // The 0.42 ceiling exists to keep the DEFAULT base chance rare - stage and intensity
            // bonuses should never turn 0.07 into something common. A user who raises the base
            // above the ceiling wants that frequency, so the ceiling never limits the base itself:
            // only the automatic bonuses are capped.
            double base = Config.MULTIVORTEX_EPISODE_CHANCE.get();
            double chance = Mth.clamp(base + stageBlend * 0.10D + Math.max(0.0F, intensityBlend - 0.55F) * 0.10D, 0.0D, Math.max(0.42D, base));
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
            // How far a lifted base pulls up, rolled per episode the same way. It is rolled for every
            // episode rather than only the lifted ones so a save that resumes mid-episode restores the
            // same retraction whichever way the episode came out.
            float lift = (float) Mth.clamp(Config.MULTIVORTEX_LIFTED_TIP_HEIGHT.get(), 0.0D,
                    MultiVortexParentProfile.MAX_LIFTED_TIP);
            multiVortexLiftFraction = Mth.clamp(lift + (random.nextFloat() - 0.5F) * 0.12F, 0.0F,
                    MultiVortexParentProfile.MAX_LIFTED_TIP);
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
        if (subVortices.size() > desiredCount) {
            // One per retarget rather than the whole excess at once: a shrinking episode thins out over the
            // seconds a player is watching it instead of losing four vortices between two frames. Retiring
            // picks whichever vortex is closest to its OWN expiry rather than the last in the list, so each
            // one finishes the life it was born with.
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

    /**
     * Whether this tornado is one of the ones that carries horizontal vortices. The trait is rolled
     * the first time the storm is strong enough to grow them and then held, so a funnel either has
     * tentacles for its whole life or never has them. The odds rise with how violent the storm is at
     * the moment it rolls, which for a storm climbing the stage ladder is its bottom rung - so the
     * config value reads as the share of tornadoes, and only storms that arrive already violent
     * actually use the higher end of the scale.
     *
     * <p>The config is still re-read each tick, so the two extreme settings keep working as live
     * switches the way the ground swarm's does: 0 takes horizontal vortices off every storm
     * immediately, 1 gives them to every storm.</p>
     *
     * <p>Only ever called from the server tick - the tubes reach clients through the synced state - so
     * the roll cannot be drawn twice from one storm.</p>
     */
    public boolean horizontalVorticesAllowed(int stage, float stageIntensity) {
        double configured = Config.HORIZONTAL_VORTICES_STORM_CHANCE.get();
        if (configured <= 0.0D) {
            return false;
        }
        if (!horizontalVortexRolled) {
            horizontalVortexRolled = true;
            int minStage = Math.max(Config.HORIZONTAL_VORTICES_MIN_STAGE.get(), StormObject.STATE_STAGE1);
            float stageBlend = Mth.clamp((stage - minStage) / (float) Math.max(1, StormObject.STATE_STAGE5 - minStage), 0.0F, 1.0F);
            float intensityBlend = Mth.clamp(stageIntensity, 0.0F, 1.0F);
            double chance = Mth.clamp(configured + stageBlend * 0.20D + Math.max(0.0F, intensityBlend - 0.60F) * 0.20D, 0.0D, 1.0D);
            horizontalVortexCapable = random.nextDouble() < chance;
        }
        return configured >= 1.0D || horizontalVortexCapable;
    }

    /**
     * True once this storm has been decided to be a carrier of horizontal vortices. Read-only; the
     * roll itself is {@link #horizontalVorticesAllowed(int, float)}.
     */
    public boolean horizontalVortexCapable() {
        return horizontalVortexCapable;
    }

    private void clearSubVortexEpisode() {
        subVortices.clear();
        multiVortexEpisodeActive = false;
        multiVortexTagLocked = false;
        multiVortexFlavour = MultiVortexFlavour.LIFTED;
        multiVortexLiftFraction = 0.28F;
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
        float envelope = episodeEnvelope(gameTime);
        // Never collapse to nothing mid-episode: at least one vortex circulates while it lasts.
        return Math.max(1, Math.round(desiredCount * (0.45F + 0.55F * envelope)));
    }

    /**
     * How firmly the episode is currently set up: 0 outside it, 1 through its middle, built in over its
     * first quarter and released over its last third.
     *
     * <p>Deliberately not gated on {@code SUBVORTEX_EPISODE_RAMP}: that switch is about how many
     * vortices the episode carries, while this envelope also drives the lifted base's retraction. A
     * tornado whose whole foot is deleted the instant an episode starts and snapped back on the instant
     * it ends would pop on screen regardless of how many vortices it is wearing, so the geometry always
     * ramps. An episode restored from a save that predates the start tick has no timeline to ramp on and
     * counts as fully set up.
     */
    private float episodeEnvelope(long gameTime) {
        if (!multiVortexEpisodeActive || multiVortexEpisodeStartTick <= 0L
                || multiVortexEpisodeEndTick <= multiVortexEpisodeStartTick) {
            return 1.0F;
        }
        long length = multiVortexEpisodeEndTick - multiVortexEpisodeStartTick;
        float progress = Mth.clamp((gameTime - multiVortexEpisodeStartTick) / (float) length, 0.0F, 1.0F);
        float build = rampSmoothstep(progress / 0.25F);
        float decay = 1.0F - rampSmoothstep((progress - 0.62F) / 0.38F);
        return build * decay;
    }

    /**
     * The lifted base's own ramp, and deliberately a slower one than the episode envelope above.
     *
     * <p>That envelope also sets how many vortices the episode carries, so it is built over the
     * episode's first quarter - fast enough that the vortices show up when the episode starts. The
     * retraction is a whole funnel's foot moving: lifting it over that same quarter would slide the
     * column's base a couple of blocks a tick on even a modest tornado. It therefore builds over the
     * first two fifths of the episode and settles back over the last three tenths, which keeps the
     * base's movement far below one layer of the funnel per tick - the step that would read as the
     * bottom of the funnel hopping rather than rising.
     */
    private float liftEnvelope(long gameTime) {
        if (!multiVortexEpisodeActive || multiVortexEpisodeStartTick <= 0L
                || multiVortexEpisodeEndTick <= multiVortexEpisodeStartTick) {
            return 1.0F;
        }
        long length = multiVortexEpisodeEndTick - multiVortexEpisodeStartTick;
        float progress = Mth.clamp((gameTime - multiVortexEpisodeStartTick) / (float) length, 0.0F, 1.0F);
        float build = rampSmoothstep(progress / 0.40F);
        float decay = 1.0F - rampSmoothstep((progress - 0.70F) / 0.30F);
        return build * decay;
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

    /**
     * How far up a lifted parent's condensation has retracted <em>right now</em>, as a fraction of the
     * funnel's height: 0 means the column still reaches the ground, which is where it sits before an
     * episode, at the very start of one, and again by the time it decays away.
     *
     * <p>The retraction rides the episode envelope, so the foot of the funnel lifts off the surface over
     * the episode's first seconds and settles back down onto it at the end. Nothing is ever deleted
     * outright: the column above the retraction is drawn at the parent's own width and opacity
     * throughout, so a multi-vortex episode adds vortices to a tornado instead of replacing it.
     */
    public float parentLiftFraction(long gameTime) {
        if (multiVortexFlavour != MultiVortexFlavour.LIFTED) {
            return 0.0F;
        }
        float envelope = gameTime == Long.MIN_VALUE ? 1.0F : liftEnvelope(gameTime);
        return Mth.clamp(multiVortexLiftFraction * envelope, 0.0F, MultiVortexParentProfile.MAX_LIFTED_TIP);
    }

    private boolean funnelMorphologyVisualActive() {
        return targetFunnelMorphology.shaped() || funnelMorphology.shaped() || funnelMorphologyBlend > 0.03F;
    }

    /**
     * Whether this tornado is wide enough to need the wide-funnel allowances: a visible funnel past the
     * ordinary cap, a stage that reads as a big storm, extra tentacles.
     *
     * <p>It is deliberately about the WIDTH and not about the wedge. The wedge is one of the shapes a wide
     * tornado can roll, and none of those allowances should depend on which shape came up - otherwise two
     * identical storms differ in how big they are drawn and how severe they read, on the strength of a coin
     * flip. The test is the same exit width the wedge episodes use, so every one of these paths behaves
     * exactly as it did for a wedge.
     */
    public boolean wideTornado() {
        return Math.max(damagePathWidth, targetDamagePathWidth) >= WEDGE_EXIT_WIDTH_BLOCKS;
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
        // Only the TARGET clears here. The shape itself stays in force, and `smooth()` eases its blend
        // back to plain and releases it once the blend reaches zero. Dropping the shape and zeroing the
        // blend on the spot snapped the funnel out of its shape in a single tick, and the step was worst
        // at the top of a flared shape, where the profile's own scale is largest - the bowl's flare is the
        // cloud base, so the ring there swelled past every layer under it and then reset.
        targetTrunkNeckFraction = 0.50F;
        targetTrunkCurveStrength = 1.0F;
    }

    private void clearNonWedgeFunnelMorphologyTag() {
        if (!wedgeMorphologyActive()) {
            clearFunnelMorphologyTag();
            return;
        }
        // A shape giving way to the wedge: the wedge becomes the target and `smooth()` hands over through
        // plain - the shape in force keeps the profile while its blend falls, then the wedge takes over at
        // zero. No shape is dropped here.
        targetFunnelMorphology = FunnelMorphology.WEDGE;
        funnelMorphologyLocked = true;
        targetTrunkNeckFraction = 0.78F;
        targetTrunkCurveStrength = 0.35F;
    }

    private void clearWedgeMorphologyTag() {
        funnelMorphologyLocked = false;
        if (targetFunnelMorphology == FunnelMorphology.WEDGE) {
            targetFunnelMorphology = FunnelMorphology.DEFAULT;
        }
        // The wedge stays in force at whatever blend it has and eases back to plain in `smooth()`. It is
        // the largest profile in the mod - its ground scale reaches over five times the funnel's own radius
        // and its flared top is the top layer this whole change is about - so zeroing it here snapped a
        // half-mile-wide base back to an ordinary column between two ticks. A wedge pseudo-shape ends
        // whenever the storm drops under the exit width, which a storm sitting near that threshold does
        // repeatedly, so the snap repeated with it.
    }

    private void activateWedgeMorphology() {
        clearSidewinderTag();
        if (targetFunnelMorphology != FunnelMorphology.WEDGE) {
            targetFunnelMorphology = FunnelMorphology.WEDGE;
        }
        // Whatever shape was in force is deliberately left alone: the handoff in `smooth()` eases it out
        // before the wedge eases in. Dropping it and zeroing the blend here stepped the funnel straight
        // from the outgoing profile to the wedge profile in one tick.
        funnelMorphologyLocked = true;
        targetTrunkNeckFraction = 0.78F;
        targetTrunkCurveStrength = 0.35F;
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
        if (type.orbits()) {
            // The life and the rate were drawn independently, so the worst combinations came out at barely
            // one turn of the parent - a flicker rather than a vortex. The life is raised to cover a few
            // turns at the rate this one is actually going to circle at, and its own band caps the raise.
            life = VortexLife.lifeFor(life, orbitSpeed, Math.max(rails.minLife(), rails.maxLife()) * 2);
        }
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
        retireUnwantedSatellites(gameTime, activeLimit, parentPos == null);
        if (activeLimit <= 0 || parentPos == null) {
            return;
        }
        if (parentStage < minParentStage) {
            return;
        }
        // The limit is about how many the parent can KEEP, so a satellite that is already on its way out is
        // not counted against it: otherwise a retirement would block the next birth for as long as its fade
        // lasts, which is a limit quietly become a cooldown.
        if (gameTime < nextSatelliteAttemptTick || liveSatellites() >= activeLimit) {
            return;
        }
        nextSatelliteAttemptTick = gameTime + nextIntInclusive(900, 2400);
        float spawnChance = Mth.clamp(Config.SATELLITE_TORNADOES_SPAWN_CHANCE.get().floatValue(), 0.0F, 0.08F);
        if (random.nextFloat() > spawnChance) {
            return;
        }
        long id = allocateSatelliteId();
        float orbitSpeed = 1.45F + random.nextFloat() * 2.75F;
        // Same rule as the sub-vortices: a satellite is born with enough life to go round its parent a few
        // times at its own rate, because the shortest lives in its band used to cover 1.6 turns. The rate
        // handed in is the SLOWEST this one will ever circle at - its own speed wobbles down by up to 12%
        // every tick - so the turns counted here are turns it really gets, not turns it gets at its best.
        long lifetime = VortexLife.lifeFor(nextIntInclusive(400, 1200),
                orbitSpeed * TornadoCirculationState.ORBIT_WOBBLE_FLOOR, 2400);
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
                orbitSpeed,
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

    /** Rungs this storm has climbed past the ceiling it was born with. */
    public int strengtheningRungs() {
        return strengtheningRungs;
    }

    void noteStrengtheningRung() {
        strengtheningRungs++;
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
        tag.putInt("StrengtheningRungs", strengtheningRungs);
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
        tag.putBoolean("HorizontalVortexRolled", horizontalVortexRolled);
        tag.putBoolean("HorizontalVortexCapable", horizontalVortexCapable);
        tag.putBoolean("MultiVortexEpisodeActive", multiVortexEpisodeActive);
        tag.putString("MultiVortexFlavour", multiVortexFlavour.name());
        tag.putFloat("MultiVortexTipFraction", multiVortexTipFraction);
        tag.putFloat("MultiVortexLiftFraction", multiVortexLiftFraction);
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
        tag.putBoolean("WedgeEpisodeRolled", wedgeEpisodeRolled);
        tag.putBoolean("WedgeEpisodeIsWedge", wedgeEpisodeIsWedge);
        tag.putFloat("FunnelMorphologyBlend", funnelMorphologyBlend);
        tag.putFloat("TrunkNeckFraction", trunkNeckFraction);
        tag.putFloat("TargetTrunkNeckFraction", targetTrunkNeckFraction);
        tag.putFloat("TrunkCurvePhase", trunkCurvePhase);
        tag.putFloat("TrunkCurveStrength", trunkCurveStrength);
        tag.putFloat("TargetTrunkCurveStrength", targetTrunkCurveStrength);
        tag.putBoolean("MorphologyHandoff", morphologyHandoff);
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
        strengtheningRungs = tag.contains("StrengtheningRungs") ? Math.max(0, tag.getInt("StrengtheningRungs")) : 0;
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
        // A save written before this trait existed has no roll recorded; a storm already carrying
        // tubes was plainly one of the carriers, so that is what it reads back as. A storm that had
        // none simply rolls on its next eligible tick.
        boolean legacyHorizontalCarrier = tag.getInt("HorizontalVortexCount") > 0;
        horizontalVortexRolled = tag.contains("HorizontalVortexRolled") ? tag.getBoolean("HorizontalVortexRolled") : legacyHorizontalCarrier;
        horizontalVortexCapable = tag.contains("HorizontalVortexCapable") ? tag.getBoolean("HorizontalVortexCapable") : legacyHorizontalCarrier;
        multiVortexEpisodeActive = tag.getBoolean("MultiVortexEpisodeActive");
        // Older saves carried the ground swarm as a flag rather than a flavour; translate it so an
        // episode that is in flight across the update keeps the arrangement it was already showing.
        multiVortexFlavour = tag.contains("MultiVortexFlavour")
                ? MultiVortexFlavour.byName(tag.getString("MultiVortexFlavour"))
                : tag.getBoolean("MultiVortexGroundSwarm") ? MultiVortexFlavour.GROUND_SWARM : MultiVortexFlavour.LIFTED;
        multiVortexTipFraction = tag.contains("MultiVortexTipFraction") ? tag.getFloat("MultiVortexTipFraction") : multiVortexTipFraction;
        multiVortexLiftFraction = tag.contains("MultiVortexLiftFraction") ? tag.getFloat("MultiVortexLiftFraction") : multiVortexLiftFraction;
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
        targetTrunkCurveStrength = tag.contains("TargetTrunkCurveStrength")
                ? Mth.clamp(tag.getFloat("TargetTrunkCurveStrength"), 0.0F, 3.0F)
                : trunkCurveStrength;
        morphologyHandoff = tag.contains("MorphologyHandoff") ? tag.getBoolean("MorphologyHandoff") : false;
        nextMorphologyRetargetTick = tag.contains("NextMorphologyRetargetTick") ? tag.getLong("NextMorphologyRetargetTick") : nextMorphologyRetargetTick;
        // A storm saved before this existed keeps the shape it was drawn with: a loaded wedge tag counts as
        // an already-rolled wedge, rather than a fresh roll that could take the wedge away mid-episode.
        wedgeEpisodeIsWedge = tag.contains("WedgeEpisodeIsWedge") ? tag.getBoolean("WedgeEpisodeIsWedge") : wedgeMorphologyActive();
        wedgeEpisodeRolled = tag.contains("WedgeEpisodeRolled") ? tag.getBoolean("WedgeEpisodeRolled") : wedgeEpisodeIsWedge;
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
            targetTrunkCurveStrength = Mth.clamp(0.56F + random.nextFloat() * 0.28F, 0.0F, 1.05F);
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
            targetTrunkCurveStrength = Mth.clamp(1.02F + random.nextFloat() * 0.58F, 0.0F, Math.max(1.55F, Config.TORNADO_ELEPHANT_TRUNK_CURVE_STRENGTH.get().floatValue() * 1.55F));
            nextMorphologyRetargetTick = gameTime + nextIntInclusive(460, 1040);
        } else if (targetFunnelMorphology == FunnelMorphology.FLOATER) {
            funnelMorphologyLocked = true;
            clearSidewinderTag();
            targetTrunkNeckFraction = 0.46F + random.nextFloat() * 0.16F;
            targetTrunkCurveStrength = Mth.clamp(0.52F + random.nextFloat() * 0.36F, 0.0F, 1.05F);
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
            targetTrunkCurveStrength = Mth.clamp(1.10F + random.nextFloat() * 0.50F, 0.0F, 1.60F);
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
            targetTrunkCurveStrength = Mth.clamp(0.45F + random.nextFloat() * 0.25F, 0.0F, 0.90F);
            nextMorphologyRetargetTick = gameTime + nextIntInclusive(700, 1600);
        } else if (targetFunnelMorphology == FunnelMorphology.STOVEPIPE) {
            funnelMorphologyLocked = true;
            clearSidewinderTag();
            targetTrunkNeckFraction = 0.50F + random.nextFloat() * 0.14F;
            targetTrunkCurveStrength = Mth.clamp(0.30F + random.nextFloat() * 0.22F, 0.0F, 0.70F);
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
            targetTrunkCurveStrength = Mth.clamp(0.85F + random.nextFloat() * 0.35F, 0.0F, 1.25F);
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
            targetTrunkCurveStrength = Mth.clamp(0.40F + random.nextFloat() * 0.25F, 0.0F, 0.85F);
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
            resetWedgeEpisode();
            return false;
        }
        float currentWidth = Math.max(damagePathWidth, targetDamagePathWidth);
        float threshold = wedgeMorphologyActive() ? WEDGE_EXIT_WIDTH_BLOCKS : WEDGE_ENTER_WIDTH_BLOCKS;
        if (currentWidth < threshold) {
            if (wedgeMorphologyActive()) {
                clearWedgeMorphologyTag();
            }
            resetWedgeEpisode();
            return false;
        }

        // Crossing the width threshold makes a tornado WIDE. Whether it also looks like a wedge is a
        // separate question, and in the record it is a genuinely separate one: a half-mile damage path can
        // be a wedge, a large cone, a barrel or a massive multiple-vortex, and which one it is does not
        // follow from the tape measure. So the shape is rolled once per wide episode and then held - the
        // tag also sets the trunk profile, so re-rolling it on every retarget would visibly re-shape the
        // funnel over and over instead of leaving a storm as the storm it is.
        if (!wedgeEpisodeRolled) {
            wedgeEpisodeRolled = true;
            float base = Config.TORNADO_WEDGE_CHANCE.get().floatValue();
            // Size tilts it: a monster is more likely to read as a wedge than one that has only just
            // crossed the threshold, which is how the real distribution runs.
            float sizeFactor = Mth.clamp(
                    (currentWidth - WEDGE_ENTER_WIDTH_BLOCKS) / (WEDGE_ENTER_WIDTH_BLOCKS * 1.6F), 0.0F, 1.0F);
            wedgeEpisodeIsWedge = random.nextFloat() < Mth.clamp(base * (0.55F + sizeFactor * 0.90F), 0.0F, 1.0F);
        }
        if (!wedgeEpisodeIsWedge) {
            // Wide, but not a wedge: the funnel still has to grow with its damage path, or a mile-wide
            // path would be drawn with a needle standing over it. Only the shape is withheld.
            preserveWideWidthTargets(storm);
            if (wedgeMorphologyActive()) {
                clearWedgeMorphologyTag();
            }
            return false;
        }

        activateWedgeMorphology();
        // A wedge that is roping out keeps the shape's tag but not its floor: the width follows the storm's
        // own decay instead of being pinned at the width it used to have.
        if (!sizeContracting(storm)) {
            preserveWideWidthTargets(storm);
            currentWidth = Math.max(damagePathWidth, targetDamagePathWidth);
            float forcedCap = Math.max((float) Config.effectiveMaxVisibleFunnelWidthBlocks(), Math.min(currentWidth, WEDGE_FORCED_VISIBLE_CAP_BLOCKS));
            float desiredVisibleWidth = Mth.clamp(Math.max(currentWidth, WEDGE_ENTER_WIDTH_BLOCKS), 3.0F, forcedCap);
            targetVisibleFunnelWidth = Math.max(targetVisibleFunnelWidth, desiredVisibleWidth);
        }
        targetWidthMultiplier = targetVisibleFunnelWidth / Math.max(3.0F, DynamicStormController.rawBaseRadiusForWind(storm));
        return true;
    }

    private void resetWedgeEpisode() {
        wedgeEpisodeRolled = false;
        wedgeEpisodeIsWedge = false;
    }

    /**
     * Keeps a wide tornado's funnel as wide as its damage path, whichever shape it ended up with. This is
     * the width half of the old wedge activation, split out because a tornado can be wide without being a
     * wedge: with the two welded together the shape was forced by the width, and a wide cone or barrel
     * could not exist at all.
     *
     * <p>The guard is on width rather than on the shape, which is the only thing that changed. The floor
     * below is an anchor for a tornado that is ALREADY wide - it raises the damage path to half a mile -
     * so applying it to an ordinary narrow tornado would drag every tornado in the world up to half a mile
     * across. It was previously guarded by the wedge tag, which coincided with wide; now it is guarded by
     * the width itself.
     */
    private void preserveWideWidthTargets(StormObject storm) {
        if (storm == null) {
            return;
        }
        if (!wideTornado()) {
            return;
        }
        // ...but a storm that is past its peak is giving its width back on purpose, and a floor here would
        // hold it at half a mile while it roped out. The size lifecycle decides that; with it off this
        // method behaves exactly as it always did.
        if (sizeContracting(storm)) {
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
        /**
         * Whether its parent has taken this circulation back. A retiring one keeps its place until the
         * shortened expiry it was given, so it thins and spirals in on the way out instead of vanishing.
         */
        private boolean retiring;
        private final long spawnTick;
        private long expireTick;
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

        /**
         * The lowest the orbit-speed wobble ever takes a satellite's own rate, so anything measuring how
         * long a satellite needs to go round can ask about its slowest turn rather than its fastest.
         */
        static final float ORBIT_WOBBLE_FLOOR = 0.88F;

        private void tickVisual(long gameTime, float rotationDirection) {
            updateLifecycle(gameTime);
            // Kepler-ish spin-up as the orbit tightens, with a slow speed wobble for organic motion.
            float radiusScale = baseOrbitRadius / Math.max(4.0F, orbitRadius);
            float speedWobble = ORBIT_WOBBLE_FLOOR + 0.12F * (float) Math.sin(gameTime * 0.021D + id * 1.31D);
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
            // A retirement is the server's decision and the client draws what it is told: without this the
            // client would keep a satellite the server has already given up on until the end it was born
            // with. Expiry only ever moves earlier, so a late packet cannot resurrect one.
            if (incoming.expireTick < expireTick) {
                expireTick = incoming.expireTick;
            }
            retiring = retiring || incoming.retiring;
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

        /**
         * Ends this circulation on the parent's terms instead of its own: its expiry is pulled in to a short
         * fade, so the life curve it already has - the shrinking funnel, the falling wind, the orbit
         * tightening toward the parent - plays out and it dies the death it was going to die anyway.
         */
        void retire(long gameTime, long fadeTicks) {
            long end = VortexLife.retireTick(gameTime, expireTick, fadeTicks);
            if (end < expireTick) {
                expireTick = end;
            }
            retiring = true;
        }

        /** Whether the parent has already taken this one back and it is on its way out. */
        public boolean retiring() {
            return retiring;
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
            tag.putBoolean("Retiring", retiring);
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
            state.retiring = tag.getBoolean("Retiring");
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
