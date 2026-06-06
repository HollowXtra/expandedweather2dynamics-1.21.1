package com.imbet.mixin;

import extendedrenderer.particle.entity.EntityRotFX;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = EntityRotFX.class, remap = false)
public abstract class EntityRotFXPauseMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void expandedweather2dynamics$freezeWeatherParticlesWhilePaused(CallbackInfo ci) {
        EntityRotFX particle = (EntityRotFX) (Object) this;
        if (particle.weatherEffect && Minecraft.getInstance().isPaused()) {
            ci.cancel();
        }
    }
}
