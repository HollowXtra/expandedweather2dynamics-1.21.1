package com.imbet.mixin;

import com.imbet.client.TornadoFearClient;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the roll (Z) axis to captured entities' rendered models. Living entities have no
 * roll field, so while a player or mob is captured by a tornado core the model pose is
 * rolled around the body's vertical axis at a per-entity randomized rate. With the flip
 * on pitch (server-side for mobs, client-side for the local player) this makes captured
 * entities tumble the way the grabbed block debris does. The yaw is deliberately not
 * touched by any of it - a spinning yaw turns the whole model about its own vertical
 * axis, head and body together, which reads as pirouetting rather than being thrown.
 *
 * Verified against the 1.21.1 official mappings: LivingEntityRenderer.render starts
 * with pushPose, has a single exit, and the dispatcher's pose stack is at the entity's
 * position when render is called - so the HEAD push / RETURN pop is always balanced
 * and the roll pivots around the body's center in world space.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    @Unique
    private boolean expandedweather2dynamics$tumblePosePushed;

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At("HEAD"))
    private void expandedweather2dynamics$pushTumbleRoll(LivingEntity entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
        try {
            float roll = TornadoFearClient.modelRollFor(entity);
            if (roll == 0.0F) {
                expandedweather2dynamics$tumblePosePushed = false;
                return;
            }
            poseStack.pushPose();
            float pivot = Math.max(0.6F, entity.getBbHeight() * 0.52F);
            poseStack.translate(0.0D, pivot, 0.0D);
            poseStack.mulPose(Axis.ZP.rotationDegrees(roll));
            poseStack.translate(0.0D, -pivot, 0.0D);
            expandedweather2dynamics$tumblePosePushed = true;
        } catch (RuntimeException ignored) {
            expandedweather2dynamics$tumblePosePushed = false;
        }
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At("RETURN"))
    private void expandedweather2dynamics$popTumbleRoll(LivingEntity entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
        if (expandedweather2dynamics$tumblePosePushed) {
            poseStack.popPose();
            expandedweather2dynamics$tumblePosePushed = false;
        }
    }
}