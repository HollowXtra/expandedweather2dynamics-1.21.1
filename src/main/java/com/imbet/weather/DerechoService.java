package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.ArrayList;
import java.util.List;

/**
 * What a derecho does to the ground it crosses.
 *
 * <p>{@link DerechoModel} decides whether a line has earned the name; this is the two hazards that come with
 * it, and both are deliberately unlike a tornado's. A tornado takes things away - it lifts, it carries, it
 * drops them somewhere else - and a derecho lays things down where they stand. That is the whole difference
 * between the two kinds of wind damage in the field, and it is why the tree pass here removes a standing
 * trunk and puts a run of logs on the ground immediately downwind of it rather than throwing anything: a
 * forest a derecho has been through looks combed, not scattered. The canopy above the fallen trunk is left to
 * vanilla's own leaf decay, which is exactly what happens after the real thing - the leaves are stripped over
 * the minutes afterwards rather than in the second the wind arrives.
 *
 * <p>The second hazard is roofs, and it is the one that makes this worth classifying at all. A derecho does
 * not remove a house; it removes the *covering* of one - shingles, planks, slabs, panes - and leaves the walls
 * standing. So the pass reads the topmost blocking block of a column, which on a building is its roof, and
 * takes it if the material it is made of is one of the weak ones. Stone, brick and metal are never touched,
 * which is the reason a player can build against this.
 */
public final class DerechoService {

    /** How many roof columns one pass reads. */
    private static final int ROOF_ATTEMPTS = 6;

    /** How far up a trunk is looked for, and how many logs one felled tree may lay down. */
    private static final int TRUNK_SCAN_HEIGHT = 18;
    private static final int MAX_LOGS_PER_TREE = 10;

    /** How far behind the front the swath reaches, as a multiple of the line's own wind radius. */
    private static final double SWATH_DEPTH_FRACTION = 1.25D;

    private DerechoService() {
    }

    /**
     * Whether this line has earned the name, and which rung of it, decided from measurements of the line
     * itself. Read once per line per second by the tick below and written back onto the event, so the swath
     * pass, the alert and the client's own shelf all act on one classification rather than three.
     */
    public static DerechoModel.Verdict classify(ServerLevel level, AddonWeatherEvent event) {
        if (level == null || event == null || !event.isLine() || event.lineStart() == null || event.lineEnd() == null) {
            return new DerechoModel.Verdict(DerechoModel.Type.NONE, DerechoModel.Category.NONE, 0.0D, 0.0D);
        }
        double length = horizontalDistance(event.lineStart(), event.lineEnd());
        return DerechoModel.classify(limits(), length, event.maxWindMph(), event.travelledBlocks(),
                lineCellCount(level, event));
    }

    /** The classification thresholds as the config has them. */
    public static DerechoModel.Limits limits() {
        return new DerechoModel.Limits(Config.DERECHO_SWATH_BLOCKS.get(), Config.DERECHO_MIN_LINE_LENGTH_BLOCKS.get());
    }

    /**
     * Reclassify a line and remember the best it has ever been. Called on the same one-second cadence as the
     * rest of the addon's upkeep, and never on the client: a classification belongs to the server, and the
     * only thing the client needs from it is the marker that comes down with the event.
     */
    public static DerechoModel.Verdict updateClassification(ServerLevel level, AddonWeatherEvent event) {
        if (level == null || event == null || level.isClientSide() || !event.isLine()) {
            return new DerechoModel.Verdict(DerechoModel.Type.NONE, DerechoModel.Category.NONE, 0.0D, 0.0D);
        }
        DerechoModel.Verdict verdict = DerechoModel.strongest(verdictOf(event), classify(level, event));
        event.setDerechoMarker(verdict.type().ordinal(), verdict.category().ordinal());
        return verdict;
    }

    /** The verdict a line is already carrying, so a classification can only ever be revised upwards. */
    public static DerechoModel.Verdict verdictOf(AddonWeatherEvent event) {
        if (event == null) {
            return new DerechoModel.Verdict(DerechoModel.Type.NONE, DerechoModel.Category.NONE, 0.0D, 0.0D);
        }
        DerechoModel.Category category = categoryFor(event.derechoCategory());
        if (category == DerechoModel.Category.NONE) {
            return new DerechoModel.Verdict(DerechoModel.Type.NONE, DerechoModel.Category.NONE, 0.0D, 0.0D);
        }
        return new DerechoModel.Verdict(typeFor(event.derechoType()), category, event.travelledBlocks(), category.windFloorMph());
    }

    /** The shape an event's own marker names. */
    public static DerechoModel.Type typeFor(int marker) {
        DerechoModel.Type[] values = DerechoModel.Type.values();
        if (marker <= 0 || marker >= values.length) {
            return DerechoModel.Type.NONE;
        }
        return values[marker];
    }

    /** How many storms are strung along the line, which is what makes one serial rather than progressive. */
    private static int lineCellCount(ServerLevel level, AddonWeatherEvent event) {
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return 1;
        }
        Vec3 start = event.lineStart();
        Vec3 end = event.lineEnd();
        int cells = 0;
        for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
            if (object instanceof StormObject storm && storm.pos != null && !storm.isDead
                    && AddonWeatherEventService.isSquallLineHelperStorm(storm)
                    && distanceToSegment(storm.pos, start, end) <= Math.max(160.0D, event.radius())) {
                cells++;
            }
        }
        return Math.max(1, cells);
    }

    private static double horizontalDistance(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public static void tick(ServerLevel level, AddonWeatherEvent event) {
        if (level == null || event == null || !event.isLine()) {
            return;
        }
        DerechoModel.Category category = categoryFor(event.derechoCategory());
        if (category == DerechoModel.Category.NONE
                || !Config.DERECHOS_ENABLED.get() || !Config.damageEnabled()) {
            return;
        }
        long interval = Math.max(1L, Config.DERECHO_PASS_INTERVAL_TICKS.get());
        if (level.getGameTime() % interval != 0L) {
            return;
        }
        int budget = Math.max(0, Config.DERECHO_MAX_EDITS_PER_PASS.get());
        if (budget <= 0) {
            return;
        }
        int edits = 0;
        if (Config.DERECHO_TREE_FLATTENING.get()) {
            edits += flattenTrees(level, event, category, budget);
        }
        if (Config.DERECHO_ROOF_STRIPPING.get() && edits < budget) {
            stripRoofCovering(level, event, category, budget - edits);
        }
    }

    /** The rung an event's own marker names. */
    public static DerechoModel.Category categoryFor(int marker) {
        DerechoModel.Category[] values = DerechoModel.Category.values();
        if (marker <= 0 || marker >= values.length) {
            return DerechoModel.Category.NONE;
        }
        return values[marker];
    }

    /* ----------------------------------------------------------------- trees */

    private static int flattenTrees(ServerLevel level, AddonWeatherEvent event, DerechoModel.Category category, int budget) {
        int attempts = DerechoModel.flattenAttempts(category);
        int edits = 0;
        for (int i = 0; i < attempts && edits < budget; i++) {
            BlockPos column = swathColumn(level, event);
            if (column == null) {
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
            double localMph = localWindMph(event, Vec3.atCenterOf(base));
            if (level.random.nextDouble() >= DerechoModel.treeFlattenChance(category, localMph, event.maxWindMph())) {
                continue;
            }
            edits += layDown(level, event, base, budget - edits);
        }
        return edits;
    }

    /** The lowest standing trunk in a column, if there is one. */
    private static BlockPos findTrunkBase(ServerLevel level, BlockPos surface) {
        for (int dy = 0; dy <= TRUNK_SCAN_HEIGHT; dy++) {
            BlockPos pos = surface.offset(0, dy, 0);
            if (!level.isLoaded(pos)) {
                return null;
            }
            BlockState state = level.getBlockState(pos);
            if (state.is(BlockTags.LOGS)) {
                return pos.immutable();
            }
            if (!state.isAir() && !state.is(BlockTags.LEAVES)) {
                // Something that is neither trunk nor canopy: not a tree, or not a standing one.
                return null;
            }
        }
        return null;
    }

    /**
     * Take a standing trunk down and lay its logs along the ground downwind. The logs keep the wood they were
     * made of, so an oak falls as oak rather than turning into something else, and the axis is set across the
     * way they lie rather than along the way they stood.
     */
    private static int layDown(ServerLevel level, AddonWeatherEvent event, BlockPos base, int budget) {
        List<BlockState> logs = new ArrayList<>();
        List<BlockPos> positions = new ArrayList<>();
        for (int dy = 0; dy < MAX_LOGS_PER_TREE && logs.size() < budget; dy++) {
            BlockPos pos = base.offset(0, dy, 0);
            if (!level.isLoaded(pos)) {
                break;
            }
            BlockGrabSafety.BlockSample sample = BlockGrabSafety.sample(level, pos, null);
            if (!BlockGrabSafety.canEdit(sample)) {
                break;
            }
            BlockState state = sample.state();
            if (!state.is(BlockTags.LOGS)) {
                break;
            }
            logs.add(state);
            positions.add(pos.immutable());
        }
        if (logs.isEmpty()) {
            return 0;
        }
        Direction.Axis lie = lieAxis(event);
        int edits = 0;
        for (int i = 0; i < logs.size() && edits < budget; i++) {
            if (BlockGrabSafety.safeSetBlock(level, positions.get(i), Blocks.AIR.defaultBlockState(), 3)) {
                edits++;
            }
        }
        int stepX = lie == Direction.Axis.X ? (int) Math.signum(driftX(event)) : 0;
        int stepZ = lie == Direction.Axis.Z ? (int) Math.signum(driftZ(event)) : 0;
        if (stepX == 0 && stepZ == 0) {
            stepX = 1;
        }
        for (int i = 0; i < logs.size() && edits < budget; i++) {
            BlockPos ground = groundDownwind(level, base, stepX, stepZ, i + 1);
            if (ground == null) {
                continue;
            }
            BlockState fallen = logs.get(i);
            if (fallen.hasProperty(BlockStateProperties.AXIS)) {
                fallen = fallen.setValue(BlockStateProperties.AXIS, lie);
            }
            if (BlockGrabSafety.safeSetBlock(level, ground, fallen, 3)) {
                edits++;
            }
        }
        return edits;
    }

    /** The first air block at ground level along the way the tree is lying, or null if there is no room. */
    private static BlockPos groundDownwind(ServerLevel level, BlockPos base, int stepX, int stepZ, int distance) {
        BlockPos column = base.offset(stepX * distance, 0, stepZ * distance);
        // A run of logs reads as one fallen trunk, so it is laid level with the stump rather than following
        // the terrain two blocks down and one block up.
        for (int dy = 1; dy >= -3; dy--) {
            BlockPos candidate = column.offset(0, dy, 0);
            if (!level.isLoaded(candidate)) {
                return null;
            }
            BlockGrabSafety.BlockSample sample = BlockGrabSafety.sample(level, candidate, null);
            if (!BlockGrabSafety.canEdit(sample)) {
                continue;
            }
            if (sample.state().isAir()) {
                BlockPos below = candidate.below();
                BlockState support = level.getBlockState(below);
                if (!support.isAir() && support.getFluidState().isEmpty()) {
                    return candidate.immutable();
                }
            }
        }
        return null;
    }

    /* ----------------------------------------------------------------- roofs */

    private static int stripRoofCovering(ServerLevel level, AddonWeatherEvent event, DerechoModel.Category category, int budget) {
        int edits = 0;
        for (int i = 0; i < ROOF_ATTEMPTS && edits < budget; i++) {
            BlockPos column = swathColumn(level, event);
            if (column == null) {
                continue;
            }
            BlockPos roof = BlockGrabSafety.safeSurface(level, column, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
            if (roof == null) {
                continue;
            }
            BlockGrabSafety.BlockSample sample = BlockGrabSafety.sample(level, roof, null);
            if (!BlockGrabSafety.canEdit(sample)) {
                continue;
            }
            RoofKind kind = roofKindOf(sample.state());
            if (kind == RoofKind.NONE) {
                continue;
            }
            double localMph = localWindMph(event, Vec3.atCenterOf(roof));
            double chance = DerechoModel.roofStripChance(category, kind == RoofKind.THIN, localMph, event.maxWindMph());
            if (level.random.nextDouble() >= chance) {
                continue;
            }
            if (BlockGrabSafety.safeSetBlock(level, roof, Blocks.AIR.defaultBlockState(), 3)) {
                edits++;
            }
        }
        return edits;
    }

    /** What a roof is made of, as far as a derecho is concerned. */
    private enum RoofKind {
        NONE,
        PLANK,
        THIN
    }

    private static RoofKind roofKindOf(BlockState state) {
        if (state.is(BlockTags.LEAVES) || state.is(BlockTags.CROPS) || state.is(BlockTags.FLOWERS)) {
            return RoofKind.NONE;
        }
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock()).getPath();
        if (path.contains("carpet") || path.contains("pane") || path.contains("trapdoor")
                || path.contains("glass") || path.contains("snow") || path.contains("torch")) {
            return RoofKind.THIN;
        }
        // Everything a light roof is actually built out of. Stone, brick, deepslate, metal and concrete are
        // deliberately absent: a derecho takes shingles, not slate, and that is the shelter the player builds.
        if (state.is(BlockTags.PLANKS)
                || state.is(BlockTags.WOODEN_SLABS)
                || state.is(BlockTags.WOODEN_STAIRS)
                || state.is(BlockTags.WOOL)
                || state.is(BlockTags.WOODEN_FENCES)
                || path.contains("shingle")
                || path.contains("thatch")) {
            return RoofKind.PLANK;
        }
        return RoofKind.NONE;
    }

    /* ----------------------------------------------------------------- swath */

    /** A random column inside the swath the front is about to cross. */
    private static BlockPos swathColumn(ServerLevel level, AddonWeatherEvent event) {
        Vec3 start = event.lineStart();
        Vec3 end = event.lineEnd();
        if (start == null || end == null) {
            return null;
        }
        Vec3 axis = new Vec3(end.x - start.x, 0.0D, end.z - start.z);
        double length = Math.sqrt(axis.x * axis.x + axis.z * axis.z);
        if (length < 1.0E-3D) {
            return null;
        }
        axis = axis.scale(1.0D / length);
        double along = (level.random.nextDouble() - 0.5D) * length;
        Vec3 forward = forwardOf(event, axis);
        double depth = level.random.nextDouble() * event.radius() * SWATH_DEPTH_FRACTION;
        double behind = level.random.nextBoolean() ? -depth : depth * 0.35D;
        Vec3 point = event.position().add(axis.scale(along)).add(forward.scale(behind));
        return BlockPos.containing(point.x, point.y, point.z);
    }

    private static Vec3 forwardOf(AddonWeatherEvent event, Vec3 axis) {
        Vec3 motion = event.motion();
        if (motion != null && motion.horizontalDistanceSqr() > 1.0E-6D) {
            return new Vec3(motion.x, 0.0D, motion.z).normalize();
        }
        return new Vec3(-axis.z, 0.0D, axis.x);
    }

    /** How hard it is blowing at a point, falling off behind the front the way the line's own field does. */
    private static double localWindMph(AddonWeatherEvent event, Vec3 position) {
        Vec3 start = event.lineStart();
        Vec3 end = event.lineEnd();
        if (start == null || end == null) {
            return event.maxWindMph();
        }
        double distance = distanceToSegment(position, start, end);
        double reach = Math.max(48.0D, event.radius() * SWATH_DEPTH_FRACTION);
        return event.maxWindMph() * Math.max(0.35D, 1.0D - distance / reach);
    }

    private static double distanceToSegment(Vec3 point, Vec3 start, Vec3 end) {
        Vec3 segment = end.subtract(start);
        double lengthSqr = segment.x * segment.x + segment.z * segment.z;
        if (lengthSqr < 1.0E-5D) {
            return point.distanceTo(start);
        }
        double t = Math.max(0.0D, Math.min(1.0D,
                ((point.x - start.x) * segment.x + (point.z - start.z) * segment.z) / lengthSqr));
        double x = start.x + segment.x * t;
        double z = start.z + segment.z * t;
        double dx = point.x - x;
        double dz = point.z - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static Direction.Axis lieAxis(AddonWeatherEvent event) {
        if (Math.abs(driftX(event)) >= Math.abs(driftZ(event))) {
            return Direction.Axis.X;
        }
        return Direction.Axis.Z;
    }

    private static double driftX(AddonWeatherEvent event) {
        Vec3 motion = event.motion();
        if (motion == null || motion.horizontalDistanceSqr() < 1.0E-6D) {
            // A line that is not moving still has a wind: its trees lie along +X rather than standing up.
            return 1.0D;
        }
        return motion.x;
    }

    private static double driftZ(AddonWeatherEvent event) {
        Vec3 motion = event.motion();
        return motion == null ? 0.0D : motion.z;
    }
}
