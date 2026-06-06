package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import weather2.weathersystem.storm.StormObject;

public final class FlyingBlockImpactDamage {
    private FlyingBlockImpactDamage() {
    }

    public static void tick(ServerLevel level, StormObject storm, DynamicStormState state) {
        if (!Config.DAMAGE_ENABLED.get()
                || !Config.DAMAGE_FLYING_BLOCK_IMPACT_DAMAGE.get()
                || storm == null
                || storm.pos == null
                || !DynamicStormController.isDynamicVortex(storm)
                || state.isAbsorbedOrDecaying()
                || level.getGameTime() % 4L != 0L) {
            return;
        }
        double maxWind = TornadoWindField.maxWindMph(storm);
        if (maxWind < Config.DAMAGE_WOOD_PICKUP_MPH.get()) {
            return;
        }
        int cap = Math.max(0, Math.min(Config.DAMAGE_MAX_BLOCKS_PER_STORM_PER_TICK.get() / 3, 8));
        if (cap <= 0) {
            return;
        }
        damageImpactsNear(level, storm, storm.pos, Math.max(12.0D, DynamicStormController.baseRadiusForWind(storm) * 1.15D), cap);
        for (DynamicStormState.TornadoCirculationState satellite : state.satelliteTornadoes()) {
            if (satellite.absorbed() || !satellite.touchedDown()) {
                continue;
            }
            Vec3 center = DynamicStormController.satelliteCenter(storm, satellite, storm.pos.y);
            damageImpactsNear(level, storm, center, Math.max(8.0D, DynamicStormController.satelliteRadius(storm, satellite) * 1.2D), Math.max(1, cap / 2));
        }
    }

    private static void damageImpactsNear(ServerLevel level, StormObject storm, Vec3 center, double radius, int cap) {
        int changed = 0;
        int attempts = cap * 4;
        for (int index = 0; index < attempts && changed < cap; index++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double distance = Math.sqrt(level.random.nextDouble()) * radius * 1.55D;
            BlockPos column = BlockPos.containing(center.x + Math.cos(angle) * distance, center.y, center.z + Math.sin(angle) * distance);
            if (!level.isLoaded(column)) {
                continue;
            }
            BlockPos surface = BlockGrabSafety.safeSurface(level, column, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
            if (surface == null) {
                continue;
            }
            BlockPos pos = surface.offset(0, level.random.nextInt(5) - 2, 0);
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockGrabSafety.BlockSample block = BlockGrabSafety.sample(level, pos, null);
            TornadoWindField.WindSample sample = TornadoWindField.sample(storm, level, Vec3.atCenterOf(pos));
            double localMph = sample.strongestLocalMph();
            if (!BlockGrabSafety.canImpactDamageByWind(block, localMph)) {
                continue;
            }
            double hardnessLimit = Math.max(0.8D, (localMph - Config.DAMAGE_EF0_PICKUP_MPH.get()) / 26.0D);
            hardnessLimit *= Mth.clamp(TornadoWindField.maxWindMph(storm) / 180.0D, 0.65D, 1.65D);
            if (block.destroySpeed() > hardnessLimit) {
                continue;
            }
            if (BlockGrabSafety.safeDestroyBlock(level, pos, false)) {
                changed++;
            }
        }
    }
}
