package com.imbet.village;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The survey station: a plate, a post and a head, which is what a villager claims to become a surveyor.
 *
 * <p>The shape is the model's own masses so a tripod post is not a full block you cannot walk through.
 */
public class SurveyStationBlock extends WorkstationBlock {
    public static final MapCodec<SurveyStationBlock> CODEC = simpleCodec(SurveyStationBlock::new);

    private static final VoxelShape SILHOUETTE = Shapes.or(
            Shapes.box(0.0D, 0.0D, 0.0D, 1.0D, 2.0D / 16.0D, 1.0D),
            Shapes.box(0.375D, 2.0D / 16.0D, 0.375D, 0.625D, 12.0D / 16.0D, 0.625D),
            Shapes.box(0.0D, 12.0D / 16.0D, 0.0D, 1.0D, 1.0D, 1.0D));

    public SurveyStationBlock(Properties properties) {
        super(properties, SILHOUETTE, "Survey Station",
                "  A surveyor villager claims this and walks recent tornado tracks,",
                "  rating the damage with the same numbers the surveyor item reports.");
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }
}
