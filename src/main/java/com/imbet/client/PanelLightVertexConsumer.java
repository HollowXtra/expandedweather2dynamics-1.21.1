package com.imbet.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * Stamps a light, an overlay and a normal onto panel vertices on their way to a vanilla render type.
 *
 * <p>The panel helpers write colour and UV and nothing else, because the mod's own panel programs
 * declare no lightmap and no overlay state - the values would have nowhere to go. The fallback type
 * samples both, so the missing light has to be supplied here rather than by rewriting every helper:
 * an element left unwritten is zero, and zero light is a black plate at noon.
 *
 * <p>All three are written the moment a vertex is started, which is the one point every writer is
 * guaranteed to pass through - the helpers differ in whether a colour or a UV comes first, and some
 * write only one of the two. They land in the current vertex, so the writer's own calls fill the rest
 * of it afterwards.
 *
 * <p>The normal is up, because every panel in this mod is drawn on a block's top face and read from
 * above; a shaderpack that shades faces by their normal then lights a panel the way the block it
 * stands on is lit. The overlay is explicitly none rather than the block's own, which would put the
 * held-item break flicker across a readout.
 */
final class PanelLightVertexConsumer implements VertexConsumer {

    private final VertexConsumer delegate;
    private final int packedLight;

    /** Up, in the panel's own space: these faces are drawn flat on a top face. */
    private static final float NORMAL_X = 0.0F;
    private static final float NORMAL_Y = 1.0F;
    private static final float NORMAL_Z = 0.0F;

    PanelLightVertexConsumer(VertexConsumer delegate, int packedLight) {
        this.delegate = delegate;
        this.packedLight = packedLight;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        delegate.addVertex(x, y, z)
                .setLight(packedLight)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setNormal(NORMAL_X, NORMAL_Y, NORMAL_Z);
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        delegate.setColor(red, green, blue, alpha);
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        delegate.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        delegate.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        delegate.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float normalX, float normalY, float normalZ) {
        delegate.setNormal(normalX, normalY, normalZ);
        return this;
    }
}
