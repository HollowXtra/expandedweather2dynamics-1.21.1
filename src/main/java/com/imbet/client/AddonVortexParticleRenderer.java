package com.imbet.client;

import com.imbet.Config;
import com.imbet.weather.BlockGrabSafety;
import com.imbet.weather.DustDevilEntity;
import com.imbet.weather.FunnelCloudEntity;
import com.imbet.weather.GustnadoEntity;
import com.imbet.weather.LandspoutEntity;
import com.imbet.weather.LavaWhirlEntity;
import com.imbet.weather.LavaWhirlVisual;
import com.imbet.weather.SnowDevilEntity;
import com.imbet.weather.SteamDevilEntity;
import com.imbet.weather.SteamDevilVisual;
import com.imbet.weather.SnowDevilVisual;
import com.imbet.weather.WaterspoutEntity;
import extendedrenderer.particle.ParticleRegistry;
import extendedrenderer.particle.entity.EntityRotFX;
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
    /** How many puffs a spout's parent cloud is built from, and the share of them that make its flat base. */
    private static final int PARENT_CLOUD_PUFFS = 36;
    private static final double PARENT_CLOUD_BASE_SHARE = 0.60D;

    private AddonVortexParticleRenderer() {
    }

    public static void tick(DustDevilEntity entity, float partialTick) {
        if (!Config.VISUALS_ENABLED.get() || !Config.EXTENDED_RENDERING_ENABLED.get() || entity == null || entity.isRemoved() || ParticleArt.cloud256() == null) {
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
        SNOW_DEVIL,
        GUSTNADO,
        LANDSPOUT,
        WATERSPOUT,
        FUNNEL_CLOUD,
        LAVA_WHIRL,
        STEAM_DEVIL;

        private static VortexKind forEntity(DustDevilEntity entity) {
            if (entity instanceof FunnelCloudEntity) {
                return FUNNEL_CLOUD;
            }
            // The volcanic pair before the dust devil fallback, and before the snow devil, because all four
            // of them are DustDevilEntity instances: without their own kinds they would all be drawn in the
            // colour of the earth, which for a whirl of fire is not a near miss.
            if (entity instanceof LavaWhirlEntity) {
                return LAVA_WHIRL;
            }
            if (entity instanceof SteamDevilEntity) {
                return STEAM_DEVIL;
            }
            // Before the dust devil check, because a snow devil is a DustDevilEntity too: without this it
            // fell through to the dust devil's own kind and was drawn in the colour of the earth.
            if (entity instanceof SnowDevilEntity) {
                return SNOW_DEVIL;
            }
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

        /**
         * Funnels share one set of behaviours: a smooth taper, slow spin damping, an upward alpha
         * ramp and the flattened funnel pitch. The funnel cloud is the only member whose base is
         * not on the ground, which is the entity's business, not the shape's.
         */
        private boolean funnelLike() {
            return this == LANDSPOUT || this == WATERSPOUT || this == FUNNEL_CLOUD || this == STEAM_DEVIL;
        }
    }

    private static final class VortexVisual {
        private final List<VisualLayer> layers = new ArrayList<>();
        private VortexKind lastKind;
        /** The cloud a spout hangs from. See tickParentCloud. */
        private final List<EntityRotFX> parentCloud = new ArrayList<>();
        private double parentCloudHeight;
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
                    PivotingParticle particle = createCloud(level, layer.pos, renderCull, surfaceTone, false, kind);
                    particle.spawnAsWeatherEffect();
                    layer.clouds.add(particle);
                }

                int targetCore = targetCore(kind, fraction, particleRate, surfaceTone);
                cleanup(layer.core, targetCore);
                while (layer.core.size() < targetCore) {
                    PivotingParticle particle = createCloud(level, layer.pos, renderCull, surfaceTone, true, kind);
                    particle.spawnAsWeatherEffect();
                    layer.core.add(particle);
                }

                layer.rotation += spinStepFor(kind, spinBase, (float) layerRadius, rotationSign);
                positionParticles(layer.clouds, layer, (float) layerRadius, fraction, kind, surfaceTone, false);
                positionParticles(layer.core, layer, (float) Math.max(0.35D, layerRadius * 0.48D), fraction, kind, surfaceTone, true);
            }
            if (kind == VortexKind.LANDSPOUT || kind == VortexKind.WATERSPOUT) {
                parentCloudHeight = height;
                tickParentCloud(level, height, 1.0F);
            }
        }

        private void tickParentCloudFade(float fade) {
            for (EntityRotFX puff : parentCloud) {
                float alpha = puff.getAlphaF() * Math.max(0.0F, Math.min(1.0F, fade / Math.max(0.05F, fade + 0.02F)));
                puff.setAlpha(alpha);
                puff.setFullAlphaTarget(alpha);
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
            float spin = lastKind == VortexKind.GUSTNADO ? 4.4F
                    : lastKind == VortexKind.DUST_DEVIL ? 3.1F
                    : lastKind == VortexKind.SNOW_DEVIL ? 2.6F
                    : lastKind == VortexKind.LAVA_WHIRL ? LavaWhirlVisual.FADE_SPIN_PER_TICK
                    : lastKind == VortexKind.STEAM_DEVIL ? SteamDevilVisual.FADE_SPIN_PER_TICK
                    : 2.2F;
            if (!parentCloud.isEmpty()) {
                // The cloud outlives the spout a little and thins away with it rather than vanishing.
                tickParentCloudFade(fade);
            }
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
                // One rotation vector for this particle rather than one per setter. A Vec3 is immutable, so a
                // particle holding the same one as its previous reading and its current one cannot be told
                // apart from one holding two equal copies of it - and it is a vector a frame not allocated.
                Vec3 rotNow = new Vec3(0.0D, rot, 0.0D);
                particle.setPivotRotPrev(previousRot == null ? rotNow : previousRot);
                particle.setPivotRot(rotNow);
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
                // Snow hangs in the air a little longer than dust does once the whirl is gone.
                case SNOW_DEVIL -> SnowDevilVisual.FADE_TICKS;
                case GUSTNADO -> 116L;
                // A funnel cloud rots away slowly: it unravels upward from the tip rather than
                // winking out, which is what a decaying cold-air funnel actually looks like.
                case FUNNEL_CLOUD -> 82L;
                case LANDSPOUT, WATERSPOUT -> 45L;
                case LAVA_WHIRL -> LavaWhirlVisual.FADE_TICKS;
                case STEAM_DEVIL -> SteamDevilVisual.FADE_TICKS;
            };
        }

        private static float spinBaseFor(VortexKind kind, double wind) {
            return switch (kind) {
                case WATERSPOUT -> (float) (42.0D + wind * 0.38D);
                case LANDSPOUT -> (float) (36.0D + wind * 0.30D);
                case FUNNEL_CLOUD -> (float) (18.0D + wind * 0.20D);
                case GUSTNADO -> (float) (22.0D + wind * 0.24D);
                case DUST_DEVIL -> (float) (24.0D + wind * 0.28D);
                // Slower than any other whirl: a snow devil is a shallow, lazy circulation.
                case SNOW_DEVIL -> SnowDevilVisual.spinBaseFor(wind);
                case LAVA_WHIRL -> LavaWhirlVisual.spinBaseFor(wind);
                case STEAM_DEVIL -> SteamDevilVisual.spinBaseFor(wind);
            };
        }

        private static float spinRadiusDamping(VortexKind kind, float radius) {
            return switch (kind) {
                case WATERSPOUT -> Math.max(1.15F, radius * 0.72F + 0.45F);
                case LANDSPOUT -> Math.max(1.05F, radius * 0.78F + 0.45F);
                case SNOW_DEVIL -> Math.max(0.75F, radius * SnowDevilVisual.SPIN_RADIUS_DAMPING + 0.35F);
                case LAVA_WHIRL -> Math.max(0.70F, radius * LavaWhirlVisual.SPIN_RADIUS_DAMPING + 0.35F);
                case STEAM_DEVIL -> Math.max(0.55F, radius * SteamDevilVisual.SPIN_RADIUS_DAMPING + 0.25F);
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
                case FUNNEL_CLOUD -> 5.2F;
                case GUSTNADO -> 5.8F;
                case DUST_DEVIL -> 7.0F;
                case SNOW_DEVIL -> SnowDevilVisual.MAX_SPIN_STEP;
                case LAVA_WHIRL -> LavaWhirlVisual.MAX_SPIN_STEP;
                case STEAM_DEVIL -> SteamDevilVisual.MAX_SPIN_STEP;
            };
        }

        private static Vec3 visualBase(ClientLevel level, DustDevilEntity entity, VortexKind kind, float partialTick) {
            double x = Mth.lerp(partialTick, entity.xo, entity.getX());
            double y = Mth.lerp(partialTick, entity.yo, entity.getY());
            double z = Mth.lerp(partialTick, entity.zo, entity.getZ());
            BlockPos ground = BlockGrabSafety.vortexGroundSurface(level, BlockPos.containing(x, y, z));
            double groundY = ground == null ? y : ground.getY();
            double offset = switch (kind) {
                // On the ground. This was a floor of five and a half blocks, which stood every landspout on air.
                case LANDSPOUT -> 0.45D;
                case WATERSPOUT -> 0.45D + waterspoutLiftOffset(entity);
                // Nothing at all: the vortex hangs at the entity's own altitude and the ground below
                // it is a spectator. This is the only kind that never touches the surface.
                case FUNNEL_CLOUD -> 0.0D;
                case GUSTNADO -> 0.30D;
                case DUST_DEVIL -> 0.22D;
                // Hugging the drift it is standing in, which is a little lower than a dust devil's base.
                case SNOW_DEVIL -> 0.18D;
                case LAVA_WHIRL -> LavaWhirlVisual.BASE_OFFSET_BLOCKS;
                case STEAM_DEVIL -> SteamDevilVisual.BASE_OFFSET_BLOCKS;
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
                // Tall: the tip hangs at the configured altitude and the column has to reach the
                // shower base it is condensing out of, or it reads as a floating blob instead.
                case FUNNEL_CLOUD -> Mth.clamp(58.0D + wind * 0.30D + radius * 1.1D, 62.0D, 130.0D);
                case GUSTNADO -> Mth.clamp(11.0D + wind * 0.17D + radius * 1.75D, 15.0D, 38.0D);
                case DUST_DEVIL -> Mth.clamp(24.0D + wind * 0.42D + radius * 2.0D, 34.0D, 86.0D);
                case SNOW_DEVIL -> SnowDevilVisual.heightFor(radius, wind);
                case LAVA_WHIRL -> LavaWhirlVisual.heightFor(radius, wind);
                case STEAM_DEVIL -> SteamDevilVisual.heightFor(radius, wind);
            };
        }

        private static int layerCountFor(VortexKind kind, double height) {
            return switch (kind) {
                case WATERSPOUT -> Mth.clamp((int) (height / 1.35D), 24, 48);
                case LANDSPOUT -> Mth.clamp((int) (height / 1.55D), 22, 54);
                case FUNNEL_CLOUD -> Mth.clamp((int) (height / 1.70D), 30, 72);
                case GUSTNADO -> Mth.clamp((int) (height / 1.15D), 14, 34);
                case DUST_DEVIL -> Mth.clamp((int) (height / 1.75D), 20, 48);
                case SNOW_DEVIL -> SnowDevilVisual.layerCountFor(height);
                case LAVA_WHIRL -> LavaWhirlVisual.layerCountFor(height);
                case STEAM_DEVIL -> SteamDevilVisual.layerCountFor(height);
            };
        }

        private static double radiusFor(VortexKind kind, double baseRadius, double wind, double fraction) {
            double eased = smoothstep(fraction);
            return switch (kind) {
                // A spout is a rope, not a cone: thin and nearly even all the way up, flaring like a trumpet only where it
                // meets the cloud, and a waterspout a little wider again at the water where its spray ring stands.
                case WATERSPOUT -> ropeRadius(fraction, Mth.clamp(baseRadius * 0.22D + wind * 0.004D, 0.7D, 2.2D),
                        Mth.clamp(baseRadius * 0.90D + wind * 0.010D, 3.0D, 8.0D), 1.2D);
                case LANDSPOUT -> ropeRadius(fraction, Mth.clamp(baseRadius * 0.25D + wind * 0.005D, 0.9D, 2.6D),
                        Mth.clamp(baseRadius * 1.20D + wind * 0.014D, 3.5D, 10.0D), 0.5D);
                // The tip is the thinnest part of any funnel, and a funnel cloud's tip is a tuft of
                // ragged condensation rather than a wall on the ground.
                case FUNNEL_CLOUD -> Mth.lerp(eased, Math.max(0.20D, baseRadius * 0.09D), Mth.clamp(baseRadius * 0.98D + wind * 0.013D, 2.6D, 9.4D));
                case GUSTNADO -> gustnadoRadius(baseRadius, wind, fraction);
                case DUST_DEVIL -> dustDevilRadius(baseRadius, wind, fraction);
                case SNOW_DEVIL -> SnowDevilVisual.radiusFor(baseRadius, wind, fraction);
                case LAVA_WHIRL -> LavaWhirlVisual.radiusFor(baseRadius, wind, fraction);
                case STEAM_DEVIL -> SteamDevilVisual.radiusFor(baseRadius, wind, fraction);
            };
        }

        /** A rope's width up its height: even through the middle with a slow ripple, a trumpet into the cloud, a little foot. */
        private static double ropeRadius(double fraction, double rope, double top, double foot) {
            double ripple = 1.0D + 0.12D * Math.sin(fraction * Math.PI * 3.0D);
            double flare = Math.pow(smoothstep((fraction - 0.78D) / 0.22D), 1.4D);
            double base = foot * (1.0D - smoothstep(fraction / 0.07D));
            return rope * ripple * (1.0D + base) + (top - rope) * flare;
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
            if (kind == VortexKind.WATERSPOUT || kind == VortexKind.LANDSPOUT) {
                // A spout is rarely straight: it bows, often in a gentle S, and leans back toward the cloud base as the
                // storm above drifts on. A straight cone standing bolt upright is the one shape the photographs never show.
                Vec3 forward = motion == null || motion.horizontalDistanceSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D)
                        : new Vec3(motion.x, 0.0D, motion.z).normalize();
                Vec3 cross = new Vec3(-forward.z, 0.0D, forward.x);
                double amplitude = Mth.clamp(height * 0.10D, 2.0D, 7.0D);
                double bow = Math.sin(fraction * Math.PI) * amplitude;
                double ess = Math.sin(fraction * Math.PI * 2.0D) * amplitude * 0.40D;
                double lean = Math.pow(fraction, 1.6D) * amplitude * 0.8D;
                return forward.scale(bow * 0.6D + lean).add(cross.scale(ess + bow * 0.4D));
            }
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
            if (kind == VortexKind.SNOW_DEVIL) {
                // Leans less than a dust devil: a shallow whirl has less of itself above the shear.
                return forward.scale(SnowDevilVisual.leanFor(height, fraction));
            }
            if (kind == VortexKind.LAVA_WHIRL) {
                return forward.scale(LavaWhirlVisual.leanFor(height, fraction));
            }
            if (kind == VortexKind.STEAM_DEVIL) {
                return forward.scale(SteamDevilVisual.leanFor(height, fraction));
            }
            if (kind == VortexKind.FUNNEL_CLOUD) {
                // The tip hangs almost still while the shower base it is rooted in streams away, so
                // the column leans further the higher it gets: the funnel leans back over its tip.
                double lean = Math.pow(fraction, 1.35D) * Mth.clamp(height * 0.055D, 0.6D, 4.5D);
                return forward.scale(lean);
            }
            return Vec3.ZERO;
        }

        private static int targetClouds(VortexKind kind, double radius, double fraction, float particleRate) {
            if (kind == VortexKind.LAVA_WHIRL) {
                return LavaWhirlVisual.targetRing(radius, fraction, particleRate);
            }
            if (kind == VortexKind.STEAM_DEVIL) {
                return SteamDevilVisual.targetRing(radius, fraction, particleRate);
            }
            if (kind == VortexKind.SNOW_DEVIL) {
                // Thinner than a dust devil of the same width, which is what makes it read as snow rather
                // than as the ground it is standing on.
                return SnowDevilVisual.targetRing(radius, fraction, particleRate);
            }
            double circumference = Math.max(1.0D, radius * Math.PI * 2.0D);
            double spacing = kind == VortexKind.WATERSPOUT ? 2.15D : kind == VortexKind.FUNNEL_CLOUD ? 3.10D : kind.funnelLike() ? 2.75D : 3.60D;
            int max = kind == VortexKind.WATERSPOUT ? 12 : kind == VortexKind.LANDSPOUT ? 14 : kind == VortexKind.FUNNEL_CLOUD ? 18 : kind == VortexKind.GUSTNADO ? 26 : kind == VortexKind.DUST_DEVIL ? 16 : 12;
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
            if (kind == VortexKind.SNOW_DEVIL) {
                // No dark heart: the middle of a snow devil is its brightest part, so it has no core sheet.
                return 0;
            }
            if (kind == VortexKind.STEAM_DEVIL) {
                // The same, and for a stronger reason than a snow devil's: there is nothing in a plume of
                // water vapour to darken a core with in the first place.
                return 0;
            }
            if (kind == VortexKind.FUNNEL_CLOUD) {
                // Condensation, not dust: the lower half is a thin ragged veil and only the upper
                // column carries any solid-looking core.
                return fraction < 0.55D ? 0 : Math.max(1, (int) (2.0F * particleRate));
            }
            if (kind == VortexKind.DUST_DEVIL && !tone.hasDarkCore) {
                return 0;
            }
            if (kind == VortexKind.DUST_DEVIL && fraction < 0.62D) {
                return 1;
            }
            if (kind == VortexKind.GUSTNADO && fraction < 0.78D) {
                return Math.max(1, (int) (1.5F * particleRate));
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
                // As in fadeParticles: the rotation and the pivot are one vector each, shared between the
                // previous and the current reading rather than built twice.
                Vec3 rotNow = new Vec3(0.0D, rot, 0.0D);
                particle.setPivotRotPrev(previousRot == null ? rotNow : previousRot);
                particle.setPivotRot(rotNow);
                Vec3 previousPivot = particle.getPivot();
                Vec3 pivotNow = new Vec3(0.0D, radius, 0.0D);
                particle.setPivotPrev(previousPivot == null ? pivotNow : previousPivot);
                particle.setPivot(pivotNow);

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
                } else if (kind == VortexKind.LANDSPOUT && fraction < 0.32D) {
                    // A landspout's foot is the dust it is lifting, tan and opaque, giving way to grey condensation above.
                    float dust = (float) (1.0D - smoothstep(fraction / 0.32D));
                    particle.setColor(Mth.lerp(dust, tone.red, 0.66F), Mth.lerp(dust, tone.green, 0.57F), Mth.lerp(dust, tone.blue, 0.46F));
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
                case FUNNEL_CLOUD -> (float) (fraction * 118.0D + Math.sin(fraction * Math.PI * 2.6D) * 9.0D);
                case GUSTNADO -> (float) (fraction * 96.0D + Math.sin(fraction * Math.PI * 2.5D) * 22.0D);
                case DUST_DEVIL -> (float) (fraction * 230.0D + Math.sin(fraction * Math.PI * 4.0D) * 14.0D);
                case SNOW_DEVIL -> (float) (fraction * 205.0D + Math.sin(fraction * Math.PI * 3.6D) * 11.0D);
                // A fire whirl's flame is twisted hard and almost evenly, and a steam plume is the smoothest
                // of the lot: vapour climbing a column does not corkscrew the way dust does.
                case LAVA_WHIRL -> (float) (fraction * 260.0D + Math.sin(fraction * Math.PI * 4.4D) * 16.0D);
                case STEAM_DEVIL -> (float) (fraction * 150.0D + Math.sin(fraction * Math.PI * 2.2D) * 6.0D);
            };
        }

        private static float continuousAngle(float previous, float target) {
            return previous + Mth.wrapDegrees(target - previous);
        }

        private static float scaleFor(VortexKind kind, double fraction, float radius, boolean core) {
            float base = switch (kind) {
                case WATERSPOUT -> 1.35F + (float) fraction * 2.55F + radius * 0.12F;
                case LANDSPOUT -> 1.8F + (float) fraction * 3.9F + radius * 0.16F;
                case FUNNEL_CLOUD -> 2.2F + (float) fraction * 4.4F + radius * 0.18F;
                case GUSTNADO -> 2.8F + radius * 0.46F + (float) (1.0D - fraction) * 1.8F + (float) fraction * 0.8F;
                case DUST_DEVIL -> 1.6F + radius * 0.42F + (float) (1.0D - fraction) * 1.3F + (float) fraction * 0.5F;
                case SNOW_DEVIL -> SnowDevilVisual.scaleFor(radius, fraction);
                case LAVA_WHIRL -> LavaWhirlVisual.scaleFor(radius, fraction);
                case STEAM_DEVIL -> SteamDevilVisual.scaleFor(radius, fraction);
            };
            float maxScale = kind == VortexKind.WATERSPOUT ? 4.8F
                    : kind == VortexKind.GUSTNADO ? 10.0F
                    : kind == VortexKind.DUST_DEVIL ? 6.0F
                    : kind == VortexKind.LAVA_WHIRL ? LavaWhirlVisual.MAX_SCALE
                    : kind == VortexKind.STEAM_DEVIL ? SteamDevilVisual.MAX_SCALE : 9.5F;
            return Mth.clamp(core ? base * 0.82F : base, 0.85F, maxScale);
        }

        private static float alphaFor(VortexKind kind, double fraction, boolean core) {
            float base = switch (kind) {
                case WATERSPOUT -> 0.56F;
                case LANDSPOUT -> 0.53F;
                // A condensation funnel is denser than a dust whirl: this one is made of cloud.
                case FUNNEL_CLOUD -> 0.62F;
                // Loose dust, and you should be able to see through it. A funnel is condensation; these are soil in the air.
                case GUSTNADO -> 0.36F;
                case DUST_DEVIL -> 0.38F;
                case SNOW_DEVIL -> SnowDevilVisual.alphaFor(fraction);
                case LAVA_WHIRL -> LavaWhirlVisual.alphaFor(fraction);
                case STEAM_DEVIL -> SteamDevilVisual.alphaFor(fraction);
            };
            if (kind.funnelLike()) {
                base *= (float) Mth.clamp(0.50D + fraction * 0.72D, 0.45D, 1.0D);
            } else if (kind == VortexKind.LAVA_WHIRL) {
                // Already densest at the base from its own model, so it is not scaled a second time here.
            } else if (kind == VortexKind.DUST_DEVIL) {
                base *= (float) Mth.clamp(1.10D - fraction * 0.46D, 0.52D, 1.0D);
            } else if (kind == VortexKind.GUSTNADO) {
                base *= (float) Mth.clamp(1.18D - fraction * 0.34D, 0.58D, 1.0D);
            }
            float maxAlpha = kind == VortexKind.GUSTNADO ? 0.94F
                    : kind == VortexKind.LAVA_WHIRL ? LavaWhirlVisual.MAX_ALPHA
                    : kind == VortexKind.STEAM_DEVIL ? SteamDevilVisual.MAX_ALPHA
                    : kind.funnelLike() ? 0.90F : 0.86F;
            // These vortices are built from the same cloud256 billboards and share the parent funnel's
            // sprite, so they take the same allowance for the sheet's denser regenerated body.
            if (kind.funnelLike()) {
                // A spout has no cloud of its own to end in, so its top dissolves into the air rather than stopping at a lid.
                base *= 1.0F - (float) smoothstep((fraction - 0.88D) / 0.12D);
            }
            float alpha = Mth.clamp(core ? base + 0.06F : base, kind.funnelLike() ? 0.0F : 0.16F, maxAlpha);
            return alpha * ClientWeatherEffects.FUNNEL_SPRITE_DENSITY_ALLOWANCE;
        }

        /**
         * One billboard of a vortex. The sprite is chosen by what the vortex is made of rather than by the art
         * switch: a funnel, a landspout, a waterspout and a steam devil are condensation or steam and draw the
         * dense funnel sheet, which is the only sheet opaque enough to make a column read as a column, while a
         * dust devil, a gustnado and a snow devil are loose ground material and draw the puff sheet their alphas
         * were tuned against. See {@link ParticleArt#funnelSprite()} for why the switch does not govern a funnel.
         */
        private static PivotingParticle createCloud(ClientLevel level, Vec3 pos, float renderCull, SurfaceTone tone, boolean core, VortexKind kind) {
            PivotingParticle particle = new PivotingParticle(level, pos.x, pos.y, pos.z, 0.0D, 0.0D, 0.0D,
                    kind.funnelLike() || kind == VortexKind.DUST_DEVIL || kind == VortexKind.GUSTNADO ? ParticleArt.funnelSprite() : ParticleArt.cloud256());
            particle.facePlayer = Config.STORM_SOFT_PUFFS.get();
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
            for (EntityRotFX puff : parentCloud) {
                puff.remove();
            }
            parentCloud.clear();
        }

        /**
         * The thunderstorm a spout hangs from. A landspout or a waterspout is the bottom of a growing shower cloud's
         * updraft, and drawn on its own it was a column standing in a clear sky. This is the cloud above it: a dark flat
         * base where the column meets it and a rounded tower over that, built from the same puffs as the storms, turning
         * slowly and moving with the spout.
         */
        private void tickParentCloud(ClientLevel level, double height, float fade) {
            if (base == null) {
                return;
            }
            while (parentCloud.size() < PARENT_CLOUD_PUFFS) {
                EntityRotFX puff = new EntityRotFX(level, base.x, base.y + height, base.z, 0.0D, 0.0D, 0.0D);
                puff.setSprite(ParticleArt.cloud256());
                puff.setMaxAge(25000);
                puff.setTicksFadeInMax(40.0F);
                puff.setGravity(0.0F);
                puff.setCanCollide(false);
                puff.facePlayer = true;
                puff.isTransparent = true;
                puff.renderOrder = 1;
                puff.spawnAsWeatherEffect();
                parentCloud.add(puff);
            }
            double baseY = base.y + height - 2.0D;
            double spread = Math.max(22.0D, height * 0.75D);
            long time = level.getGameTime();
            for (int i = 0; i < parentCloud.size(); i++) {
                EntityRotFX puff = parentCloud.get(i);
                double share = (i + 0.5D) / parentCloud.size();
                boolean tower = share > PARENT_CLOUD_BASE_SHARE;
                double angle = i * 2.39996D + time * 0.0015D;
                double up = tower ? (share - PARENT_CLOUD_BASE_SHARE) / (1.0D - PARENT_CLOUD_BASE_SHARE) : 0.0D;
                double radius = tower ? spread * 0.55D * (1.0D - up * 0.45D) * Math.sqrt((i * 0.618D) % 1.0D)
                        : spread * Math.sqrt(share / PARENT_CLOUD_BASE_SHARE);
                float scale = (float) (tower ? 12.0D + spread * 0.20D : 10.0D + spread * 0.18D);
                // Every puff stands on the same floor, which is what makes the base of a shower cloud flat.
                double y = Math.max(tower ? baseY + 8.0D + up * spread * 0.9D : baseY + (i % 3) * 2.5D, baseY + scale * 0.8D);
                double px = base.x + Math.cos(angle) * radius;
                double pz = base.z + Math.sin(angle) * radius;
                puff.setPrevPosX(puff.getPosX());
                puff.setPrevPosY(puff.getPosY());
                puff.setPrevPosZ(puff.getPosZ());
                puff.setPosX(px);
                puff.setPosY(y);
                puff.setPosZ(pz);
                puff.setScale(scale);
                // A shower cloud: a flat grey base, and over it a tower lit by the sun on the side it is on. Drawn dark all
                // through, it read as a cloud of smoke hanging over the spout.
                float[] shade = tower
                        ? SunShading.cloudColour(level, 0.95F, SunShading.lit(level, Math.cos(angle), 0.4D + up * 0.6D, Math.sin(angle)), 0.62F)
                        : SunShading.cloudColour(level, 0.62F, 0.5F, 1.0F);
                puff.setColor(shade[0], shade[1], shade[2]);
                float alpha = (tower ? 0.55F : 0.72F) * fade;
                puff.setAlpha(alpha);
                puff.setFullAlphaTarget(alpha);
            }
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
                // Condensation: a pale white-grey rope, not the blue of the water under it.
                return new SurfaceTone(0.84F, 0.86F, 0.89F, 0.66F, 0.69F, 0.74F, false);
            }
            if (kind == VortexKind.LANDSPOUT) {
                return new SurfaceTone(0.76F, 0.76F, 0.76F, 0.52F, 0.50F, 0.47F, false);
            }
            if (kind == VortexKind.FUNNEL_CLOUD) {
                // It is built of condensed water, not of whatever it is hanging over, so it keeps
                // its own pale grey instead of taking a tint from the block below it.
                return new SurfaceTone(0.80F, 0.82F, 0.86F, 0.63F, 0.65F, 0.71F, false);
            }
            if (kind == VortexKind.GUSTNADO) {
                return new SurfaceTone(0.64F, 0.62F, 0.56F, 0.40F, 0.40F, 0.38F, true);
            }
            if (kind == VortexKind.SNOW_DEVIL) {
                // It is made of what is lying on the ground, and what is lying there is snow: cold and
                // pale, and never the brown the dust devil's own fallback would have painted it.
                return new SurfaceTone(SnowDevilVisual.TONE_RED, SnowDevilVisual.TONE_GREEN, SnowDevilVisual.TONE_BLUE,
                        SnowDevilVisual.CORE_RED, SnowDevilVisual.CORE_GREEN, SnowDevilVisual.CORE_BLUE,
                        SnowDevilVisual.DARK_CORE);
            }
            if (kind == VortexKind.LAVA_WHIRL) {
                // It is drawn in its own light: a whirl of fire is not the colour of the rock under it, and
                // taking a tint from the block below would paint it as whatever it happens to be crossing.
                return new SurfaceTone(LavaWhirlVisual.TONE_RED, LavaWhirlVisual.TONE_GREEN, LavaWhirlVisual.TONE_BLUE,
                        LavaWhirlVisual.CORE_RED, LavaWhirlVisual.CORE_GREEN, LavaWhirlVisual.CORE_BLUE,
                        LavaWhirlVisual.DARK_CORE);
            }
            if (kind == VortexKind.STEAM_DEVIL) {
                // White, and its heart is white too: a plume of vapour has nothing darker inside it.
                return new SurfaceTone(SteamDevilVisual.TONE_RED, SteamDevilVisual.TONE_GREEN, SteamDevilVisual.TONE_BLUE,
                        SteamDevilVisual.TONE_RED, SteamDevilVisual.TONE_GREEN, SteamDevilVisual.TONE_BLUE,
                        SteamDevilVisual.DARK_CORE);
            }
            BlockState state = level.getBlockState(pos);
            if (isSandy(state)) {
                return new SurfaceTone(1.00F, 0.87F, 0.62F, 0.70F, 0.58F, 0.40F, true);
            }
            if (isDirtLike(state)) {
                return new SurfaceTone(0.72F, 0.62F, 0.48F, 0.48F, 0.40F, 0.30F, true);
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
