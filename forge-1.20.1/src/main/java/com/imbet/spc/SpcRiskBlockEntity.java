package com.imbet.spc;

import com.imbet.Config;
import com.imbet.registry.ModContent;
import com.imbet.weather.SpcOutlookService;
import com.imbet.weather.SpcRisk;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public class SpcRiskBlockEntity extends BlockEntity {
    private int mode;
    private int power;
    private SpcRisk categorical = SpcRisk.NONE;
    private SpcRisk tornado = SpcRisk.NONE;
    private SpcRisk wind = SpcRisk.NONE;
    private SpcRisk hail = SpcRisk.NONE;
    private String hazard = "None";
    private int stormCount;
    private int severeCount;
    private double maxWindMph;

    public SpcRiskBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModContent.SPC_RISK_BLOCK_ENTITY.get(), pos, blockState);
    }

    public int getPower() {
        return power;
    }

    public int getMode() {
        return mode;
    }

    public String getModeLabel() {
        return switch (mode) {
            case 1 -> "Tornado";
            case 2 -> "Wind";
            case 3 -> "Hail";
            default -> "Categorical";
        };
    }

    public SpcRisk currentRisk() {
        return switch (mode) {
            case 1 -> tornado;
            case 2 -> wind;
            case 3 -> hail;
            default -> categorical;
        };
    }

    public SpcRisk categoricalRisk() {
        return categorical;
    }

    public SpcRisk tornadoRisk() {
        return tornado;
    }

    public SpcRisk windRisk() {
        return wind;
    }

    public SpcRisk hailRisk() {
        return hail;
    }

    public String hazard() {
        return hazard;
    }

    public int stormCount() {
        return stormCount;
    }

    public int severeCount() {
        return severeCount;
    }

    public double maxWindMph() {
        return maxWindMph;
    }

    public void cycleMode(Level level, BlockState state) {
        mode = (mode + 1) % 4;
        refreshPower(level, state);
        sync(level, state);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, SpcRiskBlockEntity entity) {
        if (level.isClientSide() || !Config.RADAR_ENABLED.get() || level.getGameTime() % Config.RADAR_UPDATE_INTERVAL_TICKS.get() != 0L) {
            return;
        }
        SpcOutlookService.Outlook outlook = SpcOutlookService.compute((ServerLevel) level, Vec3.atCenterOf(pos), Config.RADAR_RANGE_BLOCKS.get());
        boolean changed = entity.apply(outlook);
        int oldPower = entity.power;
        entity.refreshPower(level, state);
        if (changed || oldPower != entity.power) {
            entity.sync(level, state);
        }
    }

    private boolean apply(SpcOutlookService.Outlook outlook) {
        boolean changed = categorical != outlook.categorical()
                || tornado != outlook.tornado()
                || wind != outlook.wind()
                || hail != outlook.hail()
                || !hazard.equals(outlook.hazard())
                || stormCount != outlook.stormCount()
                || severeCount != outlook.severeCount()
                || Math.abs(maxWindMph - outlook.maxWindMph()) > 0.05D;
        categorical = outlook.categorical();
        tornado = outlook.tornado();
        wind = outlook.wind();
        hail = outlook.hail();
        hazard = outlook.hazard();
        stormCount = outlook.stormCount();
        severeCount = outlook.severeCount();
        maxWindMph = outlook.maxWindMph();
        return changed;
    }

    private void refreshPower(Level level, BlockState state) {
        int newPower = currentRisk().redstonePower();
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
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("Mode", mode);
        tag.putInt("Power", power);
        tag.putString("Categorical", categorical.name());
        tag.putString("Tornado", tornado.name());
        tag.putString("Wind", wind.name());
        tag.putString("Hail", hail.name());
        tag.putString("Hazard", hazard);
        tag.putInt("StormCount", stormCount);
        tag.putInt("SevereCount", severeCount);
        tag.putDouble("MaxWindMph", maxWindMph);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        mode = tag.getInt("Mode");
        power = tag.getInt("Power");
        categorical = readRisk(tag, "Categorical");
        tornado = readRisk(tag, "Tornado");
        wind = readRisk(tag, "Wind");
        hail = readRisk(tag, "Hail");
        hazard = tag.contains("Hazard") ? tag.getString("Hazard") : "None";
        stormCount = tag.getInt("StormCount");
        severeCount = tag.getInt("SevereCount");
        maxWindMph = tag.getDouble("MaxWindMph");
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        return tag;
    }

    private static SpcRisk readRisk(CompoundTag tag, String key) {
        if (!tag.contains(key)) {
            return SpcRisk.NONE;
        }
        try {
            return SpcRisk.valueOf(tag.getString(key));
        } catch (IllegalArgumentException ignored) {
            return SpcRisk.NONE;
        }
    }
}
