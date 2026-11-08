package com.imbet.mixin;

import com.imbet.Config;
import com.imbet.weather.DynamicStormController;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import weather2.weathersystem.storm.TornadoHelper;

@Mixin(value = TornadoHelper.class, remap = false)
public abstract class TornadoHelperMixin {
    @Inject(method = "getTornadoBaseSize", at = @At("RETURN"), cancellable = true)
    private void expandedweather2dynamics$variableWidth(CallbackInfoReturnable<Integer> cir) {
        TornadoHelper helper = (TornadoHelper) (Object) this;
        cir.setReturnValue(DynamicStormController.adjustedTornadoBaseSize(helper.storm, cir.getReturnValue()));
    }

    @Inject(method = "tryRip", at = @At("HEAD"), cancellable = true)
    private void expandedweather2dynamics$limitDamage(Level level, int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        if (!Config.damageEnabled()) {
            return;
        }
        TornadoHelper helper = (TornadoHelper) (Object) this;
        if (!DynamicStormController.shouldTryRip(helper, level, new BlockPos(x, y, z))) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "canGrab", at = @At("HEAD"), cancellable = true)
    private void expandedweather2dynamics$canGrabBlock(Level level, BlockState state, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!Config.damageEnabled()) {
            return;
        }
        TornadoHelper helper = (TornadoHelper) (Object) this;
        if (!DynamicStormController.canGrabBlock(helper, level, state, pos)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "canGrabEntity", at = @At("HEAD"), cancellable = true)
    private void expandedweather2dynamics$canGrabEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!Config.damageEnabled()) {
            return;
        }
        TornadoHelper helper = (TornadoHelper) (Object) this;
        if (DynamicStormController.shouldForceGrabEntity(helper, entity)) {
            cir.setReturnValue(true);
            return;
        }
        if (!DynamicStormController.canGrabEntity(helper, entity)) {
            cir.setReturnValue(false);
        }
    }
}
