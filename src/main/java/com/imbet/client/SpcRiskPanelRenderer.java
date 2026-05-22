package com.imbet.client;

import com.imbet.spc.SpcRiskBlockEntity;
import com.imbet.weather.SpcRisk;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import org.joml.Matrix4f;

public class SpcRiskPanelRenderer implements BlockEntityRenderer<SpcRiskBlockEntity> {
    public SpcRiskPanelRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(SpcRiskBlockEntity spc, float partialTick, PoseStack stack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        VertexConsumer consumer = buffer.getBuffer(RadarShader.radarPanel());
        PoseStack.Pose pose = stack.last();
        float y = 1.016F;
        RenderQuads.topQuadUv(consumer, pose, 0.08F, 0.08F, 0.92F, 0.92F, y, 0x66101618);
        RenderQuads.topQuadUv(consumer, pose, 0.13F, 0.16F, 0.87F, 0.44F, y + 0.002F, 0xAA071012);

        SpcRisk[] risks = {SpcRisk.TSTM, SpcRisk.MARGINAL, SpcRisk.SLIGHT, SpcRisk.ENHANCED, SpcRisk.MODERATE, SpcRisk.HIGH};
        float startX = 0.15F;
        float width = 0.70F / risks.length;
        for (int i = 0; i < risks.length; i++) {
            float minX = startX + i * width;
            float maxX = minX + width * 0.86F;
            int color = withAlpha(risks[i].color(), risks[i] == spc.currentRisk() ? 238 : 154);
            RenderQuads.topQuadUv(consumer, pose, minX, 0.50F, maxX, 0.79F, y + 0.004F, color);
            if (risks[i] == spc.currentRisk()) {
                RenderQuads.topLineUv(consumer, pose, minX, 0.82F, maxX, 0.82F, 0.009F, y + 0.007F, 0xEFFFFFFF);
            }
        }

        drawText(spc, stack, buffer, packedLight);
    }

    private static void drawText(SpcRiskBlockEntity spc, PoseStack stack, MultiBufferSource buffer, int packedLight) {
        Font font = Minecraft.getInstance().font;
        stack.pushPose();
        stack.translate(0.17F, 1.026F, 0.22F);
        stack.mulPose(Axis.XP.rotationDegrees(90.0F));
        stack.scale(0.010F, 0.010F, 0.010F);
        Matrix4f matrix = stack.last().pose();
        String title = "SPC " + shortMode(spc.getMode());
        String risk = spc.currentRisk().label();
        font.drawInBatch(title, 0.0F, 0.0F, 0xE8F8FFFF, false, matrix, buffer, Font.DisplayMode.POLYGON_OFFSET, 0, packedLight);
        font.drawInBatch(risk, 0.0F, 12.0F, 0xFFFFFFFF, false, matrix, buffer, Font.DisplayMode.POLYGON_OFFSET, 0, packedLight);
        stack.popPose();
    }

    private static String shortMode(int mode) {
        return switch (mode) {
            case 1 -> "TOR";
            case 2 -> "WIND";
            case 3 -> "HAIL";
            default -> "CAT";
        };
    }

    private static int withAlpha(int argb, int alpha) {
        return alpha << 24 | argb & 0xFFFFFF;
    }

    @Override
    public boolean shouldRenderOffScreen(SpcRiskBlockEntity blockEntity) {
        return true;
    }
}
