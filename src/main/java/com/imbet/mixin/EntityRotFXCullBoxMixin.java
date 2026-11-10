package com.imbet.mixin;

import extendedrenderer.particle.entity.EntityRotFX;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Culls a cloud puff by what is actually drawn, where it actually is.
 *
 * <p>Weather2's particle renderer skips every puff whose render box is outside the view, and unless a puff opts into
 * a box of its own that box is the particle's collision box. That box is wrong for a cloud twice over. It is too
 * small: {@code setScale} sizes it to one scale wide and one scale tall upward from the centre, while the billboard is
 * two scales across and hangs as far below the centre as above it, so a deck puff seen from underneath is culled
 * while its lower half still fills the top of the screen. And it does not follow the puff: {@code setPosX/Y/Z} write
 * the coordinate and nothing else, so a puff moved that way - the sculpted supercell deck, a spout's parent cloud -
 * keeps culling against wherever it was born. Walking moves the view across both errors, and puffs blinked out all
 * over the sky as their stale or clipped boxes left it.
 *
 * <p>The box here is centred on the puff's current position and reaches the billboard's corners at any facing,
 * with a few blocks to spare for a tick's movement. A puff that set a render box of its own keeps it.
 */
@Mixin(value = EntityRotFX.class, remap = false)
public abstract class EntityRotFXCullBoxMixin {
    /** A tick's worth of movement either side, so a fast puff is not culled by its own interpolation. */
    private static final double MOVE_MARGIN = 4.0D;

    @Inject(method = "getBoundingBoxForRender", at = @At("HEAD"), cancellable = true)
    private void expandedweather2dynamics$cullByWhatIsDrawn(float partialTick, CallbackInfoReturnable<AABB> cir) {
        EntityRotFX self = (EntityRotFX) (Object) this;
        if (self.isUseCustomBBForRenderCulling()) {
            return;
        }
        // The billboard spans one quad size either way of its centre; its corners are 1.42 of that out at worst.
        double reach = Math.max(0.5D, self.getQuadSize(partialTick)) * 1.45D + MOVE_MARGIN;
        double x = self.getPosX();
        double y = self.getPosY();
        double z = self.getPosZ();
        cir.setReturnValue(new AABB(x - reach, y - reach, z - reach, x + reach, y + reach, z + reach));
    }
}
