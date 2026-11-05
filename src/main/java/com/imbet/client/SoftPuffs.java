package com.imbet.client;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.mixin.ParticleEngineAccessor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import extendedrenderer.ParticleManagerExtended;
import extendedrenderer.particle.entity.EntityRotFX;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import weather2.ClientTickHandler;
import weather2.config.ConfigParticle;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;

/**
 * What makes Weather2's puffs read as cloud: the same particles, drawn soft.
 *
 * <p>Weather2 draws its puffs through vanilla's translucent particle path, which is built for sprites a block across. At
 * thirty blocks across it shows. Depth writes are on, so wherever two big see-through quads pass through each other one
 * cuts the other off dead along a straight line, and that is what made a storm look like a heap of glass shards. They
 * are never sorted either, so that cut-off is a different shape from every angle. This turns the writes off, keeps
 * the queue in back-to-front order so the blend is right, and swaps in {@code soft_puff}, which feathers a puff into
 * the terrain it touches and fades one the eye is about to pass through.
 *
 * <p>Under an Iris pack the program is the pack's to choose, so only the depth writes and the sort apply there. Neither
 * of those depends on what is shading the quad.
 */
public final class SoftPuffs {
    private static ShaderInstance shader;
    private static TextureTarget depthCopy;

    /** The queue is re-sorted this often, in ticks: a puff moves little in a tick, and sorting is not free. */
    private static final int SORT_INTERVAL_TICKS = 2;

    /**
     * How many puffs the loose effects may fill the queue to. The queue holds 16384 and silently throws away whatever is
     * at its head when it is full, and a funnel's puffs live for the whole life of the funnel: let drifting cloud fill
     * the queue and it is the tornado that goes missing. Above this the loose effects stop spawning until some have died.
     */
    private static final int LOOSE_PUFF_CEILING = 11000;
    private static boolean crowded;

    private SoftPuffs() {
    }

    public static void register(RegisterShadersEvent event) throws IOException {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "soft_puff");
        event.registerShader(new ShaderInstance(event.getResourceProvider(), id, DefaultVertexFormat.PARTICLE), loaded -> shader = loaded);
    }

    /** Called as the puffs' own render type begins, after the particle engine has set vanilla's program. */
    public static void begin() {
        if (!Config.STORM_SOFT_PUFFS.get()) {
            return;
        }
        RenderSystem.depthMask(false);
        if (shader == null || RenderingCompat.isShaderPackActive()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        shader.setSampler("DepthSampler", sceneDepth(minecraft));
        RenderSystem.setShader(() -> shader);
    }

    /**
     * The scene's depth, because a pass cannot read the buffer it is drawing into. The particle pass runs once a frame,
     * so this is one copy a frame, and whichever target the pass was drawing to is put back afterwards.
     */
    private static int sceneDepth(Minecraft minecraft) {
        RenderTarget main = minecraft.getMainRenderTarget();
        if (depthCopy == null || depthCopy.width != main.width || depthCopy.height != main.height) {
            if (depthCopy != null) {
                depthCopy.destroyBuffers();
            }
            depthCopy = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
            if (main.isStencilEnabled()) {
                depthCopy.enableStencil();
            }
        }
        depthCopy.copyDepthFrom(main);
        RenderTarget particles = minecraft.levelRenderer.getParticlesTarget();
        (particles != null ? particles : main).bindWrite(false);
        return depthCopy.getDepthTextureId();
    }

    /** Whether the queue is too full for anything optional to add to it. */
    public static boolean crowded() {
        return crowded;
    }

    /** Keeps the puffs in back-to-front order, which is what a blend with no depth writes depends on. */
    public static void tick(Minecraft minecraft) {
        if (minecraft.level == null) {
            crowded = false;
            return;
        }
        Queue<Particle> queue = queue(minecraft);
        crowded = queue != null && queue.size() > LOOSE_PUFF_CEILING;
        if (!Config.STORM_SOFT_PUFFS.get() || minecraft.level.getGameTime() % SORT_INTERVAL_TICKS != 0L || queue == null || queue.size() < 2) {
            return;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 eye = camera.getPosition();
        List<Particle> puffs = new ArrayList<>(queue);
        int count = puffs.size();
        long[] keys = new long[count];
        for (int i = 0; i < count; i++) {
            Particle puff = puffs.get(i);
            double distance = distanceSqr(puff, eye);
            // Farthest first, with the index in the low bits so the keys sort as plain longs: no boxing and no
            // comparator over what can be several thousand puffs. Quarter-block steps out to about a thousand blocks.
            long depth = Math.max(0L, 0xFFFFFFFL - (long) (Math.sqrt(distance) * 4.0D));
            keys[i] = depth << 20 | i;
        }
        Arrays.sort(keys);
        queue.clear();
        for (long key : keys) {
            queue.add(puffs.get((int) (key & 0xFFFFFL)));
        }
    }

    /** Weather2 runs a particle engine of its own, and only falls back on vanilla's when told to in its config. */
    private static Queue<Particle> queue(Minecraft minecraft) {
        if (ConfigParticle.Particle_engine_weather2) {
            ParticleManagerExtended engine = ClientTickHandler.particleManagerExtended();
            return engine == null ? null : engine.particles.get(EntityRotFX.SORTED_TRANSLUCENT);
        }
        return ((ParticleEngineAccessor) minecraft.particleEngine).expandedweather2dynamics$particles().get(EntityRotFX.SORTED_TRANSLUCENT);
    }

    private static double distanceSqr(Particle puff, Vec3 eye) {
        var box = puff.getBoundingBox();
        double x = (box.minX + box.maxX) * 0.5D - eye.x;
        double y = (box.minY + box.maxY) * 0.5D - eye.y;
        double z = (box.minZ + box.maxZ) * 0.5D - eye.z;
        return x * x + y * y + z * z;
    }
}
