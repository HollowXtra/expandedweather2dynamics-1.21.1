package com.imbet;

import com.mojang.logging.LogUtils;
import com.imbet.command.EwdCommand;
import com.imbet.network.ModNetworking;
import com.imbet.registry.ModContent;
import com.imbet.weather.WeatherRuntimeEvents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.common.MinecraftForge;
import org.slf4j.Logger;

@Mod(ExpandedWeather2Dynamics.MODID)
public final class ExpandedWeather2Dynamics {
    public static final String MODID = "expandedweather2dynamics";
    public static final String WEATHER2_MODID = "weather2";
    public static final String COROUTIL_MODID = "coroutil";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ExpandedWeather2Dynamics() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModContent.register(modEventBus);
        ModNetworking.register();
        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.addListener(WeatherRuntimeEvents::onLevelTick);
        MinecraftForge.EVENT_BUS.addListener(WeatherRuntimeEvents::onBlockPlaced);
        MinecraftForge.EVENT_BUS.addListener(WeatherRuntimeEvents::onEntityJoinLevel);
        MinecraftForge.EVENT_BUS.addListener(EwdCommand::register);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ExpandedWeather2DynamicsClient.init(modEventBus));
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        if (!Config.LOG_STARTUP.get()) {
            return;
        }

        LOGGER.info(
                "Expanded Weather2 Dynamics loaded. Weather2 present: {}, CoroUtil present: {}",
                ModList.get().isLoaded(WEATHER2_MODID),
                ModList.get().isLoaded(COROUTIL_MODID)
        );
    }
}
