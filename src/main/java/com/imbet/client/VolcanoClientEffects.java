package com.imbet.client;

import com.imbet.Config;
import com.imbet.network.VolcanoSyncPayload;
import com.imbet.volcano.ClientVolcanoStore;
import com.imbet.volcano.VolcanoModel;
import com.imbet.volcano.VolcanoPhase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * What a volcano does to the frame a player is looking at.
 *
 * <p>All of it reads the server's state and none of it decides anything: the server says a column is standing
 * and how high, and this draws it, hazes the distance with it, shakes the camera under it and rains its ash on
 * whoever is standing in the fallout. Keeping the client to that side of the line is why the plume matches the
 * ash the server is actually laying down - both read the same snapshot.
 *
 * <p>The same three-way split the rest of the mod uses applies here. The plume is a volume of billboards
 * within render distance; the haze is a fog contribution that reaches much further; and the fallout is a
 * handful of vanilla particles around the player, because the player is the only place ash has to be seen
 * falling.
 */
public final class VolcanoClientEffects {
    /** How far the shaking and the local fallout are felt, in blocks. */
    private static final double FEEL_RANGE = 900.0D;
    /** How far the fallout particles are spawned around the player, in blocks. */
    private static final double FALLOUT_RADIUS = 34.0D;
    /** How far from the vent the crater's own particles are spawned, in blocks. */
    private static final double VENT_PARTICLE_RANGE = 288.0D;

    private static float shake;
    private static float haze;
    private static double shakePhase;
    private static VolcanoSyncPayload.VolcanoSnapshot active;

    private VolcanoClientEffects() {
    }

    /** The volcano the client is currently inside the influence of, or null. */
    public static VolcanoSyncPayload.VolcanoSnapshot active() {
        return active;
    }

    /** How thick the ash in the air is where the player is, 0 to 1. */
    public static float haze() {
        return haze;
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Vec3 eye = minecraft.player == null ? null : minecraft.player.position();
        if (level == null || eye == null) {
            active = null;
            shake = 0.0F;
            haze = 0.0F;
            return;
        }
        VolcanoSyncPayload.VolcanoSnapshot nearest = ClientVolcanoStore.nearest(eye.x, eye.z, FEEL_RANGE);
        active = nearest;
        VolcanoPhase phase = ClientVolcanoStore.phaseOf(nearest);
        double distance = nearest == null ? Double.MAX_VALUE
                : Math.hypot(eye.x - (nearest.x() + 0.5D), eye.z - (nearest.z() + 0.5D));

        // The shake is the volcano's own tremor: strongest during the phases that are actually exploding, and
        // falling off with distance by the same law the server uses to shake blocks loose.
        float targetShake = 0.0F;
        if (nearest != null && Config.VOLCANO_ENABLED.get() && Config.VOLCANO_SHAKE.get() && phase.erupting()) {
            double intensity = nearest.intensity();
            targetShake = (float) VolcanoModel.quakeShake(intensity, distance)
                    * (phase == VolcanoPhase.COLLAPSE ? 1.0F : phase.explosive() ? 0.8F : 0.45F);
        }
        shake += (targetShake - shake) * 0.05F;

        // The haze is ash in the air, and its thickness is the eruption's own ash density read through
        // distance. A column 12 km up carries enough to wash out a horizon from tens of kilometres away.
        float targetHaze = 0.0F;
        if (nearest != null && Config.VOLCANO_ENABLED.get() && Config.VOLCANO_ASH_HAZE.get() && phase.ashFall()) {
            double reach = Math.max(256.0D, Config.VOLCANO_ADVISORY_RANGE_BLOCKS.get() * 0.5D);
            double falloff = Mth.clamp(1.0D - distance / reach, 0.0D, 1.0D);
            targetHaze = (float) (falloff * falloff * VolcanoModel.ashHazardFactor(nearest.ashMm()))
                    * Config.VOLCANO_ASH_HAZE_STRENGTH.get().floatValue();
        }
        haze += (targetHaze - haze) * 0.06F;

        shakePhase += 0.017F + shake * 0.05F;
        if (minecraft.isPaused()) {
            return;
        }
        spawnFallout(level, eye, phase, distance);
        spawnVentParticles(level, nearest, phase, eye, distance);
    }

    /**
     * The ash falling where the player is standing.
     *
     * <p>Spawning this around the player rather than at the vent is what makes it possible at all: a column a
     * hundred kilometres downwind is still laying ash here, and the only place it has to be seen falling is in
     * front of the camera. Vanilla's own ash particle is the right mote for it, and where the fall is heavy
     * enough a little of it settles white - coarse pumice reads pale next to fine grey dust.
     */
    private static void spawnFallout(ClientLevel level, Vec3 eye, VolcanoPhase phase, double distance) {
        if (active == null || !Config.VOLCANO_ENABLED.get() || !phase.ashFall()) {
            return;
        }
        float intensity = (float) Mth.clamp(VolcanoModel.ashHazardFactor(active.ashMm()) * 4.0D, 0.0D, 1.0D);
        if (intensity <= 0.02F) {
            return;
        }
        RandomSource random = level.random;
        int count = Mth.ceil(intensity * 14.0F);
        for (int index = 0; index < count; index++) {
            if (random.nextFloat() > intensity) {
                continue;
            }
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double radius = FALLOUT_RADIUS * Math.sqrt(random.nextDouble());
            double x = eye.x + Math.cos(angle) * radius;
            double z = eye.z + Math.sin(angle) * radius;
            int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, Mth.floor(x), Mth.floor(z));
            double y = surface + 1.0D + random.nextDouble() * 14.0D;
            level.addParticle(random.nextFloat() < 0.22F ? ParticleTypes.WHITE_ASH : ParticleTypes.ASH,
                    x, y, z, 0.0D, -0.02D - random.nextDouble() * 0.02D, 0.0D);
        }
    }

    /**
     * The vent itself, when the player is close enough to watch it.
     *
     * <p>Incandescent lava at the crater and the drifting smoke above it are local effects for the same reason
     * the fallout is: the only viewer that matters is the one standing there.
     */
    private static void spawnVentParticles(ClientLevel level, VolcanoSyncPayload.VolcanoSnapshot nearest,
                                           VolcanoPhase phase, Vec3 eye, double distance) {
        if (nearest == null || !Config.VOLCANO_ENABLED.get() || distance > VENT_PARTICLE_RANGE) {
            return;
        }
        RandomSource random = level.random;
        BlockPos vent = new BlockPos(nearest.x(), nearest.ventY(), nearest.z());
        double closeness = Mth.clamp(1.0D - distance / VENT_PARTICLE_RANGE, 0.0D, 1.0D);
        if (phase.lava()) {
            int pops = Mth.ceil(closeness * 4.0F);
            for (int index = 0; index < pops; index++) {
                double x = vent.getX() + 0.5D + (random.nextDouble() - 0.5D) * nearest.craterRadius() * 1.6D;
                double z = vent.getZ() + 0.5D + (random.nextDouble() - 0.5D) * nearest.craterRadius() * 1.6D;
                double y = nearest.ventY() + nearest.craterRadius() * 0.2D;
                level.addParticle(ParticleTypes.LAVA, x, y, z, 0.0D, 0.4D + random.nextDouble(), 0.0D);
            }
        }
        if (phase.explosive()) {
            int puffs = Mth.ceil(closeness * 3.0F);
            for (int index = 0; index < puffs; index++) {
                double x = vent.getX() + 0.5D + (random.nextDouble() - 0.5D) * nearest.craterRadius() * 2.0D;
                double z = vent.getZ() + 0.5D + (random.nextDouble() - 0.5D) * nearest.craterRadius() * 2.0D;
                double y = nearest.ventY() + 1.0D + random.nextDouble() * 6.0D;
                level.addParticle(ParticleTypes.LARGE_SMOKE, x, y, z,
                        nearest.windX() * 0.12D, 0.35D + random.nextDouble() * 0.2D, nearest.windZ() * 0.12D);
            }
        }
    }

    /**
     * The tremor, as the camera feels it.
     *
     * <p>Two slow sinusoids an irrational ratio apart, so the motion never repeats visibly, applied as small
     * offsets to pitch, yaw and roll. It is deliberately a wobble rather than a jolt: a volcano shakes the
     * ground for minutes at a time, and that is what a long-period sway is. The tornado's own tumble adds to
     * this separately - both are offsets on the same event, and neither writes the other's.
     */
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (shake <= 0.001F || !Config.VOLCANO_SHAKE.get()) {
            return;
        }
        float amount = Math.min(shake, 1.0F);
        float phase = (float) shakePhase;
        float pitch = (float) Math.sin(phase * 1.0D) * 0.55F * amount;
        float yaw = (float) Math.sin(phase * 1.618D + 1.3D) * 0.55F * amount;
        float roll = (float) Math.sin(phase * 0.707D + 2.1D) * 0.85F * amount;
        event.setPitch(event.getPitch() + pitch);
        event.setYaw(event.getYaw() + yaw);
        event.setRoll(event.getRoll() + roll);
    }

    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        if (haze <= 0.002F) {
            return;
        }
        // Ash is a warm grey that goes brown where it is thick, and it is a tint on vanilla's own fog colour
        // rather than a replacement, so the time of day and the biome still show through it.
        float blend = Mth.clamp(haze * 0.75F, 0.0F, 0.8F);
        float red = 0.52F;
        float green = 0.47F;
        float blue = 0.43F;
        event.setRed(Mth.lerp(blend, event.getRed(), red));
        event.setGreen(Mth.lerp(blend, event.getGreen(), green));
        event.setBlue(Mth.lerp(blend, event.getBlue(), blue));
    }

    /**
     * The haze's own far plane, composed the same way the mist's is: only ever nearer, and by taking the
     * minimum, so whichever of the fog listeners runs first the result is the same.
     */
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (haze <= 0.002F || event.getType() != FogType.NONE) {
            return;
        }
        float farPlane = Mth.lerp(Mth.clamp(haze, 0.0F, 1.0F), 320.0F, 70.0F);
        event.setFarPlaneDistance(Math.min(event.getFarPlaneDistance(), farPlane));
    }
}
