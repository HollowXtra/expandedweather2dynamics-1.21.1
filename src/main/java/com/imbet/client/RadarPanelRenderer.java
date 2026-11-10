package com.imbet.client;

import com.imbet.Config;
import com.imbet.network.RadarSnapshotPayload;
import com.imbet.radar.ClientRadarStore;
import com.imbet.radar.RadarBlockEntity;
import com.imbet.radar.RadarStormSnapshot;
import com.imbet.weather.PrecipitationField;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.util.Mth;

import java.util.List;

public class RadarPanelRenderer implements BlockEntityRenderer<RadarBlockEntity> {

    /**
     * The vertex colour the scope face is drawn with.
     *
     * <p>Its alpha sits below {@link RadarShader#PASS_THROUGH_ALPHA} on purpose: that is how the program
     * is told to generate the screen instead of keeping the colour it was given, which is why the RGB is
     * immaterial here. It is drawn through {@link PanelRenderPath#screenPass}, which swaps it for an
     * explicitly painted screen while a shaderpack is active - that program keeps whatever colour it
     * is handed, so this alpha would leave the scope a nearly invisible wash.
     */
    private static final int SCOPE_PASS = 0x2A020303;

    /** The box the display is drawn in on the block's top face, and the range circle as a share of it. */
    private static final float FACE_MIN = 0.055F;
    private static final float FACE_MAX = 0.945F;
    private static final float RING_RADIUS = RadarEchoField.SCOPE_RADIUS / 0.445F;


    /**
     * Reflectivity samples for the cell corners currently being drawn. Reused between calls because the render
     * thread is single-threaded and this is otherwise a fresh array per storm per frame.
     */
    private static float[] fieldScratch = new float[0];

    /** Radial velocity samples for the lattice corners currently being drawn, reused for the same reason. */
    private static float[] velocityScratch = new float[0];

    /**
     * The in-world half of the radar's display: it draws what {@link RadarEchoField} computes onto the top
     * face of the block, where the radar program turns the panel coordinate into a scope face.
     */
    public RadarPanelRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(RadarBlockEntity radar, float partialTick, PoseStack stack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (!Config.RADAR_WORLD_PANEL_ENABLED.get()) {
            return;
        }
        // The feed says which product it was swept in and how far it reaches, so this panel and a handheld
        // showing the same radar cannot disagree; the block entity is only the fallback before the first sweep
        // lands, or with the radar switched off.
        RadarSnapshotPayload feed = ClientRadarStore.feedFor(radar.getBlockPos());
        List<RadarStormSnapshot> storms = feed == null ? List.of() : feed.storms();
        int range = feed != null ? Math.max(64, feed.range()) : Math.max(64, radar.getRange());
        int mode = feed != null ? feed.mode() : radar.getMode();
        float y = 1.014F;

        // The face and the product are one quad through the display program, which is the same picture the handheld
        // shows. Under a pack that program is gone, so the older path below draws them as geometry instead.
        boolean program = RadarScopeShader.ready() && !RenderingCompat.isShaderPackActive();
        if (program) {
            long tick = radar.getLevel() == null ? 0L : radar.getLevel().getGameTime();
            RadarFieldTexture field = RadarFieldTexture.forRadar(radar.getBlockPos(), storms, mode, range, tick, partialTick);
            field.display(radar.getBlockPos().getX() + 0.5D, radar.getBlockPos().getZ() + 0.5D, range, mode, RING_RADIUS, 0.55F, false);
            VertexConsumer face = buffer.getBuffer(RadarScopeShader.type(field));
            PoseStack.Pose facePose = stack.last();
            face.addVertex(facePose, FACE_MIN, y, FACE_MAX).setUv(0.0F, 1.0F).setColor(0xFFFFFFFF);
            face.addVertex(facePose, FACE_MAX, y, FACE_MAX).setUv(1.0F, 1.0F).setColor(0xFFFFFFFF);
            face.addVertex(facePose, FACE_MAX, y, FACE_MIN).setUv(1.0F, 0.0F).setColor(0xFFFFFFFF);
            face.addVertex(facePose, FACE_MIN, y, FACE_MIN).setUv(0.0F, 0.0F).setColor(0xFFFFFFFF);
        }

        VertexConsumer consumer = PanelRenderPath.consumer(buffer, RadarShader.radarPanel(), packedLight);
        PoseStack.Pose pose = stack.last();
        if (!program) {
            // The face goes down first: the map, the returns and the markers are all drawn over it, and the
            // buffer keeps the order the vertices went in.
            RenderQuads.topQuadUv(consumer, pose, 0.055F, 0.055F, 0.945F, 0.945F, y, PanelRenderPath.screenPass(RadarShader.radarPanel(), SCOPE_PASS));
            drawRadarMapGrid(consumer, pose, y + 0.001F);
        }
        for (RadarStormSnapshot storm : storms) {
            float rx = RadarEchoField.scopeCoord(storm.x(), radar.getBlockPos().getX(), range);
            float rz = RadarEchoField.scopeCoord(storm.z(), radar.getBlockPos().getZ(), range);
            float size = RadarEchoField.returnSize(storm, range);
            float circulationX = RadarEchoField.scopeCoord(storm.circulationX(), radar.getBlockPos().getX(), range);
            float circulationZ = RadarEchoField.scopeCoord(storm.circulationZ(), radar.getBlockPos().getZ(), range);
            float hookX = RadarEchoField.scopeCoord(storm.hookX(), radar.getBlockPos().getX(), range);
            float hookZ = RadarEchoField.scopeCoord(storm.hookZ(), radar.getBlockPos().getZ(), range);
            if (!RadarEchoField.insideScope(circulationX, circulationZ)) {
                circulationX = rx;
                circulationZ = rz;
            }
            float displayX = storm.hasConfirmedCirculation() ? circulationX : storm.hookEcho() ? hookX : rx;
            float displayZ = storm.hasConfirmedCirculation() ? circulationZ : storm.hookEcho() ? hookZ : rz;
            boolean returnInside = RadarEchoField.insideScope(rx, rz);
            boolean displayInside = RadarEchoField.insideScope(displayX, displayZ);
            if (!returnInside && !displayInside) {
                continue;
            }
            // A circulation inside a parent storm has no precipitation shield of its own, so it draws no
            // reflectivity return - but it does have a velocity signature, so the other two products still
            // give it an extent.
            float markerSize = Math.max(size, 0.030F);
            if (returnInside && !program) {
                PrecipitationField.Geometry precipitation = RadarEchoField.fieldOf(storm);
                if ((precipitation != null || storm.hasLineField()) && mode != 2) {
                    drawFieldReturn(consumer, pose, storm, precipitation, mode == 1, radar.getBlockPos().getX(), radar.getBlockPos().getZ(), rx, rz, range, y + 0.007F);
                } else if (mode == 1) {
                    drawVelocityReturn(consumer, pose, storm, hookX, hookZ, markerSize, y + 0.007F);
                } else if (mode == 2) {
                    drawRiskPixels(consumer, pose, storm, rx, rz, markerSize, y + 0.007F);
                } else if (size > 0.0F) {
                    drawReflectivityReturn(consumer, pose, storm, radar.getBlockPos().getX(), radar.getBlockPos().getZ(), rx, rz, size, range, y + 0.007F);
                }
            }
            float trackX = displayInside ? displayX : rx;
            float trackZ = displayInside ? displayZ : rz;
            drawMotionTrack(consumer, pose, storm, trackX, trackZ, range, y + 0.014F);
            if (storm.subVortices() > 0) {
                drawSubVortexMarkers(consumer, pose, storm, circulationX, circulationZ, size, y + 0.016F);
            }
            // The ground-truth marker first, so a signature icon drawn over it still leaves the cross's arms
            // reading: a tornado known to be on the ground has both, which is what an operator's display shows.
            boolean confirmedMarker = storm.hasConfirmedCirculation()
                    && Config.RADAR_CONFIRMED_CIRCULATION_MARKER.get() && displayInside;
            if (confirmedMarker) {
                drawTornadoMarker(consumer, pose, circulationX, circulationZ, size, y + 0.017F);
            }
            // Then the velocity product's own read, at the point the rotation is actually at - the couplet's
            // circulation for a TVS, and the resolved mesocyclone for a diamond, which for a hooking storm is
            // not the hook notch itself.
            int signature = Config.RADAR_VORTEX_ICONS.get()
                    ? RadarEchoField.vortexSignatureTier(storm) : RadarEchoField.SIGNATURE_NONE;
            float mesoX = RadarEchoField.scopeCoord(storm.mesoX(), radar.getBlockPos().getX(), range);
            float mesoZ = RadarEchoField.scopeCoord(storm.mesoZ(), radar.getBlockPos().getZ(), range);
            if (signature == RadarEchoField.SIGNATURE_TVS && displayInside) {
                drawVortexIcon(consumer, pose, circulationX, circulationZ, RadarEchoField.SIGNATURE_TVS, y + 0.019F);
            } else if ((signature == RadarEchoField.SIGNATURE_ETVS || signature == RadarEchoField.SIGNATURE_MESO)
                    && RadarEchoField.insideScope(mesoX, mesoZ)) {
                drawVortexIcon(consumer, pose, mesoX, mesoZ, signature, y + 0.019F);
            } else if (!confirmedMarker && storm.hookEcho() && displayInside) {
                drawMesocycloneMarker(consumer, pose, hookX, hookZ, size, y + 0.016F);
            }
        }
    }

    /**
     * The map under the returns: an 8x8 graticule, the power lines and the roads that cross it.
     *
     * <p>These lines used to be written well below the alpha the program switches passes on, so every one
     * of them was being repainted as a miniature radar scope rather than kept as a line, which is where
     * the dotted grid came from. They go through {@link #overlay} now, which lifts the alpha above that
     * threshold and dims the colour by the same factor.
     *
     * <p>The lines are a little wider than they were, too. At 0.0016 blocks across - the width they used to
     * be - a line is about nine tenths of a screen pixel even with the block filling the view, so it
     * rasterised as a row of dots wherever it happened to fall between two pixel centres.
     *
     * <p>The near-black quad that used to wash the whole panel is gone with it: the program's face draws
     * the screen and its bezel now, so the wash had nothing left to darken - and drawn through the
     * pass-through path it would have been clipped to the round screen, leaving exactly the corners it was
     * there to cover. The blue centre cross went too, because the face draws its own crosshair.
     */
    private static void drawRadarMapGrid(VertexConsumer consumer, PoseStack.Pose pose, float y) {
        for (int i = 0; i <= 8; i++) {
            float p = 0.08F + i * 0.105F;
            int color = i % 2 == 0 ? RadarEchoField.overlay(0x32D657, 0.30F) : RadarEchoField.overlay(0x22A445, 0.20F);
            RenderQuads.topLineUv(consumer, pose, p, 0.075F, p, 0.925F, 0.0014F, y + 0.001F, color);
            RenderQuads.topLineUv(consumer, pose, 0.075F, p, 0.925F, p, 0.0014F, y + 0.001F, color);
        }
        RenderQuads.topLineUv(consumer, pose, 0.08F, 0.24F, 0.37F, 0.18F, 0.0016F, y + 0.002F, RadarEchoField.overlay(0xCB9B12, 0.42F));
        RenderQuads.topLineUv(consumer, pose, 0.37F, 0.18F, 0.69F, 0.31F, 0.0016F, y + 0.002F, RadarEchoField.overlay(0xCB9B12, 0.42F));
        RenderQuads.topLineUv(consumer, pose, 0.69F, 0.31F, 0.91F, 0.27F, 0.0016F, y + 0.002F, RadarEchoField.overlay(0xCB9B12, 0.42F));
        RenderQuads.topLineUv(consumer, pose, 0.22F, 0.86F, 0.45F, 0.66F, 0.0016F, y + 0.002F, RadarEchoField.overlay(0xB11818, 0.38F));
        RenderQuads.topLineUv(consumer, pose, 0.45F, 0.66F, 0.78F, 0.72F, 0.0016F, y + 0.002F, RadarEchoField.overlay(0xB11818, 0.38F));
    }

    private static void drawMotionTrack(VertexConsumer consumer, PoseStack.Pose pose, RadarStormSnapshot storm, float rx, float rz, int range, float y) {
        double speed = Math.sqrt(storm.motionX() * storm.motionX() + storm.motionZ() * storm.motionZ());
        if (speed < 0.001D) {
            return;
        }
        float mx = RadarEchoField.motionTrackX(storm, range, 0.07F);
        float mz = RadarEchoField.motionTrackZ(storm, range, 0.07F);
        RenderQuads.topLineUv(consumer, pose, rx - mx, rz - mz, rx, rz, 0.004F, y, 0x82B7FFF2);
    }

    private static void drawReflectivityReturn(VertexConsumer consumer, PoseStack.Pose pose, RadarStormSnapshot storm, double originX, double originZ, float rx, float rz, float size, int range, float y) {
        float angle = RadarEchoField.motionAngle(storm);
        float cell = RadarEchoField.pixelCell();
        int cells = RadarEchoField.echoCells(size, cell);
        float cos = Mth.cos(angle);
        float sin = Mth.sin(angle);
        RadarEchoField.EchoAnchors anchors = RadarEchoField.anchorsFor(storm,
                RadarEchoField.scopeCoord(RadarEchoField.hookWorldX(storm), originX, range) - rx,
                RadarEchoField.scopeCoord(RadarEchoField.hookWorldZ(storm), originZ, range) - rz,
                RadarEchoField.scopeCoord(storm.hookX(), originX, range) - rx,
                RadarEchoField.scopeCoord(storm.hookZ(), originZ, range) - rz,
                cos, sin);

        // Sample the field once per lattice corner and share the samples between the cells that meet there. The
        // corner colours then give each cell a smooth gradient, and cells either side of a shared edge
        // interpolate to the same values along it, so the shield reads as one continuous field instead of a
        // mosaic of squares. This costs about what the old per-cell sampling cost: the lattice has roughly as
        // many corners as there used to be cells.
        int side = cells * 2 + 1;
        if (fieldScratch.length < side * side) {
            fieldScratch = new float[side * side];
        }
        float[] field = fieldScratch;
        for (int ix = 0; ix < side; ix++) {
            for (int iz = 0; iz < side; iz++) {
                float dx = (ix - cells) * cell;
                float dz = (iz - cells) * cell;
                field[iz * side + ix] = (float) RadarEchoField.reflectivityAt(storm,
                        dx * cos + dz * sin, -dx * sin + dz * cos, size, anchors, ix - cells, iz - cells);
            }
        }

        for (int ix = 0; ix < side - 1; ix++) {
            for (int iz = 0; iz < side - 1; iz++) {
                float minX = rx + (ix - cells) * cell;
                float minZ = rz + (iz - cells) * cell;
                float maxX = minX + cell;
                float maxZ = minZ + cell;
                if (!RadarEchoField.insidePanel(minX, minZ) && !RadarEchoField.insidePanel(maxX, maxZ)) {
                    continue;
                }
                float dbzMinMin = field[iz * side + ix];
                float dbzMaxMin = field[iz * side + ix + 1];
                float dbzMinMax = field[(iz + 1) * side + ix];
                float dbzMaxMax = field[(iz + 1) * side + ix + 1];
                int minMin = RadarEchoField.sampleColor(dbzMinMin);
                int maxMin = RadarEchoField.sampleColor(dbzMaxMin);
                int maxMax = RadarEchoField.sampleColor(dbzMaxMax);
                int minMax = RadarEchoField.sampleColor(dbzMinMax);
                // A cell is drawn when anything in it reaches the floor, and from there the four corner
                // colours fade toward the faintest a return may be, which is what gives the edge of a
                // shield its gradient instead of a cut-out.
                float peak = Math.max(Math.max(dbzMinMin, dbzMaxMin), Math.max(dbzMinMax, dbzMaxMax));
                if (peak < RadarEchoField.ECHO_FLOOR_DBZ) {
                    continue;
                }
                // One flat layer. Each cell used to be raised by its own peak reflectivity, which put a
                // step at every boundary between a strong cell and a weak one; the render type writes
                // colour only, so those steps separated nothing and were read as terracing across an
                // otherwise smooth shield. Strength is carried by the colour ramp alone now.
                RenderQuads.topQuadUvGradient(consumer, pose, minX, minZ, maxX, maxZ, y, minMin, maxMin, maxMax, minMax);
            }
        }
    }

    /**
     * A storm's reflectivity or velocity drawn from its own rain field and the wind in it, on the same shared lattice the
     * other returns use. This is the path a shader pack leaves; the program path reads the same two functions.
     */
    private static void drawFieldReturn(VertexConsumer consumer, PoseStack.Pose pose, RadarStormSnapshot storm, PrecipitationField.Geometry precipitation,
                                        boolean velocity, double originX, double originZ, float rx, float rz, int range, float y) {
        double blocksPerPanel = range / RadarEchoField.SCOPE_RADIUS;
        boolean line = storm.hasLineField();
        double reachBlocks = line ? RadarEchoField.lineReachBlocks(storm) : RadarEchoField.fieldReachBlocks(precipitation);
        float reachPanel = (float) (reachBlocks / blocksPerPanel);
        // Sampled every frame, so a squall line - many times a cell's reach - takes whole multiples of the lattice's cell
        // rather than more of them: it stays on the shared lattice, at a cell's cost.
        float cell = RadarEchoField.pixelCell() * Math.max(1, Mth.ceil(reachPanel / RadarEchoField.pixelCell() / 40.0F));
        int cells = Mth.clamp(Mth.ceil(reachPanel / cell), 8, 40);
        int side = cells * 2 + 1;
        if (fieldScratch.length < side * side) {
            fieldScratch = new float[side * side];
        }
        float[] field = fieldScratch;
        double radarX = originX + 0.5D;
        double radarZ = originZ + 0.5D;
        for (int ix = 0; ix < side; ix++) {
            for (int iz = 0; iz < side; iz++) {
                double worldX = storm.x() + (ix - cells) * cell * blocksPerPanel;
                double worldZ = storm.z() + (iz - cells) * cell * blocksPerPanel;
                if (line) {
                    field[iz * side + ix] = velocity
                            ? RadarEchoField.lineVelocityAtWorld(storm, worldX, worldZ, radarX, radarZ)
                            : (float) RadarEchoField.lineReflectivityAtWorld(storm, worldX, worldZ);
                } else {
                    field[iz * side + ix] = velocity
                            ? RadarEchoField.radialVelocityAtWorld(storm, precipitation, worldX, worldZ, radarX, radarZ)
                            : (float) RadarEchoField.reflectivityAtWorld(storm, precipitation, worldX, worldZ);
                }
            }
        }
        for (int ix = 0; ix < side - 1; ix++) {
            for (int iz = 0; iz < side - 1; iz++) {
                float minX = rx + (ix - cells) * cell;
                float minZ = rz + (iz - cells) * cell;
                float maxX = minX + cell;
                float maxZ = minZ + cell;
                if (!RadarEchoField.insidePanel(minX, minZ) && !RadarEchoField.insidePanel(maxX, maxZ)) {
                    continue;
                }
                float a = field[iz * side + ix];
                float b = field[iz * side + ix + 1];
                float c = field[(iz + 1) * side + ix];
                float d = field[(iz + 1) * side + ix + 1];
                if (velocity) {
                    if (Float.isNaN(a) || Float.isNaN(b) || Float.isNaN(c) || Float.isNaN(d)) {
                        continue;
                    }
                    if (Math.max(Math.max(Math.abs(a), Math.abs(b)), Math.max(Math.abs(c), Math.abs(d))) < RadarEchoField.VELOCITY_FLOOR_MPS) {
                        continue;
                    }
                    RenderQuads.topQuadUvGradient(consumer, pose, minX, minZ, maxX, maxZ, y + 0.001F,
                            RadarEchoField.velocityColor(a), RadarEchoField.velocityColor(b), RadarEchoField.velocityColor(d), RadarEchoField.velocityColor(c));
                } else {
                    if (Math.max(Math.max(a, b), Math.max(c, d)) < RadarEchoField.ECHO_FLOOR_DBZ) {
                        continue;
                    }
                    RenderQuads.topQuadUvGradient(consumer, pose, minX, minZ, maxX, maxZ, y,
                            RadarEchoField.sampleColor(a), RadarEchoField.sampleColor(b), RadarEchoField.sampleColor(d), RadarEchoField.sampleColor(c));
                }
            }
        }
    }

    private static void drawVelocityReturn(VertexConsumer consumer, PoseStack.Pose pose, RadarStormSnapshot storm, float rx, float rz, float size, float y) {
        float cell = RadarEchoField.pixelCell();
        int cells = RadarEchoField.velocityCells(size, cell);
        float rangeDistance = Math.max(0.001F, Mth.sqrt((rx - 0.5F) * (rx - 0.5F) + (rz - 0.5F) * (rz - 0.5F)));
        float beamX = (rx - 0.5F) / rangeDistance;
        float beamZ = (rz - 0.5F) / rangeDistance;
        float crossX = -beamZ;
        float crossZ = beamX;

        // The same shared lattice the reflectivity return uses: one sample per corner, four corner
        // colours per cell, and neighbours interpolating to the same value along their common edge. The
        // product used to be one flat quad per cell, which is what made a couplet read as a checkerboard.
        int side = cells * 2 + 1;
        if (velocityScratch.length < side * side) {
            velocityScratch = new float[side * side];
        }
        float[] field = velocityScratch;
        for (int ix = 0; ix < side; ix++) {
            for (int iz = 0; iz < side; iz++) {
                field[iz * side + ix] = RadarEchoField.radialVelocityAt(storm, (ix - cells) * cell, (iz - cells) * cell, size, beamX, beamZ, crossX, crossZ);
            }
        }

        for (int ix = 0; ix < side - 1; ix++) {
            for (int iz = 0; iz < side - 1; iz++) {
                float minX = rx + (ix - cells) * cell;
                float minZ = rz + (iz - cells) * cell;
                float maxX = minX + cell;
                float maxZ = minZ + cell;
                if (!RadarEchoField.insidePanel(minX, minZ) && !RadarEchoField.insidePanel(maxX, maxZ)) {
                    continue;
                }
                float peak = Math.max(Math.max(Math.abs(field[iz * side + ix]), Math.abs(field[iz * side + ix + 1])),
                        Math.max(Math.abs(field[(iz + 1) * side + ix]), Math.abs(field[(iz + 1) * side + ix + 1])));
                if (peak < RadarEchoField.VELOCITY_FLOOR_MPS) {
                    continue;
                }
                int minMin = RadarEchoField.velocityColor(field[iz * side + ix]);
                int maxMin = RadarEchoField.velocityColor(field[iz * side + ix + 1]);
                int minMax = RadarEchoField.velocityColor(field[(iz + 1) * side + ix]);
                int maxMax = RadarEchoField.velocityColor(field[(iz + 1) * side + ix + 1]);
                RenderQuads.topQuadUvGradient(consumer, pose, minX, minZ, maxX, maxZ, y + 0.001F, minMin, maxMin, maxMax, minMax);
            }
        }
    }

    private static void drawRiskPixels(VertexConsumer consumer, PoseStack.Pose pose, RadarStormSnapshot storm, float rx, float rz, float size, float y) {
        float cell = RadarEchoField.pixelCell();
        int cells = RadarEchoField.riskCells(size, cell);
        int color = RadarEchoField.riskColor(storm);
        for (int ix = -cells; ix <= cells; ix++) {
            for (int iz = -cells; iz <= cells; iz++) {
                float radial = Mth.sqrt((float) (ix * ix + iz * iz)) / cells;
                if (radial > 1.0F) {
                    continue;
                }
                // Solid through the middle, dithered only where the disc is running out. The old test
                // dropped one pixel in six wherever it liked, so every contour was speckled through its
                // own centre - and a risk area is a filled shape, not a stipple.
                float edge = Mth.clamp((1.0F - radial) / 0.28F, 0.0F, 1.0F);
                if (edge < 0.999F && RadarEchoField.pixelNoise(storm.id(), ix, iz) * 0.5F + 0.5F > edge) {
                    continue;
                }
                float sx = rx + ix * cell;
                float sz = rz + iz * cell;
                if (RadarEchoField.insidePanel(sx, sz)) {
                    drawPixelCell(consumer, pose, sx, sz, cell, y + 0.001F, color);
                }
            }
        }
    }

    private static void drawSubVortexMarkers(VertexConsumer consumer, PoseStack.Pose pose, RadarStormSnapshot storm, float rx, float rz, float size, float y) {
        int count = Mth.clamp(storm.subVortices(), 1, 10);
        for (int i = 0; i < count; i++) {
            float angle = (float) (i * Math.PI * 2.0D / count);
            float ox = Mth.cos(angle) * size * 1.55F;
            float oz = Mth.sin(angle) * size * 1.55F;
            float dot = Math.max(0.006F, size * 0.22F);
            RenderQuads.topQuadUv(consumer, pose, rx + ox - dot, rz + oz - dot, rx + ox + dot, rz + oz + dot, y, 0xEFFFF4B8);
        }
    }

    private static void drawTornadoMarker(VertexConsumer consumer, PoseStack.Pose pose, float rx, float rz, float size, float y) {
        int color = 0xD9FFF9C8;
        RenderQuads.topLineUv(consumer, pose, rx - size * 1.35F, rz, rx + size * 1.35F, rz, 0.0035F, y, color);
        RenderQuads.topLineUv(consumer, pose, rx, rz - size * 1.35F, rx, rz + size * 1.35F, 0.0035F, y + 0.001F, color);
        float ring = Math.max(0.010F, size * 0.42F);
        RenderQuads.topLineUv(consumer, pose, rx - ring, rz - ring, rx + ring, rz - ring, 0.0025F, y + 0.002F, color);
        RenderQuads.topLineUv(consumer, pose, rx + ring, rz - ring, rx + ring, rz + ring, 0.0025F, y + 0.002F, color);
        RenderQuads.topLineUv(consumer, pose, rx + ring, rz + ring, rx - ring, rz + ring, 0.0025F, y + 0.002F, color);
        RenderQuads.topLineUv(consumer, pose, rx - ring, rz + ring, rx - ring, rz - ring, 0.0025F, y + 0.002F, color);
    }

    private static void drawMesocycloneMarker(VertexConsumer consumer, PoseStack.Pose pose, float rx, float rz, float size, float y) {
        int color = 0xCFC6F6FF;
        float ring = Math.max(0.010F, size * 0.34F);
        RenderQuads.topLineUv(consumer, pose, rx - ring, rz - ring, rx + ring, rz - ring, 0.0022F, y, color);
        RenderQuads.topLineUv(consumer, pose, rx + ring, rz - ring, rx + ring, rz + ring, 0.0022F, y + 0.001F, color);
        RenderQuads.topLineUv(consumer, pose, rx + ring, rz + ring, rx - ring, rz + ring, 0.0022F, y + 0.001F, color);
        RenderQuads.topLineUv(consumer, pose, rx - ring, rz + ring, rx - ring, rz - ring, 0.0022F, y, color);
    }

    /**
     * A vortex signature icon: the filled triangle a real display uses for a TVS, or the smaller diamond it
     * uses for a mesocyclone.
     *
     * <p>Both are drawn as a larger dark shape with the bright one on top of it, which is how an icon gets its
     * outline - these programs are drawn with one flat vertex colour per vertex and have no stroke to call.
     * The size does not scale with the storm, because an icon is a symbol rather than a measurement: on a real
     * scope a TVS is the same size whether the storm under it is small or enormous.
     */
    private static void drawVortexIcon(VertexConsumer consumer, PoseStack.Pose pose, float centerX, float centerZ, int tier, float y) {
        float half = RadarEchoField.signatureHalf(tier);
        if (tier == RadarEchoField.SIGNATURE_MESO) {
            // A mesocyclone is a circle, as on the radar's own overlay: an open ring, so the couplet under it shows.
            float radius = half * 1.10F;
            drawRing(consumer, pose, centerX, centerZ, radius, 0.0070F, y, RadarEchoField.SIGNATURE_OUTLINE_COLOR);
            drawRing(consumer, pose, centerX, centerZ, radius, 0.0040F, y + 0.0007F, RadarEchoField.MESO_ICON_COLOR);
            return;
        }
        // A TVS, and an elevated one, are an inverted triangle: point down, toward where the vortex reaches the ground.
        float height = half * (RadarEchoField.SIGNATURE_HEIGHT / RadarEchoField.SIGNATURE_HALF);
        drawDownTriangle(consumer, pose, centerX, centerZ, half * 1.34F, height * 1.16F, y, RadarEchoField.SIGNATURE_OUTLINE_COLOR);
        drawDownTriangle(consumer, pose, centerX, centerZ, half, height, y + 0.0007F, RadarEchoField.signatureColor(tier));
    }

    /**
     * An inverted triangle on the panel, its point toward +z: the map's "down", as {@link #drawUpTriangle}'s point is its
     * "up". The base corners are given in the other order so it winds the same way.
     */
    private static void drawDownTriangle(VertexConsumer consumer, PoseStack.Pose pose, float centerX, float centerZ, float half, float height, float y, int color) {
        RenderQuads.topTriangleUv(consumer, pose,
                centerX, centerZ + height,
                centerX + half, centerZ - height * 0.42F,
                centerX - half, centerZ - height * 0.42F,
                y, color);
    }

    /** A ring on the panel, out of short line segments. */
    private static void drawRing(VertexConsumer consumer, PoseStack.Pose pose, float centerX, float centerZ, float radius, float width, float y, int color) {
        int segments = 18;
        for (int i = 0; i < segments; i++) {
            float a0 = (float) (Math.PI * 2.0D * i / segments);
            float a1 = (float) (Math.PI * 2.0D * (i + 1) / segments);
            RenderQuads.topLineUv(consumer, pose, centerX + Mth.cos(a0) * radius, centerZ + Mth.sin(a0) * radius,
                    centerX + Mth.cos(a1) * radius, centerZ + Mth.sin(a1) * radius, width, y, color);
        }
    }

    /**
     * An upward triangle on the panel: apex at {@code height} above the centre, base {@code 0.42 * height}
     * below it.
     *
     * <p>It points toward -z. A top face has no single "up" on screen - it depends on which side of the block
     * the player is standing - so the icon takes the map's own convention instead of trying to face the reader,
     * and a storm's icon therefore does not swing around as you walk past the radar.
     */
    private static void drawUpTriangle(VertexConsumer consumer, PoseStack.Pose pose, float centerX, float centerZ, float half, float height, float y, int color) {
        RenderQuads.topTriangleUv(consumer, pose,
                centerX, centerZ - height,
                centerX - half, centerZ + height * 0.42F,
                centerX + half, centerZ + height * 0.42F,
                y, color);
    }

    /** A diamond on the panel, with its corners {@code axis} from the centre along each axis. */
    private static void diamond(VertexConsumer consumer, PoseStack.Pose pose, float centerX, float centerZ, float axis, float y, int color) {
        // The rotated square primitive turns a corner onto the axes at sqrt(2) times its half-extent.
        float halfExtent = axis * 0.70711F;
        RenderQuads.topRotatedQuadUv(consumer, pose, centerX, centerZ, halfExtent, halfExtent, (float) (Math.PI / 4.0D), y, color);
    }

    private static void drawPixelCell(VertexConsumer consumer, PoseStack.Pose pose, float centerX, float centerZ, float cell, float y, int color) {
        float half = cell * 0.50F;
        RenderQuads.topQuadUv(consumer, pose, centerX - half, centerZ - half, centerX + half, centerZ + half, y, color);
    }

    @Override
    public boolean shouldRenderOffScreen(RadarBlockEntity blockEntity) {
        return true;
    }
}
