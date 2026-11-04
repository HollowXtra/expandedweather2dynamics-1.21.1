package com.imbet.weather;

import com.imbet.Config;
import com.imbet.registry.ModContent;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.Locale;
import java.util.UUID;

/**
 * A weather balloon (radiosonde): a latex envelope carrying an instrument package.
 *
 * <p>It is flown for its telemetry. On the way up it radios altitude, temperature, dewpoint, pressure
 * and wind back to whoever launched it, and it reports any storm it flies near. It is carried by the
 * same wind the anemometer reads, so launching one near a tornado can produce a genuine
 * tornado-intercept sounding - usually at the cost of the balloon, which bursts in tornadic winds.
 * At its apex altitude the envelope pops and the package descends under a small parachute, landing
 * where the item can be picked back up.
 *
 * <p>All physics runs on the server; the client only interpolates the visuals (spin, lean, envelope
 * inflation). The synced fields are deliberately small: phase, inflation, lean and whether the
 * balloon died in a tornado.
 */
public class WeatherBalloonEntity extends Entity {
    public static final int PHASE_ASCENT = 0;
    public static final int PHASE_DESCENT = 1;
    public static final int PHASE_LANDED = 2;

    /** 1 mph = 0.44704 m/s, and a Minecraft block is a metre, so this converts to blocks per tick. */
    private static final double MPH_TO_BLOCKS_PER_TICK = 0.44704D / 20.0D;
    /** Standard lapse rate: 6.5 C per kilometre of ascent, expressed per block. */
    private static final double LAPSE_C_PER_BLOCK = 6.5D / 1000.0D;
    private static final double SEA_LEVEL_PRESSURE_HPA = 1013.25D;
    private static final double ATMOSPHERE_SCALE_HEIGHT_BLOCKS = 8000.0D;
    /** Friction layer: near the ground the balloon sees less wind than the windfield reports. */
    private static final double FRICTION_FLOOR_BLOCKS = 12.0D;
    private static final double FRICTION_TOP_BLOCKS = 100.0D;
    /** Terminal horizontal carry, so an extreme windfield cannot teleport the entity per tick. */
    private static final double MAX_DRIFT_BLOCKS_PER_TICK = 1.4D;
    private static final double GROUND_CLEARANCE_BLOCKS = 0.35D;
    /** Envelope radius in blocks at launch and at burst (a real sonde balloons to several metres). */
    private static final float ENVELOPE_RADIUS_LAUNCH = 1.15F;
    private static final float ENVELOPE_RADIUS_APEX = 2.15F;
    /** Below this the balloon is drawn with no envelope: the balloon has popped. */
    private static final double RADIO_RANGE_BLOCKS = 4096.0D;

    private static final EntityDataAccessor<Byte> DATA_PHASE = SynchedEntityData.defineId(WeatherBalloonEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Float> DATA_INFLATION = SynchedEntityData.defineId(WeatherBalloonEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_LEAN_X = SynchedEntityData.defineId(WeatherBalloonEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_LEAN_Z = SynchedEntityData.defineId(WeatherBalloonEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DATA_TORNADIC_BURST = SynchedEntityData.defineId(WeatherBalloonEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_LANDED = SynchedEntityData.defineId(WeatherBalloonEntity.class, EntityDataSerializers.BOOLEAN);

    private static final String[] STAGE_LABELS = {
            "clear", "thunderstorm", "hail core", "forming", "stage 1", "stage 2", "stage 3", "stage 4", "stage 5"
    };
    private static final String[] COMPASS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private UUID launcher;
    private double launchY;
    private double apexY;
    private int soundingTicks;
    private int telemetryCooldown;
    private int telemetryReadings;
    private int landedTicks;
    private boolean initialised;
    private float surfaceTempC;
    private float surfaceHumidity;
    private double surfaceCapeJkg;
    private double surfaceShearMps;
    private double surfaceStormMode;

    private float leanX;
    private float leanZ;
    private float clientSpin;
    private float clientSpinPrev;
    private boolean burstEffectsShown;

    public WeatherBalloonEntity(EntityType<? extends WeatherBalloonEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    public WeatherBalloonEntity(Level level, double x, double y, double z, UUID launcher) {
        this(ModContent.WEATHER_BALLOON_ENTITY.get(), level);
        setPos(x, y, z);
        this.launcher = launcher;
        this.launchY = y;
        this.apexY = y + Config.WEATHER_BALLOON_MAX_ALTITUDE_BLOCKS.get();
        entityData.set(DATA_PHASE, (byte) PHASE_ASCENT);
    }

    // ------------------------------------------------------------------ state

    public int phase() {
        return entityData.get(DATA_PHASE);
    }

    public UUID launcherId() {
        return launcher;
    }

    /**
     * The rendered balloon train reaches several blocks above the entity origin (the envelope hangs
     * above the payload), so the culling box is inflated to cover the envelope or a balloon high
     * overhead would be frustum-culled while it is still in view.
     */
    @Override
    public net.minecraft.world.phys.AABB getBoundingBoxForCulling() {
        return super.getBoundingBoxForCulling().inflate(3.5D, 9.0D, 3.5D);
    }

    /** Client-side only: the train's own slow spin, interpolated for the frame. */
    public float spin(float partialTick) {
        return Mth.lerp(partialTick, clientSpinPrev, clientSpin);
    }

    public float inflation() {
        return entityData.get(DATA_INFLATION);
    }

    /** Synced lean in degrees: how far the train is being bent over by the wind it is flying in. */
    public float leanX() {
        return entityData.get(DATA_LEAN_X);
    }

    public float leanZ() {
        return entityData.get(DATA_LEAN_Z);
    }

    public boolean tornadicBurst() {
        return entityData.get(DATA_TORNADIC_BURST);
    }

    public boolean landed() {
        return entityData.get(DATA_LANDED);
    }

    /** Envelope radius in blocks, growing as ambient pressure drops on the way up. */
    public float envelopeRadius() {
        return Mth.lerp(Mth.clamp(inflation(), 0.0F, 1.0F), ENVELOPE_RADIUS_LAUNCH, ENVELOPE_RADIUS_APEX);
    }

    /** Height above the launch site, in blocks, used for the telemetry readout and the label. */
    public double altitudeBlocks() {
        return Math.max(0.0D, getY() - launchY);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_PHASE, (byte) PHASE_ASCENT);
        builder.define(DATA_INFLATION, 0.0F);
        builder.define(DATA_LEAN_X, 0.0F);
        builder.define(DATA_LEAN_Z, 0.0F);
        builder.define(DATA_TORNADIC_BURST, false);
        builder.define(DATA_LANDED, false);
    }

    // ------------------------------------------------------------------ ticking

    @Override
    public void tick() {
        super.tick();
        soundingTicks++;
        if (level().isClientSide()) {
            clientTick();
            return;
        }
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!initialised) {
            initialised = true;
            initialiseSounding(serverLevel);
        }
        if (soundingTicks > Config.WEATHER_BALLOON_MAX_LIFETIME_TICKS.get()) {
            report(Component.literal("[Sonde] lost: lifetime limit reached at " + formatBlocks(getY()) + ".").withStyle(ChatFormatting.GRAY));
            discard();
            return;
        }

        if (Config.WEATHER_BALLOON_ENABLED.get()) {
            switch (phase()) {
                case PHASE_ASCENT -> tickAscent(serverLevel);
                case PHASE_DESCENT -> tickDescent(serverLevel);
                default -> tickLanded(serverLevel);
            }
        }

        if (telemetryCooldown > 0) {
            telemetryCooldown--;
        } else if (phase() != PHASE_LANDED) {
            telemetryCooldown = Config.WEATHER_BALLOON_TELEMETRY_INTERVAL_TICKS.get();
            sendTelemetry(serverLevel);
        }
    }

    private void tickAscent(ServerLevel level) {
        float inflation = (float) Mth.clamp(altitudeBlocks() / Math.max(8.0D, apexY - launchY), 0.0D, 1.0D);
        entityData.set(DATA_INFLATION, inflation);

        TornadoWindField.WindSample wind = TornadoWindField.sampleStrongest(level, position());
        // A strong updraft genuinely accelerates a rising balloon, so the windfield's own vertical
        // component is added to the free lift - which is what makes a launch into a tornado core
        // shoot upwards before the envelope gives out.
        double updraft = Math.max(0.0D, wind.mphVector().y) * MPH_TO_BLOCKS_PER_TICK * Config.WEATHER_BALLOON_WIND_DRIFT_SCALE.get();
        applyDrift(level, wind, Config.balloonAscentBlocksPerTick() + Math.min(0.5D, updraft));

        if (wind.speedMph() >= Config.WEATHER_BALLOON_BURST_WIND_MPH.get()) {
            burst(level, true, "tornadic winds of " + Math.round(wind.speedMph()) + " mph ripped the envelope apart");
            return;
        }
        if (getY() >= apexY) {
            burst(level, false, "balloon expanded to " + formatBlocks(altitudeBlocks()) + " and burst at its apex");
        }
    }

    private void tickDescent(ServerLevel level) {
        TornadoWindField.WindSample wind = TornadoWindField.sampleStrongest(level, position());
        applyDrift(level, wind, -Config.balloonDescentBlocksPerTick());

        double groundY = groundLevel(level);
        if (Double.isFinite(groundY)) {
            if (getY() <= groundY + GROUND_CLEARANCE_BLOCKS) {
                land(level, groundY);
            }
            return;
        }
        // Unloaded column: do not let the payload sink through the world waiting for a heightmap.
        if (getY() < level.getMinBuildHeight() + 4.0D) {
            report(Component.literal("[Sonde] package lost in unloaded terrain.").withStyle(ChatFormatting.GRAY));
            discard();
        }
    }

    private void tickLanded(ServerLevel level) {
        landedTicks++;
        if (landedTicks > Config.WEATHER_BALLOON_LANDED_LIFETIME_TICKS.get()) {
            discard();
        }
    }

    /**
     * Moves the balloon with the wind and applies its own lift or parachute descent. The horizontal
     * carry is the sampled wind converted from mph, scaled down near the ground where the balloon is
     * still inside the surface friction layer.
     */
    private void applyDrift(ServerLevel level, TornadoWindField.WindSample wind, double verticalBlocksPerTick) {
        double aboveGround = getY() - groundLevel(level);
        if (!Double.isFinite(aboveGround)) {
            aboveGround = altitudeBlocks();
        }
        double friction = Mth.clamp((aboveGround - FRICTION_FLOOR_BLOCKS) / (FRICTION_TOP_BLOCKS - FRICTION_FLOOR_BLOCKS), 0.15D, 1.0D);

        Vec3 nativeVector = wind.mphVector();
        Vec3 drift = new Vec3(nativeVector.x, 0.0D, nativeVector.z).scale(MPH_TO_BLOCKS_PER_TICK * friction * Config.WEATHER_BALLOON_WIND_DRIFT_SCALE.get());
        double driftLength = drift.length();
        if (driftLength > MAX_DRIFT_BLOCKS_PER_TICK) {
            drift = drift.scale(MAX_DRIFT_BLOCKS_PER_TICK / driftLength);
        }

        // A slow thermal wobble keeps the ascent from looking like a lift shaft.
        double wobblePhase = soundingTicks * 0.07D + getId();
        double wobble = 0.02D * Math.sin(wobblePhase);
        Vec3 motion = new Vec3(drift.x + wobble, verticalBlocksPerTick, drift.z + wobble * 0.7D);
        move(MoverType.SELF, motion);
        setDeltaMovement(motion);

        double horizontal = Math.sqrt(drift.x * drift.x + drift.z * drift.z);
        float targetLeanX = horizontal < 1.0E-4D ? 0.0F : (float) (-drift.z / Math.max(horizontal, 1.0E-4D) * Math.min(24.0F, (float) (horizontal * 26.0F)));
        float targetLeanZ = horizontal < 1.0E-4D ? 0.0F : (float) (drift.x / Math.max(horizontal, 1.0E-4D) * Math.min(24.0F, (float) (horizontal * 26.0F)));
        leanX += (targetLeanX - leanX) * 0.08F;
        leanZ += (targetLeanZ - leanZ) * 0.08F;
        entityData.set(DATA_LEAN_X, leanX);
        entityData.set(DATA_LEAN_Z, leanZ);
    }

    private void burst(ServerLevel level, boolean tornadic, String reason) {
        entityData.set(DATA_PHASE, (byte) PHASE_DESCENT);
        entityData.set(DATA_TORNADIC_BURST, tornadic);
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_BREAK, SoundSource.NEUTRAL, 1.6F, tornadic ? 0.8F : 1.5F);
        report(Component.literal(String.format(Locale.ROOT, "[Sonde %.0f m] ", altitudeBlocks()))
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal("BURST - " + reason + (tornadic ? "." : "; parachute deployed.")).withStyle(tornadic ? ChatFormatting.RED : ChatFormatting.GOLD)));
        if (tornadic) {
            reportStormSummaryForLauncher(level, true);
        }
        if (launcher != null) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(launcher);
            if (player != null) {
                AdvancementAwarder.award(player, tornadic ? AdvancementAwarder.TORNADO_INTERCEPT : AdvancementAwarder.FLY_SOUNDING);
            }
        }
    }

    private void land(ServerLevel level, double groundY) {
        setPos(getX(), groundY + GROUND_CLEARANCE_BLOCKS, getZ());
        setDeltaMovement(Vec3.ZERO);
        entityData.set(DATA_PHASE, (byte) PHASE_LANDED);
        entityData.set(DATA_LANDED, true);
        landedTicks = 0;
        report(Component.literal(String.format(Locale.ROOT, "[Sonde] payload down at %.0f, %.0f, %.0f after %.0f m and %d readings.",
                getX(), groundY, getZ(), altitudeBlocks(), telemetryReadings)).withStyle(ChatFormatting.GRAY));
        if (Config.WEATHER_BALLOON_RETURN_ON_LANDING.get()) {
            ItemStack stack = new ItemStack(ModContent.WEATHER_BALLOON_ITEM.get());
            ItemEntity item = new ItemEntity(level, getX(), groundY + 0.6D, getZ(), stack);
            item.setPickUpDelay(20);
            level.addFreshEntity(item);
            discard();
        }
    }

    private void initialiseSounding(ServerLevel level) {
        ThermodynamicProfile profile = ThermodynamicProfileService.compute(level, blockPosition());
        surfaceHumidity = (float) Mth.clamp(profile.moisture(), 0.0D, 1.0D);
        surfaceCapeJkg = profile.capeJkg();
        surfaceShearMps = profile.deepLayerShearMps();
        surfaceStormMode = profile.stormModeIndex();
        float biomeTemperature = level.getBiome(blockPosition()).value().getBaseTemperature();
        surfaceTempC = (float) (biomeTemperature * 15.0D + profile.temperatureModifier() * 8.0D - (level.isRaining() ? 3.0D : 0.0D));
        apexY = Math.min(launchY + Config.WEATHER_BALLOON_MAX_ALTITUDE_BLOCKS.get(), level.getMaxBuildHeight() - 6.0D);
        report(Component.literal(String.format(Locale.ROOT,
                "[Sonde] launched: surface %.1fC, humidity %.0f%%, CAPE %.0f J/kg, shear %.1f m/s, %s. Radios every %.0fs.",
                surfaceTempC,
                surfaceHumidity * 100.0F,
                surfaceCapeJkg,
                surfaceShearMps,
                modeLabel(surfaceStormMode),
                Config.WEATHER_BALLOON_TELEMETRY_INTERVAL_TICKS.get() / 20.0F
        )).withStyle(ChatFormatting.AQUA));
    }

    // ------------------------------------------------------------------ telemetry

    /**
     * One radioed reading: altitude, temperature, dewpoint, pressure and wind from the same wind
     * sampling the balloon is flown by, so the numbers always describe what the balloon is doing.
     */
    private void sendTelemetry(ServerLevel level) {
        if (launcher == null) {
            return;
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(launcher);
        if (player == null || player.level() != level || player.distanceToSqr(this) > RADIO_RANGE_BLOCKS * RADIO_RANGE_BLOCKS) {
            return;
        }
        telemetryReadings++;

        double altitude = altitudeBlocks();
        double temperature = surfaceTempC - altitude * LAPSE_C_PER_BLOCK;
        double dewpoint = temperature - (1.0D - surfaceHumidity) * 22.0D;
        double pressure = SEA_LEVEL_PRESSURE_HPA * Math.exp(-altitude / ATMOSPHERE_SCALE_HEIGHT_BLOCKS);
        TornadoWindField.WindSample wind = TornadoWindField.sampleStrongest(level, position());
        double speedMph = wind.speedMph();
        String from = windDirectionLabel(wind.mphVector());

        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                        "[Sonde %.0f m] %.1fC dew %.1fC, %.0f hPa, wind %.0f mph from %s",
                        altitude, temperature, dewpoint, pressure, speedMph, from))
                .withStyle(phase() == PHASE_DESCENT ? ChatFormatting.GRAY : ChatFormatting.WHITE));

        if (telemetryReadings % 2 == 0) {
            reportStormSummary(player, false);
        }
    }

    private void reportStormSummaryForLauncher(ServerLevel level, boolean force) {
        if (launcher == null) {
            return;
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(launcher);
        if (player == null || player.level() != level) {
            return;
        }
        reportStormSummary(player, force);
    }

    private void reportStormSummary(ServerPlayer player, boolean force) {
        StormReading reading = nearestStorm(player.serverLevel(), Config.WEATHER_BALLOON_STORM_RANGE_BLOCKS.get());
        if (reading == null) {
            return;
        }
        if (!force && reading.stage() < StormObject.STATE_HAIL) {
            return;
        }
        ChatFormatting color = reading.stage() >= StormObject.STATE_FORMING ? ChatFormatting.RED : ChatFormatting.YELLOW;
        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                "[Sonde] %s bearing %s at %.0f m%s",
                reading.label(),
                reading.bearing(),
                reading.distance(),
                reading.stage() >= StormObject.STATE_FORMING ? " - circulating, " + stageLabel(reading.stage()) : ""
        )).withStyle(color));
    }

    private StormReading nearestStorm(ServerLevel level, int range) {
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return null;
        }
        StormObject nearest = null;
        double bestDistance = Double.MAX_VALUE;
        for (WeatherObject object : manager.getStormObjects()) {
            if (!(object instanceof StormObject storm) || storm.pos == null) {
                continue;
            }
            if (DynamicStormController.isAbsorbedOrDecaying(storm)) {
                continue;
            }
            double distance = Math.sqrt(storm.pos.distanceToSqr(getX(), storm.pos.y, getZ()));
            if (distance > range || distance >= bestDistance) {
                continue;
            }
            bestDistance = distance;
            nearest = storm;
        }
        if (nearest == null) {
            return null;
        }
        String label;
        try {
            StormSubtype subtype = StormClassifier.classify(nearest, DynamicStormController.stateFor(nearest));
            label = StormClassifier.labelFor(nearest, subtype);
        } catch (RuntimeException ignored) {
            label = "Storm";
        }
        double dx = nearest.pos.x - getX();
        double dz = nearest.pos.z - getZ();
        return new StormReading(label, bearingLabel(dx, dz), bestDistance, nearest.levelCurIntensityStage);
    }

    /** Sends a lifecycle message (launch, burst, landing) to the player who launched the sonde. */
    private void report(Component message) {
        if (launcher == null || !(level() instanceof ServerLevel level)) {
            return;
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(launcher);
        if (player != null) {
            player.sendSystemMessage(message);
        }
    }

    // ------------------------------------------------------------------ helpers

    private double groundLevel(ServerLevel level) {
        BlockPos column = BlockPos.containing(getX(), getY(), getZ());
        BlockPos surface = BlockGrabSafety.safeSurface(level, column, Heightmap.Types.MOTION_BLOCKING);
        return surface == null ? Double.NaN : surface.getY();
    }

    private static String modeLabel(double stormModeIndex) {
        if (stormModeIndex < 0.30D) {
            return "pulse/single-cell mode";
        }
        if (stormModeIndex < 0.55D) {
            return "multicell mode";
        }
        if (stormModeIndex < 0.75D) {
            return "supercell-capable shear";
        }
        return "supercellular shear";
    }

    private static String stageLabel(int stage) {
        if (stage < 0 || stage >= STAGE_LABELS.length) {
            return "stage " + stage;
        }
        return STAGE_LABELS[stage];
    }

    private static String formatBlocks(double blocks) {
        return String.format(Locale.ROOT, "%.0f m", blocks);
    }

    private static String windDirectionLabel(Vec3 mphVector) {
        if (mphVector == null || mphVector.horizontalDistanceSqr() < 1.0E-6D) {
            return "calm";
        }
        // Meteorological convention: the direction the wind blows FROM.
        return bearingLabel(-mphVector.x, -mphVector.z);
    }

    private static String bearingLabel(double dx, double dz) {
        double degrees = Mth.wrapDegrees(Math.toDegrees(Math.atan2(dx, -dz)));
        if (degrees < 0.0D) {
            degrees += 360.0D;
        }
        return COMPASS[(int) Math.floor((degrees + 22.5D) / 45.0D) & 7] + " (" + String.format(Locale.ROOT, "%03.0f", degrees) + ")";
    }

    private record StormReading(String label, String bearing, double distance, int stage) {
    }

    // ------------------------------------------------------------------ client

    private void clientTick() {
        clientSpinPrev = clientSpin;
        // The spin is purely visual and deliberately not a synced rotation: keeping it local means the
        // server's own rotation packets can never fight it into a stutter.
        clientSpin += phase() == PHASE_ASCENT ? 0.65F : 2.4F;
        if (clientSpin > 360.0F) {
            clientSpin -= 360.0F;
            clientSpinPrev -= 360.0F;
        }
        if (phase() != PHASE_ASCENT && !burstEffectsShown) {
            burstEffectsShown = true;
            popParticles();
        }
    }

    /** Latex scraps and the cord flailing away from the pop. Visual only. */
    private void popParticles() {
        float radius = envelopeRadius();
        for (int i = 0; i < 14; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double outward = 0.18D + random.nextDouble() * 0.35D;
            double cosAngle = Math.cos(angle);
            double sinAngle = Math.sin(angle);
            level().addParticle(
                    ParticleTypes.CLOUD,
                    getX() + cosAngle * radius,
                    getY() + random.nextDouble() * radius * 0.9D,
                    getZ() + sinAngle * radius,
                    cosAngle * outward,
                    0.02D + random.nextDouble() * 0.05D,
                    sinAngle * outward
            );
        }
        for (int i = 0; i < 6; i++) {
            level().addParticle(
                    ParticleTypes.POOF,
                    getX() + (random.nextDouble() - 0.5D) * radius,
                    getY() + (random.nextDouble() - 0.5D) * radius,
                    getZ() + (random.nextDouble() - 0.5D) * radius,
                    0.0D,
                    -0.03D,
                    0.0D
            );
        }
    }

    // ------------------------------------------------------------------ persistence

    @Override
    protected void readAdditionalSaveData(CompoundTag compound) {
        soundingTicks = compound.getInt("SoundingTicks");
        launchY = compound.getDouble("LaunchY");
        apexY = compound.getDouble("ApexY");
        landedTicks = compound.getInt("LandedTicks");
        initialised = compound.getBoolean("Initialised");
        surfaceTempC = compound.getFloat("SurfaceTempC");
        surfaceHumidity = compound.getFloat("SurfaceHumidity");
        surfaceCapeJkg = compound.getDouble("SurfaceCape");
        surfaceShearMps = compound.getDouble("SurfaceShear");
        surfaceStormMode = compound.getDouble("SurfaceMode");
        if (compound.hasUUID("Launcher")) {
            launcher = compound.getUUID("Launcher");
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag compound) {
        compound.putInt("SoundingTicks", soundingTicks);
        compound.putDouble("LaunchY", launchY);
        compound.putDouble("ApexY", apexY);
        compound.putInt("LandedTicks", landedTicks);
        compound.putBoolean("Initialised", initialised);
        compound.putFloat("SurfaceTempC", surfaceTempC);
        compound.putFloat("SurfaceHumidity", surfaceHumidity);
        compound.putDouble("SurfaceCape", surfaceCapeJkg);
        compound.putDouble("SurfaceShear", surfaceShearMps);
        compound.putDouble("SurfaceMode", surfaceStormMode);
        if (launcher != null) {
            compound.putUUID("Launcher", launcher);
        }
    }
}
