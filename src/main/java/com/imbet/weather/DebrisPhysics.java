package com.imbet.weather;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Per-material air resistance for airborne debris, as pure numbers.
 *
 * <p>A leaf is almost all surface and almost no mass, so the air holds it aloft and it drifts; a stone
 * block is the other way round and drops out of the circulation. That is quadratic drag - the force is
 * {@code F = ½ ρ C_d A v²}, so the speed a piece loses per tick grows with its own speed and with how
 * much surface it presents. Each material gets a coefficient (the {@code DRAG_*} constants): foliage
 * and glass shed speed fast, timber less, soil less again, and stone and metal barely at all because
 * their mass-to-area ratio is high.</p>
 *
 * <p>Nothing here touches a world or a renderer, so it is shared by {@link DebrisEntity}'s motion and
 * is readable from source by {@code .freebuff/scratch/DebrisSim.java}.</p>
 */
public final class DebrisPhysics {
    /** One mph is 0.44704 m/s, and a tick is a twentieth of a second. */
    public static final double BLOCKS_PER_TICK_PER_MPH = 0.44704D / 20.0D;

    /** Below this speed, in blocks per tick, the local wind is treated as calm and nothing is coupled. */
    public static final double CALM_WIND_BLOCKS_PER_TICK = 0.02D;

    /** Light, air-held material: leaves, saplings, flowers, crops. Sheds speed fastest. */
    public static final double DRAG_FOLIAGE = 0.090D;
    /** Timber: planks, logs, wooden doors and fences. */
    public static final double DRAG_WOOD = 0.055D;
    /** Glass and other impermeable panes. */
    public static final double DRAG_GLASS = 0.062D;
    /** Soil and loose ground: dirt, sand, gravel, clay, overworld base stone. */
    public static final double DRAG_SOIL = 0.070D;
    /** Masonry: bricks, stone and its shaped variants, deepslate. */
    public static final double DRAG_STONE = 0.038D;
    /** Metal: iron, gold, copper, netherite, anvils. Heaviest, barely dragged. */
    public static final double DRAG_METAL = 0.022D;
    /** Anything unrecognised. */
    public static final double DRAG_DEFAULT = 0.050D;

    private DebrisPhysics() {
    }

    /**
     * How much speed a material sheds to the air per tick, per block-per-tick of its own speed. Higher
     * is lighter and more air-held; lower is heavier and drops out of the circulation sooner.
     */
    public static double dragCoefficient(BlockState state) {
        if (state == null) {
            return DRAG_DEFAULT;
        }
        if (state.is(BlockTags.LEAVES) || state.is(BlockTags.SAPLINGS) || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.CROPS) || state.is(BlockTags.REPLACEABLE_BY_TREES)) {
            return DRAG_FOLIAGE;
        }
        if (state.is(BlockTags.PLANKS) || state.is(BlockTags.LOGS) || state.is(BlockTags.WOODEN_SLABS)
                || state.is(BlockTags.WOODEN_STAIRS) || state.is(BlockTags.WOODEN_DOORS)
                || state.is(BlockTags.WOODEN_FENCES)) {
            return DRAG_WOOD;
        }
        if (state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE) || state.is(BlockTags.IMPERMEABLE)) {
            return DRAG_GLASS;
        }
        if (state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY) || state.is(BlockTags.BASE_STONE_OVERWORLD)) {
            return DRAG_SOIL;
        }
        if (state.is(Blocks.IRON_BLOCK) || state.is(Blocks.GOLD_BLOCK) || state.is(Blocks.COPPER_BLOCK)
                || state.is(Blocks.NETHERITE_BLOCK) || state.is(Blocks.IRON_BARS)
                || state.is(Blocks.ANVIL) || state.is(Blocks.RAW_IRON_BLOCK) || state.is(Blocks.RAW_COPPER_BLOCK)) {
            return DRAG_METAL;
        }
        if (state.is(BlockTags.STONE_BRICKS) || state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.DEEPSLATE) || state.is(Blocks.BRICKS)
                || state.is(Blocks.SMOOTH_STONE) || state.is(Blocks.STONE_BRICKS)) {
            return DRAG_STONE;
        }
        return DRAG_DEFAULT;
    }
}
