package com.imbet.survey;

import com.imbet.weather.BlockGrabSafety;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Reads a patch of ground into a {@link DamageScan}.
 *
 * <p>The reading used to live inside the surveyor item. It lives here so a surveyor villager walking the
 * same ground files the same numbers a player would get standing there with the item - the two cannot
 * disagree about what a stripped trunk or a scour is, because they classify through
 * {@link StormDamage} and count through {@link DamageScan}.
 */
public final class DamageScanner {
    private DamageScanner() {
    }

    /**
     * Walk every column of the disc, read the surface for scouring and the trunk for debarking, and
     * return the finished counters.
     */
    public static DamageScan scan(ServerLevel level, BlockPos center, int radius) {
        DamageScan scan = new DamageScan(radius);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int radiusSqr = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radiusSqr) {
                    continue;
                }
                BlockPos column = center.offset(dx, 0, dz);
                if (!level.isLoaded(column)) {
                    continue;
                }
                BlockPos surface = BlockGrabSafety.safeSurface(level, column, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
                if (surface == null) {
                    continue;
                }
                BlockPos ground = surface.below();
                if (level.isLoaded(ground)) {
                    BlockState state = level.getBlockState(ground);
                    scan.recordGround(StormDamage.isScouredSurface(state), ground.getX(), ground.getZ());
                }
                for (int y = -4; y <= 14; y++) {
                    cursor.set(surface.getX(), surface.getY() + y, surface.getZ());
                    if (!level.isLoaded(cursor)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(cursor);
                    scan.recordTree(StormDamage.isDebarkedLog(state), StormDamage.isStormLeaves(state),
                            cursor.getX(), cursor.getZ());
                }
            }
        }
        scan.finish();
        return scan;
    }
}
