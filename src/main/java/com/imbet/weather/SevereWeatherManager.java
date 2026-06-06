package com.imbet.weather;

import net.minecraft.server.level.ServerLevel;

public final class SevereWeatherManager {
    private SevereWeatherManager() {
    }

    public static void tick(ServerLevel level) {
        if (level == null || level.isClientSide()) {
            return;
        }
        DailySpcOutlookService.tick(level);
        ConvectivePhenomenaService.tick(level);
        FloodingService.tick(level);
        WeatherDataStreamService.tick(level);
    }
}
