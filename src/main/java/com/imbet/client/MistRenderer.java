package com.imbet.client;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * The visible half of the mist: low, camera-facing wisps hanging in the first few blocks over the ground.
 *
 * <p>This is a volume of billboards rather than a screen effect, because the thing that makes ground mist
 * read is depth - you see individual patches of it a few blocks away, layered over the grass, with the world
 * still visible between them. A screen-space wash cannot do that and a fog plane hides everything equally.
 *
 * <p>How it is built, and why each part is the way it is:
 *
 * <ul>
 *   <li><b>A lattice around the camera</b>, one candidate per {@value #CELL_BLOCKS} blocks, each wisp placed
 *       at a hash-determined spot inside its cell. Placement is a pure function of the cell, so a wisp does
 *       not jump between frames; what changes with time is the drift and the sheet animation.</li>
 *   <li><b>Advection by the real wind.</b> The whole field is displaced by the same wind the foliage and the
 *       waves read, at true speed (one block is one metre), so mist drifts the way the air moves and slides
 *       across the ground rather than sitting on it. New mist enters upwind and leaves downwind, which is what
 *       makes it read as a layer of air instead of a set of decorations.</li>
 *   <li><b>Light from the world.</b> The render type samples the lightmap, so the mist goes dark at night and
 *       under the deck exactly the way the grass under it does, and because it is a vanilla program the
 *       distance fog applies to it - which is what lets the wisps dissolve into the haze instead of ending at
 *       their own hard edge. A shader pack is free to replace that program, so the mist is lit and fogged by
 *       the pack along with everything else in front of you.</li>
 *   <li><b>A near and a far fade.</b> Nothing is drawn within a couple of blocks of the lens, so walking
 *       through the mist does not slap a sprite across the screen, and wisps fade out toward the radius so the
 *       volume has no visible boundary.</li>
 *   <li><b>No depth writes.</b> Wisps are translucent and unsorted, so writing depth would let an invisible
 *       corner of a near wisp cut a rectangle out of the mist behind it.</li>
 * </ul>
 *
 * <p>Cost is bounded twice over: the lattice is only walked to the mist radius, and the number of wisps drawn
 * is capped at {@link #MAX_WISPS}. At the defaults that is the order of two hundred quads, all of them within
 * a few tens of blocks of the player.
 */
public final class MistRenderer {

    /** The lattice spacing, in blocks. Each cell holds at most one wisp, so this sets the mist's grain. */
    private static final float CELL_BLOCKS = 4.0F;

    /** Frames stacked down the sheet texture; the wisps morph from one to the next as time passes. */
    private static final int FRAMES = 8;

    /** How fast a wisp slides through the sheet, in frames per second. Mist churns slowly. */
    private static final float FRAME_RATE = 0.16F;

    /** The hard ceiling on wisps per frame, whatever the radius and density are set to. */
    private static final int MAX_WISPS = 1400;

    /** How close a wisp may come to the lens before it starts to thin out, in blocks. */
    private static final double NEAR_FADE_START = 1.6D;

    /** How much further it takes to reach full strength - the near fade's width, in blocks. */
    private static final double NEAR_FADE_WIDTH = 2.4D;

    /**
     * The wisps' render type.
     *
     * <p>It is vanilla's own entity translucent program over the mist sheet: the lightmap in the vertex, the
     * world's fog on it, alpha blending, no culling (the billboards are already wound toward the camera but
     * there is nothing to gain by letting a rounding error drop one), and - the one deliberate departure -
     * depth writing off, so overlapping wisps blend rather than occluding each other in draw order.
     */
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            ExpandedWeather2Dynamics.MODID, "textures/environment/mist_puff.png");

    private static final RenderType MIST = RenderType.create(
            ExpandedWeather2Dynamics.MODID + "_mist",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS,
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

    private MistRenderer() {
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || !MistField.wispsActive()) {
            return;
        }
        // Iris runs every level stage again for its shadow map; this belongs to the visible frame only.
        if (RenderingCompat.isRenderingShadowPass()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Player player = minecraft.player;
        // Underwater and in lava the fog is already the most opaque thing in the frame and the mist would be
        // drawn inside a fluid. Its own fogs stay untouched; the wisps simply stand down.
        if (level == null || player == null || player.isUnderWater()) {
            return;
        }
        draw(minecraft, level, event, minecraft.renderBuffers().bufferSource().getBuffer(MIST));
        minecraft.renderBuffers().bufferSource().endBatch(MIST);
    }

    private static void draw(Minecraft minecraft, ClientLevel level, RenderLevelStageEvent event,
                             VertexConsumer consumer) {
        PoseStack.Pose pose = event.getPoseStack().last();
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        float camX = (float) cam.x;
        float camY = (float) cam.y;
        float camZ = (float) cam.z;

        float density = MistField.density();
        float radius = Math.max(1.0F, Config.MIST_RADIUS_BLOCKS.get().floatValue());
        float layer = Math.max(0.0F, Config.MIST_HEIGHT_BLOCKS.get().floatValue());
        float puffScale = Math.max(0.01F, Config.MIST_PUFF_SIZE.get().floatValue());
        // How much of the lattice carries a wisp. Thicker mist does not only darken each wisp, it fills more
        // of the air, which is the difference between a thin haze and fog you can see drifting past you.
        float coverage = Mth.clamp(0.22F + 0.42F * density, 0.15F, 0.85F);

        double time = (level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false)) / 20.0D;
        Vec3 wind = MistField.drift();
        double driftX = wind.x * time;
        double driftZ = wind.z * time;

        int[] colour = wispColour();
        int red = colour[0];
        int green = colour[1];
        int blue = colour[2];

        int reach = Mth.ceil(radius / CELL_BLOCKS);
        int centreCellX = Mth.floor(camX / CELL_BLOCKS);
        int centreCellZ = Mth.floor(camZ / CELL_BLOCKS);
        double radiusSqr = (double) radius * radius;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int drawn = 0;

        for (int cellX = centreCellX - reach; cellX <= centreCellX + reach && drawn < MAX_WISPS; cellX++) {
            for (int cellZ = centreCellZ - reach; cellZ <= centreCellZ + reach; cellZ++) {
                if (drawn >= MAX_WISPS) {
                    break;
                }
                if (hash01(cellX, 977, cellZ) > coverage) {
                    continue;
                }
                double x = cellX * CELL_BLOCKS + hash01(cellX, 1301, cellZ) * CELL_BLOCKS + driftX;
                double z = cellZ * CELL_BLOCKS + hash01(cellX, 1607, cellZ) * CELL_BLOCKS + driftZ;
                double dx = x - camX;
                double dz = z - camZ;
                double distanceSqr = dx * dx + dz * dz;
                if (distanceSqr > radiusSqr) {
                    continue;
                }
                double distance = Math.sqrt(distanceSqr);
                float nearFade = (float) Mth.clamp((distance - NEAR_FADE_START) / NEAR_FADE_WIDTH, 0.0D, 1.0D);
                // Full strength through the middle of the volume, thinning only at the rim, so the mist has
                // no edge you can walk up to and watch appear.
                float farFade = Mth.clamp((float) (1.0D - distance / radius) * 2.2F, 0.0F, 1.0F);
                if (nearFade <= 0.0F || farFade <= 0.0F) {
                    continue;
                }
                int blockX = Mth.floor(x);
                int blockZ = Mth.floor(z);
                cursor.set(blockX, level.getMinBuildHeight(), blockZ);
                if (!level.hasChunkAt(cursor)) {
                    continue;
                }
                int groundY = level.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ);
                double y = groundY + 0.20D + hash01(cellX, 1901, cellZ) * layer;
                // A ground mist is densest in its own bottom block or two, so a wisp high in the layer is
                // thinner than one sitting on the surface.
                float heightFade = layer <= 0.0F
                        ? 1.0F
                        : 1.0F - 0.45F * (float) ((y - groundY) / Math.max(layer, 0.5D));
                cursor.set(blockX, Mth.floor(y), blockZ);
                if (!level.isLoaded(cursor) || level.getBlockState(cursor).canOcclude()) {
                    continue;
                }

                float size = (3.0F + hash01(cellX, 2203, cellZ) * 4.2F) * puffScale
                        * (0.85F + 0.30F * Math.min(density, 1.0F));
                float halfWidth = size * 0.5F;
                // Flattened: a wisp is a shallow patch of air, not a ball, and the flatter it is the more it
                // reads as a layer hugging the ground rather than as a floating bubble.
                float halfHeight = size * 0.21F;
                float alpha = (0.040F + hash01(cellX, 2503, cellZ) * 0.085F)
                        * density * nearFade * farFade * heightFade;
                if (alpha <= 0.002F) {
                    continue;
                }
                int light = LevelRenderer.getLightColor(level, cursor);

                float phase = (float) time * FRAME_RATE + hash01(cellX, 2801, cellZ) * (FRAMES - 1);
                int frame = Mth.floor(phase) % (FRAMES - 1);
                float sub = phase - Mth.floor(phase);
                float vTop = (frame + sub) / FRAMES;
                float vBottom = (frame + 1 + sub) / FRAMES;

                double normalX = dx / distance;
                double normalZ = dz / distance;
                double rightX = -normalZ;
                double rightZ = normalX;
                float relX = (float) (x - camX);
                float relY = (float) (y - camY);
                float relZ = (float) (z - camZ);
                wisp(consumer, pose, relX, relY, relZ, rightX, rightZ, halfWidth, halfHeight,
                        vTop, vBottom, red, green, blue, alpha, light, normalX, normalZ);
                drawn++;
            }
        }
    }

    /**
     * One wisp: a cylinder billboard, which is what a ground mist needs rather than a full screen-facing quad.
     *
     * <p>It is turned to face the camera about the vertical axis only, so it stays upright while you walk
     * around and past it. A fully camera-facing sprite would lie back as you looked down at it, which is
     * exactly the angle you see a ground mist from.
     */
    private static void wisp(VertexConsumer consumer, PoseStack.Pose pose,
                             float relX, float relY, float relZ, double rightX, double rightZ,
                             float halfWidth, float halfHeight, float vTop, float vBottom,
                             int red, int green, int blue, float alpha, int light,
                             double normalX, double normalZ) {
        float rx = (float) rightX * halfWidth;
        float rz = (float) rightZ * halfWidth;
        vertex(consumer, pose, relX - rx, relY - halfHeight, relZ - rz, 0.0F, vBottom, red, green, blue, alpha, light, normalX, normalZ);
        vertex(consumer, pose, relX + rx, relY - halfHeight, relZ + rz, 1.0F, vBottom, red, green, blue, alpha, light, normalX, normalZ);
        vertex(consumer, pose, relX + rx, relY + halfHeight, relZ + rz, 1.0F, vTop, red, green, blue, alpha, light, normalX, normalZ);
        vertex(consumer, pose, relX - rx, relY + halfHeight, relZ - rz, 0.0F, vTop, red, green, blue, alpha, light, normalX, normalZ);
    }

    /** One corner. Positions are camera-relative, which is the convention at this render stage. */
    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose,
                               float x, float y, float z, float u, float v,
                               int red, int green, int blue, float alpha, int light,
                               double normalX, double normalZ) {
        consumer.addVertex(pose, x, y, z)
                .setColor(red, green, blue, Mth.clamp(alpha, 0.0F, 1.0F))
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, (float) normalX, 0.0F, (float) normalZ);
    }

    /**
     * The wisps' colour, as packed 0-255 components.
     *
     * <p>A pale, slightly blue grey - the colour of water droplets rather than of dust - greyed down in rain
     * and under a wet deck, and warmed toward gold by a low sun. It is the vertex colour, so the lightmap
     * still darkens it after this: this is what the mist is made of, not how lit it is.
     */
    private static int[] wispColour() {
        float grime = MistField.grime();
        float warmth = MistField.warmth();
        float red = Mth.lerp(grime, 0.88F, 0.68F);
        float green = Mth.lerp(grime, 0.91F, 0.70F);
        float blue = Mth.lerp(grime, 0.95F, 0.75F);
        float warm = warmth * 0.60F;
        red = Mth.lerp(warm, red, 0.97F);
        green = Mth.lerp(warm, green, 0.91F);
        blue = Mth.lerp(warm, blue, 0.84F);
        return new int[]{
                Mth.clamp((int) (red * 255.0F), 0, 255),
                Mth.clamp((int) (green * 255.0F), 0, 255),
                Mth.clamp((int) (blue * 255.0F), 0, 255)};
    }

    /**
     * A stable 0-1 value for a lattice cell, as a pure function of the cell and a channel.
     *
     * <p>Stability is the point: presence, placement, size, altitude and phase all come from this, so a wisp
     * is the same wisp from frame to frame and only the clock moves it. Two cells differing by one block get
     * unrelated values, which is what stops the lattice from showing as a grid.
     *
     * <p>The mixing constants are the 64-bit finalisers: two multiply-xor rounds leave every input bit
     * affecting every output bit, which is the property a lattice hash needs - neighbouring cells must not
     * land near each other, or the placement jitter shows as a pattern.
     */
    private static float hash01(int x, int salt, int z) {
        long value = x * 0x9E3779B97F4A7C15L + z * 0xC2B2AE3D27D4EB4FL + salt * 0x165667B19E3779F9L;
        value ^= value >>> 33;
        value *= 0xFF51AFD7ED558CCDL;
        value ^= value >>> 33;
        value *= 0xC4CEB9FE1A85EC53L;
        value ^= value >>> 33;
        return (float) ((value >>> 11) * 0x1.0p-53);
    }
}
