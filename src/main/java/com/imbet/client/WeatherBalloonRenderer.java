package com.imbet.client;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.weather.WeatherBalloonEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the weather balloon as a real 3D mesh rather than a billboard sprite.
 *
 * <p>The train is modelled the way a radiosonde actually hangs: a lathe-revolved latex envelope
 * whose profile is an ellipsoid cut off just below its equator (which is exactly where a real
 * balloon's neck is), a short neck cone, four load lines down to an instrument package box, and a
 * parachute canopy instead of the envelope once the balloon has popped. Every surface is a real
 * vertex with a real normal, so the entity shader shades the sphere like a sphere.
 *
 * <p>Two render types are used deliberately. The envelope and the canopy are made of translucent
 * material and the envelope is drawn twice with opposite vertex order into a back-face-culled
 * buffer, so only its near shell contributes - which is what makes it read as one thin sheet of
 * latex instead of two stacked, muddy layers. The box and the lines are cutout geometry drawn
 * without culling, where vertex order cannot matter.
 */
public class WeatherBalloonRenderer<T extends WeatherBalloonEntity> extends EntityRenderer<T> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            ExpandedWeather2Dynamics.MODID, "textures/entity/weather_balloon.png");

    /** UV regions inside the 128x128 sheet. */
    private static final float ENV_U0 = 0.0F;
    private static final float ENV_V0 = 0.0F;
    private static final float ENV_U1 = 1.0F;
    private static final float ENV_V1 = 0.5F;
    private static final float BOX_U0 = 0.0F;
    private static final float BOX_V0 = 0.5F;
    private static final float BOX_U1 = 0.75F;
    private static final float BOX_V1 = 1.0F;
    private static final float CHUTE_U0 = 0.75F;
    private static final float CHUTE_V0 = 0.5F;
    private static final float CHUTE_U1 = 1.0F;
    private static final float CHUTE_V1 = 1.0F;
    /** A dark cord swatch, tucked into the top-left corner of the instrument box region. */
    private static final float CORD_U = 0.004F;
    private static final float CORD_V = 0.512F;

    private static final int SEGMENTS = 24;
    private static final int ENVELOPE_RINGS = 16;
    private static final int CANOPY_RINGS = 10;

    /** The envelope is an ellipsoid cut off below the equator; cos(theta) is -0.983 at the cut. */
    private static final double ENVELOPE_THETA_MAX = 2.96D;
    private static final float ENVELOPE_TALLNESS = 1.14F;
    private static final float NECK_LENGTH = 0.42F;
    private static final float NECK_RADIUS_FRACTION = 0.11F;
    private static final float NECK_FLANGE = 1.45F;
    private static final float CORD_LENGTH = 2.6F;
    private static final float LINE_HALF_WIDTH = 0.014F;
    private static final float BOX_HALF = 0.17F;
    private static final float BOX_HALF_HEIGHT = 0.23F;
    private static final float ANTENNA_LENGTH = 0.34F;
    /** Canopy width relative to the burst envelope, and how far its skirt droops past the equator. */
    private static final float CANOPY_RADIUS_FACTOR = 0.52F;
    private static final double CANOPY_THETA_MAX = 1.95D;

    private static final float[][] BOX_TILES = buildBoxTiles();

    /**
     * The cosine and sine of every segment's angle.
     *
     * <p>The ring of angles a lathe walks is a property of {@code SEGMENTS} alone - it is the same ring on every
     * surface, in every direction, in every frame - and the two builders below were taking a sine and a cosine
     * of each end of every segment for every quad they emit: sixteen transcendentals per segment where four
     * values would do, and all four of them the same four every time the mesh is drawn. SEGMENTS is a compile
     * time constant, so there are only {@code SEGMENTS + 1} of them in the whole program and they are taken once,
     * here, rather than once per vertex per frame.</p>
     *
     * <p>Each entry is filled with the identical expression the loops used to evaluate, so a table read is the
     * same double a call would have returned.</p>
     */
    private static final double[] SEGMENT_COS = new double[SEGMENTS + 1];
    private static final double[] SEGMENT_SIN = new double[SEGMENTS + 1];

    static {
        for (int segment = 0; segment <= SEGMENTS; segment++) {
            double angle = Math.PI * 2.0D * segment / SEGMENTS;
            SEGMENT_COS[segment] = Math.cos(angle);
            SEGMENT_SIN[segment] = Math.sin(angle);
        }
    }

    public WeatherBalloonRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(T entity, float entityYaw, float partialTick, PoseStack pose, MultiBufferSource buffer, int packedLight) {
        int phase = entity.phase();
        if (!Config.WEATHER_BALLOON_ENABLED.get()) {
            return;
        }

        // Must run before any consumer is taken: a name tag asks the buffer source for its own render
        // type, and that would end whatever batch we had already begun.
        super.render(entity, entityYaw, partialTick, pose, buffer, packedLight);

        float spin = entity.spin(partialTick);
        float leanX = entity.leanX();
        float leanZ = entity.leanZ();

        float radius = entity.envelopeRadius();
        float halfHeight = radius * ENVELOPE_TALLNESS;
        float neckTop = BOX_HALF_HEIGHT + CORD_LENGTH;
        float neckBottom = neckTop - NECK_LENGTH;
        float envelopeCenterY = neckTop + (float) (-halfHeight * Math.cos(ENVELOPE_THETA_MAX));

        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(spin));
        pose.mulPose(Axis.XP.rotationDegrees(leanX));
        pose.mulPose(Axis.ZP.rotationDegrees(leanZ));
        PoseStack.Pose pose2 = pose.last();

        // A consumer from a BufferSource is only valid until the next getBuffer/endBatch, so each one
        // is taken immediately before the draws that use it and never held across another fetch.
        float cordRingRadius;
        float cordRingY;
        if (phase == WeatherBalloonEntity.PHASE_ASCENT) {
            VertexConsumer latex = buffer.getBuffer(RenderType.entityTranslucentCull(TEXTURE));
            float[][] neck = buildNeck(radius, neckTop, neckBottom);
            lathe(latex, pose2, packedLight, 0xFFFFFFFF, radius, halfHeight, envelopeCenterY,
                    ENVELOPE_THETA_MAX, ENVELOPE_RINGS, ENV_U0, ENV_V0, ENV_U1, ENV_V1, true);
            lathe(latex, pose2, packedLight, 0xFFFFFFFF, radius, halfHeight, envelopeCenterY,
                    ENVELOPE_THETA_MAX, ENVELOPE_RINGS, ENV_U0, ENV_V0, ENV_U1, ENV_V1, false);
            cone(latex, pose2, packedLight, 0xFFFFFFFF, neck[0], neck[1], ENV_U0, ENV_V0, ENV_U1, ENV_V1);
            cone(latex, pose2, packedLight, 0xFFFFFFFF, neck[1], neck[2], ENV_U0, ENV_V0, ENV_U1, ENV_V1);
            cordRingRadius = radius * NECK_RADIUS_FRACTION;
            cordRingY = neckBottom;
        } else {
            float canopyRadius = radius * CANOPY_RADIUS_FACTOR;
            float canopyHeight = canopyRadius * 1.15F;
            float canopyCenterY = BOX_HALF_HEIGHT + CORD_LENGTH + ANTENNA_LENGTH;
            cordRingRadius = (float) (canopyRadius * Math.sin(CANOPY_THETA_MAX));
            cordRingY = canopyCenterY + (float) (canopyHeight * Math.cos(CANOPY_THETA_MAX));
            if (phase == WeatherBalloonEntity.PHASE_DESCENT) {
                VertexConsumer sheet = buffer.getBuffer(RenderType.entityTranslucent(TEXTURE));
                lathe(sheet, pose2, packedLight, 0xFFFFFFFF, canopyRadius, canopyHeight, canopyCenterY,
                        CANOPY_THETA_MAX, CANOPY_RINGS, CHUTE_U0, CHUTE_V0, CHUTE_U1, CHUTE_V1, true);
            }
        }

        // One contiguous cutout run for the load lines, the package and the antenna.
        VertexConsumer solid = buffer.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        if (phase == WeatherBalloonEntity.PHASE_ASCENT || phase == WeatherBalloonEntity.PHASE_DESCENT) {
            cords(solid, pose2, packedLight, cordRingRadius, cordRingY, BOX_HALF * 0.92F, BOX_HALF_HEIGHT);
        }
        box(solid, pose2, packedLight, 0xFFFFFFFF, 0.0F);
        if (phase != WeatherBalloonEntity.PHASE_DESCENT) {
            prism(solid, pose2, packedLight, 0xFFFFFFFF,
                    new Vec3(0.0D, BOX_HALF_HEIGHT, 0.0D),
                    new Vec3(0.0D, BOX_HALF_HEIGHT + ANTENNA_LENGTH, 0.0D),
                    LINE_HALF_WIDTH);
        }

        pose.popPose();
    }

    @Override
    public boolean shouldRender(T entity, Frustum frustum, double camX, double camY, double camZ) {
        double range = Config.WEATHER_BALLOON_RENDER_RANGE_BLOCKS.get();
        return entity.distanceToSqr(camX, camY, camZ) <= range * range && super.shouldRender(entity, frustum, camX, camY, camZ);
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        return TEXTURE;
    }

    // ------------------------------------------------------------------ geometry

    /**
     * Surface of revolution around the Y axis. The profile is an ellipsoid from its pole down to
     * thetaMax, so the radius and the normal are exact rather than differenced, and the normal comes
     * out of the ellipsoid relation (x/a^2, y/c^2) - which is what makes the shading read as a
     * rounded balloon instead of a flat circle.
     *
     * <p>When {@code windOneWay} is true the quad is emitted in one vertex order and the caller is
     * expected to emit the mirrored order as well, so a back-face-culled buffer keeps exactly one of
     * them.
     */
    private static void lathe(VertexConsumer consumer, PoseStack.Pose pose, int light, int color,
                              float radius, float halfHeight, float centerY, double thetaMax,
                              int rings, float u0, float v0, float u1, float v1, boolean windOneWay) {
        for (int ring = 0; ring < rings; ring++) {
            double theta0 = thetaMax * ring / rings;
            double theta1 = thetaMax * (ring + 1) / rings;
            double sinTheta0 = Math.sin(theta0);
            double cosTheta0 = Math.cos(theta0);
            double sinTheta1 = Math.sin(theta1);
            double cosTheta1 = Math.cos(theta1);
            float r0 = (float) (radius * sinTheta0);
            float y0 = centerY + (float) (halfHeight * cosTheta0);
            float r1 = (float) (radius * sinTheta1);
            float y1 = centerY + (float) (halfHeight * cosTheta1);
            float nr0 = (float) sinTheta0 / radius;
            float ny0 = (float) cosTheta0 / halfHeight;
            float nr1 = (float) sinTheta1 / radius;
            float ny1 = (float) cosTheta1 / halfHeight;
            float n0 = (float) Math.sqrt(nr0 * nr0 + ny0 * ny0);
            float n1 = (float) Math.sqrt(nr1 * nr1 + ny1 * ny1);
            nr0 /= n0;
            ny0 /= n0;
            nr1 /= n1;
            ny1 /= n1;

            float vA = Mth.lerp((float) ring / rings, v0, v1);
            float vB = Mth.lerp((float) (ring + 1) / rings, v0, v1);
            for (int segment = 0; segment < SEGMENTS; segment++) {
                double cosA0 = SEGMENT_COS[segment];
                double sinA0 = SEGMENT_SIN[segment];
                double cosA1 = SEGMENT_COS[segment + 1];
                double sinA1 = SEGMENT_SIN[segment + 1];
                float uA = Mth.lerp((float) segment / SEGMENTS, u0, u1);
                float uB = Mth.lerp((float) (segment + 1) / SEGMENTS, u0, u1);

                float x00 = (float) (r0 * cosA0);
                float z00 = (float) (r0 * sinA0);
                float x01 = (float) (r0 * cosA1);
                float z01 = (float) (r0 * sinA1);
                float x10 = (float) (r1 * cosA0);
                float z10 = (float) (r1 * sinA0);
                float x11 = (float) (r1 * cosA1);
                float z11 = (float) (r1 * sinA1);

                float nx00 = (float) (nr0 * cosA0);
                float nz00 = (float) (nr0 * sinA0);
                float nx01 = (float) (nr0 * cosA1);
                float nz01 = (float) (nr0 * sinA1);
                float nx10 = (float) (nr1 * cosA0);
                float nz10 = (float) (nr1 * sinA0);
                float nx11 = (float) (nr1 * cosA1);
                float nz11 = (float) (nr1 * sinA1);

                if (windOneWay) {
                    quad(consumer, pose, light, color,
                            x00, y0, z00, uA, vA, nx00, ny0, nz00,
                            x01, y0, z01, uB, vA, nx01, ny0, nz01,
                            x11, y1, z11, uB, vB, nx11, ny1, nz11,
                            x10, y1, z10, uA, vB, nx10, ny1, nz10);
                } else {
                    quad(consumer, pose, light, color,
                            x10, y1, z10, uA, vB, nx10, ny1, nz10,
                            x11, y1, z11, uB, vB, nx11, ny1, nz11,
                            x01, y0, z01, uB, vA, nx01, ny0, nz01,
                            x00, y0, z00, uA, vA, nx00, ny0, nz00);
                }
            }
        }
    }

    /** Neck: the cone from the envelope cut down to the ring, then the small folded flange. */
    private static float[][] buildNeck(float radius, float neckTop, float neckBottom) {
        float cutRadius = (float) (radius * Math.sin(ENVELOPE_THETA_MAX));
        float ringRadius = radius * NECK_RADIUS_FRACTION;
        return new float[][]{
                {cutRadius, neckTop},
                {ringRadius, neckBottom},
                {ringRadius * NECK_FLANGE, neckBottom - 0.06F}
        };
    }

    /** A single truncated-cone band: radius A at height A, radius B at height B. */
    private static void cone(VertexConsumer consumer, PoseStack.Pose pose, int light, int color,
                             float[] a, float[] b, float u0, float v0, float u1, float v1) {
        float radiusA = a[0];
        float heightA = a[1];
        float radiusB = b[0];
        float heightB = b[1];
        float slopeRadius = radiusA - radiusB;
        float slopeHeight = heightB - heightA;
        float length = (float) Math.sqrt(slopeRadius * slopeRadius + slopeHeight * slopeHeight);
        if (length < 1.0E-5F) {
            return;
        }
        float normalRadius = slopeHeight / length;
        float normalHeight = -slopeRadius / length;
        for (int segment = 0; segment < SEGMENTS; segment++) {
            double cosA0 = SEGMENT_COS[segment];
            double sinA0 = SEGMENT_SIN[segment];
            double cosA1 = SEGMENT_COS[segment + 1];
            double sinA1 = SEGMENT_SIN[segment + 1];
            float uA = Mth.lerp((float) segment / SEGMENTS, u0, u1);
            float uB = Mth.lerp((float) (segment + 1) / SEGMENTS, u0, u1);
            quadOutward(consumer, pose, light, color,
                    (float) (radiusA * cosA0), heightA, (float) (radiusA * sinA0), uA, v0,
                    (float) (radiusB * cosA0), heightB, (float) (radiusB * sinA0), uA, v1,
                    (float) (radiusB * cosA1), heightB, (float) (radiusB * sinA1), uB, v1,
                    (float) (radiusA * cosA1), heightA, (float) (radiusA * sinA1), uB, v0,
                    (float) (normalRadius * cosA0), normalHeight, (float) (normalRadius * sinA0));
        }
    }

    /** The instrument package: a small crate with a sensor lens and a spinning housing. */
    private static void box(VertexConsumer consumer, PoseStack.Pose pose, int light, int color, float centerY) {
        float hx = BOX_HALF;
        float hy = BOX_HALF_HEIGHT;
        float hz = BOX_HALF;
        float top = centerY + hy;
        float bottom = centerY - hy;
        float[] up = BOX_TILES[0];
        float[] down = BOX_TILES[1];
        float[] north = BOX_TILES[2];
        float[] south = BOX_TILES[3];
        float[] west = BOX_TILES[4];
        float[] east = BOX_TILES[5];

        quadOutward(consumer, pose, light, color, -hx, top, -hz, up[0], up[1], hx, top, -hz, up[2], up[1],
                hx, top, hz, up[2], up[3], -hx, top, hz, up[0], up[3], 0.0F, 1.0F, 0.0F);
        quadOutward(consumer, pose, light, color, -hx, bottom, hz, down[0], down[1], hx, bottom, hz, down[2], down[1],
                hx, bottom, -hz, down[2], down[3], -hx, bottom, -hz, down[0], down[3], 0.0F, -1.0F, 0.0F);
        quadOutward(consumer, pose, light, color, -hx, bottom, -hz, north[0], north[3], hx, bottom, -hz, north[2], north[3],
                hx, top, -hz, north[2], north[1], -hx, top, -hz, north[0], north[1], 0.0F, 0.0F, -1.0F);
        quadOutward(consumer, pose, light, color, -hx, bottom, hz, south[0], south[3], -hx, top, hz, south[0], south[1],
                hx, top, hz, south[2], south[1], hx, bottom, hz, south[2], south[3], 0.0F, 0.0F, 1.0F);
        quadOutward(consumer, pose, light, color, -hx, bottom, hz, west[0], west[3], -hx, top, hz, west[0], west[1],
                -hx, top, -hz, west[2], west[1], -hx, bottom, -hz, west[2], west[3], -1.0F, 0.0F, 0.0F);
        quadOutward(consumer, pose, light, color, hx, bottom, -hz, east[0], east[3], hx, top, -hz, east[0], east[1],
                hx, top, hz, east[2], east[1], hx, bottom, hz, east[2], east[3], 1.0F, 0.0F, 0.0F);
    }

    /** The load lines: four square-section cords from a ring down to the package corners. */
    private static void cords(VertexConsumer consumer, PoseStack.Pose pose, int light, float ringRadius, float ringY,
                              float half, float packageHalfHeight) {
        for (int index = 0; index < 4; index++) {
            double angle = Math.PI * 0.25D + index * Math.PI * 0.5D;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            Vec3 from = new Vec3(cos * ringRadius, ringY, sin * ringRadius);
            Vec3 to = new Vec3(Math.signum(cos) * half, packageHalfHeight, Math.signum(sin) * half);
            prism(consumer, pose, light, 0xFFFFFFFF, from, to, LINE_HALF_WIDTH);
        }
    }

    /** A square-section strand, used for the load lines and the whip antenna. */
    private static void prism(VertexConsumer consumer, PoseStack.Pose pose, int light, int color, Vec3 from, Vec3 to, float halfWidth) {
        Vec3 axis = to.subtract(from);
        if (axis.lengthSqr() < 1.0E-8D) {
            return;
        }
        Vec3 forward = axis.normalize();
        Vec3 reference = Math.abs(forward.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 side = forward.cross(reference).normalize().scale(halfWidth);
        Vec3 other = forward.cross(side).normalize().scale(halfWidth);
        Vec3[] corners = {
                side.add(other),
                other.subtract(side),
                side.add(other).scale(-1.0D),
                side.subtract(other)
        };
        for (int index = 0; index < 4; index++) {
            Vec3 a = corners[index];
            Vec3 b = corners[(index + 1) & 3];
            Vec3 normal = a.add(b).normalize();
            quadOutward(consumer, pose, light, color,
                    (float) (from.x + a.x), (float) (from.y + a.y), (float) (from.z + a.z), CORD_U, CORD_V,
                    (float) (to.x + a.x), (float) (to.y + a.y), (float) (to.z + a.z), CORD_U, CORD_V,
                    (float) (to.x + b.x), (float) (to.y + b.y), (float) (to.z + b.z), CORD_U, CORD_V,
                    (float) (from.x + b.x), (float) (from.y + b.y), (float) (from.z + b.z), CORD_U, CORD_V,
                    (float) normal.x, (float) normal.y, (float) normal.z);
        }
    }

    // ------------------------------------------------------------------ emit helpers

    private static void quad(VertexConsumer consumer, PoseStack.Pose pose, int light, int color,
                             float x0, float y0, float z0, float u0, float v0, float nx0, float ny0, float nz0,
                             float x1, float y1, float z1, float u1, float v1, float nx1, float ny1, float nz1,
                             float x2, float y2, float z2, float u2, float v2, float nx2, float ny2, float nz2,
                             float x3, float y3, float z3, float u3, float v3, float nx3, float ny3, float nz3) {
        vertex(consumer, pose, light, color, x0, y0, z0, u0, v0, nx0, ny0, nz0);
        vertex(consumer, pose, light, color, x1, y1, z1, u1, v1, nx1, ny1, nz1);
        vertex(consumer, pose, light, color, x2, y2, z2, u2, v2, nx2, ny2, nz2);
        vertex(consumer, pose, light, color, x3, y3, z3, u3, v3, nx3, ny3, nz3);
    }

    /** A flat-shaded quad: one normal for all four vertices, which is what a box face wants. */
    private static void quadOutward(VertexConsumer consumer, PoseStack.Pose pose, int light, int color,
                                    float x0, float y0, float z0, float u0, float v0,
                                    float x1, float y1, float z1, float u1, float v1,
                                    float x2, float y2, float z2, float u2, float v2,
                                    float x3, float y3, float z3, float u3, float v3,
                                    float nx, float ny, float nz) {
        quad(consumer, pose, light, color,
                x0, y0, z0, u0, v0, nx, ny, nz,
                x1, y1, z1, u1, v1, nx, ny, nz,
                x2, y2, z2, u2, v2, nx, ny, nz,
                x3, y3, z3, u3, v3, nx, ny, nz);
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, int light, int color,
                               float x, float y, float z, float u, float v, float nx, float ny, float nz) {
        consumer.addVertex(pose, x, y, z)
                .setColor(color)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }

    private static float[][] buildBoxTiles() {
        int columns = 3;
        int rows = 2;
        float tileWidth = (BOX_U1 - BOX_U0) / columns;
        float tileHeight = (BOX_V1 - BOX_V0) / rows;
        float[][] tiles = new float[6][4];
        for (int index = 0; index < 6; index++) {
            int column = index % columns;
            int row = index / columns;
            tiles[index][0] = BOX_U0 + column * tileWidth;
            tiles[index][1] = BOX_V0 + row * tileHeight;
            tiles[index][2] = BOX_U0 + (column + 1) * tileWidth;
            tiles[index][3] = BOX_V0 + (row + 1) * tileHeight;
        }
        return tiles;
    }
}
