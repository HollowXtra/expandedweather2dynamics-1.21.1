package com.imbet.alert;

import com.imbet.Config;
import com.imbet.registry.ModContent;
import com.imbet.weather.AlertLevel;
import com.imbet.weather.AlertService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class WeatherAlertSirenBlockEntity extends BlockEntity implements AlertStation {
    /**
     * Block-local height of the dish's pivot, which is the trunnion at the top of the deployed mast.
     *
     * <p>It lives here rather than in the renderer because it is not only a drawing detail: the server
     * computes the dish's elevation from the target's height relative to it, so the geometry the client
     * draws and the angle the block entity works out have to be the same number.
     */
    public static final float DISH_PIVOT_Y = 1.86F;
    /** How far the dish may swing below the horizon and above it, in degrees of elevation. */
    private static final float MIN_ELEVATION = -6.0F;
    private static final float MAX_ELEVATION = 86.0F;
    /** Degrees the dish sweeps each tick while it has nothing to point at. */
    private static final float SEARCH_SWEEP_DEGREES_PER_TICK = 0.35F;
    /** How much of the remaining aiming error the dish closes each tick. */
    private static final float AIM_EASE = 0.06F;
    /** A storm has to move this far, in blocks, before the client is told again. */
    private static final double TARGET_RESYNC_DISTANCE_SQR = 8.0D * 8.0D;
    /** How often the block entity refreshes its client copy while nothing else is changing, in ticks. */
    private static final long RESYNC_INTERVAL_TICKS = 100L;
    /** Where along the climb the dish starts to unfold: it is stowed for the first part of the rise. */
    private static final float UNFURL_START = 0.60F;
    /** How often an inert siren checks whether a panel's switch has been thrown. Twice a second. */
    private static final long WIRING_RECHECK_TICKS = 10L;

    private int mode;
    private int power;
    private long lastToneTick = -1_000_000L;
    private long currentAlertId = Long.MIN_VALUE;
    private long currentAlertExpiresAt;
    private AlertLevel alertLevel = AlertLevel.NONE;

    /** Ticks the mast has been climbing for, counted on both sides and stored, so a rise survives a reload. */
    private int deployTicks;
    /**
     * Whether a switched-on control panel is reachable through wire. A siren without one is inert: its
     * mast stays stowed, it warns nobody and it never registers with the alert network. Re-checked every
     * few ticks rather than pushed, so a switch thrown at the far end of a run takes effect within half a
     * second with nothing to keep in sync.
     */
    private boolean wired;
    private boolean hasTarget;
    private double targetX;
    private double targetY;
    private double targetZ;
    private long lastSyncTick;
    /** Where the dish is actually pointing now, as a continuous (never wrapped) yaw and an elevation. */
    private float aimYaw;
    private float prevAimYaw;
    private float aimElevation = MAX_ELEVATION;
    private float prevAimElevation = MAX_ELEVATION;

    public WeatherAlertSirenBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModContent.WEATHER_ALERT_SIREN_BLOCK_ENTITY.get(), pos, blockState);
    }

    public int getPower() {
        return power;
    }

    /** True when this siren is wired to a live control panel. Inert sirens warn nobody. */
    public boolean wired() {
        return wired;
    }

    /** 0 = all alerts, 1 = warnings only, 2 = muted. */
    public int mode() {
        return mode;
    }

    @Override
    public int alertMode() {
        return mode;
    }

    @Override
    public double alertRangeBlocks() {
        return Math.max(32.0D, Config.ALERTS_SIREN_RANGE_BLOCKS.get());
    }

    /**
     * False on purpose: the siren rings out of its own block position, so the network delivers its
     * banners silently. Otherwise every alert a player standing beside their own siren receives
     * would arrive with a second set of tones attached.
     */
    @Override
    public boolean ringsTones() {
        return false;
    }

    @Override
    public String stationKind() {
        return "SIREN";
    }

    @Override
    public boolean armed() {
        return wired;
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
        if (level.isClientSide()) {
            entity.tickAnimation();
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        int deployTotal = Math.max(1, Config.ALERTS_SIREN_DEPLOY_TICKS.get());
        // Wiring first, every few ticks: whether a panel's switch has been thrown decides whether this
        // siren is a warning station at all, so it is settled before anything else this tick does.
        if (Config.ALERTS_SIREN_WIRING_REQUIRED.get() && level.getGameTime() % WIRING_RECHECK_TICKS == 0L) {
            boolean wired = SirenWiring.isArmed(level, pos, Config.ALERTS_SIREN_MAX_WIRE_LENGTH.get());
            if (wired != entity.wired) {
                entity.wired = wired;
                if (!wired && level.isLoaded(pos)) {
                    // Disarming drops the station so the registry stops warning for it. Re-arming registers
                    // it again on the next check.
                    AlertStationNetwork.removeStation(serverLevel, pos);
                }
                entity.setChanged();
                level.sendBlockUpdated(pos, state, state, 3);
            }
        } else if (!Config.ALERTS_SIREN_WIRING_REQUIRED.get()) {
            // A world that does not want to build a network gets sirens that simply work.
            entity.wired = true;
        }
        // The mast climbs on the server so its height is a stored fact rather than a local animation:
        // a player who walks up mid-rise, or joins the world, is sent the count in the update tag. An
        // inert siren lowers it again at twice the climb rate: the stowed mast is how "not switched on"
        // reads from outside, and it has to be as legible as the risen one.
        if (!entity.wired) {
            entity.deployTicks = Math.max(0, entity.deployTicks - 2);
        } else if (entity.deployTicks < deployTotal) {
            entity.deployTicks++;
        }
        if (level.getGameTime() % 20L != 0L) {
            return;
        }
        if (!entity.wired) {
            entity.setAlert(level, state, AlertLevel.NONE);
            return;
        }
        // Registered even while alerts are switched off, so the station network is already complete
        // the moment they are switched back on. Registration is what tells the network we exist.
        AlertStationNetwork.recordStation(serverLevel, pos, entity);
        if (!Config.ALERTS_ENABLED.get()) {
            entity.setAlert(level, state, AlertLevel.NONE);
            // A disabled alert still leaves a dish standing there, so it keeps pointing at the sky's
            // nearest storm rather than locking up.
            entity.updateTarget(serverLevel, AlertService.NONE, deployTotal);
            return;
        }
        if (entity.mode == 2) {
            entity.setAlert(level, state, AlertLevel.NONE);
            entity.updateTarget(serverLevel, AlertService.NONE, deployTotal);
            return;
        }
        AlertService.AlertResult result = AlertService.highestAlert(serverLevel, Vec3.atCenterOf(pos), entity.mode == 1);
        entity.setAlert(level, state, result);
        entity.updateTarget(serverLevel, result, deployTotal);
        if (result.level().activatesEas() && Config.ALERTS_FAUX_SOUND.get() && entity.lastToneTick + Config.ALERT_COOLDOWN_TICKS.get() / 3L <= level.getGameTime()) {
            entity.lastToneTick = level.getGameTime();
            float pitch = result.level() == AlertLevel.TORNADO_EMERGENCY ? 0.48F : result.level() == AlertLevel.SEVERE_STORM_WARNING ? 0.74F : 0.55F;
            serverLevel.playSound(null, pos, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.BLOCKS, 1.0F, pitch);
            serverLevel.playSound(null, pos, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 0.8F, pitch + 0.45F);
        }
    }

    /**
     * Points the dish at what the siren is warning about, falling back to the closest storm in range.
     *
     * <p>An alert's position is the storm the product is about, which is the thing a player looking at
     * the dish expects it to be aimed at. With no product in force there is still a sky worth reading, so
     * the dish falls back to proximity and, failing that, sweeps - see {@link #tickAnimation}.
     */
    private void updateTarget(ServerLevel level, AlertService.AlertResult result, int deployTotal) {
        Vec3 target = result.level() != AlertLevel.NONE && result.position() != null
                ? result.position()
                : AlertService.nearestStormPosition(level, Vec3.atCenterOf(worldPosition), Config.ALERTS_SIREN_DISH_RANGE_BLOCKS.get());
        boolean hadTarget = hasTarget;
        boolean changed = (target != null) != hadTarget;
        if (target == null) {
            hasTarget = false;
        } else {
            double moved = hadTarget ? new Vec3(targetX, targetY, targetZ).distanceToSqr(target) : Double.MAX_VALUE;
            changed |= moved > TARGET_RESYNC_DISTANCE_SQR;
            targetX = target.x;
            targetY = target.y;
            targetZ = target.z;
            hasTarget = true;
        }
        boolean rising = deployTicks < deployTotal;
        if (!changed && !rising && level.getGameTime() - lastSyncTick < RESYNC_INTERVAL_TICKS) {
            return;
        }
        lastSyncTick = level.getGameTime();
        setChanged();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    /**
     * The client-side half: the climb is counted locally too, so the rise is smooth, and the dish eases
     * toward the last target the server sent instead of snapping to it when a storm moves.
     */
    private void tickAnimation() {
        int deployTotal = Math.max(1, Config.ALERTS_SIREN_DEPLOY_TICKS.get());
        // The climb is followed locally so the rise is smooth, but an unwired siren stows its mast on the
        // client as well: the flag is part of the synced state, so the stowed read is the same on both
        // sides and a siren that gets switched off visibly lowers its mast instead of sitting raised.
        if (!wired) {
            deployTicks = Math.max(0, deployTicks - 2);
        } else if (deployTicks < deployTotal) {
            deployTicks++;
        }
        prevAimYaw = aimYaw;
        prevAimElevation = aimElevation;
        if (hasTarget) {
            double dx = targetX - (worldPosition.getX() + 0.5D);
            double dz = targetZ - (worldPosition.getZ() + 0.5D);
            double dy = targetY - (worldPosition.getY() + DISH_PIVOT_Y);
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            float desiredYaw = (float) Math.toDegrees(Mth.atan2(dz, dx));
            float desiredElevation = (float) Math.toDegrees(Mth.atan2(dy, Math.max(1.0E-3D, horizontal)));
            // The yaw is kept unwrapped and the error is taken wrapped, so a dish easing from 350 to
            // 10 degrees turns two degrees rather than all the way back around the compass.
            aimYaw += Mth.wrapDegrees(desiredYaw - aimYaw) * AIM_EASE;
            aimElevation += (Mth.clamp(desiredElevation, MIN_ELEVATION, MAX_ELEVATION) - aimElevation) * AIM_EASE;
        } else {
            // Nothing to point at: sweep on from wherever the dish already is. Adding the step to the
            // current angle rather than an absolute search angle is what keeps it from snapping back
            // to the same bearing every time a storm decays.
            aimYaw += SEARCH_SWEEP_DEGREES_PER_TICK;
            aimElevation += (14.0F - aimElevation) * AIM_EASE;
        }
        aimElevation = Mth.clamp(aimElevation, MIN_ELEVATION, MAX_ELEVATION);
    }

    /** 0..1: how far the mast has climbed out of the housing. */
    public float renderDeployProgress(float partialTick) {
        int deployTotal = Math.max(1, Config.ALERTS_SIREN_DEPLOY_TICKS.get());
        return Mth.clamp((deployTicks + partialTick) / (float) deployTotal, 0.0F, 1.0F);
    }

    /** 0..1: how far the dish has unfolded, which only starts once most of the mast is up. */
    public float renderDishOpen(float partialTick) {
        float open = Mth.clamp((renderDeployProgress(partialTick) - UNFURL_START) / (1.0F - UNFURL_START), 0.0F, 1.0F);
        // Smoothstep: the dish settles into place instead of arriving at full size in one frame.
        return open * open * (3.0F - 2.0F * open);
    }

    public float renderAimYaw(float partialTick) {
        return Mth.lerp(partialTick, prevAimYaw, aimYaw);
    }

    public float renderAimElevation(float partialTick) {
        return Mth.lerp(partialTick, prevAimElevation, aimElevation);
    }

    public boolean trackingTarget() {
        return hasTarget;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            AlertStationNetwork.recordStation(serverLevel, worldPosition, this);
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
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Mode", mode);
        tag.putInt("Power", power);
        tag.putLong("LastToneTick", lastToneTick);
        tag.putLong("CurrentAlertId", currentAlertId);
        tag.putLong("CurrentAlertExpiresAt", currentAlertExpiresAt);
        tag.putString("AlertLevel", alertLevel.name());
        tag.putInt("DeployTicks", deployTicks);
        tag.putBoolean("Wired", wired);
        tag.putBoolean("HasTarget", hasTarget);
        tag.putDouble("TargetX", targetX);
        tag.putDouble("TargetY", targetY);
        tag.putDouble("TargetZ", targetZ);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
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
        // The climb only ever goes up: the same tag arrives on a fresh client as a chunk load and on a
        // watching one as a periodic update, and a local count that has run ahead of the server's must
        // not be dragged backwards by a packet that was in flight while the mast was still rising.
        deployTicks = Math.max(deployTicks, tag.getInt("DeployTicks"));
        // A save from before wiring existed carries no flag; such a siren reads as wired so an existing
        // world does not go silent on update, and the first re-check settles what it really is.
        wired = !tag.contains("Wired") || tag.getBoolean("Wired");
        hasTarget = tag.getBoolean("HasTarget");
        targetX = tag.getDouble("TargetX");
        targetY = tag.getDouble("TargetY");
        targetZ = tag.getDouble("TargetZ");
    }

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
