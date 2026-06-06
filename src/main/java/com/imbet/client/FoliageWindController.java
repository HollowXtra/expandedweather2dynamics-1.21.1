package com.imbet.client;

import com.imbet.Config;
import com.imbet.weather.AddonWeatherEvent;
import com.imbet.weather.ClientAddonWeatherEventStore;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.StormSubtype;
import com.imbet.weather.SupercellStructureService;
import com.imbet.weather.TornadoWindField;
import com.mojang.blaze3d.shaders.Uniform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import weather2.ClientTickHandler;
import weather2.weathersystem.WeatherManagerClient;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.ArrayList;

public final class FoliageWindController {
    private static Vec3 smoothedWind = Vec3.ZERO;
    private static float smoothedStrength;
    private static float smoothedGust;

    private FoliageWindController() {
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || !enabled()) {
            smoothedWind = smoothedWind.scale(0.82D);
            smoothedStrength = Mth.lerp(0.18F, smoothedStrength, 0.0F);
            smoothedGust = Mth.lerp(0.18F, smoothedGust, 0.0F);
            return;
        }

        WindCandidate target = sampleWind(minecraft.level, minecraft.player);
        float smoothing = Mth.clamp(Config.FOLIAGE_WIND_SMOOTHING.get().floatValue(), 0.001F, 1.0F);
        smoothedWind = smoothedWind.lerp(target.wind(), smoothing);
        smoothedStrength = Mth.lerp(smoothing, smoothedStrength, target.strength());
        smoothedGust = Mth.lerp(smoothing * 0.65F, smoothedGust, target.gust());
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !enabled()) {
            applyUniforms(0.0F, Vec3.ZERO, 0.0F, 0.0F);
            return;
        }
        float time = (minecraft.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false)) / 20.0F;
        applyUniforms(time, smoothedWind, smoothedStrength, smoothedGust);
    }

    public static boolean enabled() {
        return Config.VISUALS_ENABLED.get() && Config.FOLIAGE_WIND_SHADER.get();
    }

    private static WindCandidate sampleWind(ClientLevel level, Player player) {
        Vec3 playerPos = player.position();
        int range = Config.FOLIAGE_WIND_RENDER_RANGE_BLOCKS.get();
        double rangeSqr = (double) range * range;
        WindCandidate best = WindCandidate.CALM;

        WeatherManagerClient manager = ClientTickHandler.weatherManager;
        if (manager != null) {
            for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
                if (!(object instanceof StormObject storm) || storm.pos == null || storm.isDead || horizontalDistanceSqr(playerPos, storm.pos) > rangeSqr) {
                    continue;
                }
                if (DynamicStormController.isDynamicVortex(storm) && !DynamicStormController.isAbsorbedOrDecaying(storm)) {
                    best = best.stronger(fromWindSample(TornadoWindField.sample(storm, level, playerPos)));
                }
                best = best.stronger(fromWindSample(SupercellStructureService.sampleWind(storm, level, playerPos)));
                best = best.stronger(broadStormWind(storm, playerPos, range));
            }
        }

        for (AddonWeatherEvent event : ClientAddonWeatherEventStore.events()) {
            if (event == null || horizontalDistanceSqr(playerPos, event.position()) > rangeSqr) {
                continue;
            }
            best = best.stronger(fromWindSample(sampleAddonWind(event, playerPos)));
        }
        best = best.withPhysicsSample(PhysicsModFoliageBridge.sample(level, playerPos, Config.FOLIAGE_WIND_THRASH_MPH.get().floatValue()));
        return best;
    }

    private static WindCandidate broadStormWind(StormObject storm, Vec3 playerPos, int range) {
        if (storm.levelCurIntensityStage < StormObject.STATE_THUNDER && !storm.attrib_precipitation) {
            return WindCandidate.CALM;
        }
        Vec3 motion = storm.motion == null ? Vec3.ZERO : new Vec3(storm.motion.x, 0.0D, storm.motion.z);
        if (motion.horizontalDistanceSqr() < 1.0E-5D) {
            return WindCandidate.CALM;
        }
        double distance = Math.sqrt(horizontalDistanceSqr(playerPos, storm.pos));
        double falloff = Mth.clamp(1.0D - distance / Math.max(1.0D, range), 0.0D, 1.0D);
        double stage = Mth.clamp(storm.levelCurIntensityStage / (double) StormObject.STATE_STAGE5, 0.0D, 1.0D);
        Vec3 vector = motion.normalize().scale((10.0D + stage * 46.0D) * falloff);
        return fromWindVector(vector);
    }

    private static TornadoWindField.WindSample sampleAddonWind(AddonWeatherEvent event, Vec3 position) {
        if (event.isLine()) {
            return sampleLineWind(event, position);
        }
        Vec3 center = new Vec3(event.position().x, position.y, event.position().z);
        Vec3 fromCenter = position.subtract(center);
        double radius = Math.max(0.4D, Math.sqrt(fromCenter.x * fromCenter.x + fromCenter.z * fromCenter.z));
        double rmw = Math.max(1.2D, event.radius());
        if (radius > rmw * 4.0D) {
            return TornadoWindField.WindSample.CALM;
        }
        double max = event.maxWindMph();
        double tangential = radius <= rmw ? max * (radius / rmw) : max * Math.pow(rmw / radius, 0.86D);
        Vec3 radial = fromCenter.scale(1.0D / radius);
        Vec3 tangent = new Vec3(-radial.z, 0.0D, radial.x);
        double inflow = event.type() == StormSubtype.DUST_DEVIL ? 0.10D : event.type() == StormSubtype.GUSTNADO ? 0.055D : event.type() == StormSubtype.WATERSPOUT ? 0.24D : 0.18D;
        Vec3 vector = tangent.scale(tangential).add(radial.scale(-tangential * inflow));
        return new TornadoWindField.WindSample(vector, vector.length(), vector.length(), radius, rmw * 2.0D, rmw);
    }

    private static TornadoWindField.WindSample sampleLineWind(AddonWeatherEvent event, Vec3 position) {
        Vec3 start = event.lineStart();
        Vec3 end = event.lineEnd();
        if (start == null || end == null) {
            return TornadoWindField.WindSample.CALM;
        }
        Vec3 closest = closestPointOnSegment(position, start, end);
        double distance = Math.sqrt(horizontalDistanceSqr(position, closest));
        if (distance > event.radius()) {
            return TornadoWindField.WindSample.CALM;
        }
        Vec3 line = end.subtract(start);
        Vec3 along = line.horizontalDistanceSqr() < 1.0E-5D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(line.x, 0.0D, line.z).normalize();
        Vec3 motion = event.motion();
        Vec3 forward = motion != null && motion.horizontalDistanceSqr() > 1.0E-5D ? new Vec3(motion.x, 0.0D, motion.z).normalize() : new Vec3(-along.z, 0.0D, along.x);
        double falloff = Mth.clamp(1.0D - distance / Math.max(1.0D, event.radius()), 0.0D, 1.0D);
        Vec3 vector = forward.scale(event.maxWindMph() * falloff);
        return new TornadoWindField.WindSample(vector, vector.length(), vector.length(), distance, event.radius(), event.radius() * 0.5D);
    }

    private static Vec3 closestPointOnSegment(Vec3 point, Vec3 start, Vec3 end) {
        Vec3 ab = end.subtract(start);
        double lengthSqr = ab.x * ab.x + ab.z * ab.z;
        if (lengthSqr <= 1.0E-5D) {
            return start;
        }
        double t = ((point.x - start.x) * ab.x + (point.z - start.z) * ab.z) / lengthSqr;
        t = Mth.clamp(t, 0.0D, 1.0D);
        return new Vec3(start.x + ab.x * t, point.y, start.z + ab.z * t);
    }

    private static WindCandidate fromWindSample(TornadoWindField.WindSample sample) {
        if (sample == null || sample.speedMph() <= 0.01D) {
            return WindCandidate.CALM;
        }
        return fromWindVector(new Vec3(sample.mphVector().x, 0.0D, sample.mphVector().z));
    }

    private static WindCandidate fromWindVector(Vec3 vector) {
        double speed = Math.sqrt(vector.x * vector.x + vector.z * vector.z);
        if (speed <= 0.01D) {
            return WindCandidate.CALM;
        }
        float thrash = Math.max(1.0F, Config.FOLIAGE_WIND_THRASH_MPH.get().floatValue());
        float strength = Mth.clamp((float) (speed / thrash), 0.0F, 1.0F);
        float gust = Mth.clamp((float) ((speed - 18.0D) / Math.max(1.0F, thrash - 18.0F)), 0.0F, 1.0F);
        return new WindCandidate(vector, strength, gust);
    }

    private static double horizontalDistanceSqr(Vec3 first, Vec3 second) {
        double dx = first.x - second.x;
        double dz = first.z - second.z;
        return dx * dx + dz * dz;
    }

    private static void applyUniforms(float time, Vec3 wind, float strength, float gust) {
        ShaderInstance cutout = GameRenderer.getRendertypeCutoutShader();
        ShaderInstance cutoutMipped = GameRenderer.getRendertypeCutoutMippedShader();
        applyUniforms(cutout, time, wind, strength, gust);
        applyUniforms(cutoutMipped, time, wind, strength, gust);
    }

    private static void applyUniforms(ShaderInstance shader, float time, Vec3 wind, float strength, float gust) {
        if (shader == null) {
            return;
        }
        Vec3 horizontal = new Vec3(wind.x, 0.0D, wind.z);
        if (horizontal.horizontalDistanceSqr() > 1.0E-5D) {
            horizontal = horizontal.normalize();
        } else {
            horizontal = new Vec3(1.0D, 0.0D, 0.0D);
        }
        setUniform(shader, "FoliageWindVector", (float) horizontal.x, (float) horizontal.z);
        setUniform(shader, "FoliageWindStrength", Mth.clamp(strength, 0.0F, 1.0F));
        setUniform(shader, "FoliageWindTime", time);
        setUniform(shader, "FoliageWindMaxOffset", Config.FOLIAGE_WIND_MAX_OFFSET_BLOCKS.get().floatValue());
        setUniform(shader, "FoliageWindGust", Mth.clamp(gust, 0.0F, 1.0F));
    }

    private static void setUniform(ShaderInstance shader, String name, float value) {
        Uniform uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.set(value);
        }
    }

    private static void setUniform(ShaderInstance shader, String name, float first, float second) {
        Uniform uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.set(first, second);
        }
    }

    private record WindCandidate(Vec3 wind, float strength, float gust) {
        private static final WindCandidate CALM = new WindCandidate(Vec3.ZERO, 0.0F, 0.0F);

        private WindCandidate stronger(WindCandidate other) {
            return other.strength > strength ? other : this;
        }

        private WindCandidate withPhysicsSample(PhysicsModFoliageBridge.PhysicsWindSample sample) {
            if (sample == null || sample.strength() <= 0.001F || sample.wind().horizontalDistanceSqr() <= 1.0E-6D) {
                return this;
            }
            if (strength <= 0.001F || wind.horizontalDistanceSqr() <= 1.0E-6D) {
                return new WindCandidate(sample.wind(), sample.strength(), sample.gust());
            }
            Vec3 weatherDir = wind.normalize();
            Vec3 physicsDir = sample.wind().normalize();
            Vec3 mixedDir = weatherDir.scale(0.58D).add(physicsDir.scale(0.42D));
            if (mixedDir.horizontalDistanceSqr() <= 1.0E-6D) {
                mixedDir = weatherDir;
            } else {
                mixedDir = mixedDir.normalize();
            }
            double speed = Math.max(wind.length(), sample.wind().length() * 0.65D);
            return new WindCandidate(mixedDir.scale(speed), Math.max(strength, sample.strength() * 0.85F), Math.max(gust, sample.gust()));
        }
    }
}
