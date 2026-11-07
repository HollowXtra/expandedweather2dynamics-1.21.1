package com.imbet.client;

import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.radar.RadarStormSnapshot;
import com.imbet.weather.PrecipitationField;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * One radar's picture as a single sheet of product values.
 *
 * <p>Both displays used to walk every storm's lattice of cells per frame and write the colours out as geometry, so the
 * picture was as many separate shapes as there were storms: overlapping shields drew over one another rather than
 * merging, and a scope in front of a supercell paid for that storm's whole reflectivity function sixty times a second.
 * A radar does not work like that. It has one field of values per scan, and a display colours it.
 *
 * <p>So does this. A scan composites every storm into one sheet by taking the strongest return at each texel, which is
 * also how a real composite treats overlapping echoes. It happens off the render thread and only when new data
 * arrives, so a scope costs one quad to draw whatever it is looking at. The red channel is this scan and the green
 * the one before, so {@code radar_scope.fsh} can paint the new data in behind the beam.
 *
 * <p>Only a few are ever alive, least recently used first out, because only a few radars can be on screen.
 */
final class RadarFieldTexture {
    /** Texels across the range circle's diameter. */
    static final int SIZE = 192;
    private static final int MAX_LIVE = 6;

    /** How long the beam takes to come round, in ticks. */
    private static final float SWEEP_TICKS = 120.0F;

    private static final Map<BlockPos, RadarFieldTexture> LIVE = new LinkedHashMap<>(8, 0.75F, true);
    private static int nextSlot;

    private final ResourceLocation id;
    private final DynamicTexture texture;
    private List<RadarStormSnapshot> lastStorms;
    private int lastMode = Integer.MIN_VALUE;
    private int lastRange = Integer.MIN_VALUE;
    private CompletableFuture<float[]> pending;
    private int pendingMode = Integer.MIN_VALUE;
    private int pendingRange = Integer.MIN_VALUE;
    private float[] current = new float[SIZE * SIZE];
    /** The product and range the scan on the sheet was made for. */
    private int currentMode = Integer.MIN_VALUE;
    private int currentRange = Integer.MIN_VALUE;

    /** Where the beam is, and where it was when the data on the sheet arrived. Radians. */
    private float sweepAngle;
    private float scanStart;
    private float scanCovered = (float) (Math.PI * 2.0D);
    private long lastTick = Long.MIN_VALUE;
    private float lastPartial;

    private double displayX;
    private double displayZ;
    private int displayRange = 1;
    private int displayMode;
    private float displayRing = 1.0F;
    private float displayGlass;
    private boolean displayChart;

    private RadarFieldTexture() {
        id = ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "dynamic/radar_field_" + nextSlot++);
        NativeImage image = new NativeImage(SIZE, SIZE, false);
        image.fillRect(0, 0, SIZE, SIZE, 0xFF000000);
        texture = new DynamicTexture(image);
        texture.setFilter(true, false);
        texture.upload();
        Minecraft.getInstance().getTextureManager().register(id, texture);
    }

    /** The sheet for a radar at a position, created on demand and brought up to date with what it was handed. */
    static RadarFieldTexture forRadar(BlockPos origin, List<RadarStormSnapshot> storms, int mode, int range, long tick, float partial) {
        RadarFieldTexture field = LIVE.get(origin);
        if (field == null || Minecraft.getInstance().getTextureManager().getTexture(field.id, null) == null) {
            // A resource reload empties the texture manager, so a sheet it no longer holds is rebuilt, not kept.
            if (LIVE.size() >= MAX_LIVE && field == null) {
                Iterator<Map.Entry<BlockPos, RadarFieldTexture>> oldest = LIVE.entrySet().iterator();
                RadarFieldTexture evicted = oldest.next().getValue();
                oldest.remove();
                Minecraft.getInstance().getTextureManager().release(evicted.id);
            }
            field = new RadarFieldTexture();
            LIVE.put(origin.immutable(), field);
        }
        field.update(origin, storms, mode, range, tick, partial);
        return field;
    }

    ResourceLocation id() {
        return id;
    }

    int textureId() {
        return texture.getId();
    }

    /** What this sheet is being shown as, for a draw that happens later when the buffer batch ends. */
    void display(double worldX, double worldZ, int range, int mode, float ringRadius, float glass, boolean chart) {
        displayX = worldX;
        displayZ = worldZ;
        displayRange = range;
        displayMode = mode;
        displayRing = ringRadius;
        displayGlass = glass;
        displayChart = chart;
    }

    void applyDisplay() {
        RadarScopeShader.configure(this, displayX, displayZ, displayRange, displayMode, displayRing, displayGlass, displayChart);
    }

    float sweepAngle() {
        return sweepAngle;
    }

    float scanStart() {
        return scanStart;
    }

    float scanCovered() {
        return scanCovered;
    }

    private void update(BlockPos origin, List<RadarStormSnapshot> storms, int mode, int range, long tick, float partial) {
        advanceBeam(tick, partial);
        if (pending != null && pending.isDone()) {
            float[] next = pending.getNow(null);
            pending = null;
            if (next != null) {
                publish(next, pendingMode, pendingRange);
            }
        }
        if (pending == null && (storms != lastStorms || mode != lastMode || range != lastRange)) {
            lastStorms = storms;
            lastMode = mode;
            lastRange = range;
            pendingMode = mode;
            pendingRange = range;
            double originX = origin.getX();
            double originZ = origin.getZ();
            pending = CompletableFuture.supplyAsync(() -> composite(storms, mode, range, originX, originZ), Util.backgroundExecutor());
        }
    }

    private void advanceBeam(long tick, float partial) {
        if (lastTick != Long.MIN_VALUE) {
            float elapsed = Math.max(0.0F, Math.min(tick - lastTick + partial - lastPartial, 20.0F));
            float turned = elapsed * (float) (Math.PI * 2.0D) / SWEEP_TICKS;
            sweepAngle = (sweepAngle + turned) % (float) (Math.PI * 2.0D);
            scanCovered = Math.min(scanCovered + turned, (float) (Math.PI * 2.0D));
        }
        lastTick = tick;
        lastPartial = partial;
    }

    /**
     * Puts a finished scan on the sheet, keeping the last one beside it for the beam to paint over.
     *
     * <p>Only a scan of the same product at the same range is painted over. A display switched to another product or range
     * shows it at once, from the last volume scan, as a real one does: painted over the old scan, the part the beam had not
     * reached yet showed the last product read as the new one - reflectivity coloured as velocity, in reds and greens that
     * were not there.
     */
    private void publish(float[] next, int mode, int range) {
        boolean sameProduct = mode == currentMode && range == currentRange;
        currentMode = mode;
        currentRange = range;
        NativeImage image = texture.getPixels();
        if (image == null) {
            return;
        }
        float[] previous = sameProduct ? current : next;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int index = y * SIZE + x;
                int cur = Mth.clamp(Math.round(next[index] * 255.0F), 0, 255);
                int old = Mth.clamp(Math.round(previous[index] * 255.0F), 0, 255);
                // NativeImage holds ABGR: red is the low byte.
                image.setPixelRGBA(x, y, 0xFF000000 | old << 8 | cur);
            }
        }
        texture.upload();
        current = next;
        scanStart = sweepAngle;
        scanCovered = sameProduct ? 0.0F : (float) (Math.PI * 2.0D);
    }

    /**
     * Every storm on the sweep, as one sheet.
     *
     * <p>Values are normalised for the sheet: reflectivity as {@code (dBZ + 5) / 80}, velocity as {@code (v + 64) / 128},
     * and risk as severity over eight. Nought means no return at all, which is why each is lifted just clear of it.
     */
    private static float[] composite(List<RadarStormSnapshot> storms, int mode, int range, double originX, double originZ) {
        float[] out = new float[SIZE * SIZE];
        float scale = RadarEchoField.SCOPE_RADIUS * 2.0F / SIZE;
        for (RadarStormSnapshot storm : storms) {
            float centreX = RadarEchoField.scopeOffsetUnclamped(storm.x(), originX, range);
            float centreZ = RadarEchoField.scopeOffsetUnclamped(storm.z(), originZ, range);
            switch (mode) {
                case 1 -> compositeVelocity(out, storm, centreX, centreZ, scale, range, originX, originZ);
                case 2 -> compositeRisk(out, storm, centreX, centreZ, scale, range);
                default -> compositeReflectivity(out, storm, centreX, centreZ, scale, range, originX, originZ);
            }
        }
        return out;
    }

    private static void compositeReflectivity(float[] out, RadarStormSnapshot storm, float centreX, float centreZ, float scale, int range, double originX, double originZ) {
        if (storm.hasLineField()) {
            double blocksPerPanel = range / RadarEchoField.SCOPE_RADIUS;
            float reach = (float) (RadarEchoField.lineReachBlocks(storm) / blocksPerPanel);
            forEachTexel(centreX, centreZ, reach, scale, (index, dx, dz) -> {
                float dbz = (float) RadarEchoField.lineReflectivityAtWorld(storm, storm.x() + dx * blocksPerPanel, storm.z() + dz * blocksPerPanel);
                if (dbz >= RadarEchoField.ECHO_FLOOR_DBZ) {
                    float value = Mth.clamp((dbz + 5.0F) / 80.0F, 0.01F, 1.0F);
                    if (value > out[index]) {
                        out[index] = value;
                    }
                }
            });
            return;
        }
        PrecipitationField.Geometry field = RadarEchoField.fieldOf(storm);
        if (field != null) {
            // Read off the rain that is really there, a texel at a time in world blocks.
            double blocksPerPanel = range / RadarEchoField.SCOPE_RADIUS;
            float reach = (float) (RadarEchoField.fieldReachBlocks(field) / blocksPerPanel);
            forEachTexel(centreX, centreZ, reach, scale, (index, dx, dz) -> {
                float dbz = (float) RadarEchoField.reflectivityAtWorld(storm, field, storm.x() + dx * blocksPerPanel, storm.z() + dz * blocksPerPanel);
                if (dbz >= RadarEchoField.ECHO_FLOOR_DBZ) {
                    float value = Mth.clamp((dbz + 5.0F) / 80.0F, 0.01F, 1.0F);
                    if (value > out[index]) {
                        out[index] = value;
                    }
                }
            });
            return;
        }
        float size = RadarEchoField.returnSize(storm, range);
        if (size <= 0.0F) {
            return;
        }
        float angle = RadarEchoField.motionAngle(storm);
        float cos = Mth.cos(angle);
        float sin = Mth.sin(angle);
        RadarEchoField.EchoAnchors anchors = RadarEchoField.anchorsFor(storm,
                RadarEchoField.scopeOffsetUnclamped(RadarEchoField.hookWorldX(storm), originX, range) - centreX,
                RadarEchoField.scopeOffsetUnclamped(RadarEchoField.hookWorldZ(storm), originZ, range) - centreZ,
                RadarEchoField.scopeOffsetUnclamped(storm.hookX(), originX, range) - centreX,
                RadarEchoField.scopeOffsetUnclamped(storm.hookZ(), originZ, range) - centreZ,
                cos, sin);
        float reach = size * 3.1F;
        forEachTexel(centreX, centreZ, reach, scale, (index, dx, dz) -> {
            float dbz = (float) RadarEchoField.reflectivityAt(storm, dx * cos + dz * sin, -dx * sin + dz * cos, size, anchors);
            if (dbz >= RadarEchoField.ECHO_FLOOR_DBZ) {
                float value = Mth.clamp((dbz + 5.0F) / 80.0F, 0.01F, 1.0F);
                if (value > out[index]) {
                    out[index] = value;
                }
            }
        });
    }

    private static void compositeVelocity(float[] out, RadarStormSnapshot storm, float centreX, float centreZ, float scale, int range,
                                          double originX, double originZ) {
        if (storm.hasLineField()) {
            double blocksPerPanel = range / RadarEchoField.SCOPE_RADIUS;
            float reach = (float) (RadarEchoField.lineReachBlocks(storm) / blocksPerPanel);
            double radarX = originX + 0.5D;
            double radarZ = originZ + 0.5D;
            forEachTexel(centreX, centreZ, reach, scale, (index, dx, dz) -> {
                float v = RadarEchoField.lineVelocityAtWorld(storm, storm.x() + dx * blocksPerPanel, storm.z() + dz * blocksPerPanel, radarX, radarZ);
                if (!Float.isNaN(v) && Math.abs(v) >= RadarEchoField.VELOCITY_FLOOR_MPS) {
                    float value = Mth.clamp((v + 64.0F) / 128.0F, 0.01F, 1.0F);
                    if (out[index] == 0.0F || Math.abs(value - 0.5F) > Math.abs(out[index] - 0.5F)) {
                        out[index] = value;
                    }
                }
            });
            return;
        }
        PrecipitationField.Geometry field = RadarEchoField.fieldOf(storm);
        if (field != null) {
            // The wind in the storm along the beam from the radar, wherever there is rain to measure it in.
            double blocksPerPanel = range / RadarEchoField.SCOPE_RADIUS;
            float reach = (float) (RadarEchoField.fieldReachBlocks(field) / blocksPerPanel);
            double radarX = originX + 0.5D;
            double radarZ = originZ + 0.5D;
            forEachTexel(centreX, centreZ, reach, scale, (index, dx, dz) -> {
                float v = RadarEchoField.radialVelocityAtWorld(storm, field, storm.x() + dx * blocksPerPanel, storm.z() + dz * blocksPerPanel, radarX, radarZ);
                if (!Float.isNaN(v) && Math.abs(v) >= RadarEchoField.VELOCITY_FLOOR_MPS) {
                    float value = Mth.clamp((v + 64.0F) / 128.0F, 0.01F, 1.0F);
                    if (out[index] == 0.0F || Math.abs(value - 0.5F) > Math.abs(out[index] - 0.5F)) {
                        out[index] = value;
                    }
                }
            });
            return;
        }
        float size = Math.max(RadarEchoField.returnSize(storm, range), 0.030F);
        float length = Math.max(0.001F, Mth.sqrt(centreX * centreX + centreZ * centreZ));
        float beamX = centreX / length;
        float beamZ = centreZ / length;
        forEachTexel(centreX, centreZ, size * 1.6F, scale, (index, dx, dz) -> {
            float v = RadarEchoField.radialVelocityAt(storm, dx, dz, size, beamX, beamZ, -beamZ, beamX);
            if (Math.abs(v) >= RadarEchoField.VELOCITY_FLOOR_MPS) {
                float value = Mth.clamp((v + 64.0F) / 128.0F, 0.01F, 1.0F);
                // The strongest motion wins whichever way it is going.
                if (out[index] == 0.0F || Math.abs(value - 0.5F) > Math.abs(out[index] - 0.5F)) {
                    out[index] = value;
                }
            }
        });
    }

    private static void compositeRisk(float[] out, RadarStormSnapshot storm, float centreX, float centreZ, float scale, int range) {
        float size = Math.max(RadarEchoField.returnSize(storm, range), 0.030F) * 1.7F;
        float value = Mth.clamp((storm.severity() + 0.5F) / 8.0F, 0.01F, 1.0F);
        forEachTexel(centreX, centreZ, size, scale, (index, dx, dz) -> {
            if (dx * dx + dz * dz <= size * size && value > out[index]) {
                out[index] = value;
            }
        });
    }

    private interface TexelVisitor {
        void visit(int index, float dx, float dz);
    }

    private static void forEachTexel(float centreX, float centreZ, float reach, float scale, TexelVisitor visitor) {
        float half = SIZE * 0.5F;
        int minX = Math.max(0, Mth.floor((centreX - reach) / scale + half));
        int maxX = Math.min(SIZE - 1, Mth.ceil((centreX + reach) / scale + half));
        int minZ = Math.max(0, Mth.floor((centreZ - reach) / scale + half));
        int maxZ = Math.min(SIZE - 1, Mth.ceil((centreZ + reach) / scale + half));
        for (int z = minZ; z <= maxZ; z++) {
            float texelZ = (z + 0.5F - half) * scale;
            for (int x = minX; x <= maxX; x++) {
                float texelX = (x + 0.5F - half) * scale;
                visitor.visit(z * SIZE + x, texelX - centreX, texelZ - centreZ);
            }
        }
    }
}
