package com.imbet.mixin;

import com.imbet.Config;
import com.imbet.weather.AddonWeatherEventService;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.DynamicStormState;
import com.imbet.weather.StormLifecycleService;
import com.imbet.weather.TornadoDamageModel;
import com.imbet.weather.AdvancementAwarder;
import com.imbet.weather.BlockGrabSafety;
import com.imbet.weather.WindSteeringManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
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

    /**
     * The tick at which this storm's deadly capability was last asked of the air, so the reading is taken
     * about once every few seconds rather than every tick. {@link Long#MIN_VALUE} means it has never been
     * asked.
     */
    @Unique
    private long expandedweather2dynamics$deadlyAirCheckedAt = Long.MIN_VALUE;

    /**
     * Whether the last refusal of this storm's deadly capability was this gate's own, and so one it may
     * lift again. A storm the addon refused itself - a squall line's cell, a front's forced updraft -
     * never has this set, which is what keeps those refusals refused.
     */
    @Unique
    private boolean expandedweather2dynamics$airRefusedDeadly;

    /**
     * Makes the air the authority over Weather2's own deadly cycle, for every storm rather than only the
     * cells this mod seeds.
     *
     * <p>Weather2's constructor leaves {@code canBeDeadly} true on every storm, and its own
     * {@code tickProgression} promotes a fresh one into a deadly storm on a three-to-ten-game-day timer
     * without ever looking at the sky - so a capped, blown-out air mass produced its deadly storm on
     * schedule exactly as a loaded one did, and the only cells the air could refuse were the ones this mod
     * spawned itself. This is that promotion gated at its own question: the flag is read in exactly one
     * place and only while the storm is still fresh, so that is where the addon's reading is applied.
     *
     * <p>The flag follows the sky both ways, but only where this gate is what set it: a cap the air imposed
     * is lifted once the sky recovers, while a storm the addon refused itself - a squall line's cell, a
     * front's forced updraft - is never handed one back. The reading is sampled about once every ten
     * seconds because it reads the biomes around the storm and the storms around that, and taking it every
     * tick would be a heavy way to learn nothing new. With the deadly section's switch off the reading is
     * full air and this never takes anything away, which is Weather2's own behaviour.
     */
    @Inject(method = "tickProgression", at = @At("HEAD"))
    private void expandedweather2dynamics$gateDeadlyPromotionOnAir(CallbackInfo ci) {
        StormObject storm = (StormObject) (Object) this;
        if (storm.levelCurIntensityStage != StormObject.STATE_NORMAL) {
            return;
        }
        if (storm.weatherMachineControlled || storm.isPet() || storm.isPlayerControlled()) {
            return;
        }
        if (storm.manager == null || storm.pos == null || !(storm.manager.getWorld() instanceof ServerLevel level)) {
            return;
        }
        // A storm the addon refused itself - a squall line's cell, a front's forced updraft - is not this
        // gate's to hand back: only a refusal this gate made is one it can lift.
        if (!storm.canBeDeadly && !expandedweather2dynamics$airRefusedDeadly) {
            return;
        }
        long gameTime = level.getGameTime();
        if (expandedweather2dynamics$deadlyAirCheckedAt != Long.MIN_VALUE
                && gameTime - expandedweather2dynamics$deadlyAirCheckedAt < 200L) {
            return;
        }
        expandedweather2dynamics$deadlyAirCheckedAt = gameTime;
        if (AddonWeatherEventService.deadlyAirSupports(level, storm.pos)) {
            // The sky can carry a deadly storm again: hand back the flag this gate took away, if it took one.
            storm.canBeDeadly = true;
            expandedweather2dynamics$airRefusedDeadly = false;
        } else {
            storm.canBeDeadly = false;
            expandedweather2dynamics$airRefusedDeadly = true;
        }
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

    /**
     * The game time this storm last actually changed rung, for {@link StormLifecycleService}'s pace.
     * Held on the storm rather than serialized: a reload resetting the dwell simply lets the storm climb
     * again, which is harmless. {@link Long#MIN_VALUE} means it has never changed rung.
     */
    @Unique
    private long expandedweather2dynamics$lastLifecycleStageTick = Long.MIN_VALUE;

    /**
     * Paces Weather2's own staging through the mod's storm lifecycle. Weather2 advances a storm on an
     * accumulation timer of its own with no dwell, which is what let a fresh cell wear a violent rung
     * while its funnel was still a rope; this holds an ordinary land storm on its rung for a configured
     * dwell before it may climb again. See {@link StormLifecycleService} for the exemptions.
     */
    @Inject(method = "stageNext", at = @At("HEAD"), cancellable = true)
    private void expandedweather2dynamics$paceStageAdvance(CallbackInfo ci) {
        StormObject storm = (StormObject) (Object) this;
        long gameTime = StormLifecycleService.gameTimeOf(storm);
        if (StormLifecycleService.shouldBlockStageAdvance(storm, gameTime, expandedweather2dynamics$lastLifecycleStageTick)) {
            ci.cancel();
            return;
        }
        if (gameTime != Long.MIN_VALUE) {
            expandedweather2dynamics$lastLifecycleStageTick = gameTime;
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
