package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A villager's tornado drill.
 *
 * <p>The decision of *where* to go lives in {@link ShelterChoice}, which is arithmetic and therefore
 * testable; this class is the part that has to touch the world. It reads the funnel's position, drift
 * and width once per scan, collects the roofed places around the villager, asks for the best one, and
 * walks there - or, when there is nothing worth walking to, runs clear of the funnel's track instead
 * of standing still, which is what the old version did.
 *
 * <p>What it fixes, in the order a village would notice: nobody shelters where the tornado is about to
 * arrive; villagers do not cross the funnel's path to reach a door; a household does not all pile into
 * the same doorway when the next house over is open; a sleeping village wakes up; and the ones that
 * cannot reach shelter at least leave the ground the funnel is coming for.
 */
public final class TornadoShelterGoal extends Goal {
    /**
     * How far around a villager the world is read for roofed places. The threat radius is measured in
     * hundreds of blocks, but nobody walks to a shelter 300 blocks away while a tornado is on them, so
     * the search is local on purpose.
     */
    private static final int SCAN_RADIUS_BLOCKS = 32;

    /** How often the column sampler steps, in blocks: every third column, which is where the old one was. */
    private static final int COVER_STRIDE = 3;

    /** How long a villager holds position in shelter before re-asserting it, in ticks. */
    private static final int HOLD_REASSERT_TICKS = 20;

    /** How far the fleeing fallback runs from where it stands. */
    private static final double FLEE_DISTANCE_BLOCKS = 14.0D;

    /** How long a spot stays claimed if the villager that claimed it never arrives. */
    private static final long CLAIM_TTL_TICKS = 600L;

    private static final Map<Villager, Boolean> ATTACHED = new WeakHashMap<>();
    private static final Map<ServerLevel, Claims> CLAIMS = new WeakHashMap<>();

    private final Villager villager;
    private final int scanInterval;
    private final List<ShelterChoice.Spot> candidates = new ArrayList<>();
    private final List<BlockPos> candidatePositions = new ArrayList<>();

    private TornadoThreatService.Threat threat;
    private BlockPos shelter;
    private long nextScanTick;
    private long nextHoldTick;
    private long nextThreatTick;
    private long nextPanicTick;
    private boolean fleeing;

    private TornadoShelterGoal(Villager villager) {
        this.villager = villager;
        this.scanInterval = Math.max(5, Config.AI_VILLAGER_SHELTER_SCAN_INTERVAL_TICKS.get());
        setFlags(EnumSet.of(Flag.MOVE));
    }

    public static void attach(Villager villager) {
        synchronized (ATTACHED) {
            if (ATTACHED.containsKey(villager)) {
                return;
            }
            ATTACHED.put(villager, Boolean.TRUE);
        }
        villager.goalSelector.addGoal(0, new TornadoShelterGoal(villager));
    }

    @Override
    public boolean canUse() {
        if (!Config.AI_VILLAGER_TORNADO_SHELTER.get() || !(villager.level() instanceof ServerLevel level)) {
            return false;
        }
        long gameTime = level.getGameTime();
        if (gameTime < nextScanTick) {
            return false;
        }
        nextScanTick = gameTime + scanInterval + villager.getRandom().nextInt(Math.max(1, scanInterval));
        threat = TornadoThreatService.nearestThreat(level, villager.position(), TornadoThreatService.configuredRadius()).orElse(null);
        nextThreatTick = gameTime + scanInterval;
        if (threat == null) {
            shelter = null;
            return false;
        }
        // A village that sleeps through a touchdown is the same as a village with no warning at all.
        if (villager.isSleeping() && Config.AI_VILLAGER_SHELTER_WAKE_SLEEPERS.get()) {
            villager.stopSleeping();
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (!(villager.level() instanceof ServerLevel level) || threat == null) {
            return false;
        }
        if (level.getGameTime() >= nextThreatTick) {
            threat = TornadoThreatService.nearestThreat(level, villager.position(), TornadoThreatService.configuredRadius()).orElse(null);
            nextThreatTick = level.getGameTime() + scanInterval;
        }
        return threat != null;
    }

    @Override
    public void start() {
        if (villager.level() instanceof ServerLevel level && threat != null) {
            choose(level);
            walk(level);
        }
    }

    @Override
    public void tick() {
        if (!(villager.level() instanceof ServerLevel level) || threat == null) {
            return;
        }
        if (isSafeHere(level)) {
            hold(level);
            shutDoors(level);
            return;
        }
        long gameTime = level.getGameTime();
        if (shelter == null || gameTime >= nextScanTick) {
            nextScanTick = gameTime + scanInterval;
            choose(level);
        }
        walk(level);
        // A villager out in the open runs, and tells the village it is frightened. Both stop the moment it is
        // under a roof, which is what makes a village under a warning sound like one.
        villager.setSprinting(ShelterPanic.shouldSprint(threat.distanceBlocks()));
        panic(level);
    }

    /**
     * Shut the doors this villager can reach, now that it is inside one of them.
     *
     * <p>Only ever called from under a roof: a villager still walking to shelter must not shut the door it is
     * about to use, and the houses that need shutting are the ones with somebody in them.
     */
    private void shutDoors(ServerLevel level) {
        if (!Config.AI_VILLAGER_CLOSE_DOORS.get()) {
            return;
        }
        ShelterDoors.closeAround(level, villager.position(), threat.distanceBlocks());
    }

    /** A noise while exposed, at a rhythm and pitch set by how close the funnel is. */
    private void panic(ServerLevel level) {
        if (!Config.AI_VILLAGER_PANIC.get()) {
            return;
        }
        long gameTime = level.getGameTime();
        if (gameTime < nextPanicTick) {
            return;
        }
        int interval = ShelterPanic.panicSoundIntervalTicks(threat.distanceBlocks(), false);
        if (interval <= 0) {
            return;
        }
        nextPanicTick = gameTime + interval;
        level.playSound(null, villager.blockPosition(), SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL,
                0.9F, ShelterPanic.panicPitch(threat.distanceBlocks()));
    }

    @Override
    public void stop() {
        if (villager.level() instanceof ServerLevel level) {
            claims(level).release(villager, shelter);
        }
        shelter = null;
        fleeing = false;
        threat = null;
        villager.setSprinting(false);
    }

    /**
     * Pick today's shelter. The candidate list is rebuilt rather than cached because the funnel's own
     * track is what disqualifies half of it, and that changes while the villager walks.
     */
    private void choose(ServerLevel level) {
        if (!collect(level)) {
            if (shelter == null && !fleeing) {
                flee(level);
            }
            return;
        }
        Claims claims = claims(level);
        boolean[] claimed = new boolean[candidates.size()];
        long gameTime = level.getGameTime();
        for (int i = 0; i < candidates.size(); i++) {
            claimed[i] = claims.isHeldByOther(candidatePositions.get(i), villager, gameTime);
        }
        Vec3 from = villager.position();
        // With the track ignored this is the old nearest-roof rule: no lead, so nothing is refused and
        // no spot is penalised for being on the far side of the funnel.
        boolean trackAware = Config.AI_VILLAGER_SHELTER_AVOID_TRACK.get();
        double coreRadius = trackAware ? threat.coreRadiusBlocks() : 0.0D;
        double leadTicks = trackAware ? Config.AI_VILLAGER_SHELTER_LEAD_TICKS.get() : 0.0D;
        int best = ShelterChoice.pick(candidates, claimed, from.x, from.z,
                threat.center().x, threat.center().z,
                threat.motionX() * leadTicks,
                threat.motionZ() * leadTicks,
                coreRadius);
        if (best < 0) {
            // Every roofed place in reach is either in the funnel's way or already taken; running clear
            // beats standing in a doorway the funnel is about to remove.
            if (shelter == null || !fleeing) {
                flee(level);
            }
            return;
        }
        BlockPos chosen = candidatePositions.get(best);
        fleeing = false;
        if (chosen.equals(shelter)) {
            claims.hold(chosen, villager, gameTime);
            return;
        }
        claims.release(villager, shelter);
        shelter = chosen;
        claims.hold(chosen, villager, gameTime);
    }

    /**
     * Read the roofed places around the villager: first the points of interest, which is where beds,
     * bells and workstations actually are and costs a lookup rather than a scan, then a strided column
     * sweep that finds doors and plain cover the POI table cannot name.
     */
    private boolean collect(ServerLevel level) {
        candidates.clear();
        candidatePositions.clear();
        BlockPos origin = villager.blockPosition();
        // The trade and bed POIs are the good shelters, and asking for them is cheap.
        level.getPoiManager()
                .getInRange(type -> true, origin, SCAN_RADIUS_BLOCKS, PoiManager.Occupancy.ANY)
                .forEach(record -> addPoiCandidate(level, record));

        int radius = SCAN_RADIUS_BLOCKS - 1;
        for (int dx = -radius; dx <= radius; dx += COVER_STRIDE) {
            for (int dz = -radius; dz <= radius; dz += COVER_STRIDE) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                BlockPos column = origin.offset(dx, 0, dz);
                for (int dy = -3; dy <= 4; dy++) {
                    BlockPos cursor = column.offset(0, dy, 0);
                    if (!isWalkableShelter(level, cursor)) {
                        continue;
                    }
                    BlockState here = level.getBlockState(cursor);
                    BlockState below = level.getBlockState(cursor.below());
                    ShelterChoice.Kind kind = kindOf(here, below);
                    addCandidate(level, cursor, kind);
                    break;
                }
            }
        }
        return !candidates.isEmpty();
    }

    private void addPoiCandidate(ServerLevel level, PoiRecord record) {
        ShelterChoice.Kind kind = kindFor(record);
        BlockPos poi = record.getPos();
        // Stand beside a bed or a bell, not on it: a villager in a bed is asleep and a villager on a
        // bell is on the bell.
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = poi.relative(direction).immutable();
            if (isWalkableShelter(level, candidate)) {
                addCandidate(level, candidate, kind);
                return;
            }
        }
        BlockPos above = poi.above().immutable();
        if (isWalkableShelter(level, above)) {
            addCandidate(level, above, kind);
        }
    }

    private void addCandidate(ServerLevel level, BlockPos pos, ShelterChoice.Kind kind) {
        for (int i = 0; i < candidatePositions.size(); i++) {
            if (candidatePositions.get(i).equals(pos)) {
                // The POI pass runs first, so a spot already listed keeps its better kind.
                return;
            }
        }
        candidatePositions.add(pos);
        candidates.add(new ShelterChoice.Spot(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, kind));
    }

    private static ShelterChoice.Kind kindFor(PoiRecord record) {
        if (record.getPoiType().is(PoiTypes.HOME)) {
            return ShelterChoice.Kind.BED;
        }
        if (record.getPoiType().is(PoiTypes.MEETING)) {
            return ShelterChoice.Kind.BELL;
        }
        return ShelterChoice.Kind.WORKSTATION;
    }

    private static ShelterChoice.Kind kindOf(BlockState here, BlockState below) {
        if (here.getBlock() instanceof BedBlock || below.getBlock() instanceof BedBlock || here.is(BlockTags.BEDS)) {
            return ShelterChoice.Kind.BED;
        }
        if (here.getBlock() instanceof DoorBlock || here.is(BlockTags.DOORS) || below.is(BlockTags.DOORS)) {
            return ShelterChoice.Kind.DOOR;
        }
        if (here.getBlock() instanceof BellBlock) {
            return ShelterChoice.Kind.BELL;
        }
        return ShelterChoice.Kind.COVER;
    }

    /** Walk to the chosen shelter at a speed set by how close the funnel is. */
    private void walk(ServerLevel level) {
        if (shelter == null) {
            return;
        }
        double urgency = urgency(level);
        double speed = ShelterChoice.walkSpeed(urgency);
        if (villager.getNavigation().isDone() && !isAt(shelter)) {
            villager.getNavigation().moveTo(shelter.getX() + 0.5D, shelter.getY(), shelter.getZ() + 0.5D, speed);
        }
    }

    /**
     * In shelter: hold it. The brain's own stroll behaviour is what would otherwise walk a villager out
     * of a house mid-tornado, so the walk target is cleared and the position re-asserted.
     */
    private void hold(ServerLevel level) {
        if (level.getGameTime() < nextHoldTick) {
            return;
        }
        nextHoldTick = level.getGameTime() + HOLD_REASSERT_TICKS;
        villager.getNavigation().stop();
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.PATH);
    }

    /** Run clear of the track, to the side of it the villager is already on. */
    private void flee(ServerLevel level) {
        fleeing = true;
        shelter = null;
        Vec3 here = villager.position();
        double driftX = threat.motionX();
        double driftZ = threat.motionZ();
        double awayX = here.x - threat.center().x;
        double awayZ = here.z - threat.center().z;
        double length = Math.hypot(awayX, awayZ);
        double dirX;
        double dirZ;
        if (length < 1.0E-3D) {
            // Standing under it: go perpendicular to the drift, which is the shortest way out.
            double drift = Math.hypot(driftX, driftZ);
            if (drift < 1.0E-3D) {
                return;
            }
            dirX = -driftZ / drift;
            dirZ = driftX / drift;
        } else {
            dirX = awayX / length;
            dirZ = awayZ / length;
            double drift = Math.hypot(driftX, driftZ);
            if (drift > 1.0E-3D) {
                // Blend running away with running sideways, so the villager leaves the track instead of
                // racing the funnel down its own path.
                dirX = dirX * 0.4D - (driftZ / drift) * 0.6D;
                dirZ = dirZ * 0.4D + (driftX / drift) * 0.6D;
                double blended = Math.hypot(dirX, dirZ);
                dirX /= blended;
                dirZ /= blended;
            }
        }
        double urgency = urgency(level);
        villager.getNavigation().moveTo(here.x + dirX * FLEE_DISTANCE_BLOCKS, here.y,
                here.z + dirZ * FLEE_DISTANCE_BLOCKS, ShelterChoice.walkSpeed(urgency));
    }

    private double urgency(ServerLevel level) {
        double scareRange = TornadoThreatService.configuredRadius();
        return ShelterChoice.urgency(villager.position().distanceTo(threat.center()), threat.coreRadiusBlocks(), scareRange);
    }

    /** Cover overhead, and not on ground the funnel is about to cross. */
    private boolean isSafeHere(ServerLevel level) {
        if (!hasRoof(level, villager.blockPosition())) {
            return false;
        }
        if (!Config.AI_VILLAGER_SHELTER_AVOID_TRACK.get()) {
            return true;
        }
        Vec3 here = villager.position();
        int lead = Config.AI_VILLAGER_SHELTER_LEAD_TICKS.get();
        return ShelterChoice.isSafe(here.x, here.z, true,
                threat.center().x, threat.center().z,
                threat.motionX() * lead, threat.motionZ() * lead,
                threat.coreRadiusBlocks());
    }

    private boolean isAt(BlockPos pos) {
        Vec3 here = villager.position();
        return here.distanceToSqr(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D) < 2.25D;
    }

    private static boolean isWalkableShelter(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos) || level.canSeeSky(pos.above()) || !hasRoof(level, pos)) {
            return false;
        }
        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        BlockState floor = level.getBlockState(pos.below());
        return feet.getCollisionShape(level, pos).isEmpty()
                && head.getCollisionShape(level, pos.above()).isEmpty()
                && floor.isFaceSturdy(level, pos.below(), Direction.UP);
    }

    private static boolean hasRoof(ServerLevel level, BlockPos pos) {
        for (int dy = 2; dy <= 9; dy++) {
            BlockPos roof = pos.above(dy);
            if (!level.isLoaded(roof)) {
                return false;
            }
            if (!level.getBlockState(roof).getCollisionShape(level, roof).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static Claims claims(ServerLevel level) {
        synchronized (CLAIMS) {
            return CLAIMS.computeIfAbsent(level, ignored -> new Claims());
        }
    }

    /**
     * Which shelters are spoken for. Without this every villager in a village picks the same bed - the
     * nearest one - and the rest crowd its doorstep while the houses around them stand empty.
     */
    private static final class Claims {
        private final Map<BlockPos, Entry> held = new WeakHashMap<>();

        private record Entry(int ownerId, long tick) {
        }

        private boolean isHeldByOther(BlockPos pos, Villager villager, long gameTime) {
            if (!Config.AI_VILLAGER_SHELTER_SPREAD.get()) {
                return false;
            }
            Entry entry = held.get(pos);
            return entry != null && entry.ownerId() != villager.getId() && gameTime - entry.tick() <= CLAIM_TTL_TICKS;
        }

        private void hold(BlockPos pos, Villager villager, long gameTime) {
            held.put(pos, new Entry(villager.getId(), gameTime));
            prune(gameTime);
        }

        private void release(Villager villager, BlockPos pos) {
            if (pos == null) {
                return;
            }
            Entry entry = held.get(pos);
            if (entry != null && entry.ownerId() == villager.getId()) {
                held.remove(pos);
            }
        }

        private void prune(long gameTime) {
            Iterator<Map.Entry<BlockPos, Entry>> iterator = held.entrySet().iterator();
            while (iterator.hasNext()) {
                if (gameTime - iterator.next().getValue().tick() > CLAIM_TTL_TICKS) {
                    iterator.remove();
                }
            }
        }
    }
}
