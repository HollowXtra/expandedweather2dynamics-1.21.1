package com.imbet.client;

import com.imbet.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Leans vanilla's own rain and snow sheets with the wind the foliage sways in.
 *
 * <p>Vanilla draws precipitation as vertical quads, ten or twenty blocks tall, rooted on the surface
 * heightmap and standing on top of it - a column of falling water with nothing said about which way
 * the air is moving. That is the one thing a storm can be relied on to change: rain in an inflow
 * comes at you sideways. So the vertices of those quads are displaced here, and only displaced -
 * the sprite, the alpha, the falling offset, the biome test, the light lookup and the depth state
 * are all still vanilla's, which is why this adds a lean rather than a second rain system.
 *
 * <p>The displacement is a <b>shear</b>, not a translation: a vertex is moved horizontally in
 * proportion to how far it sits above the ground beneath it, so a sheet's foot stays planted where
 * its drops land and its top is carried downwind. The rate is the physical one - a drop falls at
 * its terminal velocity while the wind carries it across, so the lean is
 * {@code height * windSpeed / fallSpeed}, which is why a stiff wind tips rain nearly flat and a
 * breeze barely moves it. {@code windBendStrength} scales that rate for taste and a cap stops a
 * violent inflow from flattening the sheets into a horizontal smear.
 *
 * <p>The wind comes from {@link ClientWindSampler} - the same sampler the foliage sway reads, so the
 * grass and the rain lean in the same air rather than each having its own opinion about it - and it
 * is read per four-block cell, cached for the frame, because the rain sheet is 441 columns and a
 * full sampler read per vertex would be thousands of them. A cached cell holds the wind and the
 * ground height together, so the heightmap is read once per cell per frame as well; the ground
 * height is what turns a camera-relative vertex into a height above the surface.
 *
 * <p>The vertices handed in are camera-relative (vanilla subtracts the camera position as it emits
 * them), so the camera position captured at the head of the pass is what puts them back in the
 * world.
 */
public final class RainWindBend {

    /** Terminal fall speed of a raindrop, in metres per second. */
    private static final double FALL_SPEED_METERS_PER_SECOND = 9.0D;

    private static final double MPH_TO_METERS_PER_SECOND = 0.44704D;

    /** Blocks per cached cell, as a power of two so the cell key is a shift. */
    private static final int CELL_SHIFT = 2;

    private static final int CELL_BLOCKS = 1 << CELL_SHIFT;

    /** How far the sampler looks for wind. A storm cell is far wider than this; the point is to find
     * the tornado funnel whose inflow is overhead, not to see every storm on the client. */
    private static final int WIND_RANGE_BLOCKS = 256;

    /** Direct-mapped, so a collision costs a re-sample rather than an eviction policy. */
    private static final int CACHE_SIZE = 256;

    private static final int CACHE_MASK = CACHE_SIZE - 1;

    private static final long[] CELL_KEYS = new long[CACHE_SIZE];
    private static final double[] CELL_WIND_X = new double[CACHE_SIZE];
    private static final double[] CELL_WIND_Z = new double[CACHE_SIZE];
    private static final int[] CELL_GROUND_Y = new int[CACHE_SIZE];
    private static final boolean[] CELL_VALID = new boolean[CACHE_SIZE];

    private static double camX;
    private static double camY;
    private static double camZ;

    /** The pass's settings, read once per frame: the pass asks per vertex, and a config spec read per vertex
     * would be thousands of them for one sheet. */
    private static boolean active;
    private static double strength;
    private static double maxLeanBlocks;

    /** Vertices displaced and columns sampled in the last frame, for verification. */
    private static long lastBentVertices;
    private static long lastSampledCells;

    private RainWindBend() {
    }

    /**
     * Starts a precipitation pass: the camera its vertices are relative to, and an empty cell cache.
     *
     * <p>Called from the head of vanilla's own rain pass, so everything the redirect sees afterwards
     * belongs to this frame and to this camera.
     */
    public static void beginFrame(double cameraX, double cameraY, double cameraZ) {
        camX = cameraX;
        camY = cameraY;
        camZ = cameraZ;
        strength = Config.VANILLA_RAIN_WIND_BEND_STRENGTH.get();
        maxLeanBlocks = Config.VANILLA_RAIN_WIND_BEND_MAX_LEAN_BLOCKS.get();
        active = enabled();
        lastBentVertices = 0L;
        lastSampledCells = 0L;
        for (int i = 0; i < CACHE_SIZE; i++) {
            CELL_VALID[i] = false;
        }
    }

    /**
     * The position a precipitation vertex is drawn at, with the wind's lean added.
     *
     * <p>Returns the point unchanged whenever the feature is off, the wind is below a walking pace,
     * the column has no ground under it, or the vertex is at or below the surface - a vertex on the
     * ground is the pivot the sheet leans about and must not move.
     */
    public static Vec3 bend(float vertexX, float vertexY, float vertexZ) {
        if (!active) {
            return new Vec3(vertexX, vertexY, vertexZ);
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return new Vec3(vertexX, vertexY, vertexZ);
        }
        double worldX = camX + vertexX;
        double worldY = camY + vertexY;
        double worldZ = camZ + vertexZ;

        int cellX = Mth.floor(worldX) >> CELL_SHIFT;
        int cellZ = Mth.floor(worldZ) >> CELL_SHIFT;
        int slot = cellSlot(cellX, cellZ);
        if (!CELL_VALID[slot] || CELL_KEYS[slot] != cellKey(cellX, cellZ)) {
            sample(level, slot, cellX, cellZ);
        }
        if (!CELL_VALID[slot]) {
            return new Vec3(vertexX, vertexY, vertexZ);
        }

        double height = worldY - CELL_GROUND_Y[slot];
        if (height <= 0.0D) {
            return new Vec3(vertexX, vertexY, vertexZ);
        }
        double speed = Math.hypot(CELL_WIND_X[slot], CELL_WIND_Z[slot]);
        if (speed <= 0.5D) {
            return new Vec3(vertexX, vertexY, vertexZ);
        }
        double rate = strength * speed * MPH_TO_METERS_PER_SECOND / FALL_SPEED_METERS_PER_SECOND;
        double lean = Math.min(maxLeanBlocks, height * rate);
        if (lean <= 0.01D) {
            return new Vec3(vertexX, vertexY, vertexZ);
        }
        lastBentVertices++;
        double directionX = CELL_WIND_X[slot] / speed;
        double directionZ = CELL_WIND_Z[slot] / speed;
        return new Vec3(vertexX + directionX * lean, vertexY, vertexZ + directionZ * lean);
    }

    /**
     * Whether the sheets are being bent at all.
     *
     * <p>The classic look is left out of it: that mode is 1.12.2's own precipitation, which had no bend
     * to it, and a mod's rain leaning in a world set up to look like that one would be the one thing
     * there that is out of period.
     */
    private static boolean enabled() {
        return Config.VANILLA_RAIN_ENABLED.get() && Config.VANILLA_RAIN_WIND_BEND.get()
                && !Config.classicLook()
                && strength > 0.0D && maxLeanBlocks > 0.0D;
    }

    /** Reads the wind and the ground height for one cell. A cell with no air moving in it is not cached. */
    private static void sample(ClientLevel level, int slot, int cellX, int cellZ) {
        CELL_VALID[slot] = false;
        int centreX = (cellX << CELL_SHIFT) + CELL_BLOCKS / 2;
        int centreZ = (cellZ << CELL_SHIFT) + CELL_BLOCKS / 2;
        // The ground the drops land on is the one vanilla rooted the sheet to: the same heightmap the
        // pass itself used, so a sheet's foot stays exactly where the vanilla quad put it.
        int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, centreX, centreZ);
        ClientWindSampler.Wind wind = ClientWindSampler.sample(level, new Vec3(centreX, groundY, centreZ), WIND_RANGE_BLOCKS);
        lastSampledCells++;
        if (wind == null || wind.speedMph() <= 0.5D) {
            // Cached as a miss: the reading is stable for a frame, and re-sampling calm air would cost
            // the same as sampling windy air for nothing.
            CELL_KEYS[slot] = cellKey(cellX, cellZ);
            CELL_WIND_X[slot] = 0.0D;
            CELL_WIND_Z[slot] = 0.0D;
            CELL_GROUND_Y[slot] = groundY;
            CELL_VALID[slot] = true;
            return;
        }
        CELL_KEYS[slot] = cellKey(cellX, cellZ);
        CELL_WIND_X[slot] = wind.vectorMph().x;
        CELL_WIND_Z[slot] = wind.vectorMph().z;
        CELL_GROUND_Y[slot] = groundY;
        CELL_VALID[slot] = true;
    }

    private static int cellSlot(int cellX, int cellZ) {
        return (cellX * 31 + cellZ * 17) & CACHE_MASK;
    }

    private static long cellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
    }

    /** Vertices displaced during the last pass. */
    public static long lastBentVertices() {
        return lastBentVertices;
    }

    /** Cells whose wind and ground height were read during the last pass. */
    public static long lastSampledCells() {
        return lastSampledCells;
    }
}
