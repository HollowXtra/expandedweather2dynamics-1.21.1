package com.imbet.client;

import com.imbet.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

public final class MorningFogClient {
    private static float fogIntensity;
    private static float waterHumidity;
    private static long nextHumiditySampleTick;

    private MorningFogClient() {
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || !Config.MORNING_FOG_ENABLED.get()) {
            fogIntensity = Mth.lerp(0.12F, fogIntensity, 0.0F);
            waterHumidity = 0.0F;
            return;
        }

        ClientLevel level = minecraft.level;
        Player player = minecraft.player;
        long gameTime = level.getGameTime();
        if (gameTime >= nextHumiditySampleTick) {
            nextHumiditySampleTick = gameTime + 20L;
            waterHumidity = nearbyWaterHumidity(level, player.blockPosition());
        }

        float morning = morningWindow(level.getDayTime() % 24000L);
        float weatherMoisture = Mth.clamp(level.getRainLevel(1.0F) * 0.45F - level.getThunderLevel(1.0F) * 0.35F, 0.0F, 0.45F);
        float humidity = Mth.clamp(0.18F + waterHumidity * 0.68F + weatherMoisture, 0.0F, 1.0F);
        float target = morning * humidity * Config.MORNING_FOG_DENSITY.get().floatValue();
        fogIntensity = Mth.lerp(target > fogIntensity ? 0.055F : 0.032F, fogIntensity, target);
        if (fogIntensity < 0.008F && target < 0.008F) {
            fogIntensity = 0.0F;
        }
    }

    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        if (fogIntensity <= 0.01F) {
            return;
        }
        float blend = Mth.clamp(fogIntensity * 0.72F, 0.0F, 0.78F);
        event.setRed(Mth.lerp(blend, event.getRed(), 0.74F));
        event.setGreen(Mth.lerp(blend, event.getGreen(), 0.80F));
        event.setBlue(Mth.lerp(blend, event.getBlue(), 0.88F));
    }

    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (fogIntensity <= 0.02F) {
            return;
        }
        float pressure = Mth.clamp(fogIntensity, 0.0F, 1.0F);
        float farPlane = Mth.lerp(pressure, 180.0F, 42.0F);
        event.setFarPlaneDistance(Math.min(event.getFarPlaneDistance(), farPlane));
        event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(), 0.0F));
    }

    private static float morningWindow(long dayTime) {
        if (dayTime > 3200L && dayTime < 22600L) {
            return 0.0F;
        }
        if (dayTime >= 22600L) {
            return Mth.clamp((dayTime - 22600L) / 1400.0F, 0.0F, 1.0F) * 0.55F;
        }
        if (dayTime <= 1200L) {
            return Mth.clamp(dayTime / 1200.0F, 0.45F, 1.0F);
        }
        return Mth.clamp(1.0F - (dayTime - 1200L) / 2000.0F, 0.0F, 1.0F);
    }

    private static float nearbyWaterHumidity(ClientLevel level, BlockPos center) {
        int wetSamples = 0;
        int samples = 0;
        BlockPos.MutableBlockPos sample = new BlockPos.MutableBlockPos();
        for (int dx = -12; dx <= 12; dx += 4) {
            for (int dz = -12; dz <= 12; dz += 4) {
                for (int dy = -3; dy <= 2; dy += 1) {
                    sample.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!level.isLoaded(sample)) {
                        continue;
                    }
                    samples++;
                    if (level.getBlockState(sample).getFluidState().is(FluidTags.WATER)) {
                        wetSamples += 3;
                    }
                }
            }
        }
        if (samples <= 0) {
            return 0.0F;
        }
        return Mth.clamp(wetSamples / (float) samples, 0.0F, 1.0F);
    }
}
