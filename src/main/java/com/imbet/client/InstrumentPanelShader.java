package com.imbet.client;

import com.imbet.ExpandedWeather2Dynamics;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import java.io.IOException;

/**
 * The render type the instrument readouts are drawn with.
 *
 * <p>It is deliberately not {@link RadarShader}: that program draws a circular scope with range rings
 * and a rotating sweep beam, and it branches on the vertex alpha - its plate is drawn below that
 * threshold and its inner plate above it, so every instrument that borrowed it ended up wearing a
 * radar sweep. The panels want a plain instrument face, and this is that face as its own program:
 * same vertex format and composite state, no circular mask, no sweep, no discard.
 *
 * <p>The radar block keeps {@link RadarShader}, because a radar genuinely should look like one.
 */
public final class InstrumentPanelShader {
    private static ShaderInstance shader;
    private static final RenderStateShard.ShaderStateShard SHADER_STATE = new RenderStateShard.ShaderStateShard(() -> shader);
    private static final RenderType PANEL = RenderType.create(
            ExpandedWeather2Dynamics.MODID + "_instrument_panel",
            DefaultVertexFormat.POSITION_TEX_COLOR,
            VertexFormat.Mode.QUADS,
            1536,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(SHADER_STATE)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                    .setOverlayState(RenderStateShard.NO_OVERLAY)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false)
    );

    private InstrumentPanelShader() {
    }

    public static void register(RegisterShadersEvent event) throws IOException {
        // Cleared first: if this program fails to compile, the one the last load built is closed with the rest of
        // the old shaders, and every user of it checks for null rather than drawing with a dead program.
        shader = null;
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "instrument_panel");
        event.registerShader(new ShaderInstance(event.getResourceProvider(), id, DefaultVertexFormat.POSITION_TEX_COLOR), loaded -> shader = loaded);
    }

    /** The instrument faces' render type, or {@code null} if its program did not compile on this driver. */
    public static RenderType panel() {
        return shader == null ? null : PANEL;
    }
}
