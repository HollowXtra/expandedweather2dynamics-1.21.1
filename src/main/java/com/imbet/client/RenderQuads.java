package com.imbet.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

final class RenderQuads {
    /**
     * The UV channel of the panel helpers below carries the vertex's <em>block-local position in the panel's
     * own plane</em>: {@code (x, z)} for a top-face plate, {@code (x, y)} for an upright one. The instrument
     * and radar programs read it as their panel coordinate.
     *
     * <p>These helpers take a consumer rather than fetching one, so a caller that also draws text has to fetch
 * before its quads and finish them before the first {@code Font.drawInBatch} call: the font asks the
 * buffer source for a different render type, and that ends the batch the consumer came from. A consumer
 * held across a text draw is dead and throws {@code IllegalStateException: Not building!}.
 *
 * <p>It is carried in the UVs because it cannot be recovered from the position attribute. Block entity
     * geometry is written camera-relative - the level renderer translates the pose by {@code blockPos -
     * camera} before calling the renderer - so {@code Position.xz} in a vertex shader is a world coordinate
     * that a camera-relative one, and a program keyed on it puts its centre wherever the player happens to
     * be standing. A per-quad 0..1 UV has the opposite fault: it restarts inside every quad, so a mask or a
     * gradient is evaluated per quad rather than once across the panel. The block-local coordinate is the
     * only one that is both stable as the player moves and shared by every quad of the panel.
     */
    private RenderQuads() {
    }

    /**
     * How bright the world is where an instrument stands, from the block's own light level and the time
     * of day.
     *
     * <p>It exists because the instrument hardware is drawn with a colour-only render type - see
     * {@link #lit} - so vanilla's lighting never reaches those vertices and the block light has to be
     * resolved here instead. Sky light is scaled by the level's sky darkening, because the packed sky
     * value stays at full 15 after dusk and it is the lightmap that normally dims it; without this the
     * metalwork would stay as bright at midnight as at noon. The floor keeps an unlit instrument a
     * readable silhouette instead of a black hole in the dark.
     */
    static float lightShade(Level level, int packedLight) {
        int block = LightTexture.block(packedLight);
        int sky = LightTexture.sky(packedLight);
        float skyBrightness = 1.0F - (level == null ? 0.0F : level.getSkyDarken()) / 15.0F;
        float shade = Math.max(block / 15.0F, sky / 15.0F * skyBrightness);
        return 0.20F + 0.80F * Mth.clamp(shade, 0.0F, 1.0F);
    }

    /**
     * Scales a colour's RGB by a light shade, leaving its alpha alone.
     *
     * <p>The instrument metalwork is drawn with {@code RenderType.debugQuads()}, which is the correct
     * colour-only, no-cull, main-target type for this geometry but carries no lightmap attribute. Folding
     * the block's brightness into the vertex colour is what replaces the lighting vanilla would have
     * applied, so the metal reads dark when the world is dark.
     */
    static int lit(int color, float shade) {
        float s = Mth.clamp(shade, 0.0F, 1.0F);
        int a = color >>> 24 & 255;
        int r = Math.round((color >>> 16 & 255) * s);
        int g = Math.round((color >>> 8 & 255) * s);
        int b = Math.round((color & 255) * s);
        return a << 24 | r << 16 | g << 8 | b;
    }

    static void quad(VertexConsumer consumer, PoseStack.Pose pose, float x1, float y1, float z1, float x2, float y2, float z2, int color) {
        int a = color >>> 24 & 255;
        int r = color >>> 16 & 255;
        int g = color >>> 8 & 255;
        int b = color & 255;
        consumer.addVertex(pose, x1, y1, z1).setColor(r, g, b, a);
        consumer.addVertex(pose, x2, y1, z1).setColor(r, g, b, a);
        consumer.addVertex(pose, x2, y2, z2).setColor(r, g, b, a);
        consumer.addVertex(pose, x1, y2, z2).setColor(r, g, b, a);
    }

    static void topQuad(VertexConsumer consumer, PoseStack.Pose pose, float minX, float minZ, float maxX, float maxZ, float y, int color) {
        int a = color >>> 24 & 255;
        int r = color >>> 16 & 255;
        int g = color >>> 8 & 255;
        int b = color & 255;
        consumer.addVertex(pose, minX, y, minZ).setColor(r, g, b, a);
        consumer.addVertex(pose, maxX, y, minZ).setColor(r, g, b, a);
        consumer.addVertex(pose, maxX, y, maxZ).setColor(r, g, b, a);
        consumer.addVertex(pose, minX, y, maxZ).setColor(r, g, b, a);
    }

    static void topQuadUv(VertexConsumer consumer, PoseStack.Pose pose, float minX, float minZ, float maxX, float maxZ, float y, int color) {
        int a = color >>> 24 & 255;
        int r = color >>> 16 & 255;
        int g = color >>> 8 & 255;
        int b = color & 255;
        consumer.addVertex(pose, minX, y, minZ).setUv(minX, minZ).setColor(r, g, b, a);
        consumer.addVertex(pose, maxX, y, minZ).setUv(maxX, minZ).setColor(r, g, b, a);
        consumer.addVertex(pose, maxX, y, maxZ).setUv(maxX, maxZ).setColor(r, g, b, a);
        consumer.addVertex(pose, minX, y, maxZ).setUv(minX, maxZ).setColor(r, g, b, a);
    }

    /**
     * An upright quad on the plane {@code z = zPlane}, spanning x0..x1 and y0..y1 - the side-on counterpart
     * to {@link #topQuadUv}, for the panels that hang on a block's face rather than lying on its top.
     *
     * <p>Corners run (x0,y0) (x1,y0) (x1,y1) (x0,y1). It is meant for the colour-only render types, which
     * draw without back-face culling, so one winding is visible from both sides.
     */
    static void faceQuadUv(VertexConsumer consumer, PoseStack.Pose pose, float x0, float y0, float x1, float y1, float zPlane, int color) {
        int a = color >>> 24 & 255;
        int r = color >>> 16 & 255;
        int g = color >>> 8 & 255;
        int b = color & 255;
        consumer.addVertex(pose, x0, y0, zPlane).setUv(x0, y0).setColor(r, g, b, a);
        consumer.addVertex(pose, x1, y0, zPlane).setUv(x1, y0).setColor(r, g, b, a);
        consumer.addVertex(pose, x1, y1, zPlane).setUv(x1, y1).setColor(r, g, b, a);
        consumer.addVertex(pose, x0, y1, zPlane).setUv(x0, y1).setColor(r, g, b, a);
    }

    /**
     * A top-facing quad whose four corners each carry their own colour, so a continuous field can be drawn
     * with it. Adjacent quads that share a corner value interpolate to the same colour along their common
     * edge, which is what turns a grid of samples into a smooth surface rather than a mosaic of tiles.
     *
     * <p>Corners are given in the same winding as {@link #topQuadUv}: min/min, max/min, max/max, min/max.
     */
    static void topQuadUvGradient(VertexConsumer consumer, PoseStack.Pose pose,
                                  float minX, float minZ, float maxX, float maxZ, float y,
                                  int minMinColor, int maxMinColor, int maxMaxColor, int minMaxColor) {
        addUvVertex(consumer, pose, minX, y, minZ, minX, minZ, minMinColor);
        addUvVertex(consumer, pose, maxX, y, minZ, maxX, minZ, maxMinColor);
        addUvVertex(consumer, pose, maxX, y, maxZ, maxX, maxZ, maxMaxColor);
        addUvVertex(consumer, pose, minX, y, maxZ, minX, maxZ, minMaxColor);
    }

    private static void addUvVertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z, float u, float v, int color) {
        // The u/v pair is the caller's block-local coordinate, not a per-quad 0..1 - see the class note.
        consumer.addVertex(pose, x, y, z).setUv(u, v)
                .setColor(color >>> 16 & 255, color >>> 8 & 255, color & 255, color >>> 24 & 255);
    }

    /**
     * A filled triangle lying on a top face, corners given anticlockwise from {@code (x1,z1)}.
     *
     * <p>Written as four vertices because the radar and instrument programs are built on a QUADS vertex
     * format: a quad with its last corner repeated is a triangle, and the degenerate edge it adds has no area
     * to draw. It is not a degenerate {@code topPolyQuad} call, because that helper writes no UVs and the
     * panel coordinate these programs read lives in the UV channel - an icon drawn through it would carry
     * {@code (0,0)} as its position and be clipped to whatever the scope does at the panel's corner. This
     * writes the block-local coordinate into the UVs, like every other helper here.
     */
    static void topTriangleUv(VertexConsumer consumer, PoseStack.Pose pose,
                              float x1, float z1, float x2, float z2, float x3, float z3, float y, int color) {
        addUvVertex(consumer, pose, x1, y, z1, x1, z1, color);
        addUvVertex(consumer, pose, x2, y, z2, x2, z2, color);
        addUvVertex(consumer, pose, x3, y, z3, x3, z3, color);
        addUvVertex(consumer, pose, x3, y, z3, x3, z3, color);
    }

    static void topPolyQuad(VertexConsumer consumer, PoseStack.Pose pose, float x1, float z1, float x2, float z2, float x3, float z3, float x4, float z4, float y, int color) {
        int a = color >>> 24 & 255;
        int r = color >>> 16 & 255;
        int g = color >>> 8 & 255;
        int b = color & 255;
        consumer.addVertex(pose, x1, y, z1).setColor(r, g, b, a);
        consumer.addVertex(pose, x2, y, z2).setColor(r, g, b, a);
        consumer.addVertex(pose, x3, y, z3).setColor(r, g, b, a);
        consumer.addVertex(pose, x4, y, z4).setColor(r, g, b, a);
    }

    static void topLine(VertexConsumer consumer, PoseStack.Pose pose, float x1, float z1, float x2, float z2, float halfWidth, float y, int color) {
        float dx = x2 - x1;
        float dz = z2 - z1;
        float length = (float) Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4F) {
            topQuad(consumer, pose, x1 - halfWidth, z1 - halfWidth, x1 + halfWidth, z1 + halfWidth, y, color);
            return;
        }
        float px = -dz / length * halfWidth;
        float pz = dx / length * halfWidth;
        int a = color >>> 24 & 255;
        int r = color >>> 16 & 255;
        int g = color >>> 8 & 255;
        int b = color & 255;
        consumer.addVertex(pose, x1 + px, y, z1 + pz).setColor(r, g, b, a);
        consumer.addVertex(pose, x1 - px, y, z1 - pz).setColor(r, g, b, a);
        consumer.addVertex(pose, x2 - px, y, z2 - pz).setColor(r, g, b, a);
        consumer.addVertex(pose, x2 + px, y, z2 + pz).setColor(r, g, b, a);
    }

    static void topLineUv(VertexConsumer consumer, PoseStack.Pose pose, float x1, float z1, float x2, float z2, float halfWidth, float y, int color) {
        float dx = x2 - x1;
        float dz = z2 - z1;
        float length = (float) Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4F) {
            topQuadUv(consumer, pose, x1 - halfWidth, z1 - halfWidth, x1 + halfWidth, z1 + halfWidth, y, color);
            return;
        }
        float px = -dz / length * halfWidth;
        float pz = dx / length * halfWidth;
        int a = color >>> 24 & 255;
        int r = color >>> 16 & 255;
        int g = color >>> 8 & 255;
        int b = color & 255;
        consumer.addVertex(pose, x1 + px, y, z1 + pz).setUv(x1 + px, z1 + pz).setColor(r, g, b, a);
        consumer.addVertex(pose, x1 - px, y, z1 - pz).setUv(x1 - px, z1 - pz).setColor(r, g, b, a);
        consumer.addVertex(pose, x2 - px, y, z2 - pz).setUv(x2 - px, z2 - pz).setColor(r, g, b, a);
        consumer.addVertex(pose, x2 + px, y, z2 + pz).setUv(x2 + px, z2 + pz).setColor(r, g, b, a);
    }

    /**
     * A vertical rectangle standing on the line from one point to another, spanning a height range.
     *
     * <p>The wall-shaped counterpart to {@link #topLine}: meant for the colour-only render types, which
     * draw without back-face culling, so one winding is visible from both sides and no normals or
     * lightmap are needed. Use it for anything that has to read from the side rather than from above.
     */
    static void ribbon(VertexConsumer consumer, PoseStack.Pose pose,
                       float x1, float z1, float x2, float z2, float y0, float y1, int color) {
        if (Math.abs(x1 - x2) < 1.0E-5F && Math.abs(z1 - z2) < 1.0E-5F) {
            return;
        }
        int a = color >>> 24 & 255;
        int r = color >>> 16 & 255;
        int g = color >>> 8 & 255;
        int b = color & 255;
        consumer.addVertex(pose, x1, y0, z1).setColor(r, g, b, a);
        consumer.addVertex(pose, x2, y0, z2).setColor(r, g, b, a);
        consumer.addVertex(pose, x2, y1, z2).setColor(r, g, b, a);
        consumer.addVertex(pose, x1, y1, z1).setColor(r, g, b, a);
    }

    static void topRotatedQuadUv(VertexConsumer consumer, PoseStack.Pose pose, float centerX, float centerZ, float halfX, float halfZ, float angle, float y, int color) {
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        addRotatedVertex(consumer, pose, centerX, centerZ, -halfX, -halfZ, cos, sin, y, color);
        addRotatedVertex(consumer, pose, centerX, centerZ, halfX, -halfZ, cos, sin, y, color);
        addRotatedVertex(consumer, pose, centerX, centerZ, halfX, halfZ, cos, sin, y, color);
        addRotatedVertex(consumer, pose, centerX, centerZ, -halfX, halfZ, cos, sin, y, color);
    }

    private static void addRotatedVertex(VertexConsumer consumer, PoseStack.Pose pose, float centerX, float centerZ, float localX, float localZ, float cos, float sin, float y, int color) {
        int a = color >>> 24 & 255;
        int r = color >>> 16 & 255;
        int g = color >>> 8 & 255;
        int b = color & 255;
        float x = centerX + localX * cos - localZ * sin;
        float z = centerZ + localX * sin + localZ * cos;
        consumer.addVertex(pose, x, y, z).setUv(x, z).setColor(r, g, b, a);
    }
}
