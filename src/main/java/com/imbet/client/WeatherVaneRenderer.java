package com.imbet.client;

import com.imbet.Config;
import com.imbet.client.SolidMesh.Builder;
import com.imbet.client.SolidMesh.Material;
import com.imbet.instruments.WeatherVaneBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;

/**
 * The Weather Vane: a copper arrow turning over fixed cardinal arms, and a compass rose on the block's top face.
 *
 * <p>The cardinal arms carry cast letters, because a vane is only readable against them, and they are fixed to the
 * world rather than to the block. North is the pose's local -Z, so the arrow is built pointing north and then rotated
 * by {@code -heading}: compass headings run clockwise while a positive rotation about Y runs the other way, so the sign
 * is inverted to make the arrow agree with the bearing the readout prints.
 *
 * <p>An arrow points the way the wind comes from, which it does because the broad fin is at its tail and a vane
 * weathercocks: the tail goes downwind. That is why the fin is the largest thing on it.
 */
public class WeatherVaneRenderer implements BlockEntityRenderer<WeatherVaneBlockEntity> {
    private static final int ACCENT = 0xFF7DD3FC;

    private static final float ARMS_Y = 1.20F;
    private static final float ARROW_Y = 1.40F;
    private static final float SPINDLE_TOP = 1.52F;
    private static final float ARM_REACH = 0.36F;

    private static final SolidMesh[] FIXED = fixed();
    private static final SolidMesh[] ARROW = arrow();

    public WeatherVaneRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public AABB getRenderBoundingBox(WeatherVaneBlockEntity entity) {
        return new AABB(entity.getBlockPos()).inflate(0.1D, 0.0D, 0.1D).expandTowards(0.0D, 0.8D, 0.0D);
    }

    @Override
    public void render(WeatherVaneBlockEntity entity, float partialTick, PoseStack stack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (!Config.WEATHER_VANE_ENABLED.get()) {
            return;
        }
        // The rose is a readout rather than a piece of hardware, and stays with the other flat readouts.
        float shade = RenderQuads.lightShade(entity.getLevel(), packedLight);
        InstrumentPanel.compassRose(buffer.getBuffer(RenderType.debugQuads()), stack.last(), RenderQuads.lit(ACCENT, shade), RenderQuads.lit(0xFFFFFFFF, shade));

        VertexConsumer consumer = buffer.getBuffer(SolidMesh.renderType());
        stack.pushPose();
        stack.translate(0.5F, 0.0F, 0.5F);
        for (SolidMesh mesh : FIXED) {
            mesh.emit(consumer, stack.last(), packedLight, packedOverlay);
        }
        stack.mulPose(Axis.YP.rotationDegrees(-entity.getRenderHeading(partialTick)));
        for (SolidMesh mesh : ARROW) {
            mesh.emit(consumer, stack.last(), packedLight, packedOverlay);
        }
        stack.popPose();
    }

    private static SolidMesh[] fixed() {
        Builder spindle = new Builder(Material.BRASS);
        spindle.latheY(new float[]{0.97F, 1.00F, 1.02F, SPINDLE_TOP}, new float[]{0.030F, 0.026F, 0.014F, 0.012F}, 10, 3.0F);
        // Finials: the ball on top, and the collar the arms cross at.
        spindle.latheY(new float[]{SPINDLE_TOP - 0.004F, SPINDLE_TOP + 0.012F, SPINDLE_TOP + 0.032F, SPINDLE_TOP + 0.05F, SPINDLE_TOP + 0.056F},
                new float[]{0.010F, 0.024F, 0.030F, 0.018F, 0.002F}, 12, 4.0F);
        spindle.latheY(new float[]{ARMS_Y - 0.03F, ARMS_Y - 0.015F, ARMS_Y + 0.015F, ARMS_Y + 0.03F}, new float[]{0.016F, 0.032F, 0.032F, 0.016F}, 12, 4.0F);

        Builder arms = new Builder(Material.BRASS);
        arms.tube(-ARM_REACH, ARMS_Y, 0.0F, ARM_REACH, ARMS_Y, 0.0F, 0.0085F, 6, 3.0F);
        arms.tube(0.0F, ARMS_Y, -ARM_REACH, 0.0F, ARMS_Y, ARM_REACH, 0.0085F, 6, 3.0F);

        Builder letters = new Builder(Material.COPPER);
        letter(letters, 0, 0.0F, -ARM_REACH - 0.02F, 0.0F);
        letter(letters, 1, ARM_REACH + 0.02F, 0.0F, 270.0F);
        letter(letters, 2, 0.0F, ARM_REACH + 0.02F, 180.0F);
        letter(letters, 3, -ARM_REACH - 0.02F, 0.0F, 90.0F);
        return new SolidMesh[]{spindle.build(), arms.build(), letters.build()};
    }

    /** A letter cast in small blocks, five by five: N, E, S and W are all a vane ever needs to spell. */
    private static void letter(Builder out, int which, float x, float z, float yaw) {
        String[] glyph = switch (which) {
            case 0 -> new String[]{"X...X", "XX..X", "X.X.X", "X..XX", "X...X"};
            case 1 -> new String[]{"XXXXX", "X....", "XXXX.", "X....", "XXXXX"};
            case 2 -> new String[]{".XXXX", "X....", ".XXX.", "....X", "XXXX."};
            default -> new String[]{"X...X", "X...X", "X.X.X", "XX.XX", "X...X"};
        };
        float cell = 0.021F;
        out.resetFrame().at(x, ARMS_Y, z).turnY(yaw);
        for (int row = 0; row < 5; row++) {
            for (int column = 0; column < 5; column++) {
                if (glyph[row].charAt(column) != 'X') {
                    continue;
                }
                float left = (column - 2.5F) * cell;
                float top = (2.5F - row) * cell;
                out.box(left, top - cell, -0.006F, left + cell, top, 0.006F, 3.0F);
            }
        }
        out.resetFrame();
    }

    private static SolidMesh[] arrow() {
        Builder bearing = new Builder(Material.BRASS);
        bearing.latheY(new float[]{ARROW_Y - 0.035F, ARROW_Y - 0.02F, ARROW_Y + 0.02F, ARROW_Y + 0.035F}, new float[]{0.018F, 0.028F, 0.028F, 0.018F}, 12, 4.0F);

        Builder copper = new Builder(Material.COPPER);
        copper.tube(0.0F, ARROW_Y, 0.42F, 0.0F, ARROW_Y, -0.32F, 0.0105F, 8, 2.0F);
        // The head: a cone with a barbed back edge, on the north end of the shaft.
        copper.resetFrame().at(0.0F, ARROW_Y, -0.30F).turnY(90.0F);
        copper.latheX(new float[]{0.0F, 0.01F, 0.17F}, new float[]{0.012F, 0.052F, 0.001F}, 10, 3.0F);
        copper.resetFrame();
        // The fin, as a real one is cut: swept and notched, in a thin plate, wide enough to give the vane its leverage.
        float y = ARROW_Y;
        finQuad(copper, 0.16F, 0.012F, 0.44F, 0.118F, 0.47F, 0.012F, y);
        finQuad(copper, 0.16F, -0.012F, 0.44F, -0.118F, 0.47F, -0.012F, y);
        return new SolidMesh[]{bearing.build(), copper.build()};
    }

    /** One half of the fin, written as two faces so it has a surface whichever side the light is on. */
    private static void finQuad(Builder out, float z0, float y0, float z1, float y1, float z2, float y2, float y) {
        out.quad(0.0F, y + y0, z0, 0.0F, y + y1, z1, 0.0F, y + y2, z2, 0.0F, y + y2, z2, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);
        out.quad(0.0F, y + y2, z2, 0.0F, y + y1, z1, 0.0F, y + y0, z0, 0.0F, y + y0, z0, -1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);
    }
}
