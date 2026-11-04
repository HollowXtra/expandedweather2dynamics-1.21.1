package com.imbet.mixin;

import com.imbet.client.VanillaRainMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import weather2.client.SceneEnhancer;

/**
 * Stops Weather2 spawning its own rain and snow particles where the mod is drawing vanilla's rain instead.
 *
 * <p>Weather2's precipitation particles are spawned inside {@code tickEnvironmentalParticleSpawning},
 * a method that also spawns the ambient, leaf and sandstorm particles that have nothing to do with
 * precipitation, so cancelling it would take a great deal more than the rain. What gates the
 * precipitation is two predicates - {@code shouldRainHere} and {@code shouldSnowHere} - and they are
 * each called exactly once in that method, so redirecting those two answers is the whole suppression:
 * the rain and snow branches are skipped and every other kind of particle in the same pass is
 * untouched. Weather2's own rain sound still plays, because its rain sound comes from different
 * methods, and vanilla's own weather sound plays under it.
 */
@Mixin(value = SceneEnhancer.class, remap = false)
public abstract class Weather2SceneEnhancerMixin {

    @Redirect(method = "tickEnvironmentalParticleSpawning()V", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lweather2/client/SceneEnhancer;shouldRainHere(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/biome/Biome;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean expandedweather2dynamics$skipWeather2RainParticles(Level level, Biome biome, BlockPos pos) {
        return !VanillaRainMode.suppressWeather2Precipitation() && SceneEnhancer.shouldRainHere(level, biome, pos);
    }

    @Redirect(method = "tickEnvironmentalParticleSpawning()V", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lweather2/client/SceneEnhancer;shouldSnowHere(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/biome/Biome;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean expandedweather2dynamics$skipWeather2SnowParticles(Level level, Biome biome, BlockPos pos) {
        return !VanillaRainMode.suppressWeather2Precipitation() && SceneEnhancer.shouldSnowHere(level, biome, pos);
    }
}
