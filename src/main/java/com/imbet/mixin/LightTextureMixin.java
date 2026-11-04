package com.imbet.mixin;

import com.imbet.client.LightningFlash;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stops the lightmap flashing like a switch.
 *
 * <p>The lightmap is the other place lightning lights the world, and the one that shows on everything
 * rather than on the sky: the sky-light half of every baked light value is scaled by one number, and
 * vanilla's own flash replaces that number with a flat full brightness for as long as the flash counter
 * is above zero. That is a boolean over a counter that is set afresh by every bolt, so a storm that
 * strikes every couple of seconds flips the world's ambient light between two levels with the same beat
 * the sky colour sweeps on - the whole scene pulsing, not the strike flashing.
 *
 * <p>The fix is placed on the darkness rather than on the flash test, because the test is a boolean and
 * cannot be given a value: nothing in that method is proportional to anything. {@code getSkyDarken} is
 * the number the darkness is actually built from, and interpolating it toward full brightness by the
 * mod's flash level reaches the same ceiling the boolean did - exactly the same value at the peak, since
 * both end at full brightness - while moving there and back instead of switching.
 *
 * <p>That this redirect only ever changes anything while {@link LightningFlash#owns()} is the point: the
 * counter whose boolean consumed this value is cancelled in {@code ClientLevelMixin}, so with the revamp
 * off the branch that ignores the darkness is the one that runs, and this handler returns exactly what
 * it was given.
 */
@Mixin(LightTexture.class)
public abstract class LightTextureMixin {

    @Redirect(
            method = "updateLightTexture",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;getSkyDarken(F)F"
            )
    )
    private float expandedweather2dynamics$flashLightmap(ClientLevel level, float partialTick) {
        return LightningFlash.lightmapDarken(level.getSkyDarken(partialTick));
    }
}
