package com.imbet.weather;

import com.imbet.Config;
import com.imbet.volcano.Volcano;
import com.imbet.volcano.VolcanoService;
import com.imbet.volcano.VolcanoStore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;

/**
 * The two vortices that come off a volcano.
 *
 * <p>Neither is weather and neither is spawned by the storm ladder: a lava whirl needs a vent with magma at it
 * and a steam devil needs lava meeting water, so both are anchored to the mountain that made them and both are
 * rolled for by {@link VolcanoService} rather than by the storm scan. That is the whole reason this is not
 * another branch in {@link AddonWeatherEventService}: what these are made of is the ground, not the sky.
 *
 * <p>What the service owns is the same division of labour every other hazard in this mod uses. The whirls
 * themselves - their width, their wind, their life, what they do to an entity standing in them - belong to the
 * entities, which tick them where they are and only where they are loaded. The world edits belong here, on a
 * budget, because igniting a wood and spilling lava are permanent changes to a map and they must not be driven
 * by how many players are standing near a mountain. And the one thing neither whirl does is handled by not
 * being asked to: {@link LavaWhirlModel#movesBlocks} is false for steam, so the steam devil has no world pass
 * at all, which is not an omission but the difference between the two phenomena.
 */
public final class LavaWhirlService {

    /** How often a vent is rolled for a whirl, in ticks (200 = ten seconds). */
    private static final long ATTEMPT_INTERVAL_TICKS = 200L;

    /** How many world edits one lava whirl's pass may make. */
    private static final int MAX_EDITS_PER_PASS = 3;

    /** How often a lava whirl's world pass runs, in ticks. */
    private static final long PASS_INTERVAL_TICKS = 20L;

    /** How far around a vent the phreatic reaction is looked for. */
    private static final int WATER_SCAN_RADIUS = 24;
    private static final int WATER_SCAN_STEP = 2;

    /** How close an existing whirl has to be for a second one not to be rolled for. */
    private static final double CROWDING_RADIUS_BLOCKS = 96.0D;

    private LavaWhirlService() {
    }

    /** Called once a tick per level by the volcano system, and rolled on its own much slower cadence. */
    public static void tick(ServerLevel level) {
        if (level == null || level.isClientSide()) {
            return;
        }
        if (!Config.LAVA_WHIRLS_ENABLED.get() && !Config.STEAM_DEVILS_ENABLED.get()) {
            return;
        }
        VolcanoStore store = VolcanoStore.get(level);
        if (store == null) {
            return;
        }
        boolean attempt = level.getGameTime() % ATTEMPT_INTERVAL_TICKS == 0L;
        for (Volcano volcano : new ArrayList<>(store.volcanoes())) {
            if (volcano == null || volcano.dead()) {
                continue;
            }
            if (attempt) {
                maybeSpawn(level, volcano);
            }
        }
        if (Config.LAVA_WHIRL_DAMAGE.get() && level.getGameTime() % PASS_INTERVAL_TICKS == 0L) {
            burnTheGround(level);
        }
    }

    private static void maybeSpawn(ServerLevel level, Volcano volcano) {
        int lavaWhirls = countNear(level, volcano, StormSubtype.LAVA_WHIRL);
        int steamDevils = countNear(level, volcano, StormSubtype.STEAM_DEVIL);
        if (Config.LAVA_WHIRLS_ENABLED.get() && lavaWhirls == 0) {
            maybeSpawnLavaWhirl(level, volcano);
        }
        if (Config.STEAM_DEVILS_ENABLED.get() && steamDevils == 0) {
            maybeSpawnSteamDevil(level, volcano);
        }
    }

    /**
     * A whirl of fire off a vent that has magma at it. It is rolled for once every ten seconds and stands a
     * little over one chance in five when the eruption is strong, because a volcano can erupt for hours and a
     * whirl standing there the whole time would stop being an event and become scenery.
     */
    private static void maybeSpawnLavaWhirl(ServerLevel level, Volcano volcano) {
        if (!volcano.phase().lava()) {
            return;
        }
        double intensity = volcano.intensity();
        if (intensity < Config.LAVA_WHIRL_MIN_INTENSITY.get()) {
            return;
        }
        BlockPos vent = ventOf(level, volcano);
        if (vent == null) {
            return;
        }
        double craterRadius = volcano.craterRadius();
        Vec3 position = Vec3.atCenterOf(vent);
        if (!LavaWhirlModel.canFormLavaWhirl(true, intensity, 0.0D, craterRadius)) {
            return;
        }
        double share = Math.min(1.0D, intensity / Math.max(1.0E-6D, 3.0D));
        if (level.random.nextDouble() >= 0.06D + 0.16D * share) {
            return;
        }
        double wind = LavaWhirlModel.windMph(intensity);
        float radius = (float) LavaWhirlModel.radiusBlocks(craterRadius);
        int life = LavaWhirlModel.lifeTicks(intensity, level.random.nextDouble());
        long gameTime = level.getGameTime();
        Vec3 angle = randomHorizontal(level, 0.012D);
        AddonWeatherEvent event = new AddonWeatherEvent(nextId(level, gameTime), StormSubtype.LAVA_WHIRL,
                position, angle, radius, (float) wind, gameTime, gameTime + life);
        AddonWeatherEventService.spawnAddonEvent(level, event);
    }

    /**
     * A steam devil where lava is meeting water. Both halves are counted over a bounded grid around the vent
     * rather than assumed from the phase, because the reaction needs the two of them to actually be there: a
     * vent in the middle of a desert flows lava for days without ever making one.
     */
    private static void maybeSpawnSteamDevil(ServerLevel level, Volcano volcano) {
        BlockPos vent = ventOf(level, volcano);
        if (vent == null) {
            return;
        }
        int[] counts = countLavaAndWater(level, vent);
        if (!LavaWhirlModel.formsSteamDevil(counts[0], counts[1])) {
            return;
        }
        double strength = LavaWhirlModel.steamStrength(counts[0], counts[1]);
        if (level.random.nextDouble() >= 0.10D + 0.30D * strength) {
            return;
        }
        Vec3 position = Vec3.atCenterOf(vent).add(level.random.nextDouble() * 8.0D - 4.0D, 0.0D,
                level.random.nextDouble() * 8.0D - 4.0D);
        double wind = LavaWhirlModel.steamWindMph(strength);
        float radius = (float) LavaWhirlModel.steamRadiusBlocks(strength);
        int life = LavaWhirlModel.steamLifeTicks(strength, level.random.nextDouble());
        long gameTime = level.getGameTime();
        // Steam rises rather than drifting: the motion here is what carries the column's own lean, so it is
        // slow in every direction, and it is the only thing about a steam devil that is anywhere near gentle.
        AddonWeatherEvent event = new AddonWeatherEvent(nextId(level, gameTime), StormSubtype.STEAM_DEVIL,
                position, randomHorizontal(level, 0.006D), radius, (float) wind, gameTime, gameTime + life);
        AddonWeatherEventService.spawnAddonEvent(level, event);
    }

    /**
     * What a lava whirl does to the ground it crosses: lights what will burn, and now and then spills lava out
     * of the vent it came off. Both go through {@link BlockGrabSafety}, so a claim, a spawn-protected area or
     * another mod's structure is never touched, and both are budgeted, because a whirl is a moving fire and an
     * unbudgeted one is a map that changes shape while a player watches.
     */
    private static void burnTheGround(ServerLevel level) {
        int budget = MAX_EDITS_PER_PASS;
        for (AddonWeatherEvent event : AddonWeatherEventService.activeEvents(level)) {
            if (budget <= 0) {
                return;
            }
            LavaWhirlModel.Kind kind = kindOf(event.type());
            // The gate that says which of the two volcanic vortices can touch a block at all. Steam cannot,
            // and not running a pass for it is the difference between the two phenomena rather than a
            // feature that was left out.
            if (kind == LavaWhirlModel.Kind.NONE || !LavaWhirlModel.movesBlocks(kind)) {
                continue;
            }
            double intensity = LavaWhirlModel.intensityFromWind(event.maxWindMph());
            double ignite = LavaWhirlModel.ignitingChance(intensity);
            double spill = LavaWhirlModel.lavaSpillChance(intensity) * Math.max(0.0D, Config.LAVA_WHIRL_SPILL_CHANCE.get());
            for (int i = 0; i < 3 && budget > 0; i++) {
                BlockPos target = whirlTarget(level, event);
                if (target == null) {
                    continue;
                }
                if (level.random.nextDouble() < ignite && igniteFlammable(level, target)) {
                    budget--;
                } else if (level.random.nextDouble() < spill && spillLava(level, target)) {
                    budget--;
                }
            }
        }
    }

    /** Which of the two volcanic vortices a subtype is, if either. */
    private static LavaWhirlModel.Kind kindOf(StormSubtype type) {
        if (type == StormSubtype.LAVA_WHIRL) {
            return LavaWhirlModel.Kind.LAVA_WHIRL;
        }
        return type == StormSubtype.STEAM_DEVIL ? LavaWhirlModel.Kind.STEAM_DEVIL : LavaWhirlModel.Kind.NONE;
    }

    private static BlockPos whirlTarget(ServerLevel level, AddonWeatherEvent event) {
        double angle = level.random.nextDouble() * Math.PI * 2.0D;
        double distance = level.random.nextDouble() * Math.max(2.0D, event.radius());
        BlockPos column = BlockPos.containing(event.position().add(Math.cos(angle) * distance, 0.0D,
                Math.sin(angle) * distance));
        return BlockGrabSafety.safeSurface(level, column, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
    }

    private static boolean igniteFlammable(ServerLevel level, BlockPos pos) {
        BlockGrabSafety.BlockSample sample = BlockGrabSafety.sample(level, pos, null);
        if (!BlockGrabSafety.canEdit(sample)) {
            return false;
        }
        BlockState state = sample.state();
        if (state.isAir()) {
            BlockPos below = pos.below();
            BlockGrabSafety.BlockSample support = BlockGrabSafety.sample(level, below, null);
            if (!BlockGrabSafety.canEdit(support) || !isFlammable(support.state())) {
                return false;
            }
            return BlockGrabSafety.safeSetBlock(level, pos, Blocks.FIRE.defaultBlockState(), 3);
        }
        return isFlammable(state) && BlockGrabSafety.safeSetBlock(level, pos, Blocks.FIRE.defaultBlockState(), 3);
    }

    private static boolean isFlammable(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS) || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.FLOWERS) || state.is(BlockTags.CROPS) || state.is(BlockTags.WOOL)
                || state.is(BlockTags.REPLACEABLE);
    }

    private static boolean spillLava(ServerLevel level, BlockPos pos) {
        BlockGrabSafety.BlockSample sample = BlockGrabSafety.sample(level, pos, null);
        if (!BlockGrabSafety.canEdit(sample) || !sample.state().isAir()) {
            return false;
        }
        BlockPos below = pos.below();
        BlockGrabSafety.BlockSample support = BlockGrabSafety.sample(level, below, null);
        if (!BlockGrabSafety.canEdit(support) || support.state().isAir()) {
            return false;
        }
        return BlockGrabSafety.safeSetBlock(level, pos, Blocks.LAVA.defaultBlockState(), 3);
    }

    /** How many whirls of a kind are already standing near this vent. */
    private static int countNear(ServerLevel level, Volcano volcano, StormSubtype type) {
        Vec3 vent = Vec3.atCenterOf(volcano.vent());
        int count = 0;
        for (AddonWeatherEvent event : AddonWeatherEventService.activeEvents(level)) {
            if (event.type() == type && event.position().distanceTo(vent) <= CROWDING_RADIUS_BLOCKS) {
                count++;
            }
        }
        return count;
    }

    /** How much lava and how much water is standing around a vent, over a bounded grid. */
    private static int[] countLavaAndWater(ServerLevel level, BlockPos vent) {
        int lava = 0;
        int water = 0;
        for (int dx = -WATER_SCAN_RADIUS; dx <= WATER_SCAN_RADIUS; dx += WATER_SCAN_STEP) {
            for (int dz = -WATER_SCAN_RADIUS; dz <= WATER_SCAN_RADIUS; dz += WATER_SCAN_STEP) {
                BlockPos column = vent.offset(dx, 0, dz);
                BlockPos surface = BlockGrabSafety.safeSurface(level, column, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
                if (surface == null) {
                    continue;
                }
                BlockState state = level.getBlockState(surface);
                if (state.is(Blocks.LAVA)) {
                    lava += WATER_SCAN_STEP * WATER_SCAN_STEP;
                } else if (state.getFluidState().is(FluidTags.WATER)) {
                    water += WATER_SCAN_STEP * WATER_SCAN_STEP;
                }
            }
        }
        return new int[]{lava, water};
    }

    private static BlockPos ventOf(ServerLevel level, Volcano volcano) {
        BlockPos vent = volcano.vent();
        if (vent == null || !level.isLoaded(vent)) {
            return null;
        }
        BlockPos surface = BlockGrabSafety.safeSurface(level, vent, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        return surface == null ? vent : surface;
    }

    private static Vec3 randomHorizontal(ServerLevel level, double speed) {
        double angle = level.random.nextDouble() * Math.PI * 2.0D;
        return new Vec3(Math.cos(angle) * speed, 0.0D, Math.sin(angle) * speed);
    }

    /**
     * An id for a whirl. It is not the storm service's own formula: the two spawners share one event list, and
     * two systems that pick ids the same way out of the same clock are two systems that will eventually pick
     * the same one. This shifts by a different amount so the two sequences cannot meet.
     */
    private static long nextId(ServerLevel level, long gameTime) {
        return (gameTime << 12) ^ (level.random.nextInt(4096) & 0xFFFL);
    }
}
