package com.imbet.mixin;

import com.imbet.Config;
import com.imbet.client.ClassicFunnelScale;
import com.imbet.client.StormSwirlMirror;
import com.imbet.weather.DynamicStormController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import weather2.weathersystem.storm.StormObject;

/**
 * The client half of this mod's hooks into Weather2's storm.
 *
 * <p>{@code tickClient} and {@code tickRender} are client-only in Weather2, so a dedicated server strips them
 * from {@link StormObject} before any mixin sees the class. Hooks into them sit here, in the client mixin list,
 * because in {@code StormObjectMixin} - which servers apply too - they found no method to attach to and, being
 * required, stopped every dedicated server from starting.
 */
@Mixin(value = StormObject.class, remap = false)
public abstract class StormObjectClientMixin {

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
        expandedweather2dynamics$classicFunnelScales = ClassicFunnelScale.apply(storm, expandedweather2dynamics$classicFunnelScales);
    }

    /**
     * What {@link ClassicFunnelScale} remembers about this storm's funnel particles between ticks. Held as a
     * plain {@code Object} so this mixin, which dedicated servers apply too, never names a client-only class.
     */
    @Unique
    private Object expandedweather2dynamics$classicFunnelScales;

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
}
