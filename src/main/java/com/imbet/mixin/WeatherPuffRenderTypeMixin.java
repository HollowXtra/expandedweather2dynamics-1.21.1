package com.imbet.mixin;

import com.imbet.client.SoftPuffs;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.Tesselator;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The start of Weather2's translucent puff pass, which is where they stop being drawn as small sprites.
 *
 * <p>The target is {@code EntityRotFX.SORTED_TRANSLUCENT}, an anonymous class, so it is named by string. Its partner
 * {@code SORTED_OPAQUE_BLOCK}, which draws flying blocks, is the other anonymous class and is left alone.
 */
@Mixin(targets = "extendedrenderer.particle.entity.EntityRotFX$1", remap = false)
public abstract class WeatherPuffRenderTypeMixin {
    @Inject(method = "begin", at = @At("RETURN"))
    private void expandedweather2dynamics$drawSoft(Tesselator tesselator, TextureManager textureManager, CallbackInfoReturnable<BufferBuilder> cir) {
        SoftPuffs.begin();
    }
}
