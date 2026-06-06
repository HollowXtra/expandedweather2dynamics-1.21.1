package com.imbet.weather;

import net.minecraft.server.level.ServerLevel;

public final class ConvectivePhenomenaService {
    private ConvectivePhenomenaService() {
    }

    public static void tick(ServerLevel level) {
        AddonWeatherEventService.tick(level);
    }
}
