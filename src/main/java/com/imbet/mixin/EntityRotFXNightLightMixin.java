package com.imbet.mixin;

import com.imbet.client.NightClouds;
import extendedrenderer.particle.entity.EntityRotFX;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lights a storm's particles by less of the sky at night, so a storm is a dark shape in a dark sky rather than a pale
 * one glowing in it. See {@link NightClouds}.
 */
@Mixin(value = EntityRotFX.class, remap = false)
public abstract class EntityRotFXNightLightMixin {
    @Inject(method = "getLightColor", at = @At("RETURN"), cancellable = true)
    private void expandedweather2dynamics$nightLight(float partialTick, CallbackInfoReturnable<Integer> callback) {
        callback.setReturnValue(NightClouds.light(callback.getReturnValueI(), ((EntityRotFX) (Object) this).getPosY()));
    }
}
