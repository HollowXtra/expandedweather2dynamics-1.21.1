package com.imbet.instruments;

import com.imbet.Config;
import com.imbet.alert.AlertStation;
import com.imbet.alert.AlertStationNetwork;
import com.imbet.registry.ModContent;
import com.imbet.weather.AlertLevel;
import com.imbet.weather.AlertService;
import com.imbet.weather.WeatherInstruments;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The Weather Radio: an alert station you can put indoors.
 *
 * <p>It is a station in exactly the sense a siren is - {@link AlertStationNetwork} sweeps it, and
 * every player inside its radius gets the alert banner on their client. What makes it a radio rather
 * than a second siren is that it also <em>reads</em>: its panel shows the product in force over its
 * own position, and using it prints the full product plus the local wind, pressure and nearest storm.
 * It also rings the faux tones into the players it reaches, which is the one thing a siren deliberately
 * does not do - a siren already rings out of its own block, so it would double every alert.
 *
 * <p>Its channel works the same way the siren's does: all alerts, warnings only, or muted. Turning the
 * radio off in the config reports {@link #MODE_MUTED} to the network, so a disabled radio stops
 * broadcasting without having to be removed from the registry.
 */
public class WeatherRadioBlockEntity extends BlockEntity implements AlertStation, PoweredInstrument {
    private int mode;
    private int power;
    private AlertLevel levelAlert = AlertLevel.NONE;
    private String productCode = "";
    private String headline = "NO ACTIVE PRODUCT";
    private String source = "";
    private int distanceBlocks;

    public WeatherRadioBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModContent.WEATHER_RADIO_BLOCK_ENTITY.get(), pos, blockState);
    }

    public int getPower() {
        return power;
    }

    @Override
    public int instrumentPower() {
        return power;
    }

    public int getMode() {
        return mode;
    }

    public String modeLabel() {
        return switch (mode) {
            case 1 -> "Warnings Only";
            case 2 -> "Muted";
            default -> "All Alerts";
        };
    }

    /** The product currently being read out, for the panel. */
    public AlertLevel currentLevel() {
        return levelAlert;
    }

    public String currentProductCode() {
        return productCode;
    }

    public String currentHeadline() {
        return headline;
    }

    public String currentSource() {
        return source;
    }

    public int currentDistanceBlocks() {
        return distanceBlocks;
    }

    public void cycleMode(Level level, BlockState state) {
        mode = (mode + 1) % 3;
        // Muting has to take effect at once, so the network is told now rather than on its next sweep.
        if (level instanceof ServerLevel serverLevel) {
            AlertStationNetwork.recordStation(serverLevel, worldPosition, this);
        }
        setChanged();
    }

    @Override
    public int alertMode() {
        return Config.WEATHER_RADIO_ENABLED.get() ? mode : MODE_MUTED;
    }

    @Override
    public double alertRangeBlocks() {
        return Math.max(32.0D, Config.WEATHER_RADIO_RANGE_BLOCKS.get());
    }

    @Override
    public boolean ringsTones() {
        return Config.WEATHER_RADIO_ENABLED.get() && Config.WEATHER_RADIO_TONES.get();
    }

    @Override
    public String stationKind() {
        return "RADIO";
    }

    public static void tick(Level level, BlockPos pos, BlockState state, WeatherRadioBlockEntity entity) {
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (level.getGameTime() % 20L == 0L) {
            AlertStationNetwork.recordStation(serverLevel, pos, entity);
        }
        if (!Config.ALERTS_ENABLED.get() || entity.alertMode() == MODE_MUTED) {
            if (entity.applyReading(AlertService.NONE)) {
                entity.sync(level, state);
            }
            return;
        }
        if (level.getGameTime() % 10L != 0L) {
            return;
        }
        AlertService.AlertResult result = AlertService.highestAlert(
                serverLevel, Vec3.atCenterOf(pos), entity.mode == MODE_WARNINGS_ONLY);
        boolean changed = entity.applyReading(result);
        int oldPower = entity.power;
        entity.refreshPower(level, state);
        if (changed || oldPower != entity.power) {
            entity.sync(level, state);
        }
    }

    /**
     * Prints the full product and the local conditions to a player.
     *
     * <p>This is the radio's other half: the banner tells you to take cover, and this tells you what
     * the product actually says and what the air is doing where the set is standing.
     */
    public void readout(ServerPlayer player) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        player.sendSystemMessage(Component.literal("Weather Radio - channel " + modeLabel()).withStyle(net.minecraft.ChatFormatting.AQUA));

        AlertService.AlertResult result = alertMode() == MODE_MUTED
                ? AlertService.NONE
                : AlertService.highestAlert(serverLevel, Vec3.atCenterOf(worldPosition), mode == MODE_WARNINGS_ONLY);
        if (result.level() == AlertLevel.NONE) {
            player.sendSystemMessage(Component.literal("  No product in force for this position.").withStyle(net.minecraft.ChatFormatting.GRAY));
        } else {
            int distance = (int) Math.round(player.position().distanceTo(result.position()));
            int lead = (int) Math.round(player.position().distanceTo(result.projectedPosition()));
            player.sendSystemMessage(Component.literal("  " + result.productCode() + " / " + result.level().label() + ": " + result.label() + " " + result.source())
                    .withStyle(result.level().isWarning() ? net.minecraft.ChatFormatting.RED : net.minecraft.ChatFormatting.GOLD));
            player.sendSystemMessage(Component.literal("  " + distance + " blocks away, projected path " + lead + " blocks.").withStyle(net.minecraft.ChatFormatting.GRAY));
        }

        Vec3 center = Vec3.atCenterOf(worldPosition);
        var wind = WeatherInstruments.wind(level, center);
        WeatherInstruments.PressureReading pressure = WeatherInstruments.pressure(serverLevel, center);
        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                "  Wind %s from %s  |  %.1f hPa  |  radio reaches %.0f blocks",
                WeatherInstruments.mph(wind.speedMph()),
                WeatherInstruments.windFromLabel(wind.mphVector()),
                pressure.hpa(),
                alertRangeBlocks()))
                .withStyle(net.minecraft.ChatFormatting.GRAY));

        WeatherInstruments.StormReading storm = WeatherInstruments.nearestStorm(serverLevel, center, 4096.0D);
        if (storm != null) {
            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                    "  Nearest: %s bearing %s, %.0f blocks, %s",
                    storm.label(), storm.bearing(), storm.distanceBlocks(), WeatherInstruments.stageLabel(storm.stage())))
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
        }
    }

    private boolean applyReading(AlertService.AlertResult result) {
        AlertLevel newLevel = result.level();
        String newCode = newLevel == AlertLevel.NONE ? "" : result.productCode();
        String newHeadline = newLevel == AlertLevel.NONE ? "NO ACTIVE PRODUCT" : result.label();
        String newSource = newLevel == AlertLevel.NONE ? "" : result.source();
        int newDistance = newLevel == AlertLevel.NONE ? 0 : (int) Math.round(Math.sqrt(worldPosition.distToCenterSqr(result.position())));
        boolean changed = levelAlert != newLevel
                || !productCode.equals(newCode)
                || !headline.equals(newHeadline)
                || !source.equals(newSource)
                || distanceBlocks != newDistance;
        levelAlert = newLevel;
        productCode = newCode;
        headline = newHeadline;
        source = newSource;
        distanceBlocks = newDistance;
        return changed;
    }

    private void refreshPower(Level level, BlockState state) {
        int newPower = levelAlert.redstonePower();
        if (newPower != power) {
            power = newPower;
            level.updateNeighborsAt(worldPosition, state.getBlock());
        }
    }

    private void sync(Level level, BlockState state) {
        setChanged();
        level.sendBlockUpdated(worldPosition, state, state, 3);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            AlertStationNetwork.recordStation(serverLevel, worldPosition, this);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Mode", mode);
        tag.putInt("Power", power);
        tag.putString("AlertLevel", levelAlert.name());
        tag.putString("ProductCode", productCode);
        tag.putString("Headline", headline);
        tag.putString("Source", source);
        tag.putInt("Distance", distanceBlocks);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        mode = tag.getInt("Mode");
        power = tag.getInt("Power");
        levelAlert = AlertLevel.byName(tag.getString("AlertLevel"));
        productCode = tag.getString("ProductCode");
        headline = tag.contains("Headline") ? tag.getString("Headline") : "NO ACTIVE PRODUCT";
        source = tag.getString("Source");
        distanceBlocks = tag.getInt("Distance");
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        return tag;
    }
}
