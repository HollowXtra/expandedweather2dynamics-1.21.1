package com.imbet.weather;

import com.imbet.Config;
import com.imbet.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The ground a wildfire has been over.
 *
 * <p>{@link BurntPass} says what fire leaves behind; this is the part that reads the world and writes it.
 * It runs on the wildfire event itself, once a second, over the disc the fire has already burned through -
 * so the scar is left along the track the fire has walked rather than in one ring around it, which is what
 * a moving burn actually does and what makes the pass visible as a direction.
 *
 * <p>Two things are deliberate. The trunk fate is odds rather than a rule, because a real burn leaves some
 * trees standing; and the canopy fate is not, because a burned forest with its leaves on is not a thing
 * that exists. Charred trunks are their own block rather than stripped logs, so the scar cannot be mistaken
 * for the debarking a tornado does - the two are different events and a surveyor should be able to tell
 * which one came through.
 */
public final class WildfireScar {

    /** How often the pass writes. One second, the same cadence as the fire's own upkeep. */
    private static final long PASS_INTERVAL_TICKS = 20L;

    /** How many blocks one pass may change, across all of its cells. */
    private static final int MAX_EDITS = 26;

    private WildfireScar() {
    }

    public static void tick(ServerLevel level, AddonWeatherEvent event) {
        if (!Config.WILDFIRE_BURNT_PASS.get() || event == null || event.position() == null) {
            return;
        }
        if (!Config.damageEnabled() || level.getGameTime() % PASS_INTERVAL_TICKS != 0L) {
            return;
        }
        int cells = BurntPass.cellsPerPass(event.radius());
        double scarRadius = BurntPass.scarRadiusBlocks(event.radius());
        boolean trunks = Config.WILDFIRE_BURNT_TRUNKS.get();
        int edits = 0;
        for (int i = 0; i < cells && edits < MAX_EDITS; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double distance = Math.sqrt(level.random.nextDouble()) * scarRadius;
            BlockPos column = BlockPos.containing(
                    event.position().x + Math.cos(angle) * distance,
                    event.position().y,
                    event.position().z + Math.sin(angle) * distance);
            if (!level.isLoaded(column)) {
                continue;
            }
            BlockPos surface = BlockGrabSafety.safeSurface(level, column, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
            if (surface == null) {
                continue;
            }
            double heat = BurntPass.heat(distance, event.radius(), event.maxWindMph(), false);
            edits += burnColumn(level, surface, heat, trunks, MAX_EDITS - edits);
        }
    }

    /** Read one column from the ground up and apply a fate to everything the fire would have reached. */
    private static int burnColumn(ServerLevel level, BlockPos surface, double heat, boolean trunks, int budget) {
        int edits = 0;
        for (int dy = 0; dy <= BurntPass.COLUMN_HEIGHT_BLOCKS && edits < budget; dy++) {
            BlockPos pos = surface.offset(0, dy, 0);
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockGrabSafety.BlockSample block = BlockGrabSafety.sample(level, pos, null);
            if (!BlockGrabSafety.canEdit(block)) {
                continue;
            }
            BlockState state = block.state();
            BurntPass.Foliage foliage = classify(state);
            if (foliage == BurntPass.Foliage.OTHER) {
                continue;
            }
            BurntPass.Fate fate = BurntPass.fate(foliage, heat, level.random.nextDouble());
            if (fate == BurntPass.Fate.KEEP) {
                continue;
            }
            if (fate == BurntPass.Fate.CHAR && (!trunks || !state.is(BlockTags.LOGS))) {
                continue;
            }
            BlockState replacement = replacementFor(state, fate);
            if (replacement == null) {
                continue;
            }
            if (BlockGrabSafety.safeSetBlock(level, pos, replacement, 3)) {
                edits++;
            }
        }
        return edits;
    }

    private static BlockState replacementFor(BlockState state, BurntPass.Fate fate) {
        return switch (fate) {
            case BURN_AWAY -> Blocks.AIR.defaultBlockState();
            case CHAR -> charred(state);
            case SCORCH -> Blocks.COARSE_DIRT.defaultBlockState();
            case KEEP -> null;
        };
    }

    /** A charred trunk keeps the trunk's own axis, so a tree that fell lies down and one that stood stands. */
    private static BlockState charred(BlockState state) {
        BlockState charred = ModContent.CHARRED_LOG.get().defaultBlockState();
        if (state.hasProperty(BlockStateProperties.AXIS) && charred.hasProperty(BlockStateProperties.AXIS)) {
            charred = charred.setValue(BlockStateProperties.AXIS, state.getValue(BlockStateProperties.AXIS));
        }
        return charred;
    }

    private static BurntPass.Foliage classify(BlockState state) {
        if (state.is(BlockTags.LOGS)) {
            return BurntPass.Foliage.TRUNK;
        }
        if (state.is(BlockTags.LEAVES)) {
            return BurntPass.Foliage.LEAVES;
        }
        if (state.is(BlockTags.REPLACEABLE_BY_TREES)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.CROPS)
                || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.CAVE_VINES)) {
            return BurntPass.Foliage.UNDERGROWTH;
        }
        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM) || state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.FARMLAND)
                || state.is(Blocks.COARSE_DIRT)) {
            return BurntPass.Foliage.SOIL;
        }
        return BurntPass.Foliage.OTHER;
    }
}
