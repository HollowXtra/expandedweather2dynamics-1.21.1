package com.imbet.village;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.registry.ModVillagers;
import com.imbet.survey.StormDamage;
import com.imbet.survey.TornadoSurveySavedData;
import com.imbet.survey.TrackFootprint;
import com.imbet.weather.TornadoThreatService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The builder: a villager who re-sods scoured ground and puts bark back on stripped trunks around its
 * job site, out of the village's own chests.
 *
 * <p>What it repairs is exactly what the surveyor counts ({@link StormDamage}), so a track a surveyor
 * has rated stops being a track once a builder has worked it. The work order is {@link RepairPolicy}:
 * nearest damage first, a fixed number of blocks per trip, a fixed number of ticks per block. And what
 * it works with is {@link RepairSupplies}: the material a repair costs is the block the repair puts
 * back, taken out of a chest within reach, so rebuilding a town costs a town something. A builder that
 * cannot find the material refuses the job rather than improvising - a re-sodded village appearing out
 * of nothing is the thing this feature exists to stop being free.
 *
 * <p>Being short is not the end of it. What the village has none of in its chests, it buys: {@link
 * VillageExchange} is the price it pays, {@link VillageTradeIntake} is where its money comes from, and
 * a trip that cannot find a block in a chest becomes a trip to the villager holding one, paid for out
 * of the village's own emeralds. A village with cash and something to trade is a village that rebuilds
 * itself; one with neither is a village that waits for a player, and says so at its bench.
 */
public final class BuilderGoal extends Goal {
    /** Blocks between sampled columns while looking for damage. */
    private static final int SCAN_STRIDE = 2;

    /** How far above a column's surface and below it a stripped trunk is looked for. */
    private static final int VERTICAL_REACH = 8;

    /** The widest radius a villager will sweep for damage or for chests, to keep the scans bounded. */
    private static final int MAX_SCAN_RADIUS = 24;
    private static final int MAX_CHEST_RADIUS = 20;

    /** How close the builder has to stand to work on a block, or to open a chest. */
    private static final double WORK_REACH_SQR = 6.25D;

    /** The grid a site is looked for on, how far from its own bench a village will build, and slack. */
    private static final int SITE_STRIDE = 4;
    private static final int SITE_MARGIN = 6;

    /** How much the ground under a footprint may rise or fall before a building will not sit on it. */
    private static final int MAX_RISE = 1;

    /** The precision the recent-track footprint is asked for; see {@link com.imbet.survey.TrackFootprint}. */
    private static final int TRACK_CELL_SIZE = 8;

    /** How many of a building's blocks may be obstructed before the builder writes that site off. */
    private static final int MAX_BLOCKED_STEPS = 6;

    private static final Map<Villager, Boolean> ATTACHED = new WeakHashMap<>();

    /** One piece of damage, and what putting it back costs. */
    private record Job(RepairPolicy.Target target, BlockPos pos, Item material) {
    }

    /**
     * One block of a building: where it goes, what it is made of, and the second cell of a piece that is
     * two blocks - a door's top half, a bed's head. {@code ground} marks the layer that replaces the soil
     * it stands on, which is the one layer allowed to overwrite a block that is not replaceable.
     */
    private record BuildStep(BlockPos pos, VillageBlueprint.Material material, BlockPos second, boolean ground) {
    }

    /** A chest and what is in it, which is what a village owns as far as a builder is concerned. */
    private record Found(BlockPos pos, Map<Item, Integer> contents) {
    }

    private final Villager villager;
    private final List<Job> jobs = new ArrayList<>();
    private final List<RepairPolicy.Target> policyTargets = new ArrayList<>();
    private final Set<BlockPos> skipped = new HashSet<>();

    private final List<BuildStep> buildSteps = new ArrayList<>();
    private final Set<BlockPos> writtenOff = new HashSet<>();

    private BlockPos workPos;
    private Item workMaterial;
    private BlockPos stashPos;
    private long lastWorkTick;
    private long nextAttemptTick;
    private long nextThreatCheckTick;
    private long nextBuildAttemptTick;
    private int doneThisTrip;
    private boolean threatFree;

    // The building this trip is working on, if it is working on one at all.
    private boolean constructing;
    private VillageBlueprint.Building building;
    private BlockPos buildingOrigin;
    private int buildingFacing;
    private int buildIndex;
    private int blockedSteps;
    private Map<Item, Integer> villageStock = Map.of();

    // The shopping trip, if this trip is one: who is selling, what the village came to buy, and how much
    // more of it the village wants. A trip can be both - the chest leg for the money, then the seller.
    private BlockPos sellerPos;
    private Item buyItem;
    private int buyWant;

    private BuilderGoal(Villager villager) {
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
        villager.goalSelector.addGoal(3, new BuilderGoal(villager));
    }

    @Override
    public boolean canUse() {
        if (!Config.AI_VILLAGER_BUILDER_ENABLED.get() || !(villager.level() instanceof ServerLevel level)) {
            return false;
        }
        if (villager.isSleeping() || !isBuilder()) {
            return false;
        }
        long gameTime = level.getGameTime();
        if (gameTime < nextAttemptTick) {
            return false;
        }
        if (!threatFree(level)) {
            return false;
        }
        doneThisTrip = 0;
        lastWorkTick = gameTime;
        skipped.clear();
        workPos = null;
        constructing = false;
        if (scan(level) && pickNext(level)) {
            return true;
        }
        // A repair the village cannot pay for is a job refused, and the damage stays as it is - but a
        // builder standing next to damage it cannot afford is still a builder, and standing idle while the
        // village has nowhere to live is the worst of both. So the village's own ambition gets its turn
        // whether there was nothing to put back or nothing to put it back with.
        if (!planBuild(level)) {
            // And being short is not the same as being finished. What the village cannot find in a chest it
            // can buy, so the last thing tried before giving up is an errand: money out of the chest, and
            // the blocks out of the hands of whoever in the village is holding them.
            if (shop(level)) {
                return true;
            }
            nextAttemptTick = gameTime + Config.AI_VILLAGER_BUILDER_COOLDOWN_TICKS.get();
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (!(villager.level() instanceof ServerLevel level)
                || (workPos == null && stashPos == null && sellerPos == null && !constructing)) {
            return false;
        }
        if (villager.isSleeping() || !isBuilder()) {
            return false;
        }
        if (RepairPolicy.remaining(doneThisTrip, Config.AI_VILLAGER_BUILDER_BLOCKS_PER_TRIP.get()) <= 0) {
            nextAttemptTick = level.getGameTime() + Config.AI_VILLAGER_BUILDER_COOLDOWN_TICKS.get();
            return false;
        }
        if (constructing && nextUnplaced(level) == null) {
            // Every block of the building is where the plan says, which is a building finished - so it is
            // written down before the trip ends, or the village would build it again next time.
            completeBuild(level);
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
        if (!(villager.level() instanceof ServerLevel level)) {
            return;
        }
        if (stashPos != null) {
            if (villager.position().distanceToSqr(Vec3.atCenterOf(stashPos)) > WORK_REACH_SQR) {
                if (villager.getNavigation().isDone()) {
                    walk();
                }
                return;
            }
            villager.getNavigation().stop();
            withdraw(level);
            if (sellerPos != null) {
                // Money in hand: the chest was the first leg of the errand, and the seller is the second.
                walk();
                return;
            }
            if (constructing) {
                // Back with the material: carry on where the building stopped.
                walkToBuild();
            } else if (!pickNext(level)) {
                stopTrip(level);
            }
            return;
        }
        if (sellerPos != null) {
            tickBuy(level);
            return;
        }
        if (constructing) {
            tickBuild(level);
            return;
        }
        if (workPos == null) {
            return;
        }
        if (villager.position().distanceToSqr(Vec3.atCenterOf(workPos)) > WORK_REACH_SQR) {
            if (villager.getNavigation().isDone()) {
                walk();
            }
            return;
        }
        villager.getNavigation().stop();
        if (!RepairPolicy.due(level.getGameTime(), lastWorkTick, Config.AI_VILLAGER_BUILDER_TICKS_PER_BLOCK.get())) {
            return;
        }
        repair(level);
    }

    @Override
    public void stop() {
        // A builder off duty carries nothing: the trip's materials are back in the village's hands, not
        // held up in the air by a villager walking home.
        villager.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        workPos = null;
        workMaterial = null;
        stashPos = null;
        jobs.clear();
        policyTargets.clear();
        skipped.clear();
        constructing = false;
        building = null;
        buildingOrigin = null;
        buildSteps.clear();
        buildIndex = 0;
        blockedSteps = 0;
        villageStock = Map.of();
        sellerPos = null;
        buyItem = null;
        buyWant = 0;
    }

    private void stopTrip(ServerLevel level) {
        nextAttemptTick = level.getGameTime() + Config.AI_VILLAGER_BUILDER_COOLDOWN_TICKS.get();
        workPos = null;
        stashPos = null;
    }

    private void walk() {
        BlockPos destination = sellerPos != null ? sellerPos : stashPos != null ? stashPos : workPos;
        if (destination != null) {
            villager.getNavigation().moveTo(destination.getX() + 0.5D, destination.getY(), destination.getZ() + 0.5D, 0.65D);
        }
    }

    /**
     * Put one block back, paid for out of the builder's own hands.
     *
     * <p>What it goes back to is read from the world - the ground's own neighbours for a scour, the
     * block's own name for a stripped trunk - and the material it spends is that same block, which is
     * what makes the chest accounting add up: one item leaves the village and one block appears.
     */
    private void repair(ServerLevel level) {
        BlockState state = level.getBlockState(workPos);
        BlockState repaired = null;
        if (StormDamage.isScouredSurface(state)) {
            repaired = StormDamage.repairedSurface(level, workPos);
        } else if (StormDamage.isStandingDebarkedLog(state)) {
            repaired = StormDamage.repairedLog(state);
        }
        if (repaired == null) {
            // A trunk whose original cannot be named is left alone rather than replaced with a guess.
            skipped.add(workPos.immutable());
            workPos = null;
            pickNext(level);
            return;
        }
        if (!payFor(repaired.getBlock().asItem(), level)) {
            return;
        }
        level.setBlock(workPos, repaired, 3);
        level.levelEvent(2001, workPos, Block.getId(state));
        level.playSound(null, workPos, repaired.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.5F, 1.0F);
        doneThisTrip++;
        lastWorkTick = level.getGameTime();
        workPos = null;
        if (RepairPolicy.remaining(doneThisTrip, Config.AI_VILLAGER_BUILDER_BLOCKS_PER_TRIP.get()) > 0) {
            pickNext(level);
        }
    }

    /**
     * Spend one of the material the repair needs. With materials on this is what the builder's hands
     * have to be worth: if it is not carrying the block, it goes to fetch it rather than placing one it
     * does not have.
     */
    private boolean payFor(Item material, ServerLevel level) {
        if (!Config.AI_VILLAGER_BUILDER_MATERIALS.get()) {
            return true;
        }
        if (RepairSupplies.covers(purse(), material)) {
            villager.getInventory().removeItemType(material, 1);
            showCarried(material);
            return true;
        }
        workPos = null;
        workMaterial = null;
        // The village has none in reach. Before the repair is refused, the builder tries to buy one: a
        // villager holding the block will sell it for the village's emeralds, and a repair done with a
        // block that was paid for is still a repair the village paid for.
        if (!fetch(level, material) && !shopFor(level, material)) {
            stopTrip(level);
        }
        return false;
    }

    /**
     * Put what the builder is carrying in its arms, where the crossed-arms layer draws it, so a village
     * spending its supplies is something you can watch: the block is visibly held while the builder has
     * any, it shrinks block by block as the trip is worked off, and its arms are empty the moment the last
     * one is placed. The count is the stack's own, so what is on show is the real tally of what the
     * villager is carrying and not a decoration - and the size that tally is drawn at is
     * {@link SuppliesCarry#displayScale}, the same arithmetic the render layer asks for.
     */
    private void showCarried(Item material) {
        if (!Config.AI_VILLAGER_BUILDER_MATERIALS.get()) {
            villager.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            return;
        }
        int carrying = material == null ? 0 : SuppliesCarry.shownCount(villager.getInventory().countItem(material));
        villager.setItemSlot(EquipmentSlot.MAINHAND, carrying > 0 ? new ItemStack(material, carrying) : ItemStack.EMPTY);
    }

    /**
     * Take the next job in the work order: the first one the village can pay for, or - when nothing is
     * affordable - a walk to a chest holding the next block needed. False means the damage stays as it
     * is, which is the whole point of the materials rule.
     */
    private boolean pickNext(ServerLevel level) {
        stashPos = null;
        if (jobs.isEmpty()) {
            return false;
        }
        Vec3 from = villager.position();
        int[] order = RepairPolicy.order(policyTargets, from.x, from.y, from.z);
        List<Item> materials = jobs.stream().map(Job::material).toList();
        if (Config.AI_VILLAGER_BUILDER_MATERIALS.get()) {
            int affordable = RepairSupplies.firstAffordable(order, materials, purse());
            if (affordable >= 0) {
                Job job = jobs.get(affordable);
                workPos = job.pos();
                workMaterial = job.material();
                walk();
                return true;
            }
            // Nothing in hand is usable, so fetch what the nearest job needs - if the village has it.
            for (int index : order) {
                Job job = jobs.get(index);
                if (skipped.contains(job.pos())) {
                    continue;
                }
                return fetch(level, job.material());
            }
            return false;
        }
        // Materials off: the old behaviour, in place and out of nothing.
        for (int index : order) {
            Job job = jobs.get(index);
            if (skipped.contains(job.pos())) {
                continue;
            }
            workPos = job.pos();
            workMaterial = job.material();
            walk();
            return true;
        }
        return false;
    }

    /** Head for a chest that holds this material. False when the village has none in reach. */
    private boolean fetch(ServerLevel level, Item material) {
        BlockPos stash = findStash(level, material);
        if (stash == null) {
            return false;
        }
        // The chest is fetched for a named material: what is taken out is what the next repair needs.
        workMaterial = material;
        stashPos = stash;
        showCarried(material);
        walk();
        return true;
    }

    /** What the builder is carrying, read off its own hands rather than mirrored in a field. */
    private Map<Item, Integer> purse() {
        Map<Item, Integer> purse = new HashMap<>();
        for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
            ItemStack stack = villager.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                purse.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return purse;
    }

    /** Take this block out of the chest the builder is standing at, up to what it can carry. */
    private void withdraw(ServerLevel level) {
        BlockPos pos = stashPos;
        stashPos = null;
        if (pos == null || workMaterial == null || !(level.getBlockEntity(pos) instanceof Container container)) {
            return;
        }
        int want = RepairSupplies.withdrawAmount(container.countItem(workMaterial),
                villager.getInventory().countItem(workMaterial),
                Config.AI_VILLAGER_BUILDER_CARRY_LIMIT.get());
        for (int slot = 0; slot < container.getContainerSize() && want > 0; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || !stack.is(workMaterial)) {
                continue;
            }
            int taken = Math.min(want, stack.getCount());
            ItemStack removed = container.removeItem(slot, taken);
            if (removed.isEmpty()) {
                continue;
            }
            ItemStack leftover = villager.getInventory().addItem(removed);
            if (!leftover.isEmpty()) {
                // Hands full: the surplus goes back on the ground rather than vanishing.
                Block.popResource(level, pos, leftover);
            }
            want -= removed.getCount();
        }
        showCarried(workMaterial);
    }

    /**
     * The nearest chest in reach holding this material, or null. Chests are found by walking columns of
     * ground and asking the blocks above and below them, because vanilla has no point of interest for a
     * container and a village keeps its stores indoors as often as in the open.
     */
    private BlockPos findStash(ServerLevel level, Item material) {
        List<RepairSupplies.Stash<Item>> stashes = new ArrayList<>();
        List<BlockPos> positions = new ArrayList<>();
        for (Found found : stashes(level)) {
            stashes.add(new RepairSupplies.Stash<>(found.pos().getX() + 0.5D, found.pos().getY(), found.pos().getZ() + 0.5D,
                    found.contents()));
            positions.add(found.pos());
        }
        int index = RepairSupplies.pickStash(stashes, material, villager.getX(), villager.getY(), villager.getZ(),
                Config.AI_VILLAGER_BUILDER_CHEST_RANGE_BLOCKS.get());
        return index < 0 ? null : positions.get(index);
    }

    /**
     * Every chest in reach and what is in it: the village's storeroom, as far as a builder is concerned.
     * A repair fetches one material out of it, and construction prices a whole building against it before
     * laying a block - which is why the buildings are made of what the village actually has.
     */
    private List<Found> stashes(ServerLevel level) {
        return stashes(level, jobSiteCenter(), Config.AI_VILLAGER_BUILDER_CHEST_RANGE_BLOCKS.get());
    }

    private static List<Found> stashes(ServerLevel level, BlockPos center, int chestRange) {
        // The walk itself belongs to the village rather than to the builder: a trade payment goes into the
        // same chests a repair is paid out of, so both have to mean the same chests by the same rule.
        List<Found> found = new ArrayList<>();
        for (VillageStore.Chest chest : VillageStore.near(level, center, Math.min(MAX_CHEST_RADIUS, chestRange))) {
            found.add(new Found(chest.pos(), VillageStore.contentsOf(chest.container())));
        }
        return found;
    }

    // ---------------------------------------------------------------- building, not faking it

    /**
     * Work out what the village is owed next and where it can stand: a house, then an office, out of the
     * materials in its own chests.
     *
     * <p>Four refusals, and each one is a rule rather than a failure. Nothing to build if the village already
     * has as many buildings as it is allowed - a house, then an office and a station for each job, so the
     * village ends up owning its own job sites. No building if the ground layer cannot be afforded, because
     * a foundation half laid and abandoned is worse than nothing. No site that rises or falls too much across
     * the footprint, that has anything standing in the space the building needs, or that would land on a
     * building the village already has. And once a site is chosen it is planned from the world every time:
     * the blocks that are already there are skipped, so a building interrupted by nightfall, a storm or a
     * reload is carried on rather than started again somewhere else.
     */
    private boolean planBuild(ServerLevel level) {
        constructing = false;
        building = null;
        buildingOrigin = null;
        buildSteps.clear();
        buildIndex = 0;
        blockedSteps = 0;
        if (!Config.AI_VILLAGER_BUILDER_CONSTRUCTION.get()) {
            return false;
        }
        long gameTime = level.getGameTime();
        if (gameTime < nextBuildAttemptTick) {
            return false;
        }
        nextBuildAttemptTick = gameTime + Config.AI_VILLAGER_BUILDER_COOLDOWN_TICKS.get();
        BlockPos center = jobSiteCenter();
        int radius = Math.min(MAX_SCAN_RADIUS, Config.AI_VILLAGER_BUILDER_SITE_RANGE_BLOCKS.get());
        VillageWorksSavedData works = VillageWorksSavedData.get(level);
        forgetLostWorks(level, works, center, radius);
        // What the village has standing, straight off its own records: a work whose door is gone has
        // already been forgotten above, so a flattened station is a station the village is owed again -
        // including the two that exist to be job sites, which is how a village restaffs itself after a storm.
        VillageBlueprint.Building next = VillagePlan.next(new VillagePlan.Counts(
                works.count("house", center, radius),
                works.count("office", center, radius),
                works.count("station", center, radius),
                works.count("workshop", center, radius),
                works.count("timber_yard", center, radius),
                works.count("weather_station", center, radius)), Config.AI_VILLAGER_BUILDER_BUILDINGS.get());
        if (next == null) {
            return false;
        }
        villageStock = scanStock(level);
        if (!VillagePlan.canStart(next.foundationCost(),
                VillagePlan.totalByMaterial(materialCandidates(), villageStock))) {
            return false;
        }
        for (BlockPos origin : candidateOrigins(center, radius, writtenOff)) {
            int facing = VillageBlueprint.facingToward(center.getX() - origin.getX(), center.getZ() - origin.getZ());
            int[] size = next.placedSize(facing);
            if (works.touchesAny(center, radius, origin.getX(), origin.getZ(), size[0], size[1])) {
                continue;
            }
            BuildSite.Ground ground = probeSite(level, origin, size[0], size[1], next.height());
            if (!BuildSite.fits(ground, MAX_RISE)) {
                continue;
            }
            BlockPos base = new BlockPos(origin.getX(), BuildSite.originY(ground), origin.getZ());
            building = next;
            buildingFacing = facing;
            buildingOrigin = base;
            buildSteps.clear();
            buildSteps.addAll(stepsFor(next, facing, base));
            buildIndex = 0;
            blockedSteps = 0;
            constructing = true;
            if (nextUnplaced(level) == null) {
                // It is all standing here already, and the village simply never wrote it down.
                completeBuild(level);
                return false;
            }
            walkToBuild();
            return true;
        }
        return false;
    }

    /** What the village owns in reach: what this builder is carrying, and every chest around its bench. */
    private Map<Item, Integer> scanStock(ServerLevel level) {
        Map<Item, Integer> stock = new HashMap<>(purse());
        for (Found found : stashes(level)) {
            for (Map.Entry<Item, Integer> entry : found.contents().entrySet()) {
                stock.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }
        return stock;
    }

    /** The material-to-blocks table a plan is priced against. */
    private static Map<VillageBlueprint.Material, List<Item>> materialCandidates() {
        Map<VillageBlueprint.Material, List<Item>> candidates = new LinkedHashMap<>();
        for (VillageBlueprint.Material material : VillageBlueprint.Material.values()) {
            candidates.put(material, VillageMaterials.candidates(material));
        }
        return candidates;
    }

    /**
     * Where a building could stand, nearest first on a fixed grid. Deterministic on purpose: the same
     * village at the same moment picks the same site, which is what lets a half-finished building be found
     * again and carried on instead of started a second time.
     */
    private static List<BlockPos> candidateOrigins(BlockPos center, int radius, Set<BlockPos> writtenOff) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx += SITE_STRIDE) {
            for (int dz = -radius; dz <= radius; dz += SITE_STRIDE) {
                double distance = Math.sqrt((double) (dx * dx + dz * dz));
                if (distance < SITE_MARGIN || distance > radius) {
                    continue;
                }
                BlockPos origin = new BlockPos(center.getX() + dx, 0, center.getZ() + dz);
                if (writtenOff.contains(origin)) {
                    continue;
                }
                candidates.add(origin);
            }
        }
        candidates.sort(Comparator.comparingDouble((BlockPos pos) -> pos.distSqr(center))
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        return candidates;
    }

    /**
     * Read the ground under a footprint and the space above it. Two passes, because the height a building is
     * laid at is the lowest ground under it - which is only known once every column has been read - and the
     * clearance has to be measured up from there.
     */
    private static BuildSite.Ground probeSite(ServerLevel level, BlockPos origin, int width, int depth, int height) {
        int[] heights = new int[width * depth];
        boolean[] blocked = new boolean[width * depth];
        int lowest = Integer.MAX_VALUE;
        for (int dz = 0; dz < depth; dz++) {
            for (int dx = 0; dx < width; dx++) {
                int index = dz * width + dx;
                BlockPos column = new BlockPos(origin.getX() + dx, origin.getY(), origin.getZ() + dz);
                if (!level.isLoaded(column)) {
                    continue;
                }
                BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column).below();
                heights[index] = ground.getY();
                lowest = Math.min(lowest, ground.getY());
            }
        }
        if (lowest > 0) {
            for (int dz = 0; dz < depth; dz++) {
                for (int dx = 0; dx < width; dx++) {
                    int index = dz * width + dx;
                    BlockPos groundPos = new BlockPos(origin.getX() + dx, heights[index], origin.getZ() + dz);
                    BlockState groundState = level.getBlockState(groundPos);
                    if (groundState.isAir() || !groundState.getFluidState().isEmpty()
                            || !groundState.isSolidRender(level, groundPos)) {
                        blocked[index] = true;
                        continue;
                    }
                    for (int y = heights[index] + 1; y < lowest + height; y++) {
                        BlockPos cell = new BlockPos(origin.getX() + dx, y, origin.getZ() + dz);
                        if (!level.getBlockState(cell).canBeReplaced()) {
                            blocked[index] = true;
                            break;
                        }
                    }
                }
            }
        }
        return new BuildSite.Ground(width, depth, heights, blocked);
    }

    /** The building's pieces as world positions, in the order they are laid. */
    private static List<BuildStep> stepsFor(VillageBlueprint.Building building, int facing, BlockPos base) {
        int[] inward = VillageBlueprint.inward(facing);
        List<BuildStep> steps = new ArrayList<>();
        for (VillageBlueprint.Step step : building.placedSteps(facing)) {
            BlockPos pos = base.offset(step.dx(), step.dy(), step.dz());
            BlockPos second = null;
            if (VillageMaterials.isTall(step.material())) {
                second = pos.above();
            } else if (VillageMaterials.isLong(step.material())) {
                second = pos.offset(inward[0], 0, inward[1]);
            }
            steps.add(new BuildStep(pos, step.material(), second, step.dy() == 0));
        }
        return steps;
    }

    /** The next block of the building that is not already where the plan says, or null when it is done. */
    private BuildStep nextUnplaced(ServerLevel level) {
        for (int index = Math.min(buildIndex, buildSteps.size()); index < buildSteps.size(); index++) {
            BuildStep step = buildSteps.get(index);
            if (!isPlaced(level, step)) {
                buildIndex = index;
                return step;
            }
        }
        buildIndex = buildSteps.size();
        return null;
    }

    /** Whether a piece is already standing, both cells of it. */
    private static boolean isPlaced(ServerLevel level, BuildStep step) {
        if (!matches(level, step.pos(), step.material())) {
            return false;
        }
        return step.second() == null || matches(level, step.second(), step.material());
    }

    /** Whether a cell holds a block this material could be: how a half-built wall is picked up again. */
    private static boolean matches(ServerLevel level, BlockPos pos, VillageBlueprint.Material material) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        for (Item candidate : VillageMaterials.candidates(material)) {
            if (state.is(Block.byItem(candidate))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walk to the block being placed and, when the clock says so, place it - paid for out of the village's
     * chests exactly the way a repair is, so a building costs what it looks like it costs.
     */
    private void tickBuild(ServerLevel level) {
        BuildStep step = nextUnplaced(level);
        if (step == null) {
            completeBuild(level);
            return;
        }
        if (villager.position().distanceToSqr(Vec3.atCenterOf(step.pos())) > WORK_REACH_SQR) {
            if (villager.getNavigation().isDone()) {
                walkToBuild();
            }
            return;
        }
        villager.getNavigation().stop();
        if (!RepairPolicy.due(level.getGameTime(), lastWorkTick, Config.AI_VILLAGER_BUILDER_TICKS_PER_BLOCK.get())) {
            return;
        }
        placeBuildStep(level, step);
    }

    private void placeBuildStep(ServerLevel level, BuildStep step) {
        Item item = VillageMaterials.choose(step.material(), villageStock);
        VillageMaterials.Placement placement = VillageMaterials.placementFor(item, step.material(), buildingFacing);
        if (!placeable(level, step)) {
            // Something is standing where the village wanted a block. A few of those is a site to walk away
            // from rather than a wall built around somebody's tree.
            blockedSteps++;
            buildIndex++;
            if (blockedSteps >= MAX_BLOCKED_STEPS) {
                writtenOff.add(buildingOrigin.atY(0));
                stopTrip(level);
            }
            return;
        }
        if (!payFor(item, level)) {
            return;
        }
        level.setBlock(step.pos(), placement.first(), 3);
        if (step.second() != null && placement.second() != null) {
            level.setBlock(step.second(), placement.second(), 3);
        }
        level.playSound(null, step.pos(), placement.first().getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.6F, 1.0F);
        doneThisTrip++;
        lastWorkTick = level.getGameTime();
        buildIndex++;
    }

    /** Whether a cell can take this piece: air, or something replaceable - and soil, for the ground layer. */
    private static boolean placeable(ServerLevel level, BuildStep step) {
        if (step.ground()) {
            return true;
        }
        BlockState current = level.getBlockState(step.pos());
        return current.isAir() || current.canBeReplaced();
    }

    private void walkToBuild() {
        if (buildSteps.isEmpty()) {
            return;
        }
        BlockPos target = buildSteps.get(Math.min(buildIndex, buildSteps.size() - 1)).pos();
        villager.getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 0.65D);
    }

    /**
     * A finished building is written down before the builder goes home, or the village builds it again next
     * time: the plan is counted from what it has, and an unwritten house is a house it does not have.
     */
    private void completeBuild(ServerLevel level) {
        VillageBlueprint.Building finished = building;
        BlockPos origin = buildingOrigin;
        int facing = buildingFacing;
        constructing = false;
        building = null;
        buildingOrigin = null;
        buildSteps.clear();
        if (finished != null && origin != null) {
            int[] size = finished.placedSize(facing);
            VillageWorksSavedData.get(level).record(finished.name(), origin, facing, size[0], size[1],
                    level.getGameTime());
            // Said out loud when it is not clean: a building finished around something that was in the way is
            // still a building, and the village counting it is right - but the holes are worth knowing about.
            ExpandedWeather2Dynamics.LOGGER.info("Builder finished a {} at {} facing {}{}", finished.name(),
                    origin.toShortString(), facingName(facing),
                    blockedSteps > 0 ? " with " + blockedSteps + " blocks left obstructed" : "");
        }
        stopTrip(level);
    }

    private static String facingName(int facing) {
        return switch (facing) {
            case VillageBlueprint.NORTH -> "north";
            case VillageBlueprint.EAST -> "east";
            case VillageBlueprint.SOUTH -> "south";
            default -> "west";
        };
    }

    /**
     * Cross the village's work list off against the world. A building whose door is gone has been taken
     * down - by the next tornado, most likely - and a village that lost its house is owed one again.
     */
    private void forgetLostWorks(ServerLevel level, VillageWorksSavedData works, BlockPos center, int radius) {
        for (VillageWorksSavedData.Work work : works.near(center, radius)) {
            VillageBlueprint.Building blueprint = buildingByName(work.building());
            if (blueprint == null) {
                works.forget(work);
                continue;
            }
            int[] door = blueprint.doorAt(work.facing());
            BlockPos pos = work.origin().offset(door[0], door[1], door[2]);
            if (level.isLoaded(pos) && !matches(level, pos, VillageBlueprint.Material.DOOR)) {
                works.forget(work);
            }
        }
    }

    private static VillageBlueprint.Building buildingByName(String name) {
        for (VillageBlueprint.Building building : VillageBlueprint.villagePlan()) {
            if (building.name().equals(name)) {
                return building;
            }
        }
        return null;
    }

    /**
     * Sweep the ground around the job site for the two kinds of damage, and work out what each one will
     * cost: the block the repair will put back, which is the block the village has to have. A trunk
     * whose original cannot be named is not a job at all, so nobody walks to it.
     */
    private boolean scan(ServerLevel level) {
        jobs.clear();
        policyTargets.clear();
        BlockPos center = jobSiteCenter();
        int radius = Math.min(MAX_SCAN_RADIUS, Config.AI_VILLAGER_BUILDER_RANGE_BLOCKS.get());
        TrackFootprint footprint = recentTrackFootprint(level);
        boolean gated = gated(footprint);
        forEachDamage(level, center, radius, gated, footprint,
                (pos, damage, repaired) -> addJob(level, pos, damage, repaired));
        return !jobs.isEmpty();
    }

    /** What one block of damage is, and what it goes back to. */
    private interface DamageSink {
        void find(BlockPos pos, RepairPolicy.Damage damage, BlockState repaired);
    }

    /**
     * The sweep itself, shared by the repair scan and by the status a player reads off the bench, so the two
     * cannot disagree about what is damaged - which is the failure that matters here, because a status that
     * reports "no damage" while the builder is off repairing some is worse than no status at all.
     *
     * <p>Both kinds of damage are held to the same rule: it is damage if a recent storm's track covered it.
     * A vanilla village is built out of dirt_path and stripped logs, so without that rule a builder spends
     * its life unbarking its neighbours' houses and re-sodding their streets, and never gets to the
     * building it was supposed to put up. With no track data to go on at all - a world with surveys off, a
     * fresh world - the blocks are trusted and the old behaviour stands, because a builder that repairs
     * nothing is a worse failure than one that repairs a road.
     */
    private static void forEachDamage(ServerLevel level, BlockPos center, int radius, boolean gated,
            TrackFootprint footprint, DamageSink sink) {
        int radiusSqr = radius * radius;
        for (int dx = -radius; dx <= radius; dx += SCAN_STRIDE) {
            for (int dz = -radius; dz <= radius; dz += SCAN_STRIDE) {
                if (dx * dx + dz * dz > radiusSqr) {
                    continue;
                }
                BlockPos column = center.offset(dx, 0, dz);
                if (!level.isLoaded(column)) {
                    continue;
                }
                BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
                BlockPos ground = surface.below();
                if (level.isLoaded(ground)
                        && StormDamage.isVillagerRepairableGround(level.getBlockState(ground))
                        && inTrack(gated, footprint, ground)) {
                    sink.find(ground, RepairPolicy.Damage.GROUND, StormDamage.repairedSurface(level, ground));
                }
                for (int dy = -2; dy <= VERTICAL_REACH; dy++) {
                    BlockPos cursor = surface.offset(0, dy, 0);
                    if (!level.isLoaded(cursor)) {
                        continue;
                    }
                    BlockState cursorState = level.getBlockState(cursor);
                    if (StormDamage.isStandingDebarkedLog(cursorState) && inTrack(gated, footprint, cursor)) {
                        sink.find(cursor, RepairPolicy.Damage.LOG, StormDamage.repairedLog(cursorState));
                        break;
                    }
                }
            }
        }
    }

    /** The ground recent storms covered, or null when this village is not asking the question. */
    private static TrackFootprint recentTrackFootprint(ServerLevel level) {
        if (!Config.AI_VILLAGER_BUILDER_TRACK_DAMAGE.get()) {
            return null;
        }
        return TornadoSurveySavedData.get(level).recentFootprint(TRACK_CELL_SIZE, level.getGameTime(),
                Config.SURVEY_MAX_TRACK_AGE_TICKS.get());
    }

    /** Whether there is any track to hold damage to. No track is no evidence, not a clean world. */
    private static boolean gated(TrackFootprint footprint) {
        return footprint != null && footprint.cellCount() > 0;
    }

    private static boolean inTrack(boolean gated, TrackFootprint footprint, BlockPos pos) {
        return !gated || footprint.covers(pos.getX(), pos.getZ());
    }

    /**
     * Why a village is or is not building, as lines for a player standing at the bench.
     *
     * <p>Every number here comes from the helpers the goal itself works from - the same work records, the
     * same site probe, the same chest scan, the same damage sweep - so this cannot report "ready" while the
     * builder sits waiting on something else. It is read on a right-click, so the cost of a footprint probe
     * and a damage sweep is paid once, deliberately, by a player who asked a question.
     */
    public static List<String> status(ServerLevel level, BlockPos bench) {
        int siteRange = Math.min(MAX_SCAN_RADIUS, Config.AI_VILLAGER_BUILDER_SITE_RANGE_BLOCKS.get());
        VillageWorksSavedData works = VillageWorksSavedData.get(level);
        VillagePlan.Counts built = new VillagePlan.Counts(
                works.count("house", bench, siteRange),
                works.count("office", bench, siteRange),
                works.count("station", bench, siteRange),
                works.count("workshop", bench, siteRange),
                works.count("timber_yard", bench, siteRange),
                works.count("weather_station", bench, siteRange));
        int allowance = Math.max(0, Config.AI_VILLAGER_BUILDER_BUILDINGS.get());
        VillageBlueprint.Building next = VillagePlan.next(built, allowance);
        int damage = damageNear(level, bench, Config.AI_VILLAGER_BUILDER_RANGE_BLOCKS.get());

        // What the village owns in reach, counted by material rather than by block: two stacks of spruce and
        // one of cherry are three stacks of planks to a builder about to build one wall out of them. Read
        // before the early return, because a village with everything built can still be short of the dirt
        // its repairs want.
        Map<Item, Integer> stock = new HashMap<>();
        for (Found found : stashes(level, bench, Config.AI_VILLAGER_BUILDER_CHEST_RANGE_BLOCKS.get())) {
            for (Map.Entry<Item, Integer> entry : found.contents().entrySet()) {
                stock.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }
        int purse = stock.getOrDefault(Items.EMERALD, 0);
        if (next == null) {
            return VillageReport.lines(new VillageReport.Status(null, 0, 0, null, 0, 0, false, siteRange,
                    built.total(), allowance, damage,
                    VillageExchange.offerLines(needs(level, bench, stock), purse)));
        }
        Map<VillageBlueprint.Material, Integer> totals = VillagePlan.totalByMaterial(materialCandidates(), stock);
        String missing = null;
        int missingNeeded = 0;
        int missingHeld = 0;
        for (Map.Entry<VillageBlueprint.Material, Integer> entry : next.foundationCost().entrySet()) {
            int held = totals.getOrDefault(entry.getKey(), 0);
            if (held < entry.getValue()) {
                missing = entry.getKey().name().toLowerCase(java.util.Locale.ROOT);
                missingNeeded = entry.getValue();
                missingHeld = held;
                break;
            }
        }

        boolean siteFound = false;
        if (missing == null) {
            int[] size = next.placedSize(VillageBlueprint.NORTH);
            for (BlockPos origin : candidateOrigins(bench, siteRange, Set.of())) {
                if (BuildSite.fits(probeSite(level, origin, size[0], size[1], next.height()), MAX_RISE)) {
                    siteFound = true;
                    break;
                }
            }
        }
        return VillageReport.lines(new VillageReport.Status(next.name(), next.width(), next.depth(), missing,
                missingNeeded, missingHeld, siteFound, siteRange, built.total(), allowance, damage,
                VillageExchange.offerLines(needs(level, bench, stock), purse)));
    }

    /** How many blocks of repairable damage are near a point, by the same sweep the builder works from. */
    public static int damageNear(ServerLevel level, BlockPos center, int radius) {
        int blocks = 0;
        for (int count : repairMaterials(level, center, radius).values()) {
            blocks += count;
        }
        return blocks;
    }

    private void addJob(ServerLevel level, BlockPos pos, RepairPolicy.Damage damage, BlockState repaired) {
        if (repaired == null) {
            return;
        }
        Item material = repaired.getBlock().asItem();
        if (material == Items.AIR) {
            return;
        }
        jobs.add(new Job(new RepairPolicy.Target(pos.getX(), pos.getY(), pos.getZ(), damage), pos.immutable(), material));
        policyTargets.add(new RepairPolicy.Target(pos.getX(), pos.getY(), pos.getZ(), damage));
    }

    private BlockPos jobSiteCenter() {
        Optional<GlobalPos> jobSite = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE);
        if (jobSite.isPresent()) {
            return jobSite.get().pos();
        }
        return villager.blockPosition();
    }

    private boolean isBuilder() {
        return villager.getVillagerData().getProfession() == ModVillagers.BUILDER.get();
    }

    /** Nobody repairs a track while the funnel that made it is still overhead. */
    private boolean threatFree(ServerLevel level) {
        long gameTime = level.getGameTime();
        if (gameTime >= nextThreatCheckTick) {
            threatFree = TornadoThreatService.nearestThreat(level, villager.position(), TornadoThreatService.configuredRadius()).isEmpty();
            nextThreatCheckTick = gameTime + 40L;
        }
        return threatFree;
    }

    // ---------------------------------------------------------------- what the village is short of, and how it buys it

    /** One thing to go and buy: a concrete block, and how many more of it the village needs. */
    private record ShopNeed(Item item, int missing) {
    }

    /** How far from its bench a village looks for a site, and therefore how far it counts its works. */
    private static int siteRange() {
        return Math.min(MAX_SCAN_RADIUS, Config.AI_VILLAGER_BUILDER_SITE_RANGE_BLOCKS.get());
    }

    /**
     * The materials the damage near a point is made of, and how many blocks of each: what a village has to
     * have before it can put a track back. A block whose original cannot be named is not a material and not
     * a job, which is the same rule the work order lives by.
     */
    public static Map<Item, Integer> repairMaterials(ServerLevel level, BlockPos center, int radius) {
        TrackFootprint footprint = recentTrackFootprint(level);
        boolean gated = gated(footprint);
        Map<Item, Integer> materials = new LinkedHashMap<>();
        forEachDamage(level, center, Math.min(MAX_SCAN_RADIUS, radius), gated, footprint,
                (pos, damage, repaired) -> {
                    if (repaired == null) {
                        return;
                    }
                    Item material = repaired.getBlock().asItem();
                    if (material != Items.AIR) {
                        materials.merge(material, 1, Integer::sum);
                    }
                });
        return materials;
    }

    /**
     * What the damage still needs that the village has none of, by material. A village that owns some of
     * a block is not short of it: the builder will walk to the chest for the rest, which is what the
     * chest range is for.
     */
    private static Map<Item, Integer> repairNeeds(ServerLevel level, BlockPos center, Map<Item, Integer> stock) {
        Map<Item, Integer> needs = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> entry : repairMaterials(level, center,
                Config.AI_VILLAGER_BUILDER_RANGE_BLOCKS.get()).entrySet()) {
            int missing = entry.getValue() - Math.max(0, stock.getOrDefault(entry.getKey(), 0));
            if (missing > 0) {
                needs.put(entry.getKey(), missing);
            }
        }
        return needs;
    }

    /**
     * What the village is owed next, counted from its own records. One function, so the builder's plan, the
     * shopping list and the bench's status cannot disagree about what comes next - three copies of "count
     * the works and ask the plan" is three chances for a village to be told it needs a house it has.
     */
    private static VillageBlueprint.Building owed(ServerLevel level, BlockPos center, int radius) {
        VillageWorksSavedData works = VillageWorksSavedData.get(level);
        return VillagePlan.next(new VillagePlan.Counts(
                works.count("house", center, radius),
                works.count("office", center, radius),
                works.count("station", center, radius),
                works.count("workshop", center, radius),
                works.count("timber_yard", center, radius),
                works.count("weather_station", center, radius)), Config.AI_VILLAGER_BUILDER_BUILDINGS.get());
    }

    /**
     * What the village is short of at a bench, as the kinds of goods it buys: the list the bench publishes,
     * the list the builder goes shopping from, and the list a player's offer is measured against.
     *
     * <p>Repairs lead, because a hole in the ground is a thing that has already happened while a house that
     * is not there yet can wait - and because a village that cannot put its own streets back is a village
     * in the middle of the mess it is being asked to build in.
     */
    public static List<VillageExchange.Need> needs(ServerLevel level, BlockPos bench, Map<Item, Integer> stock) {
        Map<VillageExchange.Goods, Integer> repairs = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> entry : repairNeeds(level, bench, stock).entrySet()) {
            VillageExchange.Goods goods = VillageMaterials.goodsOf(entry.getKey());
            if (goods != null) {
                repairs.merge(goods, entry.getValue(), Integer::sum);
            }
        }
        Map<VillageExchange.Goods, Integer> building = new LinkedHashMap<>();
        VillageBlueprint.Building next = owed(level, bench, siteRange());
        if (next != null) {
            for (Map.Entry<VillageBlueprint.Material, Integer> entry : VillageExchange.shortfall(
                    next.foundationCost(), VillagePlan.totalByMaterial(materialCandidates(), stock)).entrySet()) {
                building.merge(VillageMaterials.goods(entry.getKey()), entry.getValue(), Integer::sum);
            }
        }
        return VillageExchange.wants(repairs, building);
    }

    /**
     * What the builder would go and buy, in the order the village wants it: the blocks the damage needs
     * first, then the concrete block the next building is short of. Concrete rather than by kind, because
     * buying means taking a particular stack out of a particular villager's hands.
     */
    private List<ShopNeed> shoppingList(ServerLevel level, Map<Item, Integer> stock) {
        List<ShopNeed> needs = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : repairNeeds(level, jobSiteCenter(), stock).entrySet()) {
            if (VillageMaterials.goodsOf(entry.getKey()) != null) {
                needs.add(new ShopNeed(entry.getKey(), entry.getValue()));
            }
        }
        VillageBlueprint.Building next = owed(level, jobSiteCenter(), siteRange());
        if (next != null) {
            for (Map.Entry<VillageBlueprint.Material, Integer> entry : VillageExchange.shortfall(
                    next.foundationCost(), VillagePlan.totalByMaterial(materialCandidates(), stock)).entrySet()) {
                // The block the village already leans towards, so what it buys is what it was going to
                // build with rather than whatever the price list happens to list first.
                needs.add(new ShopNeed(VillageMaterials.choose(entry.getKey(), stock), entry.getValue()));
            }
        }
        return needs;
    }

    /**
     * Go and buy what the village is short of.
     *
     * <p>Two things have to be true before a builder sets out, and both are checked here rather than
     * assumed: the village has to be short of something it prices, and there has to be somebody nearby who
     * is holding it and willing to sell it. That somebody is another of the village's own villagers, since a
     * villager keeps what a player sells it ({@link VillageTradeIntake}) - and a builder is never sent to
     * relieve a player of their belongings, because a player sells to the village at the bench, on purpose.
     */
    private boolean shop(ServerLevel level) {
        if (!Config.AI_VILLAGER_BUILDER_PURCHASING.get() || !Config.AI_VILLAGER_BUILDER_MATERIALS.get()) {
            return false;
        }
        stashPos = null;
        // The works are crossed off against the world first, or the shopping list is priced against a
        // station the tornado took down last week.
        forgetLostWorks(level, VillageWorksSavedData.get(level), jobSiteCenter(), siteRange());
        Map<Item, Integer> stock = scanStock(level);
        for (ShopNeed need : shoppingList(level, stock)) {
            if (buyFrom(level, need, stock)) {
                return true;
            }
        }
        return false;
    }

    /**
     * One errand for one block, when the need is already known: what a repair that cannot be paid for at a
     * chest turns into.
     */
    private boolean shopFor(ServerLevel level, Item item) {
        if (!Config.AI_VILLAGER_BUILDER_PURCHASING.get() || !Config.AI_VILLAGER_BUILDER_MATERIALS.get()) {
            return false;
        }
        stashPos = null;
        Map<Item, Integer> stock = scanStock(level);
        for (ShopNeed need : shoppingList(level, stock)) {
            if (need.item() == item) {
                return buyFrom(level, need, stock);
            }
        }
        // Damage the list has already stopped counting - a trunk the builder has walked past - is still a
        // block short, so the errand is attempted for one of them rather than given up on.
        return buyFrom(level, new ShopNeed(item, 1), stock);
    }

    /**
     * Set up one errand: agree the count, and fetch the money if it is not already in hand. Both halves of
     * the deal are worked out here rather than at the seller's side, so the builder only sets out on a
     * purchase it has already decided it can complete.
     */
    private boolean buyFrom(ServerLevel level, ShopNeed need, Map<Item, Integer> stock) {
        VillageExchange.Goods goods = VillageMaterials.goodsOf(need.item());
        AbstractVillager seller = nearestSeller(level, need.item());
        if (goods == null || seller == null) {
            return false;
        }
        int count = VillageExchange.buys(goods, need.missing(), seller.getInventory().countItem(need.item()),
                stock.getOrDefault(Items.EMERALD, 0));
        if (count <= 0) {
            return false;
        }
        if (purse().getOrDefault(Items.EMERALD, 0) < VillageExchange.emeraldsFor(goods, count)) {
            // The village's money is in its chests, so this errand has two legs: the chest, then the seller.
            BlockPos stash = findStash(level, Items.EMERALD);
            if (stash == null) {
                return false;
            }
            workMaterial = Items.EMERALD;
            stashPos = stash;
        }
        buyItem = need.item();
        buyWant = need.missing();
        sellerPos = seller.blockPosition().immutable();
        walk();
        return true;
    }

    /** Walk to the seller and, on arrival, buy. */
    private void tickBuy(ServerLevel level) {
        AbstractVillager seller = sellerAt(level, sellerPos);
        if (seller == null) {
            // The villager walked off, or was killed, or was never a villager at all: there is nothing to
            // buy from where the builder was sent.
            sellerPos = null;
            buyItem = null;
            buyWant = 0;
            return;
        }
        if (villager.position().distanceToSqr(Vec3.atCenterOf(sellerPos)) > WORK_REACH_SQR) {
            if (villager.getNavigation().isDone()) {
                walk();
            }
            return;
        }
        villager.getNavigation().stop();
        buy(level, seller);
    }

    /**
     * The deal itself, and the order it happens in is the whole of it: the goods come out of the seller's
     * hands, the emeralds come out of the builder's, and each is handed over only once the other is really
     * in hand. A buyer that cannot pay hands the goods back rather than taking them for nothing, and what a
     * villager is paid is the stack that was actually taken rather than the price it was quoted - the same
     * table the bench quotes a player, so a villager and a player are paid the same for the same stack.
     */
    private void buy(ServerLevel level, AbstractVillager seller) {
        VillageExchange.Goods goods = VillageMaterials.goodsOf(buyItem);
        int count = goods == null ? 0 : VillageExchange.buys(goods, buyWant,
                seller.getInventory().countItem(buyItem), purse().getOrDefault(Items.EMERALD, 0));
        ItemStack bought = count <= 0 ? ItemStack.EMPTY : seller.getInventory().removeItemType(buyItem, count);
        int price = bought.isEmpty() ? 0 : VillageExchange.emeraldsFor(goods, bought.getCount());
        ItemStack paid = price <= 0 ? ItemStack.EMPTY : villager.getInventory().removeItemType(Items.EMERALD, price);
        if (paid.isEmpty()) {
            // Nothing to pay with after all: the goods go back to the villager they came from, and the
            // trip ends having cost the village nothing but the walk.
            handOver(level, seller, bought.copy());
            sellerPos = null;
            buyItem = null;
            buyWant = 0;
            return;
        }
        handOver(level, seller, paid);
        handOver(level, villager, bought.copy());
        Item boughtItem = bought.getItem();
        sellerPos = null;
        buyItem = null;
        buyWant = 0;
        showCarried(boughtItem);
        // Back to what the errand was for: the repair that could not be paid for, or the building whose
        // foundation this block was part of.
        if (!jobs.isEmpty() && pickNext(level)) {
            return;
        }
        planNow(level);
    }

    /**
     * Plan the building again, right now. A purchase is exactly the case the cooldown must not apply to:
     * the builder has just changed what the village can afford, and making it wait half a minute to notice
     * would be the same silence this feature exists to remove, in a new place.
     */
    private void planNow(ServerLevel level) {
        nextBuildAttemptTick = 0L;
        planBuild(level);
    }

    /** The nearest villager holding this block, or null when nobody in the village has one to sell. */
    private AbstractVillager nearestSeller(ServerLevel level, Item item) {
        int range = Config.AI_VILLAGER_BUILDER_PURCHASE_RANGE_BLOCKS.get();
        AbstractVillager best = null;
        double bestDistance = Double.MAX_VALUE;
        for (AbstractVillager other : level.getEntitiesOfClass(AbstractVillager.class,
                new AABB(villager.blockPosition()).inflate(range))) {
            if (other == villager || other.getInventory().countItem(item) <= 0) {
                continue;
            }
            double distance = other.distanceToSqr(villager);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = other;
            }
        }
        return best;
    }

    /** The villager a shopping trip was sent to, if it is still there. */
    private static AbstractVillager sellerAt(ServerLevel level, BlockPos pos) {
        for (AbstractVillager seller : level.getEntitiesOfClass(AbstractVillager.class, new AABB(pos).inflate(2.0D))) {
            return seller;
        }
        return null;
    }

    /** Puts a stack in a villager's hands, and on the ground at their feet if they are full. */
    private static void handOver(ServerLevel level, AbstractVillager who, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack left = who.getInventory().addItem(stack);
        if (!left.isEmpty()) {
            Block.popResource(level, who.blockPosition(), left);
        }
    }
}
