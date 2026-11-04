package com.imbet.client;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.network.VolcanoSyncPayload;
import com.imbet.volcano.ClientVolcanoStore;
import com.imbet.volcano.VolcanoPhase;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * The eruption, drawn from anywhere.
 *
 * <p>A volcano's column is a stratospheric object: a VEI 4 stands twelve kilometres up and spreads wider than
 * it is tall, and it is the one part of this system that has to be visible from outside the hazard radius,
 * because the plume <em>is</em> the warning. So it is drawn as a volume of large, soft, camera-facing puffs
 * whose geometry comes from the eruption's own physics - the stem's width, the umbrella's spread, and the tilt
 * a sheared column leans with - rather than from a fixed model.
 *
 * <p>Because the puffs are enormous (hundreds of blocks at the sizes real columns reach) very few of them are
 * needed, and the far field is left to the haze. That is also why there is no particle system here: a particle
 * cloud of that size is a frame rate, and this is a few hundred quads.
 */
public final class VolcanoPlumeRenderer {
    /** The most puffs drawn in a frame, across every volcano. */
    private static final int MAX_PUFFS = 420;
    /** How many volcanoes are given a plume; the nearest ones are the only ones that matter. */
    private static final int MAX_VOLCANOES = 3;
    /** Nearer than this a puff is faded out, so standing inside the column is not a whiteout. */
    private static final double NEAR_FADE_START = 40.0D;
    private static final double NEAR_FADE_WIDTH = 120.0D;

    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            ExpandedWeather2Dynamics.MODID, "textures/environment/mist_puff.png");

    /** Ash is a soft-edged puff with no depth write, exactly like the mist's, but drawn with its own batch. */
    private static final RenderType PLUME = RenderType.create(
            ExpandedWeather2Dynamics.MODID + "_volcano_plume",
            DefaultVertexFormat.NEW_ENTITY,
            Mode.QUADS,
            1536,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(TEXTURE, false, false))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setOverlayState(RenderStateShard.OVERLAY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .createCompositeState(false));

    private VolcanoPlumeRenderer() {
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || !Config.VOLCANO_ENABLED.get()) {
            return;
        }
        // Iris runs every level stage again for its shadow map; this belongs to the visible frame only.
        if (RenderingCompat.isRenderingShadowPass()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null || ClientVolcanoStore.active().isEmpty()) {
            return;
        }
        double renderRange = Config.VOLCANO_RENDER_RANGE_BLOCKS.get();
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        // Nearest first, so a frame with more volcanoes than the cap draws the ones being looked at.
        java.util.List<VolcanoSyncPayload.VolcanoSnapshot> visible = ClientVolcanoStore.active().stream()
                .filter(snapshot -> Math.hypot(snapshot.x() + 0.5D - cam.x, snapshot.z() + 0.5D - cam.z) <= renderRange)
                .sorted(java.util.Comparator.comparingDouble(snapshot ->
                        Math.hypot(snapshot.x() + 0.5D - cam.x, snapshot.z() + 0.5D - cam.z)))
                .limit(MAX_VOLCANOES)
                .toList();
        if (visible.isEmpty()) {
            return;
        }
        VertexConsumer consumer = minecraft.renderBuffers().bufferSource().getBuffer(PLUME);
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);

        // The screen's own axes, in world terms, so a puff can be built facing the camera rather than in a
        // fixed plane. This is the same basis the volumetric rain's slices are cut in, and it has to be built
        // from the camera rather than taken from the pose: the level-stage pose is a rotation with no basis
        // exposed, and a quad that faced one fixed axis would be a flat polygon seen from anywhere but that
        // axis - edge on at ninety degrees to it, and skewed everywhere between.
        Vec3 view = Vec3.directionFromRotation(camera.getXRot(), camera.getYRot());
        Vec3 reference = Math.abs(view.y) > 0.999D ? new Vec3(0.0D, 0.0D, 1.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 right = view.cross(reference).normalize();
        Vec3 up = right.cross(view).normalize();

        int drawn = 0;
        for (VolcanoSyncPayload.VolcanoSnapshot snapshot : visible) {
            drawn = drawVolcano(level, consumer, event.getPoseStack().last(), cam, right, up, snapshot,
                    level.getGameTime() + partialTick, drawn);
        }
        minecraft.renderBuffers().bufferSource().endBatch(PLUME);
    }

    /** One volcano: its column, its crater's glow, and the ground cloud of a collapse. */
    private static int drawVolcano(ClientLevel level, VertexConsumer consumer, PoseStack.Pose pose, Vec3 cam,
                                   Vec3 right, Vec3 up,
                                   VolcanoSyncPayload.VolcanoSnapshot snapshot, double time, int drawn) {
        VolcanoPhase phase = ClientVolcanoStore.phaseOf(snapshot);
        double plumeBlocks = snapshot.plumeMetres();
        double craterRadius = Math.max(2.0D, snapshot.craterRadius());
        int ventX = snapshot.x();
        int ventZ = snapshot.z();
        int ventY = snapshot.ventY();

        // The column is taller than the mountain and leans downwind as it rises, which is what a real sheared
        // plume does: the wind at six kilometres is not the wind at the vent.
        if (plumeBlocks > 24.0D) {
            int count = (int) Mth.clamp(30.0D + plumeBlocks / 45.0D, 30.0D, 190.0D);
            double umbrellaRadius = plumeBlocks * 0.22D;
            double puffSize = plumeBlocks * 0.05D;
            double lean = plumeBlocks * 0.34D;
            for (int index = 0; index < count && drawn < MAX_PUFFS; index++) {
                double unit = (index + 0.5D) / count;
                // Height rises with a slight compression near the top, so the column stands rather than stops.
                double height = Math.pow(unit, 0.92D) * plumeBlocks;
                double radius = columnRadius(unit, craterRadius, umbrellaRadius);
                double angle = swirl(index, unit, time) * Math.PI * 2.0D;
                double spread = radius * Math.sqrt(hash(index, 17));
                // One power per puff: both axes of the lean are pushed out by the same profile height.
                double leanDepth = Math.pow(unit, 1.5D);
                double x = ventX + 0.5D + Math.cos(angle) * spread + snapshot.windX() * lean * leanDepth;
                double z = ventZ + 0.5D + Math.sin(angle) * spread + snapshot.windZ() * lean * leanDepth;
                double y = ventY + height + (hash(index, 31) - 0.5D) * puffSize * 0.6D;
                // The umbrella is the column's own top, and it is where a real plume stops rising and starts
                // spreading; above it the puffs are the same size but no higher.
                if (unit > 0.86D) {
                    y = ventY + plumeBlocks * (0.86D + 0.14D * Math.pow((unit - 0.86D) / 0.14D, 0.6D));
                }
                double size = puffSize * (0.85D + 0.5D * hash(index, 53));
                float alpha = columnAlpha(unit, phase);
                float[] colour = columnColour(unit, phase);
                if (puff(level, consumer, pose, cam, right, up, x, y, z, size, colour, alpha)) {
                    drawn++;
                }
            }
        }

        // The crater's own glow while there is lava in it: small, bright, and unlit, because it is emitting.
        if (snapshot.lava()) {
            double glow = Math.max(3.0D, craterRadius * 2.2D);
            for (int index = 0; index < 16 && drawn < MAX_PUFFS; index++) {
                double angle = (index / 16.0D) * Math.PI * 2.0D + time * 0.05D;
                double spread = craterRadius * 0.9D * Math.sqrt(hash(index, 211));
                double x = ventX + 0.5D + Math.cos(angle) * spread;
                double z = ventZ + 0.5D + Math.sin(angle) * spread;
                double y = ventY + craterRadius * 0.35D + hash(index, 223) * craterRadius;
                if (puffUnlit(consumer, pose, cam, right, up, x, y, z, glow, new float[] {1.0F, 0.55F, 0.16F},
                        (float) (0.30D * (0.6D + 0.4D * hash(index, 227))))) {
                    drawn++;
                }
            }
        }

        // A collapsing column puts its dense base on the ground and drives it outwards, which is the part of
        // a collapse a player is supposed to see coming.
        if (phase.pyroclastic() && snapshot.surgeReach() > 8.0D) {
            int count = 90;
            double reach = snapshot.surgeReach();
            for (int index = 0; index < count && drawn < MAX_PUFFS; index++) {
                double unit = (index + 0.5D) / count;
                // Densest just off the mountain and thinning at the front, like the current itself.
                double distance = reach * Math.sqrt(unit) * (0.55D + 0.45D * hash(index, 307));
                double angle = hash(index, 311) * Math.PI * 2.0D;
                double x = ventX + 0.5D + Math.cos(angle) * distance + snapshot.windX() * distance * 0.25D;
                double z = ventZ + 0.5D + Math.sin(angle) * distance + snapshot.windZ() * distance * 0.25D;
                double y = ventY + 2.0D + hash(index, 331) * Math.max(8.0D, reach * 0.12D);
                double size = Math.max(24.0D, reach * 0.10D) * (0.7D + 0.6D * hash(index, 337));
                float fade = (float) Mth.clamp(1.0D - distance / Math.max(1.0D, reach), 0.0D, 1.0D);
                if (puff(level, consumer, pose, cam, right, up, x, y, z, size, new float[] {0.30F, 0.26F, 0.23F},
                        (float) (0.22D * (0.35D + 0.65D * fade)))) {
                    drawn++;
                }
            }
        }
        return drawn;
    }

    /**
     * The column's own profile: a narrow stem, an umbrella that opens out at the neutral buoyancy level, and
     * the slight belly where the two meet.
     */
    private static double columnRadius(double unit, double craterRadius, double umbrellaRadius) {
        double stem = craterRadius * (0.8D + 1.6D * Math.min(unit, 0.55D));
        double opening = smoothstep(0.40D, 0.80D, unit);
        return stem + umbrellaRadius * opening;
    }

    private static float columnAlpha(double unit, VolcanoPhase phase) {
        double base = switch (phase) {
            case PHREATIC -> 0.075D;
            case MAGMATIC -> 0.085D;
            case COLLAPSE -> 0.075D;
            case FLOW -> 0.045D;
            case WANING -> 0.035D;
            case UNREST -> 0.030D;
            case DORMANT -> 0.0D;
        };
        // The stem is dense and the umbrella thin, which is what a column looks like: the top has spread out
        // and is mostly air.
        return (float) (base * (1.15D - 0.45D * unit));
    }

    /**
     * The column's colour: steam is white, ash is grey and goes pale at the top where the sun is on it, and a
     * collapsing column is dark all the way through because what is coming down is the dense part of it.
     */
    private static float[] columnColour(double unit, VolcanoPhase phase) {
        if (phase == VolcanoPhase.PHREATIC || phase == VolcanoPhase.WANING || phase == VolcanoPhase.UNREST) {
            return new float[] {0.86F, 0.86F, 0.88F};
        }
        if (phase == VolcanoPhase.FLOW) {
            return new float[] {(float) Mth.lerp(unit, 0.55D, 0.80D), (float) Mth.lerp(unit, 0.52D, 0.78D),
                    (float) Mth.lerp(unit, 0.50D, 0.76D)};
        }
        double shade = phase == VolcanoPhase.COLLAPSE ? 0.30D : 0.40D;
        double lit = phase == VolcanoPhase.COLLAPSE ? 0.52D : 0.72D;
        float value = (float) Mth.lerp(unit, shade, lit);
        return new float[] {value, value * 0.97F, value * 0.94F};
    }

    /** One lit puff: a camera-facing quad at a world position, faded out when the camera is inside it. */
    private static boolean puff(ClientLevel level, VertexConsumer consumer, PoseStack.Pose pose, Vec3 cam,
                                Vec3 right, Vec3 up,
                                double x, double y, double z, double size, float[] colour, float alpha) {
        double dx = x - cam.x;
        double dz = z - cam.z;
        double distance = Math.hypot(dx, dz);
        float nearFade = (float) Mth.clamp((distance - NEAR_FADE_START) / NEAR_FADE_WIDTH, 0.0D, 1.0D);
        float finalAlpha = alpha * nearFade;
        if (finalAlpha <= 0.004F) {
            return false;
        }
        BlockPos sample = new BlockPos(Mth.floor(x), ventSampleY(level, x, z), Mth.floor(z));
        int light = level.isLoaded(sample) ? LevelRenderer.getLightColor(level, sample)
                : net.minecraft.client.renderer.LightTexture.FULL_BRIGHT;
        return quad(consumer, pose, cam, right, up, x, y, z, size, colour, finalAlpha, light,
                (float) (dx / Math.max(1.0E-4D, distance)), (float) (dz / Math.max(1.0E-4D, distance)));
    }

    /** An emitting puff: the crater's glow, which is its own light and does not take the world's. */
    private static boolean puffUnlit(VertexConsumer consumer, PoseStack.Pose pose, Vec3 cam, Vec3 right, Vec3 up,
                                     double x, double y, double z, double size, float[] colour, float alpha) {
        if (alpha <= 0.004F) {
            return false;
        }
        double dx = x - cam.x;
        double dz = z - cam.z;
        double distance = Math.max(1.0E-4D, Math.hypot(dx, dz));
        return quad(consumer, pose, cam, right, up, x, y, z, size, colour, alpha,
                net.minecraft.client.renderer.LightTexture.FULL_BRIGHT,
                (float) (dx / distance), (float) (dz / distance));
    }

    /**
     * One puff, as a square facing the camera.
     *
     * <p>Screen-aligned rather than the mist's cylinder billboard, and for the mirror of the reason the mist
     * gives. Mist is looked down at and has to stay upright; a column is looked <em>up</em> at, and a puff two
     * kilometres overhead standing upright about the vertical axis would be edge-on to the one view there is
     * of it. Facing the screen is the only orientation that is never edge-on from anywhere.
     *
     * <p>The corners are offset along the camera's own right and up vectors, which is what makes that true:
     * offsetting along world X and Y, as this did, builds a square in a fixed vertical plane, so it reads as
     * a flat polygon from every direction but one and as a line from that one. The normal stays the horizontal
     * direction to the puff, since a plume's own shading is the lightmap's.
     */
    private static boolean quad(VertexConsumer consumer, PoseStack.Pose pose, Vec3 cam, Vec3 right, Vec3 up,
                                double x, double y, double z, double size, float[] colour, float alpha,
                                int light, float normalX, float normalZ) {
        float relX = (float) (x - cam.x);
        float relY = (float) (y - cam.y);
        float relZ = (float) (z - cam.z);
        float half = (float) (size * 0.5D);
        float acrossX = (float) right.x * half;
        float acrossY = (float) right.y * half;
        float acrossZ = (float) right.z * half;
        float upwardX = (float) up.x * half;
        float upwardY = (float) up.y * half;
        float upwardZ = (float) up.z * half;
        vertex(consumer, pose, relX - acrossX - upwardX, relY - acrossY - upwardY, relZ - acrossZ - upwardZ,
                0.0F, 1.0F, colour, alpha, light, normalX, normalZ);
        vertex(consumer, pose, relX + acrossX - upwardX, relY + acrossY - upwardY, relZ + acrossZ - upwardZ,
                1.0F, 1.0F, colour, alpha, light, normalX, normalZ);
        vertex(consumer, pose, relX + acrossX + upwardX, relY + acrossY + upwardY, relZ + acrossZ + upwardZ,
                1.0F, 0.0F, colour, alpha, light, normalX, normalZ);
        vertex(consumer, pose, relX - acrossX + upwardX, relY - acrossY + upwardY, relZ - acrossZ + upwardZ,
                0.0F, 0.0F, colour, alpha, light, normalX, normalZ);
        return true;
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z,
                               float u, float v, float[] colour, float alpha, int light,
                               float normalX, float normalZ) {
        consumer.addVertex(pose, x, y, z)
                .setColor(colour[0], colour[1], colour[2], Mth.clamp(alpha, 0.0F, 1.0F))
                .setUv(u, v)
                .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, normalX, 0.0F, normalZ);
    }

    /** Where to sample the light for a puff: the ground below it, because the sky has no light map. */
    private static int ventSampleY(ClientLevel level, double x, double z) {
        int blockX = Mth.floor(x);
        int blockZ = Mth.floor(z);
        return level.hasChunkAt(new BlockPos(blockX, level.getMinBuildHeight(), blockZ))
                ? level.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ) + 1
                : level.getMinBuildHeight();
    }

    /** Slow swirl around the column's axis, so a still column still looks like it is turning. */
    private static double swirl(int index, double unit, double time) {
        return hash(index, 71) + time * 0.012D * (1.0D - 0.5D * unit) + unit * 0.35D;
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        double t = Mth.clamp((value - edge0) / Math.max(1.0E-6D, edge1 - edge0), 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }

    /** A stable per-puff random in 0..1, so a plume does not reshuffle itself every frame. */
    private static double hash(int index, int salt) {
        int value = index * 0x9E3779B9 + salt * 0x85EBCA6B;
        value ^= value >>> 15;
        value *= 0x2545F491;
        value ^= value >>> 13;
        return (value >>> 8) / (double) (1 << 24);
    }
}
