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

    private static void setUniform(String name, float value) {
        Uniform uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.set(value);
        }
    }
}
