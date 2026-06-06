package com.imbet.weather;

import com.imbet.Config;
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
            if (!BlockGrabSafety.canEdit(block)) {
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
                BlockGrabSafety.safeSetBlock(level, pos, Blocks.DIRT_PATH.defaultBlockState(), 3);
                continue;
            }
            float hardness = block.destroySpeed();
            double hardnessLimit = Math.max(0.8D, (mph - 80.0D) / 55.0D);
            if (hardness >= 0.0F && hardness <= hardnessLimit && BlockGrabSafety.canImpactDamageByWind(block, mph)) {
                BlockGrabSafety.safeDestroyBlock(level, pos, false);
            }
        }
    }
}
