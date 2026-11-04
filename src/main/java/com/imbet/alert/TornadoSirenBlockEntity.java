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
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A pole-mounted tornado siren.
 *
 * <p>Like the dish siren, it is inert until a switched-on control panel is reachable through wire, and
 * while inert it is silent and visibly still. Where it differs is the warning itself: this siren sounds a
 * continuous tone, so its tone is a looping sound played from the block's own position rather than a
 * chime pushed at the player. The loop is started and stopped by the block entity on the client, driven
 * by the synced sounding flag, which means a player who walks into earshot mid-warning hears the siren
 * already going - the way a siren works - instead of catching a note from the beginning.
 *
 * <p>The motion is tied to the tone because it is the same machine: a mechanical siren's projector turns
 * so its rotor does not just scream in one direction, an electronic siren's lamp flashes on its attack
 * ramp, and a two-tone siren's projectors face opposite ways because the notes alternate between them.
 * The client's animation runs off the tone's own cycle length, so the flash lands on the sound.
 */
public class TornadoSirenBlockEntity extends BlockEntity implements AlertStation {
    /** Degrees the mechanical projector turns each tick while sounding. One turn in six seconds. */
    private static final float ROTATION_DEGREES_PER_TICK = 360.0F / (6.0F * 20.0F);
    /** How often an inert siren checks whether a panel's switch has been thrown. Twice a second. */
    private static final long WIRING_RECHECK_TICKS = 10L;
    /** How far the two-tone siren's yoke rocks from side to side, in degrees. */
    public static final float HILO_ROCK_DEGREES = 24.0F;

    private int mode;
    private int power;
    private boolean wired;
    /** True while an alert this siren is allowed to sound is in force. Synced, and drives the loop. */
    private boolean sounding;
    private AlertLevel alertLevel = AlertLevel.NONE;

    /** Client-only animation. The server never reads these. */
    private float spin;
    private float prevSpin;
    private float beacon;

    public TornadoSirenBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModContent.TORNADO_SIREN_BLOCK_ENTITY.get(), pos, blockState);
    }

    public SirenTone tone() {
        return getBlockState().getBlock() instanceof TornadoSirenBlock siren
                ? siren.tone()
                : SirenTone.MECHANICAL;
    }

    public int getPower() {
        return power;
    }

    public boolean wired() {
        return wired;
    }

    public boolean sounding() {
        return sounding;
    }

    public AlertLevel alertLevel() {
        return alertLevel;
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
     * False on purpose: this siren's warning is the looping tone it plays from its own position, so the
     * network carries only the banner. A station that also rang tones through the player would double it.
     */
    @Override
    public boolean ringsTones() {
        return false;
    }

    @Override
    public String stationKind() {
        return tone().id().toUpperCase() + "_SIREN";
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

    public static void tick(Level level, BlockPos pos, BlockState state, TornadoSirenBlockEntity entity) {
        if (level.isClientSide()) {
            entity.tickAnimation();
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (Config.ALERTS_SIREN_WIRING_REQUIRED.get()) {
            if (level.getGameTime() % WIRING_RECHECK_TICKS == 0L) {
                boolean wired = SirenWiring.isArmed(level, pos, Config.ALERTS_SIREN_MAX_WIRE_LENGTH.get());
                if (wired != entity.wired) {
                    entity.wired = wired;
                    if (!wired) {
                        AlertStationNetwork.removeStation(serverLevel, pos);
                    }
                    entity.setChanged();
                    level.sendBlockUpdated(pos, state, state, 3);
                }
            }
        } else {
            entity.wired = true;
        }
        if (level.getGameTime() % 20L != 0L) {
            return;
        }
        if (!entity.wired || !Config.ALERTS_ENABLED.get() || entity.mode == 2) {
            entity.applyAlert(level, state, AlertLevel.NONE);
            return;
        }
        AlertStationNetwork.recordStation(serverLevel, pos, entity);
        AlertService.AlertResult result = AlertService.highestAlert(serverLevel, Vec3.atCenterOf(pos), entity.mode == 1);
        entity.applyAlert(level, state, result.level());
    }

    private void applyAlert(Level level, BlockState state, AlertLevel level1) {
        boolean shouldSound = level1.activatesEas();
        int newPower = level1.redstonePower();
        boolean changed = shouldSound != sounding || newPower != power || level1 != alertLevel;
        alertLevel = level1;
        sounding = shouldSound;
        if (newPower != power) {
            power = newPower;
            level.updateNeighborsAt(worldPosition, state.getBlock());
        }
        if (changed) {
            setChanged();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    /**
     * Client side: advance the animation, and keep the loop running while the flag says to.
     *
     * <p>The tone only ever changes when the flag does, so the loop is started on the edge rather than
     * checked every tick - a sound instance played every tick would stack up hundreds of copies.
     */
    private void tickAnimation() {
        prevSpin = spin;
        boolean active = sounding && Config.ALERTS_SIREN_TONES.get();
        if (!active) {
            beacon = Mth.lerp(0.14F, beacon, 0.0F);
            SirenTonePlayback.update(this, false);
            return;
        }
        SirenTone tone = tone();
        switch (tone) {
            case MECHANICAL -> spin += ROTATION_DEGREES_PER_TICK;
            case ELECTRONIC -> spin = 0.0F;
            case HILO -> {
                // The yoke rocks one way for the low note and the other for the high note. The recording
                // holds each note for half of its 1.88 second cycle, so one rock out and back is one cycle.
                double tick = level == null ? 0.0D : level.getGameTime();
                double phase = tick / (20.0D * tone.cycleSeconds()) * Math.PI * 2.0D;
                spin = (float) (Math.sin(phase) * HILO_ROCK_DEGREES);
            }
        }
        // The lamp follows the tone: one flash per cycle for the electronic sweep, once per rotor turn for
        // the mechanical wail, once per recorded hi-lo pair for the alternating siren.
        double tick = level == null ? 0.0D : level.getGameTime();
        double cycleTicks = 20.0D * tone.cycleSeconds();
        double flash = 0.5D - 0.5D * Math.cos(tick / cycleTicks * Math.PI * 2.0D);
        beacon = (float) (0.25D + 0.75D * flash);
        SirenTonePlayback.update(this, true);
    }

    public float renderSpin(float partialTick) {
        return Mth.lerp(partialTick, prevSpin, spin);
    }

    /** 0..1: how bright the beacon lamp is, for the renderer to light and tint with. */
    public float renderBeacon(float partialTick) {
        return beacon;
    }

    /** The colour the beacon flashes, taken from what is actually being warned about. */
    public int beaconColor() {
        return alertLevel == AlertLevel.NONE ? AlertLevel.TORNADO_WARNING.color() : alertLevel.color();
    }

    public SoundSource soundSource() {
        return SoundSource.BLOCKS;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            AlertStationNetwork.recordStation(serverLevel, worldPosition, this);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && level.isClientSide()) {
            SirenTonePlayback.update(this, false);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Mode", mode);
        tag.putInt("Power", power);
        tag.putBoolean("Wired", wired);
        tag.putBoolean("Sounding", sounding);
        tag.putString("AlertLevel", alertLevel.name());
        tag.putString("Tone", tone().id());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        mode = tag.getInt("Mode");
        power = tag.getInt("Power");
        // A save from before wiring existed carries no flag; such a siren reads as wired so an existing
        // world does not go silent on update, and the first re-check settles what it really is.
        wired = !tag.contains("Wired") || tag.getBoolean("Wired");
        sounding = tag.getBoolean("Sounding");
        try {
            alertLevel = AlertLevel.valueOf(tag.getString("AlertLevel"));
        } catch (IllegalArgumentException ignored) {
            alertLevel = AlertLevel.NONE;
        }
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
