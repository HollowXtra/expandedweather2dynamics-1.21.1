package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

import java.lang.reflect.Method;

public final class ThunderBridge {
    private ThunderBridge() {
    }

    public static void playOrSchedule(Level level, double x, double y, double z, SoundEvent sound, SoundSource source, float volume, float pitch, boolean distanceDelay) {
        if (!Config.IMMERSIVE_THUNDER_ENABLED.get() || sound != SoundEvents.LIGHTNING_BOLT_THUNDER || FMLEnvironment.dist != Dist.CLIENT || !level.isClientSide()) {
            level.playLocalSound(x, y, z, sound, source, volume, pitch, distanceDelay);
            return;
        }
        try {
            Class<?> hooks = Class.forName("com.imbet.client.ClientWeatherEffects");
            Method method = hooks.getMethod("scheduleThunder", double.class, double.class, double.class, float.class, float.class);
            method.invoke(null, x, y, z, volume, pitch);
        } catch (ReflectiveOperationException exception) {
            level.playLocalSound(x, y, z, sound, source, volume * 0.4F, pitch, distanceDelay);
        }
    }
}
