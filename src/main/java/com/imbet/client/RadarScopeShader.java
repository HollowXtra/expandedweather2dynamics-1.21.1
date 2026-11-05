package com.imbet.client;

import com.imbet.ExpandedWeather2Dynamics;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * The radar display's program: {@code radar_scope.fsh} over a {@link RadarFieldTexture}.
 *
 * <p>A handheld's screen and a radar block's face both go through this, so they are one picture rather than two.
 */
public final class RadarScopeShader {
    private static ShaderInstance shader;
    private static final RenderStateShard.ShaderStateShard SHADER_STATE = new RenderStateShard.ShaderStateShard(() -> shader);
    private static final Map<ResourceLocation, RenderType> TYPES = new HashMap<>();

    private RadarScopeShader() {
    }

    public static void register(RegisterShadersEvent event) throws IOException {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "radar_scope");
        event.registerShader(new ShaderInstance(event.getResourceProvider(), id, DefaultVertexFormat.POSITION_TEX_COLOR), loaded -> shader = loaded);
    }

    static boolean ready() {
        return shader != null;
    }

    static ShaderInstance instance() {
        return shader;
    }

    /**
     * A render type for one field's sheet, so a display can go through a buffer source with the rest of a block.
     *
     * <p>Drawing is deferred to when the batch ends, by which point another radar may have set its own uniforms, so a
     * display's settings are applied by the render type's own state at that moment rather than when it is written.
     */
    static RenderType type(RadarFieldTexture field) {
        return TYPES.computeIfAbsent(field.id(), location -> RenderType.create(
                ExpandedWeather2Dynamics.MODID + "_radar_scope",
                DefaultVertexFormat.POSITION_TEX_COLOR,
                VertexFormat.Mode.QUADS,
                256,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(SHADER_STATE)
                        .setTextureState(new RenderStateShard.TextureStateShard(location, true, false))
                        .setTexturingState(new RenderStateShard.TexturingStateShard("radar_scope_display", field::applyDisplay, () -> {
                        }))
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                        .setOverlayState(RenderStateShard.NO_OVERLAY)
                        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .createCompositeState(false)));
    }

    /** Everything the program needs to know about what it is showing and how much surface the panel has. */
    static void configure(RadarFieldTexture field, double worldX, double worldZ, int range, int mode, float ringRadius, float glass, boolean chart) {
        set("WorldCentre", (float) worldX, (float) worldZ);
        set("RangeBlocks", range);
        set("Product", mode);
        set("SweepAngle", field.sweepAngle());
        set("ScanStart", field.scanStart());
        set("ScanCovered", field.scanCovered());
        set("RingRadius", ringRadius);
        set("GlassAmount", glass);
        set("Chart", chart ? 1.0F : 0.0F);
    }

    private static void set(String name, float value) {
        Uniform uniform = shader == null ? null : shader.getUniform(name);
        if (uniform != null) {
            uniform.set(value);
        }
    }

    private static void set(String name, float first, float second) {
        Uniform uniform = shader == null ? null : shader.getUniform(name);
        if (uniform != null) {
            uniform.set(first, second);
        }
    }
}
