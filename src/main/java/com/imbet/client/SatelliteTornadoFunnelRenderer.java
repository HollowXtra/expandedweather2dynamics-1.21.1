package com.imbet.client;

import com.imbet.Config;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.DynamicStormState;
import com.imbet.weather.BlockGrabSafety;
import com.imbet.weather.SubVortexType;
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
    /**
     * Key band for the pre-touchdown genesis whirls. Satellites and sub-vortices take {@code -id} and
     * horizontal vortices take {@code Long.MIN_VALUE + id}, so a storm would need five million
     * sub-vortices before one of them could land in this band.
     */
    private static final long GENESIS_WHIRL_KEY_BASE = 5_000_000L;

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
        funnel.tickSubVortex(level, parent, vortex, groundCenter, Math.max(100.0D, parentWindMph), rotationSign);
    }

    public static void tickHorizontalVortex(ClientLevel level, StormObject parent, DynamicStormState.HorizontalVortex vortex, Vec3 groundCenter, double parentRadius, double parentWindMph, double rotationSign) {
        if (level == null || parent == null || vortex == null || groundCenter == null) {
            return;
        }
        Key key = new Key(parent.ID, Long.MIN_VALUE + Math.max(1L, vortex.id()));
        MiniFunnel funnel = FUNNELS.computeIfAbsent(key, ignored -> new MiniFunnel(groundCenter));
        funnel.tickHorizontalVortex(level, parent, vortex, groundCenter, Math.max(6.0D, parentRadius), Math.max(90.0D, parentWindMph), rotationSign);
    }

    /**
     * One of the small vortices that turn on the ground while a tornado is still forming - before the
     * condensation has reached it. It is drawn as a short dust column: debris in the lower part where
     * the swirl is picking things up, and haze above it, never as condensation, because there is none.
     */
    public static void tickGenesisWhirl(ClientLevel level, StormObject parent, int index, Vec3 groundCenter,
                                        double windMph, double rotationSign, double visibleRadius, double height,
                                        double visibility) {
        if (level == null || parent == null || groundCenter == null || visibility <= 0.02D) {
            return;
        }
        Key key = new Key(parent.ID, -(GENESIS_WHIRL_KEY_BASE + Math.max(0, index)));
        MiniFunnel funnel = FUNNELS.computeIfAbsent(key, ignored -> new MiniFunnel(groundCenter));
        funnel.tickGenesisWhirl(level, parent.ID * 31L + index, groundCenter, Math.max(30.0D, windMph),
                rotationSign, Math.max(0.6D, visibleRadius), Math.max(3.0D, height), visibility);
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

        // Free-flight state for a horizontal vortex that has torn off the funnel wall: captured once
        // at the moment it breaks free, then carried each tick so the loose tube drifts away from
        // the tornado instead of staying stuck to it.
        private Vec3 freeOrigin;
        private Vec3 freeVelocity = Vec3.ZERO;
        private boolean freeInitialised;

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
                // Height-growing tilt: the satellite funnel leans along its orbit
                // tangent (its actual motion direction), stronger as it matures,
                // with a slight outward lean away from the parent and one slow sway.
                double lifecycle = Mth.clamp(satellite.lifecycleAt(lastSeenTick), 0.0D, 1.0D);
                double orbitRadians = Math.toRadians(satellite.orbitDegrees());
                Vec3 outward = new Vec3(Math.cos(orbitRadians), 0.0D, Math.sin(orbitRadians));
                Vec3 tangent = new Vec3(-outward.z * rotationSign, 0.0D, outward.x * rotationSign);
                double leanFrac = fraction * fraction;
                double leanStrength = 0.10D + 0.16D * lifecycle;
                double sway = Math.sin(lastSeenTick * 0.030D + satellite.id() * 0.011D + fraction * Math.PI * 1.7D) * visibleRadius * 0.05D * fraction;
                Vec3 lean = tangent.scale(leanStrength * leanFrac * visibleRadius + sway)
                        .add(outward.scale(0.05D * leanFrac * visibleRadius));
                double formedY = Mth.lerp(reveal, cloudBase.y - (1.0D - fraction) * 5.0D, base.y + i * HEIGHT_PER_LAYER);
                Vec3 target = new Vec3(base.x + lean.x, formedY, base.z + lean.z);
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

        private void tickSubVortex(ClientLevel level, StormObject storm, DynamicStormState.SubVortex vortex, Vec3 groundCenter, double windMph, double rotationSign) {
            lastSeenTick = level.getGameTime();
            if (firstSeenTick == Long.MIN_VALUE) {
                firstSeenTick = lastSeenTick;
            }
            smoothBase(level, groundCenter);

            double intensity = Mth.clamp(vortex.intensityFraction(), 0.08D, 1.0D);
            SubVortexType type = vortex.type();
            boolean collar = type.descends();
            // Width only: the funnel's height is set by the vortex's own size, and a knob that changed
            // both would make a scaled funnel a different vortex instead of a wider or narrower one.
            float funnelSize = Mth.clamp(Config.MULTIVORTEX_SUBVORTEX_FUNNEL_SIZE.get().floatValue(), 0.35F, 1.6F);
            // A type with a height floor hangs from the cloud base instead of touching the ground,
            // and a dust whirl has no condensation at all - it is visible only through its debris.
            boolean funnelVisible = type.visibleAsFunnel();
            double heightFloor = collar ? 0.0D : type.minHeightFraction();
            double visibleRadius;
            double height;
            if (collar) {
                // A vortex breakdown bubble is a collar around the funnel rather than a funnel of its
                // own: it wraps the wall at the height it has descended to, so it reads as a neck of
                // cloud travelling down the condensation funnel.
                float fraction = vortex.heightFraction(lastSeenTick);
                double wall = Math.max(3.0D, DynamicStormController.funnelWallRadiusAt(storm, fraction));
                visibleRadius = Mth.clamp(wall * 0.52D + vortex.radiusFraction() * 4.4D, 3.5D, 24.0D);
                height = Mth.clamp(8.0D + vortex.radiusFraction() * 16.0D, 8.0D, 24.0D);
            } else if (type == SubVortexType.CORE) {
                // Inner-core spots are thin and short but spin fastest.
                visibleRadius = Mth.clamp(1.2D + vortex.radiusFraction() * 4.4D + intensity * 2.0D, 1.6D, 6.4D);
                height = Mth.clamp(18.0D + windMph * 0.11D + intensity * 24.0D, 22.0D, 56.0D);
            } else if (type == SubVortexType.PERIPHERAL) {
                // Peripheral vortices are broader and taller but much weaker.
                visibleRadius = Mth.clamp(2.4D + vortex.radiusFraction() * 7.2D + intensity * 3.2D, 3.0D, 15.0D);
                height = Mth.clamp(28.0D + windMph * 0.18D + intensity * 34.0D, 36.0D, 92.0D);
            } else if (type == SubVortexType.SATELLITE_FUNNEL) {
                // The hanging secondary funnel: broad near the cloud base and long enough that its
                // tip visibly dangles, since its lowest reachable point is only a third of the way down.
                visibleRadius = Mth.clamp(3.0D + vortex.radiusFraction() * 8.6D + intensity * 4.0D, 4.0D, 20.0D);
                height = Mth.clamp(48.0D + windMph * 0.20D + intensity * 40.0D, 56.0D, 104.0D);
            } else if (type == SubVortexType.DUST_WHIRL) {
                // A dust whirl never condenses, so it is a short ground swirl rather than a funnel.
                visibleRadius = Mth.clamp(1.3D + vortex.radiusFraction() * 4.6D + intensity * 1.8D, 1.6D, 8.0D);
                height = Mth.clamp(9.0D + windMph * 0.05D + intensity * 15.0D, 10.0D, 28.0D);
            } else {
                visibleRadius = Mth.clamp(1.75D + vortex.radiusFraction() * 5.1D + intensity * 2.6D, 2.2D, 10.5D);
                height = Mth.clamp(24.0D + windMph * 0.16D + intensity * 30.0D, 32.0D, 78.0D);
            }
            int layerCount = Mth.clamp((int) (height / HEIGHT_PER_LAYER), collar ? 6 : 24, collar ? 14 : 58);
            Vec3 cloudBase = new Vec3(base.x, base.y + height, base.z);
            ensureLayers(layerCount, cloudBase);
            trimLayers(layerCount);

            float adjustedRate = particleRate();
            float renderCull = Minecraft.getInstance().gameRenderer.getRenderDistance() * 4.0F;
            float spinBase = (float) ((52.0D + windMph * 0.44D) * (type == SubVortexType.CORE ? 1.30D : collar ? 1.15D : type == SubVortexType.PERIPHERAL ? 0.78D : 1.0D));
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
                // Height-growing tilt on top of the bend: subvortex funnels lean
                // into their orbit (tangent-dominant, slight outward lean).
                double tiltFrac = fraction * fraction;
                double tilt = (0.55D + intensity * 1.45D) * tiltFrac * visibleRadius;
                Vec3 lean = outward.scale(tilt * 0.45D).add(tangent.scale(tilt * 0.82D));
                // The swing is scaled with the trunk, so a narrower vortex also wanders and leans less in
                // absolute terms instead of a hairline funnel thrashing as far as the full-size one.
                Vec3 bend = outward.scale(bendWave * bendAmount * 0.78D + fraction * visibleRadius * 0.24D)
                        .add(tangent.scale(counterWave * bendAmount * 0.62D))
                        .add(lean)
                        .scale(funnelSize);
                // Layers span from the vortex's lowest reachable height up to the cloud base, so an
                // aloft satellite funnel is a tube hanging in the air rather than one rooted on the ground.
                double span = heightFloor + fraction * (1.0D - heightFloor);
                Vec3 target = new Vec3(base.x + bend.x, base.y + span * height, base.z + bend.z);
                layer.pos = layer.pos == null ? target : layer.pos.lerp(target, 0.20D + reveal * 0.16D);

                // A sub-vortex has its own small funnel: a slender tapering trunk, narrowest at its
                // lowest reach and flaring toward the cloud base, with a wider dust swirl around its
                // base. A breakdown collar is not a funnel at all - it wraps the parent's wall - so it
                // keeps the plain cylindrical ring it had.
                float ropeWave = SubVortexFunnel.ropeWave(lastSeenTick, vortex.id(), (float) fraction);
                float topRadius = SubVortexFunnel.topRadius((float) (visibleRadius * funnelSize), (float) intensity);
                float wallRadius = collar
                        ? Mth.clamp((float) (visibleRadius * (0.66D + fraction * 0.20D + ropeWave)), 1.05F, (float) visibleRadius * 1.28F)
                        : SubVortexFunnel.wallRadius(topRadius, (float) fraction, ropeWave);
                float dustRadius = collar ? wallRadius : SubVortexFunnel.dustRadius(topRadius, (float) fraction);
                float adjustedRadius = Math.max(0.65F, wallRadius * 0.82F);
                int targetClouds = collar
                        ? Mth.clamp((int) Math.ceil(wallRadius * 2.0F * Mth.PI / Math.max(0.92F, 2.25F / adjustedRate)), 4, 30)
                        : SubVortexFunnel.puffCount(wallRadius, adjustedRate);
                // A vortex ropes out or grows in from the cloud base down, so its tip is the last part
                // of the trunk to appear and the first to go: thinner rings until it is fully formed.
                if (reveal < 0.45D) {
                    targetClouds = Math.max(2, targetClouds / 2);
                }
                if (!funnelVisible) {
                    // No condensation: a dust whirl is only ever seen through the debris it lifts.
                    targetClouds = 0;
                }
                cleanup(layer.clouds, targetClouds);
                while (layer.clouds.size() < targetClouds) {
                    PivotingParticle particle = createCloud(level, cloudBase, renderCull);
                    particle.spawnAsWeatherEffect();
                    layer.clouds.add(particle);
                }

                float spinSpeed = Math.max(0.92F, 1.85F - (float) fraction * 0.70F);
                layer.rotation += (spinBase * spinSpeed * (float) rotationSign) / Math.max(0.55F, adjustedRadius);
                if (collar) {
                    positionClouds(layer, targetClouds, adjustedRadius, wallRadius,
                            (float) Math.max(visibleRadius * 1.34D, wallRadius), fraction, reveal);
                } else {
                    // Puffs sized to the ring they sit on, so the trunk reads as a surface rather than
                    // as a few large blobs strung round a circle.
                    positionClouds(layer, targetClouds, adjustedRadius,
                            SubVortexFunnel.puffScale(wallRadius, targetClouds),
                            SubVortexFunnel.condensationAlpha((float) reveal));
                }

                // A vortex-breakdown collar rides the funnel aloft, so it never scours debris.
                // A dust whirl has nothing BUT debris, and it carries it the whole height of the swirl.
                int targetDebris;
                if (collar) {
                    targetDebris = 0;
                } else if (!funnelVisible) {
                    targetDebris = reveal > 0.25D && fraction < 0.88D
                            ? Mth.clamp((int) (7.0F + intensity * 18.0F), 6, 26)
                            : 0;
                } else {
                    targetDebris = reveal > 0.58D && fraction < 0.38D ? Mth.clamp((int) (3.0F + intensity * 10.0F), 3, 14) : 0;
                }
                cleanup(layer.debris, targetDebris);
                while (layer.debris.size() < targetDebris) {
                    PivotingParticle particle = createDebris(level, layer.pos, renderCull);
                    particle.spawnAsWeatherEffect();
                    layer.debris.add(particle);
                }
                // Debris rides the dust swirl, which is wider than the condensation at the base - the
                // vortex's scouring surface - and the funnel's own surface above it.
                positionDebris(layer, targetDebris, Math.max(adjustedRadius, dustRadius * 0.82F));
            }
        }

        private void tickGenesisWhirl(ClientLevel level, long seed, Vec3 groundCenter, double windMph, double rotationSign,
                                      double visibleRadius, double height, double visibility) {
            lastSeenTick = level.getGameTime();
            if (firstSeenTick == Long.MIN_VALUE) {
                firstSeenTick = lastSeenTick;
            }
            smoothBase(level, groundCenter);

            int layerCount = Mth.clamp((int) Math.round(height / HEIGHT_PER_LAYER), 6, 26);
            Vec3 cloudBase = new Vec3(base.x, base.y + height, base.z);
            ensureLayers(layerCount, cloudBase);
            trimLayers(layerCount);

            float adjustedRate = particleRate();
            float renderCull = Minecraft.getInstance().gameRenderer.getRenderDistance() * 4.0F;
            float spinBase = (float) (58.0D + windMph * 0.55D);

            for (int i = 0; i < layerCount; i++) {
                VisualLayer layer = layers.get(i);
                double fraction = i / (double) Math.max(1, layerCount - 1);
                // It grows from the ground up and lets go from the top down, so a whirl never arrives
                // or leaves as a whole column - the base is the last thing to go.
                double reveal = smoothstep(Mth.clamp((visibility - (1.0D - fraction) * 0.34D) / 0.30D, 0.0D, 1.0D));
                if (reveal <= 0.02D) {
                    cleanup(layer.clouds, 0);
                    cleanup(layer.debris, 0);
                    continue;
                }
                double swayA = Math.sin(lastSeenTick * 0.071D + seed * 0.37D + fraction * Math.PI * 2.45D);
                double swayB = Math.cos(lastSeenTick * 0.048D + seed * 0.19D + fraction * Math.PI * 4.10D);
                double sway = visibleRadius * 0.42D * Math.sin(fraction * Math.PI)
                        * Mth.clamp(0.35D + visibility * 0.80D, 0.0D, 1.0D);
                Vec3 target = new Vec3(base.x + swayA * sway, base.y + fraction * height, base.z + swayB * sway);
                layer.pos = layer.pos == null ? target : layer.pos.lerp(target, 0.24D + reveal * 0.18D);

                // A whirl is drawn as a dust column: the dust silhouette from the ground up, so it is a
                // swarm of small puffs making one slender column rather than a few huge ones making a
                // squat blob. It scales off the same profile the sub-vortices use, because a whirl is
                // the incipient funnel's ground swirl and should read as a small version of one.
                float wobble = SubVortexFunnel.dustWobble(lastSeenTick, seed, (float) fraction);
                float topRadius = SubVortexFunnel.topRadius((float) visibleRadius, 0.0F);
                float columnRadius = SubVortexFunnel.dustColumnRadius(topRadius, (float) fraction, wobble);
                float adjustedRadius = Math.max(0.35F, columnRadius * 0.82F);
                // No condensation at all: a whirl is visible only through what it is lifting, so the
                // column is haze low down and debris where it is actually working the ground. Haze has
                // no edge to close, so it carries fewer of the ring's puffs than a funnel would - but
                // each one still covers its share of the ring, so the column stays whole.
                int targetClouds = fraction < 0.72D
                        ? SubVortexFunnel.dustColumnPuffCount(columnRadius, adjustedRate)
                        : 0;
                if (reveal < 0.45D) {
                    targetClouds = Math.max(1, targetClouds / 2);
                }
                cleanup(layer.clouds, targetClouds);
                while (layer.clouds.size() < targetClouds) {
                    PivotingParticle particle = createCloud(level, layer.pos == null ? base : layer.pos, renderCull);
                    particle.spawnAsWeatherEffect();
                    layer.clouds.add(particle);
                }
                layer.rotation += (spinBase * (float) rotationSign) / Math.max(0.60F, adjustedRadius);
                positionClouds(layer, targetClouds, adjustedRadius,
                        SubVortexFunnel.puffScale(columnRadius, targetClouds),
                        SubVortexFunnel.condensationAlpha((float) reveal));
                // A whirl is thinner stuff than a funnel: its haze is drawn back so the swirl reads as
                // dust hanging in the air rather than as a solid column of condensation.
                float haze = Mth.clamp(0.40F + 0.50F * (float) visibility, 0.30F, 0.86F);
                for (PivotingParticle particle : layer.clouds) {
                    float faded = Mth.clamp(particle.getAlphaF() * haze, 0.05F, 0.88F);
                    particle.setAlpha(faded);
                    particle.setFullAlphaTarget(faded);
                }

                int targetDebris = fraction < 0.36D ? Mth.clamp((int) (2.0F + reveal * 6.0F), 1, 8) : 0;
                cleanup(layer.debris, targetDebris);
                while (layer.debris.size() < targetDebris) {
                    PivotingParticle particle = createDebris(level, layer.pos == null ? base : layer.pos, renderCull);
                    particle.spawnAsWeatherEffect();
                    layer.debris.add(particle);
                }
                positionDebris(layer, targetDebris, adjustedRadius);
            }
        }

        private void tickHorizontalVortex(ClientLevel level, StormObject storm, DynamicStormState.HorizontalVortex vortex, Vec3 groundCenter, double parentRadius, double windMph, double rotationSign) {
            lastSeenTick = level.getGameTime();
            if (firstSeenTick == Long.MIN_VALUE) {
                firstSeenTick = lastSeenTick;
            }
            smoothBase(level, groundCenter);

            double intensity = Mth.clamp(vortex.intensityFraction(), 0.08D, 1.0D);
            // Once a tube tears off it is no longer part of the funnel: it keeps only the shape
            // parameters it had, is thrown clear of the wall and drifts as a free-roaming tube.
            boolean detached = vortex.isDetached(lastSeenTick);
            double freeProgress = vortex.freeProgress(lastSeenTick);
            // A horizontal vortex is a TUBE THAT WRAPS THE FUNNEL: it is born in the tornado's
            // outer circulation, hugs the wall and rises while it circles, and can wrap right
            // around the condensation funnel (Oliveira et al. 2019 - the classic Andover KS
            // footage shows one wrapping the funnel more than once). So the tube follows a helix
            // on the funnel's OWN wall - read live from Weather2's layers, so it stays stuck to
            // a leaning, bending, flaring funnel - instead of being a straight arm in open air.
            double baseBearing = Math.toRadians(vortex.orbitDegrees());
            double wrapSpan = Math.toRadians(vortex.wrapDegrees() * rotationSign);
            double climb = vortex.climbFraction() + Math.sin(Math.toRadians(vortex.pitchDegrees())) * 0.05D;
            double baseHeight = vortex.heightFraction();
            double hugMid = hugAt(vortex, 0.5D, intensity);
            Vec3 midWall = funnelWallPoint(storm, baseBearing, baseHeight, hugMid);
            Vec3 outward = new Vec3(Math.cos(baseBearing), 0.0D, Math.sin(baseBearing));
            // The direction the circulation is travelling at the tear-off bearing: the tube leaves
            // the wall with the wind, so it is carried behind the funnel's rotation.
            Vec3 circulation = new Vec3(-outward.z * rotationSign, 0.0D, outward.x * rotationSign);
            if (detached) {
                advanceFreeFlight(storm, vortex, outward, circulation, baseBearing, baseHeight, hugMid);
            }
            double wrapRadius = Math.max(2.0D, DynamicStormController.funnelWallRadiusAt(storm, (float) baseHeight)) * hugMid;
            double arcLength = Mth.clamp(
                    wrapRadius * Math.abs(wrapSpan) * (0.85D + intensity * 0.45D) * (0.72D + vortex.lengthFraction() * 0.55D),
                    12.0D, 130.0D);
            double tubeRadius = Mth.clamp(parentRadius * vortex.radiusFraction() * (0.85D + intensity * 0.45D), 1.1D, 5.5D)
                    * (1.0D - 0.34D * freeProgress);
            int layerCount = Mth.clamp((int) Math.ceil(arcLength / 2.4D), 6, 30);
            ensureLayers(layerCount, base);
            trimLayers(layerCount);
            // Sample the spine once along the tube (plus one step past each end for the tangents) so
            // each layer's ring is oriented across the tube's local direction. A tube that has torn
            // off swaps the wall-hugging helix for a free-flying rope, blended in over the first few
            // ticks so it visibly peels away from the wall instead of snapping to a new shape.
            double peel = detached
                    ? smoothstep(Mth.clamp((lastSeenTick - vortex.detachTick()) / 14.0D, 0.0D, 1.0D))
                    : 0.0D;
            // The swing starts pointing along the circulation - where the tube's own axis was while
            // it hugged the wall - so peeling away is a rotation rather than a snap to a new pose.
            double tumble = Math.PI * 0.5D + (vortex.freeTumblePhase() - Math.PI) * 0.25D
                    + Math.toRadians(vortex.freeTumbleRate()) * Math.max(0L, lastSeenTick - vortex.detachTick());
            Vec3[] spine = new Vec3[layerCount + 2];
            for (int i = 0; i < layerCount + 2; i++) {
                double t = (i - 1) / (double) Math.max(1, layerCount - 1);
                Vec3 attachedPoint = wrapPoint(storm, vortex, base, midWall, baseBearing, wrapSpan, climb, baseHeight, intensity, t);
                if (!detached) {
                    spine[i] = attachedPoint;
                    continue;
                }
                Vec3 freePoint = freeSpinePoint(vortex, outward, circulation, arcLength, freeProgress, tumble, t);
                spine[i] = peel >= 1.0D ? freePoint : attachedPoint.lerp(freePoint, peel);
            }

            float adjustedRate = particleRate();
            float renderCull = Minecraft.getInstance().gameRenderer.getRenderDistance() * 4.0F;
            double formation = smoothstep(Mth.clamp((lastSeenTick - vortex.spawnTick() + 10.0D) / 28.0D, 0.0D, 1.0D));
            double lifeFade = smoothstep(Mth.clamp((vortex.expireTick() - lastSeenTick) / 42.0D, 0.0D, 1.0D));
            double visibility = formation * Math.max(0.0D, lifeFade) * (1.0D - 0.35D * freeProgress);
            // A torn-off tube is still a spinning vortex, but it winds down as it dissipates.
            float spinBase = (float) ((74.0D + windMph * 0.34D) * (1.0D - 0.25D * freeProgress));

            for (int i = 0; i < layerCount; i++) {
                VisualLayer layer = layers.get(i);
                double fraction = i / (double) Math.max(1, layerCount - 1);
                double reveal = smoothstep(Mth.clamp((visibility - Math.abs(fraction - 0.5D) * 0.42D) / 0.30D, 0.0D, 1.0D));
                if (reveal <= 0.02D) {
                    cleanup(layer.clouds, 0);
                    cleanup(layer.debris, 0);
                    continue;
                }
                Vec3 here = spine[i + 1];
                Vec3 tangent = normalize(spine[i + 2].subtract(spine[i]));
                Vec3 side = normalize(new Vec3(-tangent.z, 0.0D, tangent.x));
                Vec3 normal = normalize(new Vec3(
                        side.y * tangent.z - side.z * tangent.y,
                        side.z * tangent.x - side.x * tangent.z,
                        side.x * tangent.y - side.y * tangent.x
                ));
                double rollWave = Math.sin(lastSeenTick * 0.060D + vortex.id() * 0.43D + fraction * Math.PI * 2.7D);
                double liftWave = Math.cos(lastSeenTick * 0.047D + vortex.id() * 0.21D + fraction * Math.PI * 3.4D);
                double endTaper = Mth.clamp(Math.sin(fraction * Math.PI), 0.24D, 1.0D);
                Vec3 target = here
                        .add(side.scale(rollWave * tubeRadius * 0.30D))
                        .add(normal.scale(liftWave * tubeRadius * 0.18D))
                        .add(0.0D, endTaper * tubeRadius * 0.10D, 0.0D);
                layer.pos = layer.pos == null ? target : layer.pos.lerp(target, 0.30D + reveal * 0.18D);

                double radiusWave = 0.10D * Math.sin(lastSeenTick * 0.075D + fraction * Math.PI * 5.0D + vortex.id());
                float radius = (float) Mth.clamp(tubeRadius * (0.44D + intensity * 0.08D + radiusWave) * (0.42D + endTaper * 0.58D), 0.32D, tubeRadius * 0.92D);
                float circumference = radius * 2.0F * Mth.PI;
                int targetClouds = Mth.clamp((int) Math.ceil(circumference / Math.max(1.35F, 4.8F / adjustedRate)), 3, 12);
                cleanup(layer.clouds, targetClouds);
                while (layer.clouds.size() < targetClouds) {
                    PivotingParticle particle = createCloud(level, layer.pos == null ? base : layer.pos, renderCull);
                    particle.spawnAsWeatherEffect();
                    layer.clouds.add(particle);
                }

                layer.rotation += (spinBase * (float) rotationSign) / Math.max(0.75F, radius);
                positionHorizontalClouds(layer, targetClouds, side, normal, tangent, radius, reveal);

                int targetDebris = reveal > 0.62D && fraction < 0.62D
                        ? Mth.clamp((int) ((1.0F + intensity * 3.0F) * (1.0F - 0.75F * (float) freeProgress)), 0, 4)
                        : 0;
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

        /**
         * Releases a torn-off tube and flies it for one tick. The tear-off point is captured once,
         * from the wall it was hugging, and the tube is then thrown outward off that wall, carried
         * along with the storm and dropped slowly - so it leaves the funnel behind rather than
         * following it. Everything here is reconstructed from the detach tick plus per-tube hashes,
         * so the flight is identical on every client and survives a state sync.
         */
        private void advanceFreeFlight(StormObject storm, DynamicStormState.HorizontalVortex vortex, Vec3 outward, Vec3 circulation,
                                       double baseBearing, double baseHeight, double hugMid) {
            if (!freeInitialised) {
                freeInitialised = true;
                freeOrigin = funnelWallPoint(storm, baseBearing, baseHeight, hugMid);
                double wallRadius = Math.max(2.0D, DynamicStormController.funnelWallRadiusAt(storm, (float) baseHeight));
                // Scales with the funnel (a wedge throws harder than a rope) but stays bounded, so a
                // torn-off tube drifts clear of the tornado instead of vanishing out of sight.
                double throwSpeed = Mth.clamp(wallRadius * vortex.freeOutwardDrift(), 0.12D, 1.05D);
                freeVelocity = outward.scale(throwSpeed)
                        .add(circulation.scale(-0.030D))
                        .add(baseVelocity.scale(0.70D))
                        .add(0.0D, vortex.freeVerticalDrift(), 0.0D);
            }
            freeOrigin = freeOrigin.add(freeVelocity);
            // Drag bleeds the throw off, so the rope slows down once it is clear of the tornado
            // instead of accelerating away forever.
            freeVelocity = freeVelocity.scale(0.978D);
            // Keep it in the air: it is a condensation tube, not a ground vortex.
            double floor = base.y + 6.0D;
            if (freeOrigin.y < floor) {
                freeOrigin = new Vec3(freeOrigin.x, floor, freeOrigin.z);
                freeVelocity = new Vec3(freeVelocity.x, Math.max(0.0D, freeVelocity.y), freeVelocity.z);
            }
        }

        /**
         * Point on the free-flying tube at parameter {@code t} (0 = trailing end, 1 = leading end).
         * Nothing is anchoring it any more, so it hangs off its tumble axis as a bowed rope: the
         * axis sweeps around as it rolls, it stretches briefly as it snaps free and then relaxes,
         * and both ends drag behind its own travel.
         */
        private Vec3 freeSpinePoint(DynamicStormState.HorizontalVortex vortex, Vec3 outward, Vec3 circulation,
                                    double arcLength, double freeProgress, double tumble, double t) {
            double along = (t - 0.5D) * 2.0D;
            Vec3 direction = normalize(outward.scale(Math.cos(tumble))
                    .add(circulation.scale(Math.sin(tumble)))
                    .add(0.0D, 0.30D * Math.sin(tumble * 1.6D + vortex.freeTumblePhase()), 0.0D));
            double stretch = 1.0D + 0.22D * Math.sin(Math.PI * Mth.clamp(freeProgress * 2.6D, 0.0D, 1.0D));
            Vec3 point = freeOrigin.add(direction.scale(along * arcLength * 0.5D * stretch));
            Vec3 side = normalize(new Vec3(-direction.z, 0.0D, direction.x));
            // A loose rope bows and droops in the middle; nothing is holding it taut.
            double bow = Math.sin(Math.PI * Mth.clamp(t, 0.0D, 1.0D)) * arcLength * 0.10D;
            point = point.add(side.scale(bow * 0.60D)).add(0.0D, -bow * 0.35D, 0.0D);
            // Both ends lag behind the travel, so the tube trails its own motion - scaled by its
            // length so a short tube does not fold itself in half.
            return point.add(freeVelocity.scale(-Math.min(7.0D, arcLength * 0.16D) * Math.abs(along)));
        }

        /**
         * Point on the tube's helix at parameter {@code t} (0 = trailing end, 1 = leading end):
         * the bearing advances by the vortex's wrap span, the height climbs, and the hug grows so
         * the far end peels a little off the wall. Expressed as an offset from the smoothed anchor
         * so the tube keeps the anchor's smooth motion while its shape tracks the live funnel.
         */
        private static Vec3 wrapPoint(StormObject storm, DynamicStormState.HorizontalVortex vortex, Vec3 base, Vec3 midWall,
                                      double baseBearing, double wrapSpan, double climb, double baseHeight,
                                      double intensity, double t) {
            double height = Mth.clamp(baseHeight + climb * (t - 0.5D), 0.02D, 0.965D);
            double bearing = baseBearing + wrapSpan * (t - 0.5D);
            Vec3 wall = funnelWallPoint(storm, bearing, height, hugAt(vortex, t, intensity));
            return base.add(wall.subtract(midWall));
        }

        /**
         * How tightly the tube rides the wall at parameter t: exactly on its own hug factor at the
         * middle (so it matches the anchor), tighter at the root and peeling outward at the tip.
         */
        private static double hugAt(DynamicStormState.HorizontalVortex vortex, double t, double intensity) {
            return vortex.wallHugFactor() * (1.0D + (t - 0.5D) * (0.30D + intensity * 0.12D));
        }

        /** Point on the live funnel wall at a bearing and height fraction. */
        private static Vec3 funnelWallPoint(StormObject storm, double bearing, double heightFraction, double hug) {
            double height = Mth.clamp(heightFraction, 0.0D, 1.0D);
            Vec3 center = DynamicStormController.funnelLayerCenterAt(storm, (float) height);
            double radius = Math.max(2.0D, DynamicStormController.funnelWallRadiusAt(storm, (float) height)) * hug;
            double centerX = center == null ? 0.0D : center.x;
            double centerY = center == null ? 0.0D : center.y;
            double centerZ = center == null ? 0.0D : center.z;
            return new Vec3(centerX + Math.cos(bearing) * radius, centerY, centerZ + Math.sin(bearing) * radius);
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
            float formedScale = (float) (0.45D + reveal * 0.55D);
            float scale = (float) Mth.clamp((6.25F * (radius / Math.max(1.0F, radiusMax)) + fraction * 3.35F) * formedScale, 1.55F, 13.5F);
            float alpha = Mth.clamp(0.46F + 0.48F * (float) reveal, 0.20F, 0.94F);
            positionClouds(layer, count, adjustedRadius, scale, alpha);
        }

        /**
         * Places one ring of cloud puffs at a single pivot radius, every puff the same size and opacity.
         * Both are layer-wide values for a funnel of a given width - which is why a caller that has
         * worked out its own silhouette can place the ring directly.
         */
        private static void positionClouds(VisualLayer layer, int count, float adjustedRadius, float scale, float alpha) {
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
                particle.setScale(scale);
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
                particle.setScale((float) Mth.clamp(radius * (1.45D + reveal * 0.35D), 1.1D, 7.5D));
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
