package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The air over a piece of the map, read from the ground.
 *
 * <p>{@link BiomeClimate} is what a set of biomes adds up to; this is what is actually under the sky
 * at a place. It walks a grid of samples across a neighbourhood, reads each one's own biome, and
 * hands the lot to the model. The answer belongs to a <em>cell</em> of the map rather than to a
 * position, and is cached for that cell: an air mass is a regional thing, a storm that forms in one
 * is a storm that formed in that air, and a reading that changed every block would be neither
 * physical nor affordable. The cell is what a player would call the weather where they are - a few
 * hundred blocks of the same country - and it is the same cell the outlook is issued for.
 *
 * <p>Every entry expires, and the cache is bounded, because a map is bigger than a weather system:
 * a player flying across a continent should cost a sample per cell crossed and not a leak per cell
 * crossed. Walking into a new cell is a few dozen biome lookups, and then nothing for minutes.
 */
public final class BiomeClimateService {

    /** How far apart the samples are. The neighbourhood is a grid of these across the configured radius. */
    private static final int SAMPLE_STEP_BLOCKS = 64;
    /** How long a cell's air is kept before it is read again. */
    private static final long CACHE_TTL_TICKS = 20L * 60L * 5L;
    /** The most cells kept at once, oldest first out. */
    private static final int MAX_CACHED_CELLS = 2048;

    private static final Map<ResourceKey<Level>, Map<Long, Cached>> CACHE = new HashMap<>();

    private BiomeClimateService() {
    }

    /**
     * The air over the cell containing this position. Everything that asks what the sky can do here -
     * the profile, the outlook, a tornado's own air mass - comes through this, so they all answer with
     * the same air.
     */
    public static BiomeClimate.Reading reading(ServerLevel level, BlockPos pos) {
        if (level == null) {
            return calm();
        }
        BlockPos origin = pos == null ? level.getSharedSpawnPos() : pos;
        int cellSize = Config.climateRegionSizeBlocks();
        long key = BiomeClimate.cellKey(origin.getX(), origin.getZ(), cellSize);
        long now = level.getGameTime();
        Map<Long, Cached> cells = CACHE.computeIfAbsent(level.dimension(), ignored -> new HashMap<>());
        Cached cached = cells.get(key);
        if (cached != null && now - cached.sampledTick >= 0L && now - cached.sampledTick < CACHE_TTL_TICKS) {
            return cached.reading;
        }
        BiomeClimate.Reading reading = sample(level, cellKeyCentre(level, key, cellSize));
        cells.put(key, new Cached(reading, now));
        if (cells.size() > MAX_CACHED_CELLS) {
            prune(cells);
        }
        return reading;
    }

    /** Which belt a place is in, for a readout. */
    public static BiomeClimate.Alley alley(ServerLevel level, BlockPos pos) {
        return reading(level, pos).alley();
    }

    /**
     * What this place is prone to, as multipliers on each phenomenon's own spawn chance. Read through
     * one method so every spawn site asks the same table the same way, and so the strength knob is
     * applied exactly once - a caller that multiplied a call site by its own strength would silently
     * apply it twice. Climate off, no level, or a strength of zero all answer with a neutral table,
     * which is every place behaving exactly as it did before any of this existed.
     */
    public static BiomeClimate.Odds odds(ServerLevel level, BlockPos pos) {
        if (level == null || !Config.CLIMATE_ENABLED.get()) {
            return BiomeClimate.Odds.NONE;
        }
        double strength = Config.climateOddsStrength();
        if (strength <= 0.0D) {
            return BiomeClimate.Odds.NONE;
        }
        return BiomeClimate.odds(reading(level, pos)).blended(strength);
    }

    /** The same table for a position given as a vector, which is how storms carry their own position. */
    public static BiomeClimate.Odds odds(ServerLevel level, Vec3 pos) {
        return pos == null ? BiomeClimate.Odds.NONE : odds(level, BlockPos.containing(pos));
    }

    /** The air a world with no climate reading at all gets, used when there is no level to sample. */
    public static BiomeClimate.Reading calm() {
        return new BiomeClimate.Reading(0.5D, 0.35D, 0.90D, 0.0D, BiomeClimate.Family.PLAINS, 0.0D,
                BiomeClimate.Alley.TEMPERATE, 0.0D);
    }

    /** The per-family instability weights as the config has them, in family order. */
    public static double[] familyWeights() {
        BiomeClimate.Family[] families = BiomeClimate.Family.values();
        double[] weights = new double[families.length];
        for (BiomeClimate.Family family : families) {
            weights[family.ordinal()] = Config.climateFamilyWeight(family);
        }
        return weights;
    }

    public static void clear() {
        CACHE.clear();
    }

    /** How many cells are being remembered, for a harness or a diagnostic. */
    public static int cachedCells() {
        int total = 0;
        for (Map<Long, Cached> cells : CACHE.values()) {
            total += cells.size();
        }
        return total;
    }

    private static BlockPos cellKeyCentre(ServerLevel level, long key, int cellSize) {
        int x = BiomeClimate.cellCentreX(key, cellSize);
        int z = BiomeClimate.cellCentreZ(key, cellSize);
        return new BlockPos(x, level.getSeaLevel(), z);
    }

    /**
     * Reads the neighbourhood: a square grid of samples across the configured radius, each one's
     * surface biome, whether that surface is water, and whether it is cold enough there to snow. A
     * sample that is not loaded is skipped rather than forced, because the reading is an average and
     * an unloaded chunk has no air to average.
     */
    private static BiomeClimate.Reading sample(ServerLevel level, BlockPos centre) {
        int radius = Config.climateSampleRadiusBlocks();
        List<BiomeClimate.Sample> samples = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx += SAMPLE_STEP_BLOCKS) {
            for (int dz = -radius; dz <= radius; dz += SAMPLE_STEP_BLOCKS) {
                BlockPos pos = centre.offset(dx, 0, dz);
                if (!level.isLoaded(pos)) {
                    continue;
                }
                BlockPos surface = BlockGrabSafety.safeSurface(level, pos, Heightmap.Types.MOTION_BLOCKING);
                if (surface == null) {
                    surface = pos;
                }
                Biome biome = level.getBiome(surface).value();
                String path = level.getBiome(surface).unwrapKey()
                        .map(key -> key.location().getPath())
                        .orElse("");
                boolean water = level.getBlockState(surface.below()).getFluidState().is(FluidTags.WATER);
                BiomeClimate.Traits traits = new BiomeClimate.Traits(
                        biome.getBaseTemperature(),
                        biome.hasPrecipitation(),
                        biome.coldEnoughToSnow(surface),
                        path);
                samples.add(new BiomeClimate.Sample(traits, water));
            }
        }
        return BiomeClimate.read(samples, familyWeights());
    }

    /** Drops the oldest cells until the cache is back under its bound. */
    private static void prune(Map<Long, Cached> cells) {
        while (cells.size() > MAX_CACHED_CELLS) {
            long oldestKey = 0L;
            long oldestTick = Long.MAX_VALUE;
            Iterator<Map.Entry<Long, Cached>> iterator = cells.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Long, Cached> entry = iterator.next();
                if (entry.getValue().sampledTick < oldestTick) {
                    oldestTick = entry.getValue().sampledTick;
                    oldestKey = entry.getKey();
                }
            }
            cells.remove(oldestKey);
        }
    }

    private record Cached(BiomeClimate.Reading reading, long sampledTick) {
    }
}
