package com.imbet.client;

import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.weather.DustDevilEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

public class DustDevilRenderer<T extends DustDevilEntity> extends EntityRenderer<T> {
    private static final ResourceLocation EMPTY = new ResourceLocation(ExpandedWeather2Dynamics.MODID, "textures/misc/empty.png");

    public DustDevilRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(T entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        AddonVortexParticleRenderer.tick(entity, partialTick);
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    @Override
    public boolean shouldRender(T entity, Frustum camera, double camX, double camY, double camZ) {
        double renderRange = 288.0D;
        return entity.distanceToSqr(camX, camY, camZ) <= renderRange * renderRange || super.shouldRender(entity, camera, camX, camY, camZ);
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        return EMPTY;
    }
}
