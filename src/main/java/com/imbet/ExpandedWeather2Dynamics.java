package com.imbet;

import com.mojang.logging.LogUtils;
import com.imbet.command.EwdCommand;
import com.imbet.network.ModNetworking;
import com.imbet.registry.ModContent;
import com.imbet.weather.WeatherRuntimeEvents;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(ExpandedWeather2Dynamics.MODID)
public final class ExpandedWeather2Dynamics {
    public static final String MODID = "expandedweather2dynamics";
    public static final String WEATHER2_MODID = "weather2";
    public static final String COROUTIL_MODID = "coroutil";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ExpandedWeather2Dynamics(IEventBus modEventBus, ModContainer modContainer) {
        ModContent.register(modEventBus);
        modEventBus.addListener(ModNetworking::register);
        modEventBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.addListener(WeatherRuntimeEvents::onLevelTick);
        NeoForge.EVENT_BUS.addListener(WeatherRuntimeEvents::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(WeatherRuntimeEvents::onBlockPlaced);
        NeoForge.EVENT_BUS.addListener(WeatherRuntimeEvents::onEntityJoinLevel);
        NeoForge.EVENT_BUS.addListener(com.imbet.weather.TornadoPlayerCapture::onFall);
        NeoForge.EVENT_BUS.addListener(EwdCommand::register);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        if (!Config.LOG_STARTUP.getAsBoolean()) {
            return;
        }

        LOGGER.info(
                "Expanded Weather2 Dynamics loaded. Weather2 present: {}, CoroUtil present: {}",
                ModList.get().isLoaded(WEATHER2_MODID),
                ModList.get().isLoaded(COROUTIL_MODID)
        );
    }
}
