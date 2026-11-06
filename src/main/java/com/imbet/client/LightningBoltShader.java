package com.imbet.client;

import com.imbet.ExpandedWeather2Dynamics;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import java.io.IOException;

/**
 * The program and the render type one lightning strike is drawn with.
 *
 * <p>This is a mod core shader rather than an override of vanilla's {@code rendertype_lightning}: the
 * geometry the mod generates carries a cross-section and an along-channel coordinate in its UVs, and
 * vanilla's lightning vertex format has no UVs to carry them in. Overriding vanilla's program would
 * also leave every other mod's bolts drawn through it with uniforms nobody had set.
 */
public final class LightningBoltShader {
    private static ShaderInstance shader;
    private static final RenderStateShard.ShaderStateShard SHADER_STATE =
            new RenderStateShard.ShaderStateShard(() -> shader);
    private static final RenderType BOLT = RenderType.create(
            ExpandedWeather2Dynamics.MODID + "_lightning_bolt",
            DefaultVertexFormat.POSITION_TEX_COLOR,
            VertexFormat.Mode.QUADS,
            // A strike is a few tens of quads once the trunk, its forks and the ground glow are in:
            // around ten kilobytes of POSITION_TEX_COLOR vertices, so this is sized for a whole
            // strike rather than for one of the mod's instrument faces.
            16384,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(SHADER_STATE)
                    // Additive, which is what vanilla's own lightning uses: a channel is a light source,
                    // so two ribbons crossing have to add rather than replace one another. It is also
                    // what makes the core read white where the halo and the core pass overlap.
                    .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    // Occluded by terrain, but never writing depth: a glow that punched holes in the
                    // depth buffer would erase the rain, the deck and the funnel standing behind it.
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                    .setOverlayState(RenderStateShard.NO_OVERLAY)
                    .createCompositeState(false)
    );

    private LightningBoltShader() {
    }

    public static void register(RegisterShadersEvent event) throws IOException {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "lightning_bolt");
        event.registerShader(
                new ShaderInstance(event.getResourceProvider(), id, DefaultVertexFormat.POSITION_TEX_COLOR),
                loaded -> shader = loaded
        );
    }

    /** True once the program has loaded. Until then the vanilla bolt is left alone rather than lost. */
    public static boolean ready() {
        return shader != null;
    }

    /**
     * Draws one strike.
     *
     * <p>Every uniform here belongs to this strike rather than to the frame - its age, its own seed,
     * how far away it is - so the mesh is flushed per bolt instead of being batched with everything
     * else. Strikes are rare and a mesh is a few hundred vertices, so a flush each is nothing.
     */
    public static void draw(PoseStack stack, LightningBoltShape.Mesh mesh, float ageSeconds, float seed,
                            float gain, float distance, float core, float filaments, float strobes, float height,
                            float range) {
        if (shader == null || mesh == null || mesh.vertexCount == 0) {
            return;
        }
        setUniform("LightningAge", ageSeconds);
        setUniform("LightningSeed", seed);
        setUniform("LightningGain", gain);
        setUniform("LightningDistance", distance);
        setUniform("LightningCore", core);
        setUniform("LightningFilaments", filaments);
        setUniform("LightningStrobes", strobes);
        setUniform("LightningHeight", height);
        setUniform("LightningRange", range);

        PoseStack.Pose pose = stack.last();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int vertex = 0; vertex < mesh.vertexCount; vertex++) {
            int offset = vertex * LightningBoltShape.STRIDE;
            builder.addVertex(pose, mesh.data[offset], mesh.data[offset + 1], mesh.data[offset + 2])
                    .setUv(mesh.data[offset + 3], mesh.data[offset + 4])
                    .setColor(mesh.data[offset + 5], mesh.data[offset + 6], mesh.data[offset + 7], mesh.data[offset + 8]);
        }
        BOLT.draw(builder.buildOrThrow());
    }

    /**
     * The game's own lightning program, which is the one a shader pack supplies its own version of. A mod program is not
     * one of the programs a pack knows, so under a pack the mod's bolt drew as nothing; drawn through this one it is shaded
     * by the pack like any other strike.
     */
    private static final RenderType BOLT_GAME_PROGRAM = RenderType.create(
            ExpandedWeather2Dynamics.MODID + "_lightning_bolt_game_program",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            65536,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_LIGHTNING_SHADER)
                    .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                    .setOverlayState(RenderStateShard.NO_OVERLAY)
                    .createCompositeState(false)
    );
    /** How many strips each ribbon is cut into across its width, so the soft profile can be carried in vertex colours. */
    private static final int STRIPS = 8;
    /** The V the shape writes for the lit ground, as in the fragment program. */
    private static final float GROUND_GLOW_V = 1.2F;

    /**
     * Draws one strike through the game's own lightning program. That program only passes the vertex colour through, so
     * what the mod's fragment program works out per pixel - the soft core-and-halo profile across the channel, the flicker
     * of the strokes, the dimming with distance - is worked out here per vertex instead, with each ribbon cut into strips
     * across its width so the profile survives being carried by vertices. The threads along the channel and the travelling
     * return stroke are the two things left out: they need a per-pixel program to draw.
     */
    public static void drawThroughGameProgram(PoseStack stack, LightningBoltShape.Mesh mesh, float ageSeconds, float gainSetting,
                                              float distance, float core, float strobes, float range) {
        if (mesh == null || mesh.vertexCount < 4) {
            return;
        }
        float envelope = Mth.lerp(Mth.clamp(strobes, 0.0F, 1.0F), 1.0F, Math.max(flashEnvelope(ageSeconds), 0.0F));
        float extinction = 1.0F / (1.0F + distance * 0.0042F);
        float rangeFade = 1.0F - smoothstep(range * 0.55F, range, distance);
        float gain = Math.max(gainSetting, 0.0F) * extinction * rangeFade * envelope;
        if (gain < 0.004F) {
            return;
        }
        float haze = Mth.clamp(distance / 340.0F, 0.0F, 1.0F);
        float heat = Mth.clamp(core, 0.0F, 2.0F);
        PoseStack.Pose pose = stack.last();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        float[] data = mesh.data;
        int quads = mesh.vertexCount / 4;
        for (int quad = 0; quad < quads; quad++) {
            int first = quad * 4;
            // Every quad the shape writes runs its U from the edge of corners 0 and 1 to the edge of corners 2 and 3, so the
            // strips are cut between those two edges. The lit ground is already cut into rings and needs no strips.
            boolean ground = data[first * LightningBoltShape.STRIDE + 4] > GROUND_GLOW_V - 0.1F;
            int strips = ground ? 1 : STRIPS;
            for (int strip = 0; strip < strips; strip++) {
                float t0 = strip / (float) strips;
                float t1 = (strip + 1) / (float) strips;
                shadedVertex(builder, pose, data, first, first + 3, t0, gain, haze, heat, ground);
                shadedVertex(builder, pose, data, first + 1, first + 2, t0, gain, haze, heat, ground);
                shadedVertex(builder, pose, data, first + 1, first + 2, t1, gain, haze, heat, ground);
                shadedVertex(builder, pose, data, first, first + 3, t1, gain, haze, heat, ground);
            }
        }
        com.mojang.blaze3d.vertex.MeshData built = builder.build();
        if (built != null) {
            BOLT_GAME_PROGRAM.draw(built);
        }
    }

    /** One vertex part of the way between two of the shape's, shaded the way the mod's fragment program shades it. */
    private static void shadedVertex(BufferBuilder builder, PoseStack.Pose pose, float[] data, int a, int b, float t,
                                     float gain, float haze, float heat, boolean ground) {
        int oa = a * LightningBoltShape.STRIDE;
        int ob = b * LightningBoltShape.STRIDE;
        float x = Mth.lerp(t, data[oa], data[ob]);
        float y = Mth.lerp(t, data[oa + 1], data[ob + 1]);
        float z = Mth.lerp(t, data[oa + 2], data[ob + 2]);
        float u = Mth.lerp(t, data[oa + 3], data[ob + 3]);
        float red = Mth.lerp(t, data[oa + 5], data[ob + 5]);
        float green = Mth.lerp(t, data[oa + 6], data[ob + 6]);
        float blue = Mth.lerp(t, data[oa + 7], data[ob + 7]);
        float passAlpha = Mth.clamp(Mth.lerp(t, data[oa + 8], data[ob + 8]), 0.02F, 1.0F);
        float cross = Math.abs(u * 2.0F - 1.0F);
        float tightness = Mth.lerp(smoothstep(0.30F, 0.95F, passAlpha), 1.5F, 5.2F);
        float profile = (float) Math.pow(Math.max(1.0F - cross, 0.0F), tightness);
        float coreMix = Mth.clamp((float) Math.pow(profile, 0.85F) * heat, 0.0F, 1.0F);
        red = Mth.lerp(coreMix, red, 1.00F);
        green = Mth.lerp(coreMix, green, 0.975F);
        blue = Mth.lerp(coreMix, blue, 0.935F);
        float cool = haze * 0.65F;
        red = Mth.lerp(cool, red, red * 0.74F);
        green = Mth.lerp(cool, green, green * 0.82F);
        blue = Mth.lerp(cool, blue, blue * 1.06F);
        float lift = 0.62F + 0.38F * coreMix;
        float alpha = passAlpha * profile * gain * (ground ? 0.90F : 1.0F);
        builder.addVertex(pose, x, y, z).setColor(Math.min(1.0F, red * lift), Math.min(1.0F, green * lift), Math.min(1.0F, blue * lift), Mth.clamp(alpha, 0.0F, 1.0F));
    }

    /** The strokes of one flash, as in the fragment program: four arriving at once and decaying, and the afterglow. */
    private static float flashEnvelope(float age) {
        float envelope = stroke(age, 0.000F, 1.00F, 30.0F)
                + stroke(age, 0.045F, 0.74F, 33.0F)
                + stroke(age, 0.105F, 0.56F, 36.0F)
                + stroke(age, 0.190F, 0.34F, 40.0F);
        return Math.min(envelope + stroke(age, 0.000F, 0.15F, 2.9F), 1.15F);
    }

    private static float stroke(float age, float start, float amplitude, float decay) {
        float since = age - start;
        return since < 0.0F ? 0.0F : amplitude * (float) Math.exp(-since * decay);
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = Mth.clamp((value - edge0) / Math.max(1.0E-6F, edge1 - edge0), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    private static void setUniform(String name, float value) {
        Uniform uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.set(value);
        }
    }
}
