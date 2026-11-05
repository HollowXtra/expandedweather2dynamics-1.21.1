package com.imbet.client;

import com.imbet.Config;
import com.imbet.anemometer.AnemometerBlockEntity;
import com.imbet.client.SolidMesh.Builder;
import com.imbet.client.SolidMesh.Material;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;

/**
 * The anemometer's rotor: three cups on three arms, the way a Robinson anemometer has been built for a century.
 *
 * <p>It has three arms because three is the number that turns smoothly: four, which is what this used to draw, gives
 * the rotor a torque that pulses with the angle. Each cup is a real hemisphere open along the direction of travel, and
 * the difference in drag between its open and its round side is the whole of how the instrument works, so the shape is
 * worth getting right. The sign of the rotation only decides which way it spins; it is not inverted the way the
 * vane's heading is.
 */
public class AnemometerRenderer implements BlockEntityRenderer<AnemometerBlockEntity> {
    private static final float HUB_Y = 1.0F;
    private static final float REACH = 0.31F;
    private static final float CUP_RADIUS = 0.082F;

    private static final SolidMesh[] FIXED = fixed();
    private static final SolidMesh[] ROTOR = rotor();

    public AnemometerRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public AABB getRenderBoundingBox(AnemometerBlockEntity entity) {
        return new AABB(entity.getBlockPos()).inflate(0.2D, 0.0D, 0.2D).expandTowards(0.0D, 0.5D, 0.0D);
    }

    @Override
    public void render(AnemometerBlockEntity entity, float partialTick, PoseStack stack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (!Config.ANEMOMETER_ENABLED.get()) {
            return;
        }
        VertexConsumer consumer = buffer.getBuffer(SolidMesh.renderType());
        stack.pushPose();
        stack.translate(0.5F, 0.0F, 0.5F);
        for (SolidMesh mesh : FIXED) {
            mesh.emit(consumer, stack.last(), packedLight, packedOverlay);
        }
        stack.mulPose(Axis.YP.rotationDegrees(-entity.getRenderAngle(partialTick)));
        for (SolidMesh mesh : ROTOR) {
            mesh.emit(consumer, stack.last(), packedLight, packedOverlay);
        }
        stack.popPose();
    }

    private static SolidMesh[] fixed() {
        Builder spindle = new Builder(Material.ALUMINUM);
        spindle.latheY(new float[]{HUB_Y - 0.10F, HUB_Y - 0.09F, HUB_Y - 0.03F, HUB_Y}, new float[]{0.05F, 0.042F, 0.03F, 0.016F}, 12, 4.0F);
        return new SolidMesh[]{spindle.build()};
    }

    private static SolidMesh[] rotor() {
        Builder hub = new Builder(Material.ALUMINUM);
        hub.latheY(new float[]{HUB_Y - 0.01F, HUB_Y + 0.005F, HUB_Y + 0.045F, HUB_Y + 0.06F, HUB_Y + 0.07F},
                new float[]{0.018F, 0.034F, 0.034F, 0.024F, 0.002F}, 14, 4.0F);

        Builder arms = new Builder(Material.ALUMINUM);
        Builder cupOutside = new Builder(Material.CUP);
        Builder cupInside = new Builder(Material.BORE);
        int rings = 6;
        float[] stations = new float[rings + 1];
        float[] radii = new float[rings + 1];
        float[] shades = new float[rings + 1];
        for (int i = 0; i <= rings; i++) {
            double t = i / (double) rings * Math.PI * 0.5D;
            // From the cup's rim at its equator, round to the pole at the back of the cup.
            stations[i] = -(float) Math.sin(t) * CUP_RADIUS;
            radii[i] = Math.max(0.001F, (float) Math.cos(t) * CUP_RADIUS);
            shades[i] = 0.95F - 0.65F * (float) Math.sin(t);
        }
        for (int arm = 0; arm < 3; arm++) {
            float yaw = arm * 120.0F;
            arms.resetFrame().turnY(yaw);
            arms.tube(0.02F, HUB_Y + 0.025F, 0.0F, REACH, HUB_Y + 0.025F, 0.0F, 0.0085F, 6, 4.0F);

            cupOutside.resetFrame().turnY(yaw).at(REACH, HUB_Y + 0.025F, 0.0F).turnY(90.0F);
            cupOutside.latheX(stations, radii, 16, 4.0F);
            cupInside.resetFrame().turnY(yaw).at(REACH, HUB_Y + 0.025F, 0.0F).turnY(90.0F);
            float[] inner = new float[rings + 1];
            for (int i = 0; i <= rings; i++) {
                inner[i] = Math.max(0.001F, radii[i] * 0.94F);
            }
            cupInside.latheX(stations, inner, 16, 4.0F, true, shades);
        }
        return new SolidMesh[]{hub.build(), arms.build(), cupOutside.build(), cupInside.build()};
    }
}
