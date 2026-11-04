package com.imbet.weather;

import com.imbet.Config;
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
        // The world's own calendar, on top of whichever season the world is showing. Its scales always apply -
        // spring's shear, summer's pulse air and winter's frontal flow are the same physics whoever counted the
        // day - but its temperature and moisture offsets stand down while another mod's season is in charge,
        // because that mod has already moved the temperature with more granularity than a season can (an early
        // spring is not a late one) and two seasons' worth of winter would be a world that never thaws.
        MeteorologicalCalendar.Day calendarDay = SeasonCalendarService.day(level);
        MeteorologicalCalendar.Effect calendarEffect = SeasonCalendarService.effect(calendarDay);
        double temperatureShift = season.sereneSeasons() ? season.temperatureModifier() : calendarEffect.temperature();
        double moistureShift = season.sereneSeasons() ? season.moistureLift() : calendarEffect.moisture();
        Vec3 shearVector = shearVector(level, origin).scale(calendarEffect.shear());
        double shearMps = Mth.clamp(shearVector.length(), 0.0D, 48.0D);

        long day = Math.floorDiv(level.getDayTime(), 24000L);
        double dailyRoll = (((day * 1103515245L + level.getSeed()) & 0xFFFFL) / 65535.0D - 0.5D) * 520.0D;
        // Where the air comes from. With the climate on, it is the ground's own reading: the biome families
        // around this place set its heat, its moisture, how readily that air converts, and how sharp the
        // moisture boundary across it is. With it off, it is the biome average this mod has always taken, so
        // an old world keeps exactly the sky it was tuned against - the old sampler is still here and still
        // the one used.
        BiomeClimate.Reading air = Config.CLIMATE_ENABLED.get() ? BiomeClimateService.reading(level, origin) : null;
        double heat;
        double moisture;
        double instability;
        double contrast;
        double waterInfluence;
        if (air != null) {
            // The season is a temperature offset, so it lands on the heat the same way it always did: the
            // reading is the raw temperature put through the same normalisation, and a season moves it.
            heat = Mth.clamp(air.heat() + temperatureShift / BiomeClimate.HEAT_SPAN, 0.0D, 1.0D);
            moisture = Mth.clamp(air.moisture() + moistureShift, 0.0D, 1.0D);
            instability = air.instability();
            contrast = air.contrast();
            waterInfluence = air.waterShare();
        } else {
            Sample sample = sampleBiomeThermodynamics(level, origin);
            heat = Mth.clamp((sample.temperature + temperatureShift - BiomeClimate.COLD_FLOOR)
                    / BiomeClimate.HEAT_SPAN, 0.0D, 1.0D);
            moisture = Mth.clamp(sample.moisture + moistureShift, 0.0D, 1.0D);
            instability = 1.0D;
            contrast = 0.0D;
            waterInfluence = sample.waterInfluence;
        }
        double cape = Mth.clamp(BiomeClimate.capeJkg(heat, moisture, instability, waterInfluence, dailyRoll)
                * calendarEffect.instability(), 0.0D, BiomeClimate.CAPE_CEILING_JKG);
        double stormMode = BiomeClimate.stormModeIndex(cape, shearMps, contrast, Config.CLIMATE_CONTRAST_STRENGTH.get());
        String seasonLabel = season.sereneSeasons()
                ? season.label()
                : calendarDay == null ? "Vanilla" : calendarDay.season().label() + ", day " + calendarDay.dayOfYearOneBased();
        return new ThermodynamicProfile(cape, shearMps, shearVector, stormMode, temperatureShift, moisture,
                season.sereneSeasons(), seasonLabel);
    }

    /**
     * Whether the year is in its hurricane window. Another mod's season wins when it has one, because
     * it knows its own sub-seasons; otherwise the mod's own calendar says late summer into early
     * autumn, and a world with the calendar switched off keeps the 96-day cycle this used to run on.
     */
    public static boolean hurricaneWindow(ServerLevel level, BlockPos origin) {
        SereneSeasonsCompat.SeasonSample season = SereneSeasonsCompat.sample(level, origin);
        if (season.sereneSeasons()) {
            return season.hurricaneWindow();
        }
        if (SeasonCalendarService.enabled()) {
            MeteorologicalCalendar.Day day = SeasonCalendarService.day(level);
            return day != null && day.hurricaneWindow();
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
