package com.imbet.client;

import com.imbet.ExpandedWeather2Dynamics;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Which program an instrument or radar face is drawn through.
 *
 * <p>The readouts are drawn with the mod's own core programs, and those programs are the reason the
 * faces have their machined grain, their drifting sheen and - for the radar - a scope that is
 * generated from the panel coordinate rather than painted. All of that needs a {@code ShaderInstance}
 * this mod registers itself, and that is exactly what an active Iris shaderpack takes away: the pack
 * replaces the core programs, and a mod program the pack does not provide is left with a fallback
 * that does not draw the face. Every panel used to answer that by returning early, so with a pack on,
 * a barometer, a rain gauge, a radio, a probe, the SPC outlook and the radar all simply disappeared.
 *
 * <p>They are drawn through {@link RenderType#entityTranslucent} instead while a pack is active. It is
 * a vanilla program, so the pack replaces it properly; it carries the lightmap, so a face darkens with
 * the world it stands in rather than glowing at midnight; it draws without culling, which the panel
 * helpers rely on; and its vertex format is a superset of the one the panels already write, so only
 * the light, the overlay and a normal have to be supplied - {@link PanelLightVertexConsumer} does that.
 *
 * <p>The plate texture is deliberately a flat white sheet: every colour, border and bar on these faces
 * is vertex data already, so the texture's only job is to give the program something to sample. What is
 * lost under a pack is the program's own surface, and a face becomes the flat plate its geometry
 * describes - the honest trade, because the alternative was no face at all.
 */
final class PanelRenderPath {
    /**
     * The flat sheet the fallback samples, and the only texture this path needs.
     *
     * <p>White rather than transparent or grey: the fallback multiplies it by the vertex colour, so
     * white leaves every panel's own palette exactly as written and one texture serves all of them.
     */
    private static final ResourceLocation PLATE = ResourceLocation.fromNamespaceAndPath(
            ExpandedWeather2Dynamics.MODID, "textures/misc/panel_plate.png");

    /**
     * The vertex alpha the radar program reads as "generate the scope here instead of keeping my colour".
     *
     * <p>It is a contract with {@code radar_panel.fsh} (see {@link RadarShader#PASS_THROUGH_ALPHA}) and
     * it is meaningless to the fallback program, which keeps whatever colour it is handed. So the
     * screen pass has to be swapped rather than reused: at that alpha a vanilla program would paint a
     * nearly invisible wash where the scope should be.
     */
    private static final int SHADER_GENERATED_PASS = 0x2A020303;

    /**
     * The scope face the fallback draws in its place: dark glass, opaque enough to read as a screen.
     *
     * <p>It is drawn first, under the map, the returns and the markers, so those sit on top of it in
     * draw order exactly as they do under the program - the geometry was always painted over the scope
     * rather than branched around it.
     */
    private static final int FALLBACK_SCREEN_PASS = 0xE60A1014;

    private PanelRenderPath() {
    }

    /**
     * The render type a panel's geometry should be written to, program or pack.
     *
     * <p>A {@code null} program is one that did not compile on this driver, and is drawn exactly as under a
     * pack: through the vanilla fallback, which every driver can run, rather than through a missing program.
     */
    static RenderType type(RenderType customProgram) {
        return usesFallback(customProgram) ? RenderType.entityTranslucent(PLATE) : customProgram;
    }

    private static boolean usesFallback(RenderType customProgram) {
        return customProgram == null || RenderingCompat.isShaderPackActive();
    }

    /**
     * A consumer for a panel's geometry, lit when it has to be.
     *
     * <p>The custom programs declare no lightmap state, so the panels never wrote a light and never
     * needed one. The fallback type does sample the lightmap, and an unwritten light is zero - a black
     * face, not a bright one - so the block's own packed light is stamped onto every vertex on the way
     * through. That is what makes a panel follow the world it stands in, and it is the same packed
     * light the font is already given for its text, so words and plate agree.
     */
    static VertexConsumer consumer(MultiBufferSource buffer, RenderType customProgram, int packedLight) {
        RenderType type = type(customProgram);
        VertexConsumer consumer = buffer.getBuffer(type);
        return type == customProgram ? consumer : new PanelLightVertexConsumer(consumer, packedLight);
    }

    /** The radar's screen pass, generated by the program or painted by the fallback. */
    static int screenPass(RenderType customProgram, int pass) {
        return usesFallback(customProgram) && pass == SHADER_GENERATED_PASS
                ? FALLBACK_SCREEN_PASS : pass;
    }
}
