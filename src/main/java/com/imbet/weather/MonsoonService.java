package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The wet season, where it is raining, and what that does to the ground.
 *
 * <p>{@link MonsoonModel} is the calendar; this is what a world does with it. Three things come out of here and
 * all three are read by systems that already exist, which is deliberate: a season is not a new kind of storm,
 * and it must not need a new spawner, a new entity and a new renderer to be real. What it needs is for the
 * ordinary systems to be told that it is the wet season, so the same rain floods harder, the same sky greys
 * over, and the ground around a player is standing wet for weeks.
 *
 * <p><b>No networking, and no server-only reading.</b> The calendar is derived from the level's own day
 * counter, which both sides have and which is already identical on both sides, and the phase in the cycle is
 * taken from where the world's own spawn is - a property of the world both sides know without being told. So
 * the client can read the season for its own sky and the server can read it for its own floods and they cannot
 * disagree, and none of this needed a packet. The belt a place sits in is read the same way
 * {@link BiomeClimateService} reads air, from the biomes under a small grid, because that is the whole point
 * of the season: it lands in monsoon country and hardly touches a desert.
 *
 * <p>The per-pixel cost is one biome lookup per grid point per five minutes, cached by cell. Everything else on
 * the path is arithmetic.
 */
public final class MonsoonService {

    /** How far apart the belt samples are, in blocks. */
    private static final int BELT_SAMPLE_STEP_BLOCKS = 128;
    /** How long a cell's belt reading is kept. */
    private static final long BELT_CACHE_TTL_TICKS = 20L * 60L * 5L;
    /** The most cells kept at once. */
    private static final int MAX_CACHED_CELLS = 1024;
    /** How many of the season's own rain bands one player's neighbourhood is allowed to hold. */
    private static final int BANDS_PER_PLAYER = 3;

    private static final Map<ResourceKey<Level>, Map<Long, CachedBelt>> BELTS = new HashMap<>();

    private MonsoonService() {
    }

    /**
     * The season, as a calendar: whether it is running, how far in it is, and where the world is in its own
     * cycle. Read from the day counter and the world's own phase, so it is the same on both sides.
     */
    public static MonsoonModel.Report report(Level level) {
        if (level == null) {
            return MonsoonModel.Report.NONE;
        }
        long day = level.getDayTime() / 24000L;
        BlockPos spawn = level.getSharedSpawnPos();
        int offset = spawn == null ? 0 : Math.floorMod(spawn.getX() * 31 + spawn.getZ() * 17, Math.max(1, Config.MONSOON_PERIOD_DAYS.get()));
        return MonsoonModel.report(day, Config.MONSOON_SEASON_DAYS.get(), Config.MONSOON_PERIOD_DAYS.get(), offset);
    }

    /** Everything the season has to say about a place, or {@link Conditions#NONE} outside the season. */
    public static Conditions conditions(Level level, Vec3 pos) {
        if (level == null || !Config.MONSOON_ENABLED.get()) {
            return Conditions.NONE;
        }
        MonsoonModel.Report report = report(level);
        if (!report.active()) {
            return Conditions.NONE;
        }
        double belt = beltMultiplier(level, pos);
        MonsoonModel.Settings settings = settings();
        return new Conditions(
                true,
                report.strength(),
                belt,
                MonsoonModel.rainIntensity(report.strength(), belt, settings),
                MonsoonModel.floodMultiplier(report.strength(), belt, settings),
                MonsoonModel.skyShare(report.strength(), settings),
                MonsoonModel.cells(report.strength(), belt),
                report
        );
    }

    /** The season's rain at a place, on the scale the mod's rain and floods are read on. */
    public static double rainIntensity(Level level, Vec3 pos) {
        return conditions(level, pos).rainIntensity();
    }

    /**
     * How much harder a given rain floods here because the season has been wetting this ground for days. Read
     * for storms as well as for the season's own bands: the same downpour onto soaked ground does more, which
     * is the coupling the whole feature is about. Answers 1.0 outside the season, so a caller can multiply by
     * it unconditionally.
     */
    public static double floodMultiplier(Level level, Vec3 pos) {
        return conditions(level, pos).floodMultiplier();
    }

    /** How much of the sky the season is taking here, as a share of the mod's own storm tint. */
    public static double skyShare(Level level, Vec3 pos) {
        return conditions(level, pos).skyShare();
    }

    /** The season's rain bands near a place, as centres and one intensity, for the ground to be flooded at. */
    public static List<Band> bandsNear(Level level, Vec3 pos) {
        Conditions conditions = conditions(level, pos);
        if (!conditions.active() || conditions.rainIntensity() <= 0.0D) {
            return List.of();
        }
        int wanted = Math.max(0, Math.min(BANDS_PER_PLAYER, conditions.cells()));
        if (wanted == 0) {
            return List.of();
        }
        long centreKey = MonsoonModel.cellKey(pos.x, pos.z);
        double[] centre = MonsoonModel.cellCentre(centreKey);
        int cx = (int) Math.floor(centre[0] / MonsoonModel.CELL_SPACING_BLOCKS);
        int cz = (int) Math.floor(centre[1] / MonsoonModel.CELL_SPACING_BLOCKS);
        List<Band> bands = new ArrayList<>();
        // The nearest cells first, and only the belt's own share of them: the season rains in bands, so a
        // neighbourhood gets a few of them rather than a single mass sitting on the player.
        for (int ring = 0; ring <= 2 && bands.size() < wanted; ring++) {
            for (int dx = -ring; dx <= ring && bands.size() < wanted; dx++) {
                for (int dz = -ring; dz <= ring && bands.size() < wanted; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    long key = MonsoonModel.cellKeyFor(cx + dx, cz + dz);
                    double[] cell = MonsoonModel.cellCentre(key);
                    // The cell's centre is asked for twice - once to read the belt at it, once to hand the band
                    // its own position - and a Vec3 cannot be changed after it is built, so it need only be
                    // built once.
                    Vec3 cellCentre = new Vec3(cell[0], pos.y, cell[1]);
                    double beltThere = beltMultiplier(level, cellCentre);
                    if (beltThere <= MonsoonModel.BELT_DRY) {
                        continue;
                    }
                    double intensity = MonsoonModel.rainIntensity(conditions.strength(), beltThere, settings());
                    if (intensity <= 0.0D) {
                        continue;
                    }
                    bands.add(new Band(cellCentre, MonsoonModel.CELL_RADIUS_BLOCKS,
                            intensity * conditions.floodMultiplier()));
                }
            }
        }
        return bands;
    }

    public static void clear() {
        BELTS.clear();
    }

    private static MonsoonModel.Settings settings() {
        return new MonsoonModel.Settings(Config.MONSOON_RAIN_INTENSITY.get(),
                Config.MONSOON_FLOOD_MULTIPLIER.get(), Config.MONSOON_SKY_SHARE.get());
    }

    /**
     * What the season is worth here, from the biomes under a small grid around the point. Everything the
     * season does is scaled by it, so a place in the belt gets the season whole and dry country gets a
     * quarter of it, and both of those come out of one reading rather than a list of which biomes count.
     */
    private static double beltMultiplier(Level level, Vec3 pos) {
        if (pos == null) {
            return MonsoonModel.BELT_WET;
        }
        int cellSize = Math.max(1, Config.climateRegionSizeBlocks());
        long key = BiomeClimate.cellKey((int) Math.floor(pos.x), (int) Math.floor(pos.z), cellSize);
        Map<Long, CachedBelt> cells = BELTS.computeIfAbsent(level.dimension(), ignored -> new HashMap<>());
        long now = level.getGameTime();
        CachedBelt cached = cells.get(key);
        if (cached != null && now - cached.sampledTick >= 0L && now - cached.sampledTick < BELT_CACHE_TTL_TICKS) {
            return cached.multiplier;
        }
        double multiplier = MonsoonModel.beltMultiplier(readBelt(level, pos));
        cells.put(key, new CachedBelt(multiplier, now));
        if (cells.size() > MAX_CACHED_CELLS) {
            cells.clear();
        }
        return multiplier;
    }

    /**
     * Whether a place is in the monsoon belt, and whether its air is merely wet. Read from the biome grid at
     * the same spacing the climate service uses, at the point's own altitude rather than the surface, because
     * this runs on both sides and the surface height is a server-side question.
     */
    private static MonsoonModel.Belt readBelt(Level level, Vec3 pos) {
        List<BiomeClimate.Sample> samples = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos sample = BlockPos.containing(pos.x + dx * BELT_SAMPLE_STEP_BLOCKS, pos.y,
                        pos.z + dz * BELT_SAMPLE_STEP_BLOCKS);
                Biome biome = level.getBiome(sample).value();
                String path = level.getBiome(sample).unwrapKey()
                        .map(key -> key.location().getPath())
                        .orElse("");
                boolean water = !level.isLoaded(sample)
                        || level.getBlockState(sample.below()).getFluidState().is(FluidTags.WATER);
                samples.add(new BiomeClimate.Sample(new BiomeClimate.Traits(
                        biome.getBaseTemperature(),
                        biome.hasPrecipitation(),
                        biome.coldEnoughToSnow(sample),
                        path), water));
            }
        }
        return MonsoonModel.beltOf(BiomeClimate.read(samples, BiomeClimateService.familyWeights()));
    }

    /** One of the season's own rain bands: where it stands, how wide it is, and how hard it is raining there. */
    public record Band(Vec3 centre, double radiusBlocks, double intensity) {
    }

    /** Everything the season has to say about one place at one moment. */
    public record Conditions(boolean active, double strength, double beltMultiplier, double rainIntensity,
                             double floodMultiplier, double skyShare, int cells, MonsoonModel.Report report) {

        public static final Conditions NONE = new Conditions(false, 0.0D, 0.0D, 0.0D, 1.0D, 0.0D, 0,
                MonsoonModel.Report.NONE);
    }

    private record CachedBelt(double multiplier, long sampledTick) {
    }
}
