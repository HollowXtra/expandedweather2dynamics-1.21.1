package com.imbet.client;

import com.imbet.alert.WeatherAlertSirenBlockEntity;
import com.imbet.client.SolidMesh.Builder;
import com.imbet.client.SolidMesh.Material;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

/**
 * The Weather Alert Siren's antenna: a two-stage mast that telescopes out of the housing, a dish that unfolds on top of
 * it, and the dish turning to point at the storm the siren is warning about.
 *
 * <p>The mast is not animated by sliding the whole model up through the block. It stretches: the top post's edge climbs
 * from {@link #POST_TOP_STOWED_Y}, which is low enough that the collar on top of it sits flush with the sleeve, up to
 * {@link #MAST_TOP_Y}, while its bottom stays buried. The housing is an opaque cube drawn before the block entities, so
 * the part still inside it is hidden by it - which is the read we want, an antenna rising out of a box. A middle stage
 * climbs at half the rate, which is what makes a telescoping mast read as telescoping rather than as one post growing.
 *
 * <p>The dish hangs on a cantilever {@link #ARM} blocks in front of the mast rather than directly over it. That is not
 * decoration: the dish's rim sweeps a sphere around its pivot as it tilts, so a dish pivoting on the mast's own axis
 * drives its lower rim straight through the mast at the elevations a distant storm actually needs - roughly horizontal.
 * Offsetting the elevation axis forward puts the whole swept sphere in clear air, which is why real dishes are mounted
 * that way too.
 *
 * <p>The surface is a real parabola opening toward the feed: {@code x = D (r/R)^2}, vertex on the elevation axis and the
 * rim forward of it, with the horn at the focus. It used to bulge the other way, so the horn hung in front of a dome.
 */
public class WeatherAlertSirenRenderer implements BlockEntityRenderer<WeatherAlertSirenBlockEntity> {
    // ---- mast ----

    private static final float SLEEVE_HALF = 0.075F;
    private static final float SLEEVE_BOTTOM_Y = 0.45F;
    private static final float SLEEVE_TOP_Y = 1.06F;

    private static final float MID_HALF = 0.058F;
    private static final float MID_BOTTOM_Y = 0.72F;
    /** How far the middle stage lags the top post: it is shorter, so it reaches half the travel. */
    private static final float MID_LAG = 0.5F;

    private static final float POST_HALF = 0.040F;
    private static final float POST_BOTTOM_Y = 0.60F;

    private static final float COLLAR_HEIGHT = 0.08F;
    private static final float COLLAR_HALF = 0.085F;

    /**
     * Post height with nothing raised: the collar's top then sits exactly at the sleeve's, so a stowed siren shows a
     * socket on its roof and no antenna. Derived from the other two so the stowed state cannot drift out of the sleeve.
     */
    private static final float POST_TOP_STOWED_Y = SLEEVE_TOP_Y - COLLAR_HEIGHT;

    private static final float MAST_TOP_Y = 1.60F;

    /** How far the dish's elevation axis stands above the top of the collar. */
    private static final float PIVOT_ABOVE_COLLAR = 0.18F;

    /**
     * The climb at which the mount and dish appear.
     *
     * <p>The whole mount is wider than the housing, so while the mast is down there is nowhere inside the block to hide
     * it, and drawing it would leave a dish hovering over the roof.
     */
    private static final float ASSEMBLY_FROM = 0.55F;

    private static final float ARM = 0.34F;

    // ---- dish ----

    private static final float DISH_RADIUS = 0.46F;
    private static final float DISH_DEPTH = 0.15F;
    private static final float DISH_STOWED_SCALE = 0.24F;
    private static final float STOWED_ELEVATION = 86.0F;
    /** The tripod and horn only appear once the dish is most of the way open. */
    private static final float RIGGING_FROM = 0.45F;
    /** Measured from the vertex: {@code R^2 / 4D}. */
    private static final float DISH_FOCAL_X = DISH_RADIUS * DISH_RADIUS / (4.0F * DISH_DEPTH);

    private static final float LAMP_OFFSET = 0.20F;
    private static final float LAMP_HALF = 0.040F;
    private static final float LAMP_Y0 = 0.875F;
    private static final float LAMP_Y1 = 0.945F;

    private static final SolidMesh SLEEVE = new Builder(Material.GALVANIZED)
            .box(-SLEEVE_HALF, SLEEVE_BOTTOM_Y, -SLEEVE_HALF, SLEEVE_HALF, SLEEVE_TOP_Y, SLEEVE_HALF, 2.0F).build();
    private static final SolidMesh STAGE_MID = new Builder(Material.ALUMINUM)
            .box(-MID_HALF, 0.0F, -MID_HALF, MID_HALF, 1.0F, MID_HALF, 1.0F).build();
    private static final SolidMesh STAGE_POST = new Builder(Material.ALUMINUM)
            .box(-POST_HALF, 0.0F, -POST_HALF, POST_HALF, 1.0F, POST_HALF, 1.0F).build();
    private static final SolidMesh COLLAR = new Builder(Material.STEEL_DARK)
            .box(-COLLAR_HALF, 0.0F, -COLLAR_HALF, COLLAR_HALF, COLLAR_HEIGHT, COLLAR_HALF, 3.0F).build();
    private static final SolidMesh LAMP = new Builder(Material.LENS)
            .box(-LAMP_HALF, LAMP_Y0, -LAMP_HALF, LAMP_HALF, LAMP_Y1, LAMP_HALF, 6.0F).build();
    private static final SolidMesh MOUNT = mount();
    private static final SolidMesh CRADLE = cradle();
    private static final SolidMesh[] DISH = dish();
    private static final SolidMesh[] RIGGING = rigging();

    public WeatherAlertSirenRenderer(BlockEntityRendererProvider.Context context) {
    }

    /** The antenna stands well above the block's own cube, so the renderer's cull box has to reach up with it. */
    @Override
    public AABB getRenderBoundingBox(WeatherAlertSirenBlockEntity entity) {
        return new AABB(entity.getBlockPos()).inflate(1.5D, 2.0D, 1.5D);
    }

    @Override
    public void render(WeatherAlertSirenBlockEntity entity, float partialTick, PoseStack stack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        float rise = entity.renderDeployProgress(partialTick);
        float open = entity.renderDishOpen(partialTick);
        VertexConsumer consumer = buffer.getBuffer(SolidMesh.renderType());

        float postTop = Mth.lerp(rise, POST_TOP_STOWED_Y, MAST_TOP_Y);
        float travel = postTop - POST_TOP_STOWED_Y;
        float midTop = POST_TOP_STOWED_Y + travel * MID_LAG;
        // How far the whole dish assembly is below its deployed height. The aiming maths uses the deployed
        // pivot, so this is a pure drawing offset.
        float lift = postTop + COLLAR_HEIGHT + PIVOT_ABOVE_COLLAR - WeatherAlertSirenBlockEntity.DISH_PIVOT_Y;

        stack.pushPose();
        stack.translate(0.5F, 0.0F, 0.5F);
        SLEEVE.emit(consumer, stack.last(), packedLight, packedOverlay);
        stage(STAGE_POST, POST_BOTTOM_Y, postTop, consumer, stack, packedLight, packedOverlay);
        stage(STAGE_MID, MID_BOTTOM_Y, midTop, consumer, stack, packedLight, packedOverlay);
        stack.pushPose();
        stack.translate(0.0F, postTop, 0.0F);
        COLLAR.emit(consumer, stack.last(), packedLight, packedOverlay);
        stack.popPose();
        statusLamps(consumer, stack, entity, partialTick, packedLight, packedOverlay);

        if (rise >= ASSEMBLY_FROM) {
            // The azimuth frame: the mast axis at the pivot's height, with the arm reaching out along +X. The sign is the
            // inverse of the bearing the block entity works out, because a pose rotation about +Y takes +X toward -Z.
            stack.pushPose();
            stack.translate(0.0F, lift + WeatherAlertSirenBlockEntity.DISH_PIVOT_Y, 0.0F);
            stack.mulPose(Axis.YP.rotationDegrees(-entity.renderAimYaw(partialTick)));
            MOUNT.emit(consumer, stack.last(), packedLight, packedOverlay);

            stack.translate(ARM, 0.0F, 0.0F);
            // Stowed, the dish lies folded and tipped up at the sky; unfolding eases it down onto its aim.
            float elevation = Mth.lerp(open, STOWED_ELEVATION, entity.renderAimElevation(partialTick));
            stack.mulPose(Axis.ZP.rotationDegrees(elevation));
            CRADLE.emit(consumer, stack.last(), packedLight, packedOverlay);

            float size = Mth.lerp(open, DISH_STOWED_SCALE, 1.0F);
            stack.pushPose();
            stack.scale(size, size, size);
            for (SolidMesh mesh : DISH) {
                mesh.emit(consumer, stack.last(), packedLight, packedOverlay);
            }
            // A horn floating in front of a half-unfolded dish is the one thing here that reads as a mistake.
            float rigging = Mth.clamp((open - RIGGING_FROM) / (1.0F - RIGGING_FROM), 0.0F, 1.0F);
            if (rigging > 0.01F) {
                stack.scale(rigging, rigging, rigging);
                for (SolidMesh mesh : RIGGING) {
                    mesh.emit(consumer, stack.last(), packedLight, packedOverlay);
                }
            }
            stack.popPose();
            stack.popPose();
        }
        stack.popPose();
    }

    private static void stage(SolidMesh stage, float bottom, float top, VertexConsumer consumer, PoseStack stack, int light, int overlay) {
        stack.pushPose();
        stack.translate(0.0F, bottom, 0.0F);
        stack.scale(1.0F, Math.max(top - bottom, 0.001F), 1.0F);
        stage.emit(consumer, stack.last(), light, overlay);
        stack.popPose();
    }

    /**
     * The status lamps on the roof plate: a fast red flash while an alert is in force, a steady amber once the dish has
     * locked onto a storm, and a dim blue-grey while it is still sweeping for one. There are four of them so the state
     * reads from whichever side a player walks up to the block, and a lit one is lit by itself rather than by the world.
     */
    private static void statusLamps(VertexConsumer consumer, PoseStack stack, WeatherAlertSirenBlockEntity entity, float partialTick, int light, int overlay) {
        long time = entity.getLevel() == null ? 0L : entity.getLevel().getGameTime();
        int color;
        int lampLight;
        if (entity.getPower() > 0) {
            float phase = 0.5F + 0.5F * Mth.sin((time + partialTick) * 0.30F);
            int red = 90 + Math.round(165.0F * phase);
            color = 0xFF000000 | red << 16 | Math.round(red * 0.24F) << 8 | Math.round(red * 0.18F);
            lampLight = LightTexture.FULL_BRIGHT;
        } else if (entity.trackingTarget()) {
            color = 0xFFE0A63A;
            lampLight = LightTexture.FULL_BRIGHT;
        } else {
            color = 0xFF5B6B78;
            lampLight = light;
        }
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            stack.pushPose();
            stack.translate((quadrant & 1) == 0 ? -LAMP_OFFSET : LAMP_OFFSET, 0.0F, (quadrant & 2) == 0 ? -LAMP_OFFSET : LAMP_OFFSET);
            LAMP.emit(consumer, stack.last(), lampLight, overlay, color);
            stack.popPose();
        }
    }

    private static SolidMesh mount() {
        Builder mount = new Builder(Material.GALVANIZED);
        mount.box(0.0F, -0.17F, -0.05F, ARM - 0.09F, -0.05F, 0.05F, 2.0F);
        mount.box(ARM - 0.09F, -0.062F, -0.062F, ARM - 0.02F, 0.062F, 0.062F, 3.0F);
        return mount.build();
    }

    private static SolidMesh cradle() {
        Builder cradle = new Builder(Material.STEEL_DARK);
        cradle.box(-0.34F, -0.08F, 0.062F, -0.03F, 0.03F, 0.092F, 3.0F);
        cradle.box(-0.34F, -0.08F, -0.092F, -0.03F, 0.03F, -0.062F, 3.0F);
        cradle.box(-0.19F, -0.055F, -0.092F, -0.01F, 0.055F, 0.092F, 3.0F);
        return cradle.build();
    }

    private static SolidMesh[] dish() {
        int rings = 9;
        float[] stations = new float[rings + 1];
        float[] radii = new float[rings + 1];
        for (int i = 0; i <= rings; i++) {
            float r = Math.max(0.002F, DISH_RADIUS * i / rings);
            radii[i] = r;
            stations[i] = DISH_DEPTH * (r / DISH_RADIUS) * (r / DISH_RADIUS);
        }
        Builder front = new Builder(Material.DISH);
        front.latheX(stations, radii, 28, 2.0F, true, null);

        Builder back = new Builder(Material.ALUMINUM);
        float[] backStations = new float[rings + 1];
        for (int i = 0; i <= rings; i++) {
            backStations[i] = stations[i] - 0.006F;
        }
        back.latheX(backStations, radii, 28, 2.0F);
        // Rolled rim, and the back-up ribs that keep a reflector this size true.
        back.latheX(new float[]{DISH_DEPTH - 0.012F, DISH_DEPTH + 0.004F, DISH_DEPTH + 0.008F},
                new float[]{DISH_RADIUS, DISH_RADIUS + 0.012F, DISH_RADIUS}, 28, 3.0F);
        Builder ribs = new Builder(Material.STEEL_DARK);
        for (int rib = 0; rib < 6; rib++) {
            ribs.resetFrame().turnX(rib * 60.0F);
            ribs.tube(-0.008F, 0.03F, 0.0F, DISH_DEPTH * 0.72F - 0.012F, DISH_RADIUS * 0.85F, 0.0F, 0.0065F, 5, 3.0F);
        }
        return new SolidMesh[]{front.build(), back.build(), ribs.build()};
    }

    private static SolidMesh[] rigging() {
        Builder struts = new Builder(Material.GALVANIZED);
        float foot = DISH_RADIUS * 0.80F;
        float footX = DISH_DEPTH * 0.64F;
        for (int leg = 0; leg < 3; leg++) {
            double angle = Math.toRadians(90.0D + leg * 120.0D);
            struts.tube(footX, foot * (float) Math.sin(angle), foot * (float) Math.cos(angle), DISH_FOCAL_X + 0.03F, 0.0F, 0.0F, 0.0085F, 5, 3.0F);
        }
        // The feed: a short flared horn looking back into the dish, with its LNB behind it.
        Builder feed = new Builder(Material.BRASS);
        feed.latheX(new float[]{DISH_FOCAL_X - 0.05F, DISH_FOCAL_X + 0.02F, DISH_FOCAL_X + 0.05F, DISH_FOCAL_X + 0.09F},
                new float[]{0.056F, 0.030F, 0.028F, 0.028F}, 12, 4.0F);
        feed.discX(DISH_FOCAL_X + 0.09F, 0.028F, 12, true, 1.0F);
        Builder mouth = new Builder(Material.BORE);
        mouth.discX(DISH_FOCAL_X - 0.05F, 0.056F, 12, false, 0.35F);
        return new SolidMesh[]{struts.build(), feed.build(), mouth.build()};
    }
}
