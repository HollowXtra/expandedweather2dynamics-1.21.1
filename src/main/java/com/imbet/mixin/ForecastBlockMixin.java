package com.imbet.mixin;

import com.imbet.weather.ForecastReadout;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import weather2.block.ForecastBlock;

@Mixin(value = ForecastBlock.class, remap = false)
public abstract class ForecastBlockMixin {
    @Inject(method = "use", at = @At("RETURN"))
    private void expandedweather2dynamics$showForecastRadar(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir) {
        if (hand != InteractionHand.MAIN_HAND) {
            return;
        }
        ForecastReadout.extendWeather2Scan(level, pos, player, player.isShiftKeyDown());
    }
}
