package com.imbet.defence;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.TransparentBlock;

/**
 * Glass a storm cannot break, made by setting it in obsidian.
 *
 * <p>Glass is the one block in this mod that everything breaks. Hail shatters it from a dime upward, the wind
 * passes take panes off a wall before they take anything else, and a derecho strips a roof's panes along with its
 * shingles - which is honest, and which left a player with exactly one way to have a window: glass it in and
 * replace it. This is the other way. Obsidian is already the mod's own idea of a block nothing gets through (it is
 * on the heavy end of the wind ladder and is never picked up), so reinforcing glass with it is the same number said
 * twice, which is why the recipe is a frame of obsidian and not a new material.
 *
 * <p>What it is <em>not</em> is a new kind of glass with its own physics: it is the vanilla glass block with a
 * different skin, and the mod's damage passes refuse it because {@link Fortification} tells them to. Nothing about
 * light, occlusion or how it renders is re-invented here, and that is deliberate - a second glass implementation is
 * a second thing that can disagree with the first about whether you can see through it.
 */
public class ReinforcedGlassBlock extends TransparentBlock {
    public static final MapCodec<ReinforcedGlassBlock> CODEC = simpleCodec(ReinforcedGlassBlock::new);

    public ReinforcedGlassBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends TransparentBlock> codec() {
        return CODEC;
    }
}
