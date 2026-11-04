package com.imbet.client;

import com.imbet.Config;
import com.imbet.weather.LeafEddy;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import weather2.weathersystem.WeatherManagerClient;
import weather2.weathersystem.storm.StormObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The leaves a gust front lifts before it arrives.
 *
 * <p>{@link LeafEddy} decides whether the air around the player is the kind that lifts a handful of leaves -
 * enough wind, a storm close enough that the wind is its inflow, and something loose on the ground - and
 * this is the part that finds the tree, keeps the little spiral alive for a second or two, and draws it.
 *
 * <p>Two details are worth the words. The particle is the leaf block's own texture, carried by a falling-dust
 * particle whose block state is the leaf block that was found, so a dark oak eddy is dark oak and a spruce
 * eddy is spruce without this class knowing what a biome is. And the eddy is anchored to the ground under
 * the canopy rather than to the leaves: what actually swirls is the litter under a tree, and anchoring it in
 * the canopy would put the whole spiral ten blocks up in the air.
 */
public final class LeafEddies {

    /** How many little spirals may be alive at once. The point of them is that they are few. */
    private static final int MAX_LIVE = 10;

    /** How many columns are read per tick looking for a tree. Each read is a heightmap lookup and a few blocks. */
    private static final int PROBES_PER_TICK = 6;

    /** How far above the ground the canopy is looked for. */
    private static final int FOLIAGE_SCAN_HEIGHT = 9;

    /** mph of lift turned into blocks per tick of upward particle motion. */
    private static final double LIFT_BLOCKS_PER_MPH_TICK = 0.004D;

    /** How much of the orbital speed becomes particle velocity across the ground. */
    private static final double ORBIT_VELOCITY_SCALE = 0.35D;

    private static final List<Eddy> EDDIES = new ArrayList<>();

    private LeafEddies() {
    }

    public static void clear() {
        EDDIES.clear();
    }

    public static void tick(ClientLevel level, LocalPlayer player, WeatherManagerClient manager) {
        if (level == null || player == null || manager == null
                || !Config.VISUALS_ENABLED.get() || !Config.LEAF_EDDIES_ENABLED.get()) {
            EDDIES.clear();
            return;
        }
        advance(level);
        Vec3 at = player.position();
        int range = Math.max(8, Config.LEAF_EDDY_RANGE_BLOCKS.get());
        double windMph = ClientWindSampler.sample(level, at, Math.max(64, range)).speedMph();
        StormObject storm = manager.getClosestStormAny(at, LeafEddy.STORM_INFLUENCE_BLOCKS);
        double stormDistance = storm == null || storm.pos == null ? Double.MAX_VALUE : storm.pos.distanceTo(at);
        double strength = LeafEddy.strength(windMph, stormDistance);
        if (strength < LeafEddy.MIN_STRENGTH) {
            return;
        }
        int loose = 0;
        List<Eddy> seeds = new ArrayList<>();
        for (int i = 0; i < PROBES_PER_TICK; i++) {
            Eddy seed = probe(level, at, range, strength);
            if (seed != null) {
                loose++;
                seeds.add(seed);
            }
        }
        int attempts = LeafEddy.spawnAttempts(strength, loose);
        for (int i = 0; i < attempts && EDDIES.size() < MAX_LIVE && !seeds.isEmpty(); i++) {
            EDDIES.add(seeds.get(level.random.nextInt(seeds.size())));
        }
    }

    /** Move the live spirals a step and draw their leaves. */
    private static void advance(ClientLevel level) {
        Iterator<Eddy> iterator = EDDIES.iterator();
        while (iterator.hasNext()) {
            Eddy eddy = iterator.next();
            if (eddy.ticksLeft <= 0) {
                iterator.remove();
                continue;
            }
            eddy.ticksLeft--;
            eddy.angle += eddy.spin;
            double tangential = eddy.radius * eddy.spin * ORBIT_VELOCITY_SCALE;
            int leaves = Math.max(1, eddy.leaves);
            for (int i = 0; i < leaves; i++) {
                double phase = eddy.angle + i * (Math.PI * 2.0D / leaves);
                // A leaf's place and the tangential push it leaves with are the same angle, read once each.
                double cosPhase = Math.cos(phase);
                double sinPhase = Math.sin(phase);
                double x = eddy.x + cosPhase * eddy.radius;
                double z = eddy.z + sinPhase * eddy.radius;
                double y = eddy.y + eddy.liftBlocksPerTick * (double) (1 + (i % 2));
                level.addParticle(new BlockParticleOption(ParticleTypes.FALLING_DUST, eddy.leaf),
                        x, y, z,
                        -sinPhase * tangential,
                        eddy.liftBlocksPerTick,
                        cosPhase * tangential);
            }
        }
    }

    /**
     * Read one random column near the player for a canopy, and if there is one, describe the spiral that
     * would stand under it. Returns null when the column has no leaves, which is the common answer and the
     * reason the caller counts how many it got.
     */
    private static Eddy probe(ClientLevel level, Vec3 at, int range, double strength) {
        double angle = level.random.nextDouble() * Math.PI * 2.0D;
        double distance = Math.sqrt(level.random.nextDouble()) * range;
        BlockPos column = BlockPos.containing(at.x + Math.cos(angle) * distance, at.y, at.z + Math.sin(angle) * distance);
        if (!level.hasChunkAt(column)) {
            return null;
        }
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
        if (!level.isLoaded(surface)) {
            return null;
        }
        for (int dy = 0; dy <= FOLIAGE_SCAN_HEIGHT; dy++) {
            BlockPos pos = surface.offset(0, dy, 0);
            BlockState state = level.getBlockState(pos);
            if (!state.is(BlockTags.LEAVES)) {
                continue;
            }
            double lift = LeafEddy.liftMph(strength) * LeafEddy.liftShare(strength) * LIFT_BLOCKS_PER_MPH_TICK;
            double spin = LeafEddy.orbitRadiansPerTick(strength) * (level.random.nextBoolean() ? 1.0D : -1.0D);
            return new Eddy(column.getX() + 0.5D, surface.getY() + 0.2D, column.getZ() + 0.5D,
                    LeafEddy.radiusBlocks(strength), level.random.nextDouble() * Math.PI * 2.0D, spin,
                    lift, LeafEddy.lifetimeTicks(strength), LeafEddy.leavesPerEddy(strength), state);
        }
        return null;
    }

    /**
     * One spiral: where it stands, how wide it turns, which way, and for how long. Mutable, because it is one
     * thing being advanced a tick at a time rather than a value handed around.
     */
    private static final class Eddy {
        private final double x;
        private final double y;
        private final double z;
        private final double radius;
        private final double liftBlocksPerTick;
        private final int leaves;
        private final BlockState leaf;
        private double angle;
        private final double spin;
        private int ticksLeft;

        private Eddy(double x, double y, double z, double radius, double angle, double spin,
                     double liftBlocksPerTick, int lifeTicks, int leaves, BlockState leaf) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.radius = radius;
            this.angle = angle;
            this.spin = spin;
            this.liftBlocksPerTick = liftBlocksPerTick;
            this.ticksLeft = lifeTicks;
            this.leaves = leaves;
            this.leaf = leaf;
        }
    }
}
