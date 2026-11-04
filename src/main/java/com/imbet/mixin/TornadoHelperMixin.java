package com.imbet.mixin;

import com.imbet.Config;
import com.imbet.weather.DynamicStormController;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import weather2.weathersystem.storm.TornadoHelper;

@Mixin(value = TornadoHelper.class, remap = false)
public abstract class TornadoHelperMixin {
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

    @Inject(method = "getTornadoBaseSize", at = @At("RETURN"), cancellable = true)
    private void expandedweather2dynamics$variableWidth(CallbackInfoReturnable<Integer> cir) {
        TornadoHelper helper = (TornadoHelper) (Object) this;
        cir.setReturnValue(DynamicStormController.adjustedTornadoBaseSize(helper.storm, cir.getReturnValue()));
    }

    @Inject(method = "tryRip", at = @At("HEAD"), cancellable = true)
    private void expandedweather2dynamics$limitDamage(Level level, int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        if (!Config.damageEnabled()) {
            return;
        }
        TornadoHelper helper = (TornadoHelper) (Object) this;
        if (!DynamicStormController.shouldTryRip(helper, level, new BlockPos(x, y, z))) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "canGrab", at = @At("HEAD"), cancellable = true)
    private void expandedweather2dynamics$canGrabBlock(Level level, BlockState state, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!Config.damageEnabled()) {
            return;
        }
        TornadoHelper helper = (TornadoHelper) (Object) this;
        if (!DynamicStormController.canGrabBlock(helper, level, state, pos)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "canGrabEntity", at = @At("HEAD"), cancellable = true)
    private void expandedweather2dynamics$canGrabEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!Config.damageEnabled()) {
            return;
        }
        TornadoHelper helper = (TornadoHelper) (Object) this;
        if (DynamicStormController.shouldForceGrabEntity(helper, entity)) {
            cir.setReturnValue(true);
            return;
        }
        if (!DynamicStormController.canGrabEntity(helper, entity)) {
            cir.setReturnValue(false);
        }
    }
}
