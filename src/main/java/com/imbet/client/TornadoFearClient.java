package com.imbet.client;

import com.imbet.Config;
import com.imbet.registry.ModContent;
import com.imbet.weather.AddonWeatherEvent;
import com.imbet.weather.ClientAddonWeatherEventStore;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.DynamicStormState;
import com.imbet.weather.StormSubtype;
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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import weather2.ClientTickHandler;
import weather2.weathersystem.WeatherManagerClient;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.ArrayList;

public final class TornadoFearClient {
    private static float fearIntensity;
    private static float targetFearIntensity;
    private static float insideDamagePathIntensity;
    private static float insideCoreIntensity;
    private static float insideEyeIntensity;
    private static FearSample activeSample = FearSample.NONE;
    private static TornadoRoarSound roarSound;
    private static int particlesThisTick;
    private static long nextImpactSoundTick;

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

        tickRoar(minecraft);
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
    }

    public static void onFov(ViewportEvent.ComputeFov event) {
        if (!Config.TORNADO_IMMERSION_ENABLED.get() || !Config.TORNADO_IMMERSION_FOV_PRESSURE.get() || Config.TORNADO_IMMERSION_ACCESSIBILITY_REDUCED_MOTION.get()) {
            return;
        }
        double pressure = Mth.clamp(fearIntensity * 0.032F + insideCoreIntensity * 0.030F - insideEyeIntensity * 0.012F, 0.0F, 0.055F);
        event.setFOV(event.getFOV() * (1.0D - pressure));
    }

    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        if (!Config.TORNADO_IMMERSION_ENABLED.get() || !Config.TORNADO_IMMERSION_FOG_PRESSURE.get() || fearIntensity <= 0.03F) {
            return;
        }
        float insideMultiplier = Config.TORNADO_IMMERSION_INSIDE_FOG_MULTIPLIER.get().floatValue();
        float corePressure = Config.TORNADO_IMMERSION_INSIDE_CORE_EFFECTS.get() ? insideCoreIntensity * 0.74F * insideMultiplier : 0.0F;
        float eyeRelief = Config.TORNADO_IMMERSION_EYE_EFFECT.get() ? insideEyeIntensity * 0.38F : 0.0F;
        float pressure = Mth.clamp(fearIntensity * 0.48F + corePressure - eyeRelief, 0.0F, 0.86F);
        float dust = Mth.clamp(insideCoreIntensity * 0.60F * insideMultiplier, 0.0F, 0.70F);
        float redTarget = Mth.lerp(dust, event.getRed() * 0.50F, 0.70F);
        float greenTarget = Mth.lerp(dust, event.getGreen() * 0.54F, 0.68F);
        float blueTarget = Mth.lerp(dust, event.getBlue() * 0.64F, 0.66F);
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
        if (!Config.TORNADO_IMMERSION_ENABLED.get() || !Config.TORNADO_IMMERSION_FOG_PRESSURE.get() || fearIntensity <= 0.08F) {
            return;
        }
        float insideMultiplier = Config.TORNADO_IMMERSION_INSIDE_FOG_MULTIPLIER.get().floatValue();
        float pressure = Mth.clamp(fearIntensity * 0.72F + insideDamagePathIntensity * 0.30F + insideCoreIntensity * 0.70F * insideMultiplier, 0.0F, 1.0F);
        float targetFar = Mth.lerp(pressure, 150.0F, 13.0F);
        if (Config.TORNADO_IMMERSION_EYE_EFFECT.get() && insideEyeIntensity > 0.02F) {
            targetFar = Mth.lerp(insideEyeIntensity * 0.55F, targetFar, 46.0F);
        }
        event.setFarPlaneDistance(Math.min(event.getFarPlaneDistance(), targetFar));
        event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(), 0.0F));
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

    public static Vec3 activeWindVector() {
        return activeSample == null ? Vec3.ZERO : activeSample.windVector();
    }

    public static Vec3 activeCenter() {
        return activeSample == null ? Vec3.ZERO : activeSample.center();
    }

    private static void updateInsideState() {
        float targetPath = 0.0F;
        float targetCore = 0.0F;
        float targetEye = 0.0F;
        if (Config.TORNADO_IMMERSION_INSIDE_CORE_EFFECTS.get() && activeSample != FearSample.NONE) {
            double pathDistance = Math.max(6.0D, activeSample.damageWidth() * 0.50D);
            double coreDistance = Math.max(Math.max(activeSample.coreRadius(), activeSample.radiusOfMaxWind() * 0.65F), 8.0F);
            double eyeDistance = Math.max(activeSample.coreRadius() * 0.45F, 4.0F);
            targetPath = Mth.clamp((float) (1.0D - activeSample.distance() / pathDistance), 0.0F, 1.0F);
            targetCore = Mth.clamp((float) (1.0D - activeSample.distance() / coreDistance), 0.0F, 1.0F);
            if (Config.TORNADO_IMMERSION_EYE_EFFECT.get() && activeSample.windMph() >= 105.0D && activeSample.distance() <= eyeDistance) {
                float windGate = Mth.clamp((float) ((activeSample.windMph() - 95.0D) / 105.0D), 0.0F, 1.0F);
                targetEye = Mth.clamp((float) (1.0D - activeSample.distance() / eyeDistance), 0.0F, 1.0F) * windGate;
            }
        }
        insideDamagePathIntensity = lerpInsideState(insideDamagePathIntensity, targetPath);
        insideCoreIntensity = lerpInsideState(insideCoreIntensity, targetCore);
        insideEyeIntensity = lerpInsideState(insideEyeIntensity, targetEye);
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
                && fearIntensity > 0.04F;
    }

    private static float rumbleStrength() {
        float wind = activeSample == null ? 0.0F : Mth.clamp((float) (activeSample.windMph() / 250.0D), 0.0F, 1.0F);
        return Mth.clamp(fearIntensity * (0.45F + wind * 0.55F) + insideCoreIntensity * 0.36F - insideEyeIntensity * 0.16F, 0.0F, 1.0F);
    }

    private static void reset(Minecraft minecraft) {
        fearIntensity = 0.0F;
        targetFearIntensity = 0.0F;
        insideDamagePathIntensity = 0.0F;
        insideCoreIntensity = 0.0F;
        insideEyeIntensity = 0.0F;
        activeSample = FearSample.NONE;
        if (roarSound != null) {
            roarSound.requestStop();
            if (minecraft != null) {
                minecraft.getSoundManager().stop(roarSound);
            }
            roarSound = null;
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
            best = stronger(best, sampleFromValues(center, vector, event.maxWindMph(), distance, event.radius() * 2.0F, coreRadius, radiusOfMaxWind, event.type() == StormSubtype.DUST_DEVIL ? 0.52F : 0.78F));
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
        return sampleFromValues(center, wind.mphVector(), Math.max(maxWindMph, wind.strongestLocalMph()), distance, damageWidth, coreRadius, radiusOfMaxWind, 1.0F + stage * 0.22F + subtypeBoost);
    }

    private static FearSample sampleFromValues(Vec3 center, Vec3 windVector, double windMph, double distance, float damageWidth, float coreRadius, float radiusOfMaxWind, float multiplier) {
        double range = Config.TORNADO_IMMERSION_RENDER_RANGE_BLOCKS.get();
        float distanceScore = Mth.clamp((float) (1.0D - distance / range), 0.0F, 1.0F);
        float windScore = Mth.clamp((float) (windMph / 235.0D), 0.0F, 1.0F);
        float pathScore = Mth.clamp((float) (1.0D - distance / Math.max(18.0F, damageWidth * 0.72F)), 0.0F, 1.0F);
        float coreScore = Mth.clamp((float) (1.0D - distance / Math.max(10.0F, Math.max(coreRadius, radiusOfMaxWind * 0.65F))), 0.0F, 1.0F);
        float intensity = (distanceScore * 0.30F + windScore * 0.34F + pathScore * 0.24F + coreScore * 0.18F) * multiplier;
        return new FearSample(center, windVector, windMph, distance, damageWidth, coreRadius, radiusOfMaxWind, Mth.clamp(intensity, 0.0F, 1.0F));
    }

    private static FearSample stronger(FearSample first, FearSample second) {
        return second.intensity() > first.intensity() ? second : first;
    }

    private static boolean isFearVortex(StormSubtype subtype) {
        return subtype == StormSubtype.LANDSPOUT || subtype == StormSubtype.DUST_DEVIL || subtype == StormSubtype.WATERSPOUT;
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

    private static void tickRoar(Minecraft minecraft) {
        if (!Config.TORNADO_IMMERSION_ROAR_SOUND.get() || fearIntensity <= 0.035F || activeSample == FearSample.NONE) {
            if (roarSound != null) {
                roarSound.fadeOut();
            }
            return;
        }
        if (roarSound == null || !minecraft.getSoundManager().isActive(roarSound)) {
            roarSound = new TornadoRoarSound(activeSample);
            minecraft.getSoundManager().play(roarSound);
        }
        roarSound.update(activeSample, fearIntensity);
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
        if (sprite == null) {
            level.addParticle(fallback, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z);
            return;
        }
        EntityRotFX particle = new EntityRotFX(level, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z);
        particle.setSprite(sprite);
        particle.setScale(scale);
        particle.setColor(red, green, blue);
        particle.setAlpha(alpha);
        particle.setMaxAge(maxAge);
        particle.setGravity(0.0F);
        particle.setCanCollide(false);
        particle.setFacePlayer(true);
        particle.facePlayerYaw = true;
        particle.windWeight = windWeight;
        particle.spawnAsWeatherEffect();
    }

    private record FearSample(Vec3 center, Vec3 windVector, double windMph, double distance, float damageWidth, float coreRadius, float radiusOfMaxWind, float intensity) {
        private static final FearSample NONE = new FearSample(Vec3.ZERO, Vec3.ZERO, 0.0D, Double.MAX_VALUE, 0.0F, 0.0F, 0.0F, 0.0F);
    }

    private static final class TornadoRoarSound extends AbstractTickableSoundInstance {
        private float targetVolume;
        private float targetPitch;
        private int ticksWithoutUpdate;

        private TornadoRoarSound(FearSample sample) {
            super(ModContent.TORNADO_ROAR_SOUND.get(), SoundSource.WEATHER, RandomSource.create());
            looping = true;
            delay = 0;
            attenuation = SoundInstance.Attenuation.LINEAR;
            volume = 0.01F;
            pitch = 0.78F;
            update(sample, 0.08F);
        }

        private void update(FearSample sample, float intensity) {
            ticksWithoutUpdate = 0;
            x = sample.center().x;
            y = sample.center().y;
            z = sample.center().z;
            float distanceFade = Mth.clamp((float) (1.0D - sample.distance() / Math.max(64.0D, Config.TORNADO_IMMERSION_RENDER_RANGE_BLOCKS.get())), 0.0F, 1.0F);
            float wind = Mth.clamp((float) (sample.windMph() / 260.0D), 0.0F, 1.0F);
            float blowout = Config.TORNADO_IMMERSION_BLOWOUT_AUDIO.get()
                    ? Mth.clamp(insideCoreIntensity * Config.TORNADO_IMMERSION_BLOWOUT_AUDIO_INTENSITY.get().floatValue(), 0.0F, 1.65F)
                    : 0.0F;
            float eye = Config.TORNADO_IMMERSION_EYE_EFFECT.get() ? insideEyeIntensity : 0.0F;
            double tick = Minecraft.getInstance().level == null ? 0.0D : Minecraft.getInstance().level.getGameTime();
            float wobble = (float) (Math.sin(tick * 0.37D) * 0.055D + Math.sin(tick * 1.17D) * 0.022D) * blowout;
            targetVolume = Mth.clamp(0.06F + intensity * 0.95F + distanceFade * 0.32F + blowout * 0.58F - eye * 0.46F, 0.0F, 1.85F);
            targetPitch = Mth.clamp(0.58F + wind * 0.26F - intensity * 0.08F - blowout * 0.13F - eye * 0.09F + wobble, 0.36F, 0.98F);
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
