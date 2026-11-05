package com.imbet.client;

import com.imbet.ExpandedWeather2Dynamics;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Arrays;

/**
 * A piece of hardware as real geometry: built once, then written to a vanilla lit render type every frame.
 *
 * <p>The machines used to be drawn with {@code debugQuads()}, one flat colour per quad with the lighting worked out
 * by hand, and three things followed from that. Nothing had a surface, so a galvanised horn and a painted cabinet
 * were two shades of plastic. Nothing was lit by the world, so the light had to be faked per block and could not
 * reach a face the way it reaches the block beside it. And a shader pack had nothing to work with, because those
 * vertices carried no normal for a pack's lighting to use.
 *
 * <p>These go through {@code entityCutoutNoCull} on the block atlas instead, with a real normal and the block's own
 * packed light on every vertex. The pack replaces that program properly, the lightmap darkens it at night, mipmaps
 * stop the distance from shimmering, and a material is an ordinary block texture that a resource pack can replace.
 *
 * <p>The vertex data is plain floats so it can be built in a static initialiser with no GL around, and rotating a
 * part is only a matter of which pose it is written with.
 */
final class SolidMesh {
    /** x y z, u v (0..1 inside the material), nx ny nz, baked shade. */
    private static final int STRIDE = 9;

    /** The one render type a mesh is drawn with: everything lives on the block atlas. */
    static RenderType renderType() {
        return RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS);
    }

    /** A surface: which block texture it wears. Resolved against the atlas per draw, so it survives a reload. */
    enum Material {
        GALVANIZED("galvanized"),
        STEEL_DARK("steel_dark"),
        PAINT_GRAY("paint_gray"),
        PAINT_YELLOW("paint_yellow"),
        PAINT_OLIVE("paint_olive"),
        PAINT_WHITE("paint_white"),
        ALUMINUM("aluminum"),
        WOOD_POLE("wood_pole"),
        CONCRETE("concrete"),
        RUBBER("rubber"),
        COPPER("copper"),
        BRASS("brass"),
        LOUVER("louver"),
        BORE("bore"),
        CABINET_DOOR("cabinet_door"),
        SOLAR_CELL("solar_cell"),
        LENS("lens"),
        DISH("dish"),
        CUP("cup");

        private final ResourceLocation id;

        Material(String name) {
            this.id = ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "block/machinery/" + name);
        }

        TextureAtlasSprite sprite() {
            return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(id);
        }
    }

    private final float[] data;
    private final Material material;

    private SolidMesh(float[] data, Material material) {
        this.data = data;
        this.material = material;
    }

    /** Writes the mesh. {@code tint} multiplies the material; {@code light} is usually the block's own packed light. */
    void emit(VertexConsumer consumer, PoseStack.Pose pose, int light, int overlay, int tint) {
        TextureAtlasSprite sprite = material.sprite();
        float u0 = sprite.getU0();
        float v0 = sprite.getV0();
        float du = sprite.getU1() - u0;
        float dv = sprite.getV1() - v0;
        float red = (tint >>> 16 & 255) / 255.0F;
        float green = (tint >>> 8 & 255) / 255.0F;
        float blue = (tint & 255) / 255.0F;
        float alpha = (tint >>> 24 & 255) / 255.0F;
        float[] v = data;
        for (int i = 0; i < v.length; i += STRIDE) {
            float shade = v[i + 8];
            consumer.addVertex(pose, v[i], v[i + 1], v[i + 2])
                    .setColor(red * shade, green * shade, blue * shade, alpha)
                    .setUv(u0 + v[i + 3] * du, v0 + v[i + 4] * dv)
                    .setOverlay(overlay)
                    .setLight(light)
                    .setNormal(pose, v[i + 5], v[i + 6], v[i + 7]);
        }
    }

    void emit(VertexConsumer consumer, PoseStack.Pose pose, int light, int overlay) {
        emit(consumer, pose, light, overlay, 0xFFFFFFFF);
    }

    /**
     * Writes geometry in machine terms: boxes, lathes, tubes and tapered horns, each with a real normal.
     *
     * <p>{@code tiles} everywhere is how many repeats of the material fit in one block, so a material reads at the
     * same scale on a pole as on a cabinet. Because a sprite cannot wrap on the atlas, a long face either repeats by
     * being cut into pieces (the lathe's rings and segments do that naturally) or stretches within one repeat.
     */
    static final class Builder {
        private final Material material;
        private float[] out = new float[STRIDE * 256];
        private int size;
        private final Matrix4f transform = new Matrix4f();
        private final Matrix3f normals = new Matrix3f();
        private final Vector3f scratch = new Vector3f();

        Builder(Material material) {
            this.material = material;
        }

        /** Moves everything written after this. */
        Builder at(float x, float y, float z) {
            transform.translate(x, y, z);
            normals.set(transform).invert().transpose();
            return this;
        }

        Builder turnY(float degrees) {
            transform.rotateY((float) Math.toRadians(degrees));
            normals.set(transform).invert().transpose();
            return this;
        }

        Builder turnX(float degrees) {
            transform.rotateX((float) Math.toRadians(degrees));
            normals.set(transform).invert().transpose();
            return this;
        }

        Builder turnZ(float degrees) {
            transform.rotateZ((float) Math.toRadians(degrees));
            normals.set(transform).invert().transpose();
            return this;
        }

        Builder resetFrame() {
            transform.identity();
            normals.identity();
            return this;
        }

        SolidMesh build() {
            return new SolidMesh(Arrays.copyOf(out, size), material);
        }

        private void vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz, float shade) {
            if (size + STRIDE > out.length) {
                out = Arrays.copyOf(out, out.length * 2);
            }
            transform.transformPosition(x, y, z, scratch);
            out[size] = scratch.x;
            out[size + 1] = scratch.y;
            out[size + 2] = scratch.z;
            out[size + 3] = Mth.clamp(u, 0.0F, 1.0F);
            out[size + 4] = Mth.clamp(v, 0.0F, 1.0F);
            normals.transform(nx, ny, nz, scratch);
            float length = Math.max(scratch.length(), 1.0E-5F);
            out[size + 5] = scratch.x / length;
            out[size + 6] = scratch.y / length;
            out[size + 7] = scratch.z / length;
            out[size + 8] = shade;
            size += STRIDE;
        }

        /** One flat quad, corners in order, with the given normal and the whole of the given UV window. */
        Builder quad(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz,
                     float nx, float ny, float nz, float u0, float v0, float u1, float v1, float shade) {
            vertex(ax, ay, az, u0, v1, nx, ny, nz, shade);
            vertex(bx, by, bz, u1, v1, nx, ny, nz, shade);
            vertex(cx, cy, cz, u1, v0, nx, ny, nz, shade);
            vertex(dx, dy, dz, u0, v0, nx, ny, nz, shade);
            return this;
        }

        /** The UV window for a run of {@code length} blocks starting at {@code start}, kept inside one repeat. */
        private static float[] window(float start, float length, float tiles) {
            float span = Math.min(length * tiles, 1.0F);
            float from = start * tiles;
            from -= Mth.floor(from);
            if (from + span > 1.0F) {
                from = 1.0F - span;
            }
            return new float[]{from, from + span};
        }

        Builder box(float x0, float y0, float z0, float x1, float y1, float z1, float tiles) {
            return box(x0, y0, z0, x1, y1, z1, tiles, 1.0F);
        }

        /** An axis-aligned box, its underside darker the way a surface under an overhang actually is. */
        Builder box(float x0, float y0, float z0, float x1, float y1, float z1, float tiles, float shade) {
            float[] ux = window(x0, x1 - x0, tiles);
            float[] uz = window(z0, z1 - z0, tiles);
            float[] uy = window(y0, y1 - y0, tiles);
            quad(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0, 1, 0, ux[0], uz[0], ux[1], uz[1], shade);
            quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0, ux[0], uz[0], ux[1], uz[1], shade * 0.82F);
            quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1, ux[0], uy[0], ux[1], uy[1], shade);
            quad(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0, 0, -1, ux[0], uy[0], ux[1], uy[1], shade);
            quad(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1, 0, 0, uz[0], uy[0], uz[1], uy[1], shade);
            quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1, 0, 0, uz[0], uy[0], uz[1], uy[1], shade);
            return this;
        }

        /** A box wearing the whole of the material on its front (+Z) face: a cabinet door, a label, a panel. */
        Builder panel(float x0, float y0, float z, float x1, float y1) {
            return quad(x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z, 0, 0, 1, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);
        }

        /**
         * A surface of revolution about the X axis through the current origin: horns, drums, rims, domes.
         *
         * <p>{@code stations} and {@code radii} run along the axis. The normals are smooth along the profile and
         * around the ring, so a flare reads as a flare with no hand-painted banding. {@code inward} turns the normals
         * in for the inside of a bell. {@code shades} (optional, one per station) bakes in what no lighting model here
         * can work out on its own: a throat is dark because it is deep, and a rim is bright because it is not.
         */
        Builder latheX(float[] stations, float[] radii, int segments, float tiles, boolean inward, float[] shades) {
            int count = stations.length;
            float[] slopeSin = new float[count];
            float[] slopeCos = new float[count];
            for (int i = 0; i < count; i++) {
                int a = Math.max(i - 1, 0);
                int b = Math.min(i + 1, count - 1);
                float dx = stations[b] - stations[a];
                float dr = radii[b] - radii[a];
                float length = Math.max(Mth.sqrt(dx * dx + dr * dr), 1.0E-5F);
                // The profile's normal in the (axis, radial) plane: perpendicular to its direction of travel.
                slopeSin[i] = -dr / length;
                slopeCos[i] = dx / length;
            }
            float sign = inward ? -1.0F : 1.0F;
            float travelled = 0.0F;
            for (int i = 0; i < count - 1; i++) {
                float dx = stations[i + 1] - stations[i];
                float dr = radii[i + 1] - radii[i];
                float run = Mth.sqrt(dx * dx + dr * dr);
                float[] vWindow = window(travelled, run, tiles);
                travelled += run;
                float meanRadius = Math.max(0.5F * (radii[i] + radii[i + 1]), 0.01F);
                float around = (float) (Math.PI * 2.0D * meanRadius);
                float uRepeats = Math.max(1.0F, Math.round(around * tiles));
                float shade0 = shades == null ? 1.0F : shades[i];
                float shade1 = shades == null ? 1.0F : shades[i + 1];
                for (int j = 0; j < segments; j++) {
                    float a0 = (float) (Math.PI * 2.0D * j / segments);
                    float a1 = (float) (Math.PI * 2.0D * (j + 1) / segments);
                    float c0 = Mth.cos(a0);
                    float s0 = Mth.sin(a0);
                    float c1 = Mth.cos(a1);
                    float s1 = Mth.sin(a1);
                    float u0 = (j / (float) segments) * uRepeats;
                    u0 -= Mth.floor(u0);
                    float u1 = u0 + uRepeats / segments;
                    if (u1 > 1.0F) {
                        u0 = Math.max(0.0F, 1.0F - uRepeats / segments);
                        u1 = 1.0F;
                    }
                    vertex(stations[i], radii[i] * s0, radii[i] * c0, u0, vWindow[0],
                            sign * slopeSin[i], sign * slopeCos[i] * s0, sign * slopeCos[i] * c0, shade0);
                    vertex(stations[i], radii[i] * s1, radii[i] * c1, u1, vWindow[0],
                            sign * slopeSin[i], sign * slopeCos[i] * s1, sign * slopeCos[i] * c1, shade0);
                    vertex(stations[i + 1], radii[i + 1] * s1, radii[i + 1] * c1, u1, vWindow[1],
                            sign * slopeSin[i + 1], sign * slopeCos[i + 1] * s1, sign * slopeCos[i + 1] * c1, shade1);
                    vertex(stations[i + 1], radii[i + 1] * s0, radii[i + 1] * c0, u0, vWindow[1],
                            sign * slopeSin[i + 1], sign * slopeCos[i + 1] * s0, sign * slopeCos[i + 1] * c0, shade1);
                }
            }
            return this;
        }

        Builder latheX(float[] stations, float[] radii, int segments, float tiles) {
            return latheX(stations, radii, segments, tiles, false, null);
        }

        /** The same about the Y axis: masts, poles, drums standing upright. */
        Builder latheY(float[] stations, float[] radii, int segments, float tiles, float[] shades) {
            turnZ(90.0F);
            float[] flipped = new float[stations.length];
            for (int i = 0; i < stations.length; i++) {
                flipped[i] = stations[i];
            }
            latheX(flipped, radii, segments, tiles, false, shades);
            turnZ(-90.0F);
            return this;
        }

        Builder latheY(float[] stations, float[] radii, int segments, float tiles) {
            return latheY(stations, radii, segments, tiles, null);
        }

        /** A flat disc across the X axis at {@code x}, facing along {@code +X} or {@code -X}. */
        Builder discX(float x, float radius, int segments, boolean facingPositive, float shade) {
            float nx = facingPositive ? 1.0F : -1.0F;
            for (int j = 0; j < segments; j++) {
                float a0 = (float) (Math.PI * 2.0D * j / segments);
                float a1 = (float) (Math.PI * 2.0D * (j + 1) / segments);
                float u0 = 0.5F + 0.5F * Mth.cos(a0);
                float v0 = 0.5F + 0.5F * Mth.sin(a0);
                float u1 = 0.5F + 0.5F * Mth.cos(a1);
                float v1 = 0.5F + 0.5F * Mth.sin(a1);
                vertex(x, 0.0F, 0.0F, 0.5F, 0.5F, nx, 0.0F, 0.0F, shade);
                vertex(x, radius * Mth.sin(a0), radius * Mth.cos(a0), u0, v0, nx, 0.0F, 0.0F, shade);
                vertex(x, radius * Mth.sin(a1), radius * Mth.cos(a1), u1, v1, nx, 0.0F, 0.0F, shade);
                vertex(x, 0.0F, 0.0F, 0.5F, 0.5F, nx, 0.0F, 0.0F, shade);
            }
            return this;
        }

        /** A disc facing up or down at {@code y}. */
        Builder discY(float y, float radius, int segments, boolean facingUp, float shade) {
            turnZ(90.0F);
            discX(y, radius, segments, facingUp, shade);
            turnZ(-90.0F);
            return this;
        }

        /** A round bar between two points. */
        Builder tube(float ax, float ay, float az, float bx, float by, float bz, float radius, int segments, float tiles) {
            float dx = bx - ax;
            float dy = by - ay;
            float dz = bz - az;
            float length = Mth.sqrt(dx * dx + dy * dy + dz * dz);
            if (length < 1.0E-4F) {
                return this;
            }
            Matrix4f saved = new Matrix4f(transform);
            transform.translate(ax, ay, az);
            // Turn +X onto the bar's direction.
            Vector3f axis = new Vector3f(dx / length, dy / length, dz / length);
            float yaw = (float) Math.atan2(-axis.z, axis.x);
            float pitch = (float) Math.asin(Mth.clamp(axis.y, -1.0F, 1.0F));
            transform.rotateY(yaw);
            transform.rotateZ(pitch);
            normals.set(transform).invert().transpose();
            latheX(new float[]{0.0F, length}, new float[]{radius, radius}, segments, tiles, false, null);
            discX(length, radius, segments, true, 0.9F);
            transform.set(saved);
            normals.set(transform).invert().transpose();
            return this;
        }

        /**
         * A rectangular horn opening along +X: the projector an amplifier-driven siren uses, because a rectangular
         * mouth covers a quadrant more evenly than a round bell does. Its four outside walls go to this builder; the
         * caller draws the bore with {@link #hornBore} on a dark material.
         */
        Builder hornWalls(float x0, float x1, float h0, float w0, float h1, float w1, float tiles) {
            flankedQuad(x0, h0, w0, x1, h1, w1, 1, tiles);
            flankedQuad(x0, h0, w0, x1, h1, w1, -1, tiles);
            float run = x1 - x0;
            float slopeZ = (w1 - w0) / run;
            float nzLen = Mth.sqrt(1.0F + slopeZ * slopeZ);
            float[] u = window(x0, run, tiles);
            float[] v = window(0.0F, h1 * 2.0F, tiles);
            quad(x0, -h0, w0, x1, -h1, w1, x1, h1, w1, x0, h0, w0, -slopeZ / nzLen, 0, 1 / nzLen, u[0], v[0], u[1], v[1], 1.0F);
            quad(x1, -h1, -w1, x0, -h0, -w0, x0, h0, -w0, x1, h1, -w1, -slopeZ / nzLen, 0, -1 / nzLen, u[0], v[0], u[1], v[1], 1.0F);
            return this;
        }

        private void flankedQuad(float x0, float h0, float w0, float x1, float h1, float w1, int side, float tiles) {
            float run = x1 - x0;
            float slope = (h1 - h0) / run;
            float len = Mth.sqrt(1.0F + slope * slope);
            float[] u = window(x0, run, tiles);
            float[] v = window(0.0F, w1 * 2.0F, tiles);
            float s = side;
            if (side > 0) {
                quad(x0, h0, -w0, x0, h0, w0, x1, h1, w1, x1, h1, -w1, -slope / len, 1 / len, 0, v[0], u[0], v[1], u[1], 1.0F);
            } else {
                quad(x0, -h0, w0, x0, -h0, -w0, x1, -h1, -w1, x1, -h1, w1, -slope / len, s / len, 0, v[0], u[0], v[1], u[1], 0.86F);
            }
        }

        /** The inside of that horn, shaded from black at the throat to the material at the mouth. */
        Builder hornBore(float x0, float x1, float h0, float w0, float h1, float w1) {
            float inset = 0.92F;
            float hi = h1 * inset;
            float wi = w1 * inset;
            float hb = h0 * inset;
            float wb = w0 * inset;
            for (int side = 0; side < 4; side++) {
                float ax, ay, az, bx, by, bz;
                float cx, cy, cz, dx, dy, dz;
                float nx = 0.2F;
                float ny = side == 0 ? -1 : side == 1 ? 1 : 0;
                float nz = side == 2 ? -1 : side == 3 ? 1 : 0;
                switch (side) {
                    case 0 -> {
                        ax = x0; ay = hb; az = wb; bx = x0; by = hb; bz = -wb;
                        cx = x1; cy = hi; cz = -wi; dx = x1; dy = hi; dz = wi;
                    }
                    case 1 -> {
                        ax = x0; ay = -hb; az = -wb; bx = x0; by = -hb; bz = wb;
                        cx = x1; cy = -hi; cz = wi; dx = x1; dy = -hi; dz = -wi;
                    }
                    case 2 -> {
                        ax = x0; ay = -hb; az = wb; bx = x0; by = hb; bz = wb;
                        cx = x1; cy = hi; cz = wi; dx = x1; dy = -hi; dz = wi;
                    }
                    default -> {
                        ax = x0; ay = hb; az = -wb; bx = x0; by = -hb; bz = -wb;
                        cx = x1; cy = -hi; cz = -wi; dx = x1; dy = hi; dz = -wi;
                    }
                }
                vertex(ax, ay, az, 0, 1, nx, ny, nz, 0.18F);
                vertex(bx, by, bz, 1, 1, nx, ny, nz, 0.18F);
                vertex(cx, cy, cz, 1, 0, nx, ny, nz, 0.90F);
                vertex(dx, dy, dz, 0, 0, nx, ny, nz, 0.90F);
            }
            // The throat itself, closed off.
            quad(x0, -hb, wb, x0, -hb, -wb, x0, hb, -wb, x0, hb, wb, 1, 0, 0, 0, 0, 1, 1, 0.12F);
            return this;
        }
    }
}
