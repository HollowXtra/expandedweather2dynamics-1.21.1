package com.imbet.registry;

import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.alert.WeatherAlertSirenBlock;
import com.imbet.balloon.WeatherBalloonItem;
import com.imbet.alert.WeatherAlertSirenBlockEntity;
import com.imbet.anemometer.AnemometerBlock;
import com.imbet.anemometer.AnemometerBlockEntity;
import com.imbet.instruments.BarometerBlock;
import com.imbet.instruments.BarometerBlockEntity;
import com.imbet.instruments.RainGaugeBlock;
import com.imbet.instruments.RainGaugeBlockEntity;
import com.imbet.instruments.TornadoProbeBlock;
import com.imbet.instruments.TornadoProbeBlockEntity;
import com.imbet.instruments.TornadoProbeItem;
import com.imbet.instruments.WeatherRadioBlock;
import com.imbet.instruments.WeatherRadioBlockEntity;
import com.imbet.instruments.WeatherVaneBlock;
import com.imbet.instruments.WeatherVaneBlockEntity;
import com.imbet.radar.RadarBlock;
import com.imbet.radar.RadarBlockEntity;
import com.imbet.radar.RadarMenu;
import com.imbet.radar.PortableRadarItem;
import com.imbet.spc.SpcRiskBlock;
import com.imbet.spc.SpcRiskBlockEntity;
import com.imbet.survey.DamageSurveyorItem;
import com.imbet.weather.DustDevilEntity;
import com.imbet.weather.GustnadoEntity;
import com.imbet.weather.LandspoutEntity;
import com.imbet.weather.WaterspoutEntity;
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

    public static final DeferredHolder<SoundEvent, SoundEvent> TORNADO_ROAR_SOUND = SOUNDS.register("tornado_roar", () ->
            SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "tornado_roar")));

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
        event.accept(WEATHER_ALERT_SIREN_BLOCK_ITEM.get());
        event.accept(WEATHER_RADIO_BLOCK_ITEM.get());
        event.accept(ANEMOMETER_BLOCK_ITEM.get());
        event.accept(WEATHER_VANE_BLOCK_ITEM.get());
        event.accept(BAROMETER_BLOCK_ITEM.get());
        event.accept(RAIN_GAUGE_BLOCK_ITEM.get());
        event.accept(TORNADO_PROBE_BLOCK_ITEM.get());
        event.accept(WEATHER_BALLOON_ITEM.get());
        event.accept(DAMAGE_SURVEYOR_ITEM.get());
    }
}
