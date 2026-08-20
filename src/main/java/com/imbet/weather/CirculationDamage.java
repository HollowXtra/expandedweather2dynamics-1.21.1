package com.imbet.weather;

import com.imbet.Config;
import com.imbet.weather.vortex.MutableWindSample;
import com.imbet.weather.vortex.VortexEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import weather2.weathersystem.storm.StormObject;

public final class CirculationDamage {
    private CirculationDamage() {
    }

    public static void tick(ServerLevel level, StormObject parent, DynamicStormState state) {
        if (!Config.DAMAGE_ENABLED.get() || !Config.SATELLITE_TORNADOES_REAL_CIRCULATION.get() || parent == null || parent.pos == null) {
            return;
        }
        for (DynamicStormState.TornadoCirculationState circulation : state.satelliteTornadoes()) {
            if (!circulation.touchedDown() || circulation.absorbed()) {
                continue;
            }
            Vec3 center = DynamicStormController.satelliteCenter(parent, circulation, parent.pos.y);
            applyEntityWind(level, parent, circulation, center);
            applyBlockDamage(level, parent, circulation, center);
        }
    }

    private static void applyEntityWind(ServerLevel level, StormObject parent, DynamicStormState.TornadoCirculationState circulation, Vec3 center) {
        double radius = Math.max(8.0D, DynamicStormController.satelliteRadius(parent, circulation) * 4.2D);
        AABB box = new AABB(center.x - radius, center.y - 24.0D, center.z - radius, center.x + radius, center.y + 80.0D, center.z + radius);
        for (Entity entity : level.getEntities((Entity) null, box, entity -> entity.isAlive())) {
            if (entity instanceof Player player && player.isCreative()) {
                continue;
            }
            MutableWindSample sample = new MutableWindSample();
            VortexEngine.sampleWeather2StormWind(parent, level, entity.getX(), entity.getY(), entity.getZ(), sample);
            if (sample.speedMph < 45.0D) {
                continue;
            }
            double scale = Config.WINDFIELD_ENTITY_PUSH_SCALE.get();
            entity.setDeltaMovement(entity.getDeltaMovement().add(sample.mphX * scale, sample.mphY * scale, sample.mphZ * scale));
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
            BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, base).below();
            BlockPos pos = surface.offset(0, level.random.nextInt(4) - 1, 0);
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (!canEdit(level, pos, state)) {
                continue;
            }
            MutableWindSample sample = new MutableWindSample();
            VortexEngine.sampleWeather2StormWind(parent, level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, sample);
            double mph = Math.max(sample.speedMph, DynamicStormController.satelliteWindMph(parent, circulation) * (1.0D - Math.min(1.0D, Vec3.atCenterOf(pos).distanceTo(center) / Math.max(1.0D, radius * 2.0D))));
            if (mph < 85.0D) {
                continue;
            }
            if (state.is(BlockTags.CROPS) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.LEAVES) || state.is(BlockTags.REPLACEABLE_BY_TREES)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                continue;
            }
            if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.FARMLAND) || state.is(Blocks.PODZOL)) {
                level.setBlock(pos, Blocks.DIRT_PATH.defaultBlockState(), 3);
                continue;
            }
            float hardness = state.getDestroySpeed(level, pos);
            double hardnessLimit = Math.max(0.8D, (mph - 80.0D) / 55.0D);
            if (hardness >= 0.0F && hardness <= hardnessLimit) {
                level.destroyBlock(pos, false);
            }
        }
    }

    private static boolean canEdit(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        if (state.is(Blocks.BEDROCK) || state.is(Blocks.END_PORTAL) || state.is(Blocks.END_PORTAL_FRAME) || state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.COMMAND_BLOCK) || state.is(Blocks.CHAIN_COMMAND_BLOCK) || state.is(Blocks.REPEATING_COMMAND_BLOCK)) {
            return false;
        }
        return !Config.DAMAGE_PRESERVE_INVENTORY_BLOCK_ENTITIES.get() || level.getBlockEntity(pos) == null;
    }
}
