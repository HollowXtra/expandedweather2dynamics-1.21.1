package com.imbet.radar;

import com.imbet.Config;
import com.imbet.network.RadarSnapshotPayload;
import com.imbet.registry.ModContent;
import com.imbet.weather.AdvancementAwarder;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import com.imbet.network.ModNetworking;

import java.util.List;

public class RadarBlockEntity extends BlockEntity {
    private static final int[] RANGE_OPTIONS = {512, 1024, 2048, 4096};
    private int power;
    private int rangeIndex = 1;
    private int mode;

    public RadarBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModContent.RADAR_BLOCK_ENTITY.get(), pos, blockState);
    }

    public int getPower() {
        return power;
    }

    public int getRange() {
        return RANGE_OPTIONS[Math.floorMod(rangeIndex, RANGE_OPTIONS.length)];
    }

    public int getMode() {
        return mode;
    }

    public String getModeLabel() {
        return switch (mode) {
            case 1 -> "Velocity";
            case 2 -> "SPC Risk";
            default -> "Reflectivity";
        };
    }

    public void cycleMode() {
        mode = (mode + 1) % 3;
        if (mode == 0) {
            rangeIndex = (rangeIndex + 1) % RANGE_OPTIONS.length;
        }
        setChanged();
    }

    public static void tick(Level level, BlockPos pos, BlockState state, RadarBlockEntity entity) {
        if (level.isClientSide() || !Config.RADAR_ENABLED.get() || level.getGameTime() % Config.RADAR_UPDATE_INTERVAL_TICKS.get() != 0L) {
            return;
        }
        ServerLevel serverLevel = (ServerLevel) level;
        List<RadarStormSnapshot> snapshots = RadarService.buildSnapshot(serverLevel, pos, Math.min(Config.RADAR_RANGE_BLOCKS.get(), entity.getRange()));
        if (snapshots.stream().anyMatch(snapshot -> snapshot.hookEcho() || snapshot.label().contains("Tornado") || snapshot.label().contains("Waterspout") || snapshot.label().contains("Landspout"))) {
            for (ServerPlayer player : serverLevel.players()) {
                if (player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) < 64.0D * 64.0D) {
                    AdvancementAwarder.award(player, AdvancementAwarder.DETECT_TORNADO);
                }
            }
        }
        int newPower = RadarService.powerFor(snapshots);
        if (newPower != entity.power) {
            entity.power = newPower;
            entity.setChanged();
            level.updateNeighborsAt(pos, state.getBlock());
        }
        ModNetworking.sendToPlayersNear(
                serverLevel,
                pos.getX() + 0.5D,
                pos.getY() + 0.5D,
                pos.getZ() + 0.5D,
                entity.getRange(),
                new RadarSnapshotPayload(pos, snapshots)
        );
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("Power", power);
        tag.putInt("RangeIndex", rangeIndex);
        tag.putInt("Mode", mode);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        power = tag.getInt("Power");
        rangeIndex = tag.getInt("RangeIndex");
        mode = tag.getInt("Mode");
    }
}
