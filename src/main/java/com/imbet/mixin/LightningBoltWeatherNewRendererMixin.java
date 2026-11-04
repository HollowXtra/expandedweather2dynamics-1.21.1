package com.imbet.mixin;

import com.imbet.client.LightningRevamp;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import weather2.client.entity.render.LightningBoltWeatherNewRenderer;
import weather2.weathersystem.storm.LightningBoltWeatherNew;

/**
 * The same stand-down for Weather2's own bolt renderer.
 *
 * <p>In practice this is the one that matters: Weather2 strikes the lightning in its storms with its
 * own entity, so its renderer is what a player sees. It builds the channel the way vanilla does,
 * from the strike's seed and the entity's position, and shades it through vanilla's lightning
 * program - which is why one replacement covers both bolts and why both have to be stood down, or
 * the revamped channel would be doubled by the old one.
 */
@Mixin(value = LightningBoltWeatherNewRenderer.class, remap = false)
public abstract class LightningBoltWeatherNewRendererMixin {
    @Inject(
            method = "render(Lweather2/weathersystem/storm/LightningBoltWeatherNew;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void expandedweather2dynamics$revampedBolt(LightningBoltWeatherNew bolt, float yaw, float partialTick,
                                                       PoseStack pose, MultiBufferSource buffer, int light,
                                                       CallbackInfo callback) {
        if (LightningRevamp.replaces()) {
            callback.cancel();
        }
    }
}
