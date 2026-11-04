package com.imbet.client;

import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.weather.DebrisEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws a piece of debris as the block it is.
 *
 * <p>The whole render is the block model, turned by the entity's own yaw and pitch so it tumbles as it
 * flies, which is why this renderer does not use a texture of its own - the surface camera is drawn to
 * an empty texture and the cube carries the block's real texture through the block renderer.</p>
 */
public class DebrisEntityRenderer extends EntityRenderer<DebrisEntity> {
    private static final ResourceLocation EMPTY = ResourceLocation.fromNamespaceAndPath(
            ExpandedWeather2Dynamics.MODID, "textures/misc/empty.png");

    public DebrisEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(DebrisEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        BlockState state = entity.blockState();
        if (state == null || state.isAir()) {
            return;
        }
        poseStack.pushPose();
        // The block model is drawn from a corner, so shift it half a block to centre it on the entity.
        poseStack.translate(-0.5D, -0.5D, -0.5D);
        poseStack.mulPose(Axis.YP.rotationDegrees(-entityYaw));
        poseStack.mulPose(Axis.XP.rotationDegrees(entity.getXRot()));
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                state, poseStack, buffer, packedLight, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    @Override
    public boolean shouldRender(DebrisEntity entity, Frustum camera, double camX, double camY, double camZ) {
        double renderRange = 320.0D;
        return entity.distanceToSqr(camX, camY, camZ) <= renderRange * renderRange
                || super.shouldRender(entity, camera, camX, camY, camZ);
    }

    @Override
    public ResourceLocation getTextureLocation(DebrisEntity entity) {
        return EMPTY;
    }
}
