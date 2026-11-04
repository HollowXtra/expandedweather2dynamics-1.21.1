package com.imbet.client;

import com.imbet.alert.WeatherAlertSirenBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

/**
 * The Weather Alert Siren's antenna: a two-stage mast that telescopes out of the housing, a dish that unfolds
 * on top of it, and the dish turning to point at the storm the siren is warning about.
 *
 * <p>The mast is not animated by sliding the whole model up through the block. It stretches: the top post's
 * edge climbs from {@link #POST_TOP_STOWED_Y}, which is low enough that the collar on top of it sits flush
 * with the sleeve, up to {@link #MAST_TOP_Y}, while its bottom stays buried. The housing is an opaque cube
 * drawn before the block entities, so the part still inside it is hidden by it - which is the read we want,
 * an antenna rising out of a box. A middle stage climbs at half the rate, which is what makes a telescoping
 * mast read as telescoping rather than as one post growing.
 *
 * <p>The dish hangs on a cantilever {@link #ARM} blocks in front of the mast rather than directly over it.
 * That is not decoration: the dish's rim sweeps a sphere of radius {@code sqrt(R^2 + D^2)} around its pivot
 * as it tilts, so a dish pivoting on the mast's own axis drives its lower rim straight through the mast at
 * the elevations a distant storm actually needs - roughly horizontal. Offsetting the elevation axis forward
 * puts the whole swept sphere in clear air, which is why real dishes are mounted that way too.
 *
 * <p>Three rules from the rest of the mod apply here. The geometry is drawn with {@code debugQuads()}, not
 * {@code lightning()}, because the latter writes into the weather render target. That render type is
 * colour-only with no depth write, so it does not occlude itself: parts meant to hide other parts have to be
 * drawn last, so the post and the middle stage are painted before the sleeve that swallows them. And because
 * it is colour-only, all lighting is ours - {@link #box} shades each face the way a vanilla block does, and
 * the dish is banded by radius and split into radial panels, which is what stops smooth white geometry from
 * reading as plastic next to a texture atlas.
 */
public class WeatherAlertSirenRenderer implements BlockEntityRenderer<WeatherAlertSirenBlockEntity> {
    // ---- mast ----

    /** Outer sleeve the stages slide through, and the stub of it that shows above the roof. */
    private static final float SLEEVE_HALF = 0.075F;
    private static final float SLEEVE_BOTTOM_Y = 0.45F;
    private static final float SLEEVE_TOP_Y = 1.06F;

    /** Middle telescoping stage. */
    private static final float MID_HALF = 0.058F;
    private static final float MID_BOTTOM_Y = 0.72F;
    /** How far the middle stage lags the top post: it is shorter, so it reaches half the travel. */
    private static final float MID_LAG = 0.5F;

    /** The post itself, reaching from inside the sleeve up to wherever the climb has got to. */
    private static final float POST_HALF = 0.040F;
    private static final float POST_BOTTOM_Y = 0.60F;

    /** Collar capping the post. */
    private static final float COLLAR_HEIGHT = 0.08F;
    private static final float COLLAR_HALF = 0.085F;

    /**
     * Post height with nothing raised: the collar's top then sits exactly at the sleeve's, so a stowed
     * siren shows a socket on its roof and no antenna. Derived from the other two so the stowed state
     * cannot drift out of the sleeve if either is retuned.
     */
    private static final float POST_TOP_STOWED_Y = SLEEVE_TOP_Y - COLLAR_HEIGHT;

    /** Fully raised post. */
    private static final float MAST_TOP_Y = 1.60F;

    /** How far the dish's elevation axis stands above the top of the collar. */
    private static final float PIVOT_ABOVE_COLLAR = 0.18F;

    /**
     * The climb at which the mount and dish appear.
     *
     * <p>Two things set this. The whole mount is wider than the housing - the dish alone reaches 0.8 blocks
     * from the mast axis - so while the mast is down there is nowhere inside the block to hide it, and
     * drawing it would leave a dish hovering over the roof. And the dish's rim swings
     * {@code D sin(e) + R cos(e)} below its pivot, which peaks at 0.48 blocks near 20 degrees of elevation -
     * exactly the angle it holds when it is aimed at a distant storm. Showing it before the post has climbed
     * far enough therefore drives that rim through the horn cluster: a harness that measures the dish against
     * the block model reports 0.085 blocks of intersection at climb 0.30. At 0.55 the pivot is high enough
     * that the closest approach is comfortably outside it.
     */
    private static final float ASSEMBLY_FROM = 0.55F;

    /** How far in front of the mast axis the elevation axis sits, and the bracket that reaches out to it. */
    private static final float ARM = 0.34F;
    private static final float ARM_BOTTOM = -0.17F;
    private static final float ARM_TOP = -0.05F;
    private static final float ARM_HALF = 0.050F;
    private static final float HUB_LENGTH = 0.090F;
    private static final float HUB_HALF = 0.062F;

    // ---- dish ----

    private static final float DISH_RADIUS = 0.46F;
    private static final float DISH_DEPTH = 0.15F;
    private static final int DISH_RINGS = 4;
    private static final int DISH_SEGMENTS = 16;
    /** How many segments make one reflector panel; the seams between them are shaded darker. */
    private static final int DISH_SEGMENTS_PER_PANEL = 2;
    private static final float DISH_STOWED_SCALE = 0.24F;
    private static final float STOWED_ELEVATION = 86.0F;
    private static final float RIM_LIP = 0.040F;
    private static final float RIM_OUT = 0.016F;
    private static final float STRUT_HALF_WIDTH = 0.019F;
    private static final float[] STRUT_ANGLES = {90.0F, 210.0F, 330.0F};

    /** Cradle: the plates that grip the dish's spine and turn with it in elevation. */
    private static final float CRADLE_BACK = -0.34F;
    private static final float CRADLE_FRONT = -0.03F;
    private static final float CRADLE_BOTTOM = -0.080F;
    private static final float CRADLE_TOP = 0.030F;
    private static final float CRADLE_INNER = 0.062F;
    private static final float CRADLE_OUTER = 0.092F;

    /**
     * Where the status lamps sit: one per quadrant of the roof plate, clear of the horn cluster.
     *
     * <p>They live here rather than in the model because their colour is the siren's state. Four of them,
     * rather than one, so the state is readable whichever side a player walks up to the block from.
     */
    private static final float LAMP_OFFSET = 0.20F;
    private static final float LAMP_HALF = 0.040F;
    private static final float LAMP_Y0 = 0.875F;
    private static final float LAMP_Y1 = 0.945F;

    /** The feed horn's mouth and neck, and how far the whole assembly reaches either side of the focus. */
    private static final float HORN_MOUTH_RADIUS = 0.055F;
    private static final float HORN_NECK_RADIUS = 0.026F;
    private static final float HORN_LENGTH = 0.090F;
    /** The tripod and horn only appear once the dish is most of the way open. */
    private static final float RIGGING_FROM = 0.45F;

    /**
     * The feed horn sits at the parabola's focus, measured from the vertex: {@code R^2 / 4D}. The vertex is
     * the elevation axis, so this is also where the horn sits in the dish's own frame.
     */
    private static final float DISH_FOCAL_X = DISH_RADIUS * DISH_RADIUS / (4.0F * DISH_DEPTH);

    // ---- colours ----

    private static final int SLEEVE = 0xFF98A1A9;
    private static final int POST = 0xFFC6CDD4;
    private static final int COLLAR = 0xFF8C949C;
    private static final int MOUNT = 0xFFAAB3BA;
    private static final int DISH_BACK = 0xFF979FA7;
    private static final int DISH_INNER = 0xFFB4BCC3;
    private static final int DISH_OUTER = 0xFFE1E6EA;
    private static final int DISH_RIM = 0xFFF3F6F9;
    private static final int STRUT = 0xFF7E868E;
    private static final int FEED = 0xFFC98F4A;
    private static final int FEED_MOUTH = 0xFF8A5C24;

    // Vanilla's own block-face shading: a face's brightness depends on which way it points.
    private static final float FACE_UP = 1.00F;
    private static final float FACE_DOWN = 0.55F;
    private static final float FACE_X = 0.66F;
    private static final float FACE_Z = 0.84F;

    public WeatherAlertSirenRenderer(BlockEntityRendererProvider.Context context) {
    }

    /**
     * The antenna stands well above the block's own cube, so the renderer's cull box has to reach up with
     * it. Left at one block, the mast and dish pop out of existence at the edge of the screen while they
     * are still plainly on it.
     */
    @Override
    public AABB getRenderBoundingBox(WeatherAlertSirenBlockEntity entity) {
        return new AABB(entity.getBlockPos()).inflate(1.5D, 2.0D, 1.5D);
    }

    @Override
    public void render(WeatherAlertSirenBlockEntity entity, float partialTick, PoseStack stack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        float rise = entity.renderDeployProgress(partialTick);
        float open = entity.renderDishOpen(partialTick);
        VertexConsumer consumer = buffer.getBuffer(RenderType.debugQuads());
        float shade = RenderQuads.lightShade(entity.getLevel(), packedLight);

        float postTop = Mth.lerp(rise, POST_TOP_STOWED_Y, MAST_TOP_Y);
        float travel = postTop - POST_TOP_STOWED_Y;
        float midTop = POST_TOP_STOWED_Y + travel * MID_LAG;
        // How far the whole dish assembly is below its deployed height. The aiming maths uses the deployed
        // pivot, so this is a pure drawing offset.
        float lift = postTop + COLLAR_HEIGHT + PIVOT_ABOVE_COLLAR - WeatherAlertSirenBlockEntity.DISH_PIVOT_Y;

        PoseStack.Pose pose = stack.last();
        // Inside out: each part is drawn before the part that swallows it - see the class note.
        box(consumer, pose, 0.5F, POST_BOTTOM_Y, 0.5F, POST_HALF, postTop, POST, shade);
        box(consumer, pose, 0.5F, MID_BOTTOM_Y, 0.5F, MID_HALF, midTop, POST, shade);
        box(consumer, pose, 0.5F, SLEEVE_BOTTOM_Y, 0.5F, SLEEVE_HALF, SLEEVE_TOP_Y, SLEEVE, shade);
        box(consumer, pose, 0.5F, postTop, 0.5F, COLLAR_HALF, COLLAR_HEIGHT, COLLAR, shade);
        statusLamps(consumer, pose, entity, partialTick, shade);
        if (rise < ASSEMBLY_FROM) {
            return;
        }

        stack.pushPose();
        stack.translate(0.0F, lift, 0.0F);
        stack.pushPose();
        // The azimuth frame: the mast axis at the pivot's height, with the arm reaching out along +X.
        stack.translate(0.5F, WeatherAlertSirenBlockEntity.DISH_PIVOT_Y, 0.5F);
        // The sign is the inverse of the bearing the block entity works out, because a pose rotation
        // about +Y takes +X toward -Z.
        stack.mulPose(Axis.YP.rotationDegrees(-entity.renderAimYaw(partialTick)));
        pose = stack.last();
        box(consumer, pose, 0.0F, ARM_BOTTOM, -ARM_HALF, ARM - HUB_LENGTH, ARM_TOP, ARM_HALF, MOUNT, shade);
        // Drawn before the dish: the stub goes into the dish's back, so it has to be underneath it.
        box(consumer, pose, ARM - HUB_LENGTH, -HUB_HALF, -HUB_HALF, ARM - 0.02F, HUB_HALF, HUB_HALF, MOUNT, shade);

        stack.pushPose();
        stack.translate(ARM, 0.0F, 0.0F);
        // Stowed, the dish lies folded and tipped up at the sky; unfolding eases it down onto its aim.
        float elevation = Mth.lerp(open, STOWED_ELEVATION, entity.renderAimElevation(partialTick));
        stack.mulPose(Axis.ZP.rotationDegrees(elevation));
        pose = stack.last();
        // The cradle turns with the dish, which is what keeps it behind the reflector at every elevation.
        box(consumer, pose, CRADLE_BACK, CRADLE_BOTTOM, CRADLE_INNER, CRADLE_FRONT, CRADLE_TOP, CRADLE_OUTER, MOUNT, shade);
        box(consumer, pose, CRADLE_BACK, CRADLE_BOTTOM, -CRADLE_OUTER, CRADLE_FRONT, CRADLE_TOP, -CRADLE_INNER, MOUNT, shade);
        box(consumer, pose, -0.19F, -0.055F, -CRADLE_OUTER, -0.01F, 0.055F, CRADLE_OUTER, DISH_BACK, shade);
        dish(consumer, pose, Mth.lerp(open, DISH_STOWED_SCALE, 1.0F), Mth.clamp((open - RIGGING_FROM) / (1.0F - RIGGING_FROM), 0.0F, 1.0F), shade);
        stack.popPose();

        stack.popPose();
        stack.popPose();
    }

    /**
     * The status lamps on the roof plate: a fast red flash while an alert is in force, a steady amber once
     * the dish has locked onto a storm, and a dim blue-grey while it is still sweeping for one.
     *
     * <p>They sit on the plate at 14/16, which is the model's roof top, so they read as fitted to the
     * housing rather than floating over it.
     */
    private static void statusLamps(VertexConsumer consumer, PoseStack.Pose pose, WeatherAlertSirenBlockEntity entity, float partialTick, float shade) {
        long time = entity.getLevel() == null ? 0L : entity.getLevel().getGameTime();
        int color;
        if (entity.getPower() > 0) {
            float phase = Mth.sin((time + partialTick) * 0.30F);
            int alpha = (int) (140.0F + 115.0F * (0.5F + 0.5F * phase));
            color = alpha << 24 | 0xFF4436;
        } else if (entity.trackingTarget()) {
            color = 0xFFE0A63A;
        } else {
            color = 0xFF5B6B78;
        }
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            float x = 0.5F + ((quadrant & 1) == 0 ? -LAMP_OFFSET : LAMP_OFFSET);
            float z = 0.5F + ((quadrant & 2) == 0 ? -LAMP_OFFSET : LAMP_OFFSET);
            box(consumer, pose, x - LAMP_HALF, LAMP_Y0, z - LAMP_HALF, x + LAMP_HALF, LAMP_Y1, z + LAMP_HALF, color, shade);
        }
    }

    /**
     * The dish, built around the elevation axis with its boresight down +X and its vertex at the origin.
     *
     * <p>The surface is a real parabola rather than a cone: {@code x = -D (r/R)^2}, so the rim sits
     * {@link #DISH_DEPTH} behind the vertex and the feed horn hangs at the focus in front of it. A shallow
     * cone is what a dish looks like when it has been guessed at, and it is the difference between a
     * satellite dish and a funnel.
     *
     * <p>Two things keep it from reading as a white blob. The rings are shaded from the darker centre out to
     * a bright rim, which is what makes a bowl read as concave from either side, and every
     * {@link #DISH_SEGMENTS_PER_PANEL} segments are shaded as one reflector panel, so the surface has seams
     * the way a real segmented dish does.
     *
     * <p>The tripod and the feed horn are held back until {@code rigging} starts to rise: a horn floating in
     * front of a half-unfolded dish is the one thing in this render that reads as a mistake rather than as an
     * antenna, and it is the state a player watching the mast climb would otherwise see.
     */
    private static void dish(VertexConsumer consumer, PoseStack.Pose pose, float size, float rigging, float shade) {
        float radius = DISH_RADIUS * size;
        float depth = DISH_DEPTH * size;
        for (int ring = 1; ring <= DISH_RINGS; ring++) {
            float r0 = radius * (ring - 1) / DISH_RINGS;
            float r1 = radius * ring / DISH_RINGS;
            float x0 = -depth * (r0 / radius) * (r0 / radius);
            float x1 = -depth * (r1 / radius) * (r1 / radius);
            float depthFraction = (ring - 0.5F) / DISH_RINGS;
            int panel = mix(DISH_INNER, DISH_OUTER, depthFraction);
            int panelSeam = mix(DISH_INNER, DISH_OUTER, Math.min(1.0F, depthFraction + 0.18F));
            int litPanel = RenderQuads.lit(panel, shade);
            int litSeam = RenderQuads.lit(panelSeam, shade);
            for (int segment = 0; segment < DISH_SEGMENTS; segment++) {
                float a0 = (float) (2.0D * Math.PI * segment / DISH_SEGMENTS);
                float a1 = (float) (2.0D * Math.PI * (segment + 1) / DISH_SEGMENTS);
                // Radial panels: the seam between two panels is a touch darker than the panels themselves,
                // and the panels alternate by a hair, so the surface tessellates instead of reading flat.
                boolean seam = segment % DISH_SEGMENTS_PER_PANEL == DISH_SEGMENTS_PER_PANEL - 1;
                boolean oddPanel = segment / DISH_SEGMENTS_PER_PANEL % 2 == 1;
                int color = seam ? litSeam : oddPanel ? RenderQuads.lit(panel, shade * 0.93F) : litPanel;
                quad4(consumer, pose,
                        x0, dishY(r0, a0), dishZ(r0, a0),
                        x0, dishY(r0, a1), dishZ(r0, a1),
                        x1, dishY(r1, a1), dishZ(r1, a1),
                        x1, dishY(r1, a0), dishZ(r1, a0),
                        color);
            }
        }

        // Rolled rim: a lip bent back from the edge, which is what gives the shell a thickness read.
        float lipRadius = radius + RIM_OUT * size;
        float lipX = -depth - RIM_LIP * size;
        int rim = RenderQuads.lit(DISH_RIM, shade);
        for (int segment = 0; segment < DISH_SEGMENTS; segment++) {
            float a0 = (float) (2.0D * Math.PI * segment / DISH_SEGMENTS);
            float a1 = (float) (2.0D * Math.PI * (segment + 1) / DISH_SEGMENTS);
            quad4(consumer, pose,
                    -depth, dishY(radius, a0), dishZ(radius, a0),
                    -depth, dishY(radius, a1), dishZ(radius, a1),
                    lipX, dishY(lipRadius, a1), dishZ(lipRadius, a1),
                    lipX, dishY(lipRadius, a0), dishZ(lipRadius, a0),
                    rim);
        }

        if (rigging <= 0.01F) {
            return;
        }
        float focusX = DISH_FOCAL_X * size;
        int strut = RenderQuads.lit(STRUT, shade);
        float strutHalf = STRUT_HALF_WIDTH * size * rigging;
        for (float degrees : STRUT_ANGLES) {
            float angle = (float) Math.toRadians(degrees);
            float strutR = radius * 0.80F;
            float ratio = strutR / radius;
            blade(consumer, pose,
                    -depth * ratio * ratio, dishY(strutR, angle), dishZ(strutR, angle),
                    focusX + 0.02F * size * rigging, 0.0F, 0.0F,
                    strutHalf, strut);
        }
        horn(consumer, pose, focusX, size * rigging, shade);
    }

    /**
     * The feed horn: a short cone with its mouth turned back toward the reflector and a capped neck, which
     * is what makes a dish's feed read as a device hanging at the focus instead of a floating dot.
     */
    private static void horn(VertexConsumer consumer, PoseStack.Pose pose, float focusX, float size, float shade) {
        if (size < 1.0E-3F) {
            return;
        }
        float mouthRadius = HORN_MOUTH_RADIUS * size;
        float neckRadius = HORN_NECK_RADIUS * size;
        float mouthX = focusX - HORN_LENGTH * 0.5F * size;
        float neckX = focusX + HORN_LENGTH * 0.5F * size;
        int body = RenderQuads.lit(FEED, shade);
        int mouth = RenderQuads.lit(FEED_MOUTH, shade);
        int segments = 8;
        for (int segment = 0; segment < segments; segment++) {
            float a0 = (float) (2.0D * Math.PI * segment / segments);
            float a1 = (float) (2.0D * Math.PI * (segment + 1) / segments);
            // Mouth at -X so it faces the reflector, neck at +X behind it.
            quad4(consumer, pose,
                    mouthX, mouthRadius * Mth.sin(a0), mouthRadius * Mth.cos(a0),
                    mouthX, mouthRadius * Mth.sin(a1), mouthRadius * Mth.cos(a1),
                    neckX, neckRadius * Mth.sin(a1), neckRadius * Mth.cos(a1),
                    neckX, neckRadius * Mth.sin(a0), neckRadius * Mth.cos(a0),
                    body);
            // The mouth is a disc, drawn as a fan of degenerate quads - cheap, and it reads as an opening.
            quad4(consumer, pose,
                    mouthX, 0.0F, 0.0F,
                    mouthX, mouthRadius * Mth.sin(a0), mouthRadius * Mth.cos(a0),
                    mouthX, mouthRadius * Mth.sin(a1), mouthRadius * Mth.cos(a1),
                    mouthX, 0.0F, 0.0F,
                    mouth);
            quad4(consumer, pose,
                    neckX, 0.0F, 0.0F,
                    neckX, neckRadius * Mth.sin(a1), neckRadius * Mth.cos(a1),
                    neckX, neckRadius * Mth.sin(a0), neckRadius * Mth.cos(a0),
                    neckX, 0.0F, 0.0F,
                    body);
        }
    }

    private static float dishY(float radius, float angle) {
        return radius * Mth.sin(angle);
    }

    private static float dishZ(float radius, float angle) {
        return radius * Mth.cos(angle);
    }

    /** Linear blend of two packed colours, used for the dish's centre-to-rim gradient. */
    private static int mix(int from, int to, float t) {
        float f = Mth.clamp(t, 0.0F, 1.0F);
        int a = Math.round(Mth.lerp(f, from >>> 24 & 255, to >>> 24 & 255));
        int r = Math.round(Mth.lerp(f, from >>> 16 & 255, to >>> 16 & 255));
        int g = Math.round(Mth.lerp(f, from >>> 8 & 255, to >>> 8 & 255));
        int b = Math.round(Mth.lerp(f, from & 255, to & 255));
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static void box(VertexConsumer consumer, PoseStack.Pose pose, float centerX, float y0, float centerZ, float halfXZ, float height, int color, float shade) {
        box(consumer, pose, centerX - halfXZ, y0, centerZ - halfXZ, centerX + halfXZ, y0 + height, centerZ + halfXZ, color, shade);
    }

    /**
     * An axis-aligned box from six quads, each face shaded the way a vanilla block face is.
     *
     * <p>Both halves matter. A box is the right primitive because the instrument renderers otherwise draw
     * flat plates, and a dish mount made of plates reads as origami from any angle but straight on. The
     * per-face shading is what makes that box read as Minecraft geometry: with a single flat colour the
     * metalwork looks like smooth plastic standing next to a texture atlas, and with the vanilla up/down/
     * north/east steps it looks like it was built out of blocks.
     */
    private static void box(VertexConsumer consumer, PoseStack.Pose pose, float x0, float y0, float z0, float x1, float y1, float z1, int color, float shade) {
        quad4(consumer, pose, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, RenderQuads.lit(color, shade * FACE_UP));
        quad4(consumer, pose, x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0, RenderQuads.lit(color, shade * FACE_DOWN));
        quad4(consumer, pose, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, RenderQuads.lit(color, shade * FACE_Z));
        quad4(consumer, pose, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1, RenderQuads.lit(color, shade * FACE_Z));
        quad4(consumer, pose, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, RenderQuads.lit(color, shade * FACE_X));
        quad4(consumer, pose, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, RenderQuads.lit(color, shade * FACE_X));
    }

    /**
     * A rod between two points, as two quads crossed at right angles.
     *
     * <p>Colour-only geometry has no diameter, so a single quad vanishes edge-on; crossing two of them is
     * the cheapest way to make a strut that is visible from every direction.
     */
    private static void blade(VertexConsumer consumer, PoseStack.Pose pose,
                              float ax, float ay, float az, float bx, float by, float bz,
                              float halfWidth, int color) {
        float ux = bx - ax;
        float uy = by - ay;
        float uz = bz - az;
        float length = Mth.sqrt(ux * ux + uy * uy + uz * uz);
        if (length < 1.0E-4F) {
            return;
        }
        ux /= length;
        uy /= length;
        uz /= length;
        // Any perpendicular to the axis will do; the vertical one is only a bad choice when the rod is
        // itself vertical, which is the one case it is replaced.
        float px;
        float py;
        float pz;
        if (Math.abs(uy) > 0.95F) {
            px = 1.0F;
            py = 0.0F;
            pz = 0.0F;
        } else {
            px = -uz;
            py = 0.0F;
            pz = ux;
        }
        float pl = Mth.sqrt(px * px + py * py + pz * pz);
        px /= pl;
        py /= pl;
        pz /= pl;
        float qx = uy * pz - uz * py;
        float qy = uz * px - ux * pz;
        float qz = ux * py - uy * px;
        quad4(consumer, pose,
                ax + px * halfWidth, ay + py * halfWidth, az + pz * halfWidth,
                bx + px * halfWidth, by + py * halfWidth, bz + pz * halfWidth,
                bx - px * halfWidth, by - py * halfWidth, bz - pz * halfWidth,
                ax - px * halfWidth, ay - py * halfWidth, az - pz * halfWidth, color);
        quad4(consumer, pose,
                ax + qx * halfWidth, ay + qy * halfWidth, az + qz * halfWidth,
                bx + qx * halfWidth, by + qy * halfWidth, bz + qz * halfWidth,
                bx - qx * halfWidth, by - qy * halfWidth, bz - qz * halfWidth,
                ax - qx * halfWidth, ay - qy * halfWidth, az - qz * halfWidth, color);
    }

    private static void quad4(VertexConsumer consumer, PoseStack.Pose pose,
                              float ax, float ay, float az, float bx, float by, float bz,
                              float cx, float cy, float cz, float dx, float dy, float dz, int color) {
        int a = color >>> 24 & 255;
        int r = color >>> 16 & 255;
        int g = color >>> 8 & 255;
        int b = color & 255;
        consumer.addVertex(pose, ax, ay, az).setColor(r, g, b, a);
        consumer.addVertex(pose, bx, by, bz).setColor(r, g, b, a);
        consumer.addVertex(pose, cx, cy, cz).setColor(r, g, b, a);
        consumer.addVertex(pose, dx, dy, dz).setColor(r, g, b, a);
    }
}
