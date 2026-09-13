package com.imbet;

import com.imbet.client.AlertBannerOverlay;
import com.imbet.client.AnemometerRenderer;
import com.imbet.client.BarometerPanelRenderer;
import com.imbet.client.RainGaugePanelRenderer;
import com.imbet.client.TornadoProbePanelRenderer;
import com.imbet.client.WeatherRadioPanelRenderer;
import com.imbet.client.WeatherVaneRenderer;
import com.imbet.client.config.EwdConfigScreen;
import com.imbet.client.ClientWeatherEffects;
import com.imbet.client.DustDevilRenderer;
import com.imbet.client.FoliageWindController;
import com.imbet.client.ForecastIconRenderer;
import com.imbet.client.LodFogCompat;
import com.imbet.client.MorningFogClient;
import com.imbet.client.RadarScreen;
import com.imbet.client.RadarPanelRenderer;
import com.imbet.client.RadarShader;
import com.imbet.client.SpcRiskPanelRenderer;
import com.imbet.client.TornadoFearClient;
import com.imbet.client.VolumetricPrecipitationRenderer;
import com.imbet.client.WeatherBalloonRenderer;
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
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

import java.io.IOException;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = ExpandedWeather2Dynamics.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = ExpandedWeather2Dynamics.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class ExpandedWeather2DynamicsClient {
    public ExpandedWeather2DynamicsClient(ModContainer container) {
        // Custom spec-driven config screen, opened from the Mods screen > this mod > Config.
        container.registerExtensionPoint(IConfigScreenFactory.class, (modContainer, parent) -> new EwdConfigScreen(parent));
        NeoForge.EVENT_BUS.addListener(ClientWeatherEffects::onClientTick);
        NeoForge.EVENT_BUS.addListener(FoliageWindController::onClientTick);
        NeoForge.EVENT_BUS.addListener(FoliageWindController::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(TornadoFearClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(TornadoFearClient::onCameraAngles);
        NeoForge.EVENT_BUS.addListener(TornadoFearClient::onFov);
        NeoForge.EVENT_BUS.addListener(TornadoFearClient::onFogColor);
        // Registered ahead of the two weather fog handlers, and it also stands down whenever either
        // of them is active, so the LOD fog extension can never stretch a fog they deliberately
        // pulled in. The explicit stand-down is what actually guarantees that; the order is belt and
        // braces.
        NeoForge.EVENT_BUS.addListener(LodFogCompat::onRenderFog);
        NeoForge.EVENT_BUS.addListener(TornadoFearClient::onRenderFog);
        NeoForge.EVENT_BUS.addListener(MorningFogClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(MorningFogClient::onFogColor);
        NeoForge.EVENT_BUS.addListener(MorningFogClient::onRenderFog);
        NeoForge.EVENT_BUS.addListener(VolumetricPrecipitationRenderer::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(ForecastIconRenderer::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(XaeroWeatherOverlayRenderer::onScreenRenderPost);
        NeoForge.EVENT_BUS.addListener(XaeroWeatherOverlayRenderer::onHudRenderPost);
        NeoForge.EVENT_BUS.addListener(AlertBannerOverlay::onHudRenderPost);
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
        event.registerBlockEntityRenderer(ModContent.WEATHER_RADIO_BLOCK_ENTITY.get(), WeatherRadioPanelRenderer::new);
        event.registerBlockEntityRenderer(ModContent.WEATHER_VANE_BLOCK_ENTITY.get(), WeatherVaneRenderer::new);
        event.registerBlockEntityRenderer(ModContent.BAROMETER_BLOCK_ENTITY.get(), BarometerPanelRenderer::new);
        event.registerBlockEntityRenderer(ModContent.RAIN_GAUGE_BLOCK_ENTITY.get(), RainGaugePanelRenderer::new);
        event.registerBlockEntityRenderer(ModContent.TORNADO_PROBE_BLOCK_ENTITY.get(), TornadoProbePanelRenderer::new);
        event.registerEntityRenderer(ModContent.DUST_DEVIL_ENTITY.get(), DustDevilRenderer::new);
        event.registerEntityRenderer(ModContent.LANDSPOUT_ENTITY.get(), DustDevilRenderer::new);
        event.registerEntityRenderer(ModContent.WATERSPOUT_ENTITY.get(), DustDevilRenderer::new);
        event.registerEntityRenderer(ModContent.GUSTNADO_ENTITY.get(), DustDevilRenderer::new);
        event.registerEntityRenderer(ModContent.WEATHER_BALLOON_ENTITY.get(), WeatherBalloonRenderer::new);
    }

    @SubscribeEvent
    static void registerShaders(RegisterShadersEvent event) throws IOException {
        RadarShader.register(event);
    }
}
