package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.config.ConfigMisc;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Twins: two tornadoes on the ground at once, side by side, each on its own track.
 *
 * <p>The addon already had every way of putting <em>one</em> tornado on a storm, and Weather2 already
 * runs many storms - an outbreak spreads a family of them down a front. What it could not do is put
 * two tornadoes in the same field of view at the same time: the satellites beside a tornado are
 * deliberately weaker than it (a capped fraction of its wind, spiralling in to be absorbed), so a
 * pair of equals never happened. This is that pair, and it is made the way the addon makes every
 * other storm: a real {@link StormObject} seeded beside a storm that is already tornadic, kept on a
 * tornado rung for as long as the pair lasts, and left to Weather2 from there.
 *
 * <p><b>Why a second storm rather than a second funnel.</b> Weather2 gives a funnel to any land storm
 * at or above its forming rung - its own {@code tick} builds one through {@code setAndUpdateTornado}
 * and then positions it from the storm's own ground position - so a storm held at a tornado rung is
 * drawn, moved, and felt as a tornado by Weather2 itself. That means this addon draws nothing for a
 * twin: the funnel, the layers, the spin, the debris, the sound, and the wind are the ones every other
 * tornado gets, which is also why a pair reads correctly under plain vanilla lighting and with no
 * resource pack - two funnels made of Weather2's own art.
 *
 * <p><b>Everything downstream follows for free.</b> Because a twin is a tornadic storm and not a
 * decoration, the mod's own dynamic vortex handling picks it up the same way it picks up any tornado:
 * its wind field, its damage swath and ground scouring, its funnel build-in through
 * {@link TornadoGenesis}, its radar signature, its warning, its spotter report and its damage survey
 * all belong to it and to nobody else. Two tornadoes therefore mean two tracks on the survey, two
 * warnings, and two separate paths of damage - the thing the video this was built for actually shows.
 *
 * <p><b>What a twin is not.</b> It is not merged into anything: every merge path in the addon skips
 * it, because two storms converging is exactly what twins look like and absorbing one of them would
 * delete the pair the moment it became interesting. It never carries a twin of its own. And it is not
 * permanent: it ends with its pair when the first tornado decays, or when its own life runs out,
 * by Weather2's own decay - the addon stops holding it up and lets it rope out.
 */
public final class TwinTornadoService {
    /**
     * The stamp on every twin storm. Weather2 writes this string into the storm's own save data, so a
     * twin is still recognised as a twin after a reload without the addon remembering anything -
     * the same trick the squall-line cells and the frontal cells use.
     */
    private static final String TWIN_SPAWNER_UUID = "00000000-0000-0000-0000-000000000004";

    /**
     * How often a live pair is looked at. Weather2 moves and ticks both storms itself every tick; this
     * is only the cadence at which the addon re-reads the pair to keep the second one on its rung and
     * on its course, so it is deliberately slow.
     */
    private static final int MAINTAIN_INTERVAL_TICKS = 40;
    /** How often a corrected pair is pushed to the clients. */
    private static final int SYNC_INTERVAL_TICKS = 80;
    /** The strength a twin's stage is held at, the same band the addon's other spawned cells sit in. */
    private static final float PEER_STAGE_INTENSITY = 0.68F;
    /** How much of the parent's wind momentum a twin carries. */
    private static final float PEER_WIND_MOMENTUM_SHARE = 0.85F;
    /** How far from a twin's own place a storm stops counting as the pair: the spacing plus slack. */
    private static final double PAIR_REACH_SHARE = 1.6D;
    /** The clear sky a twin needs to be seeded into, so it is not stacked inside another cell's deck. */
    private static final double STACKING_RANGE_BLOCKS = 150.0D;
    /** The rain a twin brings with it: a tornado's storm rains, and this one is beside a tornado. */
    private static final float TWIN_RAIN_AMOUNT = 0.86F;
    /** Weather2's own fallback storm heading, used when the parent has not started moving yet. */
    private static final Vec3 FALLBACK_HEADING = new Vec3(0.923D, 0.0D, -0.382D);

    private TwinTornadoService() {
    }

    /** Whether this storm is a twin - the second tornado of a pair - rather than the first. */
    public static boolean isTwinStorm(StormObject storm) {
        return storm != null && TWIN_SPAWNER_UUID.equals(storm.spawnerUUID);
    }

    public static void tick(ServerLevel level) {
        if (level == null || level.isClientSide() || !Config.TWIN_TORNADOES_ENABLED.get()) {
            return;
        }
        long gameTime = level.getGameTime();
        if (Math.floorMod(gameTime, (long) MAINTAIN_INTERVAL_TICKS) != 0L) {
            return;
        }
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return;
        }
        List<StormObject> storms = snapshot(manager);
        for (StormObject twin : storms) {
            if (isTwinStorm(twin)) {
                maintain(level, manager, twin, pairFor(storms, twin), gameTime);
            }
        }
        if (TwinTornadoModel.attemptDue(gameTime, Config.TWIN_ATTEMPT_INTERVAL_TICKS.get())) {
            trySeed(level, manager, storms, gameTime);
        }
    }

    /**
     * The tornado a twin belongs to: the nearest storm that is a tornado in its own right. Read from
     * the world every time rather than remembered, so a pair that survived a reload is still a pair.
     */
    private static StormObject pairFor(List<StormObject> storms, StormObject twin) {
        if (twin.pos == null) {
            return null;
        }
        double reach = TwinTornadoModel.spacingBlocks(Config.TWIN_SPACING_BLOCKS.get()) * PAIR_REACH_SHARE
                + TwinTornadoModel.TWIN_TRAIL_BLOCKS;
        StormObject best = null;
        double bestDistance = reach;
        for (StormObject candidate : storms) {
            if (candidate == twin || isTwinStorm(candidate) || candidate.pos == null) {
                continue;
            }
            if (!candidate.isTornadoFormingOrGreater() || isDisqualified(candidate)) {
                continue;
            }
            double distance = horizontalDistance(candidate.pos, twin.pos);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private static void maintain(ServerLevel level, WeatherManagerServer manager, StormObject twin, StormObject pair, long gameTime) {
        if (pair == null && Config.TWIN_DIES_WITH_PAIR.get()) {
            windDown(manager, twin);
            return;
        }
        if (lifetimeSpent(twin, gameTime)) {
            windDown(manager, twin);
            return;
        }
        if (pair == null) {
            // No pair and not configured to end with one: the twin is a tornado in its own right now,
            // and the only thing left to decide is how long that goes on.
            return;
        }
        holdPeer(level, manager, twin, pair, gameTime);
    }

    /**
     * Keeps the second tornado of a pair a peer of the first: its rung at the parent's own, its air as
     * wet and as fast as the storm carrying it, and its course on the beam the pair was formed on.
     */
    private static void holdPeer(ServerLevel level, WeatherManagerServer manager, StormObject twin, StormObject pair, long gameTime) {
        double roll = TwinTornadoModel.stableRoll(twin.ID);
        int rung = TwinTornadoModel.peerStage(pair.levelCurIntensityStage, Config.TWIN_MIN_PARENT_STAGE.get(), roll);
        if (twin.levelCurIntensityStage < rung) {
            twin.levelCurIntensityStage = rung;
        }
        twin.maxIntensityStage = Math.max(twin.maxIntensityStage, rung);
        twin.levelStormIntensityMax = Math.max(twin.levelStormIntensityMax, rung);
        twin.levelCurStagesIntensity = Math.max(twin.levelCurStagesIntensity, PEER_STAGE_INTENSITY);
        twin.levelWater = Math.max(twin.levelWater, Math.max(120, twin.levelWaterStartRaining * 3));
        twin.levelWindMomentum = Math.max(twin.levelWindMomentum, pair.levelWindMomentum * PEER_WIND_MOMENTUM_SHARE);
        twin.stormType = StormObject.TYPE_LAND;
        twin.attrib_waterSpout = false;
        twin.isGrowing = false;
        twin.hasStormPeaked = false;
        // The air decides whether a twin may be promoted into a deadly one, the same as every other storm
        // this mod seeds, and it is re-read as the pair is held so a sky that caps over does not leave the
        // twin carrying a deadly pass it no longer earns.
        twin.canBeDeadly = AddonWeatherEventService.deadlyAirSupports(level, twin.pos);
        twin.tempAngleFormingTornado = 0.0F;
        twin.setPrecipitating(true);
        twin.setCloudlessStorm(false);
        twin.spinSpeed = peerSpin(pair);
        twin.size = Math.max(twin.size, TwinTornadoModel.peerSize(pair.size, roll));
        twin.maxSize = Math.max(twin.maxSize, twin.size);
        holdCourse(twin, pair, roll);
        if (Math.floorMod(gameTime, (long) SYNC_INTERVAL_TICKS) == 0L) {
            manager.syncStormUpdate(twin);
        }
    }

    /**
     * Puts a twin on its own heading: the parent's course turned back toward the parent by the pair's
     * weave, at the parent's speed. This is what keeps two storms moving as a pair instead of drifting
     * apart or falling into each other - what changes over a pair's life is only the few degrees of
     * weave, and over an eight-minute life that is tens of blocks of closing out of hundreds.
     */
    private static void holdCourse(StormObject twin, StormObject pair, double roll) {
        if (pair.pos == null || twin.pos == null) {
            return;
        }
        Vec3 heading = headingOf(pair);
        double side = TwinTornadoModel.sideFromPosition(pair.pos.x, pair.pos.z, heading.x, heading.z,
                twin.pos.x, twin.pos.z);
        if (side == 0.0D) {
            side = TwinTornadoModel.sideOf(roll);
        }
        TwinTornadoModel.Ground course = TwinTornadoModel.twinHeading(heading.x, heading.z, side,
                TwinTornadoModel.weaveDegrees(roll));
        double speed = TwinTornadoModel.peerSpeed(pair.motion == null ? 0.0D : pair.motion.horizontalDistance(), 0.03D);
        twin.motion = new Vec3(course.x() * speed, 0.0D, course.z() * speed);
        twin.angleMovementTornadoOverride = 0.0F;
        twin.angleIsOverridden = false;
    }

    /**
     * Stops holding a twin up and hands it back to Weather2. Nothing is deleted or killed here: the
     * storm's ceiling is dropped to the hail rung and it is marked as having peaked, which is
     * Weather2's own way of telling a storm to wind down. The funnel ropes out and the second tornado
     * of the pair dies the way the first one will.
     */
    private static void windDown(WeatherManagerServer manager, StormObject twin) {
        twin.isGrowing = false;
        twin.hasStormPeaked = true;
        twin.maxIntensityStage = Math.min(twin.maxIntensityStage, StormObject.STATE_HAIL);
        twin.levelStormIntensityMax = Math.min(twin.levelStormIntensityMax, StormObject.STATE_HAIL);
        manager.syncStormUpdate(twin);
    }

    /**
     * How long a twin has been a twin, measured from its own funnel's birth. That tick is rolled once,
     * shared with every client and written into the storm's save data, so a twin's life is the same
     * length after a reload as it was before one - and a twin whose funnel has not appeared yet has
     * not started living.
     */
    private static boolean lifetimeSpent(StormObject twin, long gameTime) {
        int lifetime = Config.TWIN_LIFETIME_TICKS.get();
        long birthTick = DynamicStormController.stateFor(twin).funnelBirthTick();
        if (birthTick == Long.MIN_VALUE) {
            return false;
        }
        long age = gameTime - birthTick;
        return age > 0L && TwinTornadoModel.lifetimeSpent((int) Math.min(Integer.MAX_VALUE, age), lifetime);
    }

    private static void trySeed(ServerLevel level, WeatherManagerServer manager, List<StormObject> storms, long gameTime) {
        int maxPerPair = Mth.clamp(Config.TWIN_MAX_PER_TORNADO.get(), 0, 2);
        if (maxPerPair <= 0) {
            return;
        }
        StormObject parent = bestParent(storms, gameTime, maxPerPair);
        if (parent == null) {
            return;
        }
        if (!TwinTornadoModel.rollSeeds(level.random.nextDouble(), Config.TWIN_SPAWN_CHANCE.get())) {
            return;
        }
        seedTwin(level, manager, parent, gameTime);
    }

    /**
     * The tornado a pair should form on: the strongest one that is already a formed tornado on its own
     * and does not have its twin yet. Only the strongest is considered, in the order the storms are
     * held and then by id, so the choice cannot flicker between two equally violent parents.
     */
    private static StormObject bestParent(List<StormObject> storms, long gameTime, int maxPerPair) {
        StormObject best = null;
        for (StormObject storm : storms) {
            if (!isCandidateParent(storm, gameTime)) {
                continue;
            }
            if (twinsAround(storms, storm) >= maxPerPair) {
                continue;
            }
            if (best == null || storm.levelCurIntensityStage > best.levelCurIntensityStage
                    || (storm.levelCurIntensityStage == best.levelCurIntensityStage && storm.ID < best.ID)) {
                best = storm;
            }
        }
        return best;
    }

    private static boolean isCandidateParent(StormObject storm, long gameTime) {
        if (storm == null || storm.pos == null || storm.isDead || isTwinStorm(storm) || isDisqualified(storm)) {
            return false;
        }
        if (!storm.isTornadoFormingOrGreater()) {
            return false;
        }
        if (!TwinTornadoModel.parentEligible(storm.levelCurIntensityStage, Config.TWIN_MIN_PARENT_STAGE.get())) {
            return false;
        }
        // A twin belongs beside a tornado, not beside a funnel that is still reaching for the ground:
        // the pair has to be two tornadoes, and the first one has to be one already.
        return TwinTornadoModel.parentFormed(DynamicStormController.stateFor(storm).genesisProgress(gameTime));
    }

    /**
     * Whether a storm is something a twin must never be paired with, however tornadic it looks: a water
     * storm wearing a funnel is a waterspout, a tropical system is not a tornado at all, and the cells
     * this addon seeds for its own squall lines and fronts are parts of another structure.
     */
    private static boolean isDisqualified(StormObject storm) {
        return storm.attrib_waterSpout
                || storm.stormType == StormObject.TYPE_WATER
                || storm.isPet()
                || storm.isPlayerControlled()
                || storm.weatherMachineControlled
                || DynamicStormController.isAbsorbedOrDecaying(storm)
                || DynamicStormController.isNativeCyclone(storm)
                || AddonWeatherEventService.isSquallLineHelperStorm(storm)
                || AddonWeatherEventService.isHurricaneHelperStorm(storm);
    }

    /** How many twins are already paired with this parent. */
    private static int twinsAround(List<StormObject> storms, StormObject parent) {
        if (parent.pos == null) {
            return 0;
        }
        double reach = TwinTornadoModel.spacingBlocks(Config.TWIN_SPACING_BLOCKS.get()) * PAIR_REACH_SHARE
                + TwinTornadoModel.TWIN_TRAIL_BLOCKS;
        int count = 0;
        for (StormObject other : storms) {
            if (!isTwinStorm(other) || other.pos == null) {
                continue;
            }
            if (horizontalDistance(parent.pos, other.pos) <= reach) {
                count++;
            }
        }
        return count;
    }

    /**
     * Seeds the second tornado of a pair: a real Weather2 storm, placed abeam of the first and a little
     * behind it, at the same rung and the same size, on a heading woven a few degrees back toward the
     * first. It carries the twin stamp, so every merge path and every future twin attempt can see what
     * it is.
     */
    private static void seedTwin(ServerLevel level, WeatherManagerServer manager, StormObject parent, long gameTime) {
        Vec3 heading = headingOf(parent);
        double roll = TwinTornadoModel.stableRoll(parent.ID);
        double side = TwinTornadoModel.sideOf(roll);
        TwinTornadoModel.Ground beam = TwinTornadoModel.abeam(parent.pos.x, parent.pos.z, heading.x, heading.z, side,
                TwinTornadoModel.spacingBlocks(Config.TWIN_SPACING_BLOCKS.get()));
        TwinTornadoModel.Ground spot = TwinTornadoModel.trailing(beam, heading.x, heading.z,
                TwinTornadoModel.TWIN_TRAIL_BLOCKS);
        Vec3 aloft = aloftPos(level, spot);
        if (hasNearbyStorm(manager, aloft, STACKING_RANGE_BLOCKS)) {
            return;
        }
        StormObject twin = new StormObject(manager);
        twin.initPositions(aloft);
        twin.initFirstTime();
        twin.naturallySpawned = true;
        twin.spawnerUUID = TWIN_SPAWNER_UUID;
        configureTwin(twin, parent, heading, roll, AddonWeatherEventService.deadlyAirSupports(level, aloft));
        manager.addStormObject(twin);
        manager.syncStormNew(twin);
        manager.lastStormFormed = Math.max(manager.lastStormFormed, gameTime);
        manager.isVanillaRainActiveOnServer = true;
        manager.isVanillaThunderActiveOnServer = true;
        manager.vanillaRainTimeOnServer = Math.max(manager.vanillaRainTimeOnServer, 20 * 60 * 6);
        manager.vanillaRainAmountOnServer = Math.max(manager.vanillaRainAmountOnServer, TWIN_RAIN_AMOUNT);
    }

    private static void configureTwin(StormObject twin, StormObject parent, Vec3 heading, double roll, boolean canBeDeadly) {
        int rung = TwinTornadoModel.peerStage(parent.levelCurIntensityStage, Config.TWIN_MIN_PARENT_STAGE.get(), roll);
        twin.stormType = StormObject.TYPE_LAND;
        twin.levelCurIntensityStage = rung;
        twin.levelCurStagesIntensity = PEER_STAGE_INTENSITY;
        twin.maxIntensityStage = rung;
        twin.levelStormIntensityMax = rung;
        twin.isGrowing = false;
        twin.hasStormPeaked = false;
        twin.canBeDeadly = canBeDeadly;
        twin.alwaysProgresses = false;
        twin.attrib_waterSpout = false;
        twin.tempAngleFormingTornado = 0.0F;
        twin.spinSpeed = peerSpin(parent);
        twin.levelWindMomentum = Math.max(0.6F, parent.levelWindMomentum * PEER_WIND_MOMENTUM_SHARE);
        twin.levelWater = Math.max(120, parent.levelWaterStartRaining * 3);
        twin.size = TwinTornadoModel.peerSize(parent.size, roll);
        twin.maxSize = Math.max(twin.size, TwinTornadoModel.peerSize(parent.maxSize, roll));
        twin.setPrecipitating(true);
        twin.setCloudlessStorm(false);
        TwinTornadoModel.Ground course = TwinTornadoModel.twinHeading(heading.x, heading.z,
                TwinTornadoModel.sideOf(roll), TwinTornadoModel.weaveDegrees(roll));
        double speed = TwinTornadoModel.peerSpeed(parent.motion == null ? 0.0D : parent.motion.horizontalDistance(), 0.03D);
        twin.motion = new Vec3(course.x() * speed, 0.0D, course.z() * speed);
        twin.angleMovementTornadoOverride = 0.0F;
        twin.angleIsOverridden = false;
        // The pair turns the same way. Two funnels spinning against each other is the one thing that
        // would give a pair away, and the addon already decides each storm's handedness in its state.
        DynamicStormState parentState = DynamicStormController.stateFor(parent);
        DynamicStormState twinState = DynamicStormController.stateFor(twin);
        if (parentState.rotationAssigned()) {
            twinState.setAntiCyclonic(parentState.isAntiCyclonic());
        } else {
            twinState.assignCyclonicIfUnset();
        }
    }

    private static double peerSpin(StormObject parent) {
        double magnitude = Math.max(0.035D, Math.abs(parent.spinSpeed));
        return parent.spinSpeed < 0.0D ? -magnitude : magnitude;
    }

    private static Vec3 headingOf(StormObject storm) {
        Vec3 motion = storm.motion;
        if (motion == null || motion.horizontalDistanceSqr() < 1.0E-5D) {
            return FALLBACK_HEADING;
        }
        return new Vec3(motion.x, 0.0D, motion.z).normalize();
    }

    /** The cloud base a seeded storm belongs at, the same one the addon's other cells are placed at. */
    private static Vec3 aloftPos(ServerLevel level, TwinTornadoModel.Ground spot) {
        int x = Mth.floor(spot.x());
        int z = Mth.floor(spot.z());
        double surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        double cloudBase = Math.max(level.getSeaLevel() + 34.0D, ConfigMisc.Cloud_Layer0_Height - 30.0D);
        double y = Mth.clamp(Math.max(surface + 42.0D, cloudBase),
                level.getMinBuildHeight() + 24.0D, level.getMaxBuildHeight() - 20.0D);
        return new Vec3(spot.x(), y, spot.z());
    }

    private static List<StormObject> snapshot(WeatherManagerServer manager) {
        List<StormObject> storms = new ArrayList<>();
        for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
            if (object instanceof StormObject storm && storm.pos != null && !storm.isDead) {
                storms.add(storm);
            }
        }
        return storms;
    }

    private static boolean hasNearbyStorm(WeatherManagerServer manager, Vec3 center, double range) {
        double rangeSqr = range * range;
        for (WeatherObject object : manager.getStormObjects()) {
            if (object instanceof StormObject storm && storm.pos != null && !storm.isDead
                    && storm.pos.distanceToSqr(center) <= rangeSqr) {
                return true;
            }
        }
        return false;
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }
}
