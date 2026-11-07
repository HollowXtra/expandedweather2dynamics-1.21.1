package com.imbet.client;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.network.VolcanoSyncPayload;
import com.imbet.volcano.ClientVolcanoStore;
import com.imbet.volcano.VolcanoPhase;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * The eruption, drawn from anywhere.
 *
 * <p>Drawn from photographs of real eruptions rather than from a model of one. A sustained column is opaque: a tight
 * mass of cauliflower billows, tan where the sun is on it and a dark grey-brown in its own shadow, boiling upward
 * from the vent and leaning downwind as it climbs, until it flattens into an umbrella that streams away with the wind
 * and drops curtains of ash under it. A phreatic blast is the same shape in white steam. A collapsing column sends
 * pyroclastic flows down the mountain - not a ring all round it, but lobes that pour down particular flanks, ground
 * hugging, with ash billowing up off their heads. While there is lava the base of the column is lit orange from
 * below, which at night is most of what can be seen, and an explosive column is laced with lightning.
 *
 * <p>The height is the game's sky, not the real one. A column twelve kilometres high is a few hundred blocks here, the
 * same scale a supercell's tower is drawn at - a real VEI 4 column and a real supercell top are about the same height -
 * because a column drawn at its true height in blocks stood almost entirely past the far clip plane, and what was left
 * of it was too faint to see at all.
 */
public final class VolcanoPlumeRenderer {
    /** The most puffs drawn in a frame, across every volcano. */
    private static final int MAX_PUFFS = 1100;
    /** How many volcanoes are given a plume; the nearest ones are the only ones that matter. */
    private static final int MAX_VOLCANOES = 3;
    /** Nearer than this a puff is faded out, so standing inside the column is not a whiteout. */
    private static final double NEAR_FADE_START = 24.0D;
    private static final double NEAR_FADE_WIDTH = 70.0D;
    /** Blocks of sky per metre of real column: about the scale the mod draws a supercell's tower at. */
    private static final double SKY_BLOCKS_PER_METRE = 0.04D;
    private static final double MIN_COLUMN_BLOCKS = 60.0D;
    private static final double MAX_COLUMN_BLOCKS = 900.0D;

    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            ExpandedWeather2Dynamics.MODID, "textures/environment/ash_billow.png");
    private static final ResourceLocation GLOW_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            ExpandedWeather2Dynamics.MODID, "textures/environment/soft_glow.png");

    /** Ash is a soft-edged puff with no depth write, exactly like the mist's, but drawn with its own batch. */
    private static final RenderType PLUME = RenderType.create(
            ExpandedWeather2Dynamics.MODID + "_volcano_plume",
            DefaultVertexFormat.NEW_ENTITY,
            Mode.QUADS,
            4096,
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

    /** The lava's light: a soft disc added to the frame rather than laid over it, so at night it glows. */
    private static final RenderType GLOW = RenderType.create(
            ExpandedWeather2Dynamics.MODID + "_volcano_glow",
            DefaultVertexFormat.NEW_ENTITY,
            Mode.QUADS,
            1024,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(GLOW_TEXTURE, false, false))
                    .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setOverlayState(RenderStateShard.OVERLAY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .createCompositeState(false));

    /** Lightning is a line of light, not a puff: an untextured, unlit ribbon added to the frame. */
    private static final RenderType BOLT = RenderType.create(
            ExpandedWeather2Dynamics.MODID + "_volcano_bolt",
            DefaultVertexFormat.POSITION_COLOR,
            Mode.QUADS,
            512,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .createCompositeState(false));

    private static int drawn;
    /** The sky's haze colour this frame, which a far puff is blended toward. */
    private static float[] haze = {0.7F, 0.8F, 1.0F};

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
        java.util.List<VolcanoSyncPayload.VolcanoSnapshot> visible = ClientVolcanoStore.active().stream()
                .filter(snapshot -> Math.hypot(snapshot.x() + 0.5D - cam.x, snapshot.z() + 0.5D - cam.z) <= renderRange)
                .sorted(java.util.Comparator.comparingDouble(snapshot ->
                        Math.hypot(snapshot.x() + 0.5D - cam.x, snapshot.z() + 0.5D - cam.z)))
                .limit(MAX_VOLCANOES)
                .toList();
        if (visible.isEmpty()) {
            return;
        }
        // The screen's own axes, in world terms, so a puff faces the camera rather than a fixed plane.
        Vec3 view = Vec3.directionFromRotation(camera.getXRot(), camera.getYRot());
        Vec3 reference = Math.abs(view.y) > 0.999D ? new Vec3(0.0D, 0.0D, 1.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 right = view.cross(reference).normalize();
        Vec3 up = right.cross(view).normalize();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double time = level.getGameTime() + partialTick;
        PoseStack.Pose pose = event.getPoseStack().last();

        // The game's distance fog is lifted for the column. It is total by a couple of hundred blocks at ordinary render
        // distances, and a column half a kilometre off was drawn entirely in the fog's colour - a pale blue-white ghost with
        // none of its own colour left. The haze a far column does sit in is put back by hand, in the puff's own colour.
        float fogStart = com.mojang.blaze3d.systems.RenderSystem.getShaderFogStart();
        float fogEnd = com.mojang.blaze3d.systems.RenderSystem.getShaderFogEnd();
        float[] fogColour = com.mojang.blaze3d.systems.RenderSystem.getShaderFogColor();
        haze = new float[] {fogColour[0], fogColour[1], fogColour[2]};
        com.mojang.blaze3d.systems.RenderSystem.setShaderFogStart(1.0E7F);
        com.mojang.blaze3d.systems.RenderSystem.setShaderFogEnd(1.1E7F);
        VertexConsumer consumer = minecraft.renderBuffers().bufferSource().getBuffer(PLUME);
        drawn = 0;
        for (VolcanoSyncPayload.VolcanoSnapshot snapshot : visible) {
            drawVolcano(level, consumer, pose, cam, view, right, up, snapshot, time);
        }
        minecraft.renderBuffers().bufferSource().endBatch(PLUME);

        VertexConsumer glow = minecraft.renderBuffers().bufferSource().getBuffer(GLOW);
        for (VolcanoSyncPayload.VolcanoSnapshot snapshot : visible) {
            if (snapshot.lava()) {
                drawLavaGlow(glow, pose, cam, right, up, snapshot, time);
            }
        }
        minecraft.renderBuffers().bufferSource().endBatch(GLOW);

        VertexConsumer bolts = minecraft.renderBuffers().bufferSource().getBuffer(BOLT);
        boolean any = false;
        for (VolcanoSyncPayload.VolcanoSnapshot snapshot : visible) {
            any |= drawLightning(bolts, pose, cam, view, snapshot, time);
        }
        if (any) {
            minecraft.renderBuffers().bufferSource().endBatch(BOLT);
        }
        com.mojang.blaze3d.systems.RenderSystem.setShaderFogStart(fogStart);
        com.mojang.blaze3d.systems.RenderSystem.setShaderFogEnd(fogEnd);
    }

    /** The column's height on the game's sky, in blocks. */
    static double columnBlocks(VolcanoSyncPayload.VolcanoSnapshot snapshot) {
        return snapshot.plumeMetres() <= 1.0D ? 0.0D
                : Mth.clamp(snapshot.plumeMetres() * SKY_BLOCKS_PER_METRE, MIN_COLUMN_BLOCKS, MAX_COLUMN_BLOCKS);
    }

    private static void drawVolcano(ClientLevel level, VertexConsumer consumer, PoseStack.Pose pose, Vec3 cam,
                                    Vec3 view, Vec3 right, Vec3 up,
                                    VolcanoSyncPayload.VolcanoSnapshot snapshot, double time) {
        VolcanoPhase phase = ClientVolcanoStore.phaseOf(snapshot);
        double column = columnBlocks(snapshot);
        double crater = Math.max(2.0D, snapshot.craterRadius());
        double ventX = snapshot.x() + 0.5D;
        double ventZ = snapshot.z() + 0.5D;
        double ventY = snapshot.ventY() + 1.0D;
        double windLength = Math.hypot(snapshot.windX(), snapshot.windZ());
        double windX = windLength > 1.0E-4D ? snapshot.windX() / windLength : 1.0D;
        double windZ = windLength > 1.0E-4D ? snapshot.windZ() / windLength : 0.0D;
        double shear = Mth.clamp(snapshot.windMph() / 30.0D, 0.35D, 1.6D);
        long seed = snapshot.id() * 0x9E3779B97F4A7C15L;

        if (column > 1.0D) {
            drawColumn(level, consumer, pose, cam, right, up, phase, snapshot, column, crater, ventX, ventY, ventZ,
                    windX, windZ, shear, seed, time);
        }
        if (phase.pyroclastic() && snapshot.surgeReach() > 8.0D) {
            drawPyroclasticFlows(level, consumer, pose, cam, right, up, snapshot, column, ventX, ventY, ventZ, seed, time);
        }
    }

    // ------------------------------------------------------------------ the column

    private static void drawColumn(ClientLevel level, VertexConsumer consumer, PoseStack.Pose pose, Vec3 cam,
                                   Vec3 right, Vec3 up, VolcanoPhase phase, VolcanoSyncPayload.VolcanoSnapshot snapshot,
                                   double column, double crater, double ventX, double ventY, double ventZ,
                                   double windX, double windZ, double shear, long seed, double time) {
        boolean steam = phase == VolcanoPhase.PHREATIC || phase == VolcanoPhase.UNREST || phase == VolcanoPhase.WANING;
        float density = switch (phase) {
            case MAGMATIC -> 0.84F;
            case COLLAPSE -> 0.86F;
            case PHREATIC -> 0.68F;
            case FLOW -> 0.38F;
            case WANING -> 0.26F;
            case UNREST -> 0.14F;
            case DORMANT -> 0.0F;
        };
        if (density <= 0.0F) {
            return;
        }
        double stem = Math.max(crater * 1.4D, column * 0.07D);
        double lean = column * 0.32D * shear;
        // The billows rise and are recycled: each one climbs from the vent to the top and starts again, so the column
        // is seen boiling upward instead of standing still. A real column rises at tens of metres a second.
        double cycleTicks = Mth.clamp(column * 2.6D, 300.0D, 1800.0D);
        int count = (int) Mth.clamp(90.0D + column * 0.42D, 90.0D, 460.0D);
        for (int index = 0; index < count && drawn < MAX_PUFFS; index++) {
            double unit = fract(hash(seed, index, 3) + time / cycleTicks);
            // Spread up the column slightly bottom-heavy, which is where the momentum and the mass are.
            double height = Math.pow(unit, 1.08D) * column;
            double radius = stem + column * 0.14D * unit + column * 0.05D * smoothstep(0.65D, 0.95D, unit);
            double angle = hash(seed, index, 5) * Math.PI * 2.0D + time * 0.0016D * (1.0D - unit);
            double skin = 0.55D + 0.45D * Math.sqrt(hash(seed, index, 7));
            double leanDepth = unit * unit;
            double x = ventX + Math.cos(angle) * radius * skin + windX * lean * leanDepth;
            double z = ventZ + Math.sin(angle) * radius * skin + windZ * lean * leanDepth;
            double y = ventY + height;
            // Each billow swells as it climbs, and breathes a little, so the outline keeps changing.
            double size = (radius * 1.25D + 6.0D) * (0.80D + 0.40D * hash(seed, index, 11))
                    * (1.0D + 0.08D * Math.sin(time * 0.02D + index));
            float fadeIn = (float) smoothstep(0.0D, 0.05D, unit);
            float fadeOut = (float) (1.0D - smoothstep(0.90D, 1.0D, unit));
            float alpha = density * (1.0F - 0.25F * (float) unit) * fadeIn * fadeOut;
            float lit = SunShading.lit(level, Math.cos(angle), (unit - 0.35D) * 0.9D, Math.sin(angle));
            float[] colour = steam ? SunShading.cloudColour(level, 0.96F, lit, 0.66F) : ash(level, lit, phase == VolcanoPhase.COLLAPSE ? 0.82F : 1.0F);
            if (puff(level, consumer, pose, cam, right, up, x, y, z, size, colour, alpha)) {
                drawn++;
            }
        }

        // The umbrella: where the column stops rising it spreads and streams downwind, flat on top and ragged
        // underneath, thinning as it goes.
        if (column >= 140.0D && (phase == VolcanoPhase.MAGMATIC || phase == VolcanoPhase.COLLAPSE || phase == VolcanoPhase.PHREATIC)) {
            double topX = ventX + windX * lean;
            double topZ = ventZ + windZ * lean;
            double topY = ventY + column * 0.97D;
            double reach = column * 1.25D * shear;
            int umbrella = (int) Mth.clamp(column * 0.16D, 40.0D, 150.0D);
            for (int index = 0; index < umbrella && drawn < MAX_PUFFS; index++) {
                double along = Math.pow(hash(seed, index, 101), 0.8D) * reach;
                double across = (hash(seed, index, 103) * 2.0D - 1.0D) * (column * 0.22D + along * 0.32D);
                double x = topX + windX * along - windZ * across;
                double z = topZ + windZ * along + windX * across;
                double y = topY - Math.pow(hash(seed, index, 107), 1.5D) * column * 0.14D - along * 0.05D;
                double size = column * (0.16D + 0.06D * hash(seed, index, 109)) * (1.0D + along / Math.max(1.0D, reach) * 0.6D);
                float alpha = density * 0.70F * (float) (1.0D - 0.65D * along / Math.max(1.0D, reach));
                float lit = SunShading.lit(level, windX * 0.3D, 1.0D, windZ * 0.3D);
                float[] colour = steam ? SunShading.cloudColour(level, 0.96F, lit, 0.70F) : ash(level, lit, 1.0F);
                if (puff(level, consumer, pose, cam, right, up, x, y, z, size, colour, alpha)) {
                    drawn++;
                }
            }
            // Ash falling out of the umbrella: grey-brown curtains hanging down to the ground downwind.
            if (phase.ashFall() && !steam) {
                int curtains = 22;
                for (int index = 0; index < curtains && drawn < MAX_PUFFS; index++) {
                    double along = (0.20D + 0.80D * hash(seed, index, 131)) * reach;
                    double across = (hash(seed, index, 137) * 2.0D - 1.0D) * (column * 0.15D + along * 0.22D);
                    double x = topX + windX * along - windZ * across;
                    double z = topZ + windZ * along + windX * across;
                    double ground = groundAt(level, x, z, ventY - 4.0D);
                    double base = topY - column * 0.12D - along * 0.05D;
                    double height = Math.max(20.0D, base - ground);
                    double width = column * (0.07D + 0.05D * hash(seed, index, 139));
                    float alpha = 0.11F * (float) (1.0D - 0.5D * along / Math.max(1.0D, reach));
                    float[] colour = ash(level, 0.4F, 0.9F);
                    // A curtain of falling ash is soft-edged and thins toward the ground: a stack of soft puffs, each a
                    // little narrower and fainter than the one above it, and drifting with the wind as it falls. One tall
                    // stretched quad drew it as a hard grey stripe.
                    int pieces = 5;
                    for (int piece = 0; piece < pieces && drawn < MAX_PUFFS; piece++) {
                        double share = (piece + 0.5D) / pieces;
                        double py = base - share * height;
                        double drift = share * height * 0.25D;
                        double size = width * (1.9D - 0.6D * share);
                        float fade = (float) (1.0D - 0.55D * share);
                        if (puff(level, consumer, pose, cam, right, up, x + windX * drift, py, z + windZ * drift, size, colour, alpha * fade)) {
                            drawn++;
                        }
                    }
                }
            }
        }
    }

    /** Volcanic ash in the light: tan toward the sun, a dark grey-brown in its own shadow. */
    private static float[] ash(ClientLevel level, float lit, float brightness) {
        float[] grey = SunShading.cloudColour(level, 0.62F * brightness, lit, 0.40F);
        return new float[] {grey[0], grey[1] * 0.93F, grey[2] * 0.83F};
    }

    // ------------------------------------------------------------------ lava light

    /**
     * The orange light of the lava on the underside of the column and over the crater. It is its own light, so it is
     * drawn full-bright and only faintly by day; at night it is most of what can be seen of an eruption.
     */
    private static void drawLavaGlow(VertexConsumer consumer, PoseStack.Pose pose, Vec3 cam, Vec3 right, Vec3 up,
                                     VolcanoSyncPayload.VolcanoSnapshot snapshot, double time) {
        VolcanoPhase phase = ClientVolcanoStore.phaseOf(snapshot);
        double column = columnBlocks(snapshot);
        double crater = Math.max(2.0D, snapshot.craterRadius());
        double ventX = snapshot.x() + 0.5D;
        double ventZ = snapshot.z() + 0.5D;
        double ventY = snapshot.ventY() + 1.0D;
        double windLength = Math.hypot(snapshot.windX(), snapshot.windZ());
        double windX = windLength > 1.0E-4D ? snapshot.windX() / windLength : 1.0D;
        double windZ = windLength > 1.0E-4D ? snapshot.windZ() / windLength : 0.0D;
        double shear = Mth.clamp(snapshot.windMph() / 30.0D, 0.35D, 1.6D);
        long seed = snapshot.id() * 0x9E3779B97F4A7C15L;
        double glow = Math.max(8.0D, crater * 3.2D);
        for (int index = 0; index < 20 && drawn < MAX_PUFFS; index++) {
            double angle = hash(seed, index, 211) * Math.PI * 2.0D + time * 0.04D;
            double spread = crater * 0.9D * Math.sqrt(hash(seed, index, 213));
            double x = ventX + Math.cos(angle) * spread;
            double z = ventZ + Math.sin(angle) * spread;
            double y = ventY + crater * 0.3D + hash(seed, index, 217) * crater * 1.2D;
            float flicker = 0.75F + 0.25F * (float) Math.sin(time * 0.3D + index * 1.7D);
            if (puffUnlit(consumer, pose, cam, right, up, x, y, z, glow, new float[] {1.0F, 0.36F, 0.07F}, 0.24F * flicker)) {
                drawn++;
            }
        }
        // Lava bombs: blobs of molten rock thrown out of the crater on ballistic arcs, glowing as they fly and falling back
        // on the flanks. Each one's flight is a function of time alone, so they fly the same for everyone watching.
        if (phase == VolcanoPhase.MAGMATIC || phase == VolcanoPhase.COLLAPSE) {
            int bombs = phase == VolcanoPhase.MAGMATIC ? 18 : 10;
            double flight = 70.0D;
            double gravity = 0.06D;
            for (int index = 0; index < bombs && drawn < MAX_PUFFS; index++) {
                double t = fract(hash(seed, index, 601) + time / flight) * flight;
                double heading = hash(seed, index, 603) * Math.PI * 2.0D + Math.floor(hash(seed, index, 601) + time / flight) * 2.4D;
                double outward = 0.25D + 0.55D * hash(seed, index, 605);
                double upward = 1.6D + 1.4D * hash(seed, index, 607);
                double y = ventY + crater * 0.3D + upward * t - 0.5D * gravity * t * t;
                if (y < ventY - crater) {
                    continue;
                }
                double x = ventX + Math.cos(heading) * outward * t;
                double z = ventZ + Math.sin(heading) * outward * t;
                float cool = (float) Mth.clamp(t / flight, 0.0D, 1.0D);
                if (puffUnlit(consumer, pose, cam, right, up, x, y, z, 3.2D + 2.0D * hash(seed, index, 609),
                        new float[] {1.0F, 0.62F - 0.25F * cool, 0.20F - 0.12F * cool}, 0.70F * (1.0F - 0.6F * cool))) {
                    drawn++;
                }
            }
        }
        if (column > 1.0D && (phase == VolcanoPhase.MAGMATIC || phase == VolcanoPhase.COLLAPSE || phase == VolcanoPhase.FLOW)) {
            double stem = Math.max(crater * 1.3D, column * 0.05D);
            double lean = column * 0.32D * shear;
            int count = 28;
            for (int index = 0; index < count && drawn < MAX_PUFFS; index++) {
                double unit = Math.pow(hash(seed, index, 223), 1.6D) * 0.38D;
                double angle = hash(seed, index, 227) * Math.PI * 2.0D;
                double radius = (stem + column * 0.10D * unit) * 0.8D;
                double x = ventX + Math.cos(angle) * radius + windX * lean * unit * unit;
                double z = ventZ + Math.sin(angle) * radius + windZ * lean * unit * unit;
                double y = ventY + unit * column;
                double size = radius * 1.6D + 8.0D;
                float alpha = 0.16F * (float) (1.0D - unit / 0.38D);
                if (puffUnlit(consumer, pose, cam, right, up, x, y, z, size, new float[] {1.0F, 0.34F, 0.06F}, alpha)) {
                    drawn++;
                }
            }
        }
    }

    // ------------------------------------------------------------------ pyroclastic flows

    /**
     * Pyroclastic flows: lobes of hot ash and rock pouring down particular flanks, advancing through the collapse,
     * hugging the ground with a rolling, billowing head and ash lofting off the top of it.
     */
    private static void drawPyroclasticFlows(ClientLevel level, VertexConsumer consumer, PoseStack.Pose pose, Vec3 cam,
                                             Vec3 right, Vec3 up, VolcanoSyncPayload.VolcanoSnapshot snapshot,
                                             double column, double ventX, double ventY, double ventZ, long seed, double time) {
        double reach = snapshot.surgeReach();
        double advance = reach * smoothstep(0.0D, 0.75D, Mth.clamp(snapshot.phaseProgress(), 0.0D, 1.0D));
        int lobes = 2 + (int) (hash(seed, 0, 401) * 2.0D);
        for (int lobe = 0; lobe < lobes; lobe++) {
            double heading = hash(seed, lobe, 403) * Math.PI * 2.0D;
            double width = 0.12D + 0.10D * hash(seed, lobe, 405);
            double head = advance * (0.70D + 0.30D * hash(seed, lobe, 407));
            if (head < 6.0D) {
                continue;
            }
            int count = (int) Mth.clamp(head * 0.5D, 24.0D, 110.0D);
            for (int index = 0; index < count && drawn < MAX_PUFFS; index++) {
                double t = Math.pow(hash(seed, lobe * 997 + index, 409), 0.7D);
                double distance = head * t;
                // A flow follows the ground, wandering a little, and widens as it spreads out of its gully.
                double meander = Math.sin(distance * 0.03D + lobe * 2.1D) * distance * 0.08D;
                double side = (hash(seed, lobe * 997 + index, 411) * 2.0D - 1.0D) * distance * width;
                double dirX = Math.cos(heading);
                double dirZ = Math.sin(heading);
                double x = ventX + dirX * distance - dirZ * (side + meander);
                double z = ventZ + dirZ * distance + dirX * (side + meander);
                double ground = groundAt(level, x, z, ventY - distance * 0.4D);
                boolean nearHead = t > 0.84D;
                double size = (8.0D + distance * 0.10D) * (nearHead ? 1.7D : 1.0D) * (0.75D + 0.5D * hash(seed, index, 413));
                double y = ground + size * (nearHead ? 0.45D : 0.30D) + Math.sin(time * 0.05D + index) * 1.5D;
                float lit = SunShading.lit(level, Math.cos(heading + index), 0.5D, Math.sin(heading + index));
                float[] colour = ash(level, lit, 0.95F);
                if (puff(level, consumer, pose, cam, right, up, x, y, z, size, colour, 0.62F)) {
                    drawn++;
                }
                // Ash lofting off the flow: lighter, rising, a tall cloud over the length of it.
                if (hash(seed, index, 417) < 0.35D && drawn < MAX_PUFFS) {
                    double rise = fract(hash(seed, index, 419) + time / 400.0D);
                    double loft = column > 1.0D ? column * 0.28D : 60.0D;
                    double ly = ground + size * 0.6D + rise * loft * (0.4D + 0.6D * t);
                    float la = 0.34F * (float) (1.0D - rise) * (float) smoothstep(0.0D, 0.1D, rise);
                    if (puff(level, consumer, pose, cam, right, up, x, ly, z, size * (1.2D + rise), ash(level, lit, 1.05F), la)) {
                        drawn++;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ lightning

    /**
     * Lightning in the column. Ash particles grinding past each other charge the column, and an explosive one is laced
     * with bolts - short ones near the vent and longer ones higher up. A bolt lasts a few frames and the column lights
     * round it.
     */
    private static boolean drawLightning(VertexConsumer bolts, PoseStack.Pose pose, Vec3 cam, Vec3 view,
                                         VolcanoSyncPayload.VolcanoSnapshot snapshot, double time) {
        VolcanoPhase phase = ClientVolcanoStore.phaseOf(snapshot);
        double column = columnBlocks(snapshot);
        if (!phase.explosive() || column < 100.0D || phase == VolcanoPhase.UNREST) {
            return false;
        }
        long seed = snapshot.id() * 0x9E3779B97F4A7C15L;
        long bucket = (long) Math.floor(time / 5.0D);
        double chance = phase == VolcanoPhase.MAGMATIC ? 0.22D : 0.12D;
        if (hash(seed, (int) bucket, 501) > chance) {
            return false;
        }
        double life = time / 5.0D - bucket;
        float strength = (float) (1.0D - life);
        double ventX = snapshot.x() + 0.5D;
        double ventZ = snapshot.z() + 0.5D;
        double ventY = snapshot.ventY() + 1.0D;
        double stem = Math.max(Math.max(2.0D, snapshot.craterRadius()) * 1.3D, column * 0.05D);
        double startUnit = 0.05D + 0.45D * hash(seed, (int) bucket, 503);
        double angle = hash(seed, (int) bucket, 505) * Math.PI * 2.0D;
        double x = ventX + Math.cos(angle) * stem * 0.6D;
        double z = ventZ + Math.sin(angle) * stem * 0.6D;
        double y = ventY + column * startUnit;
        double length = column * (0.10D + 0.18D * hash(seed, (int) bucket, 507));
        double headingX = Math.cos(angle + Math.PI);
        double headingZ = Math.sin(angle + Math.PI);
        int segments = 7;
        double px = x, py = y, pz = z;
        for (int segment = 0; segment < segments; segment++) {
            double nx = px + headingX * length / segments + (hash(seed, (int) bucket * 31 + segment, 509) - 0.5D) * length * 0.35D;
            double nz = pz + headingZ * length / segments + (hash(seed, (int) bucket * 31 + segment, 511) - 0.5D) * length * 0.35D;
            double ny = py - length / segments * (0.3D + 0.7D * hash(seed, (int) bucket * 31 + segment, 513));
            ribbon(bolts, pose, cam, view, px, py, pz, nx, ny, nz, 1.6D, strength);
            px = nx;
            py = ny;
            pz = nz;
        }
        return true;
    }

    private static void ribbon(VertexConsumer consumer, PoseStack.Pose pose, Vec3 cam, Vec3 view,
                               double x0, double y0, double z0, double x1, double y1, double z1, double width, float strength) {
        Vec3 along = new Vec3(x1 - x0, y1 - y0, z1 - z0);
        Vec3 side = along.cross(view);
        if (side.lengthSqr() < 1.0E-8D) {
            return;
        }
        side = side.normalize().scale(width * 0.5D);
        float r = 0.86F, g = 0.84F, b = 1.0F, a = 0.85F * strength;
        consumer.addVertex(pose, (float) (x0 - side.x - cam.x), (float) (y0 - side.y - cam.y), (float) (z0 - side.z - cam.z)).setColor(r, g, b, a);
        consumer.addVertex(pose, (float) (x0 + side.x - cam.x), (float) (y0 + side.y - cam.y), (float) (z0 + side.z - cam.z)).setColor(r, g, b, a);
        consumer.addVertex(pose, (float) (x1 + side.x - cam.x), (float) (y1 + side.y - cam.y), (float) (z1 + side.z - cam.z)).setColor(r, g, b, a);
        consumer.addVertex(pose, (float) (x1 - side.x - cam.x), (float) (y1 - side.y - cam.y), (float) (z1 - side.z - cam.z)).setColor(r, g, b, a);
    }

    // ------------------------------------------------------------------ quads

    /** One lit puff: a camera-facing quad at a world position, faded out when the camera is inside it. */
    private static boolean puff(ClientLevel level, VertexConsumer consumer, PoseStack.Pose pose, Vec3 cam,
                                Vec3 right, Vec3 up,
                                double x, double y, double z, double size, float[] colour, float alpha) {
        double dx = x - cam.x;
        double dz = z - cam.z;
        double distance = Math.sqrt(dx * dx + (y - cam.y) * (y - cam.y) + dz * dz);
        float nearFade = (float) Mth.clamp((distance - size * 0.5D - NEAR_FADE_START) / NEAR_FADE_WIDTH + 1.0D, 0.0D, 1.0D);
        float finalAlpha = alpha * nearFade;
        if (finalAlpha <= 0.004F) {
            return false;
        }
        int light = lightAt(level, x, z);
        // Aerial perspective: a far column softens into the sky's haze, but keeps its own colour well beyond where the
        // game's fog would have erased it.
        float aerial = (float) Mth.clamp((distance - 400.0D) / 3000.0D, 0.0D, 0.55D);
        if (aerial > 0.0F) {
            colour = new float[] {Mth.lerp(aerial, colour[0], haze[0]), Mth.lerp(aerial, colour[1], haze[1]), Mth.lerp(aerial, colour[2], haze[2])};
        }
        double horizontal = Math.max(1.0E-4D, Math.hypot(dx, dz));
        return quad(consumer, pose, cam, right, up, x, y, z, size, size, colour, finalAlpha, light,
                (float) (dx / horizontal), (float) (dz / horizontal));
    }

    /** An emitting puff: its own light, not the world's. */
    private static boolean puffUnlit(VertexConsumer consumer, PoseStack.Pose pose, Vec3 cam, Vec3 right, Vec3 up,
                                     double x, double y, double z, double size, float[] colour, float alpha) {
        if (alpha <= 0.004F) {
            return false;
        }
        double dx = x - cam.x;
        double dz = z - cam.z;
        double distance = Math.max(1.0E-4D, Math.hypot(dx, dz));
        return quad(consumer, pose, cam, right, up, x, y, z, size, size, colour, alpha, LightTexture.FULL_BRIGHT,
                (float) (dx / distance), (float) (dz / distance));
    }

    /** A falling curtain: a tall quad standing upright and turned to face the camera about the vertical. */
    private static boolean curtain(ClientLevel level, VertexConsumer consumer, PoseStack.Pose pose, Vec3 cam,
                                   double x, double y, double z, double width, double height, float[] colour, float alpha) {
        double dx = x - cam.x;
        double dz = z - cam.z;
        double horizontal = Math.max(1.0E-4D, Math.hypot(dx, dz));
        if (horizontal < 30.0D) {
            alpha *= (float) (horizontal / 30.0D);
        }
        if (alpha <= 0.004F) {
            return false;
        }
        Vec3 right = new Vec3(-dz / horizontal, 0.0D, dx / horizontal);
        Vec3 up = new Vec3(0.0D, 1.0D, 0.0D);
        return quad(consumer, pose, cam, right, up, x, y, z, width, height, colour, alpha, lightAt(level, x, z),
                (float) (dx / horizontal), (float) (dz / horizontal));
    }

    private static boolean quad(VertexConsumer consumer, PoseStack.Pose pose, Vec3 cam, Vec3 right, Vec3 up,
                                double x, double y, double z, double width, double height, float[] colour, float alpha,
                                int light, float normalX, float normalZ) {
        float relX = (float) (x - cam.x);
        float relY = (float) (y - cam.y);
        float relZ = (float) (z - cam.z);
        float halfW = (float) (width * 0.5D);
        float halfH = (float) (height * 0.5D);
        float acrossX = (float) right.x * halfW, acrossY = (float) right.y * halfW, acrossZ = (float) right.z * halfW;
        float upwardX = (float) up.x * halfH, upwardY = (float) up.y * halfH, upwardZ = (float) up.z * halfH;
        vertex(consumer, pose, relX - acrossX - upwardX, relY - acrossY - upwardY, relZ - acrossZ - upwardZ, 0.0F, 1.0F, colour, alpha, light, normalX, normalZ);
        vertex(consumer, pose, relX + acrossX - upwardX, relY + acrossY - upwardY, relZ + acrossZ - upwardZ, 1.0F, 1.0F, colour, alpha, light, normalX, normalZ);
        vertex(consumer, pose, relX + acrossX + upwardX, relY + acrossY + upwardY, relZ + acrossZ + upwardZ, 1.0F, 0.0F, colour, alpha, light, normalX, normalZ);
        vertex(consumer, pose, relX - acrossX + upwardX, relY - acrossY + upwardY, relZ - acrossZ + upwardZ, 0.0F, 0.0F, colour, alpha, light, normalX, normalZ);
        return true;
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z,
                               float u, float v, float[] colour, float alpha, int light, float normalX, float normalZ) {
        consumer.addVertex(pose, x, y, z)
                .setColor(colour[0], colour[1], colour[2], Mth.clamp(alpha, 0.0F, 1.0F))
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, normalX, 0.0F, normalZ);
    }

    /** The light a puff is drawn with: the sky light over the ground below it, because the air has no light map. */
    private static int lightAt(ClientLevel level, double x, double z) {
        int blockX = Mth.floor(x);
        int blockZ = Mth.floor(z);
        if (!level.hasChunkAt(new BlockPos(blockX, level.getMinBuildHeight(), blockZ))) {
            return LightTexture.pack(0, level.getSkyDarken() > 0 ? 15 - level.getSkyDarken() : 15);
        }
        BlockPos sample = new BlockPos(blockX, level.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ) + 1, blockZ);
        return LevelRenderer.getLightColor(level, sample);
    }

    /** The ground's height, from the surface map the client is sent; a fallback where the chunk is not loaded. */
    private static double groundAt(ClientLevel level, double x, double z, double fallback) {
        int blockX = Mth.floor(x);
        int blockZ = Mth.floor(z);
        if (!level.hasChunkAt(new BlockPos(blockX, level.getMinBuildHeight(), blockZ))) {
            return fallback;
        }
        return level.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ);
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        double t = Mth.clamp((value - edge0) / Math.max(1.0E-6D, edge1 - edge0), 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }

    private static double fract(double value) {
        return value - Math.floor(value);
    }

    /** A stable random in 0..1 per volcano, puff and purpose, so a plume does not reshuffle itself every frame. */
    private static double hash(long seed, int index, int salt) {
        long value = seed + index * 0x9E3779B97F4A7C15L + salt * 0xC2B2AE3D27D4EB4FL;
        value ^= value >>> 31;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 33;
        return (value >>> 11) / (double) (1L << 53);
    }
}
