package com.imbet.mixin;

import com.imbet.client.LodViewDistance;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reports the LOD renderer's draw distance as the render distance while one is drawing, so that
 * anything which culls itself by "how far do we draw" is not cut off short of terrain that is
 * actually on screen.
 *
 * <p>This one value is the whole LOD compatibility fix, because every affected renderer already
 * routes through it while vanilla itself never calls it: {@code GameRenderer.getRenderDistance()}
 * has no caller anywhere in the Minecraft sources - it exists for mods. Weather2's
 * {@code TornadoFunnelSimple} reads it once per update into a field it then hands to every funnel
 * and debris particle via {@code PivotingParticle.setRenderDistanceCull}, multiplying it by 4. Our
 * satellite funnel, sub-vortex and addon vortex particles derive their cutoff the same way. All of
 * them pick the extension up here without changing a line of their own.
 *
 * <p>That multiply-by-four is also why the far clip plane follows this value, on both sides:
 * vanilla's own {@code getDepthFar()} already returns {@code renderDistance * 4}, and Weather2's
 * {@code GameRendererOverride} mixin reimplements exactly that formula. Following it here is the
 * point rather than a side effect to avoid - geometry past the far plane is clipped, so a longer
 * particle cull on its own would leave a distant tornado culled in and then clipped out - but it
 * does mean the extension trades depth-buffer precision far from the camera, which is what the
 * config's upper bound is for.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "getRenderDistance", at = @At("RETURN"), cancellable = true)
    private void expandedweather2dynamics$lodAwareRenderDistance(CallbackInfoReturnable<Float> cir) {
        // Never let a LOD probe throw out of the render path: a broken version of either mod must
        // cost us the extension, not the frame.
        try {
            Float original = cir.getReturnValue();
            if (original == null) {
                return;
            }
            float extended = LodViewDistance.extend(original);
            if (extended > original) {
                cir.setReturnValue(extended);
            }
        } catch (Throwable ignored) {
        }
    }
}
