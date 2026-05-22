package com.imbet;

import com.imbet.client.AnemometerRenderer;
import com.imbet.client.ClientWeatherEffects;
import com.imbet.client.RadarScreen;
import com.imbet.client.RadarPanelRenderer;
import com.imbet.client.RadarShader;
import com.imbet.client.SpcRiskPanelRenderer;
import com.imbet.client.TornadoFearClient;
import com.imbet.client.TornadoInflowShader;
import com.imbet.registry.ModContent;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.common.MinecraftForge;

import java.io.IOException;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
public class ExpandedWeather2DynamicsClient {
    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(ExpandedWeather2DynamicsClient::onClientSetup);
        modEventBus.addListener(ExpandedWeather2DynamicsClient::registerRenderers);
        modEventBus.addListener(ExpandedWeather2DynamicsClient::registerShaders);
        MinecraftForge.EVENT_BUS.addListener(ClientWeatherEffects::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(TornadoFearClient::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(TornadoFearClient::onCameraAngles);
        MinecraftForge.EVENT_BUS.addListener(TornadoFearClient::onFov);
        MinecraftForge.EVENT_BUS.addListener(TornadoFearClient::onFogColor);
        MinecraftForge.EVENT_BUS.addListener(TornadoFearClient::onRenderFog);
        MinecraftForge.EVENT_BUS.addListener(TornadoInflowShader::onRenderLevelStage);
    }

    static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(ModContent.RADAR_MENU.get(), RadarScreen::new));
        if (Config.LOG_STARTUP.get()) {
            ExpandedWeather2Dynamics.LOGGER.info("Expanded Weather2 Dynamics client setup complete.");
        }
    }

    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModContent.RADAR_BLOCK_ENTITY.get(), RadarPanelRenderer::new);
        event.registerBlockEntityRenderer(ModContent.ANEMOMETER_BLOCK_ENTITY.get(), AnemometerRenderer::new);
        event.registerBlockEntityRenderer(ModContent.SPC_RISK_BLOCK_ENTITY.get(), SpcRiskPanelRenderer::new);
    }

    static void registerShaders(RegisterShadersEvent event) {
        RadarShader.register(event);
        TornadoInflowShader.register(event);
    }
}
