package com.imbet.volcano;

import com.imbet.Config;
import com.imbet.network.NetworkSafety;
import com.imbet.network.VolcanoSyncPayload;
import com.imbet.weather.AlertLevel;
import com.imbet.weather.AlertService;
import com.imbet.weather.BlockGrabSafety;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Everything a volcano does to the world.
 *
 * <p>The 1.12.2 original poured lava up a pipe, put a lava blob on top and let it set, forever. This runs the
 * ladder instead: unrest, steam, a column, the collapse of that column, the lava that follows it, and the mud
 * the rain makes of the ash afterwards. Each phase has its own hazards and its own reach, and all of them are
 * expressed in {@link VolcanoModel} so the numbers can be checked against the real world rather than tuned by
 * eye.
 *
 * <p>Two rules govern how much of the world it touches. Nothing it does is unbounded: every tick gets a block
 * budget, and every edit goes through {@link BlockGrabSafety} so claims, spawn protection and modded structures
 * are left alone. And the far field is represented rather than built: block changes happen within
 * {@code volcano.hazardRadiusBlocks} of the vent, while the ash cloud, the haze and the advisory reach as far
 * as the physics says they should. Re-landscaping a thousand chunks to draw an ash fall that a player sees as
 * grey air would be a waste of a server's life.
 */
public final class VolcanoService {
    /** How close a player has to be for the volcano to keep working on the world around it. */
    private static final int WORK_RANGE_MARGIN = 256;

    /** How many times the hazard budget the cone may place in one tick while it is being built. */
    private static final int BUILD_BUDGET_MULTIPLE = 3;

    /** The absolute ceiling on one tick's cone placement, so a huge scale cannot stall the server. */
    private static final int BUILD_BLOCKS_PER_TICK_CAP = 512;

    private VolcanoService() {
    }

    // ================================================================== entry points

    public static void tick(ServerLevel level) {
        if (!Config.VOLCANO_ENABLED.get()) {
            return;
        }
        VolcanoStore store = VolcanoStore.get(level);
        long gameTime = level.getGameTime();
        if (Config.VOLCANO_NATURAL_SPAWNING.get()
                && gameTime % Math.max(20, Config.VOLCANO_SCAN_INTERVAL_TICKS.get()) == 0L) {
            maybeSpawn(level, store, gameTime);
        }
        for (Volcano volcano : new ArrayList<>(store.volcanoes())) {
            if (volcano.dead()) {
                store.remove(volcano.id());
                continue;
            }
            tickVolcano(level, volcano, gameTime);
        }
        // What the mountain can spin off itself: a whirl of fire above a vent with magma at it, and a column
        // of steam where its lava is reaching water. Both belong to the volcano rather than to the storm
        // ladder - what they are made of is the ground - so they are driven from here and rolled for on their
        // own much slower cadence.
        com.imbet.weather.LavaWhirlService.tick(level);
    }

    /**
     * Puts a volcano at a spot on purpose, for the command and for testing.
     *
     * <p>The vent is placed on the surface, and the edifice is left unbuilt: it arrives with the first
     * eruption, which is both what happens in the world and a way to see whether the mountain the system draws
     * is one a player would believe.
     */
    public static Volcano spawnCommandVolcano(ServerLevel level, BlockPos pos, double playerY, double intensity, int scale) {
        if (pos == null) {
            return null;
        }
        VolcanoStore store = VolcanoStore.get(level);
        int ventY = resolveVentY(level, pos, playerY);
        Volcano volcano = new Volcano(store.nextId(), pos.getX(), pos.getZ(), ventY);
        volcano.setScale(VolcanoModel.clamp(scale, 0, 40));
        volcano.setVentY(ventY);
        volcano.setWind(1.0D, 0.0D, 14.0D);
        volcano.setIntensity(VolcanoModel.clamp(intensity, VolcanoModel.MIN_INTENSITY, VolcanoModel.MAX_INTENSITY));
        volcano.setEpisodes(1);
        volcano.setQuiescenceTicks(0L);
        volcano.setAshTick(level.getGameTime());
        store.add(volcano);
        enterPhase(level, volcano, VolcanoPhase.UNREST, level.getGameTime());
        return volcano;
    }

    /**
     * Where the vent actually belongs, in a world that may not be able to say.
     *
     * <p>A volcano is a mountain, so it goes on the surface - never at the depth of whatever cave the caller
     * happens to be in, and never inside the ground under their feet. Three heightmaps are tried because a
     * freshly generated chunk can have one primed and another not, and when none of them answers, sea level
     * (or the caller's own feet, if they are higher) is a better guess than the bottom of the world.
     */
    private static int resolveVentY(ServerLevel level, BlockPos pos, double playerY) {
        BlockPos column = new BlockPos(pos.getX(), 0, pos.getZ());
        for (Heightmap.Types type : new Heightmap.Types[] {
                Heightmap.Types.WORLD_SURFACE, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Heightmap.Types.MOTION_BLOCKING}) {
            BlockPos surface = BlockGrabSafety.safeSurface(level, column, type);
            if (surface != null && surface.getY() > level.getMinBuildHeight() + 4) {
                return surface.getY();
            }
        }
        return (int) Math.max(level.getSeaLevel() + 1, Math.floor(playerY) + 1.0D);
    }

    public static List<Volcano> volcanoes(ServerLevel level) {
        return VolcanoStore.get(level).volcanoes();
    }

    public static Volcano nearest(ServerLevel level, double x, double z, double maxDistance) {
        return VolcanoStore.get(level).nearest(x, z, maxDistance);
    }

    public static boolean remove(ServerLevel level, long id) {
        return VolcanoStore.get(level).remove(id);
    }

    public static Volcano find(ServerLevel level, long id) {
        return VolcanoStore.get(level).byId(id);
    }

    /**
     * Makes a volcano do the next thing it was going to do anyway, now.
     *
     * <p>A dormant volcano starts an episode; one already erupting is pushed one rung up the ladder. It is the
     * command-line equivalent of waiting, for the case where waiting is what you are trying to avoid - and the
     * episode's size is still the one its own seed decided on, so what it does is what it was always going to
     * do rather than something the command chose.
     */
    public static boolean forceEruption(ServerLevel level, long id) {
        Volcano volcano = VolcanoStore.get(level).byId(id);
        if (volcano == null) {
            return false;
        }
        long gameTime = level.getGameTime();
        if (volcano.phase() == VolcanoPhase.DORMANT) {
            volcano.setQuiescenceTicks(0L);
            beginEpisode(level, volcano, gameTime);
        } else if (volcano.phase() == VolcanoPhase.WANING) {
            endEpisode(volcano, gameTime);
            beginEpisode(level, volcano, gameTime);
        } else {
            enterPhase(level, volcano, volcano.phase().next(), gameTime);
        }
        return true;
    }

    /**
     * The volcanic product for a place, folded into the mod's ordinary alert ladder.
     *
     * <p>An eruption is a warning, not a forecast: the ash advisory is a watch-strength product and the
     * pyroclastic flow warning is a full warning that sounds a siren, which is exactly how these are issued in
     * the real world. Routing it through {@link AlertService} means the siren station gating, the banner, the
     * redstone output and the product codes all apply to volcanoes without a second system.
     */
    public static AlertService.AlertResult alertFor(ServerLevel level, Vec3 origin) {
        if (!Config.VOLCANO_ENABLED.get() || !Config.VOLCANO_ALERTS.get() || origin == null) {
            return AlertService.NONE;
        }
        Volcano closest = null;
        double closestSqr = (double) Config.VOLCANO_ADVISORY_RANGE_BLOCKS.get() * Config.VOLCANO_ADVISORY_RANGE_BLOCKS.get();
        for (Volcano volcano : VolcanoStore.get(level).volcanoes()) {
            double dx = volcano.x() + 0.5D - origin.x;
            double dz = volcano.z() + 0.5D - origin.z;
            double distanceSqr = dx * dx + dz * dz;
            if (distanceSqr < closestSqr) {
                closestSqr = distanceSqr;
                closest = volcano;
            }
        }
        if (closest == null || !closest.phase().erupting()) {
            return AlertService.NONE;
        }
        double distance = Math.sqrt(closestSqr);
        Vec3 position = new Vec3(closest.x() + 0.5D, closest.ventY(), closest.z() + 0.5D);
        long expires = level.getGameTime() + 20L * 60L * 10L;
        if (closest.phase().pyroclastic()) {
            // The collapse is the one product that has to be acted on within seconds.
            double reach = VolcanoModel.surgeRunoutBlocks(closest.intensity());
            return new AlertService.AlertResult(
                    AlertLevel.PYROCLASTIC_FLOW_WARNING,
                    "Pyroclastic flow - " + closest.phase().label(),
                    "PDC",
                    "OBSERVED",
                    position,
                    position.add(new Vec3(closest.windX(), 0.0D, closest.windZ()).scale(Math.min(reach, 256.0D))),
                    closest.id(),
                    expires);
        }
        AlertLevel alertLevel = distance <= Config.VOLCANO_HAZARD_RADIUS_BLOCKS.get()
                ? AlertLevel.VOLCANIC_ASH_WARNING
                : AlertLevel.VOLCANIC_ASH_ADVISORY;
        String product = alertLevel == AlertLevel.VOLCANIC_ASH_WARNING ? "VAW" : "VAA";
        return new AlertService.AlertResult(
                alertLevel,
                "Volcanic ash - " + closest.phase().label(),
                product,
                "OBSERVED",
                position,
                position,
                closest.id(),
                expires);
    }

    // ================================================================== spawning

    /**
     * Looks for a place a volcano could plausibly be.
     *
     * <p>A volcano has to be somewhere, not anywhere: it needs a province that is volcanic at all (a property of
     * the seed, so the same ground is volcanic every session), enough relief that a mountain is not being
     * dropped onto a plain, and enough room that it is not growing inside the last one. Candidates are drawn in
     * a ring around a player rather than at random in the world, because the world is enormous and a volcano
     * nobody can walk to is a volcano nobody ever sees.
     */
    private static void maybeSpawn(ServerLevel level, VolcanoStore store, long gameTime) {
        if (!store.hasRoom()) {
            return;
        }
        if (level.players().isEmpty()) {
            return;
        }
        long sinceLast = gameTime - store.lastGenesisTick();
        if (store.lastGenesisTick() != Long.MIN_VALUE && sinceLast < Config.VOLCANO_MIN_SPAWN_INTERVAL_TICKS.get()) {
            return;
        }
        if (level.random.nextDouble() > Config.VOLCANO_SPAWN_CHANCE.get()) {
            return;
        }
        ServerPlayer anchor = level.players().get(level.random.nextInt(level.players().size()));
        long seed = level.getSeed();
        double minDistance = Config.VOLCANO_SPAWN_MIN_DISTANCE_BLOCKS.get();
        double maxDistance = Math.max(minDistance + 64.0D, Config.VOLCANO_SPAWN_MAX_DISTANCE_BLOCKS.get());
        for (int attempt = 0; attempt < 24; attempt++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double distance = minDistance + level.random.nextDouble() * (maxDistance - minDistance);
            int x = (int) Math.floor(anchor.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(anchor.getZ() + Math.sin(angle) * distance);
            if (VolcanoModel.provincePotential(seed, x, z) < Config.VOLCANO_PROVINCE_THRESHOLD.get()) {
                continue;
            }
            if (store.nearest(x, z, Config.VOLCANO_MIN_SPACING_BLOCKS.get()) != null) {
                continue;
            }
            BlockPos surface = BlockGrabSafety.safeSurface(level, new BlockPos(x, 0, z), Heightmap.Types.WORLD_SURFACE);
            if (surface == null) {
                continue;
            }
            if (level.getBlockState(surface).is(Blocks.WATER) || relief(level, x, z) < Config.VOLCANO_MIN_RELIEF_BLOCKS.get()) {
                continue;
            }
            Volcano volcano = new Volcano(store.nextId(), x, z, surface.getY());
            // It is born small and its eruptions are what make it a mountain, but not so small that it reads as
            // an artefact of the terrain before it has ever erupted.
            volcano.setScale(VolcanoModel.NATURAL_START_SCALE);
            volcano.setWind(1.0D, 0.0D, 12.0D);
            volcano.setQuiescenceTicks(VolcanoModel.quiescenceTicks(volcano.id(), 0, seed,
                    Config.VOLCANO_QUIESCENCE_MIN_TICKS.get(), Config.VOLCANO_QUIESCENCE_MAX_TICKS.get()));
            volcano.setAshTick(gameTime);
            store.add(volcano);
            store.setLastGenesisTick(gameTime);
            return;
        }
    }

    /** How much the ground varies around a spot, which is what tells a mountain range from a plain. */
    private static int relief(ServerLevel level, int x, int z) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int dx = -48; dx <= 48; dx += 48) {
            for (int dz = -48; dz <= 48; dz += 48) {
                BlockPos surface = BlockGrabSafety.safeSurface(level, new BlockPos(x + dx, 0, z + dz), Heightmap.Types.WORLD_SURFACE);
                if (surface == null) {
                    continue;
                }
                min = Math.min(min, surface.getY());
                max = Math.max(max, surface.getY());
            }
        }
        return max <= min ? 0 : max - min;
    }

    // ================================================================== the phase machine

    private static void tickVolcano(ServerLevel level, Volcano volcano, long gameTime) {
        sampleWind(volcano, gameTime);
        boolean working = level.hasNearbyAlivePlayer(volcano.x(), volcano.ventY(), volcano.z(),
                Config.VOLCANO_HAZARD_RADIUS_BLOCKS.get() + WORK_RANGE_MARGIN);
        if (volcano.shellVersion() < Volcano.SHELL_VERSION) {
            // The cone's geometry changed under a mountain that is already standing, so it is laid again from the
            // first block. See Volcano.SHELL_VERSION: the materials are position-hashed, so this fills the gaps
            // rather than sculpting something new, and it repairs saves made by the earlier builder.
            volcano.resetEdifice(Volcano.SHELL_VERSION);
        }
        if (volcano.phase() == VolcanoPhase.DORMANT) {
            if (volcano.quiescent()) {
                beginEpisode(level, volcano, gameTime);
            } else {
                volcano.countQuiescence();
            }
        } else {
            volcano.tickPhase();
            Budget budget = new Budget(working ? Config.VOLCANO_BLOCK_BUDGET.get() : 0);
            if (working) {
                // The edifice is built whatever the phase is. A mountain whose build was interrupted by a server
                // stop, or one an updated geometry marked for repair, should finish itself where it stands rather
                // than wait for the next eruption to be whole.
                buildEdifice(level, volcano, budget);
                tickPhaseHazards(level, volcano, gameTime, budget);
            }
            if (volcano.phaseFinished()) {
                VolcanoPhase next = volcano.phase().next();
                if (next == VolcanoPhase.DORMANT) {
                    endEpisode(volcano, gameTime);
                } else {
                    enterPhase(level, volcano, next, gameTime);
                }
            }
        }
        // A dormant volcano is not a dead one: its ash is still loose, and rain still turns it into mud.
        Budget idle = new Budget(working ? Config.VOLCANO_BLOCK_BUDGET.get() : 0);
        if (working) {
            maybeStartLahars(level, volcano, gameTime);
            tickLahars(level, volcano, gameTime, idle);
            if (volcano.phase() == VolcanoPhase.DORMANT && onshoreCrustCadence(gameTime)) {
                coolLavaLake(level, volcano, idle);
            }
        }
        maybeSync(level, volcano, gameTime);
    }

    /**
     * The moment a volcano wakes up, weeks of warning become an eruption.
     *
     * <p>The size is drawn from the volcano's own seed and episode count, so it is decided once and survives a
     * reload; the draw is weighted towards the small end of the scale, because that is how eruptions are
     * distributed.
     */
    private static void beginEpisode(ServerLevel level, Volcano volcano, long gameTime) {
        double intensity = VolcanoModel.episodeIntensity(volcano.id(), volcano.episodes(), level.getSeed(), Config.VOLCANO_MAX_INTENSITY.get());
        volcano.setIntensity(intensity);
        volcano.setEpisodes(volcano.episodes() + 1);
        volcano.setPressure(0.1D);
        volcano.setMagmaLevel(0.05D);
        enterPhase(level, volcano, VolcanoPhase.UNREST, gameTime);
    }

    private static void endEpisode(Volcano volcano, long gameTime) {
        volcano.setScale(VolcanoModel.scaleAfterEpisode(volcano.scale(), volcano.intensity()));
        volcano.setPressure(0.0D);
        volcano.setMagmaLevel(VolcanoModel.clamp(volcano.magmaLevel() - 0.5D, 0.0D, 1.0D));
        volcano.surges().clear();
        volcano.setAshTick(gameTime);
        volcano.enterPhase(VolcanoPhase.DORMANT, 1L);
        volcano.setQuiescenceTicks(VolcanoModel.quiescenceTicks(volcano.id(), volcano.episodes(), 0L,
                Config.VOLCANO_QUIESCENCE_MIN_TICKS.get(), Config.VOLCANO_QUIESCENCE_MAX_TICKS.get()));
    }

    private static void enterPhase(ServerLevel level, Volcano volcano, VolcanoPhase phase, long gameTime) {
        volcano.enterPhase(phase, durationTicks(phase));
        switch (phase) {
            case UNREST -> {
                volcano.setPressure(0.15D);
                crackGround(level, volcano, gameTime);
            }
            case PHREATIC -> volcano.setPressure(0.35D);
            case MAGMATIC -> {
                volcano.setPressure(0.6D);
                volcano.setMagmaLevel(Math.max(volcano.magmaLevel(), 0.7D));
            }
            case COLLAPSE -> {
                volcano.setPressure(1.0D);
                startSurges(level, volcano);
            }
            case FLOW -> startLavaFlows(level, volcano);
            case WANING -> volcano.setPressure(0.0D);
            case DORMANT -> {
            }
        }
    }

    private static long durationTicks(VolcanoPhase phase) {
        return switch (phase) {
            case UNREST -> Config.VOLCANO_WARNING_TICKS.get();
            case PHREATIC -> Config.VOLCANO_PHREATIC_TICKS.get();
            case MAGMATIC -> Config.VOLCANO_COLUMN_TICKS.get();
            case COLLAPSE -> Config.VOLCANO_COLLAPSE_TICKS.get();
            case FLOW -> Config.VOLCANO_FLOW_TICKS.get();
            case WANING -> Config.VOLCANO_WANING_TICKS.get();
            case DORMANT -> 1L;
        };
    }

    private static void tickPhaseHazards(ServerLevel level, Volcano volcano, long gameTime, Budget budget) {
        switch (volcano.phase()) {
            case UNREST -> tickQuakes(level, volcano, gameTime, budget);
            case PHREATIC -> {
                tickQuakes(level, volcano, gameTime, budget);
                throwBombs(level, volcano, gameTime, budget, false);
                layAsh(level, volcano, gameTime, budget);
                tickSounds(level, volcano, gameTime);
            }
            case MAGMATIC -> {
                volcano.buildPressure(0.004D);
                throwBombs(level, volcano, gameTime, budget, true);
                layAsh(level, volcano, gameTime, budget);
                ashLightning(level, volcano, gameTime);
                tickQuakes(level, volcano, gameTime, budget);
                tickSounds(level, volcano, gameTime);
            }
            case COLLAPSE -> {
                tickSurges(level, volcano, gameTime, budget);
                throwBombs(level, volcano, gameTime, budget, true);
                layAsh(level, volcano, gameTime, budget);
                tickQuakes(level, volcano, gameTime, budget);
                tickSounds(level, volcano, gameTime);
            }
            case FLOW -> {
                tickLavaFlows(level, volcano, gameTime, budget);
                layAsh(level, volcano, gameTime, budget);
                tickQuakes(level, volcano, gameTime, budget);
                if (onshoreCrustCadence(gameTime)) {
                    fillLavaLake(level, volcano, budget);
                }
            }
            case WANING -> {
                tickQuakes(level, volcano, gameTime, budget);
                if (onshoreCrustCadence(gameTime)) {
                    coolLavaLake(level, volcano, budget);
                }
            }
            case DORMANT -> {
            }
        }
    }

    /**
     * The volcano's own record of the prevailing wind.
     *
     * <p>An ash cloud is shaped by the mean wind over hours, not by the gust at a tick, and the plume has to be
     * consistent between the server's ash fall and the client's particles. So the volcano keeps one direction
     * and drifts it slowly, and both sides use it.
     */
    private static void sampleWind(Volcano volcano, long gameTime) {
        double hours = gameTime / 1000.0D;
        double targetBearing = Math.toRadians((volcano.id() * 73.0D + hours * 9.0D + Math.sin(hours * 0.05D) * 35.0D) % 360.0D);
        double targetSpeed = 9.0D + 15.0D * (0.5D + 0.5D * Math.sin(hours * 0.03D + 1.7D));
        double currentBearing = Math.atan2(volcano.windZ(), volcano.windX());
        double difference = targetBearing - currentBearing;
        while (difference > Math.PI) {
            difference -= Math.PI * 2.0D;
        }
        while (difference < -Math.PI) {
            difference += Math.PI * 2.0D;
        }
        double bearing = currentBearing + difference * 0.002D;
        double speed = volcano.windMph() + (targetSpeed - volcano.windMph()) * 0.002D;
        volcano.setWind(Math.cos(bearing) * speed, Math.sin(bearing) * speed, speed);
    }

    // ================================================================== building the mountain

    /**
     * Walks the shell of the cone a few hundred blocks per tick, and remembers where it stopped.
     *
     * <p>The mountain is laid down during the eruption that grows it, which is the honest order: a volcano with
     * no eruptive history is a hill, and the cone is the pile its eruptions left. Rebuilding at the larger scale
     * is idempotent because every block's material comes from a hash of its position, so the parts that were
     * already right are simply placed again.
     */
    private static void buildEdifice(ServerLevel level, Volcano volcano, Budget budget) {
        int target = Math.max(1, volcano.scale());
        if (VolcanoModel.edificeComplete(volcano.builtScale(), volcano.buildStage(), target)) {
            return;
        }
        // The mountain rises under whoever is standing on it. That is deliberate - ground being lifted by the
        // cone growing through it is the effect - but it must shed them rather than swallow them, and it must
        // never leave rock where they are standing. Both of those only cost anything when somebody is inside
        // the footprint, which is why the guard is decided once here rather than per block.
        boolean guarded = carryRiders(level, volcano);
        if (volcano.builtScale() < 0) {
            volcano.setBuiltScale(0);
        }
        // The cone is allowed a larger share of the tick than the hazards are, because it is the one part of an
        // eruption whose whole point is to be watched arriving, and its total size is fixed by the shell - it is
        // a build that finishes, not a drain that runs forever. It is still charged to the same budget below, so
        // this raises the ceiling on one tick's work rather than removing it.
        int allowance = Math.min(BUILD_BLOCKS_PER_TICK_CAP,
                Math.max(budget.remaining, Config.VOLCANO_BLOCK_BUDGET.get() * BUILD_BUDGET_MULTIPLE));
        int placed = 0;
        if (volcano.buildStage() == 0) {
            int scale = VolcanoModel.nextConeScale(volcano.builtScale(), target);
            while (placed < allowance) {
                int[] point = VolcanoModel.coneShellPoint(scale, volcano.buildCursor());
                if (point == null) {
                    volcano.setBuildCursor(0);
                    volcano.setBuiltScale(scale);
                    if (VolcanoModel.nextConeScale(scale, target) != 0) {
                        // This size is laid, but the mountain is not yet the size it is meant to be, so the next
                        // tick starts the next size up. Cutting the crater here instead is the trap: the crater
                        // is cut at the summit of the *target* size, so on a half-built cone it is cut into thin
                        // air a dozen blocks above the actual summit.
                        charge(budget, placed);
                        return;
                    }
                    volcano.setBuildStage(1);
                    charge(budget, placed);
                    return;
                }
                placeConeBlock(level, volcano, scale, point[0], point[1], point[2], point[3], guarded);
                volcano.setBuildCursor(volcano.buildCursor() + 1);
                placed++;
            }
            charge(budget, placed);
            return;
        }
        // Stage 1: open the crater and line the throat.
        int craterRadius = (int) Math.ceil(volcano.craterRadius());
        int rings = Math.max(1, (int) Math.round(Math.PI * craterRadius));
        int craterLayers = 5;
        while (placed < allowance) {
            int index = volcano.buildCursor();
            int layer = index / rings;
            int point = index % rings;
            if (layer >= craterLayers) {
                volcano.setBuildCursor(0);
                volcano.setBuildStage(2);
                volcano.setBuiltScale(target);
                charge(budget, placed);
                return;
            }
            double angle = 2.0D * Math.PI * point / rings;
            int x = volcano.x() + (int) Math.round(Math.cos(angle) * craterRadius);
            int z = volcano.z() + (int) Math.round(Math.sin(angle) * craterRadius);
            int y = (int) Math.round(volcano.summitY()) - layer;
            BlockPos pos = new BlockPos(x, y, z);
            if (layer == craterLayers - 1) {
                // The floor of the crater is the throat: hot rock while the volcano is erupting, and set
                // stone once it has finished.
                place(level, pos, volcano.phase().lava() ? Blocks.LAVA.defaultBlockState() : Blocks.OBSIDIAN.defaultBlockState());
            } else {
                destroy(level, pos);
            }
            volcano.setBuildCursor(index + 1);
            placed++;
        }
        charge(budget, placed);
    }

    /**
     * Hands back what the cone actually placed, so the hazards sharing this tick's budget see the real cost.
     *
     * <p>Charging the cone's full allowance whether or not it was spent would let it idle away a budget it is
     * not using, which would show up as an eruption that stops laying ash for no visible reason.
     */
    private static void charge(Budget budget, int placed) {
        budget.remaining = Math.max(0, budget.remaining - placed);
    }

    /**
     * Carries everyone the cone is growing under up and outward, and reports whether anyone is inside it.
     *
     * <p>A volcano summoned at your feet builds its shell around you, and an eruption that arrives under a
     * player is supposed to throw them off the mountain - not seal them inside it. So while the edifice grows,
     * anything living within its footprint is pushed up the slope and outward, and its fall damage is cleared
     * because the ground rose rather than the player dropped.
     */
    private static boolean carryRiders(ServerLevel level, Volcano volcano) {
        double radius = Math.max(6.0D, volcano.edificeRadius() + 2.0D);
        AABB box = new AABB(
                volcano.x() + 0.5D - radius, volcano.ventY() - 4.0D, volcano.z() + 0.5D - radius,
                volcano.x() + 0.5D + radius, volcano.ventY() + volcano.edificeHeight() + 8.0D, volcano.z() + 0.5D + radius);
        List<net.minecraft.world.entity.LivingEntity> riders = level.getEntitiesOfClass(
                net.minecraft.world.entity.LivingEntity.class, box, entity -> !entity.isSpectator());
        for (net.minecraft.world.entity.LivingEntity rider : riders) {
            double dx = rider.getX() - (volcano.x() + 0.5D);
            double dz = rider.getZ() - (volcano.z() + 0.5D);
            double distance = Math.max(0.001D, Math.hypot(dx, dz));
            rider.push(dx / distance * 0.06D, 0.16D, dz / distance * 0.06D);
            rider.fallDistance = 0.0F;
        }
        return !riders.isEmpty();
    }

    /**
     * Whether a block of the cone may be placed here.
     *
     * <p>The one thing the mountain must never do is put rock where a player is standing: being entombed in the
     * cone you just spawned is a death sentence, and it is a black screen besides. The skipped blocks leave a
     * pocket of air that the next shell pass may fill once whoever was in it has been carried clear.
     */
    private static boolean clearOfRiders(ServerLevel level, BlockPos pos) {
        return level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new AABB(pos),
                entity -> !entity.isSpectator()).isEmpty();
    }

    private static void placeConeBlock(ServerLevel level, Volcano volcano, int scale, int layer, int point, int points, int inward, boolean guarded) {
        int[] horizontal = VolcanoModel.shellBlockAt(scale, layer, point, points, inward, volcano.x(), volcano.z());
        int y = volcano.ventY() + layer;
        BlockPos pos = new BlockPos(horizontal[0], y, horizontal[1]);
        if (guarded && !clearOfRiders(level, pos)) {
            return;
        }
        if (layer <= 1) {
            // The skirt is made of whatever the volcano is standing on, so a cone in a desert has sand at its
            // foot and one in a forest has dirt, and it stops looking like a model dropped onto the world.
            BlockState ground = groundBlock(level, pos);
            if (ground != null) {
                place(level, pos, ground);
                return;
            }
        }
        place(level, pos, coneMaterial(volcano, pos, layer, scale));
    }

    /** The rock a layer of the cone is made of, decided by position so it never changes when it is rebuilt. */
    private static BlockState coneMaterial(Volcano volcano, BlockPos pos, int layer, int scale) {
        int height = Math.max(1, VolcanoModel.coneLayers(scale));
        double fraction = layer / (double) height;
        long mixed = (pos.asLong() * 0x9E3779B97F4A7C15L) ^ (volcano.id() * 0x2545F4914F6CDD1DL);
        int pick = (int) ((mixed >>> 33) % 100L);
        if (fraction > 0.86D) {
            // The summit is scoria: black, brittle, and hot enough in places to glow.
            return pick < 58 ? Blocks.BLACKSTONE.defaultBlockState()
                    : pick < 86 ? Blocks.BASALT.defaultBlockState()
                    : Blocks.MAGMA_BLOCK.defaultBlockState();
        }
        if (pick < 12) {
            return Blocks.TUFF.defaultBlockState();
        }
        if (pick < 19) {
            return Blocks.MAGMA_BLOCK.defaultBlockState();
        }
        if (pick < 58) {
            return Blocks.BASALT.defaultBlockState();
        }
        return Blocks.ANDESITE.defaultBlockState();
    }

    private static BlockState groundBlock(ServerLevel level, BlockPos pos) {
        for (int depth = 1; depth <= 3; depth++) {
            BlockPos below = pos.below(depth);
            if (!level.isLoaded(below)) {
                return null;
            }
            BlockState state = level.getBlockState(below);
            if (state.isSolidRender(level, below) && !state.is(BlockTags.LEAVES)) {
                return state;
            }
        }
        return null;
    }

    /** Replaces the ground with hot rock around the summit: the cracks that open before an eruption. */
    private static void crackGround(ServerLevel level, Volcano volcano, long gameTime) {
        Random random = new Random(volcano.id() * 31L + gameTime);
        int radius = (int) Math.max(6.0D, volcano.edificeRadius() + 8.0D);
        for (int index = 0; index < 24; index++) {
            int x = volcano.x() + random.nextInt(radius * 2 + 1) - radius;
            int z = volcano.z() + random.nextInt(radius * 2 + 1) - radius;
            BlockPos surface = BlockGrabSafety.safeSurface(level, new BlockPos(x, 0, z), Heightmap.Types.WORLD_SURFACE);
            if (surface == null || surface.getY() < volcano.ventY() - 24) {
                continue;
            }
            BlockState state = random.nextBoolean() ? Blocks.MAGMA_BLOCK.defaultBlockState() : Blocks.TUFF.defaultBlockState();
            place(level, surface, state);
        }
    }

    /**
     * Whether this tick is one where the crater's surface is worked on.
     *
     * <p>The lake is a disc of the crater's own area, so visiting it is the one place in this system where the
     * cost scales with a volcano's size rather than with a budget. A crust forms over minutes anyway, so it is
     * sampled twice a second rather than twenty times, which is what keeps a large caldera idle and cheap.
     */
    private static boolean onshoreCrustCadence(long gameTime) {
        return gameTime % 40L == 0L;
    }

    private static void fillLavaLake(ServerLevel level, Volcano volcano, Budget budget) {
        int radius = (int) Math.max(1.0D, volcano.craterRadius() * 0.7D);
        for (int dx = -radius; dx <= radius && budget.remaining > 0; dx++) {
            for (int dz = -radius; dz <= radius && budget.remaining > 0; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                BlockPos pos = new BlockPos(volcano.x() + dx, (int) Math.round(volcano.summitY()) - 4, volcano.z() + dz);
                BlockState existing = level.isLoaded(pos) ? level.getBlockState(pos) : null;
                if (existing != null && existing.is(Blocks.LAVA)) {
                    continue;
                }
                if (place(level, pos, Blocks.LAVA.defaultBlockState())) {
                    budget.remaining--;
                }
            }
        }
    }

    private static void coolLavaLake(ServerLevel level, Volcano volcano, Budget budget) {
        int radius = (int) Math.max(1.0D, volcano.craterRadius() * 0.7D);
        for (int dx = -radius; dx <= radius && budget.remaining > 0; dx++) {
            for (int dz = -radius; dz <= radius && budget.remaining > 0; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                BlockPos pos = new BlockPos(volcano.x() + dx, (int) Math.round(volcano.summitY()) - 4, volcano.z() + dz);
                if (!level.isLoaded(pos) || !level.getBlockState(pos).is(Blocks.LAVA)) {
                    continue;
                }
                if (place(level, pos, Blocks.OBSIDIAN.defaultBlockState())) {
                    budget.remaining--;
                }
            }
        }
    }

    // ================================================================== lava

    private static void startLavaFlows(ServerLevel level, Volcano volcano) {
        volcano.flows().removeIf(Volcano.LavaFlow::settled);
        int count = Math.max(1, Config.VOLCANO_LAVA_FLOW_COUNT.get());
        double effusionRate = 1.0D + volcano.intensity() * 3.0D;
        Random random = new Random(volcano.id() * 977L + volcano.episodes());
        int rim = (int) Math.round(volcano.craterRadius());
        for (int index = 0; index < count; index++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            // One pair of readings per arm: the head the flow starts at and the direction it leaves in are
            // the same angle.
            double cosAngle = Math.cos(angle);
            double sinAngle = Math.sin(angle);
            int headX = volcano.x() + (int) Math.round(cosAngle * rim);
            int headZ = volcano.z() + (int) Math.round(sinAngle * rim);
            BlockPos head = BlockGrabSafety.safeSurface(level, new BlockPos(headX, 0, headZ), Heightmap.Types.WORLD_SURFACE);
            int headY = head == null ? (int) Math.round(volcano.summitY()) : Math.max(head.getY(), (int) Math.round(volcano.summitY()) - 2);
            volcano.flows().add(new Volcano.LavaFlow(cosAngle, sinAngle, headX, headY, headZ, effusionRate));
        }
    }

    /**
     * Walks each arm of lava down the mountain, leaving a hot channel and a crusted edge behind it.
     *
     * <p>The front follows the ground rather than a straight line - lava goes downhill - and it stops when it
     * either runs out of fall or reaches the length its own effusion rate can sustain, which is why real flows
     * end kilometres short of the flat ground below them.
     */
    private static void tickLavaFlows(ServerLevel level, Volcano volcano, long gameTime, Budget budget) {
        double acceleration = Config.VOLCANO_LAVA_FLOW_ACCELERATION.get();
        for (Volcano.LavaFlow flow : volcano.flows()) {
            if (flow.settled() || budget.remaining <= 0) {
                continue;
            }
            flow.tick();
            double speed = VolcanoModel.lavaFrontBlocksPerTick(flow.effusionRate(), flow.travelled(), flow.budget()) * acceleration;
            if (flow.travelled() >= flow.budget() || speed <= 0.0D) {
                flow.settle();
                continue;
            }
            int interval = Math.max(1, (int) Math.round(1.0D / speed));
            if (flow.ageTicks() % interval != 0) {
                continue;
            }
            BlockPos head = new BlockPos(flow.headX(), flow.headY(), flow.headZ());
            BlockPos next = downhillStep(level, head, flow.headingX(), flow.headingZ());
            if (next == null) {
                if (!spreadSideways(level, flow, budget)) {
                    flow.settle();
                }
                continue;
            }
            // The channel behind the front crusts as it cools, so the flow reads as a moving stream rather
            // than a growing carpet.
            place(level, head, Blocks.BASALT.defaultBlockState());
            place(level, next, Blocks.LAVA.defaultBlockState());
            budget.remaining--;
            if (level.random.nextInt(6) == 0) {
                place(level, next.below(), Blocks.MAGMA_BLOCK.defaultBlockState());
            }
            igniteAround(level, next);
            flow.addTravel(Math.max(1.0D, Math.sqrt(head.distSqr(next))));
            flow.setHead(next.getX(), next.getY(), next.getZ());
            flow.nudgeHeading(next.getX() - head.getX(), next.getZ() - head.getZ());
        }
    }

    /** The lowest passable neighbour of a flow head, biased towards the direction it was already going. */
    private static BlockPos downhillStep(ServerLevel level, BlockPos head, double headingX, double headingZ) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                for (int dy = 0; dy >= -2; dy--) {
                    BlockPos candidate = head.offset(dx, dy, dz);
                    if (!level.isLoaded(candidate) || !canFlowInto(level, candidate)) {
                        continue;
                    }
                    // A drop is preferred to a step, and the flow's own heading breaks ties against it.
                    double alignment = (dx * headingX + dz * headingZ) / Math.hypot(dx, dz);
                    double score = dy * 4.0D - alignment - Math.sqrt(candidate.distSqr(head)) * 0.1D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private static boolean spreadSideways(ServerLevel level, Volcano.LavaFlow flow, Budget budget) {
        BlockPos head = new BlockPos(flow.headX(), flow.headY(), flow.headZ());
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos candidate = head.offset(dx, -1, dz);
                if (!level.isLoaded(candidate) || !canFlowInto(level, candidate)) {
                    continue;
                }
                place(level, candidate, Blocks.LAVA.defaultBlockState());
                budget.remaining--;
                return true;
            }
        }
        return false;
    }

    private static boolean canFlowInto(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.is(Blocks.LAVA)) {
            return true;
        }
        if (!state.getFluidState().isEmpty()) {
            return true;
        }
        // Vegetation is burnt away by the front rather than dammed by it.
        return state.is(BlockTags.REPLACEABLE) || state.is(BlockTags.LEAVES) || state.is(BlockTags.FLOWERS);
    }

    private static void igniteAround(ServerLevel level, BlockPos pos) {
        if (!Config.VOLCANO_LAVA_FLOWS.get() || level.random.nextInt(12) != 0) {
            return;
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos candidate = pos.offset(dx, 0, dz);
                if (!level.isLoaded(candidate)) {
                    continue;
                }
                BlockState state = level.getBlockState(candidate);
                if (state.is(BlockTags.REPLACEABLE) || state.is(BlockTags.LEAVES)) {
                    place(level, candidate, Blocks.FIRE.defaultBlockState());
                    return;
                }
            }
        }
    }

    /** How much lava is at the vent, spread over the flow arms, for the config readout and the tests. */
    public static double effusionRateFor(Volcano volcano) {
        return 1.0D + volcano.intensity() * 3.0D;
    }

    // ================================================================== the collapse

    /**
     * Looses the column's dense base down the flanks.
     *
     * <p>Arms are sent out all round, because a collapsing column does not pick a direction: what the wind does
     * is weight it - the column leans downwind, so its own side of the mountain is where the current runs
     * furthest, and the windward side is where it stops soonest.
     */
    private static void startSurges(ServerLevel level, Volcano volcano) {
        volcano.surges().clear();
        double runout = VolcanoModel.surgeRunoutBlocks(volcano.intensity());
        int arms = 16;
        double windBearing = Math.atan2(volcano.windZ(), volcano.windX());
        for (int index = 0; index < arms; index++) {
            double angle = Math.PI * 2.0D * index / arms;
            double difference = Math.abs(wrapAngle(angle - windBearing));
            double weight = difference < Math.PI / 2.0D ? 1.3D : 0.7D;
            volcano.surges().add(new Volcano.SurgeFront(angle, runout * weight));
        }
    }

    private static double wrapAngle(double angle) {
        while (angle > Math.PI) {
            angle -= Math.PI * 2.0D;
        }
        while (angle < -Math.PI) {
            angle += Math.PI * 2.0D;
        }
        return angle;
    }

    /**
     * Advances every arm of the current and does its damage in the band it just crossed.
     *
     * <p>A density current does not have a front line so much as a moving wall: it knocks over, buries, burns
     * and suffocates everything it passes over and nothing behind it, which is what the band between its old
     * and new distance is.
     */
    private static void tickSurges(ServerLevel level, Volcano volcano, long gameTime, Budget budget) {
        double blocksPerTick = VolcanoModel.surgeBlocksPerTick(volcano.intensity());
        boolean damage = Config.VOLCANO_PYROCLASTIC_DAMAGE.get();
        double hazardRadius = Config.VOLCANO_HAZARD_RADIUS_BLOCKS.get();
        for (Volcano.SurgeFront surge : volcano.surges()) {
            if (surge.spent()) {
                continue;
            }
            surge.tick();
            double previous = surge.distance();
            double next = previous + blocksPerTick * surge.speedFactor();
            surge.setDistance(next);
            if (next >= surge.runout()) {
                surge.spend();
            }
            if (next > hazardRadius) {
                // Past the working radius the current is carried by the client's cloud and the advisory; a
                // volcano is not allowed to dig up the world a kilometre away to prove a point.
                continue;
            }
            for (int sample = 0; sample < 3 && budget.remaining > 0; sample++) {
                double distance = previous + (next - previous) * ((sample + 0.5D) / 3.0D);
                double x = volcano.x() + Math.cos(surge.angleRadians()) * distance;
                double z = volcano.z() + Math.sin(surge.angleRadians()) * distance;
                BlockPos surface = BlockGrabSafety.safeSurface(level, new BlockPos((int) Math.floor(x), 0, (int) Math.floor(z)), Heightmap.Types.WORLD_SURFACE);
                if (surface == null) {
                    continue;
                }
                scaldSurface(level, surface, budget);
                igniteAround(level, surface);
            }
            if (damage) {
                hurtInBand(level, volcano, previous, next, surge.angleRadians(), 10.0D + volcano.intensity() * 3.0D);
            }
        }
    }

    /** What a pyroclastic current leaves behind: the surface buried, burnt and stripped. */
    private static void scaldSurface(ServerLevel level, BlockPos surface, Budget budget) {
        for (int dy = 0; dy <= 3 && budget.remaining > 0; dy++) {
            BlockPos pos = surface.above(dy);
            if (!level.isLoaded(pos)) {
                return;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.is(BlockTags.REPLACEABLE) || state.is(BlockTags.LEAVES) || state.is(BlockTags.FLOWERS)) {
                if (place(level, pos, Blocks.TUFF.defaultBlockState())) {
                    budget.remaining--;
                }
            } else {
                break;
            }
        }
        place(level, surface, Blocks.TUFF.defaultBlockState());
    }

    private static void hurtInBand(ServerLevel level, Volcano volcano, double from, double to, double angle, double damage) {
        double mid = (from + to) / 2.0D;
        double x = volcano.x() + Math.cos(angle) * mid;
        double z = volcano.z() + Math.sin(angle) * mid;
        AABB box = new AABB(x - (to - from) * 0.5D - 8.0D, volcano.ventY() - 24.0D, z - (to - from) * 0.5D - 8.0D,
                x + (to - from) * 0.5D + 8.0D, volcano.ventY() + 120.0D, z + (to - from) * 0.5D + 8.0D);
        List<Entity> entities = level.getEntitiesOfClass(Entity.class, box, entity -> !entity.isSpectator() && entity.isAlive());
        for (Entity entity : entities) {
            double distance = Math.hypot(entity.getX() - volcano.x(), entity.getZ() - volcano.z());
            if (distance < from - 4.0D || distance > to + 4.0D) {
                continue;
            }
            entity.hurt(level.damageSources().onFire(), (float) damage);
            entity.igniteForSeconds(6.0F);
        }
    }

    // ================================================================== the ash

    /**
     * Puts ash in the air where physics says it should be, and on the ground where it can be seen.
     *
     * <p>The deposit thins with distance along the wind, which is the shape of a real isopach, and it is placed
     * only near the vent: a VEI 4 lays ash over a province, and a server that tried to draw all of it as blocks
     * would never finish the first tick. What is out of reach is represented by the cloud, the haze and the
     * advisory instead.
     */
    private static void layAsh(ServerLevel level, Volcano volcano, long gameTime, Budget budget) {
        if (!volcano.phase().ashFall()) {
            return;
        }
        if (gameTime - volcano.lastAshTick() < Config.VOLCANO_ASH_INTERVAL_TICKS.get()) {
            return;
        }
        volcano.setLastAshTick(gameTime);
        double ventThickness = VolcanoModel.ashThicknessMm(volcano.intensity(), 250.0D, volcano.windMph());
        if (volcano.ashMm() < ventThickness) {
            volcano.addAshMm(ventThickness - volcano.ashMm());
        }
        if (!Config.VOLCANO_ASH_DEPOSITS.get()) {
            return;
        }
        Random random = new Random(volcano.id() * 6151L + gameTime);
        int radius = Config.VOLCANO_ASH_RADIUS_BLOCKS.get();
        for (int sample = 0; sample < 18 && budget.remaining > 0; sample++) {
            double offsetX = (random.nextDouble() * 2.0D - 1.0D) * radius;
            double offsetZ = (random.nextDouble() * 2.0D - 1.0D) * radius;
            double along = offsetX * volcano.windX() + offsetZ * volcano.windZ();
            double across = Math.abs(offsetX * -volcano.windZ() + offsetZ * volcano.windX());
            // Ash goes where the wind carries it and spreads across it, so the deposit is measured downwind
            // and thinned off-axis.
            double distance = Math.max(32.0D, along + across * 0.6D);
            double thickness = VolcanoModel.ashThicknessMm(volcano.intensity(), distance, volcano.windMph());
            if (thickness < 1.0D) {
                continue;
            }
            int x = volcano.x() + (int) Math.round(offsetX);
            int z = volcano.z() + (int) Math.round(offsetZ);
            BlockPos surface = BlockGrabSafety.safeSurface(level, new BlockPos(x, 0, z), Heightmap.Types.WORLD_SURFACE);
            if (surface == null || !level.getBlockState(surface.above()).isAir()) {
                continue;
            }
            BlockState deposit = thickness >= 64.0D ? Blocks.TUFF.defaultBlockState() : Blocks.GRAY_CONCRETE_POWDER.defaultBlockState();
            if (place(level, surface.above(), deposit)) {
                budget.remaining--;
            }
        }
    }

    /** Ash in the column rubbing together, which is why an erupting volcano makes its own lightning. */
    private static void ashLightning(ServerLevel level, Volcano volcano, long gameTime) {
        if (!Config.VOLCANO_ASH_LIGHTNING.get() || volcano.intensity() < 2.0D) {
            return;
        }
        int interval = Math.max(60, (int) Math.round(900.0D - volcano.intensity() * 120.0D));
        if (gameTime % interval != 0) {
            return;
        }
        Random random = new Random(volcano.id() * 7919L + gameTime);
        double reach = Math.min(volcano.plumeMetres() * 0.4D, 400.0D);
        double distance = 40.0D + random.nextDouble() * reach;
        int x = volcano.x() + (int) Math.round(volcano.windX() * distance + (random.nextDouble() - 0.5D) * 60.0D);
        int z = volcano.z() + (int) Math.round(volcano.windZ() * distance + (random.nextDouble() - 0.5D) * 60.0D);
        BlockPos surface = BlockGrabSafety.safeSurface(level, new BlockPos(x, 0, z), Heightmap.Types.WORLD_SURFACE);
        if (surface == null) {
            return;
        }
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt == null) {
            return;
        }
        bolt.moveTo(Vec3.atBottomCenterOf(surface));
        level.addFreshEntity(bolt);
    }

    // ================================================================== sound

    /**
     * The eruption heard from outside it.
     *
     * <p>A volcanic blast kilometres away is not a bang but a long, low roll that arrives through the ground as
     * much as the air, which is why this reuses the game's own thunder rather than shipping an explosion sound:
     * thunder is already the correct physics - a distant, enormous, air-carried shock - and no new asset can
     * say it better. Lava pops play at the crater for the same reason, so a player standing near the vent hears
     * the thing they are looking at.
     */
    private static void tickSounds(ServerLevel level, Volcano volcano, long gameTime) {
        if (!Config.VOLCANO_SOUNDS.get()) {
            return;
        }
        int interval = Math.max(60, (int) Math.round(280.0D - volcano.intensity() * 40.0D));
        if (gameTime % interval != 0) {
            return;
        }
        double x = volcano.x() + 0.5D;
        double z = volcano.z() + 0.5D;
        double y = volcano.summitY();
        // The pitch falls with the size of the eruption: a bigger blast is a deeper one.
        float pitch = (float) VolcanoModel.clamp(0.9D - volcano.intensity() * 0.09D, 0.45D, 1.1D);
        float volume = (float) (2.2D + volcano.intensity() * 0.5D);
        level.playSound(null, x, y, z, net.minecraft.sounds.SoundEvents.LIGHTNING_BOLT_THUNDER,
                net.minecraft.sounds.SoundSource.WEATHER, volume, pitch);
        if (volcano.phase().lava() && level.random.nextInt(3) == 0) {
            level.playSound(null, x, y, z, net.minecraft.sounds.SoundEvents.LAVA_POP,
                    net.minecraft.sounds.SoundSource.BLOCKS, 1.4F, 0.7F + level.random.nextFloat() * 0.3F);
        }
    }

    // ================================================================== bombs and shaking

    private static void throwBombs(ServerLevel level, Volcano volcano, long gameTime, Budget budget, boolean magma) {
        if (!Config.VOLCANO_LAVA_BOMBS.get()) {
            return;
        }
        int interval = Math.max(20, (int) Math.round((magma ? 70.0D : 200.0D) - volcano.intensity() * 8.0D));
        if (gameTime % interval != 0) {
            return;
        }
        Random random = new Random(volcano.id() * 104729L + gameTime);
        int count = 1 + random.nextInt(magma ? 3 : 2);
        for (int index = 0; index < count && budget.remaining > 0; index++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double distance = 25.0D + random.nextDouble() * (40.0D + volcano.intensity() * 70.0D);
            int x = volcano.x() + (int) Math.round(Math.cos(angle) * distance);
            int z = volcano.z() + (int) Math.round(Math.sin(angle) * distance);
            BlockPos surface = BlockGrabSafety.safeSurface(level, new BlockPos(x, 0, z), Heightmap.Types.WORLD_SURFACE);
            if (surface == null) {
                continue;
            }
            if (place(level, surface.above(), magma ? Blocks.MAGMA_BLOCK.defaultBlockState() : Blocks.TUFF.defaultBlockState())) {
                budget.remaining--;
            }
            if (magma && Config.VOLCANO_LAVA_FLOWS.get()) {
                igniteAround(level, surface.above());
            }
            double damage = magma ? 9.0D + volcano.intensity() * 2.0D : 5.0D;
            AABB box = new AABB(surface).inflate(3.5D);
            for (Entity entity : level.getEntitiesOfClass(Entity.class, box, entity -> !entity.isSpectator() && entity.isAlive())) {
                entity.hurt(magma ? level.damageSources().onFire() : level.damageSources().generic(), (float) damage);
            }
        }
    }

    /**
     * The small earthquakes that come with an eruption.
     *
     * <p>They are what the camera shake on the client is describing, and they also bring things down: a quake
     * near the cone shakes loose blocks and sends animals running, which is the visible half of the warning the
     * unrest phase is supposed to be.
     */
    private static void tickQuakes(ServerLevel level, Volcano volcano, long gameTime, Budget budget) {
        if (!Config.VOLCANO_EARTHQUAKES.get() || volcano.phase().quakeCadenceTicks() <= 0) {
            return;
        }
        int interval = VolcanoModel.quakeIntervalTicks(volcano.intensity(), volcano.phase().quakeCadenceTicks());
        if (gameTime % interval != 0) {
            return;
        }
        volcano.setLastQuakeTick(gameTime);
        Random random = new Random(volcano.id() * 3571L + gameTime);
        int radius = (int) Math.max(24.0D, volcano.edificeRadius() + 24.0D);
        int attempts = 2 + (int) Math.round(volcano.intensity());
        for (int index = 0; index < attempts && budget.remaining > 0; index++) {
            int x = volcano.x() + random.nextInt(radius * 2 + 1) - radius;
            int z = volcano.z() + random.nextInt(radius * 2 + 1) - radius;
            BlockPos surface = BlockGrabSafety.safeSurface(level, new BlockPos(x, 0, z), Heightmap.Types.WORLD_SURFACE);
            if (surface == null) {
                continue;
            }
            BlockPos above = surface.above();
            if (!level.isLoaded(above)) {
                continue;
            }
            BlockState state = level.getBlockState(above);
            if (!state.isAir() && !state.is(BlockTags.REPLACEABLE)) {
                continue;
            }
            BlockState ground = level.getBlockState(surface);
            var sample = BlockGrabSafety.sample(level, surface, ground);
            if (!sample.valid() || sample.protectedBlock() || !ground.isSolidRender(level, surface)) {
                continue;
            }
            if (BlockGrabSafety.safeDestroyBlock(level, surface, false)) {
                FallingBlockEntity.fall(level, surface, ground);
                budget.remaining--;
            }
        }
        // Animals know before people do.
        AABB box = new AABB(volcano.x() - radius, volcano.ventY() - 32.0D, volcano.z() - radius,
                volcano.x() + radius, volcano.ventY() + 64.0D, volcano.z() + radius);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box, Entity::isAlive)) {
            double dx = mob.getX() - volcano.x();
            double dz = mob.getZ() - volcano.z();
            double distance = Math.max(1.0D, Math.hypot(dx, dz));
            double shake = VolcanoModel.quakeShake(volcano.intensity(), distance);
            mob.setDeltaMovement(mob.getDeltaMovement().add(dx / distance * shake * 0.35D, mob.onGround() ? 0.2D : 0.0D, dz / distance * shake * 0.35D));
        }
    }

    // ================================================================== lahars

    /**
     * Rain on fresh ash, which is the hazard that outlives the eruption.
     *
     * <p>It needs all four conditions to be true: water, enough ash, ash that has not been consolidated by
     * years of weather, and a slope for it to run down. Once it starts the mud follows the same valleys as the
     * ash did, and it is heavy enough to bury a road.
     */
    private static void maybeStartLahars(ServerLevel level, Volcano volcano, long gameTime) {
        if (!Config.VOLCANO_LAHARS.get()) {
            return;
        }
        if (gameTime - volcano.lastLaharTick() < 400L) {
            return;
        }
        double rain = level.getRainLevel(1.0F);
        if (!level.isRaining() && rain <= 0.0F) {
            return;
        }
        double slope = VolcanoModel.slopeDegrees(volcano.edificeHeight(), Math.max(1.0D, volcano.edificeRadius()));
        boolean trigger = VolcanoModel.laharTrigger(rain, volcano.ashMm(), volcano.ashAgeDays(gameTime), slope,
                Config.VOLCANO_LAHAR_MIN_RAIN.get(), Config.VOLCANO_LAHAR_MIN_ASH_MM.get(),
                Config.VOLCANO_LAHAR_MAX_ASH_AGE_DAYS.get(), 4.0D);
        if (!trigger) {
            return;
        }
        volcano.setLastLaharTick(gameTime);
        Random random = new Random(volcano.id() * 1237L + gameTime);
        int arms = 1 + random.nextInt(2);
        int rim = (int) Math.round(volcano.craterRadius() + 2.0D);
        for (int index = 0; index < arms; index++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            // As above: the lahar's head and its heading are one angle, so one cosine and one sine.
            double cosAngle = Math.cos(angle);
            double sinAngle = Math.sin(angle);
            int headX = volcano.x() + (int) Math.round(cosAngle * rim);
            int headZ = volcano.z() + (int) Math.round(sinAngle * rim);
            BlockPos head = BlockGrabSafety.safeSurface(level, new BlockPos(headX, 0, headZ), Heightmap.Types.WORLD_SURFACE);
            int headY = head == null ? (int) Math.round(volcano.summitY()) : head.getY();
            volcano.lahars().add(new Volcano.Lahar(cosAngle, sinAngle, headX, headY, headZ, volcano.ashMm() * 2.0D));
        }
    }

    private static void tickLahars(ServerLevel level, Volcano volcano, long gameTime, Budget budget) {
        if (volcano.lahars().isEmpty()) {
            return;
        }
        double acceleration = Math.max(1.0D, Config.VOLCANO_LAVA_FLOW_ACCELERATION.get());
        double speed = VolcanoModel.laharBlocksPerTick(VolcanoModel.slopeDegrees(volcano.edificeHeight(), Math.max(1.0D, volcano.edificeRadius())), acceleration);
        if (speed <= 0.0D) {
            return;
        }
        int interval = Math.max(1, (int) Math.round(1.0D / speed));
        volcano.lahars().removeIf(lahar -> {
            if (lahar.spent() || lahar.volume() <= 0.0D) {
                return true;
            }
            return !tickLaharStep(level, lahar, gameTime, interval, budget);
        });
    }

    /** One advancing step of a lahar, or false when the flow has run its course and should be dropped. */
    private static boolean tickLaharStep(ServerLevel level, Volcano.Lahar lahar, long gameTime, int interval, Budget budget) {
        lahar.tick();
        if (gameTime % interval != 0) {
            return true;
        }
        BlockPos head = new BlockPos(lahar.headX(), lahar.headY(), lahar.headZ());
        BlockPos next = budget.remaining > 0 ? downhillStep(level, head, lahar.headingX(), lahar.headingZ()) : null;
        if (next == null) {
            // Out of slope or out of allowance: the mud stops where it is, which is where a real one stops.
            lahar.spend();
            return false;
        }
        place(level, next, Blocks.MUD.defaultBlockState());
        place(level, head, Blocks.PACKED_MUD.defaultBlockState());
        budget.remaining--;
        lahar.drain(4.0D);
        lahar.setHead(next.getX(), next.getY(), next.getZ());
        AABB box = new AABB(next).inflate(4.0D);
        for (Entity entity : level.getEntitiesOfClass(Entity.class, box, entity -> !entity.isSpectator() && entity.isAlive())) {
            entity.hurt(level.damageSources().generic(), 4.0F);
        }
        return true;
    }

    // ================================================================== world helpers

    /**
     * Places a block if the world will let it.
     *
     * <p>Every edit the volcano makes goes through the same safety check the rest of the mod uses, so a claim,
     * a spawn-protected area, a modded machine or a block entity is never quietly replaced by hot rock.
     */
    private static boolean place(ServerLevel level, BlockPos pos, BlockState state) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        if (level.getBlockState(pos).is(state.getBlock())) {
            return false;
        }
        var sample = BlockGrabSafety.sample(level, pos, null);
        if (!sample.valid() || sample.protectedBlock()) {
            return false;
        }
        return BlockGrabSafety.safeSetBlock(level, pos, state, 3);
    }

    private static boolean destroy(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos) || level.getBlockState(pos).isAir()) {
            return false;
        }
        var sample = BlockGrabSafety.sample(level, pos, null);
        if (!sample.valid() || sample.protectedBlock()) {
            return false;
        }
        return BlockGrabSafety.safeDestroyBlock(level, pos, false);
    }

    // ================================================================== syncing

    private static void maybeSync(ServerLevel level, Volcano volcano, long gameTime) {
        if (gameTime - volcano.lastSyncTick() < Config.VOLCANO_STATE_SYNC_INTERVAL_TICKS.get()) {
            return;
        }
        volcano.setLastSyncTick(gameTime);
        if (VolcanoStore.get(level).volcanoes().isEmpty()) {
            return;
        }
        sendState(level, null);
    }

    /**
     * Sends the volcanoes within reach of each player.
     *
     * <p>The range is the advisory's rather than the hazard's, because a plume and its haze are what the
     * client draws and both reach much further than the blocks do. Each player gets their own list, so a
     * world with a dozen volcanoes does not send all of them to everyone.
     */
    private static void sendState(ServerLevel level, ServerPlayer only) {
        double reach = Config.VOLCANO_ADVISORY_RANGE_BLOCKS.get() * 1.5D;
        List<Volcano> all = VolcanoStore.get(level).volcanoes();
        for (ServerPlayer player : only != null ? List.of(only) : level.players()) {
            List<VolcanoSyncPayload.VolcanoSnapshot> snapshots = new ArrayList<>();
            for (Volcano candidate : all) {
                double dx = candidate.x() - player.getX();
                double dz = candidate.z() - player.getZ();
                if (dx * dx + dz * dz <= reach * reach) {
                    snapshots.add(snapshot(candidate));
                }
            }
            NetworkSafety.sendToPlayer(player, new VolcanoSyncPayload(snapshots));
        }
    }

    /** Everything a client needs to draw the volcano, and nothing about what it is doing to the world. */
    public static VolcanoSyncPayload.VolcanoSnapshot snapshot(Volcano volcano) {
        return new VolcanoSyncPayload.VolcanoSnapshot(
                volcano.id(),
                volcano.x(),
                volcano.z(),
                volcano.ventY(),
                volcano.scale(),
                volcano.intensity(),
                volcano.phase().ordinal(),
                volcano.phaseProgress(),
                volcano.plumeMetres(),
                volcano.ashMm(),
                volcano.windX(),
                volcano.windZ(),
                volcano.windMph(),
                volcano.furthestSurge(),
                volcano.edificeRadius(),
                volcano.craterRadius(),
                volcano.pressure(),
                volcano.phase().lava(),
                volcano.phase().pyroclastic());
    }

    /** Sends the whole state to one player, for a fresh login or a station that just came online. */
    public static void pushTo(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        sendState(level, player);
    }

    /** A per-tick allowance of world edits, shared by everything a volcano wants to do at once. */
    private static final class Budget {
        private int remaining;

        private Budget(int remaining) {
            this.remaining = Math.max(0, remaining);
        }
    }
}
