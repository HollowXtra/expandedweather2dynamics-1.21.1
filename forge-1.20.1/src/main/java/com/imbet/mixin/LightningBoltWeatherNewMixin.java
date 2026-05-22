package com.imbet.mixin;

import com.imbet.weather.ThunderBridge;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import weather2.weathersystem.storm.LightningBoltWeatherNew;

@Mixin(value = LightningBoltWeatherNew.class, remap = false)
public abstract class LightningBoltWeatherNewMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V"))
    private void expandedweather2dynamics$delayThunder(Level level, double x, double y, double z, SoundEvent sound, SoundSource source, float volume, float pitch, boolean distanceDelay) {
        ThunderBridge.playOrSchedule(level, x, y, z, sound, source, volume, pitch, distanceDelay);
    }
}
