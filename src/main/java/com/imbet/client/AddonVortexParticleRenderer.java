package com.imbet.client;

import com.imbet.Config;
import com.imbet.weather.BlockGrabSafety;
import com.imbet.weather.DustDevilEntity;
import com.imbet.weather.GustnadoEntity;
import com.imbet.weather.LandspoutEntity;
import com.imbet.weather.WaterspoutEntity;
import extendedrenderer.particle.ParticleRegistry;
import extendedrenderer.particle.entity.PivotingParticle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class AddonVortexParticleRenderer {
    private static final Map<Integer, VortexVisual> VISUALS = new HashMap<>();

    private AddonVortexParticleRenderer() {
    }

    public static void tick(DustDevilEntity entity, float partialTick) {
        if (!Config.VISUALS_ENABLED.get() || !Config.EXTENDED_RENDERING_ENABLED.get() || entity == null || entity.isRemoved() || ParticleRegistry.cloud256 == null) {
            return;
        }
        if (Minecraft.getInstance().isPaused()) {
            return;
        }
        if (!(entity.level() instanceof ClientLevel level)) {
            return;
        }
        VortexKind kind = VortexKind.forEntity(entity);
        VortexVisual visual = VISUALS.computeIfAbsent(entity.getId(), ignored -> new VortexVisual(kind));
        visual.tick(level, entity, kind, partialTick);
    }

    public static void cleanup(long gameTime) {
        Iterator<Map.Entry<Integer, VortexVisual>> iterator = VISUALS.entrySet().iterator();
        while (iterator.hasNext()) {
            VortexVisual visual = iterator.next().getValue();
            if (gameTime - visual.lastSeenTick > 2L && visual.fadeTick(gameTime)) {
                visual.remove();
                iterator.remove();
            }
        }
    }

    public static void clear() {
        for (VortexVisual visual : VISUALS.values()) {
            visual.remove();
        }
        VISUALS.clear();
    }

    private enum VortexKind {
        DUST_DEVIL,
        GUSTNADO,
        LANDSPOUT,
        WATERSPOUT;

        private static VortexKind forEntity(DustDevilEntity entity) {
            if (entity instanceof WaterspoutEntity) {
                return WATERSPOUT;
            }
            if (entity instanceof LandspoutEntity) {
                return LANDSPOUT;
            }
            if (entity instanceof GustnadoEntity) {
                return GUSTNADO;
            }
            return DUST_DEVIL;
        }

        private boolean funnelLike() {
            return this == LANDSPOUT || this == WATERSPOUT;
        }
    }

    private static final class VortexVisual {
        private final List<VisualLayer> layers = new ArrayList<>();
        private VortexKind lastKind;
        private Vec3 base;
        private long lastSeenTick;
        private long lastUpdateTick = Long.MIN_VALUE;
        private long fadeStartTick = Long.MIN_VALUE;
        private Vec3 lastMotion = Vec3.ZERO;
        private boolean fading;

        private VortexVisual(VortexKind kind) {
            this.lastKind = kind;
        }

        private void tick(ClientLevel level, DustDevilEntity entity, VortexKind kind, float partialTick) {
            long gameTime = level.getGameTime();
            lastSeenTick = gameTime;
            fading = false;
            fadeStartTick = Long.MIN_VALUE;
            if (lastUpdateTick == gameTime) {
                return;
            }
            lastUpdateTick = gameTime;
            if (kind != lastKind) {
                remove();
                lastKind = kind;
            }
            Vec3 entityMotion = entity.getDeltaMovement();
            if (entityMotion != null && entityMotion.horizontalDistanceSqr() > 1.0E-6D) {
                lastMotion = new Vec3(entityMotion.x, 0.0D, entityMotion.z);
            }
            Vec3 targetBase = visualBase(level, entity, kind, partialTick);
            base = base == null ? targetBase : base.lerp(targetBase, kind.funnelLike() ? 0.42D : 0.55D);

            double wind = Math.max(12.0D, entity.maxWindMph());
            double rawRadius = Math.max(1.0D, entity.radius());
            double height = heightFor(kind, rawRadius, wind);
            int layerCount = layerCountFor(kind, height);
            ensureLayers(layerCount, base);
            trimLayers(layerCount);

            float particleRate = particleRate();
            float renderCull = Minecraft.getInstance().gameRenderer.getRenderDistance() * 4.0F;
            SurfaceTone surfaceTone = SurfaceTone.from(level, BlockPos.containing(base.x, base.y - 0.75D, base.z), kind);
            float rotationSign = (entity.getId() & 1) == 0 ? 1.0F : -1.0F;
            float spinBase = spinBaseFor(kind, wind);

            for (int i = 0; i < layerCount; i++) {
                VisualLayer layer = layers.get(i);
                double fraction = i / (double) Math.max(1, layerCount - 1);
                double targetY = base.y + fraction * height;
                double layerRadius = radiusFor(kind, rawRadius, wind, fraction);
                Vec3 shear = shearOffset(kind, lastMotion, fraction, height);
                Vec3 layerTarget = new Vec3(base.x + shear.x, targetY + verticalRoll(level, i, fraction, kind), base.z + shear.z);
                layer.pos = layer.pos == null ? layerTarget : layer.pos.lerp(layerTarget, 0.50D);

                int targetClouds = targetClouds(kind, layerRadius, fraction, particleRate);
                cleanup(layer.clouds, targetClouds);
                while (layer.clouds.size() < targetClouds) {
                    PivotingParticle particle = createCloud(level, layer.pos, renderCull, surfaceTone, false);
                    particle.spawnAsWeatherEffect();
                    layer.clouds.add(particle);
                }

                int targetCore = targetCore(kind, fraction, particleRate, surfaceTone);
                cleanup(layer.core, targetCore);
                while (layer.core.size() < targetCore) {
                    PivotingParticle particle = createCloud(level, layer.pos, renderCull, surfaceTone, true);
                    particle.spawnAsWeatherEffect();
                    layer.core.add(particle);
                }

                layer.rotation += spinStepFor(kind, spinBase, (float) layerRadius, rotationSign);
                positionParticles(layer.clouds, layer, (float) layerRadius, fraction, kind, surfaceTone, false);
                positionParticles(layer.core, layer, (float) Math.max(0.35D, layerRadius * 0.48D), fraction, kind, surfaceTone, true);
            }
        }

        private boolean fadeTick(long gameTime) {
            if (layers.isEmpty()) {
                return true;
            }
            if (!fading) {
                fading = true;
                fadeStartTick = gameTime;
            }
            long age = Math.max(0L, gameTime - fadeStartTick);
            long duration = fadeDuration(lastKind);
            float fade = 1.0F - Mth.clamp(age / (float) duration, 0.0F, 1.0F);
            if (fade <= 0.0F) {
                return true;
            }
            Vec3 drift = lastMotion.horizontalDistanceSqr() > 1.0E-6D
                    ? new Vec3(lastMotion.x, 0.0D, lastMotion.z).normalize().scale(lastKind == VortexKind.GUSTNADO ? 0.075D : 0.032D)
                    : Vec3.ZERO;
            float spin = lastKind == VortexKind.GUSTNADO ? 4.4F : lastKind == VortexKind.DUST_DEVIL ? 3.1F : 2.2F;
            for (VisualLayer layer : layers) {
                if (layer.pos != null) {
                    layer.pos = layer.pos.add(drift.x, lastKind == VortexKind.GUSTNADO ? 0.010D : 0.025D, drift.z);
                }
                layer.rotation += spin;
                fadeParticles(layer.clouds, layer, spin, fade);
                fadeParticles(layer.core, layer, spin * 1.25F, fade);
            }
            return false;
        }

        private static void fadeParticles(List<PivotingParticle> particles, VisualLayer layer, float spin, float fade) {
            for (PivotingParticle particle : particles) {
                if (particle == null || !particle.isAlive()) {
                    continue;
                }
                Vec3 previousRot = particle.getPivotRot();
                float rot = previousRot == null ? layer.rotation : (float) previousRot.y + spin;
                particle.setPivotRotPrev(previousRot == null ? new Vec3(0.0D, rot, 0.0D) : previousRot);
                particle.setPivotRot(new Vec3(0.0D, rot, 0.0D));
                particle.setPrevPosX(particle.getPosX());
                particle.setPrevPosY(particle.getPosY());
                particle.setPrevPosZ(particle.getPosZ());
                if (layer.pos != null) {
                    particle.setPosition(layer.pos.x, layer.pos.y, layer.pos.z);
                }
                particle.setScale(Mth.clamp(particle.getScale() * 1.006F, 0.05F, 64.0F));
                float fadedAlpha = Mth.clamp(0.88F * fade, 0.0F, 0.88F);
                particle.setAlpha(fadedAlpha);
                particle.setFullAlphaTarget(fadedAlpha);
            }
        }

        private static long fadeDuration(VortexKind kind) {
            return switch (kind) {
                case DUST_DEVIL -> 95L;
                case GUSTNADO -> 116L;
                case LANDSPOUT, WATERSPOUT -> 45L;
            };
        }

        private static float spinBaseFor(VortexKind kind, double wind) {
            return switch (kind) {
                case WATERSPOUT -> (float) (42.0D + wind * 0.38D);
                case LANDSPOUT -> (float) (36.0D + wind * 0.30D);
                case GUSTNADO -> (float) (22.0D + wind * 0.24D);
                case DUST_DEVIL -> (float) (24.0D + wind * 0.28D);
            };
        }

        private static float spinRadiusDamping(VortexKind kind, float radius) {
            return switch (kind) {
                case WATERSPOUT -> Math.max(1.15F, radius * 0.72F + 0.45F);
                case LANDSPOUT -> Math.max(1.05F, radius * 0.78F + 0.45F);
                default -> Math.max(0.65F, radius + 0.35F);
            };
        }

        private static float spinStepFor(VortexKind kind, float spinBase, float radius, float rotationSign) {
            float rawStep = spinBase / spinRadiusDamping(kind, radius);
            return Mth.clamp(rawStep, 1.2F, maxSpinStepFor(kind)) * rotationSign;
        }

        private static float maxSpinStepFor(VortexKind kind) {
            return switch (kind) {
                case WATERSPOUT -> 8.5F;
                case LANDSPOUT -> 7.2F;
                case GUSTNADO -> 5.8F;
                case DUST_DEVIL -> 7.0F;
            };
        }

        private static Vec3 visualBase(ClientLevel level, DustDevilEntity entity, VortexKind kind, float partialTick) {
            double x = Mth.lerp(partialTick, entity.xo, entity.getX());
            double y = Mth.lerp(partialTick, entity.yo, entity.getY());
            double z = Mth.lerp(partialTick, entity.zo, entity.getZ());
            BlockPos ground = BlockGrabSafety.vortexGroundSurface(level, BlockPos.containing(x, y, z));
            double groundY = ground == null ? y : ground.getY();
            double offset = switch (kind) {
                case LANDSPOUT -> Math.max(5.5D, entity.radius() * 0.72D);
                case WATERSPOUT -> 0.45D + waterspoutLiftOffset(entity);
                case GUSTNADO -> 0.30D;
                case DUST_DEVIL -> 0.22D;
            };
            return new Vec3(x, Math.max(y, groundY + offset), z);
        }

        private static double waterspoutLiftOffset(DustDevilEntity entity) {
            int remaining = entity.lifeTicksRemaining();
            if (remaining > 600) {
                return 0.0D;
            }
            double liftProgress = 1.0D - Mth.clamp(remaining / 600.0D, 0.0D, 1.0D);
            return smoothstep(liftProgress) * Mth.clamp(12.0D + entity.radius() * 1.45D, 14.0D, 30.0D);
        }

        private static double heightFor(VortexKind kind, double radius, double wind) {
            return switch (kind) {
                case WATERSPOUT -> Mth.clamp(28.0D + wind * 0.26D + radius * 0.95D, 34.0D, 66.0D);
                case LANDSPOUT -> Mth.clamp(30.0D + wind * 0.36D + radius * 1.3D, 36.0D, 82.0D);
                case GUSTNADO -> Mth.clamp(11.0D + wind * 0.17D + radius * 1.75D, 15.0D, 38.0D);
                case DUST_DEVIL -> Mth.clamp(24.0D + wind * 0.42D + radius * 2.0D, 34.0D, 86.0D);
            };
        }

        private static int layerCountFor(VortexKind kind, double height) {
            return switch (kind) {
                case WATERSPOUT -> Mth.clamp((int) (height / 1.35D), 24, 48);
                case LANDSPOUT -> Mth.clamp((int) (height / 1.55D), 22, 54);
                case GUSTNADO -> Mth.clamp((int) (height / 1.15D), 14, 34);
                case DUST_DEVIL -> Mth.clamp((int) (height / 1.75D), 20, 48);
            };
        }

        private static double radiusFor(VortexKind kind, double baseRadius, double wind, double fraction) {
            double eased = smoothstep(fraction);
            return switch (kind) {
                case WATERSPOUT -> Mth.lerp(eased, Math.max(0.35D, baseRadius * 0.16D), Mth.clamp(baseRadius * 0.72D + wind * 0.010D, 2.2D, 6.8D));
                case LANDSPOUT -> Mth.lerp(eased, Math.max(0.30D, baseRadius * 0.18D), Mth.clamp(baseRadius * 1.45D + wind * 0.016D, 3.6D, 12.0D));
                case GUSTNADO -> gustnadoRadius(baseRadius, wind, fraction);
                case DUST_DEVIL -> dustDevilRadius(baseRadius, wind, fraction);
            };
        }

        private static double gustnadoRadius(double baseRadius, double wind, double fraction) {
            double ground = Mth.clamp(baseRadius * 2.70D + wind * 0.036D, 4.0D, 17.0D);
            double core = Mth.clamp(baseRadius * 1.08D + wind * 0.015D, 1.8D, 7.2D);
            double shelf = Mth.clamp(baseRadius * 2.05D + wind * 0.026D, 3.2D, 14.0D);
            if (fraction < 0.30D) {
                return Mth.lerp(smoothstep(fraction / 0.30D), ground, core);
            }
            return Mth.lerp(smoothstep((fraction - 0.30D) / 0.70D), core, shelf);
        }

        private static double dustDevilRadius(double baseRadius, double wind, double fraction) {
            double ground = Mth.clamp(baseRadius * 1.40D + wind * 0.028D, 2.4D, 8.2D);
            double column = Mth.clamp(baseRadius * 0.78D + wind * 0.014D, 1.35D, 4.8D);
            double top = Mth.clamp(baseRadius * 0.42D + wind * 0.007D, 0.75D, 2.8D);
            double lowerBlend = smoothstep(Mth.clamp(fraction / 0.24D, 0.0D, 1.0D));
            double upperBlend = smoothstep(Mth.clamp((fraction - 0.24D) / 0.76D, 0.0D, 1.0D));
            double radius = fraction < 0.24D ? Mth.lerp(lowerBlend, ground, column) : Mth.lerp(upperBlend, column, top);
            double waviness = 1.0D + Math.sin(fraction * Math.PI * 5.5D) * 0.08D;
            return radius * waviness;
        }

        private static double verticalRoll(ClientLevel level, int layerIndex, double fraction, VortexKind kind) {
            double speed = kind.funnelLike() ? 0.045D : 0.085D;
            double amplitude = kind == VortexKind.GUSTNADO ? 0.36D : kind.funnelLike() ? 0.10D : 0.22D;
            return Math.sin(level.getGameTime() * speed + layerIndex * 0.72D + fraction * Math.PI * 2.0D) * amplitude;
        }

        private static Vec3 shearOffset(VortexKind kind, Vec3 motion, double fraction, double height) {
            if (motion == null || motion.horizontalDistanceSqr() < 1.0E-6D) {
                return Vec3.ZERO;
            }
            Vec3 forward = new Vec3(motion.x, 0.0D, motion.z).normalize();
            if (kind == VortexKind.GUSTNADO) {
                double lean = Math.pow(fraction, 1.18D) * Mth.clamp(height * 0.54D, 4.0D, 18.0D);
                Vec3 cross = new Vec3(-forward.z, 0.0D, forward.x).scale(Math.sin(fraction * Math.PI * 3.0D) * 1.3D);
                return forward.scale(lean).add(cross);
            }
            if (kind == VortexKind.DUST_DEVIL) {
                double lean = Math.sin(fraction * Math.PI) * Mth.clamp(height * 0.045D, 0.25D, 2.2D);
                return forward.scale(lean);
            }
            return Vec3.ZERO;
        }

        private static int targetClouds(VortexKind kind, double radius, double fraction, float particleRate) {
            double circumference = Math.max(1.0D, radius * Math.PI * 2.0D);
            double spacing = kind == VortexKind.WATERSPOUT ? 2.15D : kind.funnelLike() ? 2.75D : 2.25D;
            int max = kind == VortexKind.WATERSPOUT ? 12 : kind == VortexKind.LANDSPOUT ? 14 : kind == VortexKind.GUSTNADO ? 26 : kind == VortexKind.DUST_DEVIL ? 16 : 12;
            int min = kind.funnelLike() ? 2 : kind == VortexKind.GUSTNADO ? 2 : 1;
            if (kind == VortexKind.DUST_DEVIL) {
                min = fraction < 0.82D ? 2 : 1;
            } else if (kind == VortexKind.GUSTNADO) {
                min = fraction < 0.76D ? 4 : 3;
            }
            int count = (int) Math.ceil(circumference / spacing * particleRate);
            if (!kind.funnelLike() && fraction > 0.72D) {
                count = Math.max(min, count - 1);
            }
            return Mth.clamp(count, min, max);
        }

        private static int targetCore(VortexKind kind, double fraction, float particleRate, SurfaceTone tone) {
            if (kind == VortexKind.WATERSPOUT || kind == VortexKind.LANDSPOUT) {
                return 0;
            }
            if (kind == VortexKind.DUST_DEVIL && !tone.hasDarkCore) {
                return 0;
            }
            if (kind == VortexKind.DUST_DEVIL && fraction < 0.62D) {
                return Math.max(1, (int) (3.0F * particleRate));
            }
            if (kind == VortexKind.GUSTNADO && fraction < 0.78D) {
                return Math.max(2, (int) (4.0F * particleRate));
            }
            if (fraction < 0.18D || fraction > 0.76D) {
                return 0;
            }
            return Math.max(1, (int) (2.0F * particleRate));
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
            while (particles.size() > targetCount + 2) {
                particles.remove(particles.size() - 1).remove();
            }
        }

        private static void positionParticles(List<PivotingParticle> particles, VisualLayer layer, float radius, double fraction, VortexKind kind, SurfaceTone tone, boolean core) {
            if (particles.isEmpty()) {
                return;
            }
            float spacing = 360.0F / particles.size();
            int index = 0;
            for (PivotingParticle particle : particles) {
                float targetRot = layer.rotation + helicalTwist(kind, fraction) + spacing * index + (core ? 18.0F : 0.0F);
                Vec3 previousRot = particle.getPivotRot();
                float rot = previousRot == null ? targetRot : continuousAngle((float) previousRot.y, targetRot);
                particle.setPivotRotPrev(previousRot == null ? new Vec3(0.0D, rot, 0.0D) : previousRot);
                particle.setPivotRot(new Vec3(0.0D, rot, 0.0D));
                Vec3 previousPivot = particle.getPivot();
                particle.setPivotPrev(previousPivot == null ? new Vec3(0.0D, radius, 0.0D) : previousPivot);
                particle.setPivot(new Vec3(0.0D, radius, 0.0D));

                Vec3 pivotedPosition = particle.getPivotedPosition(0);
                double vecX = -pivotedPosition.x;
                double vecZ = -pivotedPosition.z;
                particle.prevRotationYaw = particle.rotationYaw;
                particle.rotationYaw = -(float) (Mth.atan2(vecZ, vecX) * 180.0D / Math.PI) + 90.0F;
                particle.rotationYaw += core ? 22.0F : (particle.getEntityId() % 70) - 35.0F;
                particle.rotationPitch = kind.funnelLike() ? -28.0F : -18.0F;
                if (particle.rotationYaw > 0 && particle.prevRotationYaw < 0) {
                    particle.prevRotationYaw += 360.0F;
                }

                particle.setPrevPosX(particle.getPosX());
                particle.setPrevPosY(particle.getPosY());
                particle.setPrevPosZ(particle.getPosZ());
                particle.setPosition(layer.pos.x, layer.pos.y, layer.pos.z);
                particle.setScale(scaleFor(kind, fraction, radius, core));
                float alpha = alphaFor(kind, fraction, core);
                particle.setAlpha(alpha);
                particle.setFullAlphaTarget(alpha);
                if (core) {
                    particle.setColor(tone.coreRed, tone.coreGreen, tone.coreBlue);
                } else {
                    particle.setColor(tone.red, tone.green, tone.blue);
                }
                if (particle.getAge() > particle.getTicksFadeInMax() + 1) {
                    particle.setAge((int) particle.getTicksFadeInMax() + 1);
                }
                index++;
            }
        }

        private static float helicalTwist(VortexKind kind, double fraction) {
            return switch (kind) {
                case WATERSPOUT -> (float) (fraction * 132.0D + Math.sin(fraction * Math.PI * 3.0D) * 5.0D);
                case LANDSPOUT -> (float) (fraction * 155.0D + Math.sin(fraction * Math.PI * 3.5D) * 7.0D);
                case GUSTNADO -> (float) (fraction * 96.0D + Math.sin(fraction * Math.PI * 2.5D) * 22.0D);
                case DUST_DEVIL -> (float) (fraction * 230.0D + Math.sin(fraction * Math.PI * 4.0D) * 14.0D);
            };
        }

        private static float continuousAngle(float previous, float target) {
            return previous + Mth.wrapDegrees(target - previous);
        }

        private static float scaleFor(VortexKind kind, double fraction, float radius, boolean core) {
            float base = switch (kind) {
                case WATERSPOUT -> 1.35F + (float) fraction * 2.55F + radius * 0.12F;
                case LANDSPOUT -> 1.8F + (float) fraction * 3.9F + radius * 0.16F;
                case GUSTNADO -> 5.4F + radius * 0.82F + (float) (1.0D - fraction) * 3.6F + (float) fraction * 1.5F;
                case DUST_DEVIL -> 3.1F + radius * 0.68F + (float) (1.0D - fraction) * 2.25F + (float) fraction * 0.85F;
            };
            float maxScale = kind == VortexKind.WATERSPOUT ? 4.8F : kind == VortexKind.GUSTNADO ? 18.0F : kind == VortexKind.DUST_DEVIL ? 11.0F : 9.5F;
            return Mth.clamp(core ? base * 0.82F : base, 0.85F, maxScale);
        }

        private static float alphaFor(VortexKind kind, double fraction, boolean core) {
            float base = switch (kind) {
                case WATERSPOUT -> 0.56F;
                case LANDSPOUT -> 0.53F;
                case GUSTNADO -> 0.82F;
                case DUST_DEVIL -> 0.70F;
            };
            if (kind.funnelLike()) {
                base *= (float) Mth.clamp(0.50D + fraction * 0.72D, 0.45D, 1.0D);
            } else if (kind == VortexKind.DUST_DEVIL) {
                base *= (float) Mth.clamp(1.10D - fraction * 0.46D, 0.52D, 1.0D);
            } else if (kind == VortexKind.GUSTNADO) {
                base *= (float) Mth.clamp(1.18D - fraction * 0.34D, 0.58D, 1.0D);
            }
            float maxAlpha = kind == VortexKind.GUSTNADO ? 0.94F : kind.funnelLike() ? 0.90F : 0.86F;
            return Mth.clamp(core ? base + 0.16F : base, 0.28F, maxAlpha);
        }

        private static PivotingParticle createCloud(ClientLevel level, Vec3 pos, float renderCull, SurfaceTone tone, boolean core) {
            PivotingParticle particle = new PivotingParticle(level, pos.x, pos.y, pos.z, 0.0D, 0.0D, 0.0D, ParticleRegistry.cloud256);
            particle.setMaxAge(25000);
            particle.setTicksFadeInMax(core ? 8 : 14);
            particle.setParticleSpeed(0.0D, 0.0D, 0.0D);
            particle.setColor(core ? tone.coreRed : tone.red, core ? tone.coreGreen : tone.green, core ? tone.coreBlue : tone.blue);
            float initialAlpha = core ? 0.84F : 0.70F;
            particle.setAlpha(initialAlpha);
            particle.setFullAlphaTarget(initialAlpha);
            particle.setGravity(0.0F);
            particle.isTransparent = true;
            particle.renderOrder = 1;
            particle.rotationYaw = level.random.nextFloat() * 360.0F;
            particle.setRenderDistanceCull(renderCull);
            return particle;
        }

        private static float particleRate() {
            ParticleStatus status = Minecraft.getInstance().options.particles().get();
            if (status == ParticleStatus.MINIMAL) {
                return 0.35F;
            }
            if (status == ParticleStatus.DECREASED) {
                return 0.62F;
            }
            return 1.0F;
        }

        private static double smoothstep(double value) {
            double clamped = Mth.clamp(value, 0.0D, 1.0D);
            return clamped * clamped * (3.0D - 2.0D * clamped);
        }

        private void ensureLayers(int layerCount, Vec3 pos) {
            while (layers.size() < layerCount) {
                layers.add(new VisualLayer(pos));
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

        private void remove() {
            for (VisualLayer layer : layers) {
                layer.remove();
            }
            layers.clear();
        }
    }

    private static final class SurfaceTone {
        private final float red;
        private final float green;
        private final float blue;
        private final float coreRed;
        private final float coreGreen;
        private final float coreBlue;
        private final boolean hasDarkCore;

        private SurfaceTone(float red, float green, float blue, float coreRed, float coreGreen, float coreBlue, boolean hasDarkCore) {
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.coreRed = coreRed;
            this.coreGreen = coreGreen;
            this.coreBlue = coreBlue;
            this.hasDarkCore = hasDarkCore;
        }

        private static SurfaceTone from(ClientLevel level, BlockPos pos, VortexKind kind) {
            if (kind == VortexKind.WATERSPOUT) {
                return new SurfaceTone(0.62F, 0.70F, 0.82F, 0.44F, 0.50F, 0.60F, false);
            }
            if (kind == VortexKind.LANDSPOUT) {
                return new SurfaceTone(0.56F, 0.55F, 0.52F, 0.34F, 0.31F, 0.27F, false);
            }
            if (kind == VortexKind.GUSTNADO) {
                return new SurfaceTone(0.43F, 0.41F, 0.36F, 0.17F, 0.18F, 0.18F, true);
            }
            BlockState state = level.getBlockState(pos);
            if (isSandy(state)) {
                return new SurfaceTone(0.72F, 0.61F, 0.42F, 0.42F, 0.32F, 0.20F, true);
            }
            if (isDirtLike(state)) {
                return new SurfaceTone(0.52F, 0.42F, 0.28F, 0.26F, 0.20F, 0.14F, true);
            }
            return new SurfaceTone(0.60F, 0.50F, 0.34F, 0.32F, 0.25F, 0.17F, true);
        }

        private static boolean isSandy(BlockState state) {
            return state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE);
        }

        private static boolean isDirtLike(BlockState state) {
            return state.is(Blocks.DIRT)
                    || state.is(Blocks.COARSE_DIRT)
                    || state.is(Blocks.ROOTED_DIRT)
                    || state.is(Blocks.GRASS_BLOCK)
                    || state.is(Blocks.PODZOL)
                    || state.is(Blocks.MUD)
                    || state.is(Blocks.MYCELIUM);
        }
    }

    private static final class VisualLayer {
        private Vec3 pos;
        private float rotation;
        private final List<PivotingParticle> clouds = new ArrayList<>();
        private final List<PivotingParticle> core = new ArrayList<>();

        private VisualLayer(Vec3 pos) {
            this.pos = pos;
        }

        private void remove() {
            for (PivotingParticle particle : clouds) {
                particle.remove();
            }
            for (PivotingParticle particle : core) {
                particle.remove();
            }
            clouds.clear();
            core.clear();
        }
    }
}
