package com.imbet.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;

/**
 * The shared look of the new instrument readouts.
 *
 * <p>Every instrument panel is drawn on the block's top face and read from above, so they all share
 * one plate, one text scale and one set of row positions. Keeping that in one place is what stops the
 * barometer, the rain gauge and the probe from drifting apart visually, and it means the readouts sit
 * at the same height and size as the SPC risk panel beside them.
 *
 * <p>Text is drawn through {@code Font.drawInBatch} with the pose rotated onto the top face, which is
 * the same technique the SPC panel uses; the plate itself goes through the instrument panel shader so
 * it composites correctly with translucent geometry.
 *
 * <p>A caller must draw everything that goes through the plate's render type <em>before</em> it draws
 * any text, and that is a hard requirement rather than a preference: a consumer taken from a
 * {@code MultiBufferSource} is ended the moment the source is asked for a different render type, which
 * the font does on its first call, and an ended buffer rejects the next vertex with
 * {@code IllegalStateException: Not building!}. Every renderer here follows that order - plate, then
 * bars, then rows - and a caller that wants a bar underneath a label it has already drawn has to move
 * the bar up rather than draw it there.
 */
final class InstrumentPanel {
    /** The plane every panel is drawn on, just above the block's top face. */
    static final float PANEL_Y = 1.016F;
    static final float TEXT_SCALE = 0.008F;
    /** Left edge and the seven row heights, top to bottom. */
    static final float TEXT_X = 0.100F;
    static final float[] ROWS = {0.085F, 0.215F, 0.345F, 0.475F, 0.605F, 0.735F, 0.865F};

    private static final int PLATE = 0xD0060B0D;
    private static final int BACKDROP = 0x66101618;

    private InstrumentPanel() {
    }

    /** The dark plate every panel sits on, with a border in the instrument's own colour. */
    static void plate(VertexConsumer consumer, PoseStack.Pose pose, int accent) {
        RenderQuads.topQuadUv(consumer, pose, 0.030F, 0.030F, 0.970F, 0.970F, PANEL_Y, BACKDROP);
        RenderQuads.topQuadUv(consumer, pose, 0.058F, 0.058F, 0.942F, 0.942F, PANEL_Y + 0.002F, PLATE);

        int border = withAlpha(accent, 0xB0);
        RenderQuads.topLineUv(consumer, pose, 0.058F, 0.058F, 0.942F, 0.058F, 0.0035F, PANEL_Y + 0.005F, border);
        RenderQuads.topLineUv(consumer, pose, 0.058F, 0.942F, 0.942F, 0.942F, 0.0035F, PANEL_Y + 0.005F, border);
        RenderQuads.topLineUv(consumer, pose, 0.058F, 0.058F, 0.058F, 0.942F, 0.0035F, PANEL_Y + 0.005F, border);
        RenderQuads.topLineUv(consumer, pose, 0.942F, 0.058F, 0.942F, 0.942F, 0.0035F, PANEL_Y + 0.005F, border);
    }

    /** A horizontal bar, empty track included, so a small value still shows a track to read against. */
    static void bar(VertexConsumer consumer, PoseStack.Pose pose, float rowZ, float fraction, int color) {
        float minX = 0.100F;
        float maxX = 0.900F;
        RenderQuads.topQuadUv(consumer, pose, minX, rowZ, maxX, rowZ + 0.038F, PANEL_Y + 0.003F, 0x38FFFFFF);
        float filled = minX + (maxX - minX) * Math.max(0.0F, Math.min(1.0F, fraction));
        if (filled > minX) {
            RenderQuads.topQuadUv(consumer, pose, minX, rowZ, filled, rowZ + 0.038F, PANEL_Y + 0.005F, withAlpha(color, 0xDC));
        }
    }

    /**
     * How many characters fit across the plate at normal size.
     *
     * <p>The plate is 0.84 world units wide and text is drawn at 0.008, so the usable budget is a
     * little over a hundred pixels of glyph space - roughly seventeen characters at this font's
     * average advance. Every row goes through here rather than trusting each renderer to have counted,
     * because a row that overruns the plate runs off the block face and across the neighbour's.
     */
    private static final int FULL_WIDTH_CHARACTERS = 17;

    /** One row of panel text, positioned by index into {@link #ROWS}, trimmed to the plate. */
    static void text(PoseStack stack, MultiBufferSource buffer, int packedLight, int row, String value, int color) {
        text(stack, buffer, packedLight, row, value, color, 1.0F);
    }

    /** One row at a multiple of the normal text size, trimmed to the space that size leaves. */
    static void text(PoseStack stack, MultiBufferSource buffer, int packedLight, int row, String value, int color, float scale) {
        int budget = Math.max(4, (int) (FULL_WIDTH_CHARACTERS / Math.max(0.1F, scale)));
        textScaled(stack, buffer, packedLight, TEXT_X,
                ROWS[Math.max(0, Math.min(ROWS.length - 1, row))], fit(value, budget), color, scale);
    }

    static void textAt(PoseStack stack, MultiBufferSource buffer, int packedLight, float x, float z, String value, int color) {
        textScaled(stack, buffer, packedLight, x, z, value, color, 1.0F);
    }

    /** A single row at a multiple of the normal text size, for the one number that matters most. */
    static void textScaled(PoseStack stack, MultiBufferSource buffer, int packedLight, float x, float z,
                          String value, int color, float scale) {
        if (value == null || value.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        stack.pushPose();
        stack.translate(x, PANEL_Y + 0.010F, z);
        stack.mulPose(Axis.XP.rotationDegrees(90.0F));
        stack.scale(TEXT_SCALE * scale, TEXT_SCALE * scale, TEXT_SCALE * scale);
        Matrix4f matrix = stack.last().pose();
        font.drawInBatch(value, 0.0F, 0.0F, color, false, matrix, buffer, Font.DisplayMode.POLYGON_OFFSET, 0, packedLight);
        stack.popPose();
    }

    /** Trims a string to a character budget, because the font width is predictable enough here. */
    static String fit(String value, int maxCharacters) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxCharacters ? value : value.substring(0, Math.max(0, maxCharacters - 1)) + ".";
    }

    /** The plate's character budget, for a renderer that wants to trim before composing a row. */
    static String fitToPlate(String value) {
        return fit(value, FULL_WIDTH_CHARACTERS);
    }

    static int withAlpha(int argb, int alpha) {
        return alpha << 24 | argb & 0xFFFFFF;
    }

    /** Dark text on a bright accent, light text on a dark one. */
    static int readableOn(int argb) {
        int r = argb >> 16 & 0xFF;
        int g = argb >> 8 & 0xFF;
        int b = argb & 0xFF;
        double luminance = (0.299D * r + 0.587D * g + 0.114D * b) / 255.0D;
        return luminance > 0.62D ? 0xFF0B0F12 : 0xFFFFFFFF;
    }

    /**
     * The four cardinal ticks on a block's top face, so a vane has something to be read against.
     *
     * <p>Drawn without UVs, which is what lets it go through the plain colour render type the vane
     * uses rather than the panel shader the readouts use.
     */
    static void compassRose(VertexConsumer consumer, PoseStack.Pose pose, int accent, int strong) {
        float center = 0.5F;
        float inner = 0.150F;
        float outer = 0.205F;
        for (int index = 0; index < 4; index++) {
            double angle = Math.PI * 0.5D * index;
            float dx = (float) Math.sin(angle);
            float dz = (float) -Math.cos(angle);
            int color = index == 0 ? strong : withAlpha(accent, 0x9C);
            RenderQuads.topLine(consumer, pose,
                    center + dx * inner, center + dz * inner,
                    center + dx * outer, center + dz * outer,
                    0.0060F, PANEL_Y + 0.004F, color);
        }
    }
}
