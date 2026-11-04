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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Whole-structure demolition.
 *
 * <p>Block-by-block pick-up can never lift a house: each block is judged on its own, so a plank wall
 * only loses the planks the funnel happens to sample, and a brick shell loses almost nothing because
 * every individual block out-resists the wind. Real violent tornadoes do the opposite - they fail the
 * structure as a whole, tear it off its foundation, and break it apart in the air.
 *
 * <p>This service does that. When a strong tornado's funnel sits over a building, it commits the
 * building to a demolition episode: one bounded flood fill finds the connected structure, and the
 * structure is then torn away in a scheduled sweep instead of a per-block dice roll. The schedule is
 * physical rather than random - it works one storey at a time from the top down, the roof first
 * because nothing braces it, then each storey below once the one above has stopped bracing it, with
 * the foundation course last - and every torn block is handed to Weather2's own debris spawner so the
 * funnel carries and lifts it exactly like the blocks it grabs itself.
 *
 * <p>Three rules keep it honest. A block is only torn if the funnel is actually delivering
 * {@code blockTearMph} to it, so a storm that grazes a house still only ruins the part it touched.
 * Masonry needs masonry-strength wind, so an EF2 strips a timber house while a brick shell keeps its
 * walls. And a skipped block is never retried within the episode, so the wave can never stall behind
 * a protected chest or a block some other pass already took.
 */
public final class StructureLift {
    private static final Map<StormObject, Episode> EPISODES = new WeakHashMap<>();
    /**
     * Structures a storm already failed to make any progress on. A brick shell in an EF2 wind passes
     * the gate for its roof but not for its walls, so without this the storm would re-detect the
     * remaining shell every scan interval and burn a flood fill on it each time.
     */
    private static final Map<StormObject, BarrenLift> BARREN = new WeakHashMap<>();
    private static final long BARREN_COOLDOWN_TICKS = 400L;
    private static final double BARREN_SAME_STRUCTURE_BLOCKS = 32.0D;
    private static final Direction[] NEIGHBOURS = {
            Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };
    /** How deep below the surface heightmap a building wall is still looked for. */
    private static final int SEED_SEARCH_DEPTH = 24;
    /** Random columns sampled per scan before the storm gives up and tries again next interval. */
    private static final int SCAN_ATTEMPTS = 12;
    /** How far a lifted building is broadcast: past this a player could not see it anyway. */
    private static final double PLUCK_BROADCAST_RANGE = 512.0D;

    private StructureLift() {
    }

    public static void tick(ServerLevel level, StormObject storm, DynamicStormState state) {
        if (level == null || storm == null || state == null || storm.pos == null) {
            return;
        }
        if (!Config.STRUCTURE_LIFT_ENABLED.get() || !Config.damageEnabled()) {
            return;
        }
        if (!DynamicStormController.isDynamicVortex(storm) || state.isAbsorbedOrDecaying()) {
            forget(storm);
            return;
        }
        long gameTime = level.getGameTime();
        Episode episode = episodeOf(storm);
        if (episode != null) {
            if (!releaseStep(level, storm, episode, gameTime)) {
                if (episode.torn == 0) {
                    rememberBarren(storm, episode.center, gameTime);
                }
                forget(storm);
            }
            return;
        }
        if (Config.STRUCTURE_LIFT_MAX_CONCURRENT.get() <= 0) {
            return;
        }
        // Each storm scans on its own tick inside the interval, so a sky full of tornadoes does not
        // pile every structure search onto the same tick.
        int interval = Math.max(1, Config.STRUCTURE_LIFT_SCAN_INTERVAL_TICKS.get());
        if (Math.floorMod(gameTime + storm.ID, interval) != 0L) {
            return;
        }
        if (TornadoWindField.maxWindMph(storm) < Config.STRUCTURE_LIFT_MIN_WIND_MPH.get()) {
            return;
        }
        if (storm.levelCurIntensityStage < Config.STRUCTURE_LIFT_MIN_STAGE.get()) {
            return;
        }
        if (concurrentLifts(level) >= Config.STRUCTURE_LIFT_MAX_CONCURRENT.get()) {
            return;
        }
        Episode started = scan(level, storm, state, gameTime);
        if (started != null) {
            synchronized (EPISODES) {
                EPISODES.put(storm, started);
            }
        }
    }

    /**
     * True while the storm has committed a live demolition that includes this block. Weather2's own
     * grabbed-block rules consult this so the structure's masonry is not vetoed mid-demolition by
     * material resistance it has already been judged to have failed.
     */
    public static boolean isCommitted(StormObject storm, BlockPos pos) {
        if (storm == null || pos == null) {
            return false;
        }
        Episode episode = episodeOf(storm);
        return episode != null && episode.members.contains(pos.asLong());
    }

    private static boolean releaseStep(ServerLevel level, StormObject storm, Episode episode, long gameTime) {
        if (gameTime > episode.endTick) {
            return false;
        }
        // A storm that has weakened or roped out leaves whatever is left of the building standing.
        if (TornadoWindField.maxWindMph(storm) < Config.STRUCTURE_LIFT_MIN_WIND_MPH.get() * 0.85D) {
            return false;
        }
        if (episode.wholeBuilding) {
            // One step and the building is gone: taken off its foundation entire, then carried by the
            // clients as a single body that comes apart in the air.
            pluckWholeBuilding(level, storm, episode, gameTime);
            return false;
        }
        double tearMph = Config.STRUCTURE_LIFT_BLOCK_WIND_MPH.get();
        int budget = Math.max(1, (int) Math.round(Config.STRUCTURE_LIFT_BLOCKS_PER_TICK.get() * stageScale(storm)));
        int attempts = budget * 4;
        int torn = 0;
        while (torn < budget && episode.index < episode.plan.size() && attempts-- > 0) {
            Planned planned = episode.plan.get(episode.index);
            if (planned.releaseTick > gameTime) {
                break;
            }
            episode.index++;
            if (tearBlock(level, storm, planned, tearMph)) {
                torn++;
                episode.torn++;
            }
        }
        return episode.index < episode.plan.size();
    }

    /**
     * Takes a whole building at once.
     *
     * <p>A rigid body cannot lose blocks half way through being picked up: if part of the structure
     * cannot be taken - a protected region, a chunk that is not loaded, a block some other system
     * already changed - then flying the rest would leave copies of the leftovers standing while their
     * images are in the air. So every block is checked first, the ones that cannot go are simply left
     * out of the body, and if too little is left to be worth flying the episode falls back to the
     * ordinary storey-by-storey tear.</p>
     */
    private static void pluckWholeBuilding(ServerLevel level, StormObject storm, Episode episode, long gameTime) {
        double requiredMph = requiredTearMph(episode);
        TornadoWindField.WindSample atStructure = TornadoWindField.sample(storm, level, episode.center);
        if (TornadoDamageModel.damageWindMph(storm, atStructure) < requiredMph) {
            return;
        }
        List<Planned> taken = new ArrayList<>(episode.plan.size());
        for (Planned planned : episode.plan) {
            if (canTakeWhole(level, planned)) {
                taken.add(planned);
            }
        }
        if (taken.size() < episode.plan.size() * 0.6D || taken.isEmpty()) {
            return;
        }
        List<StructureFlightPayload.Block> carried = new ArrayList<>(taken.size());
        for (Planned planned : taken) {
            if (!BlockGrabSafety.safeDestroyBlock(level, planned.pos, false)) {
                continue;
            }
            // Offsets travel in half blocks, so a building centred between two blocks keeps every
            // block exactly where it was instead of being nudged half a block as it takes off.
            carried.add(new StructureFlightPayload.Block(
                    (int) Math.round((planned.pos.getX() + 0.5D - episode.center.x) * 2.0D),
                    (int) Math.round((planned.pos.getY() + 0.5D - episode.center.y) * 2.0D),
                    (int) Math.round((planned.pos.getZ() + 0.5D - episode.center.z) * 2.0D),
                    Block.getId(planned.state)));
        }
        if (carried.isEmpty()) {
            return;
        }
        episode.torn += carried.size();
        int flightTicks = (int) Math.round(Math.max(1.0D, Config.STRUCTURE_LIFT_FLIGHT_SECONDS.get()) * 20.0D);
        // Kind.BUILDING: the landing fields are a thrown body's business, so a building leaves them at
        // zero and keeps its own flight, which is driven by the live funnel rather than by a target.
        StructureFlightPayload payload = new StructureFlightPayload(storm.ID, StructureFlightPayload.Kind.BUILDING,
                episode.center.x, episode.center.y, episode.center.z, episode.groundY,
                flightTicks, Config.STRUCTURE_LIFT_TUMBLE_RATE.get().floatValue(),
                0.0D, 0.0D, 0.0D, 1.0F, false, carried);
        // Sent to everyone who could see it: the building is drawn by each client, so the ones that
        // could not see it were never told, and the ones that could get it in the same tick the world
        // updates that removed the blocks.
        NetworkSafety.sendToPlayersNear(level, episode.center.x, episode.center.y, episode.center.z,
                PLUCK_BROADCAST_RANGE, payload);
    }

    /** Wind the whole structure needs before it can be taken as one body. */
    private static double requiredTearMph(Episode episode) {
        double tearMph = Config.STRUCTURE_LIFT_BLOCK_WIND_MPH.get();
        for (Planned planned : episode.plan) {
            if (isMasonry(planned.state)) {
                return Math.max(tearMph, Config.DAMAGE_STONE_PICKUP_MPH.get());
            }
        }
        return tearMph;
    }

    /** Whether one block of a committed structure can come with it: present, unchanged, and editable. */
    private static boolean canTakeWhole(ServerLevel level, Planned planned) {
        if (!level.isLoaded(planned.pos)) {
            return false;
        }
        BlockGrabSafety.BlockSample sample = BlockGrabSafety.sample(level, planned.pos, null);
        return sample.valid() && !sample.air() && sample.state() == planned.state && BlockGrabSafety.canEdit(sample);
    }

    private static boolean tearBlock(ServerLevel level, StormObject storm, Planned planned, double tearMph) {
        BlockPos pos = planned.pos;
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockGrabSafety.BlockSample sample = BlockGrabSafety.sample(level, pos, null);
        if (!sample.valid() || sample.air() || sample.state() != planned.state) {
            return false;
        }
        if (!BlockGrabSafety.canEdit(sample)) {
            return false;
        }
        BlockState state = sample.state();
        // Wood, glass, and wool come off once the shell has failed. Masonry only comes with it when the
        // wind is genuinely at masonry strength, so an EF2 tears a wooden house to its floorboards while
        // it can still only take the roof and upper walls off a brick one.
        double requiredMph = isMasonry(state) ? Math.max(tearMph, Config.DAMAGE_STONE_PICKUP_MPH.get()) : tearMph;
        TornadoWindField.WindSample wind = TornadoWindField.sample(storm, level, Vec3.atCenterOf(pos));
        if (TornadoDamageModel.damageWindMph(storm, wind) < requiredMph) {
            return false;
        }
        if (!BlockGrabSafety.safeDestroyBlock(level, pos, false)) {
            return false;
        }
        spawnDebris(level, storm, pos, state);
        return true;
    }

    /**
     * Hands the torn block to Weather2's own debris spawner: the client turns it into a block-textured
     * cube owned by this storm, which the funnel then pushes, lifts, and rolls like any grabbed block.
     */
    private static void spawnDebris(ServerLevel level, StormObject storm, BlockPos pos, BlockState state) {
        if (storm.manager instanceof WeatherManagerServer server) {
            server.syncBlockParticleNew(pos, state, storm);
        }
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                6, 0.35D, 0.35D, 0.35D, 0.05D);
    }

    private static Episode scan(ServerLevel level, StormObject storm, DynamicStormState state, long gameTime) {
        // The scan radius follows the damage path so a wide wedge hunts for buildings across its
        // whole footprint, not just within 48 blocks of the funnel - a mile-wide storm's outer
        // swath was previously invisible to this loop, so a town on its edge was never lifted.
        // Still capped (512) so sampling stays dense enough to actually hit structures.
        double radius = Mth.clamp(Math.max(12.0D, state.damagePathWidth() * 0.5D) + 6.0D, 12.0D, 512.0D);
        int minBlocks = Config.STRUCTURE_LIFT_MIN_BLOCKS.get();
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
            BlockPos seed = findSeed(level, surface);
            if (seed == null) {
                continue;
            }
            List<Planned> found = flood(level, seed);
            if (found.size() < minBlocks || !looksLikeBuilding(found)) {
                continue;
            }
            // The fill has to have ended on its own. If it hit the block cap the "structure" is a slice
            // out of something far larger - a badlands terracotta wall, a filled-in hillside, half a
            // mansion - and tearing the sampled slice out of the middle of it looks like a bug, so it is
            // left to the normal block-by-block rules instead.
            if (found.size() >= Config.STRUCTURE_LIFT_MAX_BLOCKS.get()) {
                continue;
            }
            Vec3 center = centroid(found);
            if (isBarren(storm, center, gameTime)) {
                continue;
            }
            return plan(level, storm, found, gameTime);
        }
        return null;
    }

    /**
     * Topmost building block in a column. The surface heightmap sits just above the roof, so walking
     * down finds the roof first. Loose scenery (leaves, vines, crops) is walked past so a canopy or a
     * vine cannot hide a house, but natural ground aborts the column: a field or a hillside has no
     * building in it, and the search must not chew down into terrain looking for one.
     */
    private static BlockPos findSeed(ServerLevel level, BlockPos surface) {
        BlockPos.MutableBlockPos cursor = surface.mutable();
        for (int depth = 0; depth < SEED_SEARCH_DEPTH; depth++) {
            cursor.move(0, -1, 0);
            if (cursor.getY() < level.getMinBuildHeight() || !level.isLoaded(cursor)) {
                return null;
            }
            BlockGrabSafety.BlockSample sample = BlockGrabSafety.sample(level, cursor, null);
            if (!sample.valid() || sample.air() || sample.fluid()) {
                continue;
            }
            if (isTerrainFloor(sample.state())) {
                return null;
            }
            if (isBuildingBlock(sample.state())) {
                return cursor.immutable();
            }
        }
        return null;
    }

    /** Bounded flood fill over connected building materials, so a bridging deck cannot run away. */
    private static List<Planned> flood(ServerLevel level, BlockPos seed) {
        int maxBlocks = Config.STRUCTURE_LIFT_MAX_BLOCKS.get();
        int maxRadius = Config.STRUCTURE_LIFT_MAX_RADIUS_BLOCKS.get();
        boolean allowMasonry = Config.STRUCTURE_LIFT_ALLOW_MASONRY.get();
        List<Planned> out = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed);
        visited.add(seed.asLong());
        while (!queue.isEmpty() && out.size() < maxBlocks) {
            BlockPos pos = queue.poll();
            BlockGrabSafety.BlockSample sample = BlockGrabSafety.sample(level, pos, null);
            if (!sample.valid() || sample.air() || !BlockGrabSafety.canEdit(sample)) {
                continue;
            }
            BlockState state = sample.state();
            if (!isBuildingBlock(state) || (!allowMasonry && isMasonry(state))) {
                continue;
            }
            out.add(new Planned(pos, state));
            for (Direction direction : NEIGHBOURS) {
                BlockPos next = pos.relative(direction);
                if (Math.abs(next.getX() - seed.getX()) > maxRadius
                        || Math.abs(next.getY() - seed.getY()) > maxRadius
                        || Math.abs(next.getZ() - seed.getZ()) > maxRadius) {
                    continue;
                }
                if (!level.isLoaded(next) || !visited.add(next.asLong())) {
                    continue;
                }
                queue.add(next);
            }
        }
        return out;
    }

    /**
     * Separates a building from a lump of scenery: something tall enough to have a roof over walls,
     * wide enough on both horizontal axes to enclose space, and made of at least two real levels.
     * A single wall, a fence line, a floor, a bridge, and a tree trunk all fail this.
     */
    private static boolean looksLikeBuilding(List<Planned> blocks) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        Map<Integer, Integer> perLevel = new HashMap<>();
        for (Planned block : blocks) {
            BlockPos pos = block.pos;
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY());
            maxY = Math.max(maxY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
            perLevel.merge(pos.getY(), 1, Integer::sum);
        }
        if (maxY - minY + 1 < 3 || maxX - minX + 1 < 4 || maxZ - minZ + 1 < 4) {
            return false;
        }
        int solidLevels = 0;
        for (int count : perLevel.values()) {
            if (count >= 4) {
                solidLevels++;
            }
        }
        return solidLevels >= 2;
    }

    /**
     * Turns the structure into a release schedule. The tear works one storey at a time from the top
     * down, because that is the order a building actually fails in: the roof is unanchored, so it goes
     * first, then each storey below it once the one above has stopped bracing it, and the foundation
     * course goes last. Within a storey the ranking is windward exposure (the face the local wind blows
     * onto), blocks under open sky, how close they are to the funnel right now, and a deterministic
     * jitter so the tear line is ragged instead of a clean geometric sweep.
     */
    private static Episode plan(ServerLevel level, StormObject storm, List<Planned> blocks, long gameTime) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        Set<Long> members = new HashSet<>();
        for (Planned block : blocks) {
            BlockPos pos = block.pos;
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY());
            maxY = Math.max(maxY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
            members.add(pos.asLong());
        }
        double centerX = (minX + maxX + 1) * 0.5D;
        double centerY = (minY + maxY + 1) * 0.5D;
        double centerZ = (minZ + maxZ + 1) * 0.5D;
        double spanY = Math.max(1.0D, maxY - minY);
        double reach = Math.max(4.0D, Math.max(maxX - minX, maxZ - minZ) * 0.5D);

        TornadoWindField.WindSample centerWind = TornadoWindField.sample(storm, level, new Vec3(centerX, centerY, centerZ));
        Vec3 windDir = new Vec3(centerWind.mphVector().x, 0.0D, centerWind.mphVector().z);
        if (windDir.lengthSqr() < 1.0E-4D) {
            windDir = storm.motion == null ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(storm.motion.x, 0.0D, storm.motion.z);
        }
        double windLength = windDir.length();
        Vec3 windUnit = windLength < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : windDir.scale(1.0D / windLength);
        double funnelReach = reach * 2.0D + 4.0D;

        for (Planned block : blocks) {
            BlockPos pos = block.pos;
            double dx = pos.getX() + 0.5D - centerX;
            double dz = pos.getZ() + 0.5D - centerZ;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            double exposure = horizontal < 0.001D ? 0.0D : (dx * windUnit.x + dz * windUnit.z) / horizontal;
            double windward = Math.max(0.0D, -exposure);
            double heightFraction = (pos.getY() - minY) / spanY;
            double toFunnelX = pos.getX() + 0.5D - storm.pos.x;
            double toFunnelZ = pos.getZ() + 0.5D - storm.pos.z;
            double toFunnel = Math.sqrt(toFunnelX * toFunnelX + toFunnelZ * toFunnelZ);
            double proximity = Mth.clamp(1.0D - toFunnel / funnelReach, 0.0D, 1.0D);
            boolean unsupportedTop = !members.contains(pos.above().asLong());
            double jitter = ((pos.asLong() * 0x9E3779B97F4A7C15L) >>> 12 & 0xFFL) / 255.0D;
            block.score = heightFraction * 1.15D
                    + windward * 0.85D
                    + (unsupportedTop ? 0.65D : 0.0D)
                    + proximity * 0.55D
                    + jitter * 0.60D;
        }
        blocks.sort(Comparator.<Planned>comparingInt(block -> -block.pos.getY())
                .thenComparing(Comparator.comparingDouble((Planned block) -> block.score).reversed()));

        int count = blocks.size();
        int duration = Math.max(12, (int) Math.round(Config.STRUCTURE_LIFT_DURATION_TICKS.get()
                * Mth.clamp(Math.sqrt(count / 140.0D), 0.72D, 1.8D)));
        for (int index = 0; index < count; index++) {
            blocks.get(index).releaseTick = (int) (gameTime + Math.floor(duration * (double) index / count));
        }
        return new Episode(blocks, members, new Vec3(centerX, centerY, centerZ), level.dimension(),
                (int) (gameTime + duration), minY, wholeBuilding(blocks));
    }

    /**
     * Whether this structure is small enough to be taken whole. Above the whole-building cap it is too
     * big to be carried as one body - a warehouse is not a house - and it keeps the storey-by-storey
     * tear, which is also what a large structure actually does: it fails in pieces.
     */
    private static boolean wholeBuilding(List<Planned> blocks) {
        if (!Config.STRUCTURE_LIFT_WHOLE_BUILDING.get()) {
            return false;
        }
        int cap = Math.min(Config.STRUCTURE_LIFT_WHOLE_MAX_BLOCKS.get(), Config.STRUCTURE_LIFT_MAX_BLOCKS.get());
        return blocks.size() <= cap && blocks.size() > 0;
    }

    /** Natural ground. Reaching it means the column holds no building at all. */
    private static boolean isTerrainFloor(BlockState state) {
        if (state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.BASE_STONE_OVERWORLD)) {
            return true;
        }
        if (state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(Blocks.SNOW_BLOCK)
                || state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE)
                || state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE)
                || state.is(Blocks.END_STONE) || state.is(Blocks.BLACKSTONE)
                || state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.GRANITE) || state.is(Blocks.DIORITE) || state.is(Blocks.ANDESITE)
                || state.is(Blocks.TUFF) || state.is(Blocks.CALCITE) || state.is(Blocks.DRIPSTONE_BLOCK)) {
            return true;
        }
        return blockPath(state).contains("_ore");
    }

    /**
     * Loose scenery: walked past during the column scan, never part of the structure itself. A giant
     * mushroom cap belongs here rather than in the building list, or a dark forest would offer up
     * "houses" made of stem and cap.
     */
    private static boolean isSoftScenery(BlockState state) {
        return state.is(BlockTags.LEAVES)
                || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.CROPS)
                || state.is(BlockTags.REPLACEABLE_BY_TREES)
                || blockPath(state).endsWith("_mushroom_block");
    }

    /**
     * Shaped or wood building materials. Nature-shaped blocks are excluded (they are terrain, and a
     * desert mesa must not read as a house), while the crafted shapes of the same family are included -
     * so smooth sandstone is a building but raw sandstone is a rock.
     */
    static boolean isBuildingBlock(BlockState state) {
        if (state == null || state.isAir() || isTerrainFloor(state) || isSoftScenery(state)) {
            return false;
        }
        if (state.is(BlockTags.PLANKS)
                || state.is(BlockTags.WOODEN_DOORS)
                || state.is(BlockTags.WOODEN_TRAPDOORS)
                || state.is(BlockTags.WOODEN_STAIRS)
                || state.is(BlockTags.WOODEN_SLABS)
                || state.is(BlockTags.WOODEN_FENCES)
                || state.is(BlockTags.FENCE_GATES)
                || state.is(BlockTags.WALLS)
                || state.is(BlockTags.STONE_BRICKS)
                || state.is(BlockTags.STONE_BUTTONS)) {
            return true;
        }
        String path = blockPath(state);
        if (path.contains("glass") || path.contains("pane") || path.contains("wool") || path.contains("carpet")) {
            return true;
        }
        // Logs are part of a building when they are its walls or its roof - the shape test below is
        // what keeps a standing tree from being mistaken for one.
        if (path.contains("_log") || path.contains("_wood") || path.contains("_stem") || path.contains("_hyphae")
                || path.contains("bamboo_block")) {
            return true;
        }
        // Storage and metal blocks (iron, copper, quartz, purpur, hay...) are building materials too.
        if (path.endsWith("_block")) {
            return true;
        }
        return path.contains("plank")
                || path.contains("brick")
                || path.contains("cobble")
                || path.contains("terracotta")
                || path.contains("concrete")
                || path.contains("stone")
                || path.contains("quartz")
                || path.contains("prismarine")
                || path.contains("polished")
                || path.contains("smooth")
                || path.contains("cut_")
                || path.contains("chiseled")
                || path.contains("mud")
                || path.contains("hay")
                || path.contains("bookshelf")
                || path.contains("_door")
                || path.contains("trapdoor")
                || path.contains("fence")
                || path.contains("stairs")
                || path.contains("slab")
                || path.contains("_wall");
    }

    /**
     * Masonry is the part of a building that out-resists the wind block by block, so it only comes off
     * at masonry-strength wind (see {@code tearBlock}) even once its building has failed as a whole.
     * With allowMasonry off it is left out of the structure entirely and only the wood, glass, and wool
     * parts are swept.
     */
    private static boolean isMasonry(BlockState state) {
        if (state.is(BlockTags.STONE_BRICKS) || state.is(BlockTags.WALLS)
                || state.is(Blocks.IRON_BLOCK) || state.is(Blocks.COPPER_BLOCK)) {
            return true;
        }
        String path = blockPath(state);
        return path.contains("brick")
                || path.contains("cobble")
                || path.contains("terracotta")
                || path.contains("concrete")
                || path.contains("stone")
                || path.contains("deepslate")
                || path.contains("quartz")
                || path.contains("prismarine")
                || path.contains("tile");
    }

    private static String blockPath(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id == null ? "" : id.getPath();
    }

    private static double stageScale(StormObject storm) {
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE5) {
            return 2.0D;
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE4) {
            return 1.6D;
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE3) {
            return 1.3D;
        }
        return 1.0D;
    }

    private static Vec3 centroid(List<Planned> blocks) {
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        for (Planned block : blocks) {
            x += block.pos.getX() + 0.5D;
            y += block.pos.getY() + 0.5D;
            z += block.pos.getZ() + 0.5D;
        }
        double scale = 1.0D / blocks.size();
        return new Vec3(x * scale, y * scale, z * scale);
    }

    private static void rememberBarren(StormObject storm, Vec3 center, long gameTime) {
        synchronized (BARREN) {
            BARREN.put(storm, new BarrenLift(center, gameTime));
        }
    }

    private static boolean isBarren(StormObject storm, Vec3 center, long gameTime) {
        BarrenLift barren;
        synchronized (BARREN) {
            barren = BARREN.get(storm);
        }
        if (barren == null || gameTime - barren.tick() >= BARREN_COOLDOWN_TICKS) {
            return false;
        }
        return barren.center().distanceToSqr(center) <= BARREN_SAME_STRUCTURE_BLOCKS * BARREN_SAME_STRUCTURE_BLOCKS;
    }

    private static Episode episodeOf(StormObject storm) {
        synchronized (EPISODES) {
            return EPISODES.get(storm);
        }
    }

    private static void forget(StormObject storm) {
        synchronized (EPISODES) {
            EPISODES.remove(storm);
        }
    }

    private static int concurrentLifts(ServerLevel level) {
        synchronized (EPISODES) {
            int count = 0;
            for (Episode episode : EPISODES.values()) {
                if (episode.dimension == level.dimension()) {
                    count++;
                }
            }
            return count;
        }
    }

    private static final class Planned {
        final BlockPos pos;
        final BlockState state;
        double score;
        int releaseTick;

        Planned(BlockPos pos, BlockState state) {
            this.pos = pos;
            this.state = state;
        }
    }

    private record BarrenLift(Vec3 center, long tick) {
    }

    private static final class Episode {
        final List<Planned> plan;
        final Set<Long> members;
        final Vec3 center;
        final ResourceKey<Level> dimension;
        final int endTick;
        /** The ground the structure stands on, which is the floor a whole-building lift rises from. */
        final double groundY;
        /** True when this structure is taken entire and carried, rather than torn away storey by storey. */
        final boolean wholeBuilding;
        int index;
        int torn;

        Episode(List<Planned> plan, Set<Long> members, Vec3 center, ResourceKey<Level> dimension, int endTick,
                double groundY, boolean wholeBuilding) {
            this.plan = plan;
            this.members = members;
            this.center = center;
            this.dimension = dimension;
            this.endTick = endTick;
            this.groundY = groundY;
            this.wholeBuilding = wholeBuilding;
        }
    }
}
