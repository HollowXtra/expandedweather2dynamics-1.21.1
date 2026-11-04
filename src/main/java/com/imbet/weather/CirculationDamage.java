package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import weather2.weathersystem.storm.StormObject;

public final class CirculationDamage {
    /**
     * Stratified sweep phase for the funnel's ground scar: advanced once per sampled column so each tick
     * works a different slice of the footprint. Server-side damage runs on the server thread, so a single
     * counter for all storms is enough - it only has to not repeat itself.
     */
    private static int funnelScarPhase;

    private CirculationDamage() {
    }

    public static void tick(ServerLevel level, StormObject parent, DynamicStormState state) {
        if (!Config.damageEnabled() || parent == null || parent.pos == null) {
            return;
        }
        if (Config.MULTIVORTEX_ENABLED.get()) {
            long gameTime = level.getGameTime();
            for (DynamicStormState.SubVortex vortex : state.subVortices()) {
                // A descending breakdown bubble only damages once it reaches the surface circulation.
                if (!vortex.isGroundContact(gameTime)) {
                    continue;
                }
                Vec3 center = TornadoWindField.subVortexGroundCenter(level, parent, vortex, parent.pos.y);
                applySubVortexEntityWind(level, parent, vortex, center);
                applySubVortexBlockDamage(level, parent, vortex, center);
            }
        }
        // The main swath: Weather2's grab loop only reaches ~100 blocks from the funnel, so a
        // mile-wide wedge's outer footprint was never touched at all - the middle got shredded
        // while the edges of the same town stood untouched. This sweep works the WHOLE damage
        // path, sharing the storm's (width-scaled) block budget so the total per tick stays sane.
        if (state.damagePathWidth() > 64.0F) {
            applyMainSwathBlockDamage(level, parent, state);
        }
        // The funnel's own scar, worked at whatever width the funnel has right now.
        applyFunnelScarSweep(level, parent, state);
        if (Config.SATELLITE_TORNADOES_REAL_CIRCULATION.get()) {
            for (DynamicStormState.TornadoCirculationState circulation : state.satelliteTornadoes()) {
                if (!circulation.touchedDown() || circulation.absorbed()) {
                    continue;
                }
                Vec3 center = DynamicStormController.satelliteCenter(parent, circulation, parent.pos.y);
                applyEntityWind(level, parent, circulation, center);
                applyBlockDamage(level, parent, circulation, center);
            }
        }
    }

    /**
     * The main swath sweep for wide tornadoes. Samples the whole damage-path disk, evaluates the
     * swath wind profile (the same one the pickup model uses), and applies the same rules as the
     * sub-vortex path: vegetation stripped, soil scour-tiered, fragile blocks destroyed. Only runs
     * past 64 blocks of path width, so normal tornadoes stay entirely on Weather2's grab loop and
     * nothing doubles up near the funnel - out there it is the only thing working the ground.
     */
    private static void applyMainSwathBlockDamage(ServerLevel level, StormObject parent, DynamicStormState state) {
        if (level.getGameTime() % 2L != 0L) {
            return;
        }
        double swathRadius = Math.max(32.0D, state.damagePathWidth() * 0.5D);
        // Attempts grow with the swath's AREA (sqrt of width) so a 3-mile monster works its whole
        // footprint without the per-tick cost exploding linearly with diameter.
        int attempts = Mth.clamp((int) Math.round(8.0D * Math.sqrt(swathRadius / 32.0D)), 8, 42);
        Vec3 center = parent.pos;
        int cap = Math.max(4, Config.SATELLITE_TORNADOES_DAMAGE_BLOCKS_PER_TICK.get() * 2);
        int changed = 0;
        for (int i = 0; i < attempts && changed < cap; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double distance = Math.sqrt(level.random.nextDouble()) * swathRadius;
            BlockPos base = BlockPos.containing(center.x + Math.cos(angle) * distance, center.y, center.z + Math.sin(angle) * distance);
            if (!level.isLoaded(base)) {
                continue;
            }
            BlockPos surface = BlockGrabSafety.safeSurface(level, base, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
            if (surface == null) {
                continue;
            }
            BlockPos pos = surface.below().offset(0, level.random.nextInt(3) - 1, 0);
            if (!level.isLoaded(pos) || !state.tryConsumeBlockDamage(level.getGameTime(), parent)) {
                continue;
            }
            BlockGrabSafety.BlockSample block = BlockGrabSafety.sample(level, pos, null);
            if (!BlockGrabSafety.canEdit(block)) {
                continue;
            }
            BlockState blockState = block.state();
            Vec3 blockCenter = Vec3.atCenterOf(pos);
            double mph = TornadoDamageModel.damageWindMph(parent, TornadoWindField.sample(parent, level, blockCenter));
            if (mph < 85.0D) {
                continue;
            }
            if (blockState.is(BlockTags.CROPS) || blockState.is(BlockTags.FLOWERS) || blockState.is(BlockTags.LEAVES) || blockState.is(BlockTags.REPLACEABLE_BY_TREES)) {
                if (BlockGrabSafety.safeSetBlock(level, pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)) {
                    changed++;
                }
                continue;
            }
            if (blockState.is(Blocks.GRASS_BLOCK) || blockState.is(Blocks.DIRT) || blockState.is(Blocks.COARSE_DIRT) || blockState.is(Blocks.FARMLAND) || blockState.is(Blocks.PODZOL) || blockState.is(Blocks.MYCELIUM)) {
                BlockState replacement = scourReplacement(level, pos, mph);
                if (replacement != null && !blockState.is(replacement.getBlock()) && BlockGrabSafety.safeSetBlock(level, pos, replacement, Block.UPDATE_ALL)) {
                    changed++;
                }
                continue;
            }
            if (mph < 135.0D) {
                continue;
            }
            float hardness = block.destroySpeed();
            double hardnessLimit = Math.max(0.75D, (mph - BlockGrabSafety.resistanceMph(block) + 24.0D) / 34.0D);
            if (hardness >= 0.0F && hardness <= hardnessLimit && BlockGrabSafety.canImpactDamageByWind(block, mph)) {
                if (BlockGrabSafety.safeDestroyBlock(level, pos, false)) {
                    changed++;
                }
            }
        }
    }

    private static void applySubVortexEntityWind(ServerLevel level, StormObject parent, DynamicStormState.SubVortex vortex, Vec3 center) {
        // Peripheral vortices sweep a much wider, weaker swath; inner-core spots a tight vicious one.
        double radius = Math.max(10.0D, DynamicStormController.baseRadiusForWind(parent)
                * Math.max(0.24D, vortex.radiusFraction()) * 1.35D * vortex.type().damageScale());
        AABB box = new AABB(center.x - radius, center.y - 10.0D, center.z - radius, center.x + radius, center.y + 72.0D, center.z + radius);
        for (Entity entity : level.getEntities((Entity) null, box, entity -> entity.isAlive())) {
            if (entity instanceof Player player && player.isCreative()) {
                continue;
            }
            TornadoWindField.WindSample sample = TornadoWindField.sample(parent, level, entity.position());
            if (sample.strongestLocalMph() < 55.0D) {
                continue;
            }
            entity.setDeltaMovement(entity.getDeltaMovement().add(TornadoWindField.mphToEntityDelta(sample.mphVector())));
            entity.hurtMarked = true;
        }
    }

    /**
     * Ticks the funnel's own ground scar forward. Weather2's grab loop only ever works a few dozen blocks
     * around the base, and the wide swath above spreads its handful of samples across the whole damage
     * path - up to three miles of it - so the soil actually under the funnel was barely touched, and the
     * scar stayed the width it had when the tornado was small. This pass works the funnel's footprint at
     * the width it currently has, so the scar grows and recedes with the funnel itself.
     *
     * <p>The disk is walked in strata rather than re-rolled: each tick advances a phase and samples that
     * slice, so successive ticks cover new ground instead of re-sampling the same random spots (which is
     * what made the outer swath's coverage collapse as it grew).
     */
    private static void applyFunnelScarSweep(ServerLevel level, StormObject parent, DynamicStormState state) {
        // A hurricane's suppressed funnel has no ground circulation to scar, and open water has no soil to
        // scour - the mph gate below would reject most of that anyway, but the heightmap lookups are not free.
        if (!Config.DAMAGE_GROUND_SCOURING.get() || DynamicStormController.shouldSuppressHelperFunnel(parent)) {
            return;
        }
        double scarRadius = Math.max(3.0D, state.visibleFunnelWidth() * 0.5D);
        // Columns per tick grow with the funnel's width, and the rate is chosen against the tornado's own
        // travel: a footprint fills in about 1.6 x radius ticks at one block per tick of motion, so 1.2 x
        // radius keeps the ground the funnel is standing on mostly worked before it moves on. Capped so a
        // three-mile wedge cannot stall the server - there the scar comes out wide but patchy, which is
        // also what the widest real tornadoes leave behind.
        int columns = Mth.clamp((int) Math.round(scarRadius * 1.2D), 3, 96);
        int cap = Math.max(6, columns);
        int changed = 0;
        int attempts = columns * 2;
        for (int i = 0; i < attempts && changed < cap; i++) {
            funnelScarPhase++;
            double angle = funnelScarPhase * 0.6180339887D * Math.PI * 2.0D;
            double radial = Math.sqrt(((funnelScarPhase * 0.3819660113D) % 1.0D)) * scarRadius;
            BlockPos base = BlockPos.containing(parent.pos.x + Math.cos(angle) * radial, parent.pos.y,
                    parent.pos.z + Math.sin(angle) * radial);
            if (!level.isLoaded(base)) {
                continue;
            }
            BlockPos surface = BlockGrabSafety.safeSurface(level, base, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
            if (surface == null) {
                continue;
            }
            BlockPos pos = surface.below().offset(0, level.random.nextInt(3) - 1, 0);
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockGrabSafety.BlockSample block = BlockGrabSafety.sample(level, pos, null);
            if (!BlockGrabSafety.canEdit(block)) {
                continue;
            }
            BlockState blockState = block.state();
            Vec3 blockCenter = Vec3.atCenterOf(pos);
            double mph = TornadoDamageModel.damageWindMph(parent, TornadoWindField.sample(parent, level, blockCenter));
            if (mph < 85.0D) {
                continue;
            }
            if (blockState.is(BlockTags.CROPS) || blockState.is(BlockTags.FLOWERS) || blockState.is(BlockTags.LEAVES)
                    || blockState.is(BlockTags.REPLACEABLE_BY_TREES)) {
                if (BlockGrabSafety.safeSetBlock(level, pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)) {
                    changed++;
                }
                continue;
            }
            if (!isScourableSoil(blockState)) {
                continue;
            }
            BlockState replacement = scourReplacement(level, pos, mph);
            if (replacement != null && !blockState.is(replacement.getBlock())
                    && BlockGrabSafety.safeSetBlock(level, pos, replacement, Block.UPDATE_ALL)) {
                changed++;
            }
        }
    }

    private static boolean isScourableSoil(BlockState blockState) {
        return blockState.is(Blocks.GRASS_BLOCK) || blockState.is(Blocks.DIRT) || blockState.is(Blocks.COARSE_DIRT)
                || blockState.is(Blocks.FARMLAND) || blockState.is(Blocks.PODZOL) || blockState.is(Blocks.MYCELIUM)
                || blockState.is(Blocks.ROOTED_DIRT);
    }

    private static void applySubVortexBlockDamage(ServerLevel level, StormObject parent, DynamicStormState.SubVortex vortex, Vec3 center) {
        int cap = Math.max(4, Config.SATELLITE_TORNADOES_DAMAGE_BLOCKS_PER_TICK.get());
        if (cap <= 0 || level.getGameTime() % 2L != 0L) {
            return;
        }
        double radius = Mth.clamp(DynamicStormController.baseRadiusForWind(parent)
                * Math.max(0.24D, vortex.radiusFraction()) * 0.72D * vortex.type().damageScale(), 3.0D, 32.0D);
        double vortexMaxWind = DynamicStormController.subVortexWindMph(parent, vortex);
        int changed = 0;
        int attempts = Math.max(8, cap * 4);
        for (int i = 0; i < attempts && changed < cap; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double distance = Math.sqrt(level.random.nextDouble()) * radius * 1.65D;
            BlockPos base = BlockPos.containing(center.x + Math.cos(angle) * distance, center.y, center.z + Math.sin(angle) * distance);
            if (!level.isLoaded(base)) {
                continue;
            }
            BlockPos surface = BlockGrabSafety.safeSurface(level, base, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
            if (surface == null) {
                continue;
            }
            BlockPos pos = surface.below().offset(0, level.random.nextInt(3) - 1, 0);
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockGrabSafety.BlockSample block = BlockGrabSafety.sample(level, pos, null);
            if (!canEdit(block)) {
                continue;
            }
            BlockState blockState = block.state();
            Vec3 blockCenter = Vec3.atCenterOf(pos);
            double falloff = Mth.clamp(1.0D - blockCenter.distanceTo(center) / Math.max(1.0D, radius * 2.2D), 0.0D, 1.0D);
            TornadoWindField.WindSample sample = TornadoWindField.sample(parent, level, blockCenter);
            double mph = Math.max(sample.strongestLocalMph(), vortexMaxWind * falloff);
            if (mph < 95.0D) {
                continue;
            }
            if (blockState.is(BlockTags.CROPS) || blockState.is(BlockTags.FLOWERS) || blockState.is(BlockTags.LEAVES) || blockState.is(BlockTags.REPLACEABLE_BY_TREES)) {
                if (BlockGrabSafety.safeSetBlock(level, pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)) {
                    changed++;
                }
                continue;
            }
            if (blockState.is(Blocks.GRASS_BLOCK) || blockState.is(Blocks.DIRT) || blockState.is(Blocks.COARSE_DIRT) || blockState.is(Blocks.FARMLAND) || blockState.is(Blocks.PODZOL) || blockState.is(Blocks.MYCELIUM)) {
                BlockState replacement = scourReplacement(level, pos, mph);
                if (replacement == null) {
                    continue;
                }
                if (!blockState.is(replacement.getBlock()) && BlockGrabSafety.safeSetBlock(level, pos, replacement, Block.UPDATE_ALL)) {
                    changed++;
                }
                continue;
            }
            if (mph < 135.0D) {
                continue;
            }
            float hardness = block.destroySpeed();
            double hardnessLimit = Math.max(0.75D, (mph - BlockGrabSafety.resistanceMph(block) + 24.0D) / 34.0D);
            if (hardness >= 0.0F && hardness <= hardnessLimit && BlockGrabSafety.canImpactDamageByWind(block, mph)) {
                if (BlockGrabSafety.safeDestroyBlock(level, pos, false)) {
                    changed++;
                }
            }
        }
    }

    private static void applyEntityWind(ServerLevel level, StormObject parent, DynamicStormState.TornadoCirculationState circulation, Vec3 center) {
        double radius = Math.max(8.0D, DynamicStormController.satelliteRadius(parent, circulation) * 4.2D);
        AABB box = new AABB(center.x - radius, center.y - 24.0D, center.z - radius, center.x + radius, center.y + 80.0D, center.z + radius);
        for (Entity entity : level.getEntities((Entity) null, box, entity -> entity.isAlive())) {
            if (entity instanceof Player player && player.isCreative()) {
                continue;
            }
            TornadoWindField.WindSample sample = TornadoWindField.sample(parent, level, entity.position());
            if (sample.strongestLocalMph() < 45.0D) {
                continue;
            }
            entity.setDeltaMovement(entity.getDeltaMovement().add(TornadoWindField.mphToEntityDelta(sample.mphVector())));
            entity.hurtMarked = true;
        }
    }

    private static void applyBlockDamage(ServerLevel level, StormObject parent, DynamicStormState.TornadoCirculationState circulation, Vec3 center) {
        int cap = Config.SATELLITE_TORNADOES_DAMAGE_BLOCKS_PER_TICK.get();
        if (cap <= 0 || level.getGameTime() % 2L != 0L) {
            return;
        }
        double radius = Math.max(4.0D, DynamicStormController.satelliteRadius(parent, circulation));
        int attempts = Math.max(4, cap * 3);
        for (int i = 0; i < attempts; i++) {
            if (!circulation.tryConsumeDamage(level.getGameTime(), cap)) {
                return;
            }
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double distance = Math.sqrt(level.random.nextDouble()) * radius * 1.45D;
            BlockPos base = BlockPos.containing(center.x + Math.cos(angle) * distance, center.y, center.z + Math.sin(angle) * distance);
            if (!level.isLoaded(base)) {
                continue;
            }
            BlockPos surface = BlockGrabSafety.safeSurface(level, base, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
            if (surface == null) {
                continue;
            }
            surface = surface.below();
            BlockPos pos = surface.offset(0, level.random.nextInt(4) - 1, 0);
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockGrabSafety.BlockSample block = BlockGrabSafety.sample(level, pos, null);
            if (!canEdit(block)) {
                continue;
            }
            BlockState state = block.state();
            TornadoWindField.WindSample sample = TornadoWindField.sample(parent, level, Vec3.atCenterOf(pos));
            double mph = Math.max(sample.strongestLocalMph(), DynamicStormController.satelliteWindMph(parent, circulation) * (1.0D - Math.min(1.0D, Vec3.atCenterOf(pos).distanceTo(center) / Math.max(1.0D, radius * 2.0D))));
            if (mph < 85.0D) {
                continue;
            }
            if (state.is(BlockTags.CROPS) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.LEAVES) || state.is(BlockTags.REPLACEABLE_BY_TREES)) {
                BlockGrabSafety.safeSetBlock(level, pos, Blocks.AIR.defaultBlockState(), 3);
                continue;
            }
            if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.FARMLAND) || state.is(Blocks.PODZOL)) {
                BlockState replacement = scourReplacement(level, pos, mph);
                if (replacement != null) {
                    BlockGrabSafety.safeSetBlock(level, pos, replacement, 3);
                }
                continue;
            }
            float hardness = block.destroySpeed();
            double hardnessLimit = Math.max(0.55D, (mph - BlockGrabSafety.resistanceMph(block) + 18.0D) / 42.0D);
            if (hardness >= 0.0F && hardness <= hardnessLimit && BlockGrabSafety.canImpactDamageByWind(block, mph)) {
                BlockGrabSafety.safeDestroyBlock(level, pos, false);
            }
        }
    }

    /**
     * Wind-tiered soil scouring, matching the main swath: light winds flatten to
     * a path, violent circulations expose coarse earth or scour the topsoil off.
     */
    private static BlockState scourReplacement(ServerLevel level, BlockPos pos, double mph) {
        if (Config.DAMAGE_SCOURING_EXCAVATION.get() && mph >= 185.0D) {
            BlockState below = level.getBlockState(pos.below());
            if (!below.isAir() && below.getFluidState().isEmpty() && !below.is(Blocks.BEDROCK)) {
                return Blocks.AIR.defaultBlockState(); // topsoil scoured clean off
            }
        }
        if (mph >= 150.0D) {
            return level.random.nextFloat() < 0.30F ? Blocks.ROOTED_DIRT.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState();
        }
        return Blocks.DIRT_PATH.defaultBlockState();
    }

    private static boolean canEdit(BlockGrabSafety.BlockSample sample) {
        return BlockGrabSafety.canEdit(sample);
    }
}
