package com.imbet.spc;

import com.imbet.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The weather computer's block entity.
 *
 * <p>It is the SPC risk instrument in a different case: same outlook service, same issuance, same hazard
 * probabilities, same tick. Everything it knows is already on {@link SpcRiskBlockEntity}, so there is
 * nothing here but the block entity type - which is the point. A second copy of the outlook logic is a
 * second thing that can disagree with the panel on the wall.
 */
public class WeatherComputerBlockEntity extends SpcRiskBlockEntity {
    public WeatherComputerBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModContent.WEATHER_COMPUTER_BLOCK_ENTITY.get(), pos, blockState);
    }
}
