package com.imbet.mixin;

import com.imbet.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import weather2.weathersystem.storm.TornadoHelper;

/**
 * The client half of this mod's hooks into Weather2's tornado helper. {@code soundUpdates} is client-only in
 * Weather2 and is stripped on a dedicated server, so the hook lives in the client mixin list: in
 * {@code TornadoHelperMixin}, which servers apply too, a required hook with no method to attach to stopped the
 * server from starting.
 */
@Mixin(value = TornadoHelper.class, remap = false)
public abstract class TornadoHelperClientMixin {
    /**
     * Silences Weather2's own tornado wind, so the mod's roar is the only one.
     *
     * <p>Weather2's wind is not one sound to replace but three sets picked by loudness -
     * {@code WeatherUtilSound.snd_wind_far}, {@code snd_wind_close} and {@code snd_tornado_dmg_close} - and this
     * method is the only place any of them is played. Cancelling it is therefore exactly "no Weather2 wind" and
     * nothing else: its siren blocks, destruction and sandstorm audio are all played by other classes and keep
     * working.
     *
     * <p>The reason to replace rather than layer is that the two do not agree about anything. Ours is a
     * continuous loop anchored at the funnel whose own falloff runs to the wall and out to hundreds of blocks;
     * Weather2's is a player-locked sample retriggered on the two-second world-time cycle, fading in a straight
     * line to silence at 120 blocks and only while the player is outdoors. Played together they double up, and
     * the faster retrigger beats against the roar.
     *
     * <p>It only stands Weather2 down while the replacement is actually going to play: with the immersion
     * system or the roar switched off, Weather2's wind is left alone rather than the tornado falling silent.
     */
    @Inject(method = "soundUpdates", at = @At("HEAD"), cancellable = true)
    private void expandedweather2dynamics$replaceWeather2Wind(boolean nearTornado, boolean insideTornado,
                                                              CallbackInfo ci) {
        if (Config.TORNADO_IMMERSION_REPLACE_WEATHER2_WIND.get()
                && Config.TORNADO_IMMERSION_ENABLED.get()
                && Config.TORNADO_IMMERSION_ROAR_SOUND.get()) {
            ci.cancel();
        }
    }
}
