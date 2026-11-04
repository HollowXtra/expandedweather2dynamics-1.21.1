package com.imbet.weather;

import com.imbet.Config;
import com.imbet.network.NetworkSafety;
import com.imbet.network.StructureFlightPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import weather2.weathersystem.storm.StormObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Whole-tree uprooting.
 *
 * <p>Weather2, and this mod's own wind damage, only ever grip a tree one block at a time: a trunk block
 * out-resists the wind on its own, so a violent tornado strips the canopy and debarks the trunk but the
 * tree stays standing. Real violent tornadoes do the opposite - they fail the tree as a whole, take it
 * out of the ground roots and all, and throw it.
 *
 * <p>This service does that. When a strong funnel sits over a tree it takes the trunk and canopy in one
 * step, hands the client a rigid body to fly ({@link StructureFlightPayload.Kind#THROWN}), and resolves
 * the landing itself: a tree is a projectile, so where it comes down matters, and it comes down on
 * whatever is built there. Two bodies on the same block at the same tick is the whole point - the client
 * draws the tree hitting the house, the server crushes the house - so the landing point is decided
 * before the lift and travels in the packet rather than being recomputed by either side.
 *
 * <p>Three rules keep it honest. The throw direction is the storm's own motion, fanned across its
 * forward flank, so a tree is thrown where a tornado actually throws debris and a structure in that fan
 * is what it hits - the search only prefers a built-up column, it never invents a target that is not
 * already in the path. A tree is only lifted if every one of its trunk blocks can actually be taken, so
 * a protected or half-loaded tree never leaves a stump behind while a copy of it flies. And nothing is
 * removed until the landing has been resolved, so a failed lift leaves the tree exactly where it was.
 *
 * <p>What happens at the landing is a decision made before the lift, validated before anything is
 * removed: a tree that comes down on open ground soft enough to take a trunk is FELLED, not shattered.
 * It hits, crushes whatever is under it exactly as before, then settles over
 * {@link StructureFlightPayload#FELLED_SETTLE_TICKS} and becomes a real felled trunk lying along the
 * throw direction - placed as real blocks, rotatable logs where the ground has room for them and
 * leaves trimmed onto the crown - so what the player finds afterwards is a tree on the ground they can
 * break up, not a fading afterimage. The placement is written only on the settle tick and only into
 * columns that are clear at the moment of writing, so a player building in the landing path in those
 * few ticks is never entombed. Ground too built-up or too hard leaves the shatter, and so does any
 * validation failure - a placed trunk that does not match the tree that flew is worse than debris.
 */
public final class TreeLift {
    /** Every tree currently in the air, per level, with the tick its impact resolves on. */
    private static final Map<ResourceKey<Level>, List<Airborne>> AIRBORNE = new HashMap<>();
    private static final Direction[] NEIGHBOURS = {
            Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };
    /** How deep below the surface heightmap a trunk is still looked for. */
    private static final int TRUNK_SEARCH_DEPTH = 10;
    /** How far a trunk may wander horizontally from the block it was found at. A 2x2 trunk is one. */
    private static final int TRUNK_RADIUS = 2;
    /** How far out from the trunk the canopy is collected. */
    private static final int CANOPY_REACH = 4;
    /** A tree needs at least this much foliage to be a tree rather than a wooden pillar. */
    private static final int MIN_CANOPY_BLOCKS = 4;
    /** Random columns sampled per scan before the storm gives up and tries again next interval. */
    private static final int SCAN_ATTEMPTS = 10;
    /** How far a lifted tree is broadcast: past this a player could not see it anyway. */
    private static final double PLUCK_BROADCAST_RANGE = 512.0D;
    /** Downward speed of a landed tree, in blocks per tick. Roughly terminal for a log. */
    private static final double IMPACT_FALL_SPEED = 1.6D;
    /** Share of the fall speed the tree still carries along its throw when it lands. */
    private static final double IMPACT_HORIZONTAL_FRACTION = 0.35D;
    /** Surface hardness a felled trunk is allowed to lie across. Anything harder sheds the trunk. */
    private static final float FELLED_MAX_SURFACE_HARDNESS = 1.5F;
    /** A felled trunk's crown is trimmed harder than the flying one: the tree has shed leaves on the way down. */
    private static final double FELLED_CROWN_FRACTION = 0.6D;
    /**
     * Trunk blocks written per felled tree, capped. The trunk is one rigid body in the air but the
     * terrain it comes down on does not have to be flat for its whole length, so the placement walks the
     * ground; this cap bounds the walk.
     */
    private static final int MAX_FELLED_TRUNK_LENGTH = 26;
    /** Map of per-dimension felled trees whose settle has not finished, waiting to be placed. */
    private static final Map<ResourceKey<Level>, List<Settling>> SETTLING = new HashMap<>();

    private TreeLift() {
    }

    public static void tick(ServerLevel level, StormObject storm, DynamicStormState state) {
        if (level == null || storm == null || state == null || storm.pos == null) {
            return;
        }
        if (!Config.TREE_LIFT_ENABLED.get() || !Config.damageEnabled()) {
            return;
        }
        if (!DynamicStormController.isDynamicVortex(storm) || state.isAbsorbedOrDecaying()) {
            return;
        }
        long gameTime = level.getGameTime();
        // Each storm scans on its own tick inside the interval, so a sky full of tornadoes does not pile
        // every tree search onto the same tick.
        int interval = Math.max(1, Config.TREE_LIFT_SCAN_INTERVAL_TICKS.get());
        if (Math.floorMod(gameTime + storm.ID, interval) != 0L) {
            return;
        }
        if (Config.TREE_LIFT_MAX_CONCURRENT.get() <= 0) {
            return;
        }
        if (TornadoWindField.maxWindMph(storm) < Config.TREE_LIFT_MIN_WIND_MPH.get()) {
            return;
        }
        if (storm.levelCurIntensityStage < Config.TREE_LIFT_MIN_STAGE.get()) {
            return;
        }
        if (airborneCount(level) >= Config.TREE_LIFT_MAX_CONCURRENT.get()) {
            return;
        }
        scan(level, storm, state, gameTime);
    }

    /**
     * Resolves the trees that have finished flying. Called every tick for the level rather than from the
     * storm tick, because the storm that threw a tree can weaken, rope out or die while the tree is still
     * in the air - and a tree that is already airborne has to land whatever happened to the storm.
     */
    public static void tickImpacts(ServerLevel level) {
        tickSettles(level);
        if (level == null) {
            return;
        }
        List<Airborne> due = null;
        synchronized (AIRBORNE) {
            List<Airborne> flying = AIRBORNE.get(level.dimension());
            if (flying == null || flying.isEmpty()) {
                return;
            }
            long gameTime = level.getGameTime();
            for (int index = flying.size() - 1; index >= 0; index--) {
                if (gameTime >= flying.get(index).impactTick()) {
                    if (due == null) {
                        due = new ArrayList<>(2);
                    }
                    due.add(flying.remove(index));
                }
            }
            if (flying.isEmpty()) {
                AIRBORNE.remove(level.dimension());
            }
        }
        if (due == null) {
            return;
        }
        for (Airborne airborne : due) {
            if (level.getGameTime() - airborne.impactTick() > 200L) {
                // Long overdue - the level was unloaded across the landing. Crushing blocks in a chunk
                // nobody watched, a hundred seconds late, is worse than dropping it.
                continue;
            }
            resolveImpact(level, airborne);
        }
    }

    private static void scan(ServerLevel level, StormObject storm, DynamicStormState state, long gameTime) {
        // Follows the damage path like the structure scan, so a wide wedge uproots trees across
        // its whole footprint instead of only near the funnel; capped at 384 to stay dense.
        double radius = Mth.clamp(Math.max(12.0D, state.damagePathWidth() * 0.5D) + 8.0D, 12.0D, 384.0D);
        for (int attempt = 0; attempt < SCAN_ATTEMPTS; attempt++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double distance = Math.sqrt(level.random.nextDouble()) * radius;
            BlockPos column = BlockPos.containing(
                    storm.pos.x + Math.cos(angle) * distance,
                    storm.pos.y,
                    storm.pos.z + Math.sin(angle) * distance);
            if (!level.isLoaded(column)) {
                continue;
            }
            BlockPos surface = BlockGrabSafety.safeSurface(level, column, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
            if (surface == null) {
                continue;
            }
            BlockPos base = findTrunkBase(level, surface);
            if (base == null) {
                continue;
            }
            if (pluck(level, storm, state, base, gameTime)) {
                return;
            }
        }
    }

    /**
     * The bottom block of the trunk in one column. The surface heightmap lands on the topmost log, so
     * walking down finds the trunk; anything else solid first means there is no tree in this column at
     * all - a roof, a wall, terrain - and the search moves on rather than chewing down through it.
     */
    private static BlockPos findTrunkBase(ServerLevel level, BlockPos surface) {
        BlockPos.MutableBlockPos cursor = surface.mutable();
        for (int depth = 0; depth < TRUNK_SEARCH_DEPTH; depth++) {
            cursor.move(0, -1, 0);
            if (cursor.getY() < level.getMinBuildHeight() || !level.isLoaded(cursor)) {
                return null;
            }
            BlockGrabSafety.BlockSample sample = BlockGrabSafety.sample(level, cursor, null);
            if (!sample.valid() || sample.air() || sample.fluid()) {
                continue;
            }
            if (!isLog(sample.state())) {
                return null;
            }
            return trunkBase(level, cursor.immutable());
        }
        return null;
    }

    /** Walks a found log down to the bottom of its trunk, so the lift starts from the ground. */
    private static BlockPos trunkBase(ServerLevel level, BlockPos log) {
        BlockPos.MutableBlockPos cursor = log.mutable();
        for (int step = 0; step < TRUNK_SEARCH_DEPTH * 4; step++) {
            BlockPos below = cursor.below();
            if (below.getY() < level.getMinBuildHeight() || !level.isLoaded(below)) {
                break;
            }
            BlockGrabSafety.BlockSample sample = BlockGrabSafety.sample(level, below, null);
            if (!sample.valid() || !isLog(sample.state())) {
                break;
            }
            cursor.move(0, -1, 0);
        }
        return cursor.immutable();
    }

    /**
     * Uproots one tree, or leaves it standing. Everything is decided before anything is removed: the
     * trunk is validated, the canopy is collected, the landing is aimed and the landing column is
     * confirmed loaded - and only then are the blocks taken, so a tree that fails any of those checks is
     * still exactly where it was.
     */
    private static boolean pluck(ServerLevel level, StormObject storm, DynamicStormState state, BlockPos base, long gameTime) {
        int maxLogs = Config.TREE_LIFT_MAX_LOGS.get();
        List<BlockPos> logs = floodTrunk(level, base, maxLogs);
        if (logs.size() < Config.TREE_LIFT_MIN_LOGS.get() || logs.size() > maxLogs) {
            return false;
        }
        if (trunkHeight(logs) < 2) {
            return false;
        }
        if (!onNaturalGround(level, base.below())) {
            return false;
        }
        if (touchesCraftedWood(level, logs)) {
            return false;
        }
        List<BlockPos> canopy = Config.TREE_LIFT_CARRY_LEAVES.get()
                ? floodCanopy(level, logs, Config.TREE_LIFT_MAX_CANOPY_BLOCKS.get())
                : List.of();
        if (Config.TREE_LIFT_CARRY_LEAVES.get() && canopy.size() < MIN_CANOPY_BLOCKS) {
            return false;
        }
        // Every trunk block has to be takeable. Lifting a tree with a protected or half-loaded block in
        // its trunk would leave a stump standing under a copy of the same tree in the air.
        for (BlockPos pos : logs) {
            if (!takeable(level, pos)) {
                return false;
            }
        }
        Vec3 centre = Vec3.atCenterOf(base).add(0.0D, (trunkHeight(logs) - 1) * 0.5D, 0.0D);
        Vec3 throwDirection = throwDirection(level, storm, base);
        Landing landing = aimLanding(level, base, throwDirection);
        if (landing == null) {
            return false;
        }
        // Whether the tree comes down in one piece is decided now, not at the impact, and validated now
        // rather than trusted: if the ground it is aimed at cannot take a trunk, or a trunk laid there
        // would hang in the air, the tree shatters like any other debris and the placement is never
        // attempted. Validating before the lift also means the packet both sides read agrees.
        boolean felled = Config.TREE_LIFT_FELL_ON_LANDING.get()
                && validateFelledGround(level, landing);
        List<StructureFlightPayload.Block> carried = new ArrayList<>(logs.size() + canopy.size());
        for (BlockPos pos : logs) {
            StructureFlightPayload.Block block = carry(centre, pos, level.getBlockState(pos));
            if (block != null) {
                carried.add(block);
            }
        }
        for (BlockPos pos : canopy) {
            if (!takeable(level, pos)) {
                continue;
            }
            StructureFlightPayload.Block block = carry(centre, pos, level.getBlockState(pos));
            if (block != null) {
                carried.add(block);
            }
        }
        if (carried.size() < logs.size()) {
            return false;
        }
        BlockState trunkState = level.getBlockState(base);
        for (BlockPos pos : logs) {
            BlockGrabSafety.safeDestroyBlock(level, pos, false);
        }
        for (BlockPos pos : canopy) {
            if (takeable(level, pos)) {
                BlockGrabSafety.safeDestroyBlock(level, pos, false);
            }
        }
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, trunkState),
                centre.x, centre.y, centre.z, 24, 1.2D, 1.4D, 1.2D, 0.12D);

        int flightTicks = (int) Math.round(Math.max(1.0D, Config.TREE_LIFT_FLIGHT_SECONDS.get()) * 20.0D);
        double spinSign = DynamicStormController.rotationSign(storm);
        StructureFlightPayload payload = new StructureFlightPayload(storm.ID, StructureFlightPayload.Kind.THROWN,
                centre.x, centre.y, centre.z, base.getY(),
                flightTicks, Config.TREE_LIFT_TUMBLE_RATE.get().floatValue(),
                landing.x(), landing.y(), landing.z(), (float) spinSign, felled, carried);
        NetworkSafety.sendToPlayersNear(level, centre.x, centre.y, centre.z, PLUCK_BROADCAST_RANGE, payload);

        double mass = Math.max(0.0D, Config.TREE_LIFT_IMPACT_CRUSH_MASS.get()) * (0.6D + 0.4D * carried.size() / 40.0D);
        Airborne airborne = new Airborne(gameTime + flightTicks,
                landing.x(), landing.y(), landing.z(), throwDirection.x, throwDirection.z, mass, felled,
                landing.axisX(), landing.axisZ(), logs.size(), trunkState);
        synchronized (AIRBORNE) {
            AIRBORNE.computeIfAbsent(level.dimension(), ignored -> new ArrayList<>(4)).add(airborne);
        }
        return true;
    }

    /**
     * Whether the aimed landing can take a whole trunk lying on it. Three tests: the surface is soft
     * enough for a trunk to rest on rather than smash through, the block below it is natural ground or
     * already-felled wood rather than somebody's floor, and the trunk's own length has clear room along
     * the throw direction - a landing validated against a cliff or a hollow would place a floating
     * telegraph pole.
     */
    private static boolean validateFelledGround(ServerLevel level, Landing landing) {
        BlockPos surface = landing.impactPos().above();
        BlockGrabSafety.BlockSample sample = BlockGrabSafety.sample(level, surface, null);
        if (!sample.valid() || sample.destroySpeed() > FELLED_MAX_SURFACE_HARDNESS) {
            return false;
        }
        BlockState below = level.getBlockState(landing.impactPos());
        if (below.isAir() || isArtificialGround(below)) {
            return false;
        }
        int trunkLength = landing.trunkLength();
        for (int step = 0; step < trunkLength; step++) {
            BlockPos column = surface.offset(landing.axisX() * step, 0, landing.axisZ() * step);
            if (!level.isLoaded(column)) {
                return false;
            }
            BlockPos ground = BlockGrabSafety.safeSurface(level, column, Heightmap.Types.MOTION_BLOCKING);
            if (ground == null) {
                return false;
            }
            int drop = ground.getY() - surface.getY();
            if (drop < -1 || drop > 2) {
                return false;
            }
        }
        return true;
    }

    /** Ground a felled trunk is not allowed to lie across: anything somebody built the surface out of. */
    private static boolean isArtificialGround(BlockState state) {
        return state.is(BlockTags.PLANKS)
                || state.is(BlockTags.WOODEN_STAIRS)
                || state.is(BlockTags.WOODEN_SLABS)
                || state.is(BlockTags.WOODEN_DOORS)
                || state.is(BlockTags.WOODEN_TRAPDOORS)
                || state.is(BlockTags.STONE_BRICKS)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.BRICKS)
                || state.is(Blocks.GLASS)
                || state.is(Blocks.IRON_BLOCK)
                || state.is(Blocks.GOLD_BLOCK)
                || state.is(Blocks.DIAMOND_BLOCK)
                || state.is(Blocks.NETHERITE_BLOCK);
    }

    /**
     * Where the tree comes down. A tornado slings debris across its forward flank, so the throw runs
     * along the storm's own motion and the search fans across that heading, preferring a column with
     * something built on it and falling back to the straight-downwind point. The aim only ever picks
     * between places the throw already reached - it never reaches further than the fan.
     */
    private static Landing aimLanding(ServerLevel level, BlockPos base, Vec3 throwDirection) {
        if (throwDirection.lengthSqr() < 1.0E-4D) {
            return null;
        }
        double distance = Config.TREE_LIFT_THROW_DISTANCE_BLOCKS.get();
        if (distance <= 0.0D) {
            return null;
        }
        double heading = Math.atan2(throwDirection.z, throwDirection.x);
        double baseX = base.getX() + 0.5D;
        double baseZ = base.getZ() + 0.5D;
        // The axis a felled trunk would lie along decides how much room its placement needs. The
        // axis is SIGNED: the trunk lies towards where the throw is going, so its crown lands on the
        // true head, and the ground is validated along the whole of that walk.
        TreeThrowAim.Axis axis = TreeThrowAim.felledAxis(heading);
        int axisX = axis == TreeThrowAim.Axis.X ? (throwDirection.x >= 0.0D ? 1 : -1) : 0;
        int axisZ = axis == TreeThrowAim.Axis.X ? 0 : (throwDirection.z >= 0.0D ? 1 : -1);
        int trunkLength = Math.max(2, Math.min(MAX_FELLED_TRUNK_LENGTH, Config.TREE_LIFT_MAX_LOGS.get()));
        Landing fallback = null;
        for (int index = 0; index < TreeThrowAim.candidateCount(); index++) {
            double[] candidate = TreeThrowAim.candidate(index, baseX, baseZ, heading, distance);
            if (candidate == null) {
                continue;
            }
            BlockPos column = BlockPos.containing(candidate[0], base.getY(), candidate[1]);
            if (!level.isLoaded(column)) {
                continue;
            }
            BlockPos surface = BlockGrabSafety.safeSurface(level, column, Heightmap.Types.MOTION_BLOCKING);
            if (surface == null) {
                continue;
            }
            Landing landed = new Landing(column.getX() + 0.5D, surface.getY(), column.getZ() + 0.5D,
                    surface.below(), trunkLength, axisX, axisZ);
            if (fallback == null) {
                fallback = landed;
            }
            // Landing on a roof is the case worth aiming for; the block under the landing point is
            // what the impact resolves against, so a built block there is a structure about to be hit.
            if (StructureLift.isBuildingBlock(level.getBlockState(surface.below()))) {
                return landed;
            }
        }
        return fallback;
    }

    /** The direction the tree is thrown: the way the storm is travelling, or its local wind if it is stalled. */
    private static Vec3 throwDirection(ServerLevel level, StormObject storm, BlockPos base) {
        Vec3 motion = storm.motion == null ? Vec3.ZERO : new Vec3(storm.motion.x, 0.0D, storm.motion.z);
        if (motion.lengthSqr() > 1.0E-4D) {
            return motion.normalize();
        }
        TornadoWindField.WindSample sample = TornadoWindField.sample(storm, level, Vec3.atCenterOf(base));
        Vec3 wind = new Vec3(sample.mphVector().x, 0.0D, sample.mphVector().z);
        return wind.lengthSqr() > 1.0E-4D ? wind.normalize() : Vec3.ZERO;
    }

    /**
     * The landing, resolved on the server, in two phases.
     *
     * <p>The impact phase is the landing the mod has always resolved: the tree arrives on a slant and
     * crushes whatever is under it, with the crusher's own budget and material rules deciding how much
     * of the structure actually fails.
     *
     * <p>The settle phase is new, and only runs for a tree the pluck decided to fell: exactly
     * {@link StructureFlightPayload#FELLED_SETTLE_TICKS} after the impact - the same tick the client's
     * drawn body finishes settling - the felled trunk is written into the world as real blocks. A
     * felled log is placed upright where the ground has no room to lay it sideways, and the crown is
     * trimmed onto the head of the trunk from the direction it was thrown. Every column is re-checked
     * at the moment of writing, so a player who ran into the landing path during the settle is pushed
     * out of the way of the write, never sealed inside it. If the ground changed since the pluck - a
     * wall raised, the crater deeper than expected - the placement is abandoned whole and the tree
     * remains a shatter.
     */
    private static void resolveImpact(ServerLevel level, Airborne airborne) {
        BlockPos impact = airborne.impactPos();
        if (!level.isLoaded(impact)) {
            return;
        }
        Vec3 velocity = new Vec3(airborne.directionX() * IMPACT_HORIZONTAL_FRACTION, -IMPACT_FALL_SPEED,
                airborne.directionZ() * IMPACT_HORIZONTAL_FRACTION);
        double mass = airborne.mass();
        if (mass > 0.0D) {
            // Always resolved as a violent impact: the lift gate is a violent tornado by definition, and
            // the crusher's material rules are gated on that flag - a landed tree that could not break a
            // plank roof would be the wrong kind of honest.
            StructureCrusher.crush(level, impact, velocity, mass, true);
        }
        BlockState ground = level.getBlockState(impact);
        if (!ground.isAir()) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground),
                    airborne.x(), airborne.y(), airborne.z(), 30, 1.8D, 0.6D, 1.8D, 0.18D);
        }
        level.sendParticles(ParticleTypes.CLOUD,
                airborne.x(), airborne.y() + 0.4D, airborne.z(), 12, 1.6D, 0.5D, 1.6D, 0.05D);
        damageEntities(level, airborne, impact, velocity, mass);
        // The settle is scheduled, not immediate: the client draws the body easing down over the settle
        // ticks, and the trunk is placed on the exact tick that settle ends.
        if (airborne.felled()) {
            synchronized (AIRBORNE) {
                SETTLING.computeIfAbsent(level.dimension(), ignored -> new ArrayList<>(2))
                        .add(new Settling(level.getGameTime() + StructureFlightPayload.FELLED_SETTLE_TICKS,
                                airborne));
            }
        }
    }

    /**
     * Places the trunks of trees whose settle has run out. Called every tick for the level.
     */
    public static void tickSettles(ServerLevel level) {
        if (level == null) {
            return;
        }
        List<Settling> due = null;
        synchronized (SETTLING) {
            List<Settling> settling = SETTLING.get(level.dimension());
            if (settling == null || settling.isEmpty()) {
                return;
            }
            long gameTime = level.getGameTime();
            for (int index = settling.size() - 1; index >= 0; index--) {
                if (gameTime >= settling.get(index).placeTick()) {
                    if (due == null) {
                        due = new ArrayList<>(2);
                    }
                    due.add(settling.remove(index));
                }
            }
            if (settling.isEmpty()) {
                SETTLING.remove(level.dimension());
            }
        }
        if (due == null) {
            return;
        }
        for (Settling settling : due) {
            if (level.getGameTime() - settling.placeTick() > 200L) {
                // Unloaded across the settle: placing a trunk into a chunk nobody watched is worse than
                // losing the log.
                continue;
            }
            placeFelledTrunk(level, settling.airborne());
        }
    }

    /**
     * Writes the felled trunk into the world: real logs laid along the axis the throw chose, rotated
     * where the terrain lets them lie flat, upright where it does not, and a trimmed crown of leaves
     * on the head of the trunk. Every write is a fresh check - the world moved on while the tree flew.
     */
    private static void placeFelledTrunk(ServerLevel level, Airborne airborne) {
        if (!level.isLoaded(airborne.impactPos())) {
            return;
        }
        BlockState trunkState = airborne.trunkState();
        int length = Math.min(airborne.trunkLength(), MAX_FELLED_TRUNK_LENGTH);
        if (length < 1) {
            return;
        }
        int axisX = airborne.axisX();
        int axisZ = airborne.axisZ();
        if (axisX == 0 && axisZ == 0) {
            return;
        }
        BlockState lying = trunkState;
        if (lying.hasProperty(RotatedPillarBlock.AXIS)) {
            lying = lying.setValue(RotatedPillarBlock.AXIS,
                    axisX != 0 ? Direction.Axis.X : Direction.Axis.Z);
        }
        // The crown is trimmed from the flying canopy, not re-grown: a tree that lands has lost leaves.
        int crownBudget = (int) Math.round(Math.max(0.0D, Config.TREE_LIFT_FELL_CANOPY_BLOCKS.get())
                * FELLED_CROWN_FRACTION);
        int startAirY = airborne.impactPos().above().getY();
        int logsPlaced = 0;
        BlockPos head = null;
        for (int step = 0; step < length; step++) {
            BlockPos cell = airborne.impactPos().above().offset(axisX * step, 0, axisZ * step);
            if (!level.isLoaded(cell)) {
                continue;
            }
            BlockPos surface = BlockGrabSafety.safeSurface(level, cell, Heightmap.Types.MOTION_BLOCKING);
            if (surface == null) {
                continue;
            }
            // The trunk lies across gentle terrain only; a step off a ledge or up a wall breaks it and
            // the trunk just ends there, the way a real felled trunk hangs or stops at a slope break.
            int drop = surface.getY() - startAirY;
            if (drop < -1 || drop > 1) {
                continue;
            }
            if (safePlaceIntoAir(level, surface, lying)) {
                logsPlaced++;
                head = surface;
            }
        }
        if (logsPlaced <= 0) {
            return;
        }
        int leavesPlaced = 0;
        if (head != null && crownBudget > 0) {
            // head is the last log placed along the walk - the end the throw carried the trunk towards.
            // The crown sits there as a small blob around and above the log, into air only.
            // The crown sits on the head of the trunk - the end the throw carried forward - as a small
            // blob around and above the last log, into air only.
            for (int dx = -1; dx <= 1 && leavesPlaced < crownBudget; dx++) {
                for (int dy = 0; dy <= 1 && leavesPlaced < crownBudget; dy++) {
                    for (int dz = -1; dz <= 1 && leavesPlaced < crownBudget; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos cell = head.offset(dx, dy, dz);
                        if (!level.isLoaded(cell)) {
                            continue;
                        }
                        if (safePlaceIntoAir(level, cell, crownLeaf(trunkState))) {
                            leavesPlaced++;
                        }
                    }
                }
            }
        }
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, trunkState),
                airborne.x(), airborne.y(), airborne.z(), 18, 1.2D, 0.4D, 1.2D, 0.08D);
    }

    /**
     * A leaf for the felled crown: the fallen trunk's own, so a tree from a mod's wood lands as itself rather than
     * as an oak. The trunk is the only thing the flight carried that says what the tree was, and the leaf is
     * worked out from it by {@link WoodForms} - the wood's own name, then the registry - which answers null for a
     * wood with no leaves at all, a nether stem among them, and for those the game's own oak is used rather than
     * nothing, a crown of no leaves being a bare trunk.
     *
     * <p>Persistent where the leaves allow it, so the crown does not decay away from the trunk it was cut from. A
     * mod's leaves block is under no obligation to carry that property, or to be the game's own
     * {@link LeavesBlock} at all, so a leaf without it is placed as it comes rather than forced.
     */
    private static BlockState crownLeaf(BlockState trunk) {
        Block leaf = WoodForms.leaves(trunk.getBlock());
        BlockState state = leaf == null ? Blocks.OAK_LEAVES.defaultBlockState() : leaf.defaultBlockState();
        return state.hasProperty(LeavesBlock.PERSISTENT)
                ? state.setValue(LeavesBlock.PERSISTENT, Boolean.TRUE)
                : state;
    }

    /** Places one block, but only into air, and never inside a player. */
    private static boolean safePlaceIntoAir(ServerLevel level, BlockPos pos, BlockState state) {
        BlockGrabSafety.BlockSample sample = BlockGrabSafety.sample(level, pos, null);
        if (!sample.valid() || !sample.air() || sample.fluid() || sample.protectedBlock()) {
            return false;
        }
        AABB occupying = new AABB(pos);
        for (Player player : level.players()) {
            if (player.isAlive() && !player.isSpectator() && player.getBoundingBox().intersects(occupying)) {
                return false;
            }
        }
        return BlockGrabSafety.safeSetBlock(level, pos, state, 3);
    }

    private static void damageEntities(ServerLevel level, Airborne airborne, BlockPos impact, Vec3 velocity, double mass) {
        if (!Config.TREE_LIFT_IMPACT_DAMAGES_ENTITIES.get()) {
            return;
        }
        float amount = (float) Mth.clamp(3.0D + mass * 0.9D, 3.0D, 18.0D);
        double radius = 3.0D;
        AABB box = new AABB(impact.getX() - radius, impact.getY() - 1.0D, impact.getZ() - radius,
                impact.getX() + 1.0D + radius, impact.getY() + 4.0D, impact.getZ() + 1.0D + radius);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (entity.isSpectator()) {
                continue;
            }
            entity.hurt(level.damageSources().fallingBlock(null), amount);
            entity.push(velocity.x * 0.35D, 0.22D, velocity.z * 0.35D);
            entity.hurtMarked = true;
        }
    }

    /** The trunk connected to a starting block, bounded so a wooden build cannot be walked as one tree. */
    private static List<BlockPos> floodTrunk(ServerLevel level, BlockPos base, int maxLogs) {
        List<BlockPos> logs = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(base);
        visited.add(base.asLong());
        while (!queue.isEmpty() && logs.size() <= maxLogs) {
            BlockPos pos = queue.poll();
            BlockGrabSafety.BlockSample sample = BlockGrabSafety.sample(level, pos, null);
            if (!sample.valid() || !isLog(sample.state())) {
                continue;
            }
            logs.add(pos);
            for (Direction direction : NEIGHBOURS) {
                BlockPos next = pos.relative(direction);
                if (Math.abs(next.getX() - base.getX()) > TRUNK_RADIUS
                        || Math.abs(next.getZ() - base.getZ()) > TRUNK_RADIUS
                        || next.getY() < base.getY() - 1
                        || next.getY() > base.getY() + maxLogs + 4) {
                    continue;
                }
                if (!level.isLoaded(next) || !visited.add(next.asLong())) {
                    continue;
                }
                queue.add(next);
            }
        }
        return logs;
    }

    /** The canopy attached to a trunk: leaves only, reached from the trunk, bounded in every direction. */
    private static List<BlockPos> floodCanopy(ServerLevel level, List<BlockPos> logs, int cap) {
        Set<Long> trunk = new HashSet<>();
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        for (BlockPos log : logs) {
            trunk.add(log.asLong());
            minX = Math.min(minX, log.getX());
            maxX = Math.max(maxX, log.getX());
            minY = Math.min(minY, log.getY());
            maxY = Math.max(maxY, log.getY());
            minZ = Math.min(minZ, log.getZ());
            maxZ = Math.max(maxZ, log.getZ());
        }
        for (BlockPos log : logs) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos next = log.offset(dx, dy, dz);
                        if (trunk.contains(next.asLong()) || !visited.add(next.asLong())) {
                            continue;
                        }
                        queue.add(next);
                    }
                }
            }
        }
        List<BlockPos> canopy = new ArrayList<>();
        while (!queue.isEmpty() && canopy.size() < cap) {
            BlockPos pos = queue.poll();
            if (pos.getX() < minX - CANOPY_REACH || pos.getX() > maxX + CANOPY_REACH
                    || pos.getY() < minY - 1 || pos.getY() > maxY + CANOPY_REACH
                    || pos.getZ() < minZ - CANOPY_REACH || pos.getZ() > maxZ + CANOPY_REACH
                    || !level.isLoaded(pos)) {
                continue;
            }
            BlockGrabSafety.BlockSample sample = BlockGrabSafety.sample(level, pos, null);
            if (!sample.valid() || !sample.state().is(BlockTags.LEAVES)) {
                continue;
            }
            canopy.add(pos);
            for (Direction direction : NEIGHBOURS) {
                BlockPos next = pos.relative(direction);
                if (!visited.add(next.asLong())) {
                    continue;
                }
                queue.add(next);
            }
        }
        return canopy;
    }

    /**
     * Whether a standing tree is standing in a settlement. Crafted structural wood against the trunk
     * means the trunk is somebody's wall, not a tree; fences, ladders and signs are deliberately not
     * counted, because a fence post beside a tree is scenery and a tree by a field must still come up.
     */
    private static boolean touchesCraftedWood(ServerLevel level, List<BlockPos> logs) {
        for (BlockPos log : logs) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos next = log.offset(dx, dy, dz);
                        if (!level.isLoaded(next)) {
                            continue;
                        }
                        BlockState state = level.getBlockState(next);
                        if (state.is(BlockTags.PLANKS)
                                || state.is(BlockTags.WOODEN_STAIRS)
                                || state.is(BlockTags.WOODEN_SLABS)
                                || state.is(BlockTags.WOODEN_DOORS)
                                || state.is(BlockTags.WOODEN_TRAPDOORS)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean onNaturalGround(ServerLevel level, BlockPos below) {
        if (below.getY() < level.getMinBuildHeight() || !level.isLoaded(below)) {
            return false;
        }
        BlockState state = level.getBlockState(below);
        if (state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.GRAVEL)
                || state.is(Blocks.PODZOL) || state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.MUD)
                || state.is(Blocks.NETHERRACK) || state.is(Blocks.SOUL_SOIL)
                || state.is(Blocks.CRIMSON_NYLIUM) || state.is(Blocks.WARPED_NYLIUM)) {
            return true;
        }
        String path = blockPath(state);
        return path.contains("nylium") || path.contains("_dirt") || path.contains("grass");
    }

    private static boolean takeable(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockGrabSafety.BlockSample sample = BlockGrabSafety.sample(level, pos, null);
        return BlockGrabSafety.canEdit(sample) && sample.valid() && !sample.air() && !sample.fluid();
    }

    /** One carried block, as an offset from the body's centre in half blocks, or null if it is not carryable. */
    private static StructureFlightPayload.Block carry(Vec3 centre, BlockPos pos, BlockState state) {
        if (state == null || state.isAir()) {
            return null;
        }
        return new StructureFlightPayload.Block(
                (int) Math.round((pos.getX() + 0.5D - centre.x) * 2.0D),
                (int) Math.round((pos.getY() + 0.5D - centre.y) * 2.0D),
                (int) Math.round((pos.getZ() + 0.5D - centre.z) * 2.0D),
                Block.getId(state));
    }

    /** How many blocks tall the trunk stands, from the rows it occupies. */
    private static int trunkHeight(List<BlockPos> logs) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (BlockPos log : logs) {
            minY = Math.min(minY, log.getY());
            maxY = Math.max(maxY, log.getY());
        }
        return logs.isEmpty() ? 0 : maxY - minY + 1;
    }

    private static boolean isLog(BlockState state) {
        return state != null && (state.is(BlockTags.LOGS) || state.is(BlockTags.LOGS_THAT_BURN));
    }

    private static String blockPath(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id == null ? "" : id.getPath();
    }

    private static int airborneCount(ServerLevel level) {
        synchronized (AIRBORNE) {
            List<Airborne> flying = AIRBORNE.get(level.dimension());
            return flying == null ? 0 : flying.size();
        }
    }

    /** Where a thrown tree is aimed, and the block the impact resolves against. */
    private record Landing(double x, double y, double z, BlockPos impactPos, int trunkLength,
                           int axisX, int axisZ) {
    }

    /** One tree in the air: when it lands, where, how hard it hits, and whether it stays. */
    private record Airborne(long impactTick, double x, double y, double z,
                            double directionX, double directionZ, double mass, boolean felled,
                            int axisX, int axisZ, int trunkLength, BlockState trunkState) {

        BlockPos impactPos() {
            return BlockPos.containing(x, y - 1.0D, z);
        }
    }

    /** One landed tree waiting for its drawn settle to finish before the trunk is written. */
    private record Settling(long placeTick, Airborne airborne) {
    }
}
