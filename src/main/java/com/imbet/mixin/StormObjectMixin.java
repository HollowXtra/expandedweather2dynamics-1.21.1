package com.imbet.mixin;

import com.imbet.weather.DynamicStormController;
import com.imbet.weather.TornadoDamageModel;
import com.imbet.weather.AdvancementAwarder;
import com.imbet.weather.BlockGrabSafety;
import com.imbet.weather.WindSteeringManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.LightningBoltWeatherNew;

@Mixin(value = StormObject.class, remap = false)
public abstract class StormObjectMixin {
    @Unique
    private Vec3 expandedweather2dynamics$preSteeringPos;

    @Inject(method = "tick", at = @At("HEAD"))
    private void expandedweather2dynamics$tickDynamicState(CallbackInfo ci) {
        StormObject storm = (StormObject) (Object) this;
        expandedweather2dynamics$preSteeringPos = storm.pos;
        DynamicStormController.tickStorm(storm);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void expandedweather2dynamics$applyAtmosphericSteering(CallbackInfo ci) {
        StormObject storm = (StormObject) (Object) this;
        WindSteeringManager.applyPositionCorrection(storm, expandedweather2dynamics$preSteeringPos);
        expandedweather2dynamics$preSteeringPos = null;
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

    @Inject(method = "getAdjustedSpeed", at = @At("RETURN"), cancellable = true)
    private void expandedweather2dynamics$adjustMovementSpeed(CallbackInfoReturnable<Float> cir) {
        StormObject storm = (StormObject) (Object) this;
        cir.setReturnValue(WindSteeringManager.steeringSpeed(storm, cir.getReturnValue()));
    }

    @Inject(method = "calculateTopYBlock", at = @At("RETURN"), cancellable = true)
    private void expandedweather2dynamics$ignoreTreeAndRoofAnchors(CallbackInfoReturnable<Integer> cir) {
        StormObject storm = (StormObject) (Object) this;
        if (storm == null || storm.manager == null || storm.manager.getWorld() == null || storm.pos == null || storm.isPet() || storm.isPlayerControlled()) {
            return;
        }
        Level level = storm.manager.getWorld();
        BlockPos ground = BlockGrabSafety.vortexGroundSurface(level, BlockPos.containing(storm.pos.x, cir.getReturnValue(), storm.pos.z));
        if (ground != null) {
            cir.setReturnValue(ground.getY());
        }
    }

    @Inject(method = "stageNext", at = @At("RETURN"))
    private void expandedweather2dynamics$enableNativeCyclonePath(CallbackInfo ci) {
        DynamicStormController.afterStageNext((StormObject) (Object) this);
    }

    @Inject(method = "rollDiceOnMaxIntensity", at = @At("RETURN"), cancellable = true)
    private void expandedweather2dynamics$weightNaturalTornadoIntensity(CallbackInfoReturnable<Integer> cir) {
        StormObject storm = (StormObject) (Object) this;
        cir.setReturnValue(DynamicStormController.adjustRolledMaxIntensity(storm, cir.getReturnValue()));
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void expandedweather2dynamics$saveAddonState(CallbackInfo ci) {
        DynamicStormController.saveStateToStorm((StormObject) (Object) this);
    }

    @Inject(method = {"read", "nbtSyncFromServer"}, at = @At("RETURN"))
    private void expandedweather2dynamics$loadAddonState(CallbackInfo ci) {
        DynamicStormController.loadStateFromStorm((StormObject) (Object) this);
    }

    @Unique
    private long expandedweather2dynamics$lastClientSyncGameTime = Long.MIN_VALUE;

    @Inject(method = "read", at = @At("HEAD"))
    private void expandedweather2dynamics$capturePreSyncPosition(CallbackInfo ci) {
        try {
            StormObject storm = (StormObject) (Object) this;
            if (storm.manager == null || storm.manager.getWorld() == null || !storm.manager.getWorld().isClientSide()) {
                return;
            }
            WindSteeringManager.onClientSyncStart(storm, storm.manager.getWorld().getGameTime(), expandedweather2dynamics$lastClientSyncGameTime);
            expandedweather2dynamics$lastClientSyncGameTime = storm.manager.getWorld().getGameTime();
        } catch (RuntimeException ignored) {
        }
    }

    @Inject(method = "read", at = @At("RETURN"))
    private void expandedweather2dynamics$capturePostSyncPosition(CallbackInfo ci) {
        try {
            StormObject storm = (StormObject) (Object) this;
            if (storm.manager == null || storm.manager.getWorld() == null || !storm.manager.getWorld().isClientSide()) {
                return;
            }
            WindSteeringManager.onClientSyncComplete(storm, storm.manager.getWorld().getGameTime());
        } catch (RuntimeException ignored) {
        }
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
        cir.setReturnValue(DynamicStormController.adjustEntityMotion(storm, position, cir.getReturnValue(), forPlayer, forCube, grabAdj));
    }

    @Inject(method = "spinEntityv2", at = @At("HEAD"), cancellable = true)
    private void expandedweather2dynamics$mobCapture(Entity entity, CallbackInfo ci) {
        StormObject storm = (StormObject) (Object) this;
        if (TornadoDamageModel.applyMobCapture(storm, entity)) {
            ci.cancel();
        }
    }

    /**
     * Weather2's own entity pick-up (canGrabEntity -> spinEntityv2's own body, plus its
     * "tornado_shoot" branch) carries entities our capture never sees. It runs after the method,
     * so those entities get the same tumble without Weather2's motion being replaced. Skipped when
     * the HEAD inject above cancelled the call (i.e. when the violent-core capture handled it).
     */
    @Inject(method = "spinEntityv2", at = @At("TAIL"))
    private void expandedweather2dynamics$tossedTumble(Entity entity, CallbackInfo ci) {
        try {
            TornadoDamageModel.applyTossTumble((StormObject) (Object) this, entity);
        } catch (RuntimeException ignored) {
        }
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
