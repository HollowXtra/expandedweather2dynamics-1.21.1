package com.imbet.client;

import com.imbet.Config;
import com.imbet.instruments.WeatherVaneBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/**
 * The Weather Vane: a compass rose on the block's top face with a vertical arrow above it.
 *
 * <p>The arrow is built from vertical ribbons rather than the flat top-facing quads the rest of the
 * mod's geometry uses, because a vane has to be readable from the side. A flat ribbon at arrow height
 * would be nearly invisible edge-on from a player standing on the ground.
 *
 * <p>North is the pose's local -Z, so the arrow is drawn pointing north and then rotated by
 * {@code -heading}: compass headings run clockwise (north, east, south, west) while a positive rotation
 * about Y runs the other way, so the sign is inverted to make the arrow agree with the bearing the
 * readout prints. The cardinal tick pointing north is drawn brighter so the rose reads correctly from
 * any direction.
 */
public class WeatherVaneRenderer implements BlockEntityRenderer<WeatherVaneBlockEntity> {
    private static final int FRAME = 0xFF9CA3AF;
    private static final int ACCENT = 0xFF7DD3FC;
    private static final int ARROW = 0xFFF3F4F6;
    private static final int TAIL = 0xFFE5E7EB;

    /** Where the arrow plane sits above the block, and how tall the metalwork is. */
    private static final float HUB_Y = 1.030F;
    private static final float ARROW_Y0 = 1.055F;
    private static final float ARROW_Y1 = 1.150F;
    private static final float FIN_Y0 = 1.010F;
    private static final float FIN_Y1 = 1.200F;

    public WeatherVaneRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(WeatherVaneBlockEntity entity, float partialTick, PoseStack stack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (!Config.WEATHER_VANE_ENABLED.get()) {
            return;
        }
        // debugQuads(), NOT lightning(). Both are colour-only, no-cull, untextured types, but lightning
        // writes into the WEATHER render target - the buffer the rain and lightning bolts go to - so the
        // vane's metalwork was composited with the weather pass instead of the world and read as a flat
        // white shape floating on the block. debugQuads draws the same geometry into the main target with
        // proper depth, which is what the forecast plates already use.
        VertexConsumer consumer = buffer.getBuffer(RenderType.debugQuads());
        PoseStack.Pose pose = stack.last();

        // debugQuads carries no lightmap, so the block's own light level is folded into the colours here.
        // Without it the metalwork glowed at full brightness in the dark, which is what it was doing.
        float shade = RenderQuads.lightShade(entity.getLevel(), packedLight);
        int frame = RenderQuads.lit(FRAME, shade);
        int accent = RenderQuads.lit(ACCENT, shade);
        int arrow = RenderQuads.lit(ARROW, shade);
        int tail = RenderQuads.lit(TAIL, shade);

        // Rose and pivot stay put; only the metalwork above them turns.
        InstrumentPanel.compassRose(consumer, pose, accent, RenderQuads.lit(0xFFFFFFFF, shade));
        RenderQuads.topQuad(consumer, pose, 0.455F, 0.455F, 0.545F, 0.545F, HUB_Y, frame);

        stack.pushPose();
        stack.translate(0.5F, 0.0F, 0.5F);
        stack.mulPose(Axis.YP.rotationDegrees(-entity.getRenderHeading(partialTick)));
        stack.translate(-0.5F, 0.0F, -0.5F);
        PoseStack.Pose rotated = stack.last();

        // Mast, drawn as two crossed ribbons so it reads as a post from any angle.
        RenderQuads.ribbon(consumer, rotated, 0.492F, 0.5F, 0.508F, 0.5F, 0.990F, HUB_Y, frame);
        RenderQuads.ribbon(consumer, rotated, 0.5F, 0.492F, 0.5F, 0.508F, 0.990F, HUB_Y, frame);

        // Arrow: the tip points north in local space, which is the direction the wind comes FROM.
        RenderQuads.ribbon(consumer, rotated, 0.5F, 0.620F, 0.5F, 0.190F, ARROW_Y0, ARROW_Y1, arrow);
        RenderQuads.ribbon(consumer, rotated, 0.5F, 0.145F, 0.408F, 0.290F, ARROW_Y0, ARROW_Y1, arrow);
        RenderQuads.ribbon(consumer, rotated, 0.5F, 0.145F, 0.592F, 0.290F, ARROW_Y0, ARROW_Y1, arrow);

        // Tail fin, the wide flat part that gives a real vane its leverage.
        RenderQuads.ribbon(consumer, rotated, 0.372F, 0.690F, 0.628F, 0.690F, FIN_Y0, FIN_Y1, tail);
        RenderQuads.ribbon(consumer, rotated, 0.372F, 0.660F, 0.372F, 0.690F, FIN_Y0, FIN_Y1, accent);
        RenderQuads.ribbon(consumer, rotated, 0.628F, 0.660F, 0.628F, 0.690F, FIN_Y0, FIN_Y1, accent);

        stack.popPose();
    }
}
