package com.imbet.village;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.registry.ModVillagers;
import com.imbet.survey.DamageScan;
import com.imbet.survey.DamageScanner;
import com.imbet.survey.TornadoSurveySavedData;
import com.imbet.weather.TornadoThreatService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * The surveyor: a villager who walks a fresh tornado track and files a damage rating on it.
 *
 * <p>It uses the surveyor item's own reading - {@link DamageScanner} and {@link DamageScan} - so a
 * village's survey and a player's survey of the same ground come out the same, and the rating lands in
 * the same saved track the player's surveyor writes to. The track then reads as surveyed on the survey
 * screen, which is the point: a village that was hit knows what hit it.
 */
public final class SurveyorGoal extends Goal {
    /** Stride over a track's points when looking for the closest one to walk to. */
    private static final int POINT_STRIDE = 4;

    /** How close the surveyor has to be to the track to take its reading, in blocks, squared. */
    private static final double WORK_REACH_SQR = 6.25D;

    private static final Map<Villager, Boolean> ATTACHED = new WeakHashMap<>();

    private final Villager villager;
    private TornadoSurveySavedData.TornadoTrack track;
    private Vec3 workPos;
    private long nextAttemptTick;
    private long nextThreatCheckTick;
    private boolean threatFree;

    private SurveyorGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    public static void attach(Villager villager) {
        synchronized (ATTACHED) {
            if (ATTACHED.containsKey(villager)) {
                return;
            }
            ATTACHED.put(villager, Boolean.TRUE);
        }
        villager.goalSelector.addGoal(2, new SurveyorGoal(villager));
    }

    @Override
    public boolean canUse() {
        if (!Config.AI_VILLAGER_SURVEYOR_ENABLED.get() || !(villager.level() instanceof ServerLevel level)) {
            return false;
        }
        if (villager.isSleeping() || !isSurveyor()) {
            return false;
        }
        long gameTime = level.getGameTime();
        if (gameTime < nextAttemptTick) {
            return false;
        }
        nextAttemptTick = gameTime + Config.AI_VILLAGER_SURVEYOR_COOLDOWN_TICKS.get() / 4L;
        if (!threatFree(level)) {
            return false;
        }
        return findWork(level);
    }

    @Override
    public boolean canContinueToUse() {
        if (track == null || workPos == null || !(villager.level() instanceof ServerLevel level)) {
            return false;
        }
        if (villager.isSleeping() || !isSurveyor()) {
            return false;
        }
        return threatFree(level);
    }

    @Override
    public void start() {
        if (workPos != null) {
            villager.getNavigation().moveTo(workPos.x, workPos.y, workPos.z, 0.75D);
        }
    }

    @Override
    public void tick() {
        if (!(villager.level() instanceof ServerLevel level) || workPos == null || track == null) {
            return;
        }
        if (villager.getNavigation().isDone() && villager.position().distanceToSqr(workPos) > WORK_REACH_SQR) {
            villager.getNavigation().moveTo(workPos.x, workPos.y, workPos.z, 0.75D);
            return;
        }
        if (villager.position().distanceToSqr(workPos) > WORK_REACH_SQR) {
            return;
        }
        fileSurvey(level);
    }

    @Override
    public void stop() {
        track = null;
        workPos = null;
    }

    /**
     * Read the ground and file the rating, the way the surveyor item does - same radius, same counters,
     * same estimator, and the track records who filed it.
     */
    private void fileSurvey(ServerLevel level) {
        BlockPos center = villager.blockPosition();
        DamageScan scan = DamageScanner.scan(level, center, Config.SURVEY_RADIUS_BLOCKS.get());
        int loggedMph = Math.round(track.maxWindMph());
        int estimatedMph = scan.rankedMph(loggedMph);
        String ef = DamageScan.efLabelFor(estimatedMph);
        String confidence = scan.confidenceWithTrack(track.points().size(), loggedMph);
        boolean firstReading = !track.surveyed();
        TornadoSurveySavedData.get(level).markSurveyed(track, ef, estimatedMph, confidence);
        // The same line the item prints, so a player reading the log can see what the village filed.
        ExpandedWeather2Dynamics.LOGGER.info(
                "Surveyor filed {} on track {}: {} estimate, {}+ mph, confidence {} (scoured={}, debarked logs={})",
                ef, track.displayId(), ef, estimatedMph, confidence,
                scan.scouredSurfaceBlocks(), scan.debarkedLogs());
        if (firstReading) {
            // A rating that only lives on the survey screen is a rating nobody standing in the damage reads.
            plantMarker(level, ef, estimatedMph, confidence, track.displayId());
        }
        nextAttemptTick = level.getGameTime() + Config.AI_VILLAGER_SURVEYOR_COOLDOWN_TICKS.get();
        track = null;
        workPos = null;
    }

    /**
     * Leave the short version of the rating standing where the surveyor worked: what it was, how hard it
     * blew, how sure the reading is, and which track it belongs to, on four sign lines.
     *
     * <p>One sign per track: the track is marked surveyed when the sign goes up, and a surveyor only walks
     * unsurveyed tracks, so a village rates a tornado once and then walks past its own sign.
     */
    private void plantMarker(ServerLevel level, String ef, int mph, String confidence, String trackId) {
        if (!Config.AI_VILLAGER_SURVEYOR_SIGN.get()) {
            return;
        }
        BlockPos spot = markerSite(level);
        if (spot == null) {
            return;
        }
        BlockState sign = Blocks.OAK_SIGN.defaultBlockState()
                .setValue(StandingSignBlock.ROTATION, RotationSegment.convertToSegment(villager.getYRot() + 180.0F));
        level.setBlock(spot, sign, 3);
        if (level.getBlockEntity(spot) instanceof SignBlockEntity blockEntity) {
            List<String> lines = SurveyMarker.lines(ef, mph, confidence, trackId);
            var text = blockEntity.getFrontText();
            for (int line = 0; line < lines.size(); line++) {
                text = text.setMessage(line, Component.literal(lines.get(line)));
            }
            blockEntity.setText(text, true);
            blockEntity.setChanged();
        }
    }

    /** Bare ground next to the surveyor that a sign will stand on, or null when there is none. */
    private BlockPos markerSite(ServerLevel level) {
        BlockPos base = villager.blockPosition();
        for (int dy = 0; dy >= -3; dy--) {
            BlockPos candidate = base.offset(0, dy, 0);
            if (!level.isLoaded(candidate) || !level.isLoaded(candidate.below())) {
                continue;
            }
            if (!level.getBlockState(candidate).canBeReplaced()) {
                continue;
            }
            if (level.getBlockState(candidate.below()).isFaceSturdy(level, candidate.below(), Direction.UP)) {
                return candidate;
            }
        }
        return null;
    }

    /** Find an unsurveyed track in range and the closest point of it to walk to. */
    private boolean findWork(ServerLevel level) {
        Vec3 from = jobSiteCenter(level);
        Optional<TornadoSurveySavedData.TornadoTrack> nearest = TornadoSurveySavedData.get(level)
                .nearestTrack(from, Config.AI_VILLAGER_SURVEYOR_RANGE_BLOCKS.get(), level.getGameTime(),
                        Config.SURVEY_MAX_TRACK_AGE_TICKS.get());
        if (nearest.isEmpty()) {
            return false;
        }
        TornadoSurveySavedData.TornadoTrack candidate = nearest.get();
        if (candidate.surveyed()) {
            // Already rated; nothing for a second surveyor to add.
            nextAttemptTick = level.getGameTime() + Config.AI_VILLAGER_SURVEYOR_COOLDOWN_TICKS.get();
            return false;
        }
        Vec3 point = closestPoint(candidate, from);
        if (point == null) {
            return false;
        }
        track = candidate;
        workPos = point;
        return true;
    }

    private static Vec3 closestPoint(TornadoSurveySavedData.TornadoTrack track, Vec3 from) {
        Vec3 best = null;
        double bestDistance = Double.MAX_VALUE;
        int size = track.points().size();
        int stride = Math.max(POINT_STRIDE, size / 256);
        for (int i = 0; i < size; i += stride) {
            TornadoSurveySavedData.TrackPoint point = track.points().get(i);
            double distance = point.distanceTo(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = new Vec3(point.x(), point.y(), point.z());
            }
        }
        return best;
    }

    /**
     * Where the surveyor counts from: its own job site, so a surveyor stays near the post it was hired
     * for rather than walking off across the world after the closest storm.
     */
    private Vec3 jobSiteCenter(ServerLevel level) {
        Optional<GlobalPos> jobSite = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE);
        if (jobSite.isPresent()) {
            BlockPos pos = jobSite.get().pos();
            return new Vec3(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        }
        return villager.position();
    }

    private boolean isSurveyor() {
        return villager.getVillagerData().getProfession() == ModVillagers.SURVEYOR.get();
    }

    /** A surveyor does not work in a storm: the shelter goal takes the villager, and the ground is moving anyway. */
    private boolean threatFree(ServerLevel level) {
        long gameTime = level.getGameTime();
        if (gameTime >= nextThreatCheckTick) {
            threatFree = TornadoThreatService.nearestThreat(level, villager.position(), TornadoThreatService.configuredRadius()).isEmpty();
            nextThreatCheckTick = gameTime + 40L;
        }
        return threatFree;
    }
}
