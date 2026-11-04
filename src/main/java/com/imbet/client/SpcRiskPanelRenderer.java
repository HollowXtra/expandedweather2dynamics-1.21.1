package com.imbet.client;

import com.imbet.spc.SpcRiskBlockEntity;
import com.imbet.weather.SpcHazard;
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

import java.util.Locale;

/**
 * The SPC risk panel, laid out as a real convective outlook product rather than a row of colours.
 *
 * <p>Top to bottom: the issuance line (which update of the day this is), the categorical scale with
 * the current category lit, one row per hazard carrying its issued probability contour, a probability
 * bar scaled to the hazard's own contour ladder, and CIG hatch marks when the environment supports
 * significant severe weather. The hazard rows are exactly the numbers the category above them was
 * derived from, because both come from the same SPC decision matrix on the server.
 *
 * <p>The scale carries the two extension tiers past high risk, so a day that escalates onto them lights
 * a swatch that is genuinely above the published ladder rather than re-lighting high.
 *
 * <p>Modes cycle categorical, tornado, wind and hail; a hazard mode rings its row so the panel can be
 * left on one hazard and wired to redstone.
 */
public class SpcRiskPanelRenderer implements BlockEntityRenderer<SpcRiskBlockEntity> {
    private static final SpcRisk[] SCALE = SpcRisk.scale();
    private static final float PANEL_Y = 1.016F;
    private static final float TEXT_SCALE = 0.008F;
    /** Vertical layout of the three hazard rows, top to bottom. */
    private static final float[] ROW_Z = {0.50F, 0.615F, 0.73F};

    public SpcRiskPanelRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(SpcRiskBlockEntity spc, float partialTick, PoseStack stack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        VertexConsumer consumer = PanelRenderPath.consumer(buffer, InstrumentPanelShader.panel(), packedLight);
        PoseStack.Pose pose = stack.last();

        // Panel backdrop, then a dark plate the header text sits on.
        RenderQuads.topQuadUv(consumer, pose, 0.045F, 0.045F, 0.955F, 0.955F, PANEL_Y, 0x66101618);
        RenderQuads.topQuadUv(consumer, pose, 0.075F, 0.060F, 0.925F, 0.245F, PANEL_Y + 0.002F, 0xD0060B0D);

        drawCategoryScale(consumer, pose, spc);
        drawHazardRows(consumer, pose, spc);
        drawText(spc, stack, buffer, packedLight);
    }

    /** The full ladder, published categories and extension tiers, current one brightened and underlined. */
    private static void drawCategoryScale(VertexConsumer consumer, PoseStack.Pose pose, SpcRiskBlockEntity spc) {
        SpcRisk current = spc.currentRisk();
        float startX = 0.09F;
        float total = 0.82F;
        float width = total / SCALE.length;
        for (int index = 0; index < SCALE.length; index++) {
            SpcRisk risk = SCALE[index];
            float minX = startX + index * width;
            float maxX = minX + width * 0.88F;
            boolean active = risk == current;
            RenderQuads.topQuadUv(consumer, pose, minX, 0.27F, maxX, 0.40F, PANEL_Y + 0.003F,
                    withAlpha(risk.color(), active ? 0xEE : 0x96));
            if (active) {
                RenderQuads.topLineUv(consumer, pose, minX - 0.004F, 0.425F, maxX + 0.004F, 0.425F, 0.008F, PANEL_Y + 0.006F, 0xFFFFFFFF);
            }
        }
    }

    /**
     * One row per hazard: abbreviation, probability bar scaled to the hazard's own ladder, the issued
     * probability, and CIG hatching. The row in the panel's current mode is boxed.
     */
    private static void drawHazardRows(VertexConsumer consumer, PoseStack.Pose pose, SpcRiskBlockEntity spc) {
        SpcHazard[] hazards = {SpcHazard.TORNADO, SpcHazard.WIND, SpcHazard.HAIL};
        int[] probabilities = {spc.tornadoProbability(), spc.windProbability(), spc.hailProbability()};
        int[] cigs = {spc.tornadoCig(), spc.windCig(), spc.hailCig()};
        for (int index = 0; index < hazards.length; index++) {
            SpcHazard hazard = hazards[index];
            float rowZ = ROW_Z[index];
            boolean selected = spc.getMode() == index + 1;
            if (selected) {
                RenderQuads.topQuadUv(consumer, pose, 0.085F, rowZ - 0.026F, 0.915F, rowZ + 0.062F, PANEL_Y + 0.002F, 0x80060B0D);
            }

            int cig = cigs[index];
            // CIG hatching between the label and the bar: one diagonal per conditional intensity
            // level, spaced so all five the extended ladder can issue still stop short of the bar.
            for (int mark = 0; mark < cig; mark++) {
                float x = 0.212F + mark * 0.030F;
                RenderQuads.topLineUv(consumer, pose, x, rowZ + 0.040F, x + 0.015F, rowZ, 0.0040F, PANEL_Y + 0.006F, 0xFFFFFFFF);
            }

            int probability = probabilities[index];
            float fraction = probability <= 0 ? 0.0F
                    : (float) Math.max(0.06D, Math.min(1.0D, probability / (double) hazard.probabilityBarScale()));
            float barMinX = 0.36F;
            float barMaxX = barMinX + 0.26F * fraction;
            if (probability > 0) {
                RenderQuads.topQuadUv(consumer, pose, barMinX, rowZ, barMaxX, rowZ + 0.042F, PANEL_Y + 0.004F, withAlpha(hazard.barColor(), 0xDC));
            } else {
                RenderQuads.topLineUv(consumer, pose, barMinX, rowZ + 0.021F, barMinX + 0.055F, rowZ + 0.021F, 0.0035F, PANEL_Y + 0.004F, 0x60FFFFFF);
            }
        }
    }

    private static void drawText(SpcRiskBlockEntity spc, PoseStack stack, MultiBufferSource buffer, int packedLight) {
        SpcRisk risk = spc.currentRisk();
        text(stack, buffer, packedLight, 0.105F, 0.085F, "SPC " + spc.issuance(), 0xE8F8FFFF);
        text(stack, buffer, packedLight, 0.105F, 0.163F, categoryPhrase(risk), risk.color());

        SpcHazard[] hazards = {SpcHazard.TORNADO, SpcHazard.WIND, SpcHazard.HAIL};
        int[] probabilities = {spc.tornadoProbability(), spc.windProbability(), spc.hailProbability()};
        int[] cigs = {spc.tornadoCig(), spc.windCig(), spc.hailCig()};
        boolean categoricalMode = spc.getMode() == 0;
        text(stack, buffer, packedLight, 0.105F, 0.412F,
                categoricalMode ? "CATEGORICAL VIEW" : hazards[spc.getMode() - 1].displayName().toUpperCase(Locale.ROOT) + " VIEW",
                0xFFCFD8DC);
        for (int index = 0; index < hazards.length; index++) {
            SpcHazard hazard = hazards[index];
            float rowZ = ROW_Z[index] - 0.004F;
            boolean selected = spc.getMode() == index + 1;
            text(stack, buffer, packedLight, 0.105F, rowZ, hazard.abbreviation(), selected ? 0xFFFFFFFF : 0xFFE0E0E0);
            text(stack, buffer, packedLight, 0.645F, rowZ, probabilityText(hazard, probabilities[index], cigs[index]),
                    probabilities[index] > 0 ? 0xFFFFFFFF : 0xFF808A8E);
        }

        if (categoricalMode) {
            text(stack, buffer, packedLight, 0.105F, 0.812F, truncate(spc.hazard(), 17), 0xFFB0BEC5);
        } else {
            SpcHazard hazard = hazards[spc.getMode() - 1];
            int cig = cigs[spc.getMode() - 1];
            text(stack, buffer, packedLight, 0.105F, 0.812F,
                    hazard.displayName().toUpperCase(Locale.ROOT) + (cig > 0 ? " CIG" + cig + " HATCHED" : " NO CIG"), 0xFFB0BEC5);
        }
        text(stack, buffer, packedLight, 0.105F, 0.888F,
                spc.stormCount() + " STORM / " + spc.severeCount() + " SEV", 0xFF90A4AE);
    }

    private static String probabilityText(SpcHazard hazard, int probability, int cig) {
        if (probability <= 0) {
            return "<" + hazard.lowestProbability() + "%";
        }
        return probability + "%" + (cig > 0 ? " C" + cig : "");
    }

    private static String categoryPhrase(SpcRisk risk) {
        return risk.severity() <= SpcRisk.TSTM.severity() ? "GENERAL THUNDER" : risk.label() + " RISK";
    }

    private static String truncate(String value, int maxLength) {
        String safe = value == null || value.isBlank() ? "No dominant hazard" : value;
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength - 1) + ".";
    }

    private static void text(PoseStack stack, MultiBufferSource buffer, int packedLight, float x, float z, String value, int color) {
        Font font = Minecraft.getInstance().font;
        stack.pushPose();
        stack.translate(x, PANEL_Y + 0.010F, z);
        stack.mulPose(Axis.XP.rotationDegrees(90.0F));
        stack.scale(TEXT_SCALE, TEXT_SCALE, TEXT_SCALE);
        Matrix4f matrix = stack.last().pose();
        font.drawInBatch(value, 0.0F, 0.0F, color, false, matrix, buffer, Font.DisplayMode.POLYGON_OFFSET, 0, packedLight);
        stack.popPose();
    }

    private static int withAlpha(int argb, int alpha) {
        return alpha << 24 | argb & 0xFFFFFF;
    }

    @Override
    public boolean shouldRenderOffScreen(SpcRiskBlockEntity blockEntity) {
        return true;
    }
}
