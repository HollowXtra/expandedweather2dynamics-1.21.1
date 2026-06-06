package com.imbet.client;

import com.imbet.Config;
import com.imbet.weather.CompatHooks;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.DynamicStormState;
import com.imbet.weather.SupercellStructureService;
import extendedrenderer.particle.ParticleRegistry;
import extendedrenderer.particle.entity.EntityRotFX;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import weather2.ClientTickHandler;
import weather2.config.ConfigMisc;
import weather2.weathersystem.WeatherManagerClient;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class VolumetricPrecipitationRenderer {
    private static final double TWO_PI = Math.PI * 2.0D;
    private static long lastParticleTick = Long.MIN_VALUE;

    private VolumetricPrecipitationRenderer() {
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER || !Config.VOLUMETRIC_DOWNFALL_ENABLED.get()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Player player = minecraft.player;
        WeatherManagerClient manager = ClientTickHandler.weatherManager;
        if (level == null || player == null || manager == null || Config.VOLUMETRIC_DOWNFALL_MAX_CELLS_RENDERED.get() <= 0 || Config.VOLUMETRIC_DOWNFALL_MAX_VERTICES_PER_FRAME.get() <= 0) {
            return;
        }

        long gameTime = level.getGameTime();
        if (lastParticleTick == gameTime) {
            return;
        }
        lastParticleTick = gameTime;

        Vec3 playerPos = player.position();
        List<StormObject> storms = renderableStorms(manager, playerPos, Config.VOLUMETRIC_DOWNFALL_RENDER_RANGE_BLOCKS.get());
        if (storms.isEmpty()) {
            return;
        }

        int[] budget = {particleBudget()};
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        for (StormObject storm : storms.stream().limit(Config.VOLUMETRIC_DOWNFALL_MAX_CELLS_RENDERED.get()).toList()) {
            spawnStormParticles(level, storm, playerPos, gameTime, partial, budget);
            if (budget[0] <= 0) {
                break;
            }
        }
    }

    public static boolean shouldBypassVanillaRain() {
        if (!Config.VOLUMETRIC_DOWNFALL_ENABLED.get() || !Config.VOLUMETRIC_DOWNFALL_BYPASS_VANILLA_NEAR_CELLS.get()) {
            return false;
        }
        if (shaderCompatibilityActive()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        WeatherManagerClient manager = ClientTickHandler.weatherManager;
        if (minecraft.level == null || player == null || manager == null) {
            return false;
        }
        return !renderableStorms(manager, player.position(), Math.min(Config.VOLUMETRIC_DOWNFALL_RENDER_RANGE_BLOCKS.get(), 512)).isEmpty();
    }

    public static boolean shaderCompatibilityActive() {
        String mode = Config.VOLUMETRIC_DOWNFALL_SHADER_COMPATIBILITY_MODE.get();
        if ("on".equalsIgnoreCase(mode)) {
            return true;
        }
        if ("off".equalsIgnoreCase(mode)) {
            return false;
        }
        return CompatHooks.shaderPackLoaded();
    }

    public static float localDownfallIntensity() {
        if (!Config.VOLUMETRIC_DOWNFALL_ENABLED.get() || !shaderCompatibilityActive()) {
            return 0.0F;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        WeatherManagerClient manager = ClientTickHandler.weatherManager;
        if (minecraft.level == null || player == null || manager == null) {
            return 0.0F;
        }
        List<StormObject> storms = renderableStorms(manager, player.position(), Math.min(Config.VOLUMETRIC_DOWNFALL_RENDER_RANGE_BLOCKS.get(), 512));
        if (storms.isEmpty()) {
            return 0.0F;
        }
        double best = 0.0D;
        for (StormObject storm : storms) {
            double distance = Math.sqrt(horizontalDistanceSqr(storm.pos, player.position()));
            double radius = Math.max(48.0D, stormRadius(storm));
            double proximity = Mth.clamp(1.0D - distance / (radius + 80.0D), 0.0D, 1.0D);
            double stage = Mth.clamp((storm.levelCurIntensityStage - StormObject.STATE_NORMAL + 1.0D) / 7.0D, 0.18D, 1.0D);
            best = Math.max(best, proximity * stage);
        }
        return Mth.clamp((float) best, 0.0F, 1.0F);
    }

    private static List<StormObject> renderableStorms(WeatherManagerClient manager, Vec3 playerPos, int range) {
        List<StormObject> storms = new ArrayList<>();
        double rangeSqr = (double) range * range;
        for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
            if (!(object instanceof StormObject storm) || storm.pos == null || storm.isDead || DynamicStormController.isAbsorbedOrDecaying(storm)) {
                continue;
            }
            if (!shouldRenderDownfall(storm)) {
                continue;
            }
            double radius = stormRadius(storm);
            double distanceSqr = horizontalDistanceSqr(storm.pos, playerPos);
            if (distanceSqr <= (Math.sqrt(rangeSqr) + radius) * (Math.sqrt(rangeSqr) + radius)) {
                storms.add(storm);
            }
        }
        storms.sort(Comparator.comparingDouble(storm -> horizontalDistanceSqr(storm.pos, playerPos)));
        return storms;
    }

    private static void spawnStormParticles(ClientLevel level, StormObject storm, Vec3 playerPos, long gameTime, float partial, int[] budget) {
        double radius = stormRadius(storm);
        double distance = Math.sqrt(horizontalDistanceSqr(storm.pos, playerPos));
        DynamicStormState state = DynamicStormController.stateFor(storm);
        SupercellStructureService.StructureSample structure = SupercellStructureService.sample(storm, state);
        Vec3 forward = horizontalOrDefault(structure.forward(), motionDirection(storm));
        Vec3 right = horizontalOrDefault(structure.right(), new Vec3(-forward.z, 0.0D, forward.x));
        boolean hail = hailCore(storm) >= Config.VOLUMETRIC_DOWNFALL_HAIL_TINT_THRESHOLD.get();
        boolean rainWrappedVortex = isRainWrappedVortex(storm);
        Vec3 precipitationCenter = rainCenter(storm, structure, forward, right, radius, rainWrappedVortex);
        float intensity = Mth.clamp((storm.levelCurIntensityStage - StormObject.STATE_THUNDER + 1) / 6.0F, 0.2F, 1.0F);
        int count = Mth.clamp((int) (8.0D + radius / 24.0D + intensity * 18.0F), 8, hail ? 54 : 44);
        if (rainWrappedVortex && !hail) {
            count = Math.max(count, Mth.clamp((int) (38.0D + radius / 13.0D + intensity * 52.0F), 52, 136));
        }
        if (distance > 520.0D) {
            count = Math.max(5, Math.round(count * 0.48F));
        } else if (distance > 300.0D) {
            count = Math.max(6, Math.round(count * 0.68F));
        }

        double rotationSign = DynamicStormController.rotationSign(storm);
        Vec3 stormMotion = storm.motion == null ? Vec3.ZERO : storm.motion;
        Vec3 motionDrift = new Vec3(stormMotion.x, 0.0D, stormMotion.z).scale(0.12D);
        double top = Math.min(level.getMaxBuildHeight() - 4.0D, topY(storm));

        for (int index = 0; index < count && budget[0] > 0; index++) {
            boolean coreWrap = rainWrappedVortex && index % 4 == 0;
            double forwardSpread = radius * (coreWrap ? 0.44D : rainWrappedVortex ? 0.92D : 0.78D);
            double lateralSpread = radius * (coreWrap ? 0.48D : rainWrappedVortex ? 0.76D : 0.82D);
            double forwardOffset = (level.random.nextDouble() - (coreWrap ? 0.50D : 0.30D)) * forwardSpread;
            double lateralOffset = (level.random.nextDouble() - (coreWrap ? 0.50D : 0.50D)) * lateralSpread;
            Vec3 sheetCenter = coreWrap ? storm.pos.add(forward.scale(radius * 0.08D)).add(right.scale(-radius * 0.08D)) : precipitationCenter;
            Vec3 sample = sheetCenter.add(forward.scale(forwardOffset)).add(right.scale(lateralOffset)).add(motionDrift.scale(24.0D));
            double x = sample.x;
            double z = sample.z;
            BlockPos samplePos = BlockPos.containing(x, 0.0D, z);
            if (!level.isLoaded(samplePos)) {
                continue;
            }
            double floor = floorY(level, x, z);
            double minY = Math.min(top - 4.0D, floor + (rainWrappedVortex ? 3.0D : 8.0D));
            double localTop = rainWrappedVortex
                    ? Math.min(top - 4.0D, floor + Mth.lerp(level.random.nextDouble(), 58.0D, 138.0D + intensity * 34.0D))
                    : top;
            double heightBlend = Math.pow(level.random.nextDouble(), rainWrappedVortex ? 1.28D : 0.88D);
            double y = Mth.lerp(heightBlend, minY, Math.max(minY + 2.0D, localTop));
            Vec3 particlePos = new Vec3(x, y, z);
            Vec3 inward = new Vec3(storm.pos.x - x, 0.0D, storm.pos.z - z);
            if (inward.horizontalDistanceSqr() > 1.0E-4D) {
                inward = inward.normalize();
            }
            Vec3 tangent = new Vec3(-inward.z * rotationSign, 0.0D, inward.x * rotationSign);
            double fallSpeed = hail ? -0.66D : -0.48D;
            Vec3 velocity = forward.scale(0.026D + intensity * 0.034D)
                    .add(tangent.scale(rainWrappedVortex ? 0.018D + intensity * 0.020D : 0.006D))
                    .add(inward.scale(hail ? 0.012D : rainWrappedVortex ? 0.022D : 0.008D))
                    .add(motionDrift)
                    .add(0.0D, fallSpeed - level.random.nextDouble() * 0.16D, 0.0D);
            double distantFactor = Mth.clamp((distance - 180.0D) / 620.0D, 0.0D, 1.0D);
            double sampleRadius = Math.sqrt(forwardOffset * forwardOffset + lateralOffset * lateralOffset);
            double curtainFactor = Mth.clamp((sampleRadius / Math.max(1.0D, radius) - 0.18D) / 0.82D, 0.0D, 1.0D);
            spawnDownfallParticle(level, particlePos, velocity, hail, rainWrappedVortex, intensity, distantFactor, curtainFactor, budget);

            if (!hail && distantFactor < 0.20D && level.random.nextFloat() < 0.18F + intensity * 0.18F && budget[0] > 0) {
                Vec3 mistPos = particlePos.add(0.0D, -level.random.nextDouble() * 5.0D, 0.0D);
                spawnMistParticle(level, mistPos, tangent.scale(0.018D).add(inward.scale(0.018D)), intensity, budget);
            }
        }
    }

    private static void spawnDownfallParticle(ClientLevel level, Vec3 pos, Vec3 velocity, boolean hail, boolean rainWrappedVortex, float intensity, double distantFactor, double curtainFactor, int[] budget) {
        TextureAtlasSprite sprite;
        if (hail) {
            sprite = level.random.nextFloat() < 0.34F ? ParticleRegistry.hail : ParticleRegistry.rain_white;
        } else {
            sprite = downfallSheetSprite();
        }
        float sheetFactor = (float) Mth.clamp(Math.max(distantFactor, curtainFactor * 0.65D), 0.0D, 1.0D);
        float scale = hail
                ? 0.58F + level.random.nextFloat() * 0.46F
                : Mth.lerp(
                        sheetFactor,
                        rainWrappedVortex ? 18.0F + level.random.nextFloat() * (16.0F + intensity * 10.0F) : 16.0F + level.random.nextFloat() * (12.0F + intensity * 8.0F),
                        rainWrappedVortex ? 34.0F + level.random.nextFloat() * (24.0F + intensity * 16.0F) : 28.0F + level.random.nextFloat() * (18.0F + intensity * 12.0F)
                );
        float alpha = hail
                ? Mth.clamp(Config.VOLUMETRIC_DOWNFALL_HAIL_OPACITY.get().floatValue() * 0.58F, 0.12F, 0.48F)
                : Mth.clamp(Config.VOLUMETRIC_DOWNFALL_RAIN_OPACITY.get().floatValue() * (rainWrappedVortex ? 0.96F : 0.82F), rainWrappedVortex ? 0.30F : 0.22F, rainWrappedVortex ? 0.54F : 0.40F);
        float red = hail ? 0.82F : Mth.lerp(sheetFactor, rainWrappedVortex ? 0.30F : 0.36F, rainWrappedVortex ? 0.18F : 0.26F);
        float green = hail ? 0.96F : Mth.lerp(sheetFactor, rainWrappedVortex ? 0.32F : 0.38F, rainWrappedVortex ? 0.20F : 0.28F);
        float blue = hail ? 0.88F : Mth.lerp(sheetFactor, rainWrappedVortex ? 0.38F : 0.46F, rainWrappedVortex ? 0.28F : 0.36F);
        int maxAge = hail ? 26 + level.random.nextInt(14) : 24 + level.random.nextInt(24) + Math.round(sheetFactor * 30.0F);
        float windWeight = hail ? 0.08F : Mth.lerp(sheetFactor, 0.22F, 0.10F);
        spawnWeather2Particle(level, pos, velocity, sprite, scale, red, green, blue, alpha, maxAge, windWeight, budget);
    }

    private static TextureAtlasSprite downfallSheetSprite() {
        return ParticleRegistry.downfall3 == null ? ParticleRegistry.cloud : ParticleRegistry.downfall3;
    }

    private static void spawnMistParticle(ClientLevel level, Vec3 pos, Vec3 velocity, float intensity, int[] budget) {
        float scale = 2.4F + level.random.nextFloat() * (2.4F + intensity * 3.0F);
        float alpha = 0.08F + intensity * 0.10F;
        spawnWeather2Particle(level, pos, velocity.add(0.0D, -0.06D, 0.0D), ParticleRegistry.cloud, scale, 0.48F, 0.52F, 0.58F, alpha, 34 + level.random.nextInt(24), 0.12F, budget);
    }

    private static void spawnWeather2Particle(ClientLevel level, Vec3 pos, Vec3 velocity, TextureAtlasSprite sprite, float scale, float red, float green, float blue, float alpha, int maxAge, float windWeight, int[] budget) {
        if (budget[0] <= 0) {
            return;
        }
        budget[0]--;
        if (!Config.EXTENDED_RENDERING_ENABLED.get() || sprite == null) {
            level.addParticle(ParticleTypes.RAIN, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z);
            return;
        }
        EntityRotFX particle = new EntityRotFX(level, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z);
        particle.setSprite(sprite);
        particle.setScale(Mth.clamp(scale, 0.15F, 180.0F));
        particle.setColor(Mth.clamp(red, 0.0F, 1.0F), Mth.clamp(green, 0.0F, 1.0F), Mth.clamp(blue, 0.0F, 1.0F));
        boolean downfallSheet = sprite == ParticleRegistry.downfall3;
        boolean cloudSheet = sprite == ParticleRegistry.cloud || sprite == ParticleRegistry.cloud256;
        float clampedAlpha = downfallSheet
                ? Mth.clamp(alpha * 1.18F + 0.08F, 0.22F, 0.80F)
                : cloudSheet
                ? Mth.clamp(alpha * 1.22F + 0.08F, 0.14F, 0.96F)
                : Mth.clamp(alpha, 0.04F, 0.96F);
        particle.setAlpha(clampedAlpha);
        particle.setFullAlphaTarget(clampedAlpha);
        particle.setTicksFadeInMax(downfallSheet ? 2.0F : 4.0F);
        particle.setTicksFadeOutMax(downfallSheet ? 6.0F : 8.0F);
        particle.setMaxAge(Math.max(8, maxAge));
        particle.setGravity(0.0F);
        particle.setCanCollide(false);
        particle.setFacePlayer(!downfallSheet);
        particle.facePlayerYaw = !downfallSheet;
        particle.isTransparent = true;
        particle.renderOrder = downfallSheet ? 2 : cloudSheet ? 1 : 2;
        particle.windWeight = windWeight;
        particle.spinTowardsMotionDirection = true;
        particle.spinFast = true;
        particle.spinFastRate = 0.018F + level.random.nextFloat() * 0.035F;
        particle.setSlantParticleToWind(true);
        if (downfallSheet) {
            float yaw = (float) Math.toDegrees(Math.atan2(velocity.x, velocity.z));
            particle.prevRotationYaw = yaw;
            particle.rotationYaw = yaw;
            particle.prevRotationPitch = 82.0F;
            particle.rotationPitch = 82.0F;
            particle.quatControl = true;
            particle.useRotationAroundCenter = true;
            particle.spinFast = false;
        }
        particle.setRenderDistanceCull(Config.VOLUMETRIC_DOWNFALL_RENDER_RANGE_BLOCKS.get() + 160.0F);
        particle.spawnAsWeatherEffect();
    }

    private static int particleBudget() {
        return Mth.clamp(Config.VOLUMETRIC_DOWNFALL_MAX_VERTICES_PER_FRAME.get() / 180, 24, 180);
    }

    private static boolean shouldRenderDownfall(StormObject storm) {
        if (isLowPrecipitationVortex(storm)) {
            return false;
        }
        if (isRainWrappedVortex(storm)) {
            return true;
        }
        return storm.attrib_precipitation
                || storm.isPrecipitating()
                || storm.levelCurIntensityStage >= StormObject.STATE_THUNDER
                || storm.levelCurIntensityStage >= StormObject.STATE_HAIL;
    }

    private static boolean isRainWrappedVortex(StormObject storm) {
        if (!isTornadicVortex(storm)) {
            return false;
        }
        DynamicStormState state = DynamicStormController.stateFor(storm);
        return !isLowPrecipitationVortex(storm)
                && (storm.isPrecipitating()
                || storm.attrib_precipitation
                || storm.levelCurIntensityStage >= StormObject.STATE_HAIL
                || state.ffdStrength() >= 0.34F
                || state.hailCoreStrength() >= 0.28F);
    }

    private static boolean isLowPrecipitationVortex(StormObject storm) {
        if (!isTornadicVortex(storm)) {
            return false;
        }
        DynamicStormState state = DynamicStormController.stateFor(storm);
        float precipitationScore = state.ffdStrength() * 0.55F
                + state.hailCoreStrength() * 0.34F
                + (storm.isPrecipitating() || storm.attrib_precipitation ? 0.22F : 0.0F)
                + (storm.levelCurIntensityStage >= StormObject.STATE_HAIL ? 0.20F : 0.0F);
        return precipitationScore < 0.36F;
    }

    private static boolean isTornadicVortex(StormObject storm) {
        return DynamicStormController.isDynamicVortex(storm);
    }

    private static Vec3 rainCenter(StormObject storm, SupercellStructureService.StructureSample structure, Vec3 forward, Vec3 right, double radius, boolean rainWrappedVortex) {
        if (storm == null || storm.pos == null) {
            return Vec3.ZERO;
        }
        if (rainWrappedVortex) {
            return storm.pos.add(forward.scale(radius * 0.18D)).add(right.scale(-radius * 0.10D));
        }
        Vec3 forwardFlank = structure == null ? null : structure.forwardFlank();
        if (forwardFlank != null && forwardFlank.horizontalDistanceSqr() > 1.0E-5D) {
            return forwardFlank;
        }
        return storm.pos.add(forward.scale(radius * 0.42D)).add(right.scale(-radius * 0.18D));
    }

    private static Vec3 horizontalOrDefault(Vec3 vector, Vec3 fallback) {
        Vec3 candidate = vector == null ? Vec3.ZERO : new Vec3(vector.x, 0.0D, vector.z);
        if (candidate.horizontalDistanceSqr() > 1.0E-5D) {
            return candidate.normalize();
        }
        Vec3 fallbackHorizontal = fallback == null ? Vec3.ZERO : new Vec3(fallback.x, 0.0D, fallback.z);
        return fallbackHorizontal.horizontalDistanceSqr() > 1.0E-5D ? fallbackHorizontal.normalize() : new Vec3(1.0D, 0.0D, 0.0D);
    }

    private static Vec3 motionDirection(StormObject storm) {
        if (storm != null && storm.motion != null && storm.motion.horizontalDistanceSqr() > 1.0E-5D) {
            return new Vec3(storm.motion.x, 0.0D, storm.motion.z).normalize();
        }
        float angle = storm == null ? 0.0F : storm.getAdjustedAngle();
        double radians = Math.toRadians(angle);
        Vec3 fallback = new Vec3(Math.cos(radians), 0.0D, Math.sin(radians));
        return fallback.horizontalDistanceSqr() > 1.0E-5D ? fallback.normalize() : new Vec3(1.0D, 0.0D, 0.0D);
    }

    private static double stormRadius(StormObject storm) {
        double size = Math.max(Math.max(storm.getSize(), storm.size), storm.maxSize * 0.55D);
        if (DynamicStormController.isDynamicVortex(storm)) {
            DynamicStormState state = DynamicStormController.stateFor(storm);
            size = Math.max(size, state.damagePathWidth() * 1.4D);
        }
        return Mth.clamp(size * 1.25D, 56.0D, 360.0D);
    }

    private static double topY(StormObject storm) {
        int cloudHeight = Math.max(80, ConfigMisc.Cloud_Layer0_Height);
        return Math.max(cloudHeight, storm.currentTopYBlock > 0 ? storm.currentTopYBlock : cloudHeight);
    }

    private static double floorY(ClientLevel level, double x, double z) {
        int step = Math.max(4, Config.VOLUMETRIC_DOWNFALL_HEIGHTMAP_SAMPLE_STEP_BLOCKS.get());
        int sampleX = Mth.floor(x / step) * step;
        int sampleZ = Mth.floor(z / step) * step;
        BlockPos sample = new BlockPos(sampleX, 0, sampleZ);
        if (!level.isLoaded(sample)) {
            return level.getMinBuildHeight();
        }
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, sample).getY() + 0.05D;
    }

    private static float hailCore(StormObject storm) {
        if (storm.levelCurIntensityStage >= StormObject.STATE_HAIL) {
            return 1.0F;
        }
        DynamicStormState state = DynamicStormController.stateFor(storm);
        return state.hailCoreStrength();
    }

    private static double horizontalDistanceSqr(Vec3 first, Vec3 second) {
        double dx = first.x - second.x;
        double dz = first.z - second.z;
        return dx * dx + dz * dz;
    }
}
