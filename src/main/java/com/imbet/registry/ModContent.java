package com.imbet.registry;

import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.alert.SirenControlPanelBlock;
import com.imbet.alert.SirenWireBlock;
import com.imbet.alert.WeatherAlertSirenBlock;
import com.imbet.balloon.WeatherBalloonItem;
import com.imbet.alert.WeatherAlertSirenBlockEntity;
import com.imbet.anemometer.AnemometerBlock;
import com.imbet.anemometer.AnemometerBlockEntity;
import com.imbet.defence.ReinforcedGlassBlock;
import com.imbet.defence.StructuralRebarBlock;
import com.imbet.instruments.BarometerBlock;
import com.imbet.instruments.BarometerBlockEntity;
import com.imbet.instruments.HandheldInstrument;
import com.imbet.instruments.HandheldInstrumentItem;
import com.imbet.instruments.RainGaugeBlock;
import com.imbet.instruments.RainGaugeBlockEntity;
import com.imbet.instruments.TornadoProbeBlock;
import com.imbet.instruments.TornadoProbeBlockEntity;
import com.imbet.instruments.TornadoProbeItem;
import com.imbet.instruments.WeatherRadioBlock;
import com.imbet.instruments.WeatherRadioBlockEntity;
import com.imbet.instruments.WeatherVaneBlock;
import com.imbet.instruments.WeatherVaneBlockEntity;
import com.imbet.photo.CameraItem;
import com.imbet.photo.PhotoAlbumMenu;
import com.imbet.radar.RadarBlock;
import com.imbet.spotter.SpotterFlareItem;
import com.imbet.radar.RadarBlockEntity;
import com.imbet.radar.RadarMenu;
import com.imbet.radar.PortableRadarItem;
import com.imbet.spc.SpcRiskBlock;
import com.imbet.spc.SpcRiskBlockEntity;
import com.imbet.spc.WeatherComputerBlock;
import com.imbet.spc.WeatherComputerBlockEntity;
import com.imbet.spc.WeatherComputerMenu;
import com.imbet.survey.DamageSurveyorItem;
import com.imbet.village.BuildersBenchBlock;
import com.imbet.village.SawmillBlock;
import com.imbet.village.SurveyStationBlock;
import com.imbet.village.WeatherStationBlock;
import com.imbet.weather.DebrisEntity;
import com.imbet.weather.DustDevilEntity;
import com.imbet.weather.FunnelCloudEntity;
import com.imbet.weather.GustnadoEntity;
import com.imbet.weather.LandspoutEntity;
import com.imbet.weather.LavaWhirlEntity;
import com.imbet.weather.SnowDevilEntity;
import com.imbet.weather.SteamDevilEntity;
import com.imbet.weather.WaterspoutEntity;
import com.imbet.alert.SirenTone;
import com.imbet.drone.WeatherDroneEntity;
import com.imbet.drone.WeatherDroneItem;
import com.imbet.instruments.ProbeUpgradeItem;
import com.imbet.alert.TornadoSirenBlock;
import com.imbet.alert.TornadoSirenBlockEntity;
import com.imbet.weather.WeatherBalloonEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModContent {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(BuiltInRegistries.BLOCK, ExpandedWeather2Dynamics.MODID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(BuiltInRegistries.ITEM, ExpandedWeather2Dynamics.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, ExpandedWeather2Dynamics.MODID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(BuiltInRegistries.MENU, ExpandedWeather2Dynamics.MODID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, ExpandedWeather2Dynamics.MODID);
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, ExpandedWeather2Dynamics.MODID);

    public static final DeferredHolder<Block, RadarBlock> RADAR_BLOCK = BLOCKS.register("radar_block", () ->
            new RadarBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                    .mapColor(MapColor.STONE)
                    .strength(1.5F, 6.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> RADAR_BLOCK_ITEM = ITEMS.register("radar_block", () ->
            new BlockItem(RADAR_BLOCK.get(), new Item.Properties()));

    public static final DeferredHolder<Item, PortableRadarItem> PORTABLE_RADAR_ITEM = ITEMS.register("portable_radar", () ->
            new PortableRadarItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RadarBlockEntity>> RADAR_BLOCK_ENTITY = BLOCK_ENTITIES.register("radar_block", () ->
            BlockEntityType.Builder.of(RadarBlockEntity::new, RADAR_BLOCK.get()).build(null));

    public static final DeferredHolder<MenuType<?>, MenuType<RadarMenu>> RADAR_MENU = MENUS.register("radar_menu", () ->
            IMenuTypeExtension.create(RadarMenu::new));

    /**
     * What a wildfire leaves standing. A trunk that burned where it stood keeps its own axis, so the scar
     * keeps the shape of the tree that made it, and the block is deliberately not flammable: burnt wood does
     * not catch twice, and a scar that re-ignites would erase its own evidence.
     */
    public static final DeferredHolder<Block, RotatedPillarBlock> CHARRED_LOG = BLOCKS.register("charred_log", () ->
            new RotatedPillarBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(1.4F, 3.0F)
                    .sound(SoundType.WOOD)));

    public static final DeferredHolder<Item, BlockItem> CHARRED_LOG_ITEM = ITEMS.register("charred_log", () ->
            new BlockItem(CHARRED_LOG.get(), new Item.Properties()));

    // The album screen's menu. It carries a snapshot of the player's own album rather than a container, which
    // is why it has no slots and is only ever opened by the camera in the opener's hand.
    public static final DeferredHolder<MenuType<?>, MenuType<PhotoAlbumMenu>> PHOTO_ALBUM_MENU = MENUS.register("photo_album_menu", () ->
            IMenuTypeExtension.create(PhotoAlbumMenu::new));

    public static final DeferredHolder<Block, AnemometerBlock> ANEMOMETER_BLOCK = BLOCKS.register("anemometer", () ->
            new AnemometerBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BARS)
                    .mapColor(MapColor.METAL)
                    .strength(0.8F, 4.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> ANEMOMETER_BLOCK_ITEM = ITEMS.register("anemometer", () ->
            new BlockItem(ANEMOMETER_BLOCK.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AnemometerBlockEntity>> ANEMOMETER_BLOCK_ENTITY = BLOCK_ENTITIES.register("anemometer", () ->
            BlockEntityType.Builder.of(AnemometerBlockEntity::new, ANEMOMETER_BLOCK.get()).build(null));

    public static final DeferredHolder<Block, WeatherAlertSirenBlock> WEATHER_ALERT_SIREN_BLOCK = BLOCKS.register("weather_alert_siren", () ->
            new WeatherAlertSirenBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.COPPER_BLOCK)
                    .mapColor(MapColor.COLOR_RED)
                    .strength(1.2F, 5.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> WEATHER_ALERT_SIREN_BLOCK_ITEM = ITEMS.register("weather_alert_siren", () ->
            new BlockItem(WEATHER_ALERT_SIREN_BLOCK.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WeatherAlertSirenBlockEntity>> WEATHER_ALERT_SIREN_BLOCK_ENTITY = BLOCK_ENTITIES.register("weather_alert_siren", () ->
            BlockEntityType.Builder.of(WeatherAlertSirenBlockEntity::new, WEATHER_ALERT_SIREN_BLOCK.get()).build(null));

    // The wiring a siren is armed through. The cable is deliberately cheap and the panel is the only
    // block in the pair that does anything: a run is meant to be run anywhere.
    public static final DeferredHolder<Block, SirenWireBlock> SIREN_WIRE_BLOCK = BLOCKS.register("siren_wire", () ->
            new SirenWireBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.COPPER_BLOCK)
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(0.4F, 3.0F)
                    .sound(SoundType.COPPER)
                    .noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> SIREN_WIRE_BLOCK_ITEM = ITEMS.register("siren_wire", () ->
            new BlockItem(SIREN_WIRE_BLOCK.get(), new Item.Properties()));

    public static final DeferredHolder<Block, SirenControlPanelBlock> SIREN_CONTROL_PANEL_BLOCK = BLOCKS.register("siren_control_panel", () ->
            new SirenControlPanelBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                    .mapColor(MapColor.METAL)
                    .strength(1.2F, 5.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> SIREN_CONTROL_PANEL_BLOCK_ITEM = ITEMS.register("siren_control_panel", () ->
            new BlockItem(SIREN_CONTROL_PANEL_BLOCK.get(), new Item.Properties()));

    public static final DeferredHolder<Block, SpcRiskBlock> SPC_RISK_BLOCK = BLOCKS.register("spc_risk_block", () ->
            new SpcRiskBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.COPPER_BLOCK)
                    .mapColor(MapColor.COLOR_GREEN)
                    .strength(1.2F, 5.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> SPC_RISK_BLOCK_ITEM = ITEMS.register("spc_risk_block", () ->
            new BlockItem(SPC_RISK_BLOCK.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SpcRiskBlockEntity>> SPC_RISK_BLOCK_ENTITY = BLOCK_ENTITIES.register("spc_risk_block", () ->
            BlockEntityType.Builder.of(SpcRiskBlockEntity::new, SPC_RISK_BLOCK.get()).build(null));

    // The desk version of the risk panel. It emits light because its monitor is on, and it is directional
    // because the monitor has to face whoever is reading it.
    public static final DeferredHolder<Block, WeatherComputerBlock> WEATHER_COMPUTER_BLOCK = BLOCKS.register("weather_computer", () ->
            new WeatherComputerBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(1.4F, 6.0F)
                    .sound(SoundType.METAL)
                    .lightLevel(state -> 7)
                    .noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> WEATHER_COMPUTER_BLOCK_ITEM = ITEMS.register("weather_computer", () ->
            new BlockItem(WEATHER_COMPUTER_BLOCK.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WeatherComputerBlockEntity>> WEATHER_COMPUTER_BLOCK_ENTITY = BLOCK_ENTITIES.register("weather_computer", () ->
            BlockEntityType.Builder.of(WeatherComputerBlockEntity::new, WEATHER_COMPUTER_BLOCK.get()).build(null));

    // The computer's screen. It is a menu rather than a screen opened directly because it has something to say
    // every second - the three tabs and its own radar sweep - and the open-menu tick is the one place the server
    // already knows who is looking at what.
    public static final DeferredHolder<MenuType<?>, MenuType<WeatherComputerMenu>> WEATHER_COMPUTER_MENU = MENUS.register("weather_computer_menu", () ->
            IMenuTypeExtension.create(WeatherComputerMenu::new));

    public static final DeferredHolder<Block, WeatherRadioBlock> WEATHER_RADIO_BLOCK = BLOCKS.register("weather_radio", () ->
            new WeatherRadioBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.COPPER_BLOCK)
                    .mapColor(MapColor.COLOR_ORANGE)
                    .strength(1.1F, 4.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> WEATHER_RADIO_BLOCK_ITEM = ITEMS.register("weather_radio", () ->
            new BlockItem(WEATHER_RADIO_BLOCK.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WeatherRadioBlockEntity>> WEATHER_RADIO_BLOCK_ENTITY = BLOCK_ENTITIES.register("weather_radio", () ->
            BlockEntityType.Builder.of(WeatherRadioBlockEntity::new, WEATHER_RADIO_BLOCK.get()).build(null));

    public static final DeferredHolder<Block, WeatherVaneBlock> WEATHER_VANE_BLOCK = BLOCKS.register("weather_vane", () ->
            new WeatherVaneBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BARS)
                    .mapColor(MapColor.METAL)
                    .strength(0.8F, 4.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> WEATHER_VANE_BLOCK_ITEM = ITEMS.register("weather_vane", () ->
            new BlockItem(WEATHER_VANE_BLOCK.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WeatherVaneBlockEntity>> WEATHER_VANE_BLOCK_ENTITY = BLOCK_ENTITIES.register("weather_vane", () ->
            BlockEntityType.Builder.of(WeatherVaneBlockEntity::new, WEATHER_VANE_BLOCK.get()).build(null));

    public static final DeferredHolder<Block, BarometerBlock> BAROMETER_BLOCK = BLOCKS.register("barometer", () ->
            new BarometerBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.COPPER_BLOCK)
                    .mapColor(MapColor.COLOR_ORANGE)
                    .strength(1.1F, 4.5F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> BAROMETER_BLOCK_ITEM = ITEMS.register("barometer", () ->
            new BlockItem(BAROMETER_BLOCK.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BarometerBlockEntity>> BAROMETER_BLOCK_ENTITY = BLOCK_ENTITIES.register("barometer", () ->
            BlockEntityType.Builder.of(BarometerBlockEntity::new, BAROMETER_BLOCK.get()).build(null));

    // The three handheld instruments. They are the same readings the blocks above take, asked from wherever
    // the holder is standing, which is why they are items rather than small blocks: the point of a chaser's
    // anemometer is that it goes where the chaser goes.
    public static final DeferredHolder<Item, HandheldInstrumentItem> HANDHELD_ANEMOMETER_ITEM = ITEMS.register("handheld_anemometer", () ->
            new HandheldInstrumentItem(HandheldInstrument.Kind.ANEMOMETER, new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, HandheldInstrumentItem> HANDHELD_THERMOMETER_ITEM = ITEMS.register("handheld_thermometer", () ->
            new HandheldInstrumentItem(HandheldInstrument.Kind.THERMOMETER, new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, HandheldInstrumentItem> HANDHELD_BAROMETER_ITEM = ITEMS.register("handheld_barometer", () ->
            new HandheldInstrumentItem(HandheldInstrument.Kind.BAROMETER, new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Block, RainGaugeBlock> RAIN_GAUGE_BLOCK = BLOCKS.register("rain_gauge", () ->
            new RainGaugeBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                    .mapColor(MapColor.WOOL)
                    .strength(1.2F, 4.5F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> RAIN_GAUGE_BLOCK_ITEM = ITEMS.register("rain_gauge", () ->
            new BlockItem(RAIN_GAUGE_BLOCK.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RainGaugeBlockEntity>> RAIN_GAUGE_BLOCK_ENTITY = BLOCK_ENTITIES.register("rain_gauge", () ->
            BlockEntityType.Builder.of(RainGaugeBlockEntity::new, RAIN_GAUGE_BLOCK.get()).build(null));

    public static final DeferredHolder<Block, TornadoProbeBlock> TORNADO_PROBE_BLOCK = BLOCKS.register("tornado_probe", () ->
            new TornadoProbeBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(1.6F, 6.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    // The three village workstations. A survey station is the tripod a surveyor claims, a builders' bench
    // is the workbench a builder claims and a sawmill is the mill a lumberjack claims; all three are plain
    // blocks whose whole purpose is the profession.
    public static final DeferredHolder<Block, SurveyStationBlock> SURVEY_STATION = BLOCKS.register("survey_station", () ->
            new SurveyStationBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.CARTOGRAPHY_TABLE)
                    .mapColor(MapColor.COLOR_CYAN)
                    .strength(2.0F, 3.0F)
                    .noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> SURVEY_STATION_ITEM = ITEMS.register("survey_station", () ->
            new BlockItem(SURVEY_STATION.get(), new Item.Properties()));

    public static final DeferredHolder<Block, BuildersBenchBlock> BUILDERS_BENCH = BLOCKS.register("builders_bench", () ->
            new BuildersBenchBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.CRAFTING_TABLE)
                    .mapColor(MapColor.WOOD)
                    .strength(2.0F, 3.0F)
                    .noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> BUILDERS_BENCH_ITEM = ITEMS.register("builders_bench", () ->
            new BlockItem(BUILDERS_BENCH.get(), new Item.Properties()));

    public static final DeferredHolder<Block, SawmillBlock> SAWMILL = BLOCKS.register("sawmill", () ->
            new SawmillBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.STONECUTTER)
                    .mapColor(MapColor.WOOD)
                    .strength(2.0F, 3.0F)
                    .noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> SAWMILL_ITEM = ITEMS.register("sawmill", () ->
            new BlockItem(SAWMILL.get(), new Item.Properties()));

    // The two defence blocks. Obsidian reinforced glass is glass the damage passes refuse because of what it is
    // made of; a structural rebar is what they refuse because of what is holding it. Both are defined by the same
    // question - may this block be edited - which is asked in one place, so a fortress is a fortress to every
    // hazard at once rather than to the one a player happened to test.
    public static final DeferredHolder<Block, ReinforcedGlassBlock> REINFORCED_GLASS = BLOCKS.register("reinforced_glass", () ->
            new ReinforcedGlassBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.GLASS)
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(1.2F, 1200.0F)
                    .sound(SoundType.GLASS)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> REINFORCED_GLASS_ITEM = ITEMS.register("reinforced_glass", () ->
            new BlockItem(REINFORCED_GLASS.get(), new Item.Properties()));

    public static final DeferredHolder<Block, StructuralRebarBlock> STRUCTURAL_REBAR = BLOCKS.register("structural_rebar", () ->
            new StructuralRebarBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BARS)
                    .mapColor(MapColor.METAL)
                    .strength(2.0F, 9.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> STRUCTURAL_REBAR_ITEM = ITEMS.register("structural_rebar", () ->
            new BlockItem(STRUCTURAL_REBAR.get(), new Item.Properties()));

    // The meteorologist's own workstation: what makes a station building a weather station.
    public static final DeferredHolder<Block, WeatherStationBlock> WEATHER_STATION = BLOCKS.register("weather_station", () ->
            new WeatherStationBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.CARTOGRAPHY_TABLE)
                    .mapColor(MapColor.METAL)
                    .strength(2.0F, 3.0F)
                    .noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> WEATHER_STATION_ITEM = ITEMS.register("weather_station", () ->
            new BlockItem(WEATHER_STATION.get(), new Item.Properties()));

    // TornadoProbeItem rather than a plain BlockItem: a recovered probe keeps its reading in the stack's
    // block_entity_data component and the item is what shows it and keeps it through re-deployment.
    public static final DeferredHolder<Item, TornadoProbeItem> TORNADO_PROBE_BLOCK_ITEM = ITEMS.register("tornado_probe", () ->
            new TornadoProbeItem(TORNADO_PROBE_BLOCK.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TornadoProbeBlockEntity>> TORNADO_PROBE_BLOCK_ENTITY = BLOCK_ENTITIES.register("tornado_probe", () ->
            BlockEntityType.Builder.of(TornadoProbeBlockEntity::new, TORNADO_PROBE_BLOCK.get()).build(null));

    public static final DeferredHolder<Item, DamageSurveyorItem> DAMAGE_SURVEYOR_ITEM = ITEMS.register("damage_surveyor", () ->
            new DamageSurveyorItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, WeatherBalloonItem> WEATHER_BALLOON_ITEM = ITEMS.register("weather_balloon", () ->
            new WeatherBalloonItem(new Item.Properties().stacksTo(16)));

    // The probe upgrade kits. They are fitted to a placed probe rather than assembled on a bench, so they
    // are plain items with no block behind them.
    public static final DeferredHolder<Item, ProbeUpgradeItem> REINFORCED_PROBE_HOUSING_ITEM = ITEMS.register("reinforced_probe_housing", () ->
            new ProbeUpgradeItem(ProbeUpgradeItem.Kind.HOUSING, new Item.Properties().stacksTo(16)));

    public static final DeferredHolder<Item, ProbeUpgradeItem> PROBE_SENSOR_MODULE_ITEM = ITEMS.register("probe_sensor_module", () ->
            new ProbeUpgradeItem(ProbeUpgradeItem.Kind.SENSOR, new Item.Properties().stacksTo(16)));

    // The spotter flare: a consumable sighting. What it is for is the minute before touchdown, when the mod's
    // own scan has nothing to say and a person watching the funnel does.
    public static final DeferredHolder<Item, SpotterFlareItem> SPOTTER_FLARE_ITEM = ITEMS.register("spotter_flare", () ->
            new SpotterFlareItem(new Item.Properties().stacksTo(16)));

    // The camera: a photograph, scored where you were standing and filed in your own album.
    public static final DeferredHolder<Item, CameraItem> CAMERA_ITEM = ITEMS.register("camera", () ->
            new CameraItem(new Item.Properties().stacksTo(1)));

    // The camera drone, and the item you hold to fly it.
    public static final DeferredHolder<Item, WeatherDroneItem> WEATHER_DRONE_ITEM = ITEMS.register("weather_drone", () ->
            new WeatherDroneItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<EntityType<?>, EntityType<WeatherDroneEntity>> WEATHER_DRONE_ENTITY = ENTITIES.register("weather_drone", () ->
            EntityType.Builder.<WeatherDroneEntity>of(WeatherDroneEntity::new, MobCategory.MISC)
                    // Small enough to fly through a doorway, and tracked further than its size suggests: it is
                    // routinely flown hundreds of blocks from its pilot, who still has to see it. The range is
                    // an arithmetic and not a taste: 32 chunks is 512 blocks, which has to cover the link the
                    // drone ships with (`droneLinkRangeBlocks`, 320 blocks). At the 16 chunks this was set to,
                    // a client stopped being told where its own drone was at 256 blocks - so the camera came
                    // back and the rotor fell silent while the server still considered the link up and the
                    // airframe was still answering the pilot's keys. DroneFlightSim asserts the covering.
                    .sized(0.5F, 0.4F)
                    .clientTrackingRange(32)
                    .updateInterval(1)
                    .build(ExpandedWeather2Dynamics.MODID + ":weather_drone"));

    public static final DeferredHolder<SoundEvent, SoundEvent> TORNADO_ROAR_SOUND = SOUNDS.register("tornado_roar", () ->
            SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "tornado_roar")));

    // The roar as heard from a distance: the same loop with the top end absorbed, the way a few hundred
    // blocks of air leave it. Its own sound rather than a pitch shift of the roar, because distance does not
    // just lower a sound, it filters it - the hiss arrives first and the rumble arrives last. Two loops of one
    // waveform that are faded into each other by how close the funnel is (see TornadoRoarSound), which is why
    // they are phase-coherent: both were built from the same source in the generator.
    public static final DeferredHolder<SoundEvent, SoundEvent> TORNADO_ROAR_FAR_SOUND = SOUNDS.register("tornado_roar_far", () ->
            SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "tornado_roar_far")));

    // The drone under the roar: a humming loop for a tornado violent enough to have a note in its wind. Its
    // own sound rather than a transposed roar, because a hum is a pitch and a roar is noise - see
    // TornadoHumSound. Variable range like the roar, which is what lets its audible radius be set in blocks.
    public static final DeferredHolder<SoundEvent, SoundEvent> TORNADO_HUM_SOUND = SOUNDS.register("tornado_hum", () ->
            SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "tornado_hum")));

    // The three recorded siren tones. Each is a seamless loop of one cycle of that siren's own signal, so
    // a sounding siren is a looping sound rather than a chime repeated.
    public static final DeferredHolder<SoundEvent, SoundEvent> DRONE_ROTOR_SOUND = SOUNDS.register("drone_rotor", () ->
            SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "drone_rotor")));

    public static final DeferredHolder<SoundEvent, SoundEvent> SIREN_MECHANICAL_SOUND = SOUNDS.register("siren_mechanical", () ->
            SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "siren_mechanical")));

    public static final DeferredHolder<SoundEvent, SoundEvent> SIREN_ELECTRONIC_SOUND = SOUNDS.register("siren_electronic", () ->
            SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "siren_electronic")));

    public static final DeferredHolder<SoundEvent, SoundEvent> SIREN_HILO_SOUND = SOUNDS.register("siren_hilo", () ->
            SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "siren_hilo")));

    // ---- hail landing on things --------------------------------------------------------------------
    // One short tick per material a player can tell apart by ear, played where a stone landed: a wooden roof
    // rattles, stone ticks, metal rings, glass pings, ice cracks and a field patters. Six sounds rather than one
    // per block, because what the clatter has to say is what the stones are landing on, and the material is read
    // off the block's own sound type - so a roof built from a block this mod has never heard of still sounds like
    // what it is made of. See HailClatter for the ladder and HailClatterService for where they are played.
    public static final DeferredHolder<SoundEvent, SoundEvent> HAIL_SOFT_SOUND = SOUNDS.register("hail_soft", () ->
            SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "hail_soft")));

    public static final DeferredHolder<SoundEvent, SoundEvent> HAIL_WOOD_SOUND = SOUNDS.register("hail_wood", () ->
            SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "hail_wood")));

    public static final DeferredHolder<SoundEvent, SoundEvent> HAIL_STONE_SOUND = SOUNDS.register("hail_stone", () ->
            SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "hail_stone")));

    public static final DeferredHolder<SoundEvent, SoundEvent> HAIL_ICE_SOUND = SOUNDS.register("hail_ice", () ->
            SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "hail_ice")));

    public static final DeferredHolder<SoundEvent, SoundEvent> HAIL_GLASS_SOUND = SOUNDS.register("hail_glass", () ->
            SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "hail_glass")));

    public static final DeferredHolder<SoundEvent, SoundEvent> HAIL_METAL_SOUND = SOUNDS.register("hail_metal", () ->
            SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "hail_metal")));

    // ---- the pole-mounted sirens -------------------------------------------------------------------
    // One block per tone class, all sharing a block entity: the state they keep is identical and only the
    // machine and its tone differ, so the block carries the variant and the entity reads it back off the
    // block it belongs to.
    public static final DeferredHolder<Block, TornadoSirenBlock> MECHANICAL_SIREN_BLOCK = BLOCKS.register("mechanical_siren", () ->
            new TornadoSirenBlock(SirenTone.MECHANICAL, sirenProperties(MapColor.COLOR_GRAY)));

    public static final DeferredHolder<Item, BlockItem> MECHANICAL_SIREN_BLOCK_ITEM = ITEMS.register("mechanical_siren", () ->
            new BlockItem(MECHANICAL_SIREN_BLOCK.get(), new Item.Properties()));

    public static final DeferredHolder<Block, TornadoSirenBlock> ELECTRONIC_SIREN_BLOCK = BLOCKS.register("electronic_siren", () ->
            new TornadoSirenBlock(SirenTone.ELECTRONIC, sirenProperties(MapColor.COLOR_YELLOW)));

    public static final DeferredHolder<Item, BlockItem> ELECTRONIC_SIREN_BLOCK_ITEM = ITEMS.register("electronic_siren", () ->
            new BlockItem(ELECTRONIC_SIREN_BLOCK.get(), new Item.Properties()));

    public static final DeferredHolder<Block, TornadoSirenBlock> HILO_SIREN_BLOCK = BLOCKS.register("hilo_siren", () ->
            new TornadoSirenBlock(SirenTone.HILO, sirenProperties(MapColor.COLOR_ORANGE)));

    public static final DeferredHolder<Item, BlockItem> HILO_SIREN_BLOCK_ITEM = ITEMS.register("hilo_siren", () ->
            new BlockItem(HILO_SIREN_BLOCK.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TornadoSirenBlockEntity>> TORNADO_SIREN_BLOCK_ENTITY = BLOCK_ENTITIES.register("tornado_siren", () ->
            BlockEntityType.Builder.of(TornadoSirenBlockEntity::new,
                    MECHANICAL_SIREN_BLOCK.get(), ELECTRONIC_SIREN_BLOCK.get(), HILO_SIREN_BLOCK.get()).build(null));

    /** The properties every pole siren shares: a metal machine, climbable, not a solid cube. */
    private static BlockBehaviour.Properties sirenProperties(MapColor color) {
        return BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                .mapColor(color)
                .strength(1.6F, 6.0F)
                .sound(SoundType.METAL)
                .noOcclusion();
    }

    public static final DeferredHolder<EntityType<?>, EntityType<DustDevilEntity>> DUST_DEVIL_ENTITY = ENTITIES.register("dust_devil", () ->
            EntityType.Builder.<DustDevilEntity>of(DustDevilEntity::new, MobCategory.MISC)
                    .sized(0.8F, 1.8F)
                    .clientTrackingRange(12)
                    .updateInterval(2)
                    .build(ExpandedWeather2Dynamics.MODID + ":dust_devil"));

    public static final DeferredHolder<EntityType<?>, EntityType<LandspoutEntity>> LANDSPOUT_ENTITY = ENTITIES.register("landspout", () ->
            EntityType.Builder.<LandspoutEntity>of(LandspoutEntity::new, MobCategory.MISC)
                    .sized(1.1F, 2.4F)
                    .clientTrackingRange(10)
                    .updateInterval(2)
                    .build(ExpandedWeather2Dynamics.MODID + ":landspout"));

    public static final DeferredHolder<EntityType<?>, EntityType<WaterspoutEntity>> WATERSPOUT_ENTITY = ENTITIES.register("waterspout", () ->
            EntityType.Builder.<WaterspoutEntity>of(WaterspoutEntity::new, MobCategory.MISC)
                    .sized(1.0F, 2.2F)
                    .clientTrackingRange(10)
                    .updateInterval(2)
                    .build(ExpandedWeather2Dynamics.MODID + ":waterspout"));

    // A funnel cloud hangs aloft, so it is watched from further away than the ground-hugging
    // vortices; its own renderer reserves the drawing distance it needs.
    // A single piece of airborne debris the tornado is carrying. Watched from close in and updated every
    // tick, because its whole point is its own motion - it is not a particle the client draws.
    public static final DeferredHolder<EntityType<?>, EntityType<DebrisEntity>> DEBRIS_ENTITY = ENTITIES.register("debris", () ->
            EntityType.Builder.<DebrisEntity>of(DebrisEntity::new, MobCategory.MISC)
                    .sized(0.6F, 0.6F)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .build(ExpandedWeather2Dynamics.MODID + ":debris"));

    public static final DeferredHolder<EntityType<?>, EntityType<FunnelCloudEntity>> FUNNEL_CLOUD_ENTITY = ENTITIES.register("funnel_cloud", () ->
            EntityType.Builder.<FunnelCloudEntity>of(FunnelCloudEntity::new, MobCategory.MISC)
                    .sized(1.0F, 2.2F)
                    .clientTrackingRange(18)
                    .updateInterval(2)
                    .build(ExpandedWeather2Dynamics.MODID + ":funnel_cloud"));

    // The snow devil is the dust devil's cold-country twin, drawn by the same vortex renderer, so it is
    // watched from the same distance as the other ground-hugging whirls.
    public static final DeferredHolder<EntityType<?>, EntityType<SnowDevilEntity>> SNOW_DEVIL_ENTITY = ENTITIES.register("snow_devil", () ->
            EntityType.Builder.<SnowDevilEntity>of(SnowDevilEntity::new, MobCategory.MISC)
                    .sized(0.8F, 1.8F)
                    .clientTrackingRange(12)
                    .updateInterval(2)
                    .build(ExpandedWeather2Dynamics.MODID + ":snow_devil"));

    // The volcanic pair. They are whirls like the others - the same renderer draws them and the same wind
    // field carries them - so they are watched from a whirl's distance and updated at a whirl's rate.
    public static final DeferredHolder<EntityType<?>, EntityType<LavaWhirlEntity>> LAVA_WHIRL_ENTITY = ENTITIES.register("lava_whirl", () ->
            EntityType.Builder.<LavaWhirlEntity>of(LavaWhirlEntity::new, MobCategory.MISC)
                    .sized(1.2F, 2.4F)
                    .fireImmune()
                    .clientTrackingRange(14)
                    .updateInterval(2)
                    .build(ExpandedWeather2Dynamics.MODID + ":lava_whirl"));

    public static final DeferredHolder<EntityType<?>, EntityType<SteamDevilEntity>> STEAM_DEVIL_ENTITY = ENTITIES.register("steam_devil", () ->
            EntityType.Builder.<SteamDevilEntity>of(SteamDevilEntity::new, MobCategory.MISC)
                    .sized(0.7F, 1.6F)
                    .fireImmune()
                    .clientTrackingRange(12)
                    .updateInterval(2)
                    .build(ExpandedWeather2Dynamics.MODID + ":steam_devil"));

    public static final DeferredHolder<EntityType<?>, EntityType<GustnadoEntity>> GUSTNADO_ENTITY = ENTITIES.register("gustnado", () ->
            EntityType.Builder.<GustnadoEntity>of(GustnadoEntity::new, MobCategory.MISC)
                    .sized(0.9F, 1.6F)
                    .clientTrackingRange(12)
                    .updateInterval(2)
                    .build(ExpandedWeather2Dynamics.MODID + ":gustnado"));

    // Sondes climb high and are watched from the ground, so they are tracked far further than the
    // other addon entities; the client reserves its own drawing distance on top of this.
    public static final DeferredHolder<EntityType<?>, EntityType<WeatherBalloonEntity>> WEATHER_BALLOON_ENTITY = ENTITIES.register("weather_balloon", () ->
            EntityType.Builder.<WeatherBalloonEntity>of(WeatherBalloonEntity::new, MobCategory.MISC)
                    .sized(0.9F, 2.0F)
                    .clientTrackingRange(40)
                    .updateInterval(1)
                    .build(ExpandedWeather2Dynamics.MODID + ":weather_balloon"));

    private ModContent() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        MENUS.register(modEventBus);
        SOUNDS.register(modEventBus);
        ENTITIES.register(modEventBus);
        modEventBus.addListener(ModContent::addCreative);
    }

    /**
     * Everything this addon registers goes into Weather2's own tab, next to its radar and siren, and
     * nowhere else - one home is easier to find than a scatter of vanilla tabs.
     *
     * <p>Matched by namespace rather than by a guessed tab name. Weather2 registers its tab with a
     * bare {@code itemGroup.weather2} translation key, which is not the {@code itemGroup.<namespace>.<path>}
     * shape, so its registry path cannot be inferred from outside the mod; matching the namespace
     * cannot miss it. The order here is the order they appear in the tab.
     */
    private static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (!event.getTabKey().location().getNamespace().equals("weather2")) {
            return;
        }
        event.accept(RADAR_BLOCK_ITEM.get());
        event.accept(PORTABLE_RADAR_ITEM.get());
        event.accept(SPC_RISK_BLOCK_ITEM.get());
        event.accept(WEATHER_COMPUTER_BLOCK_ITEM.get());
        event.accept(WEATHER_ALERT_SIREN_BLOCK_ITEM.get());
        event.accept(MECHANICAL_SIREN_BLOCK_ITEM.get());
        event.accept(ELECTRONIC_SIREN_BLOCK_ITEM.get());
        event.accept(HILO_SIREN_BLOCK_ITEM.get());
        event.accept(SIREN_CONTROL_PANEL_BLOCK_ITEM.get());
        event.accept(SIREN_WIRE_BLOCK_ITEM.get());
        event.accept(WEATHER_RADIO_BLOCK_ITEM.get());
        event.accept(ANEMOMETER_BLOCK_ITEM.get());
        event.accept(WEATHER_VANE_BLOCK_ITEM.get());
        event.accept(BAROMETER_BLOCK_ITEM.get());
        event.accept(RAIN_GAUGE_BLOCK_ITEM.get());
        event.accept(HANDHELD_ANEMOMETER_ITEM.get());
        event.accept(HANDHELD_THERMOMETER_ITEM.get());
        event.accept(HANDHELD_BAROMETER_ITEM.get());
        event.accept(TORNADO_PROBE_BLOCK_ITEM.get());
        event.accept(REINFORCED_PROBE_HOUSING_ITEM.get());
        event.accept(PROBE_SENSOR_MODULE_ITEM.get());
        event.accept(WEATHER_BALLOON_ITEM.get());
        event.accept(WEATHER_DRONE_ITEM.get());
        event.accept(SPOTTER_FLARE_ITEM.get());
        event.accept(CAMERA_ITEM.get());
        event.accept(DAMAGE_SURVEYOR_ITEM.get());
        event.accept(SURVEY_STATION_ITEM.get());
        event.accept(BUILDERS_BENCH_ITEM.get());
        event.accept(SAWMILL_ITEM.get());
        event.accept(WEATHER_STATION_ITEM.get());
        event.accept(REINFORCED_GLASS_ITEM.get());
        event.accept(STRUCTURAL_REBAR_ITEM.get());
    }
}
