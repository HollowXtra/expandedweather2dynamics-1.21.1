package com.imbet.weather;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

public final class ThermodynamicProfileService {
    private ThermodynamicProfileService() {
    }

    public static ThermodynamicProfile compute(ServerLevel level, BlockPos origin) {
        if (level == null || origin == null) {
            return ThermodynamicProfile.calm();
        }
        SereneSeasonsCompat.SeasonSample season = SereneSeasonsCompat.sample(level, origin);
        Sample sample = sampleBiomeThermodynamics(level, origin);
        Vec3 shearVector = shearVector(level, origin);
        double shearMps = Mth.clamp(shearVector.length(), 0.0D, 48.0D);

        long day = Math.floorDiv(level.getDayTime(), 24000L);
        double dailyRoll = (((day * 1103515245L + level.getSeed()) & 0xFFFFL) / 65535.0D - 0.5D) * 520.0D;
        double tempNorm = Mth.clamp((sample.temperature + season.temperatureModifier() - 0.30D) / 1.35D, 0.0D, 1.0D);
        double moisture = Mth.clamp(sample.moisture + season.moistureLift(), 0.0D, 1.0D);
        double cape = Mth.clamp(220.0D + 4200.0D * tempNorm * moisture + sample.waterInfluence * 420.0D + dailyRoll, 0.0D, 5500.0D);
        double shearNorm = Mth.clamp((shearMps - 5.0D) / 34.0D, 0.0D, 1.0D);
        double capeNorm = Mth.clamp(cape / 5000.0D, 0.0D, 1.0D);
        double stormMode = Mth.clamp(capeNorm * 0.54D + shearNorm * 0.46D, 0.0D, 1.0D);
        return new ThermodynamicProfile(cape, shearMps, shearVector, stormMode, season.temperatureModifier(), moisture, season.sereneSeasons(), season.label());
    }

    public static boolean hurricaneWindow(ServerLevel level, BlockPos origin) {
        SereneSeasonsCompat.SeasonSample season = SereneSeasonsCompat.sample(level, origin);
        if (season.sereneSeasons()) {
            return season.hurricaneWindow();
        }
        long dayTime = level.getDayTime() % 24000L;
        long day = Math.floorDiv(level.getDayTime(), 24000L) % 96L;
        return day >= 52L && day <= 74L && dayTime >= 0L;
    }

    private static Sample sampleBiomeThermodynamics(ServerLevel level, BlockPos origin) {
        double temp = 0.0D;
        double moisture = 0.0D;
        double water = 0.0D;
        int samples = 0;
        for (int dx = -96; dx <= 96; dx += 48) {
            for (int dz = -96; dz <= 96; dz += 48) {
                BlockPos pos = origin.offset(dx, 0, dz);
                if (!level.isLoaded(pos)) {
                    continue;
                }
                BlockPos surface = BlockGrabSafety.safeSurface(level, pos, Heightmap.Types.MOTION_BLOCKING);
                if (surface == null) {
                    surface = pos;
                }
                Biome biome = level.getBiome(surface).value();
                temp += biome.getBaseTemperature();
                moisture += biome.hasPrecipitation() ? 0.62D : 0.26D;
                if (level.isRainingAt(surface) || level.isThundering()) {
                    moisture += 0.20D;
                }
                BlockState below = level.getBlockState(surface.below());
                if (below.getFluidState().is(FluidTags.WATER) || DynamicStormController.isOverWaterOrOcean(level, surface.getX(), surface.getZ())) {
                    water += 1.0D;
                    moisture += 0.18D;
                }
                samples++;
            }
        }
        if (samples == 0) {
            return new Sample(0.5D, 0.35D, 0.0D);
        }
        return new Sample(temp / samples, Mth.clamp(moisture / samples, 0.0D, 1.0D), Mth.clamp(water / samples, 0.0D, 1.0D));
    }

    private static Vec3 shearVector(ServerLevel level, BlockPos origin) {
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        double cloudWind = 0.0D;
        double directional = 0.0D;
        Vec3 stormMotionSpread = Vec3.ZERO;
        int stormCount = 0;
        if (manager != null) {
            cloudWind = manager.getWindManager().getWindSpeedForClouds() * 18.0D;
            Vec3 meanMotion = Vec3.ZERO;
            for (WeatherObject object : manager.getStormObjects()) {
                if (object instanceof StormObject storm && storm.pos != null && !storm.isDead && storm.pos.distanceTo(Vec3.atCenterOf(origin)) < 2200.0D) {
                    Vec3 motion = storm.motion == null ? Vec3.ZERO : storm.motion;
                    meanMotion = meanMotion.add(new Vec3(motion.x, 0.0D, motion.z));
                    directional += Math.abs(Math.sin(Math.toRadians(storm.getAdjustedAngle())));
                    stormCount++;
                }
            }
            if (stormCount > 0) {
                meanMotion = meanMotion.scale(1.0D / stormCount);
                stormMotionSpread = meanMotion.scale(260.0D);
                directional = directional / stormCount * 8.0D;
            }
        }
        long day = Math.floorDiv(level.getDayTime(), 24000L);
        double angle = ((day * 37L + level.getSeed()) & 0xFFFFL) / 65535.0D * Math.PI * 2.0D;
        double baseline = 6.0D + Math.abs(Math.sin(angle * 0.7D)) * 10.0D;
        double magnitude = Mth.clamp(baseline + cloudWind + directional + stormMotionSpread.length(), 0.0D, 48.0D);
        Vec3 direction = new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
        if (stormMotionSpread.horizontalDistanceSqr() > 1.0E-5D) {
            direction = stormMotionSpread.normalize().scale(0.65D).add(direction.scale(0.35D)).normalize();
        }
        return direction.scale(magnitude);
    }

    private record Sample(double temperature, double moisture, double waterInfluence) {
    }
}
