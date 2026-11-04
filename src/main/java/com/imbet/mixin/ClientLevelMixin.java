package com.imbet.mixin;

import com.imbet.client.LightningFlash;
import com.imbet.client.StormSkyClient;
import com.imbet.weather.StormSky;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands what a strike lights to the mod, so the sky, the fog and the cloud deck follow the channel
 * instead of vanilla's flash counter.
 *
 * <p>Three hooks, one cause. Vanilla's counter is a two-tick integer: it is set to 2 by every bolt,
 * counted down one a tick, and read in {@code getSkyColor} as {@code clamp(flash - partialTick, 0, 1)},
 * so on the tick it is down to 1 the sky's flash crosses its whole range inside that tick and a storm
 * that strikes repeatedly strobes the sky twenty times a second. The fog takes its colour from the sky,
 * so it strobes with it. The counter is also cancelled here, which is what stops the smooth flash from
 * being added on top of the counter's own.
 *
 * <p>The cloud deck is the third hook and the only one that is not a repair: vanilla never flashes
 * {@code getCloudColor}, so a strike lit the sky, lit the fog, and left the deck itself looking
 * untouched. The deck is the surface the light lands on, so it gets the flash too - at a weight of its
 * own, since it carries the light rather than being mixed into it.
 *
 * <p>All three are no-ops unless {@link LightningFlash#owns()} - the revamped bolt, its program loaded,
 * no shader pack - so with the revamp off this class does nothing at all and vanilla's own flash is
 * untouched.
 *
 * <p>Two more hooks are here for the storm's own sky rather than for a strike, and they are the reason
 * this mod no longer writes a rain level into the level to get its sky darkened. The sky's colour and the
 * cloud deck's are shaded from {@link StormSkyClient}'s reading - the deck's share of the sky over the
 * player and how deep in a hailing core's footprint they are standing - and the world's ambient light is
 * floored by the same reading through {@code getSkyDarken}, which is the one value the lightmap is built
 * from. A floor rather than a replacement, so a world that is really raining or really night is not
 * darkened twice, and so the darkest a storm can make the world is the dark the game already knows how to
 * draw.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {

    /**
     * Takes the counter out of the picture and records the strike, if the revamp has not already.
     *
     * <p>Cancelling rather than letting it run matters even though nothing reads it afterwards: leaving
     * it to tick would let the two systems disagree about whether a strike is still burning, and the
     * counter is the only writer of the field any consumer sees.
     */
    @Inject(method = "setSkyFlashTime", at = @At("HEAD"), cancellable = true)
    private void expandedweather2dynamics$ownSkyFlash(int timeFlash, CallbackInfo callback) {
        if (LightningFlash.owns()) {
            LightningFlash.onVanillaFlash();
            callback.cancel();
        }
    }

    /**
     * The sky and the fog, under the storm's own deck and then under the strike's light.
     *
     * <p>Two things happen here and the order is the argument for them being one handler. The storm's own
     * darkening and its hail green are the sky this weather has made, so they go on first; the flash is
     * mixed over the top of that, because a strike lights the sky the storm is already under. Splitting them
     * into two injections at the same point would leave which one wins to the order the mixin applies them
     * in, which is not a thing to have an opinion about with an artefact on the line. Vanilla's own term has
     * already run and contributed nothing - its counter is zero, because this class cancels every write to
     * it - so the flash added here is the whole flash: the same mix toward the same flash colour, weighted by
     * the mod's level instead of by the counter.
     */
    @Inject(method = "getSkyColor", at = @At("RETURN"), cancellable = true)
    private void expandedweather2dynamics$stormSkyAndFlash(Vec3 pos, float partialTick,
                                                           CallbackInfoReturnable<Vec3> callback) {
        Vec3 color = StormSkyClient.shadeSky(callback.getReturnValue());
        if (!LightningFlash.owns()) {
            callback.setReturnValue(color);
            return;
        }
        float weight = LightningFlash.skyWeight(LightningFlash.level(partialTick));
        if (weight <= 0.001F) {
            callback.setReturnValue(color);
            return;
        }
        // The flash colour is vanilla's own: a flash is slightly blue-white, which is why its blue
        // channel is pushed to full while the other two go to four fifths.
        callback.setReturnValue(new Vec3(
                Mth.lerp(weight, color.x, 0.8D),
                Mth.lerp(weight, color.y, 0.8D),
                Mth.lerp(weight, color.z, 1.0D)
        ));
    }

    /**
     * The deck itself: the storm's darkening and green, then the flash vanilla never gives a deck.
     *
     * <p>The deck takes less of the green than the sky behind it does - it is the surface the light is coming
     * through rather than the light, and a deck as green as its own sky reads as a filter over the picture.
     */
    @Inject(method = "getCloudColor", at = @At("RETURN"), cancellable = true)
    private void expandedweather2dynamics$deckAndFlash(float partialTick, CallbackInfoReturnable<Vec3> callback) {
        Vec3 color = StormSkyClient.shadeDeck(callback.getReturnValue());
        if (!LightningFlash.owns()) {
            callback.setReturnValue(color);
            return;
        }
        float weight = LightningFlash.cloudWeight(LightningFlash.level(partialTick));
        if (weight <= 0.001F) {
            callback.setReturnValue(color);
            return;
        }
        // Slightly less blue than the sky's flash colour: a lit deck is white-grey cloud, and pushing it
        // as blue as the sky reads as the sky showing through rather than as cloud being lit.
        callback.setReturnValue(new Vec3(
                Mth.lerp(weight, color.x, 0.90D),
                Mth.lerp(weight, color.y, 0.92D),
                Mth.lerp(weight, color.z, 1.0D)
        ));
    }

    /**
     * The world's own ambient light, floored by the storm's own darkening.
     *
     * <p>This is the value the lightmap is built from - vanilla computes the sky-light half of every baked
     * light from it - so darkening it here darkens everything the world is lit by, from the ground under a
     * tree to a mob's face, without a second reading anywhere. It is a floor: the game's own value wins
     * whenever it is lower, so an actual night or an actual thunderstorm is still the darker of the two and a
     * storm cannot stack on top of either.
     */
    @Inject(method = "getSkyDarken", at = @At("RETURN"), cancellable = true)
    private void expandedweather2dynamics$stormDarken(float partialTick, CallbackInfoReturnable<Float> callback) {
        callback.setReturnValue((float) StormSky.worldDarken(callback.getReturnValue(), StormSkyClient.darkness()));
    }
}
