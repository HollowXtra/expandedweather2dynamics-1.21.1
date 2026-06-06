package com.imbet.client;

import com.imbet.weather.DynamicStormState;
import com.imbet.weather.BlockGrabSafety;
import extendedrenderer.particle.ParticleRegistry;
import extendedrenderer.particle.entity.PivotingParticle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import weather2.weathersystem.storm.StormObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class SatelliteTornadoFunnelRenderer {
    private static final Map<Key, MiniFunnel> FUNNELS = new HashMap<>();

    private SatelliteTornadoFunnelRenderer() {
    }

    public static void tick(ClientLevel level, StormObject parent, DynamicStormState.TornadoCirculationState satellite, Vec3 groundCenter, double windMph, double rotationSign) {
        if (level == null || parent == null || satellite == null || groundCenter == null || satellite.absorbed()) {
            return;
        }
        Key key = new Key(parent.ID, satellite.id());
        MiniFunnel funnel = FUNNELS.computeIfAbsent(key, ignored -> new MiniFunnel(groundCenter));
        funnel.tick(level, satellite, groundCenter, Math.max(45.0D, windMph), rotationSign);
    }

    public static void tickSubVortex(ClientLevel level, StormObject parent, DynamicStormState.SubVortex vortex, Vec3 groundCenter, double parentWindMph, double rotationSign) {
        if (level == null || parent == null || vortex == null || groundCenter == null) {
            return;
        }
        Key key = new Key(parent.ID, -Math.max(1L, vortex.id()));
        MiniFunnel funnel = FUNNELS.computeIfAbsent(key, ignored -> new MiniFunnel(groundCenter));
        funnel.tickSubVortex(level, vortex, groundCenter, Math.max(100.0D, parentWindMph), rotationSign);
    }

    public static void tickHorizontalVortex(ClientLevel level, StormObject parent, DynamicStormState.HorizontalVortex vortex, Vec3 groundCenter, double parentRadius, double parentWindMph, double rotationSign) {
        if (level == null || parent == null || vortex == null || groundCenter == null) {
            return;
        }
        Key key = new Key(parent.ID, Long.MIN_VALUE + Math.max(1L, vortex.id()));
        MiniFunnel funnel = FUNNELS.computeIfAbsent(key, ignored -> new MiniFunnel(groundCenter));
        funnel.tickHorizontalVortex(level, vortex, groundCenter, Math.max(6.0D, parentRadius), Math.max(90.0D, parentWindMph), rotationSign);
    }

    public static void cleanup(long gameTime) {
        Iterator<Map.Entry<Key, MiniFunnel>> iterator = FUNNELS.entrySet().iterator();
        while (iterator.hasNext()) {
            MiniFunnel funnel = iterator.next().getValue();
            long missingTicks = gameTime - funnel.lastSeenTick;
            if (missingTicks > 2L && funnel.fadeOut(missingTicks)) {
                funnel.remove();
                iterator.remove();
            }
        }
    }

    public static void clear() {
        for (MiniFunnel funnel : FUNNELS.values()) {
            funnel.remove();
        }
        FUNNELS.clear();
    }

    private record Key(long parentStormId, long satelliteId) {
    }

    private static final class MiniFunnel {
        private static final float HEIGHT_PER_LAYER = 1.35F;

        private final List<VisualLayer> layers = new ArrayList<>();
        private Vec3 base;
        private Vec3 baseVelocity = Vec3.ZERO;
        private long firstSeenTick = Long.MIN_VALUE;
        private long lastSeenTick;

        private MiniFunnel(Vec3 base) {
            this.base = base;
        }

        private void tick(ClientLevel level, DynamicStormState.TornadoCirculationState satellite, Vec3 groundCenter, double windMph, double rotationSign) {
            lastSeenTick = level.getGameTime();
            if (firstSeenTick == Long.MIN_VALUE) {
                firstSeenTick = lastSeenTick;
            }
            smoothBase(level, groundCenter);

            double visibleRadius = Mth.clamp(Math.max(2.25D, satellite.visibleFunnelWidth() * 0.38D), 2.25D, 24.0D);
            double topRadiusMax = Mth.clamp(visibleRadius * 2.05D, 7.0D, 36.0D);
            double topRadius = Mth.clamp(visibleRadius * 1.08D + windMph / 42.0D, Math.max(5.0D, visibleRadius * 1.22D), topRadiusMax);
            double height = Mth.clamp(32.0D + windMph * 0.30D + visibleRadius * 0.58D, 40.0D, 96.0D);
            int layerCount = Mth.clamp((int) (height / HEIGHT_PER_LAYER), 22, 72);
            Vec3 cloudBase = new Vec3(base.x, base.y + height, base.z);
            ensureLayers(layerCount, cloudBase);
            trimLayers(layerCount);

            float adjustedRate = particleRate();
            float renderCull = Minecraft.getInstance().gameRenderer.getRenderDistance() * 4.0F;
            float spinBase = (float) (36.0D + windMph * 0.32D);
            int lowerDebrisLayers = Mth.clamp((int) (windMph / 7.0D), 6, layerCount);
            double formation = smoothstep(Mth.clamp((lastSeenTick - firstSeenTick + 18.0D) / 34.0D, 0.0D, 1.0D));

            for (int i = 0; i < layerCount; i++) {
                VisualLayer layer = layers.get(i);
                double fraction = i / (double) Math.max(1, layerCount - 1);
                double reveal = smoothstep(Mth.clamp((formation - (1.0D - fraction) * 0.54D) / 0.20D, 0.0D, 1.0D));
                if (reveal <= 0.02D) {
                    cleanup(layer.clouds, 0);
                    cleanup(layer.debris, 0);
                    continue;
                }
                double eased = fraction * fraction * (3.0D - 2.0D * fraction);
                double layerRadius = Mth.lerp(eased, Math.max(0.9D, visibleRadius * 0.22D), topRadius);
                double coneLean = Math.sin(lastSeenTick * 0.035D + satellite.id() * 0.011D + fraction * Math.PI * 2.0D) * visibleRadius * 0.10D * fraction;
                double leanAngle = Math.toRadians(satellite.orbitDegrees() + 90.0F);
                double formedY = Mth.lerp(reveal, cloudBase.y - (1.0D - fraction) * 5.0D, base.y + i * HEIGHT_PER_LAYER);
                Vec3 target = new Vec3(
                        base.x + Math.cos(leanAngle) * coneLean,
                        formedY,
                        base.z + Math.sin(leanAngle) * coneLean
                );
                layer.pos = layer.pos == null ? target : layer.pos.lerp(target, 0.34D + reveal * 0.20D);

                float radius = (float) layerRadius;
                float radiusMax = (float) topRadius;
                float adjustedRadius = Math.max(0.55F, radius * (radius / Math.max(1.0F, radiusMax)));
                float circumference = radius * 2.0F * Mth.PI;
                float particleSpace = Math.max(1.8F, (8.5F / adjustedRate) * Mth.clamp(radius / Math.max(1.0F, radiusMax), 0.24F, 1.0F));
                int targetClouds = Mth.clamp((int) Math.ceil(circumference / particleSpace), 2, 26);
                if (reveal < 0.32D) {
                    targetClouds = Math.max(1, targetClouds / 3);
                } else if (reveal < 0.62D) {
                    targetClouds = Math.max(1, targetClouds / 2);
                }
                if (i < 2) {
                    targetClouds = Math.max(1, targetClouds / 2);
                }
                cleanup(layer.clouds, targetClouds);
                while (layer.clouds.size() < targetClouds) {
                    PivotingParticle particle = createCloud(level, cloudBase, renderCull);
                    particle.spawnAsWeatherEffect();
                    layer.clouds.add(particle);
                }

                float spinSpeed = Math.max(0.72F, 1.0F - (i + 1.0F) / layerCount + 1.0F);
                layer.rotation += (spinBase * spinSpeed * (float) rotationSign) / Math.max(0.65F, adjustedRadius);
                positionClouds(layer, targetClouds, adjustedRadius, radius, radiusMax, fraction, reveal);

                int targetDebris = reveal > 0.78D && i <= lowerDebrisLayers && i >= 1 ? Mth.clamp((int) (4.0F * adjustedRate + windMph / 32.0D), 3, 12) : 0;
                cleanup(layer.debris, targetDebris);
                while (layer.debris.size() < targetDebris) {
                    PivotingParticle particle = createDebris(level, layer.pos, renderCull);
                    particle.spawnAsWeatherEffect();
                    layer.debris.add(particle);
                }
                positionDebris(layer, targetDebris, adjustedRadius);
            }
        }

        private void tickSubVortex(ClientLevel level, DynamicStormState.SubVortex vortex, Vec3 groundCenter, double windMph, double rotationSign) {
            lastSeenTick = level.getGameTime();
            if (firstSeenTick == Long.MIN_VALUE) {
                firstSeenTick = lastSeenTick;
            }
            smoothBase(level, groundCenter);

            double intensity = Mth.clamp(vortex.intensityFraction(), 0.08D, 1.0D);
            double visibleRadius = Mth.clamp(1.75D + vortex.radiusFraction() * 5.1D + intensity * 2.6D, 2.2D, 10.5D);
            double height = Mth.clamp(24.0D + windMph * 0.16D + intensity * 30.0D, 32.0D, 78.0D);
            int layerCount = Mth.clamp((int) (height / HEIGHT_PER_LAYER), 24, 58);
            Vec3 cloudBase = new Vec3(base.x, base.y + height, base.z);
            ensureLayers(layerCount, cloudBase);
            trimLayers(layerCount);

            float adjustedRate = particleRate();
            float renderCull = Minecraft.getInstance().gameRenderer.getRenderDistance() * 4.0F;
            float spinBase = (float) (52.0D + windMph * 0.44D);
            double formation = smoothstep(Mth.clamp((lastSeenTick - vortex.spawnTick() + 14.0D) / 34.0D, 0.0D, 1.0D));
            double lifeFade = smoothstep(Mth.clamp((vortex.expireTick() - lastSeenTick) / 48.0D, 0.0D, 1.0D));
            double visibility = formation * Math.max(0.0D, lifeFade);
            double orbitAngle = Math.toRadians(vortex.orbitDegrees());
            Vec3 outward = new Vec3(Math.cos(orbitAngle), 0.0D, Math.sin(orbitAngle));
            Vec3 tangent = new Vec3(-outward.z * rotationSign, 0.0D, outward.x * rotationSign);

            for (int i = 0; i < layerCount; i++) {
                VisualLayer layer = layers.get(i);
                double fraction = i / (double) Math.max(1, layerCount - 1);
                double reveal = smoothstep(Mth.clamp((visibility - (1.0D - fraction) * 0.36D) / 0.28D, 0.0D, 1.0D));
                if (reveal <= 0.02D) {
                    cleanup(layer.clouds, 0);
                    cleanup(layer.debris, 0);
                    continue;
                }
                double bendWave = Math.sin(lastSeenTick * 0.070D + vortex.id() * 0.37D + fraction * Math.PI * 2.65D);
                double counterWave = Math.cos(lastSeenTick * 0.047D + vortex.id() * 0.19D + fraction * Math.PI * 4.10D);
                double bendAmount = Math.sin(fraction * Math.PI) * (1.65D + intensity * 8.6D);
                Vec3 bend = outward.scale(bendWave * bendAmount * 0.78D + fraction * visibleRadius * 0.24D)
                        .add(tangent.scale(counterWave * bendAmount * 0.62D));
                Vec3 target = new Vec3(base.x + bend.x, base.y + fraction * height, base.z + bend.z);
                layer.pos = layer.pos == null ? target : layer.pos.lerp(target, 0.20D + reveal * 0.16D);

                double ropeWave = 0.10D * Math.sin(lastSeenTick * 0.055D + fraction * Math.PI * 5.4D + vortex.id());
                double layerRadius = Mth.clamp(visibleRadius * (0.66D + fraction * 0.20D + ropeWave), 1.05D, visibleRadius * 1.28D);
                float radius = (float) layerRadius;
                float radiusMax = (float) Math.max(visibleRadius * 1.34D, radius);
                float adjustedRadius = Math.max(0.65F, radius * 0.82F);
                float circumference = radius * 2.0F * Mth.PI;
                int targetClouds = Mth.clamp((int) Math.ceil(circumference / Math.max(0.92F, 2.25F / adjustedRate)), 4, 30);
                if (reveal < 0.45D) {
                    targetClouds = Math.max(2, targetClouds / 2);
                }
                cleanup(layer.clouds, targetClouds);
                while (layer.clouds.size() < targetClouds) {
                    PivotingParticle particle = createCloud(level, cloudBase, renderCull);
                    particle.spawnAsWeatherEffect();
                    layer.clouds.add(particle);
                }

                float spinSpeed = Math.max(0.92F, 1.85F - (float) fraction * 0.70F);
                layer.rotation += (spinBase * spinSpeed * (float) rotationSign) / Math.max(0.55F, adjustedRadius);
                positionClouds(layer, targetClouds, adjustedRadius, radius, radiusMax, fraction, reveal);

                int targetDebris = reveal > 0.58D && fraction < 0.38D ? Mth.clamp((int) (3.0F + intensity * 10.0F), 3, 14) : 0;
                cleanup(layer.debris, targetDebris);
                while (layer.debris.size() < targetDebris) {
                    PivotingParticle particle = createDebris(level, layer.pos, renderCull);
                    particle.spawnAsWeatherEffect();
                    layer.debris.add(particle);
                }
                positionDebris(layer, targetDebris, adjustedRadius);
            }
        }

        private void tickHorizontalVortex(ClientLevel level, DynamicStormState.HorizontalVortex vortex, Vec3 groundCenter, double parentRadius, double windMph, double rotationSign) {
            lastSeenTick = level.getGameTime();
            if (firstSeenTick == Long.MIN_VALUE) {
                firstSeenTick = lastSeenTick;
            }
            smoothBase(level, groundCenter);

            double intensity = Mth.clamp(vortex.intensityFraction(), 0.08D, 1.0D);
            double tubeLength = Mth.clamp(parentRadius * vortex.lengthFraction() * (0.40D + intensity * 0.24D), 5.0D, 32.0D);
            double tubeRadius = Mth.clamp(parentRadius * vortex.radiusFraction() * (0.42D + intensity * 0.18D), 0.45D, 3.2D);
            int layerCount = Mth.clamp((int) Math.ceil(tubeLength / 1.85D), 4, 16);
            ensureLayers(layerCount, base);
            trimLayers(layerCount);

            float adjustedRate = particleRate();
            float renderCull = Minecraft.getInstance().gameRenderer.getRenderDistance() * 4.0F;
            double formation = smoothstep(Mth.clamp((lastSeenTick - vortex.spawnTick() + 10.0D) / 28.0D, 0.0D, 1.0D));
            double lifeFade = smoothstep(Mth.clamp((vortex.expireTick() - lastSeenTick) / 42.0D, 0.0D, 1.0D));
            double visibility = formation * Math.max(0.0D, lifeFade);
            double orbitAngle = Math.toRadians(vortex.orbitDegrees());
            Vec3 outward = new Vec3(Math.cos(orbitAngle), 0.0D, Math.sin(orbitAngle));
            Vec3 tangent = new Vec3(-outward.z * rotationSign, 0.0D, outward.x * rotationSign);
            double pitch = Math.toRadians(vortex.pitchDegrees());
            Vec3 axis = normalize(tangent.scale(0.92D).add(outward.scale(0.22D)).add(0.0D, Math.sin(pitch) * 0.12D, 0.0D));
            Vec3 side = normalize(new Vec3(-axis.z, 0.0D, axis.x));
            Vec3 normal = normalize(new Vec3(
                    side.y * axis.z - side.z * axis.y,
                    side.z * axis.x - side.x * axis.z,
                    side.x * axis.y - side.y * axis.x
            ));
            float spinBase = (float) (74.0D + windMph * 0.34D);

            for (int i = 0; i < layerCount; i++) {
                VisualLayer layer = layers.get(i);
                double fraction = i / (double) Math.max(1, layerCount - 1);
                double reveal = smoothstep(Mth.clamp((visibility - Math.abs(fraction - 0.5D) * 0.42D) / 0.30D, 0.0D, 1.0D));
                if (reveal <= 0.02D) {
                    cleanup(layer.clouds, 0);
                    cleanup(layer.debris, 0);
                    continue;
                }
                double axial = (fraction - 0.5D) * tubeLength;
                double rollWave = Math.sin(lastSeenTick * 0.060D + vortex.id() * 0.43D + fraction * Math.PI * 2.7D);
                double liftWave = Math.cos(lastSeenTick * 0.047D + vortex.id() * 0.21D + fraction * Math.PI * 3.4D);
                double endTaper = Mth.clamp(Math.sin(fraction * Math.PI), 0.24D, 1.0D);
                Vec3 target = base
                        .add(axis.scale(axial))
                        .add(outward.scale(rollWave * tubeRadius * 0.24D))
                        .add(0.0D, liftWave * tubeRadius * 0.08D + endTaper * tubeRadius * 0.10D, 0.0D);
                layer.pos = layer.pos == null ? target : layer.pos.lerp(target, 0.30D + reveal * 0.18D);

                double radiusWave = 0.10D * Math.sin(lastSeenTick * 0.075D + fraction * Math.PI * 5.0D + vortex.id());
                float radius = (float) Mth.clamp(tubeRadius * (0.44D + intensity * 0.08D + radiusWave) * (0.42D + endTaper * 0.58D), 0.32D, tubeRadius * 0.92D);
                float circumference = radius * 2.0F * Mth.PI;
                int targetClouds = Mth.clamp((int) Math.ceil(circumference / Math.max(1.35F, 4.8F / adjustedRate)), 2, 7);
                cleanup(layer.clouds, targetClouds);
                while (layer.clouds.size() < targetClouds) {
                    PivotingParticle particle = createCloud(level, layer.pos == null ? base : layer.pos, renderCull);
                    particle.spawnAsWeatherEffect();
                    layer.clouds.add(particle);
                }

                layer.rotation += (spinBase * (float) rotationSign) / Math.max(0.75F, radius);
                positionHorizontalClouds(layer, targetClouds, side, normal, axis, radius, reveal);

                int targetDebris = reveal > 0.62D && fraction < 0.62D ? Mth.clamp((int) (1.0F + intensity * 3.0F), 1, 4) : 0;
                cleanup(layer.debris, targetDebris);
                while (layer.debris.size() < targetDebris) {
                    PivotingParticle particle = createDebris(level, layer.pos, renderCull);
                    particle.spawnAsWeatherEffect();
                    layer.debris.add(particle);
                }
                positionHorizontalDebris(layer, targetDebris, side, normal, radius * 1.08F);
            }
        }

        private boolean fadeOut(long missingTicks) {
            if (layers.isEmpty()) {
                return true;
            }
            float fade = 1.0F - Mth.clamp((missingTicks - 2L) / 44.0F, 0.0F, 1.0F);
            if (fade <= 0.0F) {
                return true;
            }
            for (VisualLayer layer : layers) {
                layer.rotation += 2.2F;
                fadeParticles(layer.clouds, fade);
                fadeParticles(layer.debris, fade * 0.78F);
            }
            return false;
        }

        private static void fadeParticles(List<PivotingParticle> particles, float fade) {
            for (PivotingParticle particle : particles) {
                if (particle == null || !particle.isAlive()) {
                    continue;
                }
                float fadedAlpha = Mth.clamp(0.88F * fade, 0.0F, 0.88F);
                particle.setAlpha(fadedAlpha);
                particle.setFullAlphaTarget(fadedAlpha);
                particle.setScale(Mth.clamp(particle.getScale() * 1.006F, 0.05F, 64.0F));
            }
        }

        private void smoothBase(ClientLevel level, Vec3 target) {
            if (base == null) {
                base = target;
                baseVelocity = Vec3.ZERO;
                clampBaseToGround(level);
                return;
            }
            double horizontalDistanceSqr = (base.x - target.x) * (base.x - target.x) + (base.z - target.z) * (base.z - target.z);
            if (horizontalDistanceSqr > 4096.0D) {
                base = new Vec3(target.x, Math.max(target.y, base.y - 1.5D), target.z);
                baseVelocity = Vec3.ZERO;
                clampBaseToGround(level);
                return;
            }
            Vec3 delta = target.subtract(base);
            if (delta.lengthSqr() < 0.0009D) {
                baseVelocity = baseVelocity.scale(0.45D);
                return;
            }
            double verticalDelta = Mth.clamp(delta.y, -2.5D, 4.0D);
            Vec3 clampedDelta = new Vec3(delta.x, verticalDelta, delta.z);
            baseVelocity = baseVelocity.scale(0.64D).add(clampedDelta.scale(0.18D));
            double speed = baseVelocity.horizontalDistance();
            double maxStep = Mth.clamp(delta.horizontalDistance() * 0.72D, 0.35D, 5.2D);
            if (speed > maxStep && speed > 1.0E-5D) {
                baseVelocity = new Vec3(baseVelocity.x / speed * maxStep, baseVelocity.y, baseVelocity.z / speed * maxStep);
            }
            baseVelocity = new Vec3(baseVelocity.x, Mth.clamp(baseVelocity.y, -0.45D, 0.75D), baseVelocity.z);
            base = base.add(baseVelocity);
            if (base.y < target.y - 0.18D) {
                base = new Vec3(base.x, target.y - 0.18D, base.z);
                baseVelocity = new Vec3(baseVelocity.x, Math.max(0.0D, baseVelocity.y), baseVelocity.z);
            }
            clampBaseToGround(level);
        }

        private void clampBaseToGround(ClientLevel level) {
            if (level == null || base == null) {
                return;
            }
            BlockPos ground = BlockGrabSafety.vortexGroundSurface(level, BlockPos.containing(base));
            if (ground == null) {
                return;
            }
            double minimumY = ground.getY() + 0.42D;
            if (base.y < minimumY) {
                base = new Vec3(base.x, minimumY, base.z);
                baseVelocity = new Vec3(baseVelocity.x, Math.max(0.0D, baseVelocity.y), baseVelocity.z);
            }
        }

        private static float particleRate() {
            ParticleStatus status = Minecraft.getInstance().options.particles().get();
            if (status == ParticleStatus.MINIMAL) {
                return 0.35F;
            }
            if (status == ParticleStatus.DECREASED) {
                return 0.65F;
            }
            return 1.0F;
        }

        private static double smoothstep(double value) {
            double clamped = Mth.clamp(value, 0.0D, 1.0D);
            return clamped * clamped * (3.0D - 2.0D * clamped);
        }

        private void ensureLayers(int layerCount, Vec3 basePos) {
            while (layers.size() < layerCount) {
                layers.add(new VisualLayer(basePos));
            }
        }

        private void trimLayers(int layerCount) {
            for (int i = layerCount; i < layers.size(); i++) {
                layers.get(i).remove();
            }
            while (layers.size() > layerCount) {
                layers.remove(layers.size() - 1);
            }
        }

        private static void cleanup(List<PivotingParticle> particles, int targetCount) {
            Iterator<PivotingParticle> iterator = particles.iterator();
            while (iterator.hasNext()) {
                PivotingParticle particle = iterator.next();
                if (!particle.isAlive() || targetCount <= 0) {
                    particle.remove();
                    iterator.remove();
                }
            }
            int maxLive = Math.max(0, targetCount + 3);
            while (particles.size() > maxLive) {
                PivotingParticle particle = particles.remove(particles.size() - 1);
                particle.remove();
            }
        }

        private static void positionClouds(VisualLayer layer, int count, float adjustedRadius, float radius, float radiusMax, double fraction, double reveal) {
            if (count <= 0) {
                return;
            }
            float spacing = 360.0F / count;
            int index = 0;
            for (PivotingParticle particle : layer.clouds) {
                float rot = spacing * index + layer.rotation;
                particle.setPivotRotPrev(particle.getPivotRot());
                particle.setPivotRot(new Vec3(0.0D, rot, 0.0D));
                particle.setPivotPrev(particle.getPivot());
                particle.setPivot(new Vec3(0.0D, adjustedRadius, 0.0D));

                Vec3 pivotedPosition = particle.getPivotedPosition(0);
                double vecX = -pivotedPosition.x;
                double vecZ = -pivotedPosition.z;
                particle.prevRotationYaw = particle.rotationYaw;
                particle.rotationYaw = -(float) (Mth.atan2(vecZ, vecX) * 180.0D / Math.PI) + 90.0F;
                particle.rotationYaw -= (particle.getEntityId() % 90) - 45;
                particle.rotationPitch = -30.0F;
                if (particle.rotationYaw > 0 && particle.prevRotationYaw < 0) {
                    particle.prevRotationYaw += 360.0F;
                }

                particle.setPosition(layer.pos.x, layer.pos.y, layer.pos.z);
                particle.setPrevPosX(particle.getPosX());
                particle.setPrevPosY(particle.getPosY());
                particle.setPrevPosZ(particle.getPosZ());
                float formedScale = (float) (0.45D + reveal * 0.55D);
                particle.setScale((float) Mth.clamp((6.25F * (radius / Math.max(1.0F, radiusMax)) + fraction * 3.35F) * formedScale, 1.55F, 13.5F));
                float alpha = Mth.clamp(0.46F + 0.48F * (float) reveal, 0.20F, 0.94F);
                particle.setAlpha(alpha);
                particle.setFullAlphaTarget(alpha);
                if (particle.getAge() > particle.getTicksFadeInMax() + 1) {
                    particle.setAge((int) particle.getTicksFadeInMax() + 1);
                }
                index++;
            }
        }

        private static void positionDebris(VisualLayer layer, int count, float adjustedRadius) {
            if (count <= 0) {
                return;
            }
            float spacing = 360.0F / count;
            int index = 0;
            for (PivotingParticle particle : layer.debris) {
                float radAdj = (particle.getEntityId() % Math.max(1, count)) / (float) Math.max(1, count);
                radAdj = 0.45F + radAdj * 0.75F;
                float rot = spacing * index + layer.rotation;
                particle.setPivotRotPrev(particle.getPivotRot());
                particle.setPivotRot(new Vec3(0.0D, rot, 0.0D));
                particle.setPivotPrev(particle.getPivot());
                particle.setPivot(new Vec3(0.0D, adjustedRadius * radAdj + 0.9F, 0.0D));
                particle.prevRotationYaw = particle.rotationYaw;
                particle.rotationYaw += 6.0F;
                particle.rotationPitch = -30.0F;
                particle.setPosition(layer.pos.x, layer.pos.y, layer.pos.z);
                particle.setPrevPosX(particle.getPosX());
                particle.setPrevPosY(particle.getPosY());
                particle.setPrevPosZ(particle.getPosZ());
                particle.setScale(1.15F);
                if (particle.getAge() > particle.getTicksFadeInMax() + 1) {
                    particle.setAge((int) particle.getTicksFadeInMax() + 1);
                }
                particle.setGravity(0.0F);
                index++;
            }
        }

        private static void positionHorizontalClouds(VisualLayer layer, int count, Vec3 side, Vec3 normal, Vec3 axis, float radius, double reveal) {
            if (count <= 0 || layer.pos == null) {
                return;
            }
            float spacing = 360.0F / count;
            int index = 0;
            for (PivotingParticle particle : layer.clouds) {
                double radians = Math.toRadians(spacing * index + layer.rotation);
                Vec3 radial = side.scale(Math.cos(radians)).add(normal.scale(Math.sin(radians)));
                Vec3 pos = layer.pos.add(radial.scale(radius));
                particle.setPrevPosX(particle.getPosX());
                particle.setPrevPosY(particle.getPosY());
                particle.setPrevPosZ(particle.getPosZ());
                particle.setPosition(pos.x, pos.y, pos.z);
                particle.prevRotationYaw = particle.rotationYaw;
                particle.rotationYaw = (float) (Mth.atan2(axis.z, axis.x) * 180.0D / Math.PI) - 90.0F + (index % 3 - 1) * 18.0F;
                particle.rotationPitch = (float) (Math.sin(radians) * 55.0D);
                particle.setScale((float) Mth.clamp(radius * (1.08D + reveal * 0.30D), 0.85D, 5.2D));
                float alpha = Mth.clamp(0.50F + 0.38F * (float) reveal, 0.20F, 0.88F);
                particle.setAlpha(alpha);
                particle.setFullAlphaTarget(alpha);
                if (particle.getAge() > particle.getTicksFadeInMax() + 1) {
                    particle.setAge((int) particle.getTicksFadeInMax() + 1);
                }
                index++;
            }
        }

        private static void positionHorizontalDebris(VisualLayer layer, int count, Vec3 side, Vec3 normal, float radius) {
            if (count <= 0 || layer.pos == null) {
                return;
            }
            float spacing = 360.0F / count;
            int index = 0;
            for (PivotingParticle particle : layer.debris) {
                double radians = Math.toRadians(spacing * index + layer.rotation * 1.22F);
                Vec3 radial = side.scale(Math.cos(radians)).add(normal.scale(Math.sin(radians)));
                Vec3 pos = layer.pos.add(radial.scale(radius));
                particle.setPrevPosX(particle.getPosX());
                particle.setPrevPosY(particle.getPosY());
                particle.setPrevPosZ(particle.getPosZ());
                particle.setPosition(pos.x, pos.y, pos.z);
                particle.rotationYaw += 8.0F;
                particle.rotationPitch += 11.0F;
                particle.setScale(1.0F);
                particle.setGravity(0.0F);
                if (particle.getAge() > particle.getTicksFadeInMax() + 1) {
                    particle.setAge((int) particle.getTicksFadeInMax() + 1);
                }
                index++;
            }
        }

        private static PivotingParticle createCloud(ClientLevel level, Vec3 pos, float renderCull) {
            PivotingParticle particle = new PivotingParticle(level, pos.x, pos.y, pos.z, 0.0D, 0.0D, 0.0D, ParticleRegistry.cloud256);
            particle.setMaxAge(25000);
            particle.setTicksFadeInMax(18);
            particle.setParticleSpeed(0.0D, 0.0D, 0.0D);
            float baseBright = 0.34F;
            float finalBright = Math.min(1.0F, baseBright + level.random.nextFloat() * 0.45F);
            particle.setColor(finalBright - 0.14F, finalBright - 0.14F, finalBright - 0.10F);
            particle.setAlpha(0.78F);
            particle.setFullAlphaTarget(0.78F);
            particle.setGravity(0.0F);
            particle.isTransparent = true;
            particle.renderOrder = 1;
            particle.rotationYaw = level.random.nextFloat() * 360.0F;
            particle.setRenderDistanceCull(renderCull);
            return particle;
        }

        private static Vec3 normalize(Vec3 vector) {
            double length = vector.length();
            if (length < 1.0E-5D) {
                return new Vec3(1.0D, 0.0D, 0.0D);
            }
            return vector.scale(1.0D / length);
        }

        private static PivotingParticle createDebris(ClientLevel level, Vec3 pos, float renderCull) {
            TextureAtlasSprite sprite = switch (level.random.nextInt(3)) {
                case 1 -> ParticleRegistry.debris_2;
                case 2 -> ParticleRegistry.debris_3;
                default -> ParticleRegistry.debris_1;
            };
            PivotingParticle particle = new PivotingParticle(level, pos.x, pos.y, pos.z, 0.0D, 0.0D, 0.0D, sprite);
            particle.setMaxAge(25000);
            particle.setTicksFadeInMax(14);
            particle.setParticleSpeed(0.0D, 0.0D, 0.0D);
            particle.setFacePlayer(false);
            particle.spinFast = true;
            particle.isTransparent = true;
            particle.rotationYaw = level.random.nextInt(360);
            particle.rotationPitch = level.random.nextInt(360);
            particle.setGravity(0.0F);
            float brightnessMulti = 0.55F + level.random.nextFloat() * 0.36F;
            particle.setColor(brightnessMulti, brightnessMulti * 0.88F, brightnessMulti * 0.72F);
            particle.aboveGroundHeight = 0.5D;
            particle.collisionSpeedDampen = false;
            particle.bounceSpeed = 0.03D;
            particle.bounceSpeedAhead = 0.03D;
            particle.setKillOnCollide(false);
            particle.windWeight = 5.0F;
            particle.setRenderDistanceCull(renderCull);
            return particle;
        }

        private void remove() {
            for (VisualLayer layer : layers) {
                layer.remove();
            }
            layers.clear();
        }
    }

    private static final class VisualLayer {
        private Vec3 pos;
        private float rotation;
        private final List<PivotingParticle> clouds = new ArrayList<>();
        private final List<PivotingParticle> debris = new ArrayList<>();

        private VisualLayer(Vec3 pos) {
            this.pos = pos;
        }

        private void remove() {
            for (PivotingParticle particle : clouds) {
                particle.remove();
            }
            for (PivotingParticle particle : debris) {
                particle.remove();
            }
            clouds.clear();
            debris.clear();
        }
    }
}
