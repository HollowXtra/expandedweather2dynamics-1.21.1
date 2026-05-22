package com.imbet.registry;

import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.alert.WeatherAlertSirenBlock;
import com.imbet.alert.WeatherAlertSirenBlockEntity;
import com.imbet.anemometer.AnemometerBlock;
import com.imbet.anemometer.AnemometerBlockEntity;
import com.imbet.radar.RadarBlock;
import com.imbet.radar.RadarBlockEntity;
import com.imbet.radar.RadarMenu;
import com.imbet.spc.SpcRiskBlock;
import com.imbet.spc.SpcRiskBlockEntity;
import com.imbet.survey.DamageSurveyorItem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;

public final class ModContent {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, ExpandedWeather2Dynamics.MODID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ExpandedWeather2Dynamics.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, ExpandedWeather2Dynamics.MODID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, ExpandedWeather2Dynamics.MODID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ExpandedWeather2Dynamics.MODID);

    public static final RegistryObject<RadarBlock> RADAR_BLOCK = BLOCKS.register("radar_block", () ->
            new RadarBlock(BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK)
                    .mapColor(MapColor.STONE)
                    .strength(1.5F, 6.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    public static final RegistryObject<BlockItem> RADAR_BLOCK_ITEM = ITEMS.register("radar_block", () ->
            new BlockItem(RADAR_BLOCK.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<RadarBlockEntity>> RADAR_BLOCK_ENTITY = BLOCK_ENTITIES.register("radar_block", () ->
            BlockEntityType.Builder.of(RadarBlockEntity::new, RADAR_BLOCK.get()).build(null));

    public static final RegistryObject<MenuType<RadarMenu>> RADAR_MENU = MENUS.register("radar_menu", () ->
            IForgeMenuType.create(RadarMenu::new));

    public static final RegistryObject<AnemometerBlock> ANEMOMETER_BLOCK = BLOCKS.register("anemometer", () ->
            new AnemometerBlock(BlockBehaviour.Properties.copy(Blocks.IRON_BARS)
                    .mapColor(MapColor.METAL)
                    .strength(0.8F, 4.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    public static final RegistryObject<BlockItem> ANEMOMETER_BLOCK_ITEM = ITEMS.register("anemometer", () ->
            new BlockItem(ANEMOMETER_BLOCK.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<AnemometerBlockEntity>> ANEMOMETER_BLOCK_ENTITY = BLOCK_ENTITIES.register("anemometer", () ->
            BlockEntityType.Builder.of(AnemometerBlockEntity::new, ANEMOMETER_BLOCK.get()).build(null));

    public static final RegistryObject<WeatherAlertSirenBlock> WEATHER_ALERT_SIREN_BLOCK = BLOCKS.register("weather_alert_siren", () ->
            new WeatherAlertSirenBlock(BlockBehaviour.Properties.copy(Blocks.COPPER_BLOCK)
                    .mapColor(MapColor.COLOR_RED)
                    .strength(1.2F, 5.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    public static final RegistryObject<BlockItem> WEATHER_ALERT_SIREN_BLOCK_ITEM = ITEMS.register("weather_alert_siren", () ->
            new BlockItem(WEATHER_ALERT_SIREN_BLOCK.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<WeatherAlertSirenBlockEntity>> WEATHER_ALERT_SIREN_BLOCK_ENTITY = BLOCK_ENTITIES.register("weather_alert_siren", () ->
            BlockEntityType.Builder.of(WeatherAlertSirenBlockEntity::new, WEATHER_ALERT_SIREN_BLOCK.get()).build(null));

    public static final RegistryObject<SpcRiskBlock> SPC_RISK_BLOCK = BLOCKS.register("spc_risk_block", () ->
            new SpcRiskBlock(BlockBehaviour.Properties.copy(Blocks.COPPER_BLOCK)
                    .mapColor(MapColor.COLOR_GREEN)
                    .strength(1.2F, 5.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    public static final RegistryObject<BlockItem> SPC_RISK_BLOCK_ITEM = ITEMS.register("spc_risk_block", () ->
            new BlockItem(SPC_RISK_BLOCK.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<SpcRiskBlockEntity>> SPC_RISK_BLOCK_ENTITY = BLOCK_ENTITIES.register("spc_risk_block", () ->
            BlockEntityType.Builder.of(SpcRiskBlockEntity::new, SPC_RISK_BLOCK.get()).build(null));

    public static final RegistryObject<DamageSurveyorItem> DAMAGE_SURVEYOR_ITEM = ITEMS.register("damage_surveyor", () ->
            new DamageSurveyorItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<SoundEvent> TORNADO_ROAR_SOUND = SOUNDS.register("tornado_roar", () ->
            SoundEvent.createVariableRangeEvent(new ResourceLocation(ExpandedWeather2Dynamics.MODID, "tornado_roar")));

    private ModContent() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        MENUS.register(modEventBus);
        SOUNDS.register(modEventBus);
        modEventBus.addListener(ModContent::addCreative);
    }

    private static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS || event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
            event.accept(RADAR_BLOCK_ITEM.get());
            event.accept(ANEMOMETER_BLOCK_ITEM.get());
            event.accept(WEATHER_ALERT_SIREN_BLOCK_ITEM.get());
            event.accept(SPC_RISK_BLOCK_ITEM.get());
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(DAMAGE_SURVEYOR_ITEM.get());
        }
    }
}
