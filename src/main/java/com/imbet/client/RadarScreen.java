package com.imbet.client;

import com.imbet.Config;
import com.imbet.radar.ClientRadarStore;
import com.imbet.radar.RadarBlockEntity;
import com.imbet.radar.RadarMenu;
import com.imbet.network.RadarSnapshotPayload;
import com.imbet.radar.RadarStormSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The portable radar's screen: a plan-position indicator for whatever radar it is showing.
 *
 * <p>It is not a radar of its own. Everything on the scope - where a storm sits, how wide its echo is, where
 * its hook and inflow notch curl, whether the return is a hail core or a debris ball - comes from
 * {@link RadarEchoField}, the same maths the weather radar block draws on its face, and the feed being shown
 * is a weather radar block's own feed when one was in reach. A handheld and a block therefore show one
 * picture rather than two, which they did not while each carried a private copy of the product maths: the
 * panel drew a smooth echo at the storm's true position and this screen drew a flat square near it.
 *
 * <p>Drawn with plain fills because a GUI has no vertex colours to interpolate; the field is sampled two
 * pixels a cell, close to the resolution the block's face uses, so it reads as the same shield.
 */
public class RadarScreen extends AbstractContainerScreen<RadarMenu> {
    private static final int PANEL = 0xEE0A1513;
    private static final int BORDER = 0xFF5DB6B2;
    private static final int TEXT = 0xFFE6F3F1;
    private static final int DIM_TEXT = 0xFF9FD5D1;

    /** The box the round screen fills, and where it sits inside the panel. */
    private static final int SCOPE_SIZE = 144;
    private static final int SCOPE_INSET_X = 12;
    private static final int SCOPE_INSET_Y = 20;
    private static final float SCOPE_RADIUS_PX = SCOPE_SIZE / 2.0F;

    /**
     * The pixel radius the configured range lands on.
     *
     * <p>From the block program's own proportions: the radar range is mapped onto 0.43 of the panel while the
     * screen under it reaches 0.445, so the range circle sits just inside the rim - the same margin the block
     * face has, which is what keeps a distance on this screen meaning the same distance as on that one.
     */
    private static final float RANGE_RADIUS_PX = SCOPE_RADIUS_PX * (RadarEchoField.SCOPE_RADIUS / 0.445F);

    /** Side of one field cell in pixels - near the resolution the block face samples at. */
    private static final int CELL_PX = 2;

    private static final int SCREEN_FACE = 0xFF04100E;
    private static final int RING = 0x7A2FA98C;
    private static final int RIM = 0xFF1E6353;
    private static final int CROSSHAIR = 0x55257F6B;
    private static final int GRATICULE = 0x331C6B57;
    private static final int BEAM = 0x4CE6BC;

    /**
     * A storm's sampled product field, kept between frames.
     *
     * <p>Sampling is the expensive part - every corner evaluates the reflectivity function, powers included -
     * and the answer only changes when the radar sweeps again, once a second or slower. So the lattice is
     * rebuilt when the storm, the product or the range differs from the last one and drawn from memory
     * otherwise, which is what keeps a screen open in front of a supercell from costing what the sweep costs.
     */
    private static final class Lattice {
        private RadarStormSnapshot storm;
        private int mode;
        private int range;
        private int cells;
        private int side;
        private float[] values = new float[0];

        private boolean matches(RadarStormSnapshot candidate, int candidateMode, int candidateRange) {
            return mode == candidateMode && range == candidateRange && storm != null && storm.equals(candidate);
        }

        private void mark(RadarStormSnapshot candidate, int candidateMode, int candidateRange) {
            storm = candidate;
            mode = candidateMode;
            range = candidateRange;
        }

        private float[] reserve(int side) {
            this.side = side;
            if (values.length < side * side) {
                values = new float[side * side];
            }
            return values;
        }
    }

    private final Map<Long, Lattice> lattices = new HashMap<>();

    public RadarScreen(RadarMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = 238;
        imageHeight = 184;
        inventoryLabelY = imageHeight + 1000;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL);
        graphics.renderOutline(x, y, imageWidth, imageHeight, BORDER);

        int centerX = x + SCOPE_INSET_X + SCOPE_SIZE / 2;
        int centerY = y + SCOPE_INSET_Y + SCOPE_SIZE / 2;

        RadarSnapshotPayload feed = ClientRadarStore.feedFor(menu.radarPos());
        List<RadarStormSnapshot> storms = feed == null ? List.of() : feed.storms();
        int range = feed == null ? Config.RADAR_RANGE_BLOCKS.get() : feed.range();
        int mode = feed == null ? 0 : feed.mode();
        double originX = menu.radarPos().getX();
        double originZ = menu.radarPos().getZ();

        drawScopeFace(graphics, centerX, centerY, partialTick);
        drawGraticule(graphics, centerX, centerY);
        for (RadarStormSnapshot storm : storms) {
            switch (mode) {
                case 1 -> drawVelocityReturn(graphics, storm, originX, originZ, range, mode, centerX, centerY);
                case 2 -> drawRiskArea(graphics, storm, originX, originZ, range, centerX, centerY);
                default -> drawReflectivityReturn(graphics, storm, originX, originZ, range, mode, centerX, centerY);
            }
        }
        // Tracks and markers go over the products, in the order the block face draws them, so a tornado stays
        // readable on top of whatever its own echo or risk area is doing.
        for (RadarStormSnapshot storm : storms) {
            drawMotionTrack(graphics, storm, originX, originZ, range, centerX, centerY);
            drawMarkers(graphics, storm, originX, originZ, range, centerX, centerY);
        }

        drawHeader(graphics, x, y, feed, range, mode, storms.size());
        drawStormList(graphics, x, y, storms);
    }

    // ------------------------------------------------------------------------------------------------
    // The scope itself.
    // ------------------------------------------------------------------------------------------------

    private void drawScopeFace(GuiGraphics graphics, int centerX, int centerY, float partialTick) {
        fillDisc(graphics, centerX, centerY, Math.round(SCOPE_RADIUS_PX), SCREEN_FACE);
        // Rings at quarter, half and three quarters of the range, as the block's face places them, so the gap
        // between two rings is a quarter of whatever range this radar is set to.
        strokeCircle(graphics, centerX, centerY, Math.round(RANGE_RADIUS_PX * 0.25F), RING);
        strokeCircle(graphics, centerX, centerY, Math.round(RANGE_RADIUS_PX * 0.50F), RING);
        strokeCircle(graphics, centerX, centerY, Math.round(RANGE_RADIUS_PX * 0.75F), RING);
        int reach = Math.round(RANGE_RADIUS_PX);
        graphics.fill(centerX, centerY - reach, centerX + 1, centerY + reach, CROSSHAIR);
        graphics.fill(centerX - reach, centerY, centerX + reach, centerY + 1, CROSSHAIR);
        drawSweep(graphics, centerX, centerY, partialTick);
        strokeCircle(graphics, centerX, centerY, Math.round(SCOPE_RADIUS_PX) - 1, RIM);
    }

    /**
     * The beam and its afterglow, on the block program's own terms: the same rate around the screen and the
     * same exponential decay behind it, so the two displays sweep together rather than at their own speeds.
     */
    private void drawSweep(GuiGraphics graphics, int centerX, int centerY, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        long ticks = minecraft.level == null ? 0L : minecraft.level.getGameTime();
        float angle = (ticks + partialTick) * 0.055F;
        for (int i = -12; i <= 12; i++) {
            float delta = i * 0.07F;
            double intensity = Math.exp(-Math.abs(delta) * 3.2D) * 0.42D + (i == 0 ? 0.55D : 0.0D);
            if (intensity < 0.03D) {
                continue;
            }
            drawRay(graphics, centerX, centerY, angle + delta, (int) Mth.clamp(intensity * 190.0D, 0.0D, 255.0D));
        }
    }

    private void drawRay(GuiGraphics graphics, int centerX, int centerY, float angle, int alpha) {
        float length = RANGE_RADIUS_PX * 0.98F;
        int steps = 16;
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            // Power falls with range, as it does on the face: the beam fades as it runs out to the rim.
            int faded = Math.round(alpha * (1.0F - t * t * t) * 0.85F);
            if (faded <= 2) {
                continue;
            }
            int px = centerX + Math.round(Mth.cos(angle) * length * t);
            int py = centerY + Math.round(Mth.sin(angle) * length * t);
            fillOnScope(graphics, centerX, centerY, px, py, px + 1, py + 1, RadarEchoField.withAlpha(BEAM, faded));
        }
    }

    /** The faint graticule the returns are drawn over, at the same spacing the block face uses. */
    private void drawGraticule(GuiGraphics graphics, int centerX, int centerY) {
        int reach = Math.round(RANGE_RADIUS_PX);
        for (int i = -4; i <= 4; i++) {
            int offset = Math.round(reach * i / 4.0F);
            fillOnScope(graphics, centerX, centerY, centerX + offset, centerY - reach, centerX + offset + 1, centerY + reach, GRATICULE);
            fillOnScope(graphics, centerX, centerY, centerX - reach, centerY + offset, centerX + reach, centerY + offset + 1, GRATICULE);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // The products, sampled through the shared field.
    // ------------------------------------------------------------------------------------------------

    private void drawReflectivityReturn(GuiGraphics graphics, RadarStormSnapshot storm, double originX, double originZ,
                                        int range, int mode, int centerX, int centerY) {
        float size = RadarEchoField.panelToScreen(RadarEchoField.returnSize(storm, range), RANGE_RADIUS_PX);
        if (size <= 0.0F) {
            // A circulation riding inside a parent storm's shield has no shield of its own to draw.
            return;
        }
        float rx = screenX(storm.x(), originX, range);
        float rz = screenZ(storm.z(), originZ, range);
        Lattice lattice = latticeFor(storm);
        if (!lattice.matches(storm, mode, range)) {
            float angle = RadarEchoField.motionAngle(storm);
            float cos = Mth.cos(angle);
            float sin = Mth.sin(angle);
            RadarEchoField.EchoAnchors anchors = anchors(storm, originX, originZ, range, rx, rz, cos, sin);
            int cells = RadarEchoField.echoCells(size, CELL_PX);
            int side = cells * 2 + 1;
            float[] values = lattice.reserve(side);
            for (int ix = 0; ix < side; ix++) {
                for (int iz = 0; iz < side; iz++) {
                    float dx = (ix - cells) * CELL_PX;
                    float dz = (iz - cells) * CELL_PX;
                    values[iz * side + ix] = (float) RadarEchoField.reflectivityAt(storm,
                            dx * cos + dz * sin, -dx * sin + dz * cos, size, anchors, ix - cells, iz - cells);
                }
            }
            lattice.cells = cells;
            lattice.mark(storm, mode, range);
        }
        drawLattice(graphics, lattice, storm, centerX, centerY, rx, rz, false);
    }

    private void drawVelocityReturn(GuiGraphics graphics, RadarStormSnapshot storm, double originX, double originZ,
                                    int range, int mode, int centerX, int centerY) {
        float rx = screenX(storm.x(), originX, range);
        float rz = screenZ(storm.z(), originZ, range);
        float size = Math.max(RadarEchoField.panelToScreen(RadarEchoField.returnSize(storm, range), RANGE_RADIUS_PX),
                RadarEchoField.panelToScreen(0.030F, RANGE_RADIUS_PX));
        Lattice lattice = latticeFor(storm);
        if (!lattice.matches(storm, mode, range)) {
            float rangeDistance = Math.max(0.001F, Mth.sqrt(rx * rx + rz * rz));
            float beamX = rx / rangeDistance;
            float beamZ = rz / rangeDistance;
            int cells = RadarEchoField.velocityCells(size, CELL_PX);
            int side = cells * 2 + 1;
            float[] values = lattice.reserve(side);
            for (int ix = 0; ix < side; ix++) {
                for (int iz = 0; iz < side; iz++) {
                    values[iz * side + ix] = RadarEchoField.radialVelocityAt(storm, (ix - cells) * CELL_PX, (iz - cells) * CELL_PX,
                            size, beamX, beamZ, -beamZ, beamX);
                }
            }
            lattice.cells = cells;
            lattice.mark(storm, mode, range);
        }
        drawLattice(graphics, lattice, storm, centerX, centerY, rx, rz, true);
    }

    /**
     * Paints a sampled lattice over the scope.
     *
     * <p>One fill per cell, coloured by the mean of its four corners - a GUI fill carries one colour where the
     * block's cells carry four - so the ramp still varies smoothly across a shield and the picture is the same
     * shield, a pixel coarser.
     */
    private void drawLattice(GuiGraphics graphics, Lattice lattice, RadarStormSnapshot storm,
                             int centerX, int centerY, float rx, float rz, boolean velocity) {
        int cells = lattice.cells;
        int side = lattice.side;
        float[] values = lattice.values;
        int baseX = Math.round(centerX + rx);
        int baseZ = Math.round(centerY + rz);
        for (int ix = 0; ix < side - 1; ix++) {
            for (int iz = 0; iz < side - 1; iz++) {
                float minMin = values[iz * side + ix];
                float maxMin = values[iz * side + ix + 1];
                float minMax = values[(iz + 1) * side + ix];
                float maxMax = values[(iz + 1) * side + ix + 1];
                float mean = (minMin + maxMin + minMax + maxMax) * 0.25F;
                int color;
                if (velocity) {
                    float peak = Math.max(Math.max(Math.abs(minMin), Math.abs(maxMin)), Math.max(Math.abs(minMax), Math.abs(maxMax)));
                    if (peak < RadarEchoField.VELOCITY_FLOOR_MPS) {
                        continue;
                    }
                    color = RadarEchoField.velocityColor(mean);
                } else {
                    float peak = Math.max(Math.max(minMin, maxMin), Math.max(minMax, maxMax));
                    if (peak < RadarEchoField.ECHO_FLOOR_DBZ) {
                        continue;
                    }
                    color = RadarEchoField.sampleColor(Math.max(mean, RadarEchoField.ECHO_FLOOR_DBZ));
                }
                int px = baseX + (ix - cells) * CELL_PX;
                int pz = baseZ + (iz - cells) * CELL_PX;
                fillOnScope(graphics, centerX, centerY, px, pz, px + CELL_PX, pz + CELL_PX, color);
            }
        }
    }

    private void drawRiskArea(GuiGraphics graphics, RadarStormSnapshot storm, double originX, double originZ,
                              int range, int centerX, int centerY) {
        float size = Math.max(RadarEchoField.panelToScreen(RadarEchoField.returnSize(storm, range), RANGE_RADIUS_PX),
                RadarEchoField.panelToScreen(0.030F, RANGE_RADIUS_PX));
        int cells = RadarEchoField.riskCells(size, CELL_PX);
        int color = RadarEchoField.riskColor(storm);
        int baseX = Math.round(centerX + screenX(storm.x(), originX, range));
        int baseZ = Math.round(centerY + screenZ(storm.z(), originZ, range));
        for (int ix = -cells; ix <= cells; ix++) {
            for (int iz = -cells; iz <= cells; iz++) {
                float radial = Mth.sqrt((float) (ix * ix + iz * iz)) / cells;
                if (radial > 1.0F) {
                    continue;
                }
                // Solid through the middle, dithered only where the disc is running out.
                float edge = Mth.clamp((1.0F - radial) / 0.28F, 0.0F, 1.0F);
                if (edge < 0.999F && RadarEchoField.pixelNoise(storm.id(), ix, iz) * 0.5F + 0.5F > edge) {
                    continue;
                }
                int px = baseX + ix * CELL_PX;
                int pz = baseZ + iz * CELL_PX;
                fillOnScope(graphics, centerX, centerY, px, pz, px + CELL_PX, pz + CELL_PX, color);
            }
        }
    }

    private Lattice latticeFor(RadarStormSnapshot storm) {
        // Only the storms on the current sweep are ever asked for, so the cache cannot grow without bound.
        return lattices.computeIfAbsent(storm.id(), id -> new Lattice());
    }

    // ------------------------------------------------------------------------------------------------
    // Tracks and markers.
    // ------------------------------------------------------------------------------------------------

    private void drawMotionTrack(GuiGraphics graphics, RadarStormSnapshot storm, double originX, double originZ,
                                 int range, int centerX, int centerY) {
        float mx = RadarEchoField.panelToScreen(RadarEchoField.motionTrackX(storm, range, 0.07F), RANGE_RADIUS_PX);
        float mz = RadarEchoField.panelToScreen(RadarEchoField.motionTrackZ(storm, range, 0.07F), RANGE_RADIUS_PX);
        if (mx == 0.0F && mz == 0.0F) {
            return;
        }
        float x = markerX(storm, originX, range);
        float z = markerZ(storm, originZ, range);
        line(graphics, centerX, centerY, x - mx, z - mz, x, z, RadarEchoField.withAlpha(0xFFFFFF, 170));
    }

    private void drawMarkers(GuiGraphics graphics, RadarStormSnapshot storm, double originX, double originZ,
                             int range, int centerX, int centerY) {
        float size = RadarEchoField.panelToScreen(RadarEchoField.returnSize(storm, range), RANGE_RADIUS_PX);
        float circulationX = screenX(storm.circulationX(), originX, range);
        float circulationZ = screenZ(storm.circulationZ(), originZ, range);

        if (storm.subVortices() > 0) {
            int count = Mth.clamp(storm.subVortices(), 1, 10);
            for (int i = 0; i < count; i++) {
                float angle = (float) (i * Math.PI * 2.0D / count);
                float ox = Mth.cos(angle) * size * 1.55F;
                float oz = Mth.sin(angle) * size * 1.55F;
                int dot = Math.max(1, Math.round(Math.max(1.0F, size * 0.22F)));
                int px = Math.round(centerX + circulationX + ox) - dot;
                int pz = Math.round(centerY + circulationZ + oz) - dot;
                fillOnScope(graphics, centerX, centerY, px, pz, px + dot * 2, pz + dot * 2, 0xFFFFF4B8);
            }
        }
        boolean confirmed = storm.hasConfirmedCirculation();
        if (confirmed) {
            // A confirmed tornado: the cross and ring the block face draws, at the circulation itself - and
            // drawn first, so a signature icon over it still leaves the cross's arms reading.
            float arm = Math.max(size * 1.35F, 7.0F);
            int color = 0xFFFFF9C8;
            line(graphics, centerX, centerY, circulationX - arm, circulationZ, circulationX + arm, circulationZ, color);
            line(graphics, centerX, centerY, circulationX, circulationZ - arm, circulationX, circulationZ + arm, color);
            ring(graphics, centerX, centerY, circulationX, circulationZ, Math.max(size * 0.42F, 3.0F), color);
        }
        // The velocity product's own read, at the point the rotation is at: the couplet for a TVS, the
        // resolved mesocyclone for a diamond.
        int signature = Config.RADAR_VORTEX_ICONS.get()
                ? RadarEchoField.vortexSignatureTier(storm) : RadarEchoField.SIGNATURE_NONE;
        if (signature == RadarEchoField.SIGNATURE_TVS) {
            drawVortexIcon(graphics, centerX, centerY, circulationX, circulationZ, RadarEchoField.SIGNATURE_TVS);
        } else if (signature == RadarEchoField.SIGNATURE_MESO) {
            float mesoX = screenX(storm.mesoX(), originX, range);
            float mesoZ = screenZ(storm.mesoZ(), originZ, range);
            drawVortexIcon(graphics, centerX, centerY, mesoX, mesoZ, RadarEchoField.SIGNATURE_MESO);
        } else if (!confirmed && storm.hookEcho()) {
            float hookX = screenX(RadarEchoField.hookWorldX(storm), originX, range);
            float hookZ = screenZ(RadarEchoField.hookWorldZ(storm), originZ, range);
            ring(graphics, centerX, centerY, hookX, hookZ, Math.max(size * 0.34F, 3.0F), 0xFFC6F6FF);
        }
    }

    /**
     * The signature icon, at the same size the block face draws it, with its tag beside it.
     *
     * <p>Sized from the shared panel-unit constants rather than from the storm, so the two displays show the
     * same symbol and a TVS does not grow and shrink as the storm widens.
     */
    private void drawVortexIcon(GuiGraphics graphics, int centerX, int centerY, float x, float z, int tier) {
        float half = RadarEchoField.panelToScreen(RadarEchoField.signatureHalf(tier), RANGE_RADIUS_PX);
        float axis = half * 1.55F;
        if (tier == RadarEchoField.SIGNATURE_TVS) {
            float height = half * (RadarEchoField.SIGNATURE_HEIGHT / RadarEchoField.SIGNATURE_HALF);
            triangle(graphics, centerX, centerY, x, z, half * 1.34F, height * 1.16F, RadarEchoField.SIGNATURE_OUTLINE_COLOR);
            triangle(graphics, centerX, centerY, x, z, half, height, RadarEchoField.TVS_ICON_COLOR);
        } else {
            diamond(graphics, centerX, centerY, x, z, axis * 1.30F, RadarEchoField.SIGNATURE_OUTLINE_COLOR);
            diamond(graphics, centerX, centerY, x, z, axis, RadarEchoField.MESO_ICON_COLOR);
        }
        // The tag goes beside the icon, the way a real scope labels one - but only when the whole label has
        // room inside the round screen, since a GUI drawString is not clipped to it the way the fills are.
        String label = RadarEchoField.signatureLabel(tier);
        float fromCentre = Mth.sqrt(x * x + z * z);
        if (!label.isEmpty() && fromCentre < RANGE_RADIUS_PX - 26.0F) {
            int px = Math.round(centerX + x + half + 2.0F);
            int pz = Math.round(centerY + z - 4.0F);
            graphics.drawString(font, label, px, pz, RadarEchoField.signatureColor(tier), false);
        }
    }

    /** An upward triangle centred on {@code (x, z)} in scope offsets: apex above, base below. */
    private void triangle(GuiGraphics graphics, int centerX, int centerY, float x, float z, float half, float height, int color) {
        int top = Math.round(centerY + z - height);
        int bottom = Math.round(centerY + z + height * 0.42F);
        int rows = Math.max(1, bottom - top);
        for (int py = top; py <= bottom; py++) {
            float t = (py - top) / (float) rows;
            int w = Math.max(1, Math.round(half * t));
            int px = Math.round(centerX + x);
            fillOnScope(graphics, centerX, centerY, px - w, py, px + w, py + 1, color);
        }
    }

    /** A diamond centred on {@code (x, z)} in scope offsets, corners {@code axis} out along each axis. */
    private void diamond(GuiGraphics graphics, int centerX, int centerY, float x, float z, float axis, int color) {
        int radius = Math.max(1, Math.round(axis));
        for (int dy = -radius; dy <= radius; dy++) {
            float taper = 1.0F - Math.abs(dy) / (float) radius;
            int w = Math.max(0, Math.round(axis * taper));
            int px = Math.round(centerX + x);
            int py = Math.round(centerY + z) + dy;
            fillOnScope(graphics, centerX, centerY, px - w, py, px + w + 1, py + 1, color);
        }
    }

    /** The point a storm's track and markers are anchored to: its circulation, else its hook, else itself. */
    private static float markerX(RadarStormSnapshot storm, double originX, int range) {
        return storm.hasConfirmedCirculation() ? screenX(storm.circulationX(), originX, range)
                : storm.hookEcho() ? screenX(storm.hookX(), originX, range) : screenX(storm.x(), originX, range);
    }

    private static float markerZ(RadarStormSnapshot storm, double originZ, int range) {
        return storm.hasConfirmedCirculation() ? screenZ(storm.circulationZ(), originZ, range)
                : storm.hookEcho() ? screenZ(storm.hookZ(), originZ, range) : screenZ(storm.z(), originZ, range);
    }

    // ------------------------------------------------------------------------------------------------
    // Text.
    // ------------------------------------------------------------------------------------------------

    private void drawHeader(GuiGraphics graphics, int x, int y, RadarSnapshotPayload feed, int range, int mode, int returns) {
        int textX = x + SCOPE_INSET_X;
        graphics.drawString(font, title, textX, y + 8, TEXT, false);
        String product = switch (mode) {
            case 1 -> "Velocity";
            case 2 -> "SPC risk";
            default -> "Reflectivity";
        };
        boolean linked = (feed != null && feed.radarBlock()) || linkedBlock();
        graphics.drawString(font, product + "  -  range " + range + " blocks", textX, y + imageHeight - 21, DIM_TEXT, false);
        graphics.drawString(font, linked ? "Linked weather radar" : "Local sweep - no weather radar in range",
                textX, y + imageHeight - 11, linked ? 0xFF7FE3B4 : 0xFFE3C77F, false);
        graphics.drawString(font, returns + (returns == 1 ? " return" : " returns"), x + 166, y + 20, DIM_TEXT, false);
    }

    /**
     * Whether the screen is standing on a weather radar, for the frames before its first sweep has arrived.
     *
     * <p>The feed is the authority once there is one; this only keeps the header honest in the instant between
     * a radar block opening the screen and its first sweep landing, which would otherwise be labelled a local
     * sweep.
     */
    private boolean linkedBlock() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null && minecraft.level.getBlockEntity(menu.radarPos()) instanceof RadarBlockEntity;
    }

    private void drawStormList(GuiGraphics graphics, int x, int y, List<RadarStormSnapshot> storms) {
        int listX = x + 166;
        int listY = y + 34;
        graphics.drawString(font, "SPC / EAS", listX, y + 8, TEXT, false);
        if (storms.isEmpty()) {
            graphics.drawString(font, "No returns", listX, listY, DIM_TEXT, false);
            return;
        }
        for (int i = 0; i < storms.size() && i < 8; i++) {
            RadarStormSnapshot storm = storms.get(i);
            int rowY = listY + i * 17;
            graphics.fill(listX, rowY + 1, listX + 7, rowY + 8, storm.color());
            graphics.drawString(font, storm.label(), listX + 11, rowY, TEXT, false);
            String risk = storm.risk();
            graphics.drawString(font, risk, listX + 11, rowY + 8, 0xFFB5C9C7, false);
            // The signature goes after the risk, so the list says which return is flagged without the reader
            // having to pick the icon out on the scope - but only when the row has room for it, since a GUI
            // string is not clipped and an overhanging tag would run past the panel.
            int tagX = listX + 15 + font.width(risk);
            if (Config.RADAR_VORTEX_ICONS.get()) {
                int tier = RadarEchoField.vortexSignatureTier(storm);
                String signature = RadarEchoField.signatureLabel(tier);
                if (!signature.isEmpty() && tagX + font.width(signature) <= x + imageWidth - 4) {
                    graphics.drawString(font, signature, tagX, rowY + 8, RadarEchoField.signatureColor(tier), false);
                    tagX += font.width(signature) + 4;
                }
            }
            // Then its size class, from the width the row already carries: a rope and a monster are the
            // same risk on this list otherwise, and telling them apart is what the size model is for.
            String sizeTag = RadarEchoField.sizeLabel(storm.width());
            if (!sizeTag.isEmpty() && tagX + font.width(sizeTag) <= x + imageWidth - 4) {
                graphics.drawString(font, sizeTag, tagX, rowY + 8, RadarEchoField.sizeColor(sizeTag), false);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Mapping and primitives.
    // ------------------------------------------------------------------------------------------------

    /** A world X as a pixel offset from the scope centre, through the same mapping the block face uses. */
    private static float screenX(double worldX, double originX, int range) {
        return RadarEchoField.panelToScreen(RadarEchoField.scopeCoord(worldX, originX, range) - 0.5F, RANGE_RADIUS_PX);
    }

    /** A world Z as a pixel offset from the scope centre. */
    private static float screenZ(double worldZ, double originZ, int range) {
        return RadarEchoField.panelToScreen(RadarEchoField.scopeCoord(worldZ, originZ, range) - 0.5F, RANGE_RADIUS_PX);
    }

    private static RadarEchoField.EchoAnchors anchors(RadarStormSnapshot storm, double originX, double originZ, int range,
                                                      float rx, float rz, float cos, float sin) {
        return RadarEchoField.anchorsFor(storm,
                screenX(RadarEchoField.hookWorldX(storm), originX, range) - rx,
                screenZ(RadarEchoField.hookWorldZ(storm), originZ, range) - rz,
                screenX(storm.hookX(), originX, range) - rx,
                screenZ(storm.hookZ(), originZ, range) - rz,
                cos, sin);
    }

    /** A rectangle fill clipped to the round screen, so nothing spills onto the bezel. */
    private void fillOnScope(GuiGraphics graphics, int centerX, int centerY, int x1, int y1, int x2, int y2, int color) {
        if (x2 <= x1 || y2 <= y1) {
            return;
        }
        float radius = RANGE_RADIUS_PX + 1.0F;
        float farthest = Math.max(Math.abs(y1 - centerY), Math.abs(y2 - centerY));
        if (farthest >= radius) {
            return;
        }
        int half = (int) Math.floor(Math.sqrt(radius * radius - farthest * farthest));
        int left = Math.max(x1, centerX - half);
        int right = Math.min(x2, centerX + half + 1);
        if (right <= left) {
            return;
        }
        graphics.fill(left, y1, right, y2, color);
    }

    private void fillDisc(GuiGraphics graphics, int centerX, int centerY, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int half = (int) Math.floor(Math.sqrt(Math.max(0, radius * radius - dy * dy)));
            graphics.fill(centerX - half, centerY + dy, centerX + half + 1, centerY + dy + 1, color);
        }
    }

    private void strokeCircle(GuiGraphics graphics, int centerX, int centerY, int radius, int color) {
        int inner = Math.max(1, radius - 1);
        for (int dy = -radius; dy <= radius; dy++) {
            int outerHalf = (int) Math.floor(Math.sqrt(Math.max(0, radius * radius - dy * dy)));
            int innerHalf = (int) Math.floor(Math.sqrt(Math.max(0, inner * inner - dy * dy)));
            int left = centerX - outerHalf;
            int right = centerX + outerHalf;
            graphics.fill(left, centerY + dy, left + (outerHalf - innerHalf) + 1, centerY + dy + 1, color);
            graphics.fill(right - (outerHalf - innerHalf), centerY + dy, right + 1, centerY + dy + 1, color);
        }
    }

    private void line(GuiGraphics graphics, int centerX, int centerY, float x1, float z1, float x2, float z2, int color) {
        float dx = x2 - x1;
        float dz = z2 - z1;
        int steps = Math.max(1, (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dz))));
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            int px = Math.round(centerX + x1 + dx * t);
            int pz = Math.round(centerY + z1 + dz * t);
            fillOnScope(graphics, centerX, centerY, px, pz, px + 1, pz + 1, color);
        }
    }

    private void ring(GuiGraphics graphics, int centerX, int centerY, float x, float z, float radius, int color) {
        int steps = Math.max(16, Math.round(radius * 6.0F));
        for (int i = 0; i < steps; i++) {
            double angle = i * Math.PI * 2.0D / steps;
            int px = Math.round(centerX + x + (float) Math.cos(angle) * radius);
            int pz = Math.round(centerY + z + (float) Math.sin(angle) * radius);
            fillOnScope(graphics, centerX, centerY, px, pz, px + 1, pz + 1, color);
        }
    }
}
