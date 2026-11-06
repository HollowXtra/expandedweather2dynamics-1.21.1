package com.imbet.mixin;

import com.imbet.client.LightningRevamp;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LightningBoltRenderer;
import net.minecraft.world.entity.LightningBolt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stands vanilla's bolt renderer down while the mod's own channel is being drawn.
 *
 * <p>Vanilla draws a strike as a chain of constant-width grey ribbons in four passes, and the only
 * thing a bolt's look is decided by is the program that shades them. Replacing the program alone
 * cannot give the channel a taper or a halo, because there is no cross-section in its vertices to
 * hang either on, so the geometry is replaced as well and this renderer stops drawing.
 *
 * <p>The suppression is gated on the same test the replacement pass uses, so nothing here can leave
 * a strike invisible: while the mod's program has not loaded and the bolt is not going through the
 * game's own lightning program instead, the vanilla renderer draws exactly as it always did.
 */
@Mixin(LightningBoltRenderer.class)
public abstract class LightningBoltRendererMixin {
    @Inject(
            method = "render(Lnet/minecraft/world/entity/LightningBolt;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void expandedweather2dynamics$revampedBolt(LightningBolt bolt, float yaw, float partialTick,
                                                       PoseStack pose, MultiBufferSource buffer, int light,
                                                       CallbackInfo callback) {
        if (LightningRevamp.replaces()) {
            callback.cancel();
        }
    }
}
