package com.imbet.client;

import com.imbet.drone.WeatherDroneEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The drone, drawn as a quadcopter: a body, four arms, four rotor discs, a camera on a gimbal, skids and an
 * antenna.
 *
 * <p>It is drawn from geometry rather than from a model file for the same reason the sirens are: the rotors
 * spin, the airframe banks into its turns, and the status lamp changes colour, all of which are per-frame
 * state that a static model cannot carry.
 *
 * <p>Two details do the work of making it read as a machine that is flying rather than one that is sliding:
 * the airframe leans forward as it accelerates and rolls into a turn, both taken from its actual velocity,
 * and the rotor discs blur as they spin - at full speed each is drawn as two crossed thin quads rather than
 * as a disc, which at a distance reads as the shimmer a fast rotor really has.
 */
public class WeatherDroneRenderer extends EntityRenderer<WeatherDroneEntity> {
    private static final float BODY_HALF = 0.19F;
    private static final float BODY_BOTTOM = 0.06F;
    private static final float BODY_TOP = 0.22F;
    private static final float ARM_LENGTH = 0.42F;
    private static final float ARM_THICKNESS = 0.035F;
    private static final float ROTOR_RADIUS = 0.21F;
    private static final float ROTOR_Y = 0.30F;
    private static final float CAMERA_RADIUS = 0.075F;
    private static final float SKID_HALF = 0.24F;

    private static final int SHELL = 0xFF3C4048;
    private static final int SHELL_LIGHT = 0xFF565B64;
    private static final int ARM = 0xFF23262B;
    private static final int ROTOR = 0xFF8A9099;
    private static final int ROTOR_BLUR = 0xFF6B7078;
    private static final int CAMERA = 0xFF1B1D21;
    private static final int LENS = 0xFF7FD4FF;
    private static final int SKID = 0xFF4A4E56;
    private static final int ANTENNA = 0xFF9AA1AA;
    private static final int LAMP_OK = 0xFF4CD964;
    private static final int LAMP_WARN = 0xFFFFA000;
    private static final int LAMP_BAD = 0xFFFF3B30;

    private static final float FACE_UP = 1.00F;
    private static final float FACE_DOWN = 0.55F;
    private static final float FACE_X = 0.66F;
    private static final float FACE_Z = 0.84F;

    public WeatherDroneRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(WeatherDroneEntity entity) {
        // Colour-only geometry: there is no texture, and the path is only ever used as a key.
        return ResourceLocation.fromNamespaceAndPath("expandedweather2dynamics", "drone");
    }

    @Override
    public void render(WeatherDroneEntity drone, float yaw, float partialTick, PoseStack stack, MultiBufferSource buffer, int packedLight) {
        VertexConsumer consumer = buffer.getBuffer(RenderType.debugQuads());
        float shade = RenderQuads.lightShade(drone.level(), packedLight);

        stack.pushPose();
        // Position is the entity origin at the belly of the airframe; the geometry is built around a body
        // whose centre is half a block up, so the whole thing is lifted to sit over the origin.
        stack.translate(0.0D, 0.16D, 0.0D);
        stack.mulPose(Axis.YP.rotationDegrees(-drone.getYRot()));

        Vec3 velocity = drone.tickMotion();
        // Bank and pitch come from the airframe's actual motion, read off its position history rather than its
        // velocity field: a client watching somebody else's drone is never told that drone's velocity, so the
        // motion is the only thing that is true on every client. The airframe is doing what its movement says
        // it is doing rather than playing its own animation.
        double yawRadians = Math.toRadians(drone.getYRot());
        double forward = velocity.x * -Math.sin(yawRadians) + velocity.z * Math.cos(yawRadians);
        double side = velocity.x * Math.cos(yawRadians) + velocity.z * Math.sin(yawRadians);
        float roll = (float) Mth.clamp(-side * 55.0D, -32.0D, 32.0D);
        float lean = (float) Mth.clamp(-forward * 70.0D, -26.0D, 26.0D);
        boolean spinning = drone.batteryFraction() > 0.0F;
        boolean tumbling = drone.overwhelmed();
        if (tumbling) {
            // A drone that has lost its fight with the wind tumbles; the angle is its own flight time, so the
            // rotation is continuous instead of restarting every frame.
            stack.mulPose(Axis.XP.rotationDegrees((float) (drone.tickCount * 11.0D % 360.0D)));
            stack.mulPose(Axis.ZP.rotationDegrees((float) (drone.tickCount * 7.0D % 360.0D)));
        } else {
            stack.mulPose(Axis.ZP.rotationDegrees(roll * 0.4F));
            stack.mulPose(Axis.XP.rotationDegrees(lean * 0.4F));
        }
        PoseStack.Pose pose = stack.last();

        // Skids first: everything else stands on them.
        box(consumer, pose, -SKID_HALF, -0.18F, -0.05F, -SKID_HALF + 0.05F, -0.06F, 0.05F, SKID, shade);
        box(consumer, pose, SKID_HALF - 0.05F, -0.18F, -0.05F, SKID_HALF, -0.06F, 0.05F, SKID, shade);
        box(consumer, pose, -SKID_HALF, -0.12F, -0.04F, SKID_HALF, -0.08F, 0.04F, SKID, shade);

        // The body, then the four arms.
        box(consumer, pose, -BODY_HALF, BODY_BOTTOM, -BODY_HALF, BODY_HALF, BODY_TOP, BODY_HALF, SHELL, shade);
        box(consumer, pose, -BODY_HALF * 0.7F, BODY_TOP, -BODY_HALF * 0.7F, BODY_HALF * 0.7F, BODY_TOP + 0.05F, BODY_HALF * 0.7F, SHELL_LIGHT, shade);
        for (int i = 0; i < 4; i++) {
            float sx = (i & 1) == 0 ? -1.0F : 1.0F;
            float sz = (i & 2) == 0 ? -1.0F : 1.0F;
            float x0 = sx * BODY_HALF * 0.8F;
            float x1 = sx * ARM_LENGTH;
            float z0 = sz * BODY_HALF * 0.8F;
            float z1 = sz * ARM_LENGTH;
            box(consumer, pose, Math.min(x0, x1), BODY_TOP - 0.05F, Math.min(z0, z1) - ARM_THICKNESS * 0.5F,
                    Math.max(x0, x1), BODY_TOP - 0.01F, Math.max(z0, z1) + ARM_THICKNESS * 0.5F, ARM, shade);
            // The motor can, then the rotor on top of it.
            box(consumer, pose, x1 - 0.05F, BODY_TOP - 0.02F, z1 - 0.05F, x1 + 0.05F, ROTOR_Y - 0.03F, z1 + 0.05F, SHELL_LIGHT, shade);
            rotor(consumer, pose, x1, z1, drone.renderRotor(partialTick), i, spinning, shade);
        }

        // Camera on its gimbal, under the nose, and the lens that glows while a link is up.
        box(consumer, pose, -CAMERA_RADIUS, 0.02F, BODY_HALF + 0.02F, CAMERA_RADIUS, 0.02F + CAMERA_RADIUS * 2.0F, BODY_HALF + 0.02F + CAMERA_RADIUS * 2.0F, CAMERA, shade);
        box(consumer, pose, -CAMERA_RADIUS * 0.6F, 0.04F, BODY_HALF + 0.02F + CAMERA_RADIUS * 2.0F,
                CAMERA_RADIUS * 0.6F, 0.02F + CAMERA_RADIUS * 1.4F, BODY_HALF + 0.03F + CAMERA_RADIUS * 2.0F,
                drone.linked() ? LENS : CAMERA, drone.linked() ? 1.0F : shade);

        // Antenna, and the status lamp on the tail of the body.
        box(consumer, pose, -0.012F, BODY_TOP + 0.05F, -BODY_HALF * 0.5F, 0.012F, BODY_TOP + 0.30F, BODY_HALF * 0.5F, ANTENNA, shade);
        int lamp = drone.batteryFraction() <= 0.0F ? LAMP_BAD : drone.overwhelmed() ? LAMP_WARN : LAMP_OK;
        box(consumer, pose, -0.03F, BODY_TOP + 0.05F, -BODY_HALF * 0.75F, 0.03F, BODY_TOP + 0.09F, -BODY_HALF * 0.45F, lamp, 1.0F);

        stack.popPose();
    }

    /**
     * One rotor: a hub and either four thin blades or a blur.
     *
     * <p>The blur is the trick that sells the speed. A rotor spinning at 3600 rpm is not a disc and not four
     * blades - it is a faint circle of motion - and drawing two crossed translucent quads above the blades
     * reads as exactly that, at a fraction of the geometry a real blurred disc would need.
     */
    private static void rotor(VertexConsumer consumer, PoseStack.Pose pose, float x, float z, float spin, int index, boolean spinning, float shade) {
        box(consumer, pose, x - 0.028F, ROTOR_Y - 0.03F, z - 0.028F, x + 0.028F, ROTOR_Y + 0.02F, z + 0.028F, SHELL, shade);
        // Counter-rotating pairs, which is what stops a quadcopter spinning on its own axis.
        float mine = spin * ((index & 1) == 0 ? 1.0F : -1.0F);
        double radians = Math.toRadians(mine);
        for (int blade = 0; blade < 2; blade++) {
            double angle = radians + blade * Math.PI * 0.5D;
            float bx = (float) Math.cos(angle) * ROTOR_RADIUS;
            float bz = (float) Math.sin(angle) * ROTOR_RADIUS;
            box(consumer, pose, x - Math.abs(bx) - 0.012F, ROTOR_Y - 0.004F, z - Math.abs(bz) - 0.012F,
                    x + Math.abs(bx) + 0.012F, ROTOR_Y + 0.004F, z + Math.abs(bz) + 0.012F, ROTOR, shade);
        }
        if (spinning) {
            for (int cross = 0; cross < 3; cross++) {
                double angle = radians * 0.35D + cross * Math.PI / 3.0D;
                float dx = (float) Math.cos(angle) * ROTOR_RADIUS;
                float dz = (float) Math.sin(angle) * ROTOR_RADIUS;
                quad(consumer, pose,
                        x - dz * 0.14F, ROTOR_Y + 0.008F, z + dx * 0.14F,
                        x + dz * 0.14F, ROTOR_Y + 0.008F, z - dx * 0.14F,
                        x + dx, ROTOR_Y + 0.008F, z + dz,
                        x - dx, ROTOR_Y + 0.008F, z - dz,
                        RenderQuads.lit(ROTOR_BLUR, shade * 0.5F));
            }
        }
    }

    private static void box(VertexConsumer consumer, PoseStack.Pose pose, float x0, float y0, float z0, float x1, float y1, float z1, int color, float shade) {
        quad(consumer, pose, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, RenderQuads.lit(color, shade * FACE_UP));
        quad(consumer, pose, x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0, RenderQuads.lit(color, shade * FACE_DOWN));
        quad(consumer, pose, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, RenderQuads.lit(color, shade * FACE_Z));
        quad(consumer, pose, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1, RenderQuads.lit(color, shade * FACE_Z));
        quad(consumer, pose, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, RenderQuads.lit(color, shade * FACE_X));
        quad(consumer, pose, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, RenderQuads.lit(color, shade * FACE_X));
    }

    private static void quad(VertexConsumer consumer, PoseStack.Pose pose,
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
