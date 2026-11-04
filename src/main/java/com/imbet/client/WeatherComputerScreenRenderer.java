package com.imbet.client;

import com.imbet.spc.WeatherComputerBlock;
import com.imbet.spc.WeatherComputerBlockEntity;
import com.imbet.weather.SpcHazard;
import com.imbet.weather.SpcRisk;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

import java.util.Locale;

/**
 * The monitor of the weather computer, showing the day's SPC outlook.
 *
 * <p>The screen is the block model's own glass: the model has a recessed plate whose south face sits at
 * z = 7/16, with the bezel in front of it at 7.5/16, and everything drawn here lands on the planes between
 * those two numbers. The model is authored with the screen on its south face, so this renderer turns the
 * whole thing by the same quarter turns the blockstate does, and what is left is a frame in which the
 * screen lies flat in front of the reader with x running right and y up.
 *
 * <p>That frame is the reason the layout below can be written in the same "across, and down from the top"
 * terms the flat SPC panel uses: the text is drawn with a negative Y scale, which is what turns the font's
 * own y-down convention into up, and the reader is then standing in front of the monitor looking at it.
 *
 * <p>The quads go through {@code debugQuads}, the colour-only no-cull type the rest of this mod's
 * instrument hardware uses, so the screen is exactly the colours asked for and stays legible under a
 * shader pack - these are emissive pixels, not lit geometry.
 */
public class WeatherComputerScreenRenderer implements BlockEntityRenderer<WeatherComputerBlockEntity> {
    /** The screen opening in block-local coordinates, taken from the model's four bezel bars. */
    private static final float SCREEN_X0 = 2.0F / 16.0F;
    private static final float SCREEN_X1 = 14.0F / 16.0F;
    private static final float SCREEN_Y0 = 5.0F / 16.0F;
    private static final float SCREEN_Y1 = 14.0F / 16.0F;

    /** Depth planes: in front of the model's glass at 7/16, behind its bezel at 7.5/16. */
    private static final float BACKDROP_Z = 7.05F / 16.0F;
    private static final float PANEL_Z = 7.20F / 16.0F;
    private static final float FILL_Z = 7.30F / 16.0F;
    private static final float TEXT_Z = 7.40F / 16.0F;

    /** Height of one font line in the world, and how far a glyph rises above the baseline. */
    private static final float TEXT_SCALE = 0.0055F;
    private static final float GLYPH_ASCENT = 7.0F;

    /** Layout, in screen units: u across, v down from the top of the opening. */
    private static final float INSET = 0.05F;
    private static final float HEADER_V = 0.035F;
    private static final float RULE_V = 0.150F;
    private static final float BAND_TOP = 0.200F;
    private static final float BAND_BOTTOM = 0.450F;
    private static final float[] ROW_V = {0.530F, 0.690F, 0.850F};
    private static final float BAR_HEIGHT = 0.050F;
    private static final float BAR_OFFSET = 0.055F;

    private static final int HEADER_INK = 0xFF8FE0D4;
    private static final int DIM_INK = 0xFF5E827E;
    private static final int TRACK_INK = 0xFF16211F;
    private static final int BAND_INK = 0xFF0D1417;
    private static final int STANDBY_INK = 0xFF6E7A78;
    private static final int STANDBY_BAND = 0x50222A2A;
    private static final int RULE_INK = 0x4A2A3A38;

    /** The hazard rows, in the order the block entity's modes number them. */
    private static final SpcHazard[] HAZARDS = {SpcHazard.TORNADO, SpcHazard.WIND, SpcHazard.HAIL};

    public WeatherComputerScreenRenderer(BlockEntityRendererProvider.Context context) {
    }

    /**
     * The monitor, drawn in two passes: the plate and its bars through the quad render type, then every line
     * of text through the font's.
     *
     * <p>They cannot be interleaved, and that is not a stylistic choice. A consumer taken from a
     * {@code MultiBufferSource} dies as soon as anything asks that source for a <em>different</em> render
     * type: {@code BufferSource.getBuffer} ends the batch it is holding first, and the ended buffer refuses
     * the next vertex with {@code IllegalStateException: Not building!}. The font asks for the text type, so
     * a cursor held across a {@code drawInBatch} call is already dead when it is used again - which is a
     * hard crash in the middle of a frame, and which is exactly how the balloon envelope used to crash.
     * Hence: fetch, draw every quad that belongs to this pass, and only then draw text.
     */
    @Override
    public void render(WeatherComputerBlockEntity computer, float partialTick, PoseStack stack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        Direction facing = computer.getBlockState().getValue(WeatherComputerBlock.FACING);

        stack.pushPose();
        // Into the model's own frame: the blockstate gives this model y = 0 for facing=south, and this is
        // that same turn, so what follows lines up with the bezel drawn by the block itself.
        stack.translate(0.5F, 0.5F, 0.5F);
        stack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        stack.translate(-0.5F, -0.5F, -0.5F);
        PoseStack.Pose pose = stack.last();

        SpcRisk risk = computer.currentRisk();
        boolean active = risk.severity() > SpcRisk.TSTM.severity();
        String issuance = computer.issuance();
        String headline = active ? headline(computer) : "NO ACTIVE RISK";
        String activity = computer.stormCount() + " STM / " + computer.severeCount() + " SEV";

        int[] probabilities = {computer.tornadoProbability(), computer.windProbability(), computer.hailProbability()};
        int[] cigs = {computer.tornadoCig(), computer.windCig(), computer.hailCig()};
        String[] readings = new String[HAZARDS.length];
        for (int index = 0; index < HAZARDS.length; index++) {
            readings[index] = probabilityText(HAZARDS[index], probabilities[index], cigs[index]);
        }

        // Pass one, the plate: one fetch, every quad that goes through it, no text in between.
        VertexConsumer consumer = buffer.getBuffer(RenderType.debugQuads());

        // The glass. An off day is a standby screen: darker, and with the band left grey.
        rect(consumer, pose, 0.0F, 0.0F, 1.0F, 1.0F, BACKDROP_Z, active ? 0xFF06100F : 0xFF080B0B);
        rect(consumer, pose, INSET, RULE_V, 1.0F - INSET, RULE_V + 0.006F, PANEL_Z, RULE_INK);

        // The categorical band: the risk this computer was switched to, in its own colour. This is the
        // "presence" the block exists to show, so when nothing is issued the band does not light up at all.
        rect(consumer, pose, INSET, BAND_TOP, 1.0F - INSET, BAND_BOTTOM, PANEL_Z,
                active ? withAlpha(risk.color(), 0xE4) : STANDBY_BAND);

        for (int index = 0; index < HAZARDS.length; index++) {
            SpcHazard hazard = HAZARDS[index];
            float barTop = ROW_V[index] + BAR_OFFSET;
            rect(consumer, pose, INSET, barTop, 1.0F - INSET, barTop + BAR_HEIGHT, PANEL_Z, TRACK_INK);
            if (probabilities[index] > 0) {
                float fraction = (float) Mth.clamp(probabilities[index] / (double) hazard.probabilityBarScale(), 0.06D, 1.0D);
                float track = (1.0F - INSET) - INSET;
                rect(consumer, pose, INSET, barTop, INSET + track * fraction, barTop + BAR_HEIGHT, FILL_Z,
                        withAlpha(hazard.barColor(), 0xF0));
            }
        }

        // Pass two, the text. These run in the font's own render type and are drawn after the plate, which
        // is also what puts them in front of it.
        text(stack, buffer, INSET, HEADER_V, "SPC OUTLOOK", active ? HEADER_INK : STANDBY_INK);
        text(stack, buffer, 1.0F - INSET - width(issuance), HEADER_V, issuance, DIM_INK);
        text(stack, buffer, INSET + 0.03F, BAND_TOP + 0.055F, headline, active ? BAND_INK : STANDBY_INK);
        text(stack, buffer, 1.0F - INSET - 0.03F - width(activity), BAND_TOP + 0.055F, activity,
                active ? BAND_INK : DIM_INK);
        for (int index = 0; index < HAZARDS.length; index++) {
            SpcHazard hazard = HAZARDS[index];
            float rowV = ROW_V[index];
            boolean selected = computer.getMode() == index + 1;
            text(stack, buffer, INSET, rowV, hazard.abbreviation(), selected ? 0xFFFFFFFF : 0xFFD6E2E0);
            text(stack, buffer, 1.0F - INSET - width(readings[index]), rowV, readings[index],
                    probabilities[index] > 0 ? 0xFFFFFFFF : DIM_INK);
        }

        stack.popPose();
    }

    /**
     * What the band says: the category the day is carrying in the categorical view, or the hazard the
     * computer was switched to with the number that goes with it.
     */
    private static String headline(WeatherComputerBlockEntity computer) {
        SpcRisk risk = computer.currentRisk();
        if (computer.getMode() == 0) {
            return risk.label() + " RISK";
        }
        SpcHazard hazard = HAZARDS[computer.getMode() - 1];
        int probability = computer.probabilityForMode();
        int cig = computer.cigForMode();
        return hazard.displayName().toUpperCase(Locale.ROOT) + " " + probability + "%" + (cig > 0 ? " C" + cig : "");
    }

    private static String probabilityText(SpcHazard hazard, int probability, int cig) {
        if (probability <= 0) {
            return "<" + hazard.lowestProbability() + "%";
        }
        return probability + "%" + (cig > 0 ? " C" + cig : "");
    }

    /** A filled rectangle in screen units, u across and v down. */
    private static void rect(VertexConsumer consumer, PoseStack.Pose pose, float u0, float v0, float u1, float v1, float z, int color) {
        RenderQuads.faceQuadUv(consumer, pose, x(u0), y(v1), x(u1), y(v0), z, color);
    }

    /**
     * One line of text, top-left anchored at (u, v) in screen units.
     *
     * <p>The negative Y scale is what makes the font, whose own y grows downward in its atlas frame, come
     * out upright on a face the reader is standing in front of.
     */
    private static void text(PoseStack stack, MultiBufferSource buffer, float u, float v, String value, int color) {
        if (value == null || value.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        stack.pushPose();
        stack.translate(x(u), y(v) - GLYPH_ASCENT * TEXT_SCALE, TEXT_Z);
        stack.scale(TEXT_SCALE, -TEXT_SCALE, TEXT_SCALE);
        font.drawInBatch(value, 0.0F, 0.0F, color, false, stack.last().pose(), buffer,
                Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
        stack.popPose();
    }

    /** The width a line will occupy on the screen, so a line can be set against the right-hand edge. */
    private static float width(String value) {
        return Minecraft.getInstance().font.width(value) * TEXT_SCALE;
    }

    private static float x(float u) {
        return SCREEN_X0 + u * (SCREEN_X1 - SCREEN_X0);
    }

    private static float y(float v) {
        return SCREEN_Y1 - v * (SCREEN_Y1 - SCREEN_Y0);
    }

    private static int withAlpha(int argb, int alpha) {
        return alpha << 24 | argb & 0xFFFFFF;
    }

    @Override
    public boolean shouldRenderOffScreen(WeatherComputerBlockEntity blockEntity) {
        return true;
    }
}
