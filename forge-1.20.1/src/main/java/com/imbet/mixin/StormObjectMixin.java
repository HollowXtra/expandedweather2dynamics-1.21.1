package com.imbet.mixin;

import com.imbet.weather.DynamicStormController;
import com.imbet.weather.AdvancementAwarder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.LightningBoltWeatherNew;

@Mixin(value = StormObject.class, remap = false)
public abstract class StormObjectMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void expandedweather2dynamics$tickDynamicState(CallbackInfo ci) {
        DynamicStormController.tickStorm((StormObject) (Object) this);
    }

    @Inject(method = "tickClient", at = @At("RETURN"))
    private void expandedweather2dynamics$applyWindSpeedVisualSpin(CallbackInfo ci) {
        DynamicStormController.applyClientVisualSpin((StormObject) (Object) this);
    }

    @Inject(method = "getAdjustedAngle", at = @At("RETURN"), cancellable = true)
    private void expandedweather2dynamics$adjustMovementAngle(CallbackInfoReturnable<Float> cir) {
        StormObject storm = (StormObject) (Object) this;
        cir.setReturnValue(DynamicStormController.adjustedAngle(storm, cir.getReturnValue()));
    }

    @Inject(method = "stageNext", at = @At("RETURN"))
    private void expandedweather2dynamics$enableNativeCyclonePath(CallbackInfo ci) {
        DynamicStormController.afterStageNext((StormObject) (Object) this);
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void expandedweather2dynamics$saveAddonState(CallbackInfo ci) {
        DynamicStormController.saveStateToStorm((StormObject) (Object) this);
    }

    @Inject(method = {"read", "nbtSyncFromServer"}, at = @At("RETURN"))
    private void expandedweather2dynamics$loadAddonState(CallbackInfo ci) {
        DynamicStormController.loadStateFromStorm((StormObject) (Object) this);
    }

    @Inject(method = "aimStormAtClosestOrProvidedPlayer", at = @At("HEAD"), cancellable = true)
    private void expandedweather2dynamics$skipNaturalPlayerAim(Player player, CallbackInfo ci) {
        if (DynamicStormController.shouldCancelNaturalPlayerAim((StormObject) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "spinObject", at = @At("RETURN"), cancellable = true)
    private void expandedweather2dynamics$windfieldMotion(Vec3 position, Vec3 motion, boolean forPlayer, float dampenXZ, float dampenY, boolean forCube, float grabAdj, CallbackInfoReturnable<Vec3> cir) {
        StormObject storm = (StormObject) (Object) this;
        cir.setReturnValue(DynamicStormController.adjustEntityMotion(storm, position, cir.getReturnValue()));
    }

    @Inject(method = "addWeatherEffectLightning", at = @At("HEAD"))
    private void expandedweather2dynamics$awardThunder(LightningBoltWeatherNew lightning, boolean custom, CallbackInfo ci) {
        StormObject storm = (StormObject) (Object) this;
        if (storm.manager != null && storm.manager.getWorld() instanceof ServerLevel level) {
            for (ServerPlayer player : level.players()) {
                if (player.distanceToSqr(lightning.position()) < 512.0D * 512.0D) {
                    AdvancementAwarder.award(player, AdvancementAwarder.DELAYED_THUNDER);
                }
            }
        }
    }
}
