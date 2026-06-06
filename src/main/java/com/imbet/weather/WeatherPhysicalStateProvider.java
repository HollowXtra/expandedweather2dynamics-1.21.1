package com.imbet.weather;

import net.minecraft.server.level.ServerLevel;

public interface WeatherPhysicalStateProvider {
    WeatherPhysicalSnapshot snapshot(ServerLevel level);
}
