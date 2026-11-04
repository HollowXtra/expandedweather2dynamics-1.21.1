package com.imbet.village;

import com.imbet.Config;
import com.imbet.registry.ModVillagers;
import com.imbet.survey.TornadoSurveySavedData;
import com.imbet.survey.TrackFootprint;
import com.imbet.weather.TornadoThreatService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The lumberjack: a villager who fells the trees a tornado laid down and stocks the village's chests with
 * what they were made of.
 *
 * <p>The work is the mirror of the builder's. A tornado leaves two kinds of trunk: the ones it tore bark
 * off and left standing, which the builder puts back, and the ones it pulled out of the ground and laid
 * the length of its own throw, which are a tree that came down and cannot be stood up again. The second
 * kind is this job - the logs are taken out of the world, sawed where the village is short of planks, and
 * put in a chest, so a village flattened by a storm ends up holding the materials its own builder rebuilds
 * from. One storm, two jobs, and neither undoes the other: what one repairs is standing and what the other
 * fells is lying.
 *
 * <p>A felled trunk is recognised by its shape and held to the storm's own ground. Shape, because a
 * felled trunk is a run of logs lying on their side, one log thick, resting on the ground - a single
 * lying log is a log somebody put down, and a stack of them is a wall. Ground, because a village built out
 * of logs has lying logs in it too, and a lumberjack that took a player's woodpile apart would be a worse
 * neighbour than one that left a tree lying in a field. So when the question can be asked - when there is
 * storm history to ask - it is; and when there is no history at all the answer is no, which is the one
 * place this job is deliberately stricter than the builder. A builder that repairs nothing leaves damage;
 * a lumberjack that fells the wrong thing takes what is not its own.
 */
public final class LumberjackGoal extends Goal {
    /** Blocks between sampled columns while looking for timber. */
    private static final int SCAN_STRIDE = 2;

    /** The widest radius a villager will sweep, so a bad config cannot turn a fell into a chunk tour. */
    private static final int MAX_SCAN_RADIUS = 32;

    /** How close the lumberjack has to stand to a log to take it, or to the mill to saw. */
    private static final double WORK_REACH_SQR = 6.25D;

    /** The precision the recent-track footprint is asked for; see {@link TrackFootprint}. */
    private static final int TRACK_CELL_SIZE = 8;

    /** How much of a lying log's neighbourhood is looked at for the next log of the same trunk. */
    private static final int[] STEP_ORDER = {0, -1, 1};

    private static final Map<Villager, Boolean> ATTACHED = new WeakHashMap<>();

    private final Villager villager;

    // The trip: what it set out to do, what it is carrying, and where it is going.
    private BlockPos millPos;
    private BlockPos target;
    private List<BlockPos> trunk = List.of();
    private TimberStand.Load load;
    private Item wood;
    private boolean carrying;
    private int felledThisTrip;
    private long lastFellTick;

    private long nextAttemptTick;
    private long nextThreatCheckTick;
    private boolean threatFree;

    private LumberjackGoal(Villager villager) {
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
        villager.goalSelector.addGoal(3, new LumberjackGoal(villager));
    }

    @Override
    public boolean canUse() {
        if (!Config.AI_VILLAGER_LUMBERJACK_ENABLED.get() || !(villager.level() instanceof ServerLevel level)) {
            return false;
        }
        if (villager.isSleeping() || !isLumberjack()) {
            return false;
        }
        if (load != null && carrying) {
            // Holding logs: delivering them comes before felling anything else, whatever interrupted the
            // walk - a shelter, a night, another villager's errand.
            return threatFree(level);
        }
        long gameTime = level.getGameTime();
        if (gameTime < nextAttemptTick) {
            return false;
        }
        nextAttemptTick = gameTime + Config.AI_VILLAGER_LUMBERJACK_COOLDOWN_TICKS.get() / 4L;
        if (!threatFree(level)) {
            return false;
        }
        return findWork(level);
    }

    @Override
    public boolean canContinueToUse() {
        if (load == null || millPos == null || !(villager.level() instanceof ServerLevel level)) {
            return false;
        }
        if (villager.isSleeping() || !isLumberjack()) {
            return false;
        }
        return threatFree(level);
    }

    @Override
    public void start() {
        walk();
    }

    @Override
    public void tick() {
        if (!(villager.level() instanceof ServerLevel level) || load == null || millPos == null) {
            return;
        }
        if (carrying) {
            target = millPos;
            if (!near(millPos)) {
                walk();
                return;
            }
            villager.getNavigation().stop();
            sawAndStock(level);
            return;
        }
        BlockPos next = nextLog(level);
        if (next == null) {
            // The trunk is gone - somebody else felled it, or a storm took it - so the trip ends rather
            // than the villager standing in a field looking at where a tree used to be.
            finish(level);
            return;
        }
        target = next;
        if (!near(next)) {
            walk();
            return;
        }
        villager.getNavigation().stop();
        fell(level, next);
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        target = null;
        if (load == null || !carrying) {
            // Nothing in hand: the tree it was walking to is still standing in the world, so the trip is
            // simply abandoned rather than half-done.
            trunk = List.of();
            load = null;
            wood = null;
            millPos = null;
        }
    }

    // ---------------------------------------------------------------- finding a tree to fell

    /**
     * Find the nearest trunk, decide the whole trip, and check the village can take what it would bring
     * back - before anything is lifted, so a villager never fells a tree it has nowhere to put.
     */
    private boolean findWork(ServerLevel level) {
        millPos = jobSiteCenter();
        int chestRange = chestRange();
        if (VillageStore.near(level, millPos, chestRange).isEmpty()) {
            // Nowhere to stock it: a lumberjack with no chest is a villager carrying logs around forever.
            return false;
        }
        List<List<BlockPos>> stands = stands(level, millPos, scanRange(), footprint(level));
        if (stands.isEmpty()) {
            return false;
        }
        List<BlockPos> candidate = stands.get(0);
        BlockState state = level.getBlockState(candidate.get(0));
        Item log = state.getBlock().asItem();
        if (log == Items.AIR) {
            return false;
        }
        Map<Item, Integer> stock = VillageStore.contents(level, millPos, chestRange);
        int plankWanted = 0;
        int logWanted = 0;
        for (VillageExchange.Need need : BuilderGoal.needs(level, millPos, stock)) {
            if (need.goods() == VillageExchange.Goods.PLANKS) {
                plankWanted = need.missing();
            } else if (need.goods() == VillageExchange.Goods.LOG) {
                logWanted = need.missing();
            }
        }
        TimberStand.Load planned = TimberStand.load(candidate.size(),
                Config.AI_VILLAGER_LUMBERJACK_LOGS_PER_TRIP.get(), plankWanted, logWanted);
        if (planned.isEmpty()) {
            return false;
        }
        // The store has to have room for both stacks of the load, because both are going into it and the
        // alternative is dropping logs in the grass beside a chest that was full.
        if (planned.logs() > 0
                && !VillageStore.fits(level, millPos, chestRange, new ItemStack(log, planned.logs()))) {
            return false;
        }
        Item planks = planksFor(log);
        if (planned.planks() > 0 && (planks == null
                || !VillageStore.fits(level, millPos, chestRange, new ItemStack(planks, planned.planks())))) {
            return false;
        }
        trunk = candidate;
        load = planned;
        wood = log;
        carrying = false;
        felledThisTrip = 0;
        return true;
    }

    /**
     * The felled trunks within range, nearest first.
     *
     * <p>Every column of ground in the circle is looked at, and a trunk is recognised by walking out from
     * the first lying log found: along its own axis, one log thick, allowing a step down or up so a trunk
     * lying across rough ground is still one trunk. Cells already walked are claimed, so a run of six logs
     * is one trunk rather than six.
     */
    private static List<List<BlockPos>> stands(ServerLevel level, BlockPos mill, int radius,
            TrackFootprint footprint) {
        List<List<BlockPos>> trunks = new ArrayList<>();
        Set<BlockPos> claimed = new HashSet<>();
        int radiusSqr = radius * radius;
        for (int dx = -radius; dx <= radius; dx += SCAN_STRIDE) {
            for (int dz = -radius; dz <= radius; dz += SCAN_STRIDE) {
                if (dx * dx + dz * dz > radiusSqr) {
                    continue;
                }
                BlockPos column = mill.offset(dx, 0, dz);
                if (!level.isLoaded(column)) {
                    continue;
                }
                BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
                for (int dy = 0; dy >= -1; dy--) {
                    BlockPos pos = surface.offset(0, dy, 0);
                    if (!level.isLoaded(pos) || claimed.contains(pos)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (!isLyingLog(state) || !onGround(level, pos) || !inStorm(footprint, pos)) {
                        continue;
                    }
                    List<BlockPos> run = walkRun(level, pos, state, footprint, claimed);
                    claimed.addAll(run);
                    if (TimberStand.isFelledRun(run.size())) {
                        trunks.add(nearestFirst(run, mill));
                    }
                }
            }
        }
        trunks.sort(Comparator.comparingDouble(run -> distanceTo(run.get(0), mill)));
        return trunks;
    }

    /** Walk out from one lying log along its own axis, collecting the trunk it is part of. */
    private static List<BlockPos> walkRun(ServerLevel level, BlockPos seed, BlockState seedState,
            TrackFootprint footprint, Set<BlockPos> claimed) {
        String wood = woodName(seedState);
        if (wood == null) {
            return List.of(seed);
        }
        List<BlockPos> run = new ArrayList<>();
        run.add(seed);
        Direction.Axis axis = seedState.getValue(RotatedPillarBlock.AXIS);
        for (int sign : new int[] {-1, 1}) {
            BlockPos cursor = seed;
            while (true) {
                BlockPos next = step(level, cursor, axis, sign, wood, footprint, claimed);
                if (next == null) {
                    break;
                }
                run.add(next);
                cursor = next;
            }
        }
        return run;
    }

    /**
     * The next log of the same trunk along the axis, allowing the one step of height a trunk lying across
     * rough ground needs. A log with another lying log directly above it is not part of a trunk - it is the
     * second course of somebody's wall - so it is refused.
     */
    private static BlockPos step(ServerLevel level, BlockPos from, Direction.Axis axis, int sign, String wood,
            TrackFootprint footprint, Set<BlockPos> claimed) {
        int dx = axis == Direction.Axis.X ? sign : 0;
        int dz = axis == Direction.Axis.Z ? sign : 0;
        for (int dy : STEP_ORDER) {
            BlockPos candidate = from.offset(dx, dy, dz);
            if (!level.isLoaded(candidate) || claimed.contains(candidate)) {
                continue;
            }
            BlockState state = level.getBlockState(candidate);
            if (!isLyingLog(state) || !sameWood(state, wood) || !inStorm(footprint, candidate)) {
                continue;
            }
            if (isLyingLog(level.getBlockState(candidate.above()))) {
                continue;
            }
            claimed.add(candidate);
            return candidate;
        }
        return null;
    }

    /** A log lying on its side: the shape a torn-out tree has and a standing trunk does not. */
    private static boolean isLyingLog(BlockState state) {
        return state.is(BlockTags.LOGS)
                && state.hasProperty(RotatedPillarBlock.AXIS)
                && state.getValue(RotatedPillarBlock.AXIS) != Direction.Axis.Y;
    }

    /** Whether the log is resting on something rather than hanging in the air or buried in a wall. */
    private static boolean onGround(ServerLevel level, BlockPos pos) {
        BlockPos below = pos.below();
        return level.isLoaded(below)
                && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    /**
     * Whether this cell is on ground a recent storm's track covered.
     *
     * <p>Three answers, not two. A null footprint means the question was not asked - the config says fell
     * what looks felled - and everything counts. An empty footprint means the question was asked and there
     * is no storm history: a lying log in a village with no track near it is a woodpile, so nothing counts.
     * And a footprint with cells in it counts exactly the ground the storm went over.
     */
    private static boolean inStorm(TrackFootprint footprint, BlockPos pos) {
        if (footprint == null) {
            return true;
        }
        return footprint.covers(pos.getX(), pos.getZ());
    }

    /** The same footprint the builder reads, or null when this village is not asking the question. */
    private static TrackFootprint footprint(ServerLevel level) {
        if (!Config.AI_VILLAGER_LUMBERJACK_TRACK_TIMBER.get()) {
            return null;
        }
        return TornadoSurveySavedData.get(level).recentFootprint(TRACK_CELL_SIZE, level.getGameTime(),
                Config.SURVEY_MAX_TRACK_AGE_TICKS.get());
    }

    /**
     * Which wood a log is, read from its name: oak logs, oak wood and stripped oak logs are all the one
     * oak, because a tree the storm broke in half does not stop being a single tree halfway along.
     */
    private static String woodName(BlockState state) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return TimberStand.planksName(key.getPath());
    }

    /** The same wood, and null never matches null: a block whose name says nothing about a wood is no trunk. */
    private static boolean sameWood(BlockState state, String wood) {
        String candidate = woodName(state);
        return candidate != null && candidate.equals(wood);
    }

    /** The cells of a trunk, nearest first, so the villager works from its own end of the tree. */
    private static List<BlockPos> nearestFirst(List<BlockPos> run, BlockPos mill) {
        List<BlockPos> ordered = new ArrayList<>(run);
        ordered.sort(Comparator.comparingDouble(pos -> distanceTo(pos, mill)));
        return ordered;
    }

    private static double distanceTo(BlockPos pos, BlockPos from) {
        double dx = pos.getX() - from.getX();
        double dz = pos.getZ() - from.getZ();
        return dx * dx + dz * dz;
    }

    // ---------------------------------------------------------------- the work itself

    /** The next log of the trunk that is still there, or null when the trunk has been used up. */
    private BlockPos nextLog(ServerLevel level) {
        for (BlockPos pos : trunk) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            if (isLyingLog(level.getBlockState(pos))) {
                return pos;
            }
        }
        return null;
    }

    /**
     * Take one log off the trunk, one every few ticks, the way a builder places one block at a time.
     *
     * <p>The block is only removed once the item is really in the villager's hands: a villager with a full
     * inventory stops felling rather than deleting a log by dropping it on the floor.
     */
    private void fell(ServerLevel level, BlockPos pos) {
        if (level.getGameTime() - lastFellTick < Config.AI_VILLAGER_LUMBERJACK_TICKS_PER_LOG.get()) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        ItemStack log = new ItemStack(state.getBlock().asItem());
        if (log.isEmpty()) {
            finish(level);
            return;
        }
        if (!villager.getInventory().addItem(log).isEmpty()) {
            // Nowhere to put it: the trip ends and the villager goes back to the mill with what it has.
            carrying = true;
            walk();
            return;
        }
        level.removeBlock(pos, false);
        level.playSound(null, pos, state.getSoundType().getBreakSound(), SoundSource.BLOCKS, 0.55F, 0.9F);
        felledThisTrip++;
        lastFellTick = level.getGameTime();
        if (felledThisTrip >= load.harvested()) {
            carrying = true;
            walk();
        }
    }

    /**
     * Saw the load at the mill and put it in the village's chests.
     *
     * <p>The sawing is the village's own arithmetic ({@link TimberStand}), decided before the walk: the logs
     * it wanted as logs come back as logs, the rest become planks because a log is four of them. A wood
     * whose planks cannot be named keeps its logs instead - the load is not deleted to satisfy a rule - and
     * whatever the chests cannot take is dropped for the villager's own village to pick up rather than lost.
     */
    private void sawAndStock(ServerLevel level) {
        TimberStand.Load planned = load;
        ItemStack logs = wood == null ? ItemStack.EMPTY : villager.getInventory().removeItemType(wood, planned.logs());
        ItemStack sawed = wood == null ? ItemStack.EMPTY : villager.getInventory().removeItemType(wood, planned.sawed());
        Item planks = planksFor(wood);
        if (!sawed.isEmpty() && planks != null) {
            stock(level, new ItemStack(planks, TimberStand.planksFrom(sawed.getCount())));
            level.playSound(null, millPos, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.6F, 0.8F);
        } else if (!sawed.isEmpty()) {
            // No name for this wood's planks, so the logs stay logs rather than the load vanishing.
            ItemStack merged = logs.isEmpty() ? sawed : logs.copyWithCount(logs.getCount() + sawed.getCount());
            stock(level, merged);
            logs = ItemStack.EMPTY;
        }
        stock(level, logs);
        finish(level);
    }

    /** Put a stack into the village's chests; what will not fit is dropped beside the mill, not deleted. */
    private void stock(ServerLevel level, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack left = VillageStore.deposit(level, millPos, chestRange(), stack);
        if (!left.isEmpty()) {
            Block.popResource(level, villager.blockPosition(), left);
        }
    }

    private void finish(ServerLevel level) {
        villager.getNavigation().stop();
        trunk = List.of();
        load = null;
        wood = null;
        carrying = false;
        felledThisTrip = 0;
        millPos = null;
        nextAttemptTick = level.getGameTime() + Config.AI_VILLAGER_LUMBERJACK_COOLDOWN_TICKS.get();
    }

    // ---------------------------------------------------------------- the walk, and what the mill says

    private void walk() {
        BlockPos destination = carrying ? millPos : target;
        if (destination != null) {
            villager.getNavigation().moveTo(destination.getX() + 0.5D, destination.getY(), destination.getZ() + 0.5D,
                    0.65D);
        }
    }

    private boolean near(BlockPos pos) {
        return villager.position().distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)
                <= WORK_REACH_SQR;
    }

    private BlockPos jobSiteCenter() {
        Optional<GlobalPos> jobSite = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE);
        if (jobSite.isPresent()) {
            return jobSite.get().pos();
        }
        return villager.blockPosition();
    }

    private boolean isLumberjack() {
        return villager.getVillagerData().getProfession() == ModVillagers.LUMBERJACK.get();
    }

    /** Nobody fells a track while the funnel that made it is still overhead. */
    private boolean threatFree(ServerLevel level) {
        long gameTime = level.getGameTime();
        if (gameTime >= nextThreatCheckTick) {
            threatFree = TornadoThreatService.nearestThreat(level, villager.position(),
                    TornadoThreatService.configuredRadius()).isEmpty();
            nextThreatCheckTick = gameTime + 40L;
        }
        return threatFree;
    }

    private static int scanRange() {
        return Math.min(MAX_SCAN_RADIUS, Math.max(8, Config.AI_VILLAGER_LUMBERJACK_RANGE_BLOCKS.get()));
    }

    private static int chestRange() {
        return Math.min(VillageStore.MAX_RADIUS, Math.max(4, Config.AI_VILLAGER_LUMBERJACK_CHEST_RANGE_BLOCKS.get()));
    }

    /** The planks a log of this kind saws into, read from the item's own name; null when there are none. */
    private static Item planksFor(Item log) {
        if (log == null || log == Items.AIR) {
            return null;
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(log);
        if (key == null) {
            return null;
        }
        String planks = TimberStand.planksName(key.getPath());
        if (planks == null) {
            return null;
        }
        Item resolved = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(key.getNamespace(), planks));
        return resolved == Items.AIR ? null : resolved;
    }

    /**
     * Why the mill is or is not working, as lines for a player standing at it.
     *
     * <p>The same scan the goal itself runs, so the mill cannot report standing timber the lumberjack does
     * not have, or timber the goal would refuse because there is nowhere to put it.
     */
    public static List<String> status(ServerLevel level, BlockPos mill) {
        int range = scanRange();
        int chestRange = chestRange();
        Map<Item, Integer> stock = VillageStore.contents(level, mill, chestRange);
        boolean reachable = !VillageStore.near(level, mill, chestRange).isEmpty();
        int logs = 0;
        int planks = 0;
        for (Map.Entry<Item, Integer> entry : stock.entrySet()) {
            VillageExchange.Goods goods = VillageMaterials.goodsOf(entry.getKey());
            if (goods == VillageExchange.Goods.LOG) {
                logs += entry.getValue();
            } else if (goods == VillageExchange.Goods.PLANKS) {
                planks += entry.getValue();
            }
        }
        int perTrip = Config.AI_VILLAGER_LUMBERJACK_LOGS_PER_TRIP.get();
        boolean room = reachable
                && VillageStore.fits(level, mill, chestRange, new ItemStack(Items.OAK_LOG, perTrip))
                && VillageStore.fits(level, mill, chestRange,
                        new ItemStack(Items.OAK_PLANKS, TimberStand.planksFrom(perTrip)));
        return VillageReport.timberLines(new VillageReport.Timber(
                stands(level, mill, range, footprint(level)).size(), logs, planks, reachable, room, perTrip));
    }
}
