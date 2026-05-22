package com.imbet.client;

import com.imbet.anemometer.AnemometerBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.util.Mth;

public class AnemometerRenderer implements BlockEntityRenderer<AnemometerBlockEntity> {
    public AnemometerRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(AnemometerBlockEntity entity, float partialTick, PoseStack stack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        VertexConsumer consumer = buffer.getBuffer(RenderType.lightning());
        PoseStack.Pose pose = stack.last();
        RenderQuads.quad(consumer, pose, 0.47F, 0.0F, 0.47F, 0.53F, 1.0F, 0.53F, 0xFF9CA3AF);
        float angle = entity.getRenderAngle(partialTick) * Mth.DEG_TO_RAD;
        for (int i = 0; i < 4; i++) {
            float a = angle + i * Mth.HALF_PI;
            float x = 0.5F + Mth.cos(a) * 0.28F;
            float z = 0.5F + Mth.sin(a) * 0.28F;
            RenderQuads.topQuad(consumer, pose, Math.min(0.5F, x) - 0.015F, Math.min(0.5F, z) - 0.015F, Math.max(0.5F, x) + 0.015F, Math.max(0.5F, z) + 0.015F, 1.03F, 0xFFE5E7EB);
            RenderQuads.topQuad(consumer, pose, x - 0.045F, z - 0.045F, x + 0.045F, z + 0.045F, 1.05F, 0xFF7DD3FC);
        }
    }
}
