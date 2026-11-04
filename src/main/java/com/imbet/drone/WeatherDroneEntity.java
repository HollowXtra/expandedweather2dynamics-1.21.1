package com.imbet.drone;

import com.imbet.Config;
import com.imbet.drone.WeatherDroneItem;
import com.imbet.registry.ModContent;
import com.imbet.weather.TornadoWindField;
import com.imbet.weather.WeatherInstruments;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;

/**
 * A camera drone you fly into a storm.
 *
 * <p>The flight model is the small version of how the machine actually works, and every part of it earns its
 * place by doing something a simpler model cannot:
 *
 * <ul>
 *   <li><b>Airspeed, not speed.</b> The rotors give the airframe a fixed maximum speed through the
 *       <em>air</em>. Ground track is airspeed plus wind, so a drone flying into a 40 mph inflow makes
 *       almost no progress while the same drone downwind crosses the county. This is the whole reason a
 *       drone is interesting in a storm, and it falls out of adding the wind to the velocity rather than
 *       poking the drone around as a special case.</li>
 *   <li><b>Control authority that collapses.</b> Once the wind exceeds the airframe's airspeed it cannot
 *       hold any course at all: the controls stop mattering and it goes where the air goes. That is the line
 *       between flying in a storm and being debris in one, and it is a threshold rather than a knock, so
 *       there is a moment where the drone is losing and you can still get it out.</li>
 *   <li><b>Hover is a cost.</b> Holding station needs thrust equal to weight, so it drains the battery, and
 *       working the controls drains it at twice that - which is what makes a flight time a budget to spend
 *       rather than a clock to watch. A flat battery is a descent, not a fall.</li>
 * </ul>
 *
 * <p><b>The log.</b> While it flies, the drone records what its own instruments see - peak wind, lowest
 * pressure, how long it spent in wind, and how close it got to a storm - and that log rides back to the
 * player on the drone when it is recalled or recovered. A drone that is destroyed by the wind still leaves
 * the reading it had reached, which is the same deal a tornado probe makes.
 */
public class WeatherDroneEntity extends Entity {
    /** mph to blocks per tick, the same conversion the balloon and the probe use. */
    private static final double MPH_TO_BLOCKS_PER_TICK = 0.44704D / 20.0D;
    /** Blocks per tick squared at Minecraft's gravity, for the free-fall case. */
    private static final double GRAVITY = 0.08D;
    /**
     * How hard the rotors can push, as the fraction of the gap between the airframe's velocity and the
     * velocity the pilot is asking for that the controller closes each tick.
     *
     * <p>A first-order response rather than a force, and the difference is not cosmetic: with a force model
     * plus drag the steady state is thrust/drag, so a 42 mph airframe settles at 49 mph and every
     * ground-track number in the config comments is wrong by that factor. Closing a fraction of the gap each
     * tick has the commanded velocity as its fixed point, which is what makes "ground track is airspeed plus
     * wind" literally true. 0.18 is also the response rate the old force model had, so the feel is unchanged
     * and only the ceiling moves.
     */
    private static final double THRUST_EASE = 0.18D;
    /**
     * How much of the airframe's velocity the air takes off per tick once the rotors stop. Drag is only in
     * the model where it is the only thing left: a flat battery. While the rotors are running the controller
     * has authority over the airframe's velocity and drag is not a separate term.
     */
    private static final double DRAG = 0.18D;
    /** A landing this slow is a landing; anything faster breaks the airframe. */
    private static final double CRASH_SPEED = 0.55D;
    /** Wind above the airframe's limit can still be flown in until it is this much over. */
    private static final double OVERWHELM_GRACE = 1.35D;
    /**
     * How hard the rotors work to do nothing at all, as a fraction of full power.
     *
     * <p>Not zero, and not a detail: hovering is the machine's weight held up by thrust, so a drone sitting
     * still is still working, which is why its rotors sound like it and why its battery drains. This is also
     * the floor the throttle readout has, so a hovering drone reports a quarter of full power rather than
     * silence.
     */
    private static final double HOVER_THROTTLE = 0.28D;
    /** Holding a position costs this much throttle, because station-keeping is thrust against the air. */
    private static final double STATION_KEEPING_THROTTLE = 0.45D;
    /** Battery per tick for sitting in the air, which is holding the airframe's own weight up. */
    private static final int HOVER_DRAIN = 1;
    /** Battery per tick on top of that while the pilot is working the controls. */
    private static final int MANOEUVRE_DRAIN = 1;

    private static final EntityDataAccessor<Optional<UUID>> DATA_PILOT = SynchedEntityData.defineId(WeatherDroneEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Float> DATA_BATTERY = SynchedEntityData.defineId(WeatherDroneEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DATA_LINKED = SynchedEntityData.defineId(WeatherDroneEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_OVERWHELMED = SynchedEntityData.defineId(WeatherDroneEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> DATA_PEAK_WIND = SynchedEntityData.defineId(WeatherDroneEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_MIN_HPA = SynchedEntityData.defineId(WeatherDroneEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_ALOFT = SynchedEntityData.defineId(WeatherDroneEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_THROTTLE = SynchedEntityData.defineId(WeatherDroneEntity.class, EntityDataSerializers.FLOAT);

    /** The pilot's control flags, as last received from the client. Server-side only. */
    private byte controlFlags;
    private int batteryTicks;
    private double ambientHpa = Double.NaN;
    private double minHpa = Double.NaN;
    private double peakWindMph;
    private double secondsInWind;
    /** Closest the airframe got to a storm's position, or -1 when it never came near one. */
    private double closestApproachBlocks = -1.0D;
    /** How hard the rotors are working right now, as a fraction of full power. */
    private double throttleDemand = HOVER_THROTTLE;
    private int ticksAloft;
    private float rotor;
    private float prevRotor;

    public WeatherDroneEntity(EntityType<? extends WeatherDroneEntity> type, Level level) {
        super(type, level);
        batteryTicks = Math.max(1, Config.DRONE_FLIGHT_TICKS.get());
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_PILOT, Optional.empty());
        builder.define(DATA_BATTERY, 1.0F);
        builder.define(DATA_LINKED, false);
        builder.define(DATA_OVERWHELMED, false);
        builder.define(DATA_PEAK_WIND, 0.0F);
        builder.define(DATA_MIN_HPA, Float.NaN);
        builder.define(DATA_ALOFT, 0.0F);
        builder.define(DATA_THROTTLE, (float) HOVER_THROTTLE);
    }

    // ------------------------------------------------------------------ state

    public Optional<UUID> pilotId() {
        return getEntityData().get(DATA_PILOT);
    }

    public boolean isPilotedBy(Player player) {
        return pilotId().map(id -> id.equals(player.getUUID())).orElse(false);
    }

    public boolean linked() {
        return getEntityData().get(DATA_LINKED);
    }

    public boolean overwhelmed() {
        return getEntityData().get(DATA_OVERWHELMED);
    }

    public float batteryFraction() {
        return getEntityData().get(DATA_BATTERY);
    }

    public double peakWindMph() {
        return getEntityData().get(DATA_PEAK_WIND);
    }

    public double minPressureHpa() {
        return getEntityData().get(DATA_MIN_HPA);
    }

    public double secondsAloft() {
        return getEntityData().get(DATA_ALOFT);
    }

    /**
     * How hard the rotors are working, as a fraction of full power, synced for the sound and the animation.
     *
     * <p>This is the airframe's own demand rather than its speed: a drone holding station against a gale and
     * a drone hovering in still air are doing very different amounts of work at the same speed, and the rotor
     * is the thing that sounds like which.
     */
    public float throttle() {
        return getEntityData().get(DATA_THROTTLE);
    }

    /**
     * How far the airframe moved last tick.
     *
     * <p>Position history rather than the velocity field, because a tracked entity's velocity is not sent
     * for every tick - a client watching somebody else's drone sees it move without ever being told how fast
     * it is going, and reading the motion from the movement is what gives the renderer and the sound the
     * right answer on every client rather than only on the pilot's.
     */
    public Vec3 tickMotion() {
        Vec3 displacement = new Vec3(getX() - xo, getY() - yo, getZ() - zo);
        return displacement.lengthSqr() > 1.0E-6D ? displacement : getDeltaMovement();
    }

    /**
     * The log a recovered drone carries, in the same shape a probe's reading takes.
     *
     * <p>Written from the server-side fields rather than from the synched readout, because the readout is
     * rounded for the HUD and synced ten ticks at a time: the log is the measurement, so it is the exact
     * value the instrument reached and not the last one the client happened to be shown.
     */
    public CompoundTag flightLog() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("PeakWindMph", peakWindMph);
        tag.putDouble("MinPressureHpa", minHpa);
        tag.putDouble("AmbientHpa", ambientHpa);
        tag.putDouble("SecondsAloft", ticksAloft / 20.0D);
        tag.putDouble("SecondsInWind", secondsInWind);
        tag.putDouble("ClosestApproach", closestApproachBlocks);
        tag.putFloat("Battery", batteryTicks / (float) Math.max(1, Config.DRONE_FLIGHT_TICKS.get()));
        tag.putBoolean("Lost", false);
        return tag;
    }

    /** The pilot's control flags, one bit per control, set by the serverbound packet. */
    public void setControlFlags(byte flags) {
        this.controlFlags = flags;
    }

    /**
     * Points the airframe where its pilot is looking.
     *
     * <p>The drone's own rotation has to be set from the pilot and cannot be derived: a remote drone has no
     * one riding it, so vanilla never turns its body, and before this the airframe kept whatever yaw it was
     * placed with for its whole flight - which is also why the camera could not be turned, since the client
     * used to aim the view with this very field. Setting the previous angle first is what lets the renderer
     * interpolate: without it the model would snap every tick instead of swinging to the aim.
     */
    public void setPilotLook(float yaw, float pitch) {
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
        this.setYRot(yaw);
        this.setXRot(pitch);
    }

    public void setPilot(Player player) {
        getEntityData().set(DATA_PILOT, player == null ? Optional.empty() : Optional.of(player.getUUID()));
        getEntityData().set(DATA_LINKED, player != null && inRange(player));
    }

    /** True while the player still has the controls in hand, in either hand. */
    private static boolean holdsDroneItem(Player player) {
        return player.getMainHandItem().getItem() instanceof WeatherDroneItem
                || player.getOffhandItem().getItem() instanceof WeatherDroneItem;
    }

    private boolean inRange(Player player) {
        return player != null && distanceTo(player) <= Math.max(16.0D, Config.DRONE_LINK_RANGE_BLOCKS.get());
    }

    /** The pilot, resolved on the server; null when nobody is flying it. */
    private Player resolvePilot() {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        return pilotId().map(serverLevel::getPlayerByUUID).orElse(null);
    }

    // ------------------------------------------------------------------ ticking

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            tickClient();
            return;
        }
        Player pilot = resolvePilot();
        // The link is held by whoever is holding the drone item, and that is decided here rather than on the
        // client: a link is server state, and a client that released it locally would be overruled a tick
        // later anyway.
        if (pilot != null && !holdsDroneItem(pilot)) {
            setPilot(null);
            pilot = null;
        }
        boolean linked = pilot != null && inRange(pilot);
        if (linked != this.linked()) {
            getEntityData().set(DATA_LINKED, linked);
        }
        // A drone nobody is flying holds station on its own, which is what lets you walk away from one and
        // come back to it: it is a machine with a flight controller, not a thrown object.
        boolean powered = batteryTicks > 0;
        if (linked && powered) {
            batteryTicks -= hoverDrain() + controlDrain();
        }
        Vec3 velocity = getDeltaMovement();
        Vec3 wind = windVector();
        double windMph = wind.length() / MPH_TO_BLOCKS_PER_TICK;

        boolean overwhelmed = windMph > maxAirMph() * OVERWHELM_GRACE;
        if (overwhelmed != this.overwhelmed()) {
            // Reported once on the edge rather than every tick, because the interesting moment is the one
            // where the controls stop working, and a player needs to be told that it happened.
            getEntityData().set(DATA_OVERWHELMED, overwhelmed);
        }

        if (!powered) {
            // Flat battery: no thrust at all, so it comes down. A fall is how a drone ends when it runs out.
            velocity = velocity.scale(1.0D - DRAG).add(0.0D, -GRAVITY, 0.0D);
            throttleDemand = 0.0D;
        } else {
            Vec3 target;
            if (overwhelmed) {
                // Control authority is gone: the airframe goes where the air goes and every input is ignored.
                // The rotors are still at their limit - that is precisely what being overwhelmed means, the
                // machine is doing everything it can and losing - so the throttle stays wide open.
                target = wind;
                throttleDemand = 1.0D;
            } else if (!linked) {
                // No link: the flight controller holds position, so the airframe comes to a stop against the
                // wind rather than drifting off. Station-keeping is what makes a drone you can walk away from
                // and come back to, and it costs battery the whole time it is fighting the air.
                target = Vec3.ZERO;
                throttleDemand = STATION_KEEPING_THROTTLE;
            } else {
                Vec3 ask = commanded(pilot);
                target = ask.add(wind);
                // Whatever the pilot asked for, measured against what the airframe can actually give, with a
                // hovering floor: the rotors hold the machine up before they move it anywhere.
                double capacity = (maxAirMph() + Config.DRONE_CLIMB_MPH.get()) * MPH_TO_BLOCKS_PER_TICK;
                throttleDemand = HOVER_THROTTLE
                        + (1.0D - HOVER_THROTTLE) * Mth.clamp(ask.length() / capacity, 0.0D, 1.0D);
            }
            velocity = velocity.lerp(target, THRUST_EASE);
        }
        setDeltaMovement(velocity);

        Vec3 before = position();
        move(net.minecraft.world.entity.MoverType.SELF, velocity);
        Vec3 after = position();
        double travelled = after.distanceTo(before);
        double intended = velocity.length();
        // A collision is the difference between where the airframe was going and where it got to. Hit
        // anything hard and the airframe is done; a soft bump simply stops it.
        if (intended > 1.0E-4D && travelled < intended * 0.55D && velocity.length() > CRASH_SPEED) {
            crash();
            return;
        }
        if (after.y <= level().getMinBuildHeight() + 1.0D) {
            crash();
            return;
        }

        recordStorm(velocity, windMph);
        ticksAloft++;
        getEntityData().set(DATA_ALOFT, (float) (ticksAloft / 20.0D));
        if (ticksAloft % 10 == 0) {
            syncReadout();
        }
    }

    /** How much battery holding station costs, as ticks per tick. */
    private int hoverDrain() {
        return HOVER_DRAIN;
    }

    /** Extra drain while the pilot is commanding it, because thrust is what costs current. */
    private int controlDrain() {
        return controlFlags == 0 ? 0 : MANOEUVRE_DRAIN;
    }

    private double maxAirMph() {
        return Config.DRONE_MAX_AIRSPEED_MPH.get();
    }

    /** The velocity the rotors are trying to reach, from the pilot's look and their control flags. */
    private Vec3 commanded(Player pilot) {
        double airspeed = maxAirMph() * MPH_TO_BLOCKS_PER_TICK;
        float forward = (controlFlags & FLAG_FORWARD) != 0 ? 1.0F : (controlFlags & FLAG_BACK) != 0 ? -1.0F : 0.0F;
        float strafe = (controlFlags & FLAG_RIGHT) != 0 ? 1.0F : (controlFlags & FLAG_LEFT) != 0 ? -1.0F : 0.0F;
        // The drone flies where the pilot is looking. The pitch decides how much of the airspeed goes
        // forward and how much goes up, which is what makes pointing the camera the steering.
        float yaw = pilot.getYRot();
        float pitch = pilot.getXRot();
        double pitchRadians = Math.toRadians(pitch);
        double yawRadians = Math.toRadians(yaw);
        double moveForward = Math.cos(pitchRadians);
        double moveUp = -Math.sin(pitchRadians);
        // Look direction in world space: vanilla's yaw runs clockwise from +Z.
        double lookX = -Math.sin(yawRadians) * moveForward;
        double lookZ = Math.cos(yawRadians) * moveForward;
        double vertical = (controlFlags & FLAG_UP) != 0 ? 0.75D : (controlFlags & FLAG_DOWN) != 0 ? -0.75D : moveUp;
        double horizontalScale = forward != 0.0F ? 1.0D : 0.0D;
        Vec3 along = new Vec3(lookX * forward * horizontalScale, 0.0D, lookZ * forward * horizontalScale);
        Vec3 across = new Vec3(-lookZ * strafe, 0.0D, lookX * strafe);
        Vec3 horizontal = along.add(across);
        if (horizontal.lengthSqr() > 1.0D) {
            horizontal = horizontal.normalize();
        }
        // Hover is the neutral state, and it is zero rather than a thrust that has to be balanced: with a
        // level view there is no vertical input at all, so the controller's fixed point is "no vertical
        // motion". Weight is what that zero already pays for, which is why climbing at the configured rate
        // means exactly the configured rate and not a thrust divided by something.
        double climb = vertical * Config.DRONE_CLIMB_MPH.get() * MPH_TO_BLOCKS_PER_TICK;
        return horizontal.scale(airspeed).add(0.0D, climb, 0.0D);
    }

    /** The air mass the drone is sitting in, as blocks per tick. */
    private Vec3 windVector() {
        // The strongest wind the field sampler can find at this position, in mph, converted to the blocks per
        // tick the airframe's own motion is measured in.
        TornadoWindField.WindSample sample = WeatherInstruments.wind(level(), position());
        Vec3 mph = sample == null ? Vec3.ZERO : sample.mphVector();
        return mph.scale(MPH_TO_BLOCKS_PER_TICK * Config.DRONE_WIND_COUPLING.get());
    }

    /** Records what the drone's own instruments see, which is what a recovered log is made of. */
    private void recordStorm(Vec3 velocity, double windMph) {
        if (windMph >= Math.max(10.0D, Config.DRONE_ARM_MPH.get())) {
            secondsInWind += 0.05D;
            peakWindMph = Math.max(peakWindMph, windMph);
            if (level() instanceof ServerLevel serverLevel) {
                double hpa = WeatherInstruments.pressure(serverLevel, position()).hpa();
                if (Double.isNaN(ambientHpa)) {
                    ambientHpa = hpa;
                }
                minHpa = Double.isNaN(minHpa) ? hpa : Math.min(minHpa, hpa);
                var storm = WeatherInstruments.nearestStorm(serverLevel, position(), 512.0D);
                if (storm != null && storm.distanceBlocks() >= 0.0D) {
                    closestApproachBlocks = closestApproachBlocks < 0.0D
                            ? storm.distanceBlocks()
                            : Math.min(closestApproachBlocks, storm.distanceBlocks());
                }
            }
        }
    }

    private void syncReadout() {
        getEntityData().set(DATA_THROTTLE, (float) throttleDemand);
        getEntityData().set(DATA_BATTERY, Mth.clamp(batteryTicks / (float) Math.max(1, Config.DRONE_FLIGHT_TICKS.get()), 0.0F, 1.0F));
        getEntityData().set(DATA_PEAK_WIND, (float) peakWindMph);
        getEntityData().set(DATA_MIN_HPA, Double.isNaN(minHpa) ? Float.NaN : (float) minHpa);
    }

    /** The airframe is destroyed: it drops its log for whoever finds it. */
    private void crash() {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        ItemStack wreck = new ItemStack(ModContent.WEATHER_DRONE_ITEM.get());
        WeatherDroneItem.attachLog(wreck, flightLog());
        spawnAtLocation(wreck);
        playSound(net.minecraft.sounds.SoundEvents.ITEM_BREAK, 1.4F, 0.85F);
        discard();
    }

    /**
     * The rotor angle is computed on whichever side is drawing it and never synced: it is a degree count
     * that only a renderer reads, and putting it in synched data would dirty the entity's data every tick to
     * carry a number the server has no opinion about.
     */
    private void tickClient() {
        prevRotor = rotor;
        // The rotor's speed is the airframe's own effort - the same throttle the sound is pitched from - so
        // the thing a player sees and the thing they hear are the same number. A rotor with a flat battery
        // coasts, and one that has been overwhelmed is spun by the air rather than by its motors.
        boolean spinning = batteryFraction() > 0.0F;
        float effort = spinning ? 0.35F + 0.9F * Mth.clamp(throttle(), 0.0F, 1.0F) : 0.2F;
        rotor = (rotor + effort * (overwhelmed() ? 1.3F : 1.0F) * 62.0F) % 360.0F;
    }

    public float renderRotor(float partialTick) {
        return Mth.lerp(partialTick, prevRotor, rotor);
    }

    // ------------------------------------------------------------------ interaction

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (isPilotedBy(player)) {
            setPilot(null);
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "Drone released - it will hold station here. Battery " + Math.round(batteryFraction() * 100.0F) + "%"), true);
        } else {
            setPilot(player);
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "Drone linked. Look where you want to go; your movement keys fly it."), true);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        if (level().isClientSide()) {
            return false;
        }
        crash();
        return true;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    // ------------------------------------------------------------------ persistence

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        batteryTicks = tag.contains("Battery") ? tag.getInt("Battery") : Math.max(1, Config.DRONE_FLIGHT_TICKS.get());
        ambientHpa = tag.contains("AmbientHpa") ? tag.getDouble("AmbientHpa") : Double.NaN;
        minHpa = tag.contains("MinHpa") ? tag.getDouble("MinHpa") : Double.NaN;
        peakWindMph = tag.getDouble("PeakWindMph");
        secondsInWind = tag.getDouble("SecondsInWind");
        closestApproachBlocks = tag.contains("ClosestApproach") ? tag.getDouble("ClosestApproach") : -1.0D;
        ticksAloft = tag.getInt("TicksAloft");
        if (tag.hasUUID("Pilot")) {
            getEntityData().set(DATA_PILOT, Optional.of(tag.getUUID("Pilot")));
        }
        syncReadout();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Battery", batteryTicks);
        tag.putDouble("AmbientHpa", ambientHpa);
        tag.putDouble("MinHpa", minHpa);
        tag.putDouble("PeakWindMph", peakWindMph);
        tag.putDouble("SecondsInWind", secondsInWind);
        tag.putDouble("ClosestApproach", closestApproachBlocks);
        tag.putInt("TicksAloft", ticksAloft);
        pilotId().ifPresent(id -> tag.putUUID("Pilot", id));
    }

    /** True when the battery is flat: the drone is still there, but it is not going anywhere. */
    public boolean flatBattery() {
        return batteryTicks <= 0;
    }

    // ------------------------------------------------------------------ control flags

    public static final byte FLAG_FORWARD = 1;
    public static final byte FLAG_BACK = 2;
    public static final byte FLAG_LEFT = 4;
    public static final byte FLAG_RIGHT = 8;
    public static final byte FLAG_UP = 16;
    public static final byte FLAG_DOWN = 32;

    /** Its own name for the crosshair readout, so the HUD and the logs agree. */
    public String statusLine() {
        StringBuilder builder = new StringBuilder();
        builder.append("Battery ").append(Math.round(batteryFraction() * 100.0F)).append('%');
        if (overwhelmed()) {
            builder.append("  OVERWHELMED BY WIND");
        } else if (!linked()) {
            builder.append("  NO LINK");
        }
        if (flatBattery()) {
            builder.append("  BATTERY FLAT");
        }
        return builder.toString();
    }
}
