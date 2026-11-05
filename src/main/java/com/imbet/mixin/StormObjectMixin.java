package com.imbet.mixin;

import com.imbet.Config;
import com.imbet.weather.AddonWeatherEventService;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.DynamicStormState;
import com.imbet.weather.StormLifecycleService;
import com.imbet.weather.TornadoDamageModel;
import com.imbet.weather.AdvancementAwarder;
import com.imbet.weather.BlockGrabSafety;
import com.imbet.client.StormSwirlMirror;
import com.imbet.weather.WindSteeringManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import extendedrenderer.particle.entity.EntityRotFX;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import weather2.weathersystem.storm.StormObject;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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

    @Inject(method = "tickRender", at = @At("TAIL"))
    private void expandedweather2dynamics$sculptCloudMass(float partialTick, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        // RESHAPE ITSELF decides whether there is anything to do: the classic look stands the
        // cauliflower silhouette down but keeps the hurricane shield, which has its own switch.
        com.imbet.client.StormDeckCauliflower.reshape((StormObject) (Object) this);
        com.imbet.client.ClientWeatherEffects.dropFakeDebris((StormObject) (Object) this);
    }

    /**
     * Mirrors Weather2's own cloud and dust swarms into the state its tick expects to find, and mirrors
     * them back on the way out.
     *
     * <p>The pairing is the whole mechanism, so both halves have to hold: this one negates the swarms'
     * tangential motion, Weather2's loops then add their own (always cyclonic) impulses, and the RETURN
     * below negates the result - which leaves those impulses as the only mirrored part, because the
     * swarm's positions do not change across the tick. Only one of the two would cancel the swirl
     * instead of reversing it. See {@link StormSwirlMirror} for why the sign cannot be fixed at the
     * spin's own read.
     */
    @Inject(method = "tickClient", at = @At("HEAD"))
    private void expandedweather2dynamics$mirrorSwarmBeforeWeather2(CallbackInfo ci) {
        StormSwirlMirror.before((StormObject) (Object) this);
    }

    @Inject(method = "tickClient", at = @At("RETURN"))
    private void expandedweather2dynamics$applyWindSpeedVisualSpin(CallbackInfo ci) {
        StormObject storm = (StormObject) (Object) this;
        // The other half of the mirror: Weather2 has just swirled the deck and the dust its own way, and
        // this puts the state it left behind back into the anti-cyclonic frame the next tick will read.
        StormSwirlMirror.after(storm);
        DynamicStormController.applyClientVisualSpin(storm);
        expandedweather2dynamics$scaleClassicFunnel(storm);
    }

    /**
     * Base scale and the factor last written for one funnel particle, so the dynamic width can be
     * re-applied every tick without compounding.
     */
    @Unique
    private Map<EntityRotFX, float[]> expandedweather2dynamics$classicFunnelScales;

    /**
     * Carries this mod's dynamic size onto the revived 1.12.2 funnel.
     *
     * <p>Weather2's funnel particles know nothing about the dynamic width: each is spawned once at a
     * fixed scale and its spread comes only from the storm's own size field. Applying that width to the
     * funnel was the layer funnel's job, through its per-layer radius scale, and the layer funnel is
     * stood down in classic mode - so without this pass a classic tornado would keep drawing a column of
     * one width while its own state said it was a mile across. The classic look is meant to change how the
     * tornado is drawn, not to take the dynamic size away.
     *
     * <p>Scale, not position, is the lever: Weather2 drives these particles' positions itself every tick
     * and overwriting them would fight its swirl, while a bigger puff widens the column the way a wider
     * funnel really looks. The factor is derived from the state's own width multiplier, the same number
     * that scales the tornado's base size, so the drawn column and the damage swath stay in step - and it
     * is deliberately a mild, saturating one. See the band's own note below for why the raw multiplier is
     * the wrong thing to multiply a 37-block puff by.
     */
    @Unique
    private void expandedweather2dynamics$scaleClassicFunnel(StormObject storm) {
        if (!Config.weather2OwnScatter() || !Config.VARIABLE_WIDTHS_ENABLED.get()) {
            return;
        }
        List<EntityRotFX> particles = storm.listParticlesFunnel;
        if (particles == null || particles.isEmpty()) {
            expandedweather2dynamics$classicFunnelScales = null;
            return;
        }
        Map<EntityRotFX, float[]> scales = expandedweather2dynamics$classicFunnelScales;
        if (scales == null) {
            scales = new IdentityHashMap<>();
            expandedweather2dynamics$classicFunnelScales = scales;
        }
        DynamicStormState state = DynamicStormController.stateFor(storm);
        // Deliberately a narrow band, and deliberately sub-linear. Weather2's own funnel puffs are already
        // 22.5 to 37.5 blocks across and all spawn inside a radius that only grows with the storm's stage,
        // so puff size is a saturating lever: scaling it by the raw width multiplier (which runs into the
        // tens on a mile-wide tornado) drew 200-block quads and the funnel read as a wall of slabs filling
        // the sky. The square root keeps a wider storm visibly wider - a 4x wider swath draws puffs about
        // 1.6x across - while leaving Weather2's own footprint, which is what makes a classic funnel a
        // funnel, in charge of the shape. The rest of the read is appearance: opacity, tone and the
        // per-stage spread Weather2 already applies.
        float factor = Mth.clamp((float) Math.sqrt(Mth.clamp(state.widthMultiplier(), 0.25F, 64.0F)), 0.85F, 1.6F);
        scales.keySet().removeIf(particle -> particle == null || !particle.isAlive());
        for (EntityRotFX particle : particles) {
            if (particle == null || !particle.isAlive()) {
                continue;
            }
            float current = particle.getScale();
            float[] record = scales.get(particle);
            // If the particle's scale is not the one we last wrote, Weather2 has just created it (or set
            // it for its own reasons), so that value is its base. Re-reading on mismatch is what keeps the
            // width from compounding into a runaway with every tick.
            float base = record == null || Math.abs(current - record[0] * record[1]) > 0.02F ? current : record[0];
            float scaled = Mth.clamp(base * factor, 0.05F, 80.0F);
            if (Math.abs(scaled - current) > 0.001F) {
                particle.setScale(scaled);
            }
            record = record == null ? new float[2] : record;
            record[0] = base;
            record[1] = factor;
            scales.put(particle, record);
        }
    }

    /**
     * Revives Weather2's own 1.12.2 tornado funnel.
     *
     * <p>The old funnel was never removed from the port - it is still compiled into 2.8.6 and still in
     * this very method, the entire block that fills {@code listParticlesFunnel} with cloud256 fog puffs
     * and then spins and orients them around the base. It is simply unreachable: the port wrapped it in
     * {@code if (!tornadoV2)} with a local {@code boolean tornadoV2 = true}, and put the layer funnel
     * ({@code TornadoFunnelSimple}) in its place. So the field of giant soft puffs that <em>was</em> the
     * 1.12.2 tornado cannot spawn at all, which is why no amount of standing this mod's own storm layer
     * down ever brought the old tornado back - the thing we were uncovering was the replacement.
     *
     * <p>This flips that flag for the classic look, so the tornado that gets drawn is Weather2's own
     * 1.12.2 funnel, running Weather2's own already-compiled code rather than a reimplementation of it.
     *
     * <p>Targeted by the local's name, which the dependency ships a LocalVariableTable for (slot 13,
     * {@code Z}). A renamed or removed flag fails the injection loudly at load instead of quietly
     * leaving the old funnel off - the failure this line should have.
     *
     * <p>Tropical systems are deliberately exempt. Weather2's own condition for this block is
     * {@code isTornadoFormingOrGreater() || attrib_waterSpout}, and that tag is exactly what put a
     * tornado funnel in the middle of a hurricane before - reviving the block on that condition alone
     * would bring the bug back. A real waterspout is not a tropical system by this test, so it keeps
     * its classic funnel; a hurricane keeps its shield and no point funnel.
     */
    @ModifyVariable(method = "tickClient", at = @At(value = "STORE"), name = "tornadoV2", remap = false)
    private boolean expandedweather2dynamics$reviveClassicFunnel(boolean tornadoV2) {
        if (!Config.weather2OwnScatter()) {
            return tornadoV2;
        }
        // In classic mode this is the only funnel on screen. In the combined look it is the foot of the layered
        // one, which is why the layered funnel is not stood down there - the two together are the point.
        return DynamicStormController.isTropicalSystem((StormObject) (Object) this);
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
