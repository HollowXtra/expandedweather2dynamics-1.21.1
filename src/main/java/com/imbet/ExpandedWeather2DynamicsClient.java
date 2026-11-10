package com.imbet;

import com.imbet.client.AlertBannerOverlay;
import com.imbet.client.PhotoViewfinder;
import com.imbet.client.AnemometerRenderer;
import com.imbet.client.BarometerPanelRenderer;
import com.imbet.client.RainGaugePanelRenderer;
import com.imbet.client.TornadoProbePanelRenderer;
import com.imbet.client.WeatherRadioPanelRenderer;
import com.imbet.client.WeatherVaneRenderer;
import com.imbet.client.TornadoSirenRenderer;
import com.imbet.client.WeatherDroneClient;
import com.imbet.client.WeatherDroneRenderer;
import com.imbet.client.TornadoSirenSound;
import com.imbet.client.VanillaRainMode;
import com.imbet.client.MachineItemRenderer;
import com.imbet.client.RadarScopeShader;
import com.imbet.client.SoftPuffs;
import com.imbet.client.WeatherAlertSirenRenderer;
import com.imbet.client.WeatherComputerScreen;
import com.imbet.client.config.EwdConfigScreen;
import com.imbet.client.ClientWeatherEffects;
import com.imbet.client.StormSkyClient;
import com.imbet.client.DebrisEntityRenderer;
import com.imbet.client.DustDevilRenderer;
import com.imbet.client.FoliageWindController;
import com.imbet.client.ForecastIconRenderer;
import com.imbet.client.InstrumentPanelShader;
import com.imbet.client.LightningBoltShader;
import com.imbet.client.LightningRevamp;
import com.imbet.client.LodFogCompat;
import com.imbet.client.MistField;
import com.imbet.client.MistRenderer;
import com.imbet.client.MorningFogClient;
import com.imbet.client.OceanWaveField;
import com.imbet.client.OceanWaveRenderer;
import com.imbet.client.PhotoAlbumScreen;
import com.imbet.client.RadarScreen;
import com.imbet.client.RainVolumeShader;
import com.imbet.client.RenderingCompat;
import com.imbet.client.RadarPanelRenderer;
import com.imbet.client.RadarShader;
import com.imbet.client.SpcRiskPanelRenderer;
import com.imbet.client.WeatherComputerScreenRenderer;
import com.imbet.client.TornadoFearClient;
import com.imbet.client.TornadoInflowShader;
import com.imbet.client.CelestialRenderer;
import com.imbet.client.TornadoWhiteoutShader;
import com.imbet.client.VolcanoClientEffects;
import com.imbet.client.VolcanoPlumeRenderer;
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
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
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
        // The sky's own bodies: the round sun and moon, the Milky Way and an eclipse. Drawn at AFTER_SKY,
        // where vanilla has just finished its own sky and its own (hidden) sun and moon quads.
        NeoForge.EVENT_BUS.addListener(CelestialRenderer::onRenderLevelStage);
        // The ocean reads the same wind the foliage does, through the same sampler; it is drawn after the
        // translucent chunk batch so its quads land on top of the water they displace.
        NeoForge.EVENT_BUS.addListener(OceanWaveField::onClientTick);
        NeoForge.EVENT_BUS.addListener(OceanWaveRenderer::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(TornadoFearClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(TornadoFearClient::onCameraAngles);
        NeoForge.EVENT_BUS.addListener(TornadoFearClient::onFov);
        // The dust veil the whiteout is painted with: one full-screen pass over the weather, standing in
        // for the wall of grit the player is inside. It reads the reading TornadoFearClient already
        // publishes, so the veil and the fog it collapses to always agree. It was written and never
        // subscribed - the program, the shaders and the whole whiteout reading existed with nothing
        // drawing them - which is why being engulfed showed the fog but no veil at all.
        NeoForge.EVENT_BUS.addListener(TornadoInflowShader::onRenderLevelStage);
        // Registered ahead of every other fog handler on purpose: this is the sky a player is standing under, and
        // the tornado's whiteout and the two weather fogs all mix from whatever the event already carries - so the
        // storm's own shade goes on first and anything that means to override it gets the last word.
        // The eclipse's darkening is part of that same reading now rather than a fog pass of its own, so a
        // total eclipse dims the sky, the deck, the fog and the world's light together - and, because it is
        // a reading the game itself consumes, it does so under a shader pack too.
        NeoForge.EVENT_BUS.addListener(StormSkyClient::onFogColor);
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
        // The mist is one field with two faces: the wisps drawn over the ground and the haze they sit in.
        // Both read the same reading, and the haze composes with the morning fog by taking the nearer far
        // plane and stands down entirely for the tornado's own fog, so the two can never fight each other.
        NeoForge.EVENT_BUS.addListener(MistField::onClientTick);
        NeoForge.EVENT_BUS.addListener(MistField::onFogColor);
        NeoForge.EVENT_BUS.addListener(MistField::onRenderFog);
        NeoForge.EVENT_BUS.addListener(MistRenderer::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(VolumetricPrecipitationRenderer::onRenderLevelStage);
        // The same rain, drawn as a volume by a program instead of as billboards by particles: slices cut
        // through the storm's precipitation field, so a curtain of rain between you and a tornado actually
        // hides the tornado, and flying into one has parallax. It draws at the same stage as the sheets and
        // the sheets stand down for it (see VolumetricPrecipitationRenderer), so only one of the two ever
        // draws - and it stands down in turn for an Iris pack, which is better at this than we are.
        NeoForge.EVENT_BUS.addListener(RainVolumeShader::onRenderLevelStage);
        // A volcano draws its own column from anywhere in the world, hazes the distance with the ash it has in
        // the air, shakes the camera with its tremor, and rains its ash on whoever is standing in the fallout.
        // It is not a storm and shares none of the storm path: the only thing it reads is the state the
        // server sends it, so the plume always matches the ash actually being laid down.
        NeoForge.EVENT_BUS.addListener(VolcanoClientEffects::onClientTick);
        NeoForge.EVENT_BUS.addListener(VolcanoClientEffects::onCameraAngles);
        NeoForge.EVENT_BUS.addListener(VolcanoClientEffects::onFogColor);
        NeoForge.EVENT_BUS.addListener(VolcanoClientEffects::onRenderFog);
        NeoForge.EVENT_BUS.addListener(VolcanoPlumeRenderer::onRenderLevelStage);
        // Holds Weather2's own vanilla-precipitation flag open while vanilla's rain is the rain, and puts
        // the user's own value back when it is not.
        NeoForge.EVENT_BUS.addListener(VanillaRainMode::onClientTick);
        NeoForge.EVENT_BUS.addListener(ForecastIconRenderer::onRenderLevelStage);
        // Lightning is drawn here rather than during the entity pass so the channel lands over the rain,
        // the cloud deck and the debris it lights up, which is the only ordering an additive light
        // source may have. The two entity renderers it replaces are stood down by their own mixins.
        NeoForge.EVENT_BUS.addListener(LightningRevamp::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(XaeroWeatherOverlayRenderer::onScreenRenderPost);
        NeoForge.EVENT_BUS.addListener(XaeroWeatherOverlayRenderer::onHudRenderPost);
        NeoForge.EVENT_BUS.addListener(AlertBannerOverlay::onHudRenderPost);
        // The camera's viewfinder, which is a HUD layer rather than a screen: it is drawn while the player is
        // looking through a camera and it never takes the pointer.
        NeoForge.EVENT_BUS.addListener(PhotoViewfinder::onHudRenderPost);
        // Flying a drone takes over the camera and the movement keys, and it hands them back the moment the
        // link drops or the pilot switches items.
        NeoForge.EVENT_BUS.addListener(WeatherDroneClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(WeatherDroneClient::onMovementInput);
        NeoForge.EVENT_BUS.addListener(WeatherDroneClient::onCameraAngles);
        NeoForge.EVENT_BUS.addListener(WeatherDroneClient::onRenderGui);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // The one place a siren's tone can be started: a block entity decides to sound in common code, and
        // this installs the client-side sink that turns that decision into a looping positioned sound.
        TornadoSirenSound.install();
        if (Config.LOG_STARTUP.getAsBoolean()) {
            ExpandedWeather2Dynamics.LOGGER.info("Expanded Weather2 Dynamics client setup complete.");
        }
        // Sodium meshes chunks with its own shader programs, so the foliage wind's override of the vanilla
        // terrain shaders cannot reach a chunk drawn by it (pack.mcmeta declares those same files to
        // Sodium as deliberately ignored, which is why the mod's pack no longer trips its scanner).
        // The sway is stood down rather than half-applied, and that is worth one line: otherwise the
        // feature simply looks broken and the reason is invisible.
        if (RenderingCompat.isSodiumLoaded()) {
            ExpandedWeather2Dynamics.LOGGER.info("Sodium detected: chunk terrain uses Sodium's own shader programs, so foliage wind sway is inactive for this session.");
        }
    }

    @SubscribeEvent
    static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModContent.RADAR_MENU.get(), RadarScreen::new);
        event.register(ModContent.PHOTO_ALBUM_MENU.get(), PhotoAlbumScreen::new);
        event.register(ModContent.WEATHER_COMPUTER_MENU.get(), WeatherComputerScreen::new);
    }

    @SubscribeEvent
    static void registerParticles(net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(com.imbet.registry.ModParticles.WILDFIRE_FLAME.get(), com.imbet.client.WildfireFlameParticle.Provider::new);
    }

    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModContent.RADAR_BLOCK_ENTITY.get(), RadarPanelRenderer::new);
        event.registerBlockEntityRenderer(ModContent.ANEMOMETER_BLOCK_ENTITY.get(), AnemometerRenderer::new);
        event.registerBlockEntityRenderer(ModContent.SPC_RISK_BLOCK_ENTITY.get(), SpcRiskPanelRenderer::new);
        event.registerBlockEntityRenderer(ModContent.WEATHER_COMPUTER_BLOCK_ENTITY.get(), WeatherComputerScreenRenderer::new);
        event.registerBlockEntityRenderer(ModContent.WEATHER_RADIO_BLOCK_ENTITY.get(), WeatherRadioPanelRenderer::new);
        event.registerBlockEntityRenderer(ModContent.WEATHER_VANE_BLOCK_ENTITY.get(), WeatherVaneRenderer::new);
        event.registerBlockEntityRenderer(ModContent.BAROMETER_BLOCK_ENTITY.get(), BarometerPanelRenderer::new);
        event.registerBlockEntityRenderer(ModContent.RAIN_GAUGE_BLOCK_ENTITY.get(), RainGaugePanelRenderer::new);
        event.registerBlockEntityRenderer(ModContent.TORNADO_PROBE_BLOCK_ENTITY.get(), TornadoProbePanelRenderer::new);
        event.registerBlockEntityRenderer(ModContent.WEATHER_ALERT_SIREN_BLOCK_ENTITY.get(), WeatherAlertSirenRenderer::new);
        event.registerBlockEntityRenderer(ModContent.TORNADO_SIREN_BLOCK_ENTITY.get(), TornadoSirenRenderer::new);
        event.registerEntityRenderer(ModContent.DUST_DEVIL_ENTITY.get(), DustDevilRenderer::new);
        event.registerEntityRenderer(ModContent.LANDSPOUT_ENTITY.get(), DustDevilRenderer::new);
        event.registerEntityRenderer(ModContent.WATERSPOUT_ENTITY.get(), DustDevilRenderer::new);
        event.registerEntityRenderer(ModContent.GUSTNADO_ENTITY.get(), DustDevilRenderer::new);
        event.registerEntityRenderer(ModContent.FUNNEL_CLOUD_ENTITY.get(), DustDevilRenderer::new);
        event.registerEntityRenderer(ModContent.DEBRIS_ENTITY.get(), DebrisEntityRenderer::new);
        event.registerEntityRenderer(ModContent.SNOW_DEVIL_ENTITY.get(), DustDevilRenderer::new);
        // The volcanic pair go through the same vortex renderer as every other whirl, which is what gives
        // them a volumetric column; without a renderer of their own they would be server-side entities that
        // nothing ever draws.
        event.registerEntityRenderer(ModContent.LAVA_WHIRL_ENTITY.get(), DustDevilRenderer::new);
        event.registerEntityRenderer(ModContent.STEAM_DEVIL_ENTITY.get(), DustDevilRenderer::new);
        event.registerEntityRenderer(ModContent.WEATHER_BALLOON_ENTITY.get(), WeatherBalloonRenderer::new);
        event.registerEntityRenderer(ModContent.WEATHER_DRONE_ENTITY.get(), WeatherDroneRenderer::new);
    }

    @SubscribeEvent
    static void registerClientExtensions(RegisterClientExtensionsEvent event) {
        event.registerItem(MachineItemRenderer.EXTENSIONS,
                ModContent.MECHANICAL_SIREN_BLOCK_ITEM.get(),
                ModContent.ELECTRONIC_SIREN_BLOCK_ITEM.get(),
                ModContent.HILO_SIREN_BLOCK_ITEM.get());
    }

    /**
     * Builds this mod's own programs, each on its own.
     *
     * <p>A program one graphics driver will not compile turns its own effect off instead of stopping the game.
     * An exception thrown out of this event fails the mod's loading, and the game then drops its resource
     * packs and tries again, so on that driver it can never start: an AMD driver refusing one line of the
     * tornado whiteout's noise was enough to keep players out entirely. Every user of these programs already
     * checks for one that is missing - the sun and moon fall back to the game's own, the panels to a flat
     * plate, the veils and volumes simply are not drawn - so a failure is logged once and the rest load.
     */
    @SubscribeEvent
    static void registerShaders(RegisterShadersEvent event) {
        registerShader(event, "radar_panel", RadarShader::register);
        registerShader(event, "soft_puff", SoftPuffs::register);
        registerShader(event, "radar_scope", RadarScopeShader::register);
        registerShader(event, "instrument_panel", InstrumentPanelShader::register);
        registerShader(event, "lightning_bolt", LightningBoltShader::register);
        registerShader(event, "rain_volume", RainVolumeShader::register);
        registerShader(event, "tornado_inflow", TornadoInflowShader::register);
        registerShader(event, "tornado_whiteout", TornadoWhiteoutShader::register);
        registerShader(event, "celestial", CelestialRenderer::register);
    }

    @FunctionalInterface
    private interface ShaderRegistration {
        void register(RegisterShadersEvent event) throws IOException;
    }

    private static void registerShader(RegisterShadersEvent event, String name, ShaderRegistration registration) {
        try {
            registration.register(event);
        } catch (Exception e) {
            ExpandedWeather2Dynamics.LOGGER.error("Expanded Weather2 Dynamics: the {} shader did not compile on this"
                    + " graphics driver, so that effect is turned off. Please report this with your log.", name, e);
        }
    }
}
