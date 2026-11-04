package com.imbet.weather;

import com.imbet.Config;
import com.imbet.defence.Fortification;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

public final class BlockGrabSafety {
    private static final Map<Level, TickCache> CACHES = new WeakHashMap<>();

    private BlockGrabSafety() {
    }

    public static BlockSample sample(Level level, BlockPos pos, BlockState knownState) {
        if (level == null || pos == null) {
            return BlockSample.invalid(BlockPos.ZERO);
        }
        long gameTime = safeGameTime(level);
        synchronized (CACHES) {
            TickCache cache = CACHES.computeIfAbsent(level, ignored -> new TickCache());
            if (cache.gameTime != gameTime) {
                cache.gameTime = gameTime;
                cache.samples.clear();
            }
            long key = pos.asLong();
            BlockSample cached = cache.samples.get(key);
            if (cached != null && (knownState == null || cached.state() == knownState)) {
                return cached;
            }
            BlockSample sample = createSample(level, pos, knownState);
            cache.samples.put(key, sample);
            return sample;
        }
    }

    public static void invalidate(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }
        synchronized (CACHES) {
            TickCache cache = CACHES.get(level);
            if (cache != null) {
                cache.samples.remove(pos.asLong());
            }
        }
    }

    public static boolean tryOpenDoor(Level level, BlockSample sample) {
        if (level == null || sample == null || !sample.valid()) {
            return false;
        }
        BlockState state = sample.state();
        try {
            if (!state.hasProperty(BlockStateProperties.OPEN) || state.getValue(BlockStateProperties.OPEN)) {
                return false;
            }
            level.setBlock(sample.pos(), state.setValue(BlockStateProperties.OPEN, true), 3);
            level.updateNeighborsAt(sample.pos(), state.getBlock());
            invalidate(level, sample.pos());
            return true;
        } catch (RuntimeException ignored) {
            invalidate(level, sample.pos());
            return false;
        }
    }

    public static boolean safeSetBlock(Level level, BlockPos pos, BlockState state, int flags) {
        if (level == null || pos == null || state == null) {
            return false;
        }
        try {
            boolean changed = level.setBlock(pos, state, flags);
            invalidate(level, pos);
            return changed;
        } catch (RuntimeException ignored) {
            invalidate(level, pos);
            return false;
        }
    }

    public static boolean safeDestroyBlock(Level level, BlockPos pos, boolean dropBlock) {
        if (level == null || pos == null) {
            return false;
        }
        try {
            boolean destroyed = level.destroyBlock(pos, dropBlock);
            invalidate(level, pos);
            return destroyed;
        } catch (RuntimeException ignored) {
            invalidate(level, pos);
            return false;
        }
    }

    public static BlockPos safeSurface(Level level, BlockPos column, Heightmap.Types type) {
        if (level == null || column == null || type == null || !safeLoaded(level, column)) {
            return null;
        }
        if (level instanceof ServerLevel serverLevel) {
            try {
                int chunkX = SectionPos.blockToSectionCoord(column.getX());
                int chunkZ = SectionPos.blockToSectionCoord(column.getZ());
                ChunkAccess chunk = serverLevel.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null || !chunk.hasPrimedHeightmap(type)) {
                    return null;
                }
                int y = chunk.getHeight(type, column.getX() & 15, column.getZ() & 15);
                if (y <= serverLevel.getMinBuildHeight()) {
                    return null;
                }
                int maxY = serverLevel.getMaxBuildHeight() - 1;
                if (y > maxY) {
                    y = maxY;
                }
                return new BlockPos(column.getX(), y, column.getZ());
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        try {
            return level.getHeightmapPos(type, column).immutable();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static BlockPos vortexGroundSurface(Level level, BlockPos column) {
        if (level == null || column == null || !safeLoaded(level, column)) {
            return null;
        }
        BlockPos surface = safeSurface(level, column, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        if (surface == null) {
            surface = safeSurface(level, column, Heightmap.Types.WORLD_SURFACE_WG);
        }
        if (surface == null) {
            return null;
        }
        int startY = MthClamp(surface.getY() - 1, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        int minY = Math.max(level.getMinBuildHeight(), startY - 96);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(column.getX(), startY, column.getZ());
        for (int y = startY; y >= minY; y--) {
            cursor.setY(y);
            BlockState state;
            try {
                state = level.getBlockState(cursor);
            } catch (RuntimeException ignored) {
                break;
            }
            if (state.isAir()) {
                continue;
            }
            if (!state.getFluidState().isEmpty()) {
                return cursor.above().immutable();
            }
            if (isVortexAnchorIgnored(state)) {
                continue;
            }
            return cursor.above().immutable();
        }
        return surface.immutable();
    }

    public static boolean canEdit(BlockSample sample) {
        return canEdit(sample, Config.DAMAGE_PRESERVE_INVENTORY_BLOCK_ENTITIES.get());
    }

    public static boolean canEdit(BlockSample sample, boolean preserveBlockEntities) {
        return sample != null
                && sample.valid()
                && sample.loaded()
                && !sample.air()
                && !sample.fluid()
                && !sample.protectedBlock()
                && sample.destroySpeed() >= 0.0F
                && (!preserveBlockEntities || !sample.hasBlockEntity());
    }

    public static boolean canPickupByWind(BlockSample sample, double windMph) {
        if (!canEdit(sample)) {
            return false;
        }
        if (Config.DAMAGE_NEVER_PICKUP_SOIL.get() && isNeverPickupTerrain(sample)) {
            return false;
        }
        if (!Config.DAMAGE_WIND_RESISTANCE.get()) {
            return true;
        }
        return windMph >= resistanceMph(sample);
    }

    public static boolean canImpactDamageByWind(BlockSample sample, double windMph) {
        if (sample == null || !sample.valid() || !sample.loaded() || sample.air() || sample.fluid() || sample.protectedBlock()) {
            return false;
        }
        if (Config.DAMAGE_NEVER_PICKUP_SOIL.get() && isNeverPickupTerrain(sample)) {
            return false;
        }
        if (sample.hasBlockEntity()) {
            if (!Config.DAMAGE_BLOCK_ENTITY_IMPACT_DAMAGE.get()) {
                return false;
            }
            if (Config.DAMAGE_PRESERVE_INVENTORY_BLOCK_ENTITIES.get() && sample.inventoryBlockEntity()) {
                return false;
            }
        }
        if (sample.destroySpeed() < 0.0F) {
            return false;
        }
        return !Config.DAMAGE_WIND_RESISTANCE.get() || windMph >= resistanceMph(sample);
    }

    public static boolean isNeverPickupTerrain(BlockSample sample) {
        if (sample == null || !sample.valid()) {
            return false;
        }
        BlockState state = sample.state();
        return state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.FARMLAND)
                || state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.MUD)
                || state.is(Blocks.CLAY);
    }

    public static double resistanceMph(BlockSample sample) {
        if (sample == null || !sample.valid()) {
            return Double.MAX_VALUE;
        }
        BlockState state = sample.state();
        if (isHeavyLike(state, sample.destroySpeed())) {
            return Math.max(200.0D, Config.DAMAGE_HEAVY_PICKUP_MPH.get());
        }
        if (isLogLike(state) || isStoneLike(state, sample.destroySpeed())) {
            return Math.max(136.0D, Config.DAMAGE_STONE_PICKUP_MPH.get());
        }
        if (isWoodLike(state)) {
            return Math.max(115.0D, Config.DAMAGE_WOOD_PICKUP_MPH.get());
        }
        if (isWeakLike(state, sample.destroySpeed())) {
            return Math.max(65.0D, Config.DAMAGE_EF0_PICKUP_MPH.get());
        }
        float hardness = sample.destroySpeed();
        if (hardness <= 1.5F) {
            return Math.max(115.0D, Config.DAMAGE_WOOD_PICKUP_MPH.get());
        }
        if (hardness <= 6.0F) {
            return Math.max(136.0D, Config.DAMAGE_STONE_PICKUP_MPH.get());
        }
        return Math.max(200.0D, Config.DAMAGE_HEAVY_PICKUP_MPH.get());
    }

    private static BlockSample createSample(Level level, BlockPos pos, BlockState knownState) {
        BlockPos immutable = pos.immutable();
        boolean loaded = safeLoaded(level, immutable);
        if (!loaded) {
            return new BlockSample(immutable, Blocks.AIR.defaultBlockState(), false, true, false, true, false, false, -1.0F, false);
        }

        BlockState state = knownState;
        if (state == null) {
            try {
                state = level.getBlockState(immutable);
            } catch (RuntimeException ignored) {
                return BlockSample.invalid(immutable);
            }
        }

        boolean air;
        boolean fluid;
        boolean protectedBlock;
        boolean hasBlockEntity;
        boolean inventoryBlockEntity;
        float destroySpeed;
        try {
            air = state.isAir();
            fluid = !state.getFluidState().isEmpty();
            // Fortification is folded in here rather than checked by each hazard, so a player's reinforced glass
            // and their anchored walls are refused by every pass at once - the wind, the hail, the derecho's roof
            // stripping, the lava - instead of by whichever pass happened to be tested. See Fortification.
            protectedBlock = isProtected(state)
                    || CompatHooks.isValkyrienBlockInteractionUnsafe(level, immutable, state)
                    || Fortification.fortified(level, immutable, state);
            destroySpeed = state.getDestroySpeed(level, immutable);
        } catch (RuntimeException ignored) {
            return BlockSample.invalid(immutable);
        }

        try {
            BlockEntity blockEntity = level.getBlockEntity(immutable);
            hasBlockEntity = blockEntity != null;
            inventoryBlockEntity = blockEntity instanceof Container;
        } catch (RuntimeException ignored) {
            hasBlockEntity = true;
            inventoryBlockEntity = true;
        }

        return new BlockSample(immutable, state, true, air, fluid, protectedBlock, hasBlockEntity, inventoryBlockEntity, destroySpeed, true);
    }

    private static boolean safeLoaded(Level level, BlockPos pos) {
        try {
            return level.isLoaded(pos);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static long safeGameTime(Level level) {
        try {
            return level.getGameTime();
        } catch (RuntimeException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private static boolean isProtected(BlockState state) {
        return state.is(Blocks.BEDROCK)
                || state.is(Blocks.END_PORTAL)
                || state.is(Blocks.END_PORTAL_FRAME)
                || state.is(Blocks.NETHER_PORTAL)
                || state.is(Blocks.COMMAND_BLOCK)
                || state.is(Blocks.CHAIN_COMMAND_BLOCK)
                || state.is(Blocks.REPEATING_COMMAND_BLOCK)
                || state.is(Blocks.STRUCTURE_BLOCK)
                || state.is(Blocks.JIGSAW)
                || state.is(Blocks.BARRIER)
                || state.is(Blocks.LIGHT);
    }

    private static boolean isVortexAnchorIgnored(BlockState state) {
        if (state == null) {
            return true;
        }
        return state.is(BlockTags.LEAVES)
                || state.is(BlockTags.LOGS)
                || state.is(BlockTags.PLANKS)
                || state.is(BlockTags.WOODEN_DOORS)
                || state.is(BlockTags.WOODEN_STAIRS)
                || state.is(BlockTags.WOODEN_SLABS)
                || state.is(BlockTags.WOODEN_FENCES)
                || state.is(BlockTags.FENCE_GATES)
                || state.is(BlockTags.CAVE_VINES)
                || state.is(BlockTags.REPLACEABLE_BY_TREES)
                || state.is(Blocks.BAMBOO)
                || state.is(Blocks.COCOA)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.BRICKS)
                || state.is(Blocks.TERRACOTTA)
                || idPathContains(state.getBlock(), "log")
                || idPathContains(state.getBlock(), "wood")
                || idPathContains(state.getBlock(), "plank")
                || idPathContains(state.getBlock(), "stem")
                || idPathContains(state.getBlock(), "hyphae")
                || idPathContains(state.getBlock(), "fence")
                || idPathContains(state.getBlock(), "door")
                || idPathContains(state.getBlock(), "trapdoor")
                || idPathContains(state.getBlock(), "stairs")
                || idPathContains(state.getBlock(), "slab")
                || idPathContains(state.getBlock(), "wall")
                || idPathContains(state.getBlock(), "glass")
                || idPathContains(state.getBlock(), "pane")
                || idPathContains(state.getBlock(), "brick")
                || idPathContains(state.getBlock(), "cobble")
                || idPathContains(state.getBlock(), "terracotta")
                || idPathContains(state.getBlock(), "concrete")
                || idPathContains(state.getBlock(), "wool")
                || idPathContains(state.getBlock(), "carpet")
                || idPathContains(state.getBlock(), "hay");
    }

    private static boolean isWeakLike(BlockState state, float hardness) {
        return hardness <= 0.6F
                || state.is(BlockTags.CROPS)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.REPLACEABLE_BY_TREES)
                || idPathContains(state.getBlock(), "glass")
                || idPathContains(state.getBlock(), "pane");
    }

    private static boolean isWoodLike(BlockState state) {
        return state.is(BlockTags.PLANKS)
                || state.is(BlockTags.WOODEN_DOORS)
                || state.is(BlockTags.WOODEN_STAIRS)
                || state.is(BlockTags.WOODEN_SLABS)
                || state.is(BlockTags.WOODEN_FENCES)
                || state.is(BlockTags.FENCE_GATES)
                || idPathContains(state.getBlock(), "wood")
                || idPathContains(state.getBlock(), "planks");
    }

    private static boolean isLogLike(BlockState state) {
        return state.is(BlockTags.LOGS)
                || idPathContains(state.getBlock(), "log")
                || idPathContains(state.getBlock(), "stem")
                || idPathContains(state.getBlock(), "hyphae");
    }

    private static boolean isStoneLike(BlockState state, float hardness) {
        return hardness >= 1.5F
                && (state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.STONE_BRICKS)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.BRICKS)
                || state.is(Blocks.TERRACOTTA)
                || idPathContains(state.getBlock(), "stone")
                || idPathContains(state.getBlock(), "brick")
                || idPathContains(state.getBlock(), "concrete"));
    }

    private static boolean isHeavyLike(BlockState state, float hardness) {
        return hardness >= 8.0F
                || state.is(Blocks.OBSIDIAN)
                || state.is(Blocks.CRYING_OBSIDIAN)
                || state.is(Blocks.ANCIENT_DEBRIS)
                || state.is(Blocks.IRON_BLOCK)
                || state.is(Blocks.COPPER_BLOCK)
                || state.is(Blocks.GOLD_BLOCK)
                || state.is(Blocks.DIAMOND_BLOCK)
                || state.is(Blocks.NETHERITE_BLOCK)
                || idPathContains(state.getBlock(), "steel");
    }

    private static boolean idPathContains(Block block, String text) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return id != null && id.getPath().contains(text);
    }

    private static int MthClamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public record BlockSample(
            BlockPos pos,
            BlockState state,
            boolean loaded,
            boolean air,
            boolean fluid,
            boolean protectedBlock,
            boolean hasBlockEntity,
            boolean inventoryBlockEntity,
            float destroySpeed,
            boolean valid
    ) {
        private static BlockSample invalid(BlockPos pos) {
            return new BlockSample(pos.immutable(), Blocks.AIR.defaultBlockState(), false, true, false, true, true, true, -1.0F, false);
        }
    }

    private static final class TickCache {
        private long gameTime = Long.MIN_VALUE;
        private final Map<Long, BlockSample> samples = new HashMap<>();
    }
}
