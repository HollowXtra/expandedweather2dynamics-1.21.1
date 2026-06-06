package com.imbet;

import com.imbet.client.AnemometerRenderer;
import com.imbet.client.ClientWeatherEffects;
import com.imbet.client.CloudShadowShaderRenderer;
import com.imbet.client.DustDevilRenderer;
import com.imbet.client.FoliageWindController;
import com.imbet.client.ForecastIconRenderer;
import com.imbet.client.MorningFogClient;
import com.imbet.client.RadarScreen;
import com.imbet.client.RadarPanelRenderer;
import com.imbet.client.RadarShader;
import com.imbet.client.SpcRiskPanelRenderer;
import com.imbet.client.TornadoFearClient;
import com.imbet.client.VolumetricPrecipitationRenderer;
import com.imbet.client.XaeroWeatherOverlayRenderer;
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
        MinecraftForge.EVENT_BUS.addListener(FoliageWindController::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(FoliageWindController::onRenderLevelStage);
        MinecraftForge.EVENT_BUS.addListener(TornadoFearClient::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(TornadoFearClient::onCameraAngles);
        MinecraftForge.EVENT_BUS.addListener(TornadoFearClient::onFov);
        MinecraftForge.EVENT_BUS.addListener(TornadoFearClient::onFogColor);
        MinecraftForge.EVENT_BUS.addListener(TornadoFearClient::onRenderFog);
        MinecraftForge.EVENT_BUS.addListener(MorningFogClient::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(MorningFogClient::onFogColor);
        MinecraftForge.EVENT_BUS.addListener(MorningFogClient::onRenderFog);
        MinecraftForge.EVENT_BUS.addListener(VolumetricPrecipitationRenderer::onRenderLevelStage);
        MinecraftForge.EVENT_BUS.addListener(CloudShadowShaderRenderer::onRenderLevelStage);
        MinecraftForge.EVENT_BUS.addListener(ForecastIconRenderer::onRenderLevelStage);
        MinecraftForge.EVENT_BUS.addListener(XaeroWeatherOverlayRenderer::onScreenRenderPost);
        MinecraftForge.EVENT_BUS.addListener(XaeroWeatherOverlayRenderer::onHudRenderPost);
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
        event.registerEntityRenderer(ModContent.DUST_DEVIL_ENTITY.get(), DustDevilRenderer::new);
        event.registerEntityRenderer(ModContent.LANDSPOUT_ENTITY.get(), DustDevilRenderer::new);
        event.registerEntityRenderer(ModContent.WATERSPOUT_ENTITY.get(), DustDevilRenderer::new);
        event.registerEntityRenderer(ModContent.GUSTNADO_ENTITY.get(), DustDevilRenderer::new);
    }

    static void registerShaders(RegisterShadersEvent event) {
        RadarShader.register(event);
    }
}
