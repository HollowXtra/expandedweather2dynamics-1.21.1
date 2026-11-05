package com.imbet.client;

import com.imbet.Config;
import com.imbet.registry.ModContent;
import com.imbet.weather.AddonWeatherEvent;
import com.imbet.weather.ClientAddonWeatherEventStore;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.DynamicStormState;
import com.imbet.weather.StormSubtype;
import com.imbet.weather.TornadoDamageModel;
import com.imbet.weather.TornadoWindField;
import extendedrenderer.particle.ParticleRegistry;
import extendedrenderer.particle.entity.EntityRotFX;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import weather2.ClientTickHandler;
import weather2.weathersystem.WeatherManagerClient;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;
import weather2.weathersystem.tornado.simple.TornadoFunnelSimple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class TornadoFearClient {
    private static float fearIntensity;
    private static float targetFearIntensity;
    private static float insideDamagePathIntensity;
    private static float insideCoreIntensity;
    private static float insideEyeIntensity;
    private static float whiteoutIntensity;
    private static float tumbleIntensity;
    private static float tumbleFlipAccum;
    private static boolean tumbleSpinning;
    private static boolean cameraTumbleSeeded;
    private static float cameraRollAccum;
    private static float cameraPitchAccum;
    private static float cameraYawAccum;
    private static float cameraRollSpeed;
    private static float cameraPitchSpeed;
    private static float cameraYawSpeed;
    /**
     * Per-entity roll (Z axis) state for captured models. Living entities have no roll
     * field, so the renderer mixin applies this as a pose rotation around the body's
     * vertical axis. With the flip (pitch) that is applied to the entity's own rotation, this is
     * what makes captured players and mobs tumble the way the grabbed block debris does; the yaw
     * is untouched, deliberately - see {@link #flipCapturedPlayer}.
     */
    private static final Map<UUID, TumbleRollState> TUMBLE_ROLLS = new HashMap<>();
    /** Inside this many blocks of the funnel's wall the roar is at full strength, whatever the range knob is. */
    private static final double ROAR_REFERENCE_BLOCKS = 12.0D;
    private static FearSample activeSample = FearSample.NONE;
    private static TornadoRoarSound roarSound;
    /** The air-absorbed roar that carries the distance: the same loop, with the top end gone. */
    private static TornadoRoarSound roarFarSound;
    private static TornadoHumSound humSound;
    /** The broad wind bed carried under the roar, so a distant tornado is not silence. */
    private static TornadoWindBedSound windBedSound;
    private static int particlesThisTick;
    private static long nextImpactSoundTick;
    /**
     * Ambient brightness the fog colour was last driven with, 0.30 at night to 1.0 in daylight. The dust
     * volume is multiplied by it so the 3D dust cannot glow white at night while the fog it composes with
     * goes dark - the two have to agree or the volume reads as a separate layer pasted over the world.
     */
    private static float frameAmbient = 1.0F;

    private TornadoFearClient() {
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || !Config.TORNADO_IMMERSION_ENABLED.get()) {
            reset(minecraft);
            return;
        }

        activeSample = findStrongestSample(minecraft.level, minecraft.player);
        targetFearIntensity = activeSample.intensity();
        float smoothing = targetFearIntensity > fearIntensity ? 0.16F : 0.055F;
        fearIntensity = Mth.lerp(smoothing, fearIntensity, targetFearIntensity);
        if (fearIntensity < 0.01F && targetFearIntensity < 0.01F) {
            fearIntensity = 0.0F;
        }
        updateInsideState();
        flipCapturedPlayer(minecraft);
        tickCameraTumble(minecraft);

        tickRoar(minecraft);
        tickWindBed(minecraft);
        tickHum(minecraft);
        tickImpactSounds(minecraft.level, minecraft.player);
        particlesThisTick = 0;
        if (Config.TORNADO_IMMERSION_CLOSE_DEBRIS.get() && shouldSpawnCloseDebris()) {
            spawnCloseDebris(minecraft.level, minecraft.player);
        }
    }

    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!canMoveCamera()) {
            return;
        }
        double tick = Minecraft.getInstance().level == null ? 0.0D : Minecraft.getInstance().level.getGameTime() + event.getPartialTick();
        float strength = rumbleStrength();
        float maxRoll = Config.TORNADO_IMMERSION_MAX_CAMERA_ROLL_DEGREES.get().floatValue();
        float roll = (float) (Math.sin(tick * 0.86D) * maxRoll * strength + Math.sin(tick * 2.17D) * maxRoll * 0.32F * strength);
        float yaw = (float) (Math.sin(tick * 1.43D + 2.0D) * 0.34F * strength);
        float pitch = (float) (Math.cos(tick * 1.08D) * 0.24F * strength);
        event.setRoll(event.getRoll() + roll);
        event.setYaw(event.getYaw() + yaw);
        event.setPitch(event.getPitch() + pitch);
        if (Config.TORNADO_IMMERSION_CAMERA_TUMBLE.get() && tumbleIntensity > 0.01F) {
            float tumble = tumbleIntensity;
            float maxTumbleRoll = Config.TORNADO_IMMERSION_MAX_TUMBLE_ROLL_DEGREES.get().floatValue();
            event.setRoll(event.getRoll() + (float) (Math.sin(tick * 1.32D) * maxTumbleRoll * tumble + Math.sin(tick * 3.71D) * maxTumbleRoll * 0.38F * tumble));
            event.setPitch(event.getPitch() + (float) (Math.cos(tick * 1.94D) * maxTumbleRoll * 0.42F * tumble));
            if (Minecraft.getInstance().options.getCameraType().isFirstPerson()) {
                // continuous somersault: the view rolls over while captured, with a
                // slow drifting pitch - both seeded randomly per capture so every
                // tornado ride tumbles differently (third person gets the real
                // model tumble instead, so this stays first-person only)
                //
                // The yaw here is a CAMERA offset and is never written back to the player: the view still
                // rides around the funnel, but the head and body keep the facing the mouse gave them, so
                // the model itself never spins. Because the whole offset is scaled by the capture
                // intensity it eases away to nothing, returning the view to the direction the player was
                // already aiming instead of leaving them pointed wherever the spin finished.
                event.setYaw(event.getYaw() + cameraYawAccum * tumble);
                event.setRoll(event.getRoll() + cameraRollAccum * tumble);
                event.setPitch(event.getPitch() + cameraPitchAccum * tumble);
            }
        }
    }

    /**
     * Flips the LOCAL player's actual rotation while captured. The client is authoritative on its own
     * rotation, so the server can never make this stick (movement packets overwrite it every tick).
     *
     * Only the FLIP is written here, and only in third person, where the character is actually on screen:
     * the model turns end over end at a per-player randomized rate while the renderer rolls it about its
     * own axis. The YAW is deliberately never written - not here and not for mobs - and that is the one
     * thing about this effect that is easy to get wrong. Yaw turns a model about its own vertical axis,
     * head and body together, so accumulating it does not read as tumbling at all: it reads as the entity
     * being spun on the spot by the ankle, which is a motion nothing in a tornado makes. It also fights the
     * player's own aim, which the client rewrites from mouse input every tick. Pitch and roll alone give
     * the tumble, and they leave the model pointing where the player is pointing.
     *
     * First person has no model to flip, so nothing here applies: the ride is carried by the camera in
     * {@link #tickCameraTumble}, where even the view's spin is a camera-only yaw offset rather than a
     * rotation of the player's head and body.
     */
    private static void flipCapturedPlayer(Minecraft minecraft) {
        if (!Config.TORNADO_IMMERSION_CAMERA_TUMBLE.get()
                || Config.TORNADO_IMMERSION_ACCESSIBILITY_REDUCED_MOTION.get()
                || tumbleIntensity <= 0.01F
                || !(minecraft.player instanceof net.minecraft.client.player.LocalPlayer player)
                || minecraft.options.getCameraType().isFirstPerson()) {
            tumbleSpinning = false;
            return;
        }
        // Third person: the end-over-end flip at a rate seeded from the player's UUID so some tumble one
        // way and some the other, plus the renderer's roll about the body axis.
        if (!tumbleSpinning) {
            tumbleFlipAccum = player.getXRot();
            tumbleSpinning = true;
        }
        int hash = player.getUUID().hashCode();
        float pitchDirection = ((hash >> 1) & 1) == 0 ? 1.0F : -1.0F;
        float speed = (4.5F + tumbleIntensity * 8.0F) * Config.tumbleSpeedMultiplier();
        float previousPitch = tumbleFlipAccum;
        tumbleFlipAccum = (tumbleFlipAccum + speed * 0.78F * pitchDirection) % 360.0F;
        if (tumbleFlipAccum < 0.0F) {
            tumbleFlipAccum += 360.0F;
        }
        player.xRotO = previousPitch;
        player.setXRot(tumbleFlipAccum);
    }

    /**
     * First-person camera somersault: a slow CONTINUOUS roll-over plus a drifting pitch and
     * yaw spin, all seeded randomly each capture so every tornado ride tumbles differently.
     * Third person doesn't use this - there the model itself flips and rolls instead.
     *
     * Every term here is a camera-space offset. Nothing in this path touches the player entity,
     * which is what keeps the captured player's head and body from spinning.
     */
    private static void tickCameraTumble(Minecraft minecraft) {
        if (minecraft.level == null) {
            return;
        }
        boolean active = Config.TORNADO_IMMERSION_CAMERA_TUMBLE.get()
                && !Config.TORNADO_IMMERSION_ACCESSIBILITY_REDUCED_MOTION.get()
                && tumbleIntensity > 0.01F;
        if (!active) {
            cameraTumbleSeeded = false;
            cameraRollAccum = 0.0F;
            cameraPitchAccum = 0.0F;
            cameraYawAccum = 0.0F;
            return;
        }
        if (!cameraTumbleSeeded) {
            RandomSource random = minecraft.level.random;
            cameraRollSpeed = (2.2F + random.nextFloat() * 3.4F) * (random.nextBoolean() ? 1.0F : -1.0F);
            cameraPitchSpeed = (0.5F + random.nextFloat() * 1.2F) * (random.nextBoolean() ? 1.0F : -1.0F);
            cameraYawSpeed = Config.TORNADO_IMMERSION_TUMBLE_SPIN_DEGREES.get().floatValue() * 0.05F
                    * (0.4F + tumbleIntensity * 0.6F) * Config.tumbleSpeedMultiplier()
                    * (random.nextBoolean() ? 1.0F : -1.0F);
            cameraTumbleSeeded = true;
        }
        float tumbleRate = (0.35F + tumbleIntensity * 0.65F) * Config.tumbleSpeedMultiplier();
        cameraRollAccum = (cameraRollAccum + cameraRollSpeed * tumbleRate) % 360.0F;
        if (cameraRollAccum < 0.0F) {
            cameraRollAccum += 360.0F;
        }
        // Keep a floor on the spin: tumbleIntensity is small for most captures (it scales with how deep
        // in the core you are), and letting the intensity scale the whole rate made the view crawl right
        // when you get grabbed. This is the speed the view turns at; how much of it you see is decided in
        // onCameraAngles, where the offset is scaled by the same capture intensity as the roll.
        cameraYawAccum = (cameraYawAccum + cameraYawSpeed) % 360.0F;
        if (cameraYawAccum < 0.0F) {
            cameraYawAccum += 360.0F;
        }
        cameraPitchAccum = Mth.clamp(cameraPitchAccum + cameraPitchSpeed * tumbleRate, -30.0F, 30.0F);
    }

    /**
     * Whether a player should get the tornado tumble/roll at all. Survival only - creative and
     * spectator players fly out of the funnel at will, so somersaulting their camera and model
     * there just fights them. Mobs and other entities are unaffected by this. The local player is
     * checked against the exact game mode; other players (whose mode is not sent) fall back to the
     * synced creative/spectator abilities.
     */
    public static boolean tumblesInGameMode(Player player) {
        if (player == null || player.isSpectator() || player.isCreative()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (player == minecraft.player && minecraft.gameMode != null) {
            return minecraft.gameMode.getPlayerMode() == GameType.SURVIVAL;
        }
        return true;
    }

    /**
     * Roll (Z axis) for a captured entity's rendered model, in degrees. Living entities
     * have no roll field, so the renderer mixin applies this as a pose rotation around
     * the body's vertical axis - with the flip on pitch, that is what makes captured
     * players and mobs tumble the way the grabbed blocks do. Returns 0 for entities that
     * are not captured.
     */
    public static float modelRollFor(LivingEntity entity) {
        if (entity == null || !(entity.level() instanceof ClientLevel level)) {
            return 0.0F;
        }
        long tick = level.getGameTime();
        UUID id = entity.getUUID();
        TumbleRollState state = TUMBLE_ROLLS.get(id);
        if (state == null) {
            state = new TumbleRollState(tick, 0.0F, 0.0F);
            TUMBLE_ROLLS.put(id, state);
        }
        if (state.tick() != tick) {
            float ramp = captureRampFor(entity, level);
            float roll = state.roll();
            float speed = state.speed();
            if (ramp > 0.01F) {
                if (speed == 0.0F) {
                    int hash = id.hashCode() ^ (int) (tick & 0xFFFFL);
                    float direction = (hash & 1) == 0 ? 1.0F : -1.0F;
                    float rate = 3.5F + 6.5F * ((hash >> 3) & 0xF) / 15.0F;
                    speed = direction * rate * (0.7F + ramp * 0.5F);
                }
                // The multiplier is applied here rather than baked into the seeded rate above, so
                // turning it up speeds a tumble that is already in progress.
                roll = (roll + speed * Config.tumbleSpeedMultiplier()) % 360.0F;
                if (roll < 0.0F) {
                    roll += 360.0F;
                }
            } else {
                // released: ease the roll back to 0 so the model rights itself
                speed = 0.0F;
                roll *= 0.80F;
            }
            state = new TumbleRollState(tick, roll, speed);
            TUMBLE_ROLLS.put(id, state);
            if (TUMBLE_ROLLS.size() > 4096) {
                TUMBLE_ROLLS.clear();
            }
        }
        return state.roll();
    }

    /**
     * Client-side capture ramp for a rendered entity, mirroring the server's mob gate
     * (80+ mph core, same orbit geometry) so the render roll lines up with the synced flip.
     * The local player uses the already-computed tumble intensity so the model roll matches
     * its client-side flip.
     */
    private static float captureRampFor(LivingEntity entity, ClientLevel level) {
        if (!Config.WINDFIELD_PLAYER_LAUNCH_ENABLED.get()) {
            return 0.0F;
        }
        Player local = Minecraft.getInstance().player;
        if (entity == local) {
            // tumbleIntensity is already forced to 0 outside survival
            return Config.TORNADO_IMMERSION_CAMERA_TUMBLE.get() ? tumbleIntensity : 0.0F;
        }
        if (entity instanceof Player otherPlayer && !tumblesInGameMode(otherPlayer)) {
            return 0.0F;
        }
        double gate = TornadoDamageModel.mobCaptureGateMph();
        Vec3 position = entity.position();
        double best = 0.0D;
        WeatherManagerClient manager = ClientTickHandler.weatherManager;
        if (manager != null) {
            for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
                if (!(object instanceof StormObject storm) || storm.pos == null
                        || !DynamicStormController.isDynamicVortex(storm)
                        || DynamicStormController.isAbsorbedOrDecaying(storm)) {
                    continue;
                }
                if (horizontalDistance(position, storm.pos) < 64.0D) {
                    TornadoWindField.WindSample wind = TornadoWindField.sample(storm, level, position);
                    double localWindMph = Math.max(wind.speedMph(), wind.strongestLocalMph());
                    best = Math.max(best, TornadoDamageModel.captureRamp(wind, localWindMph, gate));
                }
                DynamicStormState state = DynamicStormController.stateFor(storm);
                for (DynamicStormState.TornadoCirculationState satellite : state.satelliteTornadoes()) {
                    Vec3 center = DynamicStormController.satelliteCenter(storm, satellite, storm.pos.y);
                    if (horizontalDistance(position, center) < 48.0D) {
                        TornadoWindField.WindSample wind = TornadoWindField.sample(storm, level, position);
                        double localWindMph = Math.max(wind.speedMph(), wind.strongestLocalMph());
                        best = Math.max(best, TornadoDamageModel.captureRamp(wind, localWindMph, gate));
                    }
                }
            }
        }
        if (best <= 0.0D) {
            best = tossedRamp(entity, manager);
        }
        return (float) Mth.clamp(best, 0.0D, 1.0D);
    }

    /**
     * Weather2's OWN pick-up system runs alongside ours with completely different thresholds: it
     * grabs entities from the forming stage up on its own pull radius, and its "tornado_shoot"
     * branch flings entities at players. Anything it carries never passes our wind gate, so the
     * model roll never switched on and those entities flew rigidly. An airborne entity that is
     * actually moving inside a tornado's own pull radius is treated as tossed and tumbles too.
     * The cheap motion test runs first so ordinary mobs cost nothing here.
     */
    private static double tossedRamp(LivingEntity entity, WeatherManagerClient manager) {
        if (manager == null || entity.onGround()
                || (entity instanceof Player player && !tumblesInGameMode(player))) {
            return 0.0D;
        }
        Vec3 motion = entity.getDeltaMovement();
        double horizontal = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        if (horizontal < 0.14D && Math.abs(motion.y) < 0.24D) {
            return 0.0D;
        }
        Vec3 position = entity.position();
        double speed = Math.sqrt(horizontal * horizontal + motion.y * motion.y);
        double best = 0.0D;
        for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
            if (!(object instanceof StormObject storm) || storm.pos == null
                    || !DynamicStormController.isDynamicVortex(storm)
                    || DynamicStormController.isAbsorbedOrDecaying(storm)) {
                continue;
            }
            if (horizontalDistance(position, storm.pos) > entityPullDistance(storm)) {
                continue;
            }
            best = Math.max(best, Mth.clamp(speed * 2.2D - 0.30D, 0.0D, 0.85D));
        }
        return best;
    }

    /** Weather2's own entity pull radius for this storm, falling back to the funnel's width. */
    private static double entityPullDistance(StormObject storm) {
        TornadoFunnelSimple funnel = storm.getTornadoFunnelSimple();
        if (funnel != null && funnel.getConfig() != null && funnel.getConfig().getEntityPullDistXZ() > 1.0F) {
            return funnel.getConfig().getEntityPullDistXZ();
        }
        return Math.max(10.0D, DynamicStormController.baseRadiusForWind(storm) * 1.4D);
    }

    public static void onFov(ViewportEvent.ComputeFov event) {
        if (!Config.TORNADO_IMMERSION_ENABLED.get() || !Config.TORNADO_IMMERSION_FOV_PRESSURE.get() || Config.TORNADO_IMMERSION_ACCESSIBILITY_REDUCED_MOTION.get()) {
            return;
        }
        double pressure = Mth.clamp(fearIntensity * 0.032F + insideCoreIntensity * 0.030F - insideEyeIntensity * 0.012F, 0.0F, 0.055F);
        event.setFOV(event.getFOV() * (1.0D - pressure));
    }

    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        // The whiteout answers a narrower question than fog pressure does, so it must not be silenced by
        // switching fog pressure off - that would leave the flat veil drawn over an un-collapsed fog and
        // read as a grey sheet pasted on the view rather than as being inside the funnel.
        if (!Config.TORNADO_IMMERSION_ENABLED.get()) {
            return;
        }
        if ((!Config.TORNADO_IMMERSION_FOG_PRESSURE.get() || fearIntensity <= 0.03F) && !whiteoutActive()) {
            return;
        }
        float insideMultiplier = Config.TORNADO_IMMERSION_INSIDE_FOG_MULTIPLIER.get().floatValue();
        float corePressure = Config.TORNADO_IMMERSION_INSIDE_CORE_EFFECTS.get() ? insideCoreIntensity * 0.74F * insideMultiplier : 0.0F;
        float eyeRelief = Config.TORNADO_IMMERSION_EYE_EFFECT.get() ? insideEyeIntensity * 0.38F : 0.0F;
        float whiteout = Config.TORNADO_IMMERSION_WHITEOUT_ENABLED.get() ? whiteoutIntensity : 0.0F;
        // The whiteout term carries real weight on purpose: at full whiteout it is what drives the fog
        // colour, and leaving it as a token contribution meant the view kept its daylight colour through
        // a whiteout and the effect read as nothing at all.
        float pressure = Mth.clamp(fearIntensity * 0.48F + corePressure - eyeRelief + whiteout * 0.75F, 0.0F, 0.95F);
        float dust = Mth.clamp(insideCoreIntensity * 0.60F * insideMultiplier + whiteout * 0.34F, 0.0F, 0.92F);
        // Inside the funnel there is no landscape left, only suspended dust lit by whatever ambient light
        // survives, so the whiteout end goes to a flat neutral grey rather than a tan - and it is scaled
        // by how bright the world actually is, so a night tornado whites out to a dark grey instead of
        // glowing like a lamp.
        float ambient = Mth.clamp(Math.max(event.getRed(), Math.max(event.getGreen(), event.getBlue())) * 1.7F, 0.30F, 1.0F);
        frameAmbient = ambient;
        float grey = 0.88F * ambient;
        float redTarget = Mth.lerp(dust, event.getRed() * 0.50F, Mth.lerp(whiteout, 0.70F, grey));
        float greenTarget = Mth.lerp(dust, event.getGreen() * 0.54F, Mth.lerp(whiteout, 0.69F, grey * 0.99F));
        float blueTarget = Mth.lerp(dust, event.getBlue() * 0.64F, Mth.lerp(whiteout, 0.68F, grey * 0.97F));
        if (insideEyeIntensity > 0.02F) {
            redTarget = Mth.lerp(insideEyeIntensity * 0.32F, redTarget, event.getRed() * 0.88F);
            greenTarget = Mth.lerp(insideEyeIntensity * 0.32F, greenTarget, event.getGreen() * 0.90F);
            blueTarget = Mth.lerp(insideEyeIntensity * 0.32F, blueTarget, event.getBlue() * 0.95F);
        }
        event.setRed(Mth.lerp(pressure, event.getRed(), redTarget));
        event.setGreen(Mth.lerp(pressure, event.getGreen(), greenTarget));
        event.setBlue(Mth.lerp(pressure, event.getBlue(), blueTarget));
    }

    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (!fogPressureActive()) {
            // fogPressureActive() already includes the whiteout, so this is only a guard against the
            // early frames before the intensity has ramped in.
            return;
        }
        float insideMultiplier = Config.TORNADO_IMMERSION_INSIDE_FOG_MULTIPLIER.get().floatValue();
        float coreCollapse = Mth.clamp(insideCoreIntensity * 0.70F * insideMultiplier, 0.0F, 1.0F);
        // Cubed, so the dread only closes the air in once the funnel is nearly on top of you. Taken straight, a tornado three
        // hundred blocks off drew the fog in to a hundred and seventy, and the thing being dreaded could not be seen.
        float dread = fearIntensity * fearIntensity * fearIntensity;
        float pressure = Mth.clamp(dread * 0.72F + insideDamagePathIntensity * 0.30F
                + coreCollapse, 0.0F, 1.0F);
        float targetFar = Mth.lerp(pressure, event.getFarPlaneDistance(), 13.0F);
        if (Config.TORNADO_IMMERSION_EYE_EFFECT.get() && insideEyeIntensity > 0.02F) {
            targetFar = Mth.lerp(insideEyeIntensity * 0.55F, targetFar, 46.0F);
        }
        if (Config.TORNADO_IMMERSION_WHITEOUT_ENABLED.get() && whiteoutIntensity > 0.01F) {
            float whiteoutFar = Config.TORNADO_IMMERSION_WHITEOUT_FOG_BLOCKS.get().floatValue();
            targetFar = Mth.lerp(whiteoutIntensity, targetFar, Math.max(0.5F, whiteoutFar));
        }
        event.setFarPlaneDistance(Math.min(event.getFarPlaneDistance(), targetFar));
        event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(), 0.0F));
    }

    /**
     * Whether tornado pressure fog is currently overriding the far plane. Exposed so the LOD compat
     * fog handler can stand down instead of stretching a fog this system deliberately pulled in.
     */
    public static boolean fogPressureActive() {
        // The whiteout counts here even when fog pressure is switched off: it pulls the fog in hard on its
        // own, so anything that defers to this - the fog render hook and the LOD fog compat - has to stand
        // down for it too, or an LOD fog would stretch straight back over the collapsed fog.
        return Config.TORNADO_IMMERSION_ENABLED.get()
                && ((Config.TORNADO_IMMERSION_FOG_PRESSURE.get() && fearIntensity > 0.08F)
                        || whiteoutActive());
    }

    public static float fearIntensity() {
        return fearIntensity;
    }

    public static float insideDamagePathIntensity() {
        return insideDamagePathIntensity;
    }

    public static float insideCoreIntensity() {
        return insideCoreIntensity;
    }

    public static float insideEyeIntensity() {
        return insideEyeIntensity;
    }

    public static float whiteoutIntensity() {
        return whiteoutIntensity;
    }

    /**
     * Whether the dust whiteout is asserting itself right now, so the fog hooks can treat it as its own
     * cause independent of the fog-pressure switch.
     */
    public static boolean whiteoutActive() {
        return Config.TORNADO_IMMERSION_ENABLED.get()
                && Config.TORNADO_IMMERSION_WHITEOUT_ENABLED.get()
                && whiteoutIntensity > 0.01F;
    }

    /**
     * How violent the funnel currently engulfing the player is, normalised over the same 45 mph gate the
     * whiteout itself uses. The veil's debris reads from this so the streaking and the dark clumps scale
     * with the storm instead of running at one fixed intensity whether you are in an EF1 or an EF5.
     */
    public static float funnelViolence() {
        if (!whiteoutActive()) {
            return 0.0F;
        }
        double mph = activeSample == null ? 0.0D : activeSample.windMph();
        return Mth.clamp((float) ((mph - 45.0D) / 145.0D), 0.0F, 1.0F);
    }

    public static Vec3 activeWindVector() {
        return activeSample == null ? Vec3.ZERO : activeSample.windVector();
    }

    public static Vec3 activeCenter() {
        return activeSample == null ? Vec3.ZERO : activeSample.center();
    }

    /**
     * How much whiteout survives where the player is standing, 1.0 under open sky down to the configured
     * floor under cover. Read from whether the sky is visible above the player, so a barn, a house or a
     * cave actually protects the view rather than being whitened through as if the walls were not there.
     */
    private static float shelterExposure() {
        if (!Config.TORNADO_IMMERSION_WHITEOUT_SHELTER.get()) {
            return 1.0F;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return 1.0F;
        }
        BlockPos pos = minecraft.player.blockPosition();
        if (!minecraft.level.isLoaded(pos)) {
            return 1.0F;
        }
        if (minecraft.level.canSeeSky(pos)) {
            return 1.0F;
        }
        return (float) Config.TORNADO_IMMERSION_WHITEOUT_SHELTER_EXPOSURE.get().doubleValue();
    }

    /**
     * The dust the active storm is throwing, for the whiteout to tint and grit itself with. Falls back to
     * the standard warm grey whenever the type-aware dust is switched off or nothing is being drawn.
     */
    public static DustProfile dust() {
        if (!Config.TORNADO_IMMERSION_WHITEOUT_STORM_TINT.get() || !whiteoutActive()) {
            return DUST_DEFAULT;
        }
        DustProfile dust = activeSample.dust();
        return dust == null ? DUST_DEFAULT : dust;
    }

    /** Ambient brightness the fog colour was last driven with, so the dust volume can match it. */
    public static float veilBrightness() {
        return frameAmbient;
    }

    private static void updateInsideState() {
        float targetPath = 0.0F;
        float targetCore = 0.0F;
        float targetEye = 0.0F;
        float targetWhiteout = 0.0F;
        float targetTumble = 0.0F;
        if (activeSample != FearSample.NONE) {
            // The whiteout is its own switch rather than part of the inside-core package: it is the one
            // effect that answers "am I inside the tornado", and being buried under a second opt-in meant
            // turning off the extra core effects quietly removed it too.
            if (Config.TORNADO_IMMERSION_WHITEOUT_ENABLED.get()) {
                // It ramps in from the funnel wall and saturates in the core. It used to live only within
                // half the core radius - a few blocks at the middle of the vortex - so in practice the
                // player never reached it and the whiteout never fired at all. The wall is the radius of
                // maximum wind, which is where the dust is actually densest. It is deliberately not scaled
                // off the damage path: that swath runs to hundreds of blocks on a wedge, so triggering on
                // it would veil the screen on approach rather than on entry.
                double wall = Math.max(activeSample.radiusOfMaxWind(), Math.max(activeSample.coreRadius(), 4.0F));
                double outer = wall * Config.TORNADO_IMMERSION_WHITEOUT_RADIUS_FRACTION.get();
                double inner = Math.max(activeSample.coreRadius() * 0.60D, 3.0D);
                if (outer <= inner) {
                    outer = inner + 1.0D;
                }
                float ramp = (float) Mth.clamp((outer - activeSample.distance()) / (outer - inner), 0.0D, 1.0D);
                // Dust has to be in the air to whiten it, gated on the storm's own wind rather than the
                // player's local sample. An EF1 already lifts enough to hide the world, which is why this
                // tops out at 100 mph instead of the old 160.
                float whiteoutWindGate = Mth.clamp((float) ((activeSample.windMph() - 45.0D) / 55.0D), 0.0F, 1.0F);
                targetWhiteout = ramp * whiteoutWindGate;
                if (Config.TORNADO_IMMERSION_EYE_EFFECT.get()) {
                    // The eye pocket is meant to be a visible calm hole, and an opaque whiteout would
                    // simply hide it, so the whiteout backs off wherever the eye is asserting itself.
                    targetWhiteout *= 1.0F - Mth.clamp(insideEyeIntensity, 0.0F, 1.0F) * 0.75F;
                }
                // Cover stands the whiteout down: the funnel whitens the view on geometric distance, but a
                // roof between the player and the sky means the grit is outside, not in the room.
                targetWhiteout *= shelterExposure();
            }
            if (Config.TORNADO_IMMERSION_INSIDE_CORE_EFFECTS.get()) {
                double pathDistance = Math.max(6.0D, activeSample.damageWidth() * 0.50D);
                double coreDistance = Math.max(Math.max(activeSample.coreRadius(), activeSample.radiusOfMaxWind() * 0.65F), 8.0F);
                double eyeDistance = Math.max(activeSample.coreRadius() * 0.45F, 4.0F);
                targetPath = Mth.clamp((float) (1.0D - activeSample.distance() / pathDistance), 0.0F, 1.0F);
                targetCore = Mth.clamp((float) (1.0D - activeSample.distance() / coreDistance), 0.0F, 1.0F);
                if (Config.TORNADO_IMMERSION_EYE_EFFECT.get() && activeSample.windMph() >= 105.0D && activeSample.distance() <= eyeDistance) {
                    float windGate = Mth.clamp((float) ((activeSample.windMph() - 95.0D) / 105.0D), 0.0F, 1.0F);
                    targetEye = Mth.clamp((float) (1.0D - activeSample.distance() / eyeDistance), 0.0F, 1.0F) * windGate;
                }
                if (Config.TORNADO_IMMERSION_CAMERA_TUMBLE.get()) {
                    double tumbleDistance = Math.max(activeSample.coreRadius() * 0.78D, 6.0D);
                    float windGate = Mth.clamp((float) ((activeSample.windMph() - 85.0D) / 110.0D), 0.0F, 1.0F);
                    targetTumble = Mth.clamp((float) (1.0D - activeSample.distance() / tumbleDistance), 0.0F, 1.0F) * windGate;
                    if (targetTumble <= 0.01F) {
                        targetTumble = playerCarriedRamp(activeSample.windMph());
                    }
                }
            }
        }
        if (!tumblesInGameMode(Minecraft.getInstance().player)) {
            // Survival only: in creative and spectator the player can just fly out of the funnel,
            // so tumbling the camera and model there only fights their own movement.
            targetTumble = 0.0F;
        }
        insideDamagePathIntensity = lerpInsideState(insideDamagePathIntensity, targetPath);
        insideCoreIntensity = lerpInsideState(insideCoreIntensity, targetCore);
        insideEyeIntensity = lerpInsideState(insideEyeIntensity, targetEye);
        whiteoutIntensity = lerpInsideState(whiteoutIntensity, targetWhiteout);
        tumbleIntensity = lerpInsideState(tumbleIntensity, targetTumble);
    }

    /**
     * Weather2's OWN pick-up carries players too (spinObject with forPlayer=true, gated only by its
     * own entity pull radius and storm strength) at winds well under our camera-tumble gate - so a
     * player being dragged by that system used to get no tumble at all. If the player is airborne
     * and actually being carried (lifted or driven sideways) inside a tornado, tumble them too.
     */
    private static float playerCarriedRamp(double windMph) {
        if (windMph < 55.0D) {
            return 0.0F;
        }
        Player player = Minecraft.getInstance().player;
        if (player == null || player.onGround() || player.isPassenger() || player.isSpectator()) {
            return 0.0F;
        }
        Vec3 motion = player.getDeltaMovement();
        double horizontal = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        if (horizontal < 0.18D && motion.y < 0.05D) {
            return 0.0F;
        }
        return (float) Mth.clamp(0.20D + (windMph - 55.0D) / 120.0D, 0.0F, 0.60D);
    }

    private static float lerpInsideState(float current, float target) {
        float smoothing = target > current ? 0.24F : 0.075F;
        float value = Mth.lerp(smoothing, current, target);
        return value < 0.01F && target < 0.01F ? 0.0F : value;
    }

    private static boolean canMoveCamera() {
        return Config.TORNADO_IMMERSION_ENABLED.get()
                && Config.TORNADO_IMMERSION_CAMERA_RUMBLE.get()
                && !Config.TORNADO_IMMERSION_ACCESSIBILITY_REDUCED_MOTION.get()
                && closeEffectFalloff() > 0.0F
                && fearIntensity > 0.04F;
    }

    private static float rumbleStrength() {
        float wind = activeSample == null ? 0.0F : Mth.clamp((float) (activeSample.windMph() / 250.0D), 0.0F, 1.0F);
        float strength = (fearIntensity * (0.45F + wind * 0.55F) + insideCoreIntensity * 0.36F - insideEyeIntensity * 0.16F) * closeEffectFalloff();
        if (ef3IntenseActive()) {
            strength *= Config.TORNADO_IMMERSION_EF3_CAMERA_MULTIPLIER.get().floatValue();
        }
        return Mth.clamp(strength, 0.0F, 1.0F);
    }

    private static boolean ef3IntenseActive() {
        return Config.TORNADO_IMMERSION_INTENSE_EF3_EFFECTS.get()
                && activeSample != null
                && activeSample != FearSample.NONE
                && activeSample.windMph() >= Config.TORNADO_IMMERSION_EF3_WIND_THRESHOLD_MPH.get();
    }

    private static void reset(Minecraft minecraft) {
        fearIntensity = 0.0F;
        targetFearIntensity = 0.0F;
        insideDamagePathIntensity = 0.0F;
        insideCoreIntensity = 0.0F;
        insideEyeIntensity = 0.0F;
        whiteoutIntensity = 0.0F;
        tumbleIntensity = 0.0F;
        tumbleFlipAccum = 0.0F;
        tumbleSpinning = false;
        cameraTumbleSeeded = false;
        cameraRollAccum = 0.0F;
        cameraPitchAccum = 0.0F;
        cameraYawAccum = 0.0F;
        TUMBLE_ROLLS.clear();
        activeSample = FearSample.NONE;
        if (roarSound != null) {
            roarSound.requestStop();
            if (minecraft != null) {
                minecraft.getSoundManager().stop(roarSound);
            }
            roarSound = null;
        }
        if (roarFarSound != null) {
            roarFarSound.requestStop();
            if (minecraft != null) {
                minecraft.getSoundManager().stop(roarFarSound);
            }
            roarFarSound = null;
        }
        if (humSound != null) {
            humSound.requestStop();
            if (minecraft != null) {
                minecraft.getSoundManager().stop(humSound);
            }
            humSound = null;
        }
        if (windBedSound != null) {
            windBedSound.requestStop();
            if (minecraft != null) {
                minecraft.getSoundManager().stop(windBedSound);
            }
            windBedSound = null;
        }
    }

    private static FearSample findStrongestSample(ClientLevel level, Player player) {
        FearSample best = FearSample.NONE;
        Vec3 playerPos = player.position();
        WeatherManagerClient manager = ClientTickHandler.weatherManager;
        if (manager != null) {
            for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
                if (!(object instanceof StormObject storm) || storm.pos == null || !DynamicStormController.isDynamicVortex(storm) || DynamicStormController.isAbsorbedOrDecaying(storm)) {
                    continue;
                }
                DynamicStormState state = DynamicStormController.stateFor(storm);
                TornadoWindField.WindSample wind = TornadoWindField.sample(storm, level, playerPos);
                best = stronger(best, sampleFromStorm(storm, state, playerPos, storm.pos, wind, state.damagePathWidth(), state.coreRadius(), state.radiusOfMaxWind(), TornadoWindField.maxWindMph(storm)));
                for (DynamicStormState.TornadoCirculationState satellite : state.satelliteTornadoes()) {
                    Vec3 center = DynamicStormController.satelliteCenter(storm, satellite, storm.pos.y);
                    best = stronger(best, sampleFromStorm(storm, state, playerPos, center, wind, satellite.damagePathWidth(), satellite.coreRadius(), satellite.radiusOfMaxWind(), DynamicStormController.satelliteWindMph(storm, satellite)));
                }
            }
        }
        for (AddonWeatherEvent event : ClientAddonWeatherEventStore.events()) {
            if (event.isLine() || !isFearVortex(event.type())) {
                continue;
            }
            Vec3 center = event.position();
            double distance = horizontalDistance(playerPos, center);
            if (distance > Config.TORNADO_IMMERSION_RENDER_RANGE_BLOCKS.get()) {
                continue;
            }
            Vec3 vector = addonVortexWind(event, playerPos);
            float coreRadius = Math.max(2.0F, event.radius() * 0.35F);
            float radiusOfMaxWind = Math.max(3.0F, event.radius());
            best = stronger(best, sampleFromValues(center, vector, event.maxWindMph(), distance, event.radius() * 2.0F, coreRadius, radiusOfMaxWind, event.type() == StormSubtype.DUST_DEVIL ? 0.52F : 0.78F, profileFor(event.type(), false, 0.0F)));
        }
        return best;
    }

    private static FearSample sampleFromStorm(StormObject storm, DynamicStormState state, Vec3 playerPos, Vec3 center, TornadoWindField.WindSample wind, float damageWidth, float coreRadius, float radiusOfMaxWind, double maxWindMph) {
        double distance = horizontalDistance(playerPos, center);
        if (distance > Config.TORNADO_IMMERSION_RENDER_RANGE_BLOCKS.get()) {
            return FearSample.NONE;
        }
        float stage = Mth.clamp((storm.levelCurIntensityStage - StormObject.STATE_FORMING) / 5.0F, 0.0F, 1.0F);
        float subtypeBoost = state.isAntiCyclonic() ? 0.05F : 0.0F;
        return sampleFromValues(center, wind.mphVector(), Math.max(maxWindMph, wind.strongestLocalMph()), distance, damageWidth, coreRadius, radiusOfMaxWind, 1.0F + stage * 0.22F + subtypeBoost, profileFor(state.subtype(), storm.canSnowFromCloudTemperature, state.precipitationCharacter()));
    }

    private static FearSample sampleFromValues(Vec3 center, Vec3 windVector, double windMph, double distance, float damageWidth, float coreRadius, float radiusOfMaxWind, float multiplier, DustProfile dust) {
        double range = Config.TORNADO_IMMERSION_RENDER_RANGE_BLOCKS.get();
        float distanceScore = Mth.clamp((float) (1.0D - distance / range), 0.0F, 1.0F);
        float windScore = Mth.clamp((float) (windMph / 235.0D), 0.0F, 1.0F);
        float pathScore = Mth.clamp((float) (1.0D - distance / Math.max(18.0F, damageWidth * 0.72F)), 0.0F, 1.0F);
        float coreScore = Mth.clamp((float) (1.0D - distance / Math.max(10.0F, Math.max(coreRadius, radiusOfMaxWind * 0.65F))), 0.0F, 1.0F);
        float intensity = (distanceScore * 0.30F + windScore * 0.34F + pathScore * 0.24F + coreScore * 0.18F) * multiplier;
        return new FearSample(center, windVector, windMph, distance, damageWidth, coreRadius, radiusOfMaxWind, Mth.clamp(intensity, 0.0F, 1.0F), dust);
    }

    private static FearSample stronger(FearSample first, FearSample second) {
        return second.intensity() > first.intensity() ? second : first;
    }

    private static boolean isFearVortex(StormSubtype subtype) {
        return subtype == StormSubtype.LANDSPOUT || subtype == StormSubtype.DUST_DEVIL || subtype == StormSubtype.GUSTNADO || subtype == StormSubtype.WATERSPOUT;
    }

    private static Vec3 addonVortexWind(AddonWeatherEvent event, Vec3 playerPos) {
        Vec3 delta = playerPos.subtract(event.position());
        double radius = Math.max(0.1D, Math.sqrt(delta.x * delta.x + delta.z * delta.z));
        Vec3 radial = new Vec3(delta.x / radius, 0.0D, delta.z / radius);
        Vec3 tangent = new Vec3(-radial.z, 0.0D, radial.x);
        double falloff = Mth.clamp(1.0D - radius / Math.max(8.0F, event.radius() * 3.5F), 0.0D, 1.0D);
        return tangent.scale(event.maxWindMph() * falloff).add(radial.scale(-event.maxWindMph() * 0.30D * falloff));
    }

    private static double horizontalDistance(Vec3 first, Vec3 second) {
        double dx = first.x - second.x;
        double dz = first.z - second.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * The tornado roar, as two layers of one sound.
     *
     * <p>A roar is not a close-range effect. It is the loudest thing most people will ever hear, and it is
     * heard long before the funnel is visible - so the gate here is distance, not the close-effect radius, and
     * the loudness is worked out from that distance rather than left to the engine. The engine's own linear
     * falloff ties loudness to range: a sound that carries 300 blocks is four times louder standing next to it
     * than one that carries 75, which is not how a tornado works and is why this one declares no attenuation
     * at all and does the arithmetic itself.
     *
     * <p>Distance does not only make a sound quieter, it filters it: air absorbs the top end first, so a
     * tornado a few hundred blocks away is a rumble with no hiss in it. That is the second layer - the same
     * loop with the top absorbed, level-matched to the first - crossfaded in as the funnel recedes.
     */
    private static void tickRoar(Minecraft minecraft) {
        double distance = roarListenerDistance();
        if (!Config.TORNADO_IMMERSION_ROAR_SOUND.get() || activeSample == FearSample.NONE
                || distance >= roarRadius()) {
            if (roarSound != null) {
                roarSound.fadeOut();
            }
            if (roarFarSound != null) {
                roarFarSound.fadeOut();
            }
            return;
        }
        if (roarSound == null || !minecraft.getSoundManager().isActive(roarSound)) {
            roarSound = new TornadoRoarSound(activeSample, ModContent.TORNADO_ROAR_SOUND.get(), false);
            minecraft.getSoundManager().play(roarSound);
        }
        roarSound.update(activeSample, distance);

        if (!Config.TORNADO_IMMERSION_ROAR_DISTANCE_LAYER.get() || roarNearWeight(distance) >= 1.0F) {
            if (roarFarSound != null) {
                roarFarSound.fadeOut();
            }
            return;
        }
        if (roarFarSound == null || !minecraft.getSoundManager().isActive(roarFarSound)) {
            roarFarSound = new TornadoRoarSound(activeSample, ModContent.TORNADO_ROAR_FAR_SOUND.get(), true);
            minecraft.getSoundManager().play(roarFarSound);
        }
        roarFarSound.update(activeSample, distance);
    }

    /**
     * How far away the funnel's <b>wall</b> is, which is the distance a roar actually obeys.
     *
     * <p>The sample's own distance is to the vortex centre, and a wedge three miles wide is already surrounding
     * a player standing near its edge. Measuring to the centre would make the biggest tornadoes the quietest
     * ones at the same range from their wind, so the radius of maximum wind is taken off first: at the wall of
     * any tornado, from a rope to a wedge, the roar is the same.
     */
    private static double roarListenerDistance() {
        if (activeSample == null || activeSample == FearSample.NONE) {
            return Double.MAX_VALUE;
        }
        return Math.max(0.0D, activeSample.distance() - activeSample.radiusOfMaxWind());
    }

    private static double roarRadius() {
        return Math.max(16.0D, Config.TORNADO_IMMERSION_ROAR_RADIUS_BLOCKS.get());
    }

    /**
     * The roar's falloff, in the open air, as a gain rather than as a distance.
     *
     * <p>Full strength within the reference range - a roar does not get quieter as you take a few steps back -
     * then an inverse-power falloff, which is roughly what air and distance do to a broad-band sound. The rim
     * tapers over the last quarter of the range so the roar fades out rather than being cut off at a line you
     * can walk across.
     */
    private static float roarDistanceGain(double distance) {
        double radius = roarRadius();
        if (distance >= radius) {
            return 0.0F;
        }
        double rolloff = Config.TORNADO_IMMERSION_ROAR_DISTANCE_ROLLOFF.get();
        double falloff = distance <= ROAR_REFERENCE_BLOCKS ? 1.0D
                : Math.pow(ROAR_REFERENCE_BLOCKS / distance, rolloff);
        double rim = Mth.clamp((radius - distance) / (radius * 0.25D), 0.0D, 1.0D);
        return (float) (falloff * rim);
    }

    /**
     * How much of what is heard is the bright roar rather than the air-absorbed one: 1 at the funnel, 0 once it
     * is away, and a hand-over across the configured near range in between. The two weights sum to one, and the
     * two files are level-matched, so the hand-over changes the tone without changing the loudness.
     */
    private static float roarNearWeight(double distance) {
        double near = Math.max(1.0D, Config.TORNADO_IMMERSION_ROAR_NEAR_RANGE_BLOCKS.get());
        return Mth.clamp(1.0F - (float) ((distance - near * 0.5D) / near), 0.0F, 1.0F);
    }

    /**
     * The hum a monster tornado carries, layered under the roar.
     *
     * <p>Two things make this different from the roar, and both are the point of it existing as its own
     * sound rather than as more of that one:
     *
     * <ul>
     *   <li><b>It has a threshold.</b> The roar is the wind, and wind is there from EF0; the hum is the note
     *       a vortex that size carries, and below a certain strength there is no coherent vortex to sing. The
     *       gate is the tornado's own wind speed - the same reading the roar's pitch uses - so it turns on for
     *       a genuinely violent storm and stays quiet for the merely severe ones, which is what makes hearing
     *       it mean something.</li>
     *   <li><b>Carries much further.</b> The roar is deliberately a close-range event (its radius defaults to
     *       30 blocks), because what it represents is standing next to one. A hum is a low frequency and low
     *       frequencies travel: this one is positioned at the tornado with its own audible radius, hundreds of
     *       blocks, so it is heard arriving well before the funnel is close. Nothing else about it is
     *       distance-based - the engine does the falloff, which is why the sound is placed at the vortex
     *       rather than on the player.</li>
     * </ul>
     *
     * <p>It starts only once the wind is past the threshold and then swells with it, so a storm that reaches
     * that strength fades the drone in over a couple of seconds rather than switching it on mid-storm. The
     * eye ducks it with the roar, for the same reason: the calm hollow is quieter.
     */
    /**
     * The broad wind bed under the roar.
     *
     * <p>The roar is a close-range event by design and the hum only exists for a monster, so an ordinary
     * tornado used to be silent from any distance at all - which is what reads as the world going empty as a
     * storm approaches. The bed fills that: the air-absorbed wind loop, positioned at the funnel and carried
     * on its own radius, breathing on slow gusts so it is a body of moving air rather than a second copy of
     * the roar. Like the hum it is placed at the tornado rather than on the player, so the engine's own
     * falloff does the distance work.
     */
    private static void tickWindBed(Minecraft minecraft) {
        double radius = Math.max(16.0D, Config.TORNADO_IMMERSION_WIND_BED_RADIUS_BLOCKS.get());
        if (!Config.TORNADO_IMMERSION_WIND_BED.get() || activeSample == FearSample.NONE
                || activeSample.distance() >= radius) {
            if (windBedSound != null) {
                windBedSound.fadeOut();
            }
            return;
        }
        if (windBedSound == null || !minecraft.getSoundManager().isActive(windBedSound)) {
            windBedSound = new TornadoWindBedSound(activeSample);
            minecraft.getSoundManager().play(windBedSound);
        }
        windBedSound.update(activeSample);
    }

    private static void tickHum(Minecraft minecraft) {
        double threshold = Config.TORNADO_IMMERSION_HUM_WIND_THRESHOLD_MPH.get().doubleValue();
        if (!Config.TORNADO_IMMERSION_HUM_SOUND.get() || activeSample == FearSample.NONE
                || activeSample.windMph() < threshold) {
            if (humSound != null) {
                humSound.fadeOut();
            }
            return;
        }
        if (humSound == null || !minecraft.getSoundManager().isActive(humSound)) {
            humSound = new TornadoHumSound(activeSample);
            minecraft.getSoundManager().play(humSound);
        }
        humSound.update(activeSample, humUrgency(activeSample));
    }

    /** How far past the threshold a tornado is: 0 at the threshold, 1 once it is 100 mph beyond it. */
    private static float humUrgency(FearSample sample) {
        double threshold = Config.TORNADO_IMMERSION_HUM_WIND_THRESHOLD_MPH.get().doubleValue();
        return Mth.clamp((float) ((sample.windMph() - threshold) / 100.0D), 0.0F, 1.0F);
    }

    private static float closeEffectFalloff() {
        if (activeSample == null || activeSample == FearSample.NONE) {
            return 0.0F;
        }
        double radius = Math.max(1.0D, Config.TORNADO_IMMERSION_CLOSE_EFFECT_RADIUS_BLOCKS.get());
        return Mth.clamp((float) (1.0D - activeSample.distance() / radius), 0.0F, 1.0F);
    }

    private static void tickImpactSounds(ClientLevel level, Player player) {
        if (fearIntensity < 0.32F || activeSample == FearSample.NONE || level.getGameTime() < nextImpactSoundTick) {
            return;
        }
        float chance = Mth.clamp((fearIntensity - 0.28F) * 0.18F, 0.02F, 0.16F);
        if (level.random.nextFloat() > chance) {
            nextImpactSoundTick = level.getGameTime() + 8L + level.random.nextInt(22);
            return;
        }
        Vec3 around = player.position().add((level.random.nextDouble() - 0.5D) * 20.0D, 0.0D, (level.random.nextDouble() - 0.5D) * 20.0D);
        float volume = 0.18F + fearIntensity * 0.55F;
        float pitch = 0.65F + level.random.nextFloat() * 0.55F;
        switch (level.random.nextInt(4)) {
            case 0 -> level.playLocalSound(around.x, around.y, around.z, SoundEvents.WOOD_HIT, SoundSource.WEATHER, volume, pitch, false);
            case 1 -> level.playLocalSound(around.x, around.y, around.z, SoundEvents.GRAVEL_HIT, SoundSource.WEATHER, volume, pitch, false);
            case 2 -> level.playLocalSound(around.x, around.y, around.z, SoundEvents.GRASS_BREAK, SoundSource.WEATHER, volume * 0.72F, pitch + 0.20F, false);
            default -> level.playLocalSound(around.x, around.y, around.z, SoundEvents.CROP_BREAK, SoundSource.WEATHER, volume * 0.64F, pitch + 0.28F, false);
        }
        nextImpactSoundTick = level.getGameTime() + 6L + level.random.nextInt(18);
    }

    private static void spawnCloseDebris(ClientLevel level, Player player) {
        int max = Config.TORNADO_IMMERSION_MAX_PARTICLES_PER_TICK.get();
        if (max <= 0) {
            return;
        }
        int count = Mth.clamp((int) ((fearIntensity - 0.55F) * max * 0.18F), 1, 12);
        Vec3 wind = activeSample.windVector();
        if (wind.horizontalDistanceSqr() < 1.0E-5D) {
            wind = activeSample.center().subtract(player.position());
        }
        Vec3 windDir = wind.horizontalDistanceSqr() < 1.0E-5D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(wind.x, 0.0D, wind.z).normalize();
        Vec3 right = new Vec3(-windDir.z, 0.0D, windDir.x);
        for (int i = 0; i < count && particlesThisTick < max; i++) {
            double ahead = -10.0D + level.random.nextDouble() * 20.0D;
            double side = (level.random.nextDouble() - 0.5D) * (6.0D + fearIntensity * 10.0D);
            Vec3 base = player.position().add(windDir.scale(ahead)).add(right.scale(side));
            double y = groundY(level, base) + 0.10D + level.random.nextDouble() * (0.65D + fearIntensity * 1.3D);
            Vec3 pos = new Vec3(base.x, y, base.z);
            Vec3 velocity = windDir.scale(0.07D + fearIntensity * 0.32D)
                    .add(right.scale((level.random.nextDouble() - 0.5D) * 0.10D))
                    .add(0.0D, 0.012D + level.random.nextDouble() * fearIntensity * 0.04D, 0.0D);
            spawnWeatherParticle(level, pos, velocity.scale(0.34D), ParticleRegistry.cloud, 1.4F + fearIntensity * 1.8F, 0.43F, 0.40F, 0.36F, 0.28F, 26, 0.10F, ParticleTypes.POOF);
            particlesThisTick++;
        }
    }

    private static boolean shouldSpawnCloseDebris() {
        if (fearIntensity <= 0.55F || activeSample == FearSample.NONE) {
            return false;
        }
        double coreDangerDistance = Math.max(18.0D, activeSample.damageWidth() * 0.45D);
        return activeSample.distance() <= coreDangerDistance;
    }

    private static double groundY(ClientLevel level, Vec3 pos) {
        BlockPos blockPos = BlockPos.containing(pos);
        if (!level.isLoaded(blockPos)) {
            return pos.y;
        }
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, blockPos).getY();
    }

    private static void spawnWeatherParticle(ClientLevel level, Vec3 pos, Vec3 velocity, net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, float scale, float red, float green, float blue, float alpha, int maxAge, float windWeight, ParticleOptions fallback) {
        if (!Config.EXTENDED_RENDERING_ENABLED.get() || sprite == null) {
            level.addParticle(fallback, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z);
            return;
        }
        EntityRotFX particle = new EntityRotFX(level, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z);
        particle.setSprite(sprite);
        particle.setScale(Mth.clamp(scale, 0.05F, 42.0F));
        particle.setColor(red, green, blue);
        particle.setAlpha(Mth.clamp(alpha, 0.02F, 0.62F));
        particle.setMaxAge(maxAge);
        particle.setGravity(0.0F);
        particle.setCanCollide(false);
        particle.setFacePlayer(true);
        particle.facePlayerYaw = true;
        particle.windWeight = windWeight;
        particle.spawnAsWeatherEffect();
    }

    private record FearSample(Vec3 center, Vec3 windVector, double windMph, double distance, float damageWidth, float coreRadius, float radiusOfMaxWind, float intensity, DustProfile dust) {
        // A null dust is deliberate: NONE is built during this class's own static init, before the dust
        // constants below have been assigned, so it must not touch them. dust() treats null as DUST_DEFAULT.
        private static final FearSample NONE = new FearSample(Vec3.ZERO, Vec3.ZERO, 0.0D, Double.MAX_VALUE, 0.0F, 0.0F, 0.0F, 0.0F, null);
    }

    /**
     * The look of the dust the storm is filling the view with: a base tint and how much grit (streaks and
     * dark clumps) it carries.
     *
     * <p>Dust is not one colour. A rain-wrapped supercell throws a near-uniform washed grey with the boards
     * hidden in the water; a dust devil throws tan and loads it with grit; a snow storm throws cold
     * blue-white; a fire whirl throws dark smoke. The tint is the light end of the dust and the shader
     * darkens it for the clumps, so one colour per storm carries the whole palette.
     */
    public record DustProfile(float red, float green, float blue, float grit) {
    }

    /** The one warm-grey dust, used when the storm cannot be classified or the tint is switched off. */
    private static final DustProfile DUST_DEFAULT = new DustProfile(0.88F, 0.87F, 0.84F, 1.0F);
    /** A rain-wrapped funnel: washed grey, and the grit is mostly hidden in the rain. */
    private static final DustProfile DUST_RAIN_WRAPPED = new DustProfile(0.80F, 0.81F, 0.82F, 0.42F);
    /** Snow: cold and bright. */
    private static final DustProfile DUST_SNOW = new DustProfile(0.90F, 0.94F, 0.99F, 0.55F);
    /** Sea spray off a waterspout. */
    private static final DustProfile DUST_SPRAY = new DustProfile(0.82F, 0.88F, 0.93F, 0.60F);
    /** Tan ground dust: dust devils, landspouts, sandstorms. Grittiest. */
    private static final DustProfile DUST_TAN = new DustProfile(0.84F, 0.74F, 0.55F, 1.25F);
    /** Fire whirls and lava: dark smoke, kept a little grit-heavy so it churns. */
    private static final DustProfile DUST_SMOKE = new DustProfile(0.46F, 0.33F, 0.26F, 1.10F);

    /**
     * Picks the dust for a storm. Type decides it outright where the type is unambiguous; otherwise a snow
     * storm reads cold and a wet, high-precipitation supercell reads rain-wrapped, so a plain tornado can
     * be either a dry dust wall or a grey wash depending on what it is actually raining.
     */
    private static DustProfile profileFor(StormSubtype subtype, boolean snow, float precipitation) {
        StormSubtype type = subtype == null ? StormSubtype.NONE : subtype;
        switch (type) {
            case SNOWSTORM, SNOW_DEVIL:
                return DUST_SNOW;
            case WATERSPOUT, STEAM_DEVIL:
                return DUST_SPRAY;
            case DUST_DEVIL, SANDSTORM, GUSTNADO, LANDSPOUT:
                return DUST_TAN;
            case FIRE_WHIRL, LAVA_WHIRL, FLAMMAGENITUS:
                return DUST_SMOKE;
            default:
                break;
        }
        if (snow) {
            return DUST_SNOW;
        }
        // 0.55 splits a classic supercell from a high-precipitation one; a wet funnel is grey, not grit.
        return precipitation >= 0.55F ? DUST_RAIN_WRAPPED : DUST_DEFAULT;
    }

    private record TumbleRollState(long tick, float roll, float speed) {
    }

    /**
     * The broad wind bed itself.
     *
     * <p>Unlike the roar it computes no gain of its own: it is a positioned loop with a linear attenuation,
     * so its volume is its audible radius in the engine's own units and it fades in a straight line to nothing
     * at that range, exactly like the hum. What it adds over the hum is the gust envelope - a pair of slow
     * sines, seeded from the funnel's own position, swelling and easing the bed so it reads as air moving past
     * rather than as a held tone - and the fact that it needs no threshold at all, so every tornado has it.
     */
    private static final class TornadoWindBedSound extends AbstractTickableSoundInstance {
        private static final float BLOCKS_PER_VOLUME = 16.0F;
        private static final float MIN_VOLUME = 0.02F;
        private static final float RISE = 0.020F;
        private static final float FALL = 0.012F;

        private float targetVolume;
        private float targetPitch;
        private int ticksWithoutUpdate;
        private final float gustPhase;

        private TornadoWindBedSound(FearSample sample) {
            super(ModContent.TORNADO_ROAR_FAR_SOUND.get(), SoundSource.WEATHER, RandomSource.create());
            // Seeded from where the funnel is, so two storms on screen breathe out of step rather than
            // gusting together on the same clock.
            gustPhase = (float) (sample.center().x * 0.013D + sample.center().z * 0.019D);
            looping = true;
            delay = 0;
            attenuation = SoundInstance.Attenuation.LINEAR;
            volume = MIN_VOLUME;
            pitch = 0.72F;
            update(sample);
        }

        private void update(FearSample sample) {
            ticksWithoutUpdate = 0;
            x = sample.center().x;
            y = sample.center().y;
            z = sample.center().z;
            float radius = Config.TORNADO_IMMERSION_WIND_BED_RADIUS_BLOCKS.get().floatValue();
            float loudness = Config.TORNADO_IMMERSION_WIND_BED_VOLUME.get().floatValue();
            float wind = Mth.clamp((float) (sample.windMph() / 200.0D), 0.0F, 1.0F);
            float eye = Config.TORNADO_IMMERSION_EYE_EFFECT.get() ? insideEyeIntensity : 0.0F;
            double tick = Minecraft.getInstance().level == null ? 0.0D : Minecraft.getInstance().level.getGameTime();
            float gust = 0.72F + 0.28F * (float) Math.sin(tick * 0.021D + gustPhase);
            gust *= 0.87F + 0.13F * (float) Math.sin(tick * 0.079D + gustPhase * 1.7D);
            targetVolume = Mth.clamp(radius / BLOCKS_PER_VOLUME * loudness
                    * (0.45F + wind * 0.55F) * gust * (1.0F - eye * 0.35F), 0.0F, 72.0F);
            targetPitch = Mth.clamp(0.60F + wind * 0.18F + (gust - 0.72F) * 0.10F, 0.40F, 1.10F);
        }

        private void fadeOut() {
            targetVolume = 0.0F;
        }

        private void requestStop() {
            stop();
        }

        @Override
        public void tick() {
            ticksWithoutUpdate++;
            volume = Mth.lerp(targetVolume > volume ? RISE : FALL, volume, targetVolume);
            pitch = Mth.lerp(0.05F, pitch, targetPitch);
            if (!Config.TORNADO_IMMERSION_ENABLED.get() || !Config.TORNADO_IMMERSION_WIND_BED.get()
                    || ticksWithoutUpdate > 30 || volume < 0.01F && targetVolume <= 0.0F) {
                stop();
            }
        }
    }

    /**
     * The humming loop itself.
     *
     * <p>Its volume is a radius, not a gain. Minecraft plays a positioned sound through OpenAL's linear
     * distance model and sizes that model from {@code max(volume, 1) * 16} blocks, so the config's radius in
     * blocks becomes a volume of {@code radius / 16} and the gain then falls in a straight line from full at
     * the funnel to nothing at that range. That is also why the loop starts at a hair above silence rather
     * than at silence: the engine drops an instance whose volume is zero, and a spin-up from zero would never
     * be heard at all.
     *
     * <p>The pitch is the one thing about it that moves with the storm, and only slightly: a deeper drone as
     * the vortex gets more violent, plus a slow wobble so it breathes. The recording is a drone, not an
     * instrument with a key, so transposing it a little reads as the vortex deepening rather than as a note
     * being played.
     */
    private static final class TornadoHumSound extends AbstractTickableSoundInstance {
        /** Blocks of audible radius one unit of volume buys - the engine's own attenuation distance. */
        private static final float BLOCKS_PER_VOLUME = 16.0F;
        /** Never zero: the engine refuses to play an instance whose volume is zero. */
        private static final float MIN_VOLUME = 0.02F;
        /** How fast it fades in and out, per tick. Rising slower than the roar: this is mass, not wind. */
        private static final float RISE = 0.035F;
        private static final float FALL = 0.020F;

        private float targetVolume;
        private float targetPitch;
        private int ticksWithoutUpdate;

        private TornadoHumSound(FearSample sample) {
            super(ModContent.TORNADO_HUM_SOUND.get(), SoundSource.WEATHER, RandomSource.create());
            looping = true;
            delay = 0;
            attenuation = SoundInstance.Attenuation.LINEAR;
            volume = MIN_VOLUME;
            pitch = 0.96F;
            update(sample, 0.0F);
        }

        private void update(FearSample sample, float urgency) {
            ticksWithoutUpdate = 0;
            x = sample.center().x;
            y = sample.center().y;
            z = sample.center().z;
            float radius = Config.TORNADO_IMMERSION_HUM_RADIUS_BLOCKS.get().floatValue();
            float loudness = Config.TORNADO_IMMERSION_HUM_VOLUME.get().floatValue();
            float eye = Config.TORNADO_IMMERSION_EYE_EFFECT.get() ? insideEyeIntensity : 0.0F;
            double tick = Minecraft.getInstance().level == null ? 0.0D : Minecraft.getInstance().level.getGameTime();
            float wobble = (float) (Math.sin(tick * 0.17D) * 0.020D + Math.sin(tick * 0.41D) * 0.008D);
            // Volume is the radius, and it swells with how far past the threshold the vortex is: a tornado
            // that has just reached it hums from closer than one that has doubled it.
            targetVolume = Mth.clamp(radius / BLOCKS_PER_VOLUME * loudness
                    * (0.62F + urgency * 0.38F) * (1.0F - eye * 0.40F), 0.0F, 72.0F);
            targetPitch = Mth.clamp(0.96F - urgency * 0.14F + wobble, 0.60F, 1.30F);
        }

        private void fadeOut() {
            targetVolume = 0.0F;
        }

        private void requestStop() {
            stop();
        }

        @Override
        public void tick() {
            ticksWithoutUpdate++;
            volume = Mth.lerp(targetVolume > volume ? RISE : FALL, volume, targetVolume);
            pitch = Mth.lerp(0.08F, pitch, targetPitch);
            if (!Config.TORNADO_IMMERSION_ENABLED.get() || !Config.TORNADO_IMMERSION_HUM_SOUND.get()
                    || ticksWithoutUpdate > 30 || volume < 0.01F && targetVolume <= 0.0F) {
                stop();
            }
        }
    }

    /**
     * One loop of the roar, either the full-band one or its air-absorbed twin.
     *
     * <p>What makes this different from an ordinary ambient sound is that it declares no attenuation and
     * computes its own gain from the distance to the funnel's wall. The engine's linear model scales loudness
     * with range, so the only way to make a sound loud at the funnel would be to make the funnel's audible
     * radius small, and the only way to make it carry would be to make it quiet nearby - and a tornado is
     * both at once. Doing the falloff here breaks that link: full voice at the wall, a real presence hundreds
     * of blocks out, silence beyond the configured range.
     *
     * <p>The two layers are level-matched and their weights sum to one, so the crossfade between them as the
     * funnel recedes changes the tone and not the volume.
     */
    private static final class TornadoRoarSound extends AbstractTickableSoundInstance {
        /** True for the air-absorbed layer: the one that carries the distance. */
        private final boolean distanceLayer;
        private float targetVolume;
        private float targetPitch;
        private int ticksWithoutUpdate;

        private TornadoRoarSound(FearSample sample, SoundEvent event, boolean distanceLayer) {
            super(event, SoundSource.WEATHER, RandomSource.create());
            this.distanceLayer = distanceLayer;
            looping = true;
            delay = 0;
            attenuation = SoundInstance.Attenuation.NONE;
            volume = 0.01F;
            pitch = 0.78F;
            update(sample, roarListenerDistance());
        }

        private void update(FearSample sample, double listenerDistance) {
            ticksWithoutUpdate = 0;
            x = sample.center().x;
            y = sample.center().y;
            z = sample.center().z;
            float gain = roarDistanceGain(listenerDistance);
            float nearWeight = roarNearWeight(listenerDistance);
            float weight = distanceLayer ? 1.0F - nearWeight : nearWeight;
            float wind = Mth.clamp((float) (sample.windMph() / 260.0D), 0.0F, 1.0F);
            float blowout = Config.TORNADO_IMMERSION_BLOWOUT_AUDIO.get()
                    ? Mth.clamp(insideCoreIntensity * Config.TORNADO_IMMERSION_BLOWOUT_AUDIO_INTENSITY.get().floatValue(), 0.0F, 1.65F)
                    : 0.0F;
            float ef3 = Config.TORNADO_IMMERSION_INTENSE_EF3_EFFECTS.get() && sample.windMph() >= Config.TORNADO_IMMERSION_EF3_WIND_THRESHOLD_MPH.get().doubleValue()
                    ? Config.TORNADO_IMMERSION_EF3_ROAR_MULTIPLIER.get().floatValue()
                    : 1.0F;
            float eye = Config.TORNADO_IMMERSION_EYE_EFFECT.get() ? insideEyeIntensity : 0.0F;
            double tick = Minecraft.getInstance().level == null ? 0.0D : Minecraft.getInstance().level.getGameTime();
            float wobble = (float) (Math.sin(tick * 0.37D) * 0.055D + Math.sin(tick * 1.17D) * 0.022D) * blowout;
            // A weak vortex roars quietly and a violent one roars at full voice: the wind in it is the source,
            // and how far away it is is the falloff above. Being caught in the core adds the last of it - the
            // file's peak is 0.88, so a gain just over one saturates exactly the way being inside one should.
            float voice = 0.45F + wind * 0.55F;
            targetVolume = Mth.clamp(gain * weight * voice
                    * Config.TORNADO_IMMERSION_ROAR_LOUDNESS.get().floatValue()
                    * (1.0F + blowout * 0.35F - eye * 0.30F) * ef3, 0.0F, 1.40F);
            // Pitch stays a property of the vortex rather than of the listener: distance filters a sound, it
            // does not transpose it, which is why the far layer is a separate filtered file and not a low pitch.
            targetPitch = Mth.clamp(0.58F + wind * 0.26F - blowout * 0.10F - eye * 0.09F + wobble
                    - (ef3 - 1.0F) * 0.055F, 0.34F, 0.98F);
        }

        private void fadeOut() {
            targetVolume = 0.0F;
        }

        private void requestStop() {
            stop();
        }

        @Override
        public void tick() {
            ticksWithoutUpdate++;
            volume = Mth.lerp(0.18F, volume, targetVolume);
            pitch = Mth.lerp(0.12F, pitch, targetPitch);
            if (!Config.TORNADO_IMMERSION_ENABLED.get() || !Config.TORNADO_IMMERSION_ROAR_SOUND.get() || ticksWithoutUpdate > 30 || volume < 0.01F && targetVolume <= 0.0F) {
                stop();
            }
        }
    }
}
