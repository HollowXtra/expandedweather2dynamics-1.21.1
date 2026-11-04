package com.imbet.client;

import com.imbet.alert.TornadoSirenBlockEntity;
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
 * The three pole-mounted sirens, drawn as the machines they are.
 *
 * <p>These are not one model with a recolour. A mechanical siren is a rotor with a turning head: the whole
 * blower and its trumpet swing around the mast, because a siren whose rotor only screamed in one direction
 * would reach one half of the town and not the other. An electronic siren is a cluster of horns on a frame
 * over a control cabinet with a lamp, because an amplifier-driven siren has nothing that moves and needs
 * coverage from several directions at once. A two-tone siren has two projectors on a yoke that rocks, and
 * the rock is not decoration either: the notes alternate between the two horns, so the machine visibly
 * hands the sound from one side to the other.
 *
 * <p>Three house rules apply, the same ones the other instrument renderers follow. Everything is drawn
 * with {@code debugQuads()} - colour-only, no depth write - so the draw order runs inside out: the mast,
 * then the housing that swallows it, then whatever sits proud of that. All lighting is ours, because the
 * render type carries no lightmap: {@link #box} shades each face the way a vanilla block face is shaded,
 * and {@link #frustumX} bands a horn from throat to mouth so a trumpet reads as a trumpet rather than as
 * smooth plastic beside a texture atlas. And the bezel geometry is shared with the rest of the mod rather
 * than reimplemented, so a siren standing next to an anemometer is lit identically.
 */
public class TornadoSirenRenderer implements BlockEntityRenderer<TornadoSirenBlockEntity> {
    // ---- the mast they all share ----

    private static final float PLINTH_HALF = 0.23F;
    private static final float PLINTH_TOP = 0.10F;
    private static final float POLE_HALF = 0.056F;
    private static final float POLE_TOP = 1.34F;
    /** Bolted pipe flanges down the mast, at the heights a stack of pipe sections would be joined. */
    private static final float[] FLANGE_YS = {0.34F, 0.72F, 1.10F};
    private static final float FLANGE_HALF = 0.080F;
    private static final float FLANGE_HEIGHT = 0.036F;
    /** The head plate every machine bolts onto. */
    private static final float PLATE_HALF = 0.135F;
    private static final float PLATE_TOP = 1.42F;

    // ---- mechanical: a rotor with a turning head ----

    /** Height of the blower's axis, which is the height the whole head turns about. */
    private static final float BLOWER_AXIS_Y = 1.63F;
    private static final float BLOWER_RADIUS = 0.165F;
    private static final float BLOWER_HALF = 0.155F;
    private static final int BLOWER_SEGMENTS = 18;
    /** The motor behind the blower, and the fins that cool it. */
    private static final float MOTOR_RADIUS = 0.098F;
    private static final float MOTOR_HALF = 0.115F;
    /** The trumpet: throat at the blower, mouth out in clear air, tilted up as a real one is. */
    private static final float TRUMPET_THROAT_X = 0.12F;
    private static final float TRUMPET_MOUTH_X = 0.56F;
    private static final float TRUMPET_THROAT_RADIUS = 0.052F;
    private static final float TRUMPET_MOUTH_RADIUS = 0.135F;
    private static final float TRUMPET_RISE = 0.085F;

    // ---- electronic: horns on a frame over a cabinet ----

    /** The cabinet's door and its vents, standing against the mast. */
    private static final float CABINET_HALF = 0.185F;
    private static final float CABINET_BOTTOM = 0.14F;
    private static final float CABINET_TOP = 0.74F;
    private static final float CABINET_DEPTH = 0.22F;
    /** Four horns, aimed into the four quarters, so the block covers a full circle. */
    private static final float[] HORN_YAW_DEGREES = {45.0F, 135.0F, 225.0F, 315.0F};
    private static final float HORN_TILT_DEGREES = 14.0F;
    private static final float HORN_THROAT_X = 0.04F;
    private static final float HORN_MOUTH_X = 0.30F;
    private static final float HORN_THROAT_RADIUS = 0.048F;
    private static final float HORN_MOUTH_RADIUS = 0.104F;
    private static final float HORN_AXIS_Y = 1.60F;
    /** The beacon dome above the horns. */
    private static final float DOME_Y = 1.72F;
    private static final float DOME_RADIUS = 0.095F;

    // ---- two-tone: two projectors on a rocking yoke ----

    private static final float YOKE_AXIS_Y = 1.62F;
    private static final float YOKE_HALF_LENGTH = 0.075F;
    private static final float YOKE_HEIGHT = 0.075F;
    private static final float PROJECTOR_THROAT_X = 0.07F;
    private static final float PROJECTOR_MOUTH_X = 0.46F;
    private static final float PROJECTOR_THROAT_RADIUS = 0.056F;
    private static final float PROJECTOR_MOUTH_RADIUS = 0.145F;
    /** The valve box at the yoke's centre, where the two projectors meet the feed. */
    private static final float VALVE_HALF = 0.105F;

    // ---- colours ----

    private static final int CONCRETE = 0xFF9A9A94;
    private static final int STEEL = 0xFFA9B1B8;
    private static final int STEEL_DARK = 0xFF7C848C;
    private static final int FLANGE = 0xFF8E969E;
    private static final int ROTOR = 0xFFB9C1C8;
    private static final int ROTOR_VANE = 0xFF6E767E;
    private static final int TRUMPET = 0xFFC7CED5;
    private static final int TRUMPET_MOUTH = 0xFF4C5258;
    private static final int MOTOR = 0xFF5F6770;
    private static final int CABINET = 0xFF41474D;
    private static final int CABINET_FACE = 0xFF565D64;
    private static final int SAFETY_YELLOW = 0xFFE8B21E;
    private static final int SAFETY_BLACK = 0xFF23262A;
    private static final int HORN = 0xFFD3D9DE;
    private static final int LENS = 0xFF2A2F35;

    // Vanilla's own block-face shading: a face's brightness depends on which way it points.
    private static final float FACE_UP = 1.00F;
    private static final float FACE_DOWN = 0.55F;
    private static final float FACE_X = 0.66F;
    private static final float FACE_Z = 0.84F;

    public TornadoSirenRenderer(BlockEntityRendererProvider.Context context) {
    }

    /**
     * A siren stands well over the block below it and its projector reaches out too, so the cull box grows
     * with it. Left at one block, a siren pops out of existence at the edge of the screen while it is still
     * plainly on it - and a siren that vanishes is worse than one that pops.
     */
    @Override
    public AABB getRenderBoundingBox(TornadoSirenBlockEntity entity) {
        return new AABB(entity.getBlockPos()).inflate(1.0D, 2.0D, 1.0D);
    }

    @Override
    public void render(TornadoSirenBlockEntity entity, float partialTick, PoseStack stack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        VertexConsumer consumer = buffer.getBuffer(RenderType.debugQuads());
        float shade = RenderQuads.lightShade(entity.getLevel(), packedLight);
        float spin = entity.renderSpin(partialTick);
        float beacon = entity.renderBeacon(partialTick);
        boolean sounding = entity.sounding();
        // The beacon only lights while the siren is actually sounding, so a standing siren reads as idle
        // from across the field rather than as a lit ornament.
        int lampColor = sounding ? mix(LENS, entity.beaconColor(), 0.25F + 0.75F * beacon) : LENS;

        mast(consumer, stack, shade);
        switch (entity.tone()) {
            case MECHANICAL -> mechanical(consumer, stack, shade, spin, lampColor, sounding);

            case ELECTRONIC -> electronic(consumer, stack, shade, lampColor, beacon, sounding);

            case HILO -> hilo(consumer, stack, shade, spin, lampColor, sounding);
        }
    }

    /** The pedestal and mast, drawn before everything that sits on top of it. */
    private static void mast(VertexConsumer consumer, PoseStack stack, float shade) {
        PoseStack.Pose pose = stack.last();
        box(consumer, pose, 0.5F, 0.0F, 0.5F, PLINTH_HALF, PLINTH_TOP, CONCRETE, shade);
        box(consumer, pose, 0.5F, PLINTH_TOP, 0.5F, POLE_HALF, POLE_TOP - PLINTH_TOP, STEEL, shade);
        for (float y : FLANGE_YS) {
            box(consumer, pose, 0.5F, y, 0.5F, FLANGE_HALF, FLANGE_HEIGHT, FLANGE, shade);
        }
        box(consumer, pose, 0.5F, POLE_TOP, 0.5F, PLATE_HALF, PLATE_TOP - POLE_TOP, STEEL_DARK, shade);
    }

    /**
     * The mechanical siren: a blowing rotor with a trumpet, turning as a unit.
     *
     * <p>The whole head is drawn inside a rotation about the mast's axis. That is how the machine works -
     * one motor turns the rotor and its projector together - and it is why this siren is the loudest thing
     * in the mod: everything it has is pointed one way at a time.
     */
    private static void mechanical(VertexConsumer consumer, PoseStack stack, float shade, float spin, int lampColor, boolean sounding) {
        stack.pushPose();
        stack.translate(0.5D, BLOWER_AXIS_Y, 0.5D);
        stack.mulPose(Axis.YP.rotationDegrees(spin));
        PoseStack.Pose pose = stack.last();

        // Motor and its cooling fins, on the -X side of the blower.
        frustumX(consumer, pose, -BLOWER_HALF - MOTOR_HALF * 2.0F, -BLOWER_HALF, MOTOR_RADIUS, MOTOR_RADIUS * 0.94F, 12, MOTOR, shade);
        for (int i = 0; i < 5; i++) {
            float x = -BLOWER_HALF - MOTOR_HALF * 0.35F - i * MOTOR_HALF * 0.32F;
            box(consumer, pose, x, MOTOR_RADIUS * 0.55F, 0.0F, 0.012F, MOTOR_RADIUS * 1.22F, MOTOR, shade);
        }
        // The blower housing, seen end-on with its intake screen.
        frustumX(consumer, pose, -BLOWER_HALF, BLOWER_HALF, BLOWER_RADIUS, BLOWER_RADIUS, BLOWER_SEGMENTS, ROTOR, shade);
        coneX(consumer, pose, BLOWER_HALF, BLOWER_RADIUS * 1.02F, BLOWER_RADIUS * 0.42F, 0.012F, BLOWER_SEGMENTS, ROTOR_VANE, shade);
        // The rotor inside, visible because the mouth is dark: a hub with its vanes.
        coneX(consumer, pose, -BLOWER_HALF, BLOWER_RADIUS * 0.30F, BLOWER_RADIUS * 0.90F, 0.004F, 14, SAFETY_BLACK, shade);
        // The trumpet, rising slightly as it projects.
        stack.pushPose();
        stack.translate(TRUMPET_THROAT_X, 0.0D, 0.0D);
        stack.mulPose(Axis.ZP.rotationDegrees(-(float) Math.toDegrees(Math.atan2(TRUMPET_RISE, TRUMPET_MOUTH_X - TRUMPET_THROAT_X))));
        PoseStack.Pose trumpet = stack.last();
        float trumpetLength = TRUMPET_MOUTH_X - TRUMPET_THROAT_X;
        frustumX(consumer, trumpet, -TRUMPET_THROAT_X, 0.0F, TRUMPET_THROAT_RADIUS, TRUMPET_THROAT_RADIUS, 12, STEEL_DARK, shade);
        frustumX(consumer, trumpet, 0.0F, trumpetLength, TRUMPET_THROAT_RADIUS, TRUMPET_MOUTH_RADIUS, 16, TRUMPET, shade);
        ringX(consumer, trumpet, trumpetLength, TRUMPET_MOUTH_RADIUS, 0.022F, 16, STEEL_DARK, shade);
        // The mouth is a dark bore: an opening reads as depth even with no depth buffer to sort it with.
        coneX(consumer, trumpet, trumpetLength - 0.004F, TRUMPET_MOUTH_RADIUS * 0.98F, 0.0F, 0.006F, 16, TRUMPET_MOUTH, 1.0F);
        stack.popPose();
        // A lamp on the blower's crown, which is where a real one carries it: clear of the trumpet's swing
        // and visible while the head is pointing away.
        lampDome(consumer, stack, 0.5F, BLOWER_AXIS_Y + BLOWER_RADIUS, 0.5F, 0.058F,
                sounding ? lampColor : LENS, sounding ? 1.0F : 1.0F);
        stack.popPose();
    }

    /**
     * The electronic siren: a horn cluster over its control cabinet.
     *
     * <p>Nothing here moves, because nothing in an amplifier-driven siren does. What changes is the lamp:
     * it flashes on the tone's own attack ramp, so the light and the sound are the same signal.
     */
    private static void electronic(VertexConsumer consumer, PoseStack stack, float shade, int lampColor, float beacon, boolean sounding) {
        PoseStack.Pose pose = stack.last();
        // Cabinet against the mast, with its door face, a vent and a safety stripe.
        float z0 = 0.5F - CABINET_DEPTH * 0.5F;
        box(consumer, pose, 0.5F - CABINET_HALF, CABINET_BOTTOM, z0, 0.5F + CABINET_HALF, CABINET_TOP, z0 + CABINET_DEPTH, CABINET, shade);
        box(consumer, pose, 0.5F - CABINET_HALF * 0.82F, CABINET_BOTTOM + 0.06F, z0 - 0.012F, 0.5F + CABINET_HALF * 0.82F, CABINET_TOP - 0.06F, z0, CABINET_FACE, shade);
        box(consumer, pose, 0.5F - CABINET_HALF * 0.70F, CABINET_BOTTOM + 0.10F, z0 - 0.018F, 0.5F + CABINET_HALF * 0.70F, CABINET_BOTTOM + 0.20F, z0 - 0.012F, LENS, shade);
        box(consumer, pose, 0.5F - CABINET_HALF * 0.70F, CABINET_TOP - 0.20F, z0 - 0.018F, 0.5F + CABINET_HALF * 0.70F, CABINET_TOP - 0.11F, z0 - 0.012F, SAFETY_YELLOW, shade);
        // The frame the horns hang on.
        blade(consumer, pose, 0.5F, PLATE_TOP, 0.5F, 0.5F, HORN_AXIS_Y, 0.5F, 0.035F, STEEL_DARK);
        // Four horns, aimed into the four quarters.
        for (float yaw : HORN_YAW_DEGREES) {
            stack.pushPose();
            stack.translate(0.5D, HORN_AXIS_Y, 0.5D);
            stack.mulPose(Axis.YP.rotationDegrees(yaw));
            stack.mulPose(Axis.ZP.rotationDegrees(-HORN_TILT_DEGREES));
            PoseStack.Pose horn = stack.last();
            // A square horn, four-sided rather than round: rectangular projectors cover a quadrant better
            // than a round flare does, which is why the amplifier-driven sirens use them.
            frustumX(consumer, horn, HORN_THROAT_X * 0.5F, HORN_MOUTH_X, HORN_THROAT_RADIUS, HORN_MOUTH_RADIUS, 4, HORN, shade);
            box(consumer, horn, HORN_MOUTH_X - 0.018F, -HORN_MOUTH_RADIUS * 1.06F, -HORN_MOUTH_RADIUS * 1.06F, HORN_MOUTH_X, HORN_MOUTH_RADIUS * 1.06F, HORN_MOUTH_RADIUS * 1.06F, STEEL_DARK, shade);
            coneX(consumer, horn, HORN_MOUTH_X - 0.006F, HORN_MOUTH_RADIUS * 0.94F, HORN_MOUTH_RADIUS * 0.6F, 0.010F, 4, TRUMPET_MOUTH, 0.9F);
            stack.popPose();
        }
        // Beacon dome, standing proud of the cluster so it is not lost inside it.
        lampDome(consumer, stack, 0.5F, DOME_Y, 0.5F, DOME_RADIUS, sounding ? lampColor : LENS, sounding ? 0.45F + 0.55F * beacon : 1.0F);
    }

    /**
     * The two-tone siren: twin projectors on a yoke that rocks.
     *
     * <p>The rock is the whole point of the machine. The notes alternate between two horns facing opposite
     * ways, so as the yoke swings the low note comes from one side of the street and the high note from the
     * other. The block entity supplies the angle from the tone's own two second step, so the horns turn over
     * exactly as the note changes.
     */
    private static void hilo(VertexConsumer consumer, PoseStack stack, float shade, float spin, int lampColor, boolean sounding) {
        stack.pushPose();
        stack.translate(0.5D, YOKE_AXIS_Y, 0.5D);
        stack.mulPose(Axis.YP.rotationDegrees(spin));
        PoseStack.Pose pose = stack.last();
        // The valve box at the centre, where the feed splits into the two projectors.
        box(consumer, pose, 0.5F, -YOKE_HEIGHT * 0.5F, 0.5F, VALVE_HALF, YOKE_HEIGHT, STEEL_DARK, shade);
        // The yoke bar, and the two projectors on its ends.
        box(consumer, pose, 0.5F - YOKE_HALF_LENGTH, -0.03F, 0.5F, 0.5F + YOKE_HALF_LENGTH, 0.03F, STEEL, shade);
        for (int side = 0; side < 2; side++) {
            float sign = side == 0 ? 1.0F : -1.0F;
            frustumX(consumer, pose, 0.5F + sign * PROJECTOR_THROAT_X, 0.5F + sign * PROJECTOR_MOUTH_X,
                    PROJECTOR_THROAT_RADIUS, PROJECTOR_MOUTH_RADIUS, 14, STEEL, shade);
            ringX(consumer, pose, 0.5F + sign * PROJECTOR_MOUTH_X, PROJECTOR_MOUTH_RADIUS, 0.022F, 14, STEEL_DARK, shade);
            coneX(consumer, pose, 0.5F + sign * (PROJECTOR_MOUTH_X - 0.006F), PROJECTOR_MOUTH_RADIUS * 0.96F, 0.0F, 0.008F, 14, TRUMPET_MOUTH, 1.0F);
        }
        stack.popPose();
        // A small lamp on the yoke's centre, so the machine is readable as sounding from behind too.
        lampDome(consumer, stack, 0.5F, YOKE_AXIS_Y + YOKE_HEIGHT * 0.5F, 0.5F, 0.062F,
                sounding ? lampColor : LENS, sounding ? 0.75F : 1.0F);
    }

    /**
     * A lamp lens facing straight up.
     *
     * <p>Drawn in a frame rotated so the cone primitive's own axis points at the sky. Writing a second
     * cone routine for the vertical case would mean two sets of shading rules to keep in step, and the
     * rotation is one call.
     */
    private static void lampDome(VertexConsumer consumer, PoseStack stack, float x, float y, float z, float radius, int color, float glow) {
        stack.pushPose();
        stack.translate(x, y - 0.02D, z);
        stack.mulPose(Axis.ZP.rotationDegrees(90.0F));
        PoseStack.Pose pose = stack.last();
        int lit = RenderQuads.lit(color, Mth.clamp(glow, 0.0F, 1.0F));
        coneX(consumer, pose, 0.0F, radius, 0.0F, radius * 0.85F, 12, lit, 1.0F);
        stack.popPose();
    }

    /**
     * A frustum along the X axis, closed at both ends: the primitive every horn and rotor here is made of.
     *
     * <p>The colour is banded from throat to mouth by {@code tone} rather than kept flat, which is what
     * stops a smooth flare from reading as a plastic cone: a horn's inside really is darker than its rim,
     * and the banding gives the eye the same cue a texture would.
     */
    private static void frustumX(VertexConsumer consumer, PoseStack.Pose pose, float x0, float x1, float r0, float r1, int segments, int color, float shade) {
        if (segments <= 2) {
            // The electronic siren's horns are square, which is one segment of a four-gon.
            segments = 4;
        }
        // Half a segment of offset, so a four-sided horn comes out as a square rather than a diamond: an
        // axis-aligned mouth matches the block grid the machine is standing on.
        double step = Math.PI * 2.0D / segments;
        double offset = step * 0.5D;
        for (int i = 0; i < segments; i++) {
            float a0 = (float) (i * step + offset);
            float a1 = (float) ((i + 1) * step + offset);
            // Each segment is shaded by where it faces, the same way a block face is.
            float facing = 0.72F + 0.28F * Mth.cos((a0 + a1) * 0.5F - (float) offset);
            quad4(consumer, pose,
                    x0, r0 * Mth.sin(a0), r0 * Mth.cos(a0),
                    x0, r0 * Mth.sin(a1), r0 * Mth.cos(a1),
                    x1, r1 * Mth.sin(a1), r1 * Mth.cos(a1),
                    x1, r1 * Mth.sin(a0), r1 * Mth.cos(a0),
                    RenderQuads.lit(color, shade * facing));
        }
    }

    /** A ring around the X axis at {@code x}, standing proud by {@code thickness}: a horn's rim. */
    private static void ringX(VertexConsumer consumer, PoseStack.Pose pose, float x, float radius, float thickness, int segments, int color, float shade) {
        double step = Math.PI * 2.0D / segments;
        double offset = step * 0.5D;
        for (int i = 0; i < segments; i++) {
            float a0 = (float) (i * step + offset);
            float a1 = (float) ((i + 1) * step + offset);
            float outer = radius + thickness;
            quad4(consumer, pose,
                    x, outer * Mth.sin(a0), outer * Mth.cos(a0),
                    x, outer * Mth.sin(a1), outer * Mth.cos(a1),
                    x, radius * Mth.sin(a1), radius * Mth.cos(a1),
                    x, radius * Mth.sin(a0), radius * Mth.cos(a0),
                    RenderQuads.lit(color, shade));
            quad4(consumer, pose,
                    x, radius * Mth.sin(a0), radius * Mth.cos(a0),
                    x + thickness * 0.7F, outer * Mth.sin(a0), outer * Mth.cos(a0),
                    x + thickness * 0.7F, outer * Mth.sin(a1), outer * Mth.cos(a1),
                    x, radius * Mth.sin(a1), radius * Mth.cos(a1),
                    RenderQuads.lit(color, shade * 0.86F));
        }
    }

    /**
     * A cone or disc facing +X: the end caps, the intake screens and the lamp lenses.
     *
     * <p>Depth is drawn as a fan of degenerate quads rather than as a filled disc, because colour-only
     * geometry has no depth buffer to sort a disc's own vertices with: a flat disc facing away from the
     * camera would still be painted on top of the rim behind it. A fan that collapses to its centre point
     * shades outward from the middle instead, which reads as a recess at any angle.
     */
    private static void coneX(VertexConsumer consumer, PoseStack.Pose pose, float x, float outerRadius, float innerRadius, float depth, int segments, int color, float shade) {
        double step = Math.PI * 2.0D / segments;
        double offset = step * 0.5D;
        if (depth <= 0.0F) {
            // A flat disc: one degenerate fan, which the render type has no way to cull.
            for (int i = 0; i < segments; i++) {
                float a0 = (float) (i * step + offset);
                float a1 = (float) ((i + 1) * step + offset);
                quad4(consumer, pose,
                        x, 0.0F, 0.0F,
                        x, outerRadius * Mth.sin(a0), outerRadius * Mth.cos(a0),
                        x, outerRadius * Mth.sin(a1), outerRadius * Mth.cos(a1),
                        x, 0.0F, 0.0F,
                        RenderQuads.lit(color, shade));
            }
            return;
        }
        for (int i = 0; i < segments; i++) {
            float a0 = (float) (i * step + offset);
            float a1 = (float) ((i + 1) * step + offset);
            // The nose of the cone, so the cap reads as coming toward the viewer.
            quad4(consumer, pose,
                    x + depth, innerRadius * Mth.sin(a0), innerRadius * Mth.cos(a0),
                    x, outerRadius * Mth.sin(a0), outerRadius * Mth.cos(a0),
                    x, outerRadius * Mth.sin(a1), outerRadius * Mth.cos(a1),
                    x + depth, innerRadius * Mth.sin(a1), innerRadius * Mth.cos(a1),
                    RenderQuads.lit(color, shade));
        }
    }

    /** Linear blend of two packed colours, used to light the beacon from its own alert colour. */
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

    /** An axis-aligned box from six quads, each face shaded the way a vanilla block face is. */
    private static void box(VertexConsumer consumer, PoseStack.Pose pose, float x0, float y0, float z0, float x1, float y1, float z1, int color, float shade) {
        quad4(consumer, pose, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, RenderQuads.lit(color, shade * FACE_UP));
        quad4(consumer, pose, x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0, RenderQuads.lit(color, shade * FACE_DOWN));
        quad4(consumer, pose, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, RenderQuads.lit(color, shade * FACE_Z));
        quad4(consumer, pose, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1, RenderQuads.lit(color, shade * FACE_Z));
        quad4(consumer, pose, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, RenderQuads.lit(color, shade * FACE_X));
        quad4(consumer, pose, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, RenderQuads.lit(color, shade * FACE_X));
    }

    /** A rod between two points, as two quads crossed at right angles, so it never vanishes edge-on. */
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
