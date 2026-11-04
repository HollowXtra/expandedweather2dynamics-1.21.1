package com.imbet.village;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The weather station: the block a meteorologist claims.
 *
 * <p>The fourth workstation, and the first one a village has a reason to want for a reason other than building.
 * A survey station reads what a storm left behind, a bench builds, a mill saws; a weather station is where the
 * village keeps what it knows about the sky - which is the one trade this mod's villages are in a position to
 * have, since they are the only places in the world that get rebuilt after a storm and keep a record of the one
 * that did it.
 *
 * <p>The shape is the model's own masses, so the post is a post and the plate on top is a surface rather than a
 * full block you cannot walk through.
 */
public class WeatherStationBlock extends WorkstationBlock {
    public static final MapCodec<WeatherStationBlock> CODEC = simpleCodec(WeatherStationBlock::new);

    private static final VoxelShape SILHOUETTE = Shapes.or(
            Shapes.box(0.0D, 0.0D, 0.0D, 1.0D, 2.0D / 16.0D, 1.0D),
            Shapes.box(6.5D / 16.0D, 2.0D / 16.0D, 6.5D / 16.0D, 9.5D / 16.0D, 11.0D / 16.0D, 9.5D / 16.0D),
            Shapes.box(1.0D / 16.0D, 11.0D / 16.0D, 2.0D / 16.0D, 15.0D / 16.0D, 16.0D / 16.0D, 14.0D / 16.0D));

    public WeatherStationBlock(Properties properties) {
        super(properties, SILHOUETTE, "Weather Station",
                "  A meteorologist claims this and trades in what the sky leaves behind:",
                "  storm logs and sensor parts for emeralds, and the instruments to read the sky with.");
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }
}
