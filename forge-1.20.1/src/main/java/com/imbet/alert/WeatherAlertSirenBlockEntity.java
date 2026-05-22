package com.imbet.alert;

import com.imbet.Config;
import com.imbet.registry.ModContent;
import com.imbet.weather.AlertLevel;
import com.imbet.weather.AlertService;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class WeatherAlertSirenBlockEntity extends BlockEntity {
    private int mode;
    private int power;
    private long lastToneTick = -1_000_000L;
    private long currentAlertId = Long.MIN_VALUE;
    private long currentAlertExpiresAt;
    private AlertLevel alertLevel = AlertLevel.NONE;

    public WeatherAlertSirenBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModContent.WEATHER_ALERT_SIREN_BLOCK_ENTITY.get(), pos, blockState);
    }

    public int getPower() {
        return power;
    }

    public void cycleMode() {
        mode = (mode + 1) % 3;
        setChanged();
    }

    public String modeLabel() {
        return switch (mode) {
            case 1 -> "Warnings Only";
            case 2 -> "Muted";
            default -> "All Alerts";
        };
    }

    public static void tick(Level level, BlockPos pos, BlockState state, WeatherAlertSirenBlockEntity entity) {
        if (level.isClientSide() || !Config.ALERTS_ENABLED.get() || level.getGameTime() % 20L != 0L) {
            return;
        }
        if (entity.mode == 2) {
            entity.setAlert(level, state, AlertLevel.NONE);
            return;
        }
        ServerLevel serverLevel = (ServerLevel) level;
        AlertService.AlertResult result = AlertService.highestAlert(serverLevel, Vec3.atCenterOf(pos), entity.mode == 1);
        entity.setAlert(level, state, result);
        if (result.level().activatesEas() && Config.ALERTS_FAUX_SOUND.get() && entity.lastToneTick + Config.ALERT_COOLDOWN_TICKS.get() / 3L <= level.getGameTime()) {
            entity.lastToneTick = level.getGameTime();
            float pitch = result.level() == AlertLevel.TORNADO_EMERGENCY ? 0.48F : result.level() == AlertLevel.SEVERE_STORM_WARNING ? 0.74F : 0.55F;
            serverLevel.playSound(null, pos, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.BLOCKS, 1.0F, pitch);
            serverLevel.playSound(null, pos, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 0.8F, pitch + 0.45F);
        }
    }

    private void setAlert(Level level, BlockState state, AlertService.AlertResult result) {
        currentAlertId = result.sourceId();
        currentAlertExpiresAt = result.expiresAt();
        setAlert(level, state, result.level());
    }

    private void setAlert(Level level, BlockState state, AlertLevel levelAlert) {
        int newPower = levelAlert.redstonePower();
        alertLevel = levelAlert;
        if (newPower != power) {
            power = newPower;
            setChanged();
            level.updateNeighborsAt(worldPosition, state.getBlock());
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("Mode", mode);
        tag.putInt("Power", power);
        tag.putLong("LastToneTick", lastToneTick);
        tag.putLong("CurrentAlertId", currentAlertId);
        tag.putLong("CurrentAlertExpiresAt", currentAlertExpiresAt);
        tag.putString("AlertLevel", alertLevel.name());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        mode = tag.getInt("Mode");
        power = tag.getInt("Power");
        lastToneTick = tag.getLong("LastToneTick");
        currentAlertId = tag.contains("CurrentAlertId") ? tag.getLong("CurrentAlertId") : Long.MIN_VALUE;
        currentAlertExpiresAt = tag.getLong("CurrentAlertExpiresAt");
        try {
            alertLevel = AlertLevel.valueOf(tag.getString("AlertLevel"));
        } catch (IllegalArgumentException ignored) {
            alertLevel = AlertLevel.NONE;
        }
    }
}
