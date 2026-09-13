package com.imbet.client;

import com.imbet.Config;
import com.imbet.anemometer.AnemometerBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/**
 * The anemometer's rotor: four arms with a cup on each end, turning on the bearing housing the block
 * model provides.
 *
 * <p>The metalwork is built from vertical ribbons rather than the flat top-facing quads it used to be
 * drawn with. That is the whole point of this renderer: a flat quad seen from the side is nothing, so
 * the arm and cap geometry is stood up in the vertical plane and given a top and a floor. A player
 * standing next to the anemometer, which is how anyone actually looks at one, now sees the arms sweep
 * past instead of a grey line.
 *
 * <p>The spin is the block entity's, in degrees per tick, damped so it coasts down after a gust. The
 * size of the sweep, not the speed, is what the arms are: the rotor is drawn at the block's top face,
 * just above the cap of the housing, so the drawn metalwork never fights the model.
 */
public class AnemometerRenderer implements BlockEntityRenderer<AnemometerBlockEntity> {
    private static final int HUB = 0xFF8F979E;
    private static final int ARM = 0xFFBCC4CB;
    private static final int CUP = 0xFFEDF1F4;
    private static final int CUP_SHADE = 0xFFC6CDD3;

    /** Arm bar: reach from the hub, and how tall and how thick it is. */
    private static final float REACH = 0.280F;
    private static final float ARM_HALF_THICKNESS = 0.012F;
    private static final float ARM_Y0 = 0.975F;
    private static final float ARM_Y1 = 1.020F;

    /** Cup: how wide it is across the arm, how deep it is along it, and how far it hangs below. */
    private static final float CUP_HALF = 0.056F;
    private static final float CUP_DEPTH = 0.038F;
    private static final float CUP_Y0 = 0.930F;
    private static final float CUP_Y1 = 1.062F;

    /** Hub. */
    private static final float HUB_HALF = 0.032F;
    private static final float HUB_Y0 = 0.950F;
    private static final float HUB_Y1 = 1.058F;

    /** The four arms, as unit directions from the hub. */
    private static final float[][] ARMS = {{1.0F, 0.0F}, {0.0F, 1.0F}, {-1.0F, 0.0F}, {0.0F, -1.0F}};

    public AnemometerRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(AnemometerBlockEntity entity, float partialTick, PoseStack stack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (!Config.ANEMOMETER_ENABLED.get()) {
            return;
        }
        VertexConsumer consumer = buffer.getBuffer(RenderType.lightning());

        // Turn about the mast's axis, which is the block's centre. The sign only decides which way it
        // spins; the cups are the same either way, so it is not inverted as the vane's heading is.
        stack.pushPose();
        stack.translate(0.5F, 0.0F, 0.5F);
        stack.mulPose(Axis.YP.rotationDegrees(-entity.getRenderAngle(partialTick)));
        stack.translate(-0.5F, 0.0F, -0.5F);
        PoseStack.Pose pose = stack.last();

        hub(consumer, pose);
        for (float[] arm : ARMS) {
            arm(consumer, pose, arm[0], arm[1]);
            cup(consumer, pose, arm[0], arm[1]);
        }

        stack.popPose();
    }

    /** Two crossed walls and a cap, so the centre reads as a hub from every angle. */
    private static void hub(VertexConsumer consumer, PoseStack.Pose pose) {
        float low = 0.5F - HUB_HALF;
        float high = 0.5F + HUB_HALF;
        RenderQuads.ribbon(consumer, pose, low, 0.5F, high, 0.5F, HUB_Y0, HUB_Y1, HUB);
        RenderQuads.ribbon(consumer, pose, 0.5F, low, 0.5F, high, HUB_Y0, HUB_Y1, HUB);
        RenderQuads.topQuad(consumer, pose, low, low, high, high, HUB_Y1, HUB);
    }

    /** One arm: the two sides of the bar and its top, running from the hub to the cup. */
    private static void arm(VertexConsumer consumer, PoseStack.Pose pose, float dx, float dz) {
        float tipX = 0.5F + dx * REACH;
        float tipZ = 0.5F + dz * REACH;
        // Offset to either side of the arm's axis, which is what gives the bar its thickness.
        float sideX = -dz * ARM_HALF_THICKNESS;
        float sideZ = dx * ARM_HALF_THICKNESS;

        RenderQuads.ribbon(consumer, pose, 0.5F + sideX, 0.5F + sideZ, tipX + sideX, tipZ + sideZ, ARM_Y0, ARM_Y1, ARM);
        RenderQuads.ribbon(consumer, pose, 0.5F - sideX, 0.5F - sideZ, tipX - sideX, tipZ - sideZ, ARM_Y0, ARM_Y1, ARM);
        RenderQuads.topQuad(consumer, pose,
                Math.min(0.5F, tipX) - Math.abs(sideX), Math.min(0.5F, tipZ) - Math.abs(sideZ),
                Math.max(0.5F, tipX) + Math.abs(sideX), Math.max(0.5F, tipZ) + Math.abs(sideZ),
                ARM_Y1, ARM);
    }

    /**
     * One cup: an open scoop at the end of an arm.
     *
     * <p>Built as the shape a cup actually is - an end wall across the arm, two side walls running back
     * along it and a floor - rather than a solid block, because the open mouth is what makes a rotating
     * cup read as a cup rather than as a spinning lump.
     */
    private static void cup(VertexConsumer consumer, PoseStack.Pose pose, float dx, float dz) {
        float tipX = 0.5F + dx * REACH;
        float tipZ = 0.5F + dz * REACH;
        float innerX = tipX - dx * CUP_DEPTH;
        float innerZ = tipZ - dz * CUP_DEPTH;
        // The cup's width runs across the arm, so its walls are offset along the axis perpendicular to it.
        float acrossX = -dz * CUP_HALF;
        float acrossZ = dx * CUP_HALF;

        // Mouth end, across the arm.
        RenderQuads.ribbon(consumer, pose,
                tipX + acrossX, tipZ + acrossZ, tipX - acrossX, tipZ - acrossZ, CUP_Y0, CUP_Y1, CUP);
        // The two sides, running back toward the hub, so the opening faces outward.
        RenderQuads.ribbon(consumer, pose,
                tipX + acrossX, tipZ + acrossZ, innerX + acrossX, innerZ + acrossZ, CUP_Y0, CUP_Y1, CUP_SHADE);
        RenderQuads.ribbon(consumer, pose,
                tipX - acrossX, tipZ - acrossZ, innerX - acrossX, innerZ - acrossZ, CUP_Y0, CUP_Y1, CUP_SHADE);
        RenderQuads.topQuad(consumer, pose,
                Math.min(innerX, tipX) - Math.abs(acrossX), Math.min(innerZ, tipZ) - Math.abs(acrossZ),
                Math.max(innerX, tipX) + Math.abs(acrossX), Math.max(innerZ, tipZ) + Math.abs(acrossZ),
                CUP_Y0, CUP_SHADE);
    }
}
