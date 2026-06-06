package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;

public final class StructureCrusher {
    private static final Map<Level, TickBudget> BUDGETS = new WeakHashMap<>();

    private StructureCrusher() {
    }

    public static CrushResult crush(ServerLevel level, BlockPos center, Vec3 velocity, double massFactor, boolean violent) {
        if (level == null || center == null || velocity == null || !Config.STRUCTURE_CRUSHER_ENABLED.get()) {
            return CrushResult.NONE;
        }
        double kineticEnergy = 0.5D * Math.max(0.1D, massFactor) * velocity.lengthSqr();
        if (kineticEnergy < Config.STRUCTURE_CRUSHER_MIN_KINETIC_ENERGY.get()) {
            return CrushResult.NONE;
        }
        TickBudget budget = budget(level);
        if (budget.remaining <= 0) {
            return CrushResult.NONE;
        }
        int radius = (int) Math.max(1, Math.min(violent ? 5 : 3, Math.floor(1.0D + Math.sqrt(kineticEnergy) * 0.85D)));
        int destroyed = 0;
        double hardnessCost = 0.0D;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int y = -1; y <= 1 && budget.remaining > 0; y++) {
            for (int x = -radius; x <= radius && budget.remaining > 0; x++) {
                for (int z = -radius; z <= radius && budget.remaining > 0; z++) {
                    if (x * x + y * y + z * z > radius * radius) {
                        continue;
                    }
                    mutable.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    BlockGrabSafety.BlockSample sample = BlockGrabSafety.sample(level, mutable.immutable(), null);
                    if (!BlockGrabSafety.canImpactDamageByWind(sample, violent ? 166.0D : 111.0D)) {
                        continue;
                    }
                    BlockState state = sample.state();
                    Mutation mutation = mutationFor(state, violent);
                    if (mutation == Mutation.NONE) {
                        continue;
                    }
                    if (applyMutation(level, sample.pos(), state, mutation)) {
                        budget.remaining--;
                        destroyed++;
                        hardnessCost += Math.max(0.15D, sample.destroySpeed());
                    }
                }
            }
        }
        return destroyed == 0 ? CrushResult.NONE : new CrushResult(destroyed, hardnessCost);
    }

    public static double kineticEnergy(double massFactor, Vec3 velocity) {
        return velocity == null ? 0.0D : 0.5D * Math.max(0.1D, massFactor) * velocity.lengthSqr();
    }

    private static TickBudget budget(ServerLevel level) {
        long gameTime = level.getGameTime();
        synchronized (BUDGETS) {
            TickBudget budget = BUDGETS.computeIfAbsent(level, ignored -> new TickBudget());
            if (budget.gameTime != gameTime) {
                budget.gameTime = gameTime;
                budget.remaining = Math.max(0, Config.STRUCTURE_CRUSHER_MAX_BLOCKS_PER_TICK.get());
            }
            return budget;
        }
    }

    private static boolean applyMutation(ServerLevel level, BlockPos pos, BlockState state, Mutation mutation) {
        sendBreakParticles(level, pos, state);
        if (mutation == Mutation.VAPORIZE) {
            return BlockGrabSafety.safeSetBlock(level, pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        if (mutation == Mutation.SPLINTER) {
            boolean changed = BlockGrabSafety.safeSetBlock(level, pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            if (changed && level.random.nextFloat() < 0.45F) {
                Block.popResource(level, pos, new ItemStack(Items.STICK));
            }
            return changed;
        }
        if (mutation == Mutation.CRACK) {
            BlockState replacement = crackedState(state);
            return BlockGrabSafety.safeSetBlock(level, pos, replacement, Block.UPDATE_ALL);
        }
        return false;
    }

    private static void sendBreakParticles(ServerLevel level, BlockPos pos, BlockState state) {
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                pos.getX() + 0.5D,
                pos.getY() + 0.5D,
                pos.getZ() + 0.5D,
                8,
                0.35D,
                0.35D,
                0.35D,
                0.04D);
    }

    private static BlockState crackedState(BlockState state) {
        if (state.is(Blocks.STONE_BRICKS)) {
            return Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
        }
        if (state.is(Blocks.COBBLESTONE) || state.is(Blocks.COBBLED_DEEPSLATE)) {
            return Blocks.GRAVEL.defaultBlockState();
        }
        if (state.is(Blocks.STONE) || state.is(Blocks.SMOOTH_STONE)) {
            return Blocks.COBBLESTONE.defaultBlockState();
        }
        if (state.getFluidState().is(Fluids.EMPTY)) {
            return Blocks.GRAVEL.defaultBlockState();
        }
        return state;
    }

    private static Mutation mutationFor(BlockState state, boolean violent) {
        if (state == null || state.isAir()) {
            return Mutation.NONE;
        }
        if (isFragile(state)) {
            return Mutation.VAPORIZE;
        }
        if (isResidential(state)) {
            return Mutation.SPLINTER;
        }
        if (violent && isFortified(state)) {
            return Mutation.CRACK;
        }
        return Mutation.NONE;
    }

    private static boolean isFragile(BlockState state) {
        String path = blockPath(state);
        return state.is(BlockTags.LEAVES)
                || path.contains("glass")
                || path.contains("pane")
                || path.contains("leaves");
    }

    private static boolean isResidential(BlockState state) {
        String path = blockPath(state);
        return state.is(BlockTags.PLANKS)
                || state.is(BlockTags.WOODEN_FENCES)
                || state.is(BlockTags.WOODEN_DOORS)
                || state.is(BlockTags.WOODEN_SLABS)
                || state.is(BlockTags.WOODEN_STAIRS)
                || path.contains("terracotta")
                || path.contains("wool")
                || path.contains("fence")
                || path.contains("planks");
    }

    private static boolean isFortified(BlockState state) {
        String path = blockPath(state);
        return state.is(Blocks.STONE_BRICKS)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.COBBLED_DEEPSLATE)
                || state.is(Blocks.STONE)
                || state.is(Blocks.SMOOTH_STONE)
                || path.contains("brick")
                || path.contains("cobble");
    }

    private static String blockPath(BlockState state) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key == null ? "" : key.getPath();
    }

    public record CrushResult(int destroyedBlocks, double hardnessCost) {
        public static final CrushResult NONE = new CrushResult(0, 0.0D);
    }

    private enum Mutation {
        NONE,
        VAPORIZE,
        SPLINTER,
        CRACK
    }

    private static final class TickBudget {
        long gameTime = Long.MIN_VALUE;
        int remaining;
    }
}
