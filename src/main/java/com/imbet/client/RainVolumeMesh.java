package com.imbet.client;

import com.imbet.Config;
import com.imbet.weather.PrecipitationField;
import com.imbet.weather.RainVolumeField;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The volumetric rain drawn without a custom program, for when a shader pack has taken ours.
 *
 * <p>A shader pack replaces the core programs, and a mod's own program is not one of them: with a pack
 * active, the rain's shader path cannot draw, and what is left is the particle sheets - flat quads, which
 * up close read as exactly what they are. That is the whole reason this exists. The same storm's rain is
 * built here as geometry instead: the same slices through the same field, but shaded on the client thread
 * with {@link RainVolumeField}, written into the vertices, and drawn with {@code entityTranslucentEmissive}
 * - a <em>vanilla</em> render type, so the pack replaces it with its own program and shades the rain the
 * way it shades everything else. One field, two drawings, and the pack chooses which.
 *
 * <p>What it deliberately does differently from the program, and why:
 *
 * <ul>
 *   <li>the fine falling threads are not sampled here at all. A vertex grid can resolve the storm's
 *       shape, which is tens of blocks across, but not a lane of rain twenty blocks wide - sampling that
 *       per vertex aliases it into blotches. So the mesh carries the smooth part of the field, and the
 *       lanes are sampled at a scale a vertex grid <em>can</em> resolve, which reads as the heavier and
 *       lighter parts of a curtain rather than as grain;</li>
 *   <li>there is no texture. The rain's colour and its structure are both in the vertices, on the white
 *       texture, because a picture of rain tiled over a subdivided mesh would put its own repeating
 *       pattern across a storm at a scale nobody chose;</li>
 *   <li>the light and the storm's own fog are baked into those vertices rather than left to the render
 *       type. The sky over the storm, the strike that is nearer the rain than the eye, and the mist the
 *       rain makes of its own air are all things no program can know about, because they come from the
 *       storm - so they are worked out on the client thread at the same time the density is, and the world's
 *       own fog is deliberately left alone for the render type and the pack to apply as they do to
 *       everything else. That division is what keeps the pack's fog from being applied twice;</li>
 *   <li>it writes no depth and is not culled, so the slices blend back to front into a volume rather than
 *       cutting each other away, exactly as the program's do.</li>
 * </ul>
 */
public final class RainVolumeMesh {
    /**
     * The white texture: this path's rain is coloured by its vertices, so the picture it samples has to
     * contribute nothing.
     */
    private static final ResourceLocation WHITE =
            ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    /**
     * The lane scale this path samples at, as a fraction of the program's.
     *
     * <p>Measured rather than picked: the vertex grid is about thirty blocks across per cell, so a lane of
     * rain twenty blocks wide cannot be represented by it. At this scale the lanes are eighty blocks apart,
     * which the grid resolves comfortably, and the rain's structure becomes the broad lighter and heavier
     * patches a curtain actually has when you are not close enough to see the threads.
     */
    private static final double LANE_SCALE_FRACTION = 0.27D;

    /** Rain in the air, as the program draws it: a cool grey, darker where the storm is thickest. */
    private static final float THIN_RED = 0.60F;
    private static final float THIN_GREEN = 0.63F;
    private static final float THIN_BLUE = 0.68F;
    private static final float THICK_RED = 0.34F;
    private static final float THICK_GREEN = 0.37F;
    private static final float THICK_BLUE = 0.42F;

    private RainVolumeMesh() {
    }

    /**
     * Draws one storm's rain as a mesh of slices cut through its field.
     *
     * <p>The slices are the same slices the program draws, at the same distances, back to front, so the
     * shape of the volume is the same shape either way.
     */
    static void draw(PoseStack.Pose pose, RainVolumeShader.VolumeInputs inputs, RainVolumeLight light, float partial) {
        int slices = Mth.clamp(Config.RAIN_VOLUME_PACK_SLICES.get(), 2, 24);
        int detail = Mth.clamp(Config.RAIN_VOLUME_PACK_DETAIL.get(), 2, 24);
        double slab = (inputs.spanEnd() - inputs.spanStart()) / slices;
        if (slab <= 0.01D) {
            return;
        }
        double extinction = RainVolumeShader.EXTINCTION_PER_BLOCK
                * Config.rainOpacity(Config.RAIN_VOLUME_OPACITY.get());
        double laneScale = RainVolumeShader.THREAD_SCALE * LANE_SCALE_FRACTION;
        double time = inputs.timeSeconds();

        Vec3 view = inputs.view();
        Vec3 right = inputs.right();
        Vec3 up = inputs.up();
        Vec3 camera = inputs.camera();
        double half = inputs.boundingRadius();

        // One vertex per grid corner per slice, evaluated once and used by the four quads that meet
        // there: the grid is the expensive part of this path and four quads sharing a corner should not
        // pay for it four times.
        double[][] alpha = new double[detail + 1][detail + 1];
        float[][][] colour = new float[detail + 1][detail + 1][3];

        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
        for (int slice = slices - 1; slice >= 0; slice--) {
            double distance = inputs.spanStart() + slab * (slice + 0.5D);
            Vec3 centre = view.scale(distance);
            for (int gu = 0; gu <= detail; gu++) {
                double u = -half + 2.0D * half * gu / detail;
                for (int gv = 0; gv <= detail; gv++) {
                    double v = -half + 2.0D * half * gv / detail;
                    Vec3 relative = centre.add(right.scale(u)).add(up.scale(v));
                    double worldX = camera.x + relative.x;
                    double worldY = camera.y + relative.y;
                    double worldZ = camera.z + relative.z;
                    double density = RainVolumeField.density(inputs.field(),
                            inputs.stormX(), inputs.stormZ(),
                            inputs.forwardX(), inputs.forwardZ(), inputs.rightX(), inputs.rightZ(),
                            worldX, worldZ, worldY, inputs.groundY(), inputs.topY(),
                            inputs.virga(), time, Config.RAIN_VOLUME_FALL_SPEED.get(), laneScale);
                    alpha[gu][gv] = RainVolumeField.slabAlpha(density, extinction, slab);
                    double thickness = Math.min(1.0D, density) * 0.85D;
                    // The same two things the program does per fragment, done here per vertex: what lights this
                    // piece of rain, and how much of it is fog. Both are the reading the program is given, so
                    // the two paths cannot light or fog the rain differently - and the fog here is the storm's
                    // own mist only, because the render type this is drawn with already takes the world's fog
                    // from the pack that replaced our program.
                    double[] lit = light.lightAt(worldX, worldY, worldZ, camera);
                    double fogMix = light.mistFog(relative.length(), density) * light.fogAlpha();
                    colour[gu][gv][0] = (float) RainVolumeField.mix(
                            RainVolumeField.mix(THIN_RED, THICK_RED, thickness) * lit[0], light.fogRed(), fogMix);
                    colour[gu][gv][1] = (float) RainVolumeField.mix(
                            RainVolumeField.mix(THIN_GREEN, THICK_GREEN, thickness) * lit[1], light.fogGreen(), fogMix);
                    colour[gu][gv][2] = (float) RainVolumeField.mix(
                            RainVolumeField.mix(THIN_BLUE, THICK_BLUE, thickness) * lit[2], light.fogBlue(), fogMix);
                }
            }
            for (int gu = 0; gu < detail; gu++) {
                for (int gv = 0; gv < detail; gv++) {
                    // Four corners of this cell, taken from the shared grid rather than recomputed.
                    corner(builder, pose, centre, right, up, half, detail, gu, gv, alpha, colour, view);
                    corner(builder, pose, centre, right, up, half, detail, gu + 1, gv, alpha, colour, view);
                    corner(builder, pose, centre, right, up, half, detail, gu + 1, gv + 1, alpha, colour, view);
                    corner(builder, pose, centre, right, up, half, detail, gu, gv + 1, alpha, colour, view);
                }
            }
        }
        RenderType.entityTranslucentEmissive(WHITE, false).draw(builder.buildOrThrow());
    }

    /** One corner of one cell, with the alpha and colour the shared grid already worked out. */
    private static void corner(BufferBuilder builder, PoseStack.Pose pose, Vec3 centre, Vec3 right, Vec3 up,
                               double half, int detail, int gu, int gv,
                               double[][] alpha, float[][][] colour, Vec3 view) {
        double u = -half + 2.0D * half * gu / detail;
        double v = -half + 2.0D * half * gv / detail;
        Vec3 relative = centre.add(right.scale(u)).add(up.scale(v));
        float a = (float) RainVolumeField.clamp01(alpha[gu][gv]);
        builder.addVertex(pose, (float) relative.x, (float) relative.y, (float) relative.z)
                .setColor(colour[gu][gv][0], colour[gu][gv][1], colour[gu][gv][2], a)
                .setUv(0.0F, 0.0F)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, (float) -view.x, (float) -view.y, (float) -view.z);
    }

    /** True when the mesh is the rain's drawing: the program is unavailable and the user wants rain. */
    static boolean active() {
        return Config.RAIN_VOLUME_ENABLED.get()
                && Config.RAIN_VOLUME_PACK_PATH.get()
                && !Config.classicLook()
                && !VanillaRainMode.active();
    }
}
