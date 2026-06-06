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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

import java.io.IOException;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = ExpandedWeather2Dynamics.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = ExpandedWeather2Dynamics.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class ExpandedWeather2DynamicsClient {
    public ExpandedWeather2DynamicsClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        NeoForge.EVENT_BUS.addListener(ClientWeatherEffects::onClientTick);
        NeoForge.EVENT_BUS.addListener(FoliageWindController::onClientTick);
        NeoForge.EVENT_BUS.addListener(FoliageWindController::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(TornadoFearClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(TornadoFearClient::onCameraAngles);
        NeoForge.EVENT_BUS.addListener(TornadoFearClient::onFov);
        NeoForge.EVENT_BUS.addListener(TornadoFearClient::onFogColor);
        NeoForge.EVENT_BUS.addListener(TornadoFearClient::onRenderFog);
        NeoForge.EVENT_BUS.addListener(MorningFogClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(MorningFogClient::onFogColor);
        NeoForge.EVENT_BUS.addListener(MorningFogClient::onRenderFog);
        NeoForge.EVENT_BUS.addListener(VolumetricPrecipitationRenderer::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(CloudShadowShaderRenderer::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(ForecastIconRenderer::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(XaeroWeatherOverlayRenderer::onScreenRenderPost);
        NeoForge.EVENT_BUS.addListener(XaeroWeatherOverlayRenderer::onHudRenderPost);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        if (Config.LOG_STARTUP.getAsBoolean()) {
            ExpandedWeather2Dynamics.LOGGER.info("Expanded Weather2 Dynamics client setup complete.");
        }
    }

    @SubscribeEvent
    static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModContent.RADAR_MENU.get(), RadarScreen::new);
    }

    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModContent.RADAR_BLOCK_ENTITY.get(), RadarPanelRenderer::new);
        event.registerBlockEntityRenderer(ModContent.ANEMOMETER_BLOCK_ENTITY.get(), AnemometerRenderer::new);
        event.registerBlockEntityRenderer(ModContent.SPC_RISK_BLOCK_ENTITY.get(), SpcRiskPanelRenderer::new);
        event.registerEntityRenderer(ModContent.DUST_DEVIL_ENTITY.get(), DustDevilRenderer::new);
        event.registerEntityRenderer(ModContent.LANDSPOUT_ENTITY.get(), DustDevilRenderer::new);
        event.registerEntityRenderer(ModContent.WATERSPOUT_ENTITY.get(), DustDevilRenderer::new);
        event.registerEntityRenderer(ModContent.GUSTNADO_ENTITY.get(), DustDevilRenderer::new);
    }

    @SubscribeEvent
    static void registerShaders(RegisterShadersEvent event) throws IOException {
        RadarShader.register(event);
    }
}
