package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;
import java.util.Map;
import java.util.WeakHashMap;

public final class TornadoShelterGoal extends Goal {
    private static final Map<Villager, Boolean> ATTACHED = new WeakHashMap<>();
    private final Villager villager;
    private final int scanInterval;
    private BlockPos shelter;
    private long nextScanTick;

    private TornadoShelterGoal(Villager villager) {
        this.villager = villager;
        this.scanInterval = Math.max(5, Config.AI_VILLAGER_SHELTER_SCAN_INTERVAL_TICKS.get());
        setFlags(EnumSet.of(Flag.MOVE));
    }

    public static void attach(Villager villager) {
        synchronized (ATTACHED) {
            if (ATTACHED.containsKey(villager)) {
                return;
            }
            ATTACHED.put(villager, Boolean.TRUE);
        }
        villager.goalSelector.addGoal(0, new TornadoShelterGoal(villager));
    }

    @Override
    public boolean canUse() {
        if (!Config.AI_VILLAGER_TORNADO_SHELTER.get() || !(villager.level() instanceof ServerLevel level) || villager.isSleeping()) {
            return false;
        }
        long gameTime = level.getGameTime();
        if (gameTime < nextScanTick) {
            return false;
        }
        nextScanTick = gameTime + scanInterval + villager.getRandom().nextInt(Math.max(1, scanInterval));
        if (TornadoThreatService.nearestThreat(level, villager.position(), TornadoThreatService.configuredRadius()).isEmpty()) {
            shelter = null;
            return false;
        }
        shelter = findShelter(level, villager.blockPosition());
        return shelter != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!(villager.level() instanceof ServerLevel level) || shelter == null) {
            return false;
        }
        return TornadoThreatService.nearestThreat(level, villager.position(), TornadoThreatService.configuredRadius()).isPresent()
                && !isSheltered(level, villager.blockPosition());
    }

    @Override
    public void start() {
        if (shelter != null) {
            villager.getNavigation().moveTo(shelter.getX() + 0.5D, shelter.getY(), shelter.getZ() + 0.5D, 1.25D);
        }
    }

    @Override
    public void tick() {
        if (shelter != null && villager.getNavigation().isDone()) {
            villager.getNavigation().moveTo(shelter.getX() + 0.5D, shelter.getY(), shelter.getZ() + 0.5D, 1.18D);
        }
    }

    private static BlockPos findShelter(ServerLevel level, BlockPos origin) {
        BlockPos best = null;
        int bestScore = Integer.MAX_VALUE;
        int radius = 26;

        for (BlockPos cursor : BlockPos.betweenClosed(origin.offset(-radius, -4, -radius), origin.offset(radius, 5, radius))) {
            BlockState state = level.getBlockState(cursor);
            boolean preferred = state.getBlock() instanceof BedBlock || state.getBlock() instanceof BellBlock || state.getBlock() instanceof DoorBlock || state.is(BlockTags.DOORS);
            if (!preferred) {
                continue;
            }
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos candidate = cursor.relative(direction).immutable();
                if (!isWalkableShelter(level, candidate)) {
                    candidate = cursor.above().immutable();
                }
                if (!isWalkableShelter(level, candidate)) {
                    continue;
                }
                int score = candidate.distManhattan(origin) - 18;
                if (score < bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
        }

        for (BlockPos cursor : BlockPos.betweenClosed(origin.offset(-radius, -3, -radius), origin.offset(radius, 4, radius))) {
            if ((cursor.getX() - origin.getX()) % 3 != 0 || (cursor.getZ() - origin.getZ()) % 3 != 0) {
                continue;
            }
            BlockPos candidate = cursor.immutable();
            if (!isWalkableShelter(level, candidate)) {
                continue;
            }
            int score = candidate.distManhattan(origin);
            if (score < bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean isSheltered(ServerLevel level, BlockPos pos) {
        return !level.canSeeSky(pos.above()) && hasRoof(level, pos);
    }

    private static boolean isWalkableShelter(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos) || level.canSeeSky(pos.above()) || !hasRoof(level, pos)) {
            return false;
        }
        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        BlockState floor = level.getBlockState(pos.below());
        return feet.getCollisionShape(level, pos).isEmpty()
                && head.getCollisionShape(level, pos.above()).isEmpty()
                && floor.isFaceSturdy(level, pos.below(), Direction.UP);
    }

    private static boolean hasRoof(ServerLevel level, BlockPos pos) {
        for (int dy = 2; dy <= 9; dy++) {
            BlockPos roof = pos.above(dy);
            if (!level.isLoaded(roof)) {
                return false;
            }
            if (!level.getBlockState(roof).getCollisionShape(level, roof).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
