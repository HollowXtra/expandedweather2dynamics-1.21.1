package com.imbet.radar;

import com.imbet.Config;
import com.imbet.network.RadarSnapshotPayload;
import com.imbet.network.NetworkSafety;
import com.imbet.registry.ModContent;
import com.imbet.weather.AdvancementAwarder;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class RadarBlockEntity extends BlockEntity {
    private static final int[] RANGE_OPTIONS = {512, 1024, 2048, 4096};

    /**
     * The weather radars a handheld can be linked to, per dimension.
     *
     * <p>A radar registers itself as it ticks and drops out when it is removed, because there is no cheap way
     * to ask a server level for its block entities: iterating loaded chunks to find the nearest radar would
     * cost far more than the search it replaces. Entries are validated on lookup - a block that has been
     * broken or replaced takes its entry with it - so a stale one is never handed out, and a level that has
     * been unloaded simply finds nothing.
     */
    private static final Map<ResourceKey<Level>, Map<BlockPos, RadarBlockEntity>> SERVER_RADARS = new HashMap<>();

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

    /** The range this radar actually sweeps, which the configured ceiling can cut down. */
    public int effectiveRange() {
        return Math.max(64, Math.min(Config.RADAR_RANGE_BLOCKS.get(), getRange()));
    }

    public void cycleMode() {
        mode = (mode + 1) % 3;
        if (mode == 0) {
            rangeIndex = (rangeIndex + 1) % RANGE_OPTIONS.length;
        }
        setChanged();
    }

    /**
     * The nearest loaded weather radar to a position, or null when none is within {@code maxDistance}.
     *
     * <p>This is what a portable radar links to: it shows a weather radar's picture rather than sweeping a
     * picture of its own, so the nearest one within the player's reach is what it displays.
     */
    public static RadarBlockEntity findNearest(ServerLevel level, Vec3 position, double maxDistance) {
        Map<BlockPos, RadarBlockEntity> radars = SERVER_RADARS.get(level.dimension());
        if (radars == null || radars.isEmpty() || maxDistance <= 0.0D) {
            return null;
        }
        double best = maxDistance * maxDistance;
        RadarBlockEntity nearest = null;
        Iterator<Map.Entry<BlockPos, RadarBlockEntity>> entries = radars.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<BlockPos, RadarBlockEntity> entry = entries.next();
            RadarBlockEntity radar = entry.getValue();
            if (radar.isRemoved() || radar.getLevel() != level || level.getBlockEntity(entry.getKey()) != radar) {
                entries.remove();
                continue;
            }
            BlockPos pos = entry.getKey();
            double distance = position.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
            if (distance < best) {
                best = distance;
                nearest = radar;
            }
        }
        return nearest;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, RadarBlockEntity entity) {
        if (level.isClientSide()) {
            return;
        }
        entity.registerInIndex();
        if (!Config.RADAR_ENABLED.get() || level.getGameTime() % Config.RADAR_UPDATE_INTERVAL_TICKS.get() != 0L) {
            return;
        }
        ServerLevel serverLevel = (ServerLevel) level;
        int range = entity.effectiveRange();
        List<RadarStormSnapshot> snapshots = RadarService.buildSnapshot(serverLevel, pos, range);
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
        // The feed carries the product it was swept with, so a display never has to guess which mode the
        // block is on - and a mode cycled after the chunk loaded still reaches whoever is watching.
        RadarSnapshotPayload payload = new RadarSnapshotPayload(pos, true, entity.getMode(), range, snapshots);
        NetworkSafety.sendToPlayersNear(serverLevel, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, range, payload);
    }

    private void registerInIndex() {
        Level level = getLevel();
        if (level == null) {
            return;
        }
        SERVER_RADARS.computeIfAbsent(level.dimension(), key -> new HashMap<>()).put(getBlockPos(), this);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        Level level = getLevel();
        Map<BlockPos, RadarBlockEntity> radars = level == null ? null : SERVER_RADARS.get(level.dimension());
        if (radars != null) {
            radars.remove(getBlockPos(), this);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Power", power);
        tag.putInt("RangeIndex", rangeIndex);
        tag.putInt("Mode", mode);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        power = tag.getInt("Power");
        rangeIndex = tag.getInt("RangeIndex");
        mode = tag.getInt("Mode");
    }
}
