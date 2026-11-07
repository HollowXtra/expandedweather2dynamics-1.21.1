package com.imbet.client;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.network.RadarSnapshotPayload;
import com.imbet.radar.ClientRadarStore;
import com.imbet.radar.RadarBlockEntity;
import com.imbet.radar.RadarMenu;
import com.imbet.radar.RadarStormSnapshot;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Locale;

/**
 * The portable radar: a ruggedised handheld with a real radar display on it.
 *
 * <p>It is not a radar of its own. Everything on the scope comes from {@link RadarEchoField}, through the same
 * {@link RadarFieldTexture} and the same {@code radar_scope} program the weather radar block draws its face with, and
 * the feed being shown is a weather radar block's own feed when one was in reach. A handheld and a block therefore show
 * one picture rather than two.
 *
 * <p>The scope used to be plain fills in a small flat panel, a colour per two-pixel cell. Now the product is drawn by
 * the program, and everything drawn over it is vector geometry at the screen's own resolution rather than at the GUI's
 * coarse one, so a track is a line and a ring is round. Text that does not fit is cut to fit rather than left to run off
 * the display, which it used to do on any storm with a long name.
 *
 * <p>Under a shader pack the GUI is untouched - a pack does not replace programs for the 2D pass - so this one path
 * holds everywhere.
 */
public class RadarScreen extends AbstractContainerScreen<RadarMenu> {
    private static final ResourceLocation DEVICE = ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "textures/gui/radar_device.png");

    /** The whole device, in GUI units. The texture is authored at twice this, so it is crisp at the usual GUI scales. */
    private static final int DEVICE_WIDTH = 316;
    private static final int DEVICE_HEIGHT = 208;

    /** The screen inside the case. */
    private static final int LCD_X = 18;
    private static final int LCD_Y = 16;
    private static final int LCD_WIDTH = 280;
    private static final int LCD_HEIGHT = 168;

    /** The square the round scope fills, flush with the left of the screen. */
    private static final int SCOPE_SIZE = 168;
    private static final float SCOPE_RADIUS_PX = SCOPE_SIZE / 2.0F;

    /** The rim as a share of the box: the same share the block face has, so a distance means the same on both. */
    private static final float RING_RADIUS = RadarEchoField.SCOPE_RADIUS / 0.445F;
    private static final float RANGE_RADIUS_PX = SCOPE_RADIUS_PX * RING_RADIUS;

    /** The data column to the right of the scope. */
    private static final int DATA_X = LCD_X + SCOPE_SIZE + 6;
    private static final int DATA_WIDTH = LCD_WIDTH - SCOPE_SIZE - 10;

    private static final int INK = 0xFFE9F1F0;
    private static final int INK_DIM = 0xFF8FA6A4;
    private static final int INK_ACCENT = 0xFF5FE0C2;
    private static final int RULE = 0x663C5552;

    private static final float[] LEGEND_DBZ = {5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75};

    public RadarScreen(RadarMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = DEVICE_WIDTH;
        imageHeight = DEVICE_HEIGHT;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    /**
     * Nothing: the display draws its own title.
     *
     * <p>The inherited labels drew the menu's title a second time underneath, dark on dark, a pixel off from the real
     * one, which is why the handheld's name always looked smeared.
     */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        RenderSystem.enableBlend();
        graphics.blit(DEVICE, x, y, DEVICE_WIDTH, DEVICE_HEIGHT, 0.0F, 0.0F, DEVICE_WIDTH * 2, DEVICE_HEIGHT * 2, DEVICE_WIDTH * 2, DEVICE_HEIGHT * 2);

        RadarSnapshotPayload feed = ClientRadarStore.feedFor(menu.radarPos());
        List<RadarStormSnapshot> storms = feed == null ? List.of() : feed.storms();
        int range = feed == null ? Config.RADAR_RANGE_BLOCKS.get() : feed.range();
        int mode = feed == null ? 0 : feed.mode();
        double originX = menu.radarPos().getX();
        double originZ = menu.radarPos().getZ();

        graphics.fill(x + LCD_X, y + LCD_Y, x + LCD_X + LCD_WIDTH, y + LCD_Y + LCD_HEIGHT, 0xFF03060A);

        int scopeX = x + LCD_X;
        int scopeY = y + LCD_Y;
        drawScope(graphics, scopeX, scopeY, storms, mode, range, originX, originZ, partialTick);

        float centerX = scopeX + SCOPE_RADIUS_PX;
        float centerY = scopeY + SCOPE_RADIUS_PX;
        VertexConsumer lines = graphics.bufferSource().getBuffer(RenderType.gui());
        Matrix4f pose = graphics.pose().last().pose();
        for (RadarStormSnapshot storm : storms) {
            drawMotionTrack(lines, pose, storm, originX, originZ, range, centerX, centerY);
        }
        for (RadarStormSnapshot storm : storms) {
            drawMarkers(lines, pose, storm, originX, originZ, range, centerX, centerY);
        }
        graphics.flush();

        drawRangeLabels(graphics, scopeX, scopeY, range);
        drawData(graphics, x, y, feed, storms, range, mode);
    }

    // ------------------------------------------------------------------------------------------------
    // The scope.
    // ------------------------------------------------------------------------------------------------

    private void drawScope(GuiGraphics graphics, int x, int y, List<RadarStormSnapshot> storms, int mode, int range,
                           double originX, double originZ, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        long tick = minecraft.level == null ? 0L : minecraft.level.getGameTime();
        RadarFieldTexture field = RadarFieldTexture.forRadar(menu.radarPos(), storms, mode, range, tick, partialTick);
        if (!RadarScopeShader.ready()) {
            return;
        }
        RadarScopeShader.configure(field, originX + 0.5D, originZ + 0.5D, range, mode, RING_RADIUS, 1.0F, false);
        RenderSystem.setShader(RadarScopeShader::instance);
        RenderSystem.setShaderTexture(0, field.textureId());
        RenderSystem.disableBlend();
        Matrix4f pose = graphics.pose().last().pose();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        builder.addVertex(pose, x, y + SCOPE_SIZE, 0.0F).setUv(0.0F, 1.0F).setColor(0xFFFFFFFF);
        builder.addVertex(pose, x + SCOPE_SIZE, y + SCOPE_SIZE, 0.0F).setUv(1.0F, 1.0F).setColor(0xFFFFFFFF);
        builder.addVertex(pose, x + SCOPE_SIZE, y, 0.0F).setUv(1.0F, 0.0F).setColor(0xFFFFFFFF);
        builder.addVertex(pose, x, y, 0.0F).setUv(0.0F, 0.0F).setColor(0xFFFFFFFF);
        BufferUploader.drawWithShader(builder.buildOrThrow());
        RenderSystem.enableBlend();
    }

    /** The compass points and the range of each ring, in the corners where they cover nothing. */
    private void drawRangeLabels(GuiGraphics graphics, int x, int y, int range) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(0.5F, 0.5F, 1.0F);
        int half = SCOPE_SIZE;
        graphics.drawCenteredString(font, "N", half, 2, INK_DIM);
        graphics.drawCenteredString(font, "S", half, SCOPE_SIZE * 2 - 10, INK_DIM);
        graphics.drawString(font, "W", 2, half - 4, INK_DIM, false);
        graphics.drawString(font, "E", SCOPE_SIZE * 2 - 8, half - 4, INK_DIM, false);
        for (int ring = 1; ring <= 4; ring++) {
            String label = Integer.toString(range * ring / 4);
            float at = RANGE_RADIUS_PX * ring / 4.0F * 2.0F;
            float ox = half + at * 0.7071F;
            float oy = half + at * 0.7071F;
            graphics.drawString(font, label, Math.round(ox) - font.width(label) - 1, Math.round(oy) - 8, 0xB0A6BDBC, false);
        }
        graphics.pose().popPose();
    }

    // ------------------------------------------------------------------------------------------------
    // Tracks and markers, drawn as vectors over the product.
    // ------------------------------------------------------------------------------------------------

    private void drawMotionTrack(VertexConsumer out, Matrix4f pose, RadarStormSnapshot storm, double originX, double originZ, int range, float centerX, float centerY) {
        float mx = RadarEchoField.panelToScreen(RadarEchoField.motionTrackX(storm, range, 0.07F), RANGE_RADIUS_PX);
        float mz = RadarEchoField.panelToScreen(RadarEchoField.motionTrackZ(storm, range, 0.07F), RANGE_RADIUS_PX);
        if (mx == 0.0F && mz == 0.0F) {
            return;
        }
        float x = centerX + markerX(storm, originX, range);
        float y = centerY + markerZ(storm, originZ, range);
        line(out, pose, x - mx, y - mz, x, y, 0.6F, 0xB4FFFFFF);
    }

    private void drawMarkers(VertexConsumer out, Matrix4f pose, RadarStormSnapshot storm, double originX, double originZ, int range, float centerX, float centerY) {
        float size = RadarEchoField.panelToScreen(RadarEchoField.returnSize(storm, range), RANGE_RADIUS_PX);
        float circulationX = centerX + screenX(storm.circulationX(), originX, range);
        float circulationY = centerY + screenZ(storm.circulationZ(), originZ, range);

        if (storm.subVortices() > 0) {
            int count = Mth.clamp(storm.subVortices(), 1, 10);
            float dot = Math.max(0.8F, size * 0.10F);
            for (int i = 0; i < count; i++) {
                float angle = (float) (i * Math.PI * 2.0D / count);
                float ox = Mth.cos(angle) * Math.max(size * 0.8F, 3.5F);
                float oy = Mth.sin(angle) * Math.max(size * 0.8F, 3.5F);
                disc(out, pose, circulationX + ox, circulationY + oy, dot, 0xFFFFF4B8);
            }
        }
        boolean confirmed = storm.hasConfirmedCirculation();
        if (confirmed) {
            float arm = Math.max(size * 0.9F, 5.0F);
            line(out, pose, circulationX - arm, circulationY, circulationX + arm, circulationY, 0.55F, 0xFFFFF9C8);
            line(out, pose, circulationX, circulationY - arm, circulationX, circulationY + arm, 0.55F, 0xFFFFF9C8);
            ring(out, pose, circulationX, circulationY, Math.max(size * 0.32F, 2.6F), 0.5F, 0xFFFFF9C8);
        }
        // The velocity product's own read, at the point the rotation is at: the couplet for a TVS, the
        // resolved mesocyclone for a diamond.
        int signature = Config.RADAR_VORTEX_ICONS.get()
                ? RadarEchoField.vortexSignatureTier(storm) : RadarEchoField.SIGNATURE_NONE;
        if (signature == RadarEchoField.SIGNATURE_TVS) {
            invertedTriangle(out, pose, circulationX, circulationY, 4.6F, 7.6F, RadarEchoField.SIGNATURE_OUTLINE_COLOR);
            invertedTriangle(out, pose, circulationX, circulationY, 3.2F, 5.6F, RadarEchoField.TVS_ICON_COLOR);
        } else if (signature == RadarEchoField.SIGNATURE_ETVS) {
            float mesoX = centerX + screenX(storm.mesoX(), originX, range);
            float mesoY = centerY + screenZ(storm.mesoZ(), originZ, range);
            invertedTriangle(out, pose, mesoX, mesoY, 4.6F, 7.6F, RadarEchoField.SIGNATURE_OUTLINE_COLOR);
            invertedTriangle(out, pose, mesoX, mesoY, 3.2F, 5.6F, RadarEchoField.ETVS_ICON_COLOR);
        } else if (signature == RadarEchoField.SIGNATURE_MESO) {
            float mesoX = centerX + screenX(storm.mesoX(), originX, range);
            float mesoY = centerY + screenZ(storm.mesoZ(), originZ, range);
            ring(out, pose, mesoX, mesoY, 3.6F, 1.6F, RadarEchoField.SIGNATURE_OUTLINE_COLOR);
            ring(out, pose, mesoX, mesoY, 3.6F, 0.9F, RadarEchoField.MESO_ICON_COLOR);
        } else if (!confirmed && storm.hookEcho()) {
            float hookX = centerX + screenX(RadarEchoField.hookWorldX(storm), originX, range);
            float hookY = centerY + screenZ(RadarEchoField.hookWorldZ(storm), originZ, range);
            ring(out, pose, hookX, hookY, Math.max(size * 0.26F, 2.6F), 0.5F, 0xFFC6F6FF);
        }
    }

    /** The point a storm's track is anchored to: its circulation, else its hook, else itself. */
    private static float markerX(RadarStormSnapshot storm, double originX, int range) {
        return storm.hasConfirmedCirculation() ? screenX(storm.circulationX(), originX, range)
                : storm.hookEcho() ? screenX(storm.hookX(), originX, range) : screenX(storm.x(), originX, range);
    }

    private static float markerZ(RadarStormSnapshot storm, double originZ, int range) {
        return storm.hasConfirmedCirculation() ? screenZ(storm.circulationZ(), originZ, range)
                : storm.hookEcho() ? screenZ(storm.hookZ(), originZ, range) : screenZ(storm.z(), originZ, range);
    }

    // ------------------------------------------------------------------------------------------------
    // The data column.
    // ------------------------------------------------------------------------------------------------

    private void drawData(GuiGraphics graphics, int x, int y, RadarSnapshotPayload feed, List<RadarStormSnapshot> storms, int range, int mode) {
        int left = x + DATA_X;
        int right = left + DATA_WIDTH;
        int top = y + LCD_Y + 4;

        small(graphics, fit(title.getString().toUpperCase(Locale.ROOT), DATA_WIDTH * 2), left, top, INK_ACCENT);
        String product = switch (mode) {
            case 1 -> "BASE VELOCITY";
            case 2 -> "SPC RISK";
            default -> "BASE REFLECTIVITY";
        };
        graphics.drawString(font, fit(product, DATA_WIDTH), left, top + 7, INK, false);
        small(graphics, "RANGE " + range + " BLK", left, top + 17, INK_DIM);
        boolean linked = (feed != null && feed.radarBlock()) || linkedBlock();
        small(graphics, linked ? "LINKED TO RADAR" : "LOCAL SWEEP - NO RADAR", left, top + 22, linked ? 0xFF7FE3B4 : 0xFFE3C77F);
        small(graphics, clock(), right - font.width(clock()) / 2, top + 17, INK_DIM);
        graphics.fill(left, top + 28, right, top + 29, RULE);

        drawLegend(graphics, left, top + 31, DATA_WIDTH, mode);
        graphics.fill(left, top + 44, right, top + 45, RULE);

        drawStormList(graphics, left, top + 48, DATA_WIDTH, y + LCD_Y + LCD_HEIGHT - 4, storms);
    }

    /** The product's colour scale, because a colour on a radar only means something against the key. */
    private void drawLegend(GuiGraphics graphics, int x, int y, int width, int mode) {
        if (mode == 2) {
            small(graphics, "CATEGORICAL OUTLOOK", x, y + 2, INK_DIM);
            return;
        }
        int bars = LEGEND_DBZ.length;
        float step = width / (float) bars;
        for (int i = 0; i < bars; i++) {
            int color = mode == 1
                    ? 0xFF000000 | RadarEchoField.velocityColor((i - bars / 2) * 7.0F) & 0xFFFFFF
                    : 0xFF000000 | RadarEchoField.rampColor(LEGEND_DBZ[i]);
            graphics.fill(x + Math.round(i * step), y, x + Math.round((i + 1) * step), y + 5, color);
        }
        if (mode == 1) {
            small(graphics, "IN", x, y + 7, INK_DIM);
            small(graphics, "M/S", x + width / 2 - 3, y + 7, INK_DIM);
            small(graphics, "OUT", x + width - 7, y + 7, INK_DIM);
        } else {
            small(graphics, "5", x, y + 7, INK_DIM);
            small(graphics, "DBZ", x + width / 2 - 3, y + 7, INK_DIM);
            small(graphics, "75", x + width - 5, y + 7, INK_DIM);
        }
    }

    /**
     * The storm table, in as many rows as fit and with a count of the rest, strongest first.
     *
     * <p>Rows are laid out against the width they really have. They used to be drawn at a fixed position and left to
     * overrun, so a storm named Thunderstorm ran straight off the right edge of the display.
     */
    private void drawStormList(GuiGraphics graphics, int x, int y, int width, int bottom, List<RadarStormSnapshot> storms) {
        if (storms.isEmpty()) {
            graphics.drawString(font, "No returns", x, y, INK_DIM, false);
            return;
        }
        List<RadarStormSnapshot> ranked = storms.stream()
                .sorted((a, b) -> Double.compare(b.severity() * 1000.0D + b.windMph(), a.severity() * 1000.0D + a.windMph()))
                .toList();
        int rowHeight = 15;
        int rows = Math.max(1, (bottom - y) / rowHeight);
        boolean overflow = ranked.size() > rows;
        int shown = overflow ? rows - 1 : ranked.size();
        for (int i = 0; i < shown; i++) {
            RadarStormSnapshot storm = ranked.get(i);
            int rowY = y + i * rowHeight;
            graphics.fill(x, rowY + 1, x + 3, rowY + 12, 0xFF000000 | storm.color() & 0xFFFFFF);
            graphics.drawString(font, fit(storm.label(), width - 6), x + 6, rowY, INK, false);
            StringBuilder detail = new StringBuilder(storm.risk());
            detail.append(' ').append(Math.round(storm.windMph())).append(" MPH");
            if (Config.RADAR_VORTEX_ICONS.get()) {
                String signature = RadarEchoField.signatureLabel(RadarEchoField.vortexSignatureTier(storm));
                if (!signature.isEmpty()) {
                    detail.append(' ').append(signature);
                }
            }
            String sizeTag = RadarEchoField.sizeLabel(storm.width());
            if (!sizeTag.isEmpty()) {
                detail.append(' ').append(sizeTag);
            }
            small(graphics, fit(detail.toString(), (width - 6) * 2), x + 6, rowY + 8, INK_DIM);
        }
        if (overflow) {
            small(graphics, "+ " + (ranked.size() - shown) + " MORE RETURNS", x, y + shown * rowHeight + 2, INK_DIM);
        }
    }

    /** Half-size text, for the small print a real display carries round its product. */
    private void small(GuiGraphics graphics, String text, int x, int y, int color) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(0.5F, 0.5F, 1.0F);
        graphics.drawString(font, text, 0, 0, color, false);
        graphics.pose().popPose();
    }

    private String fit(String text, int width) {
        if (font.width(text) <= width) {
            return text;
        }
        return font.plainSubstrByWidth(text, width - font.width("..")) + "..";
    }

    /** The world's own time of day, as the display's clock. */
    private static String clock() {
        Minecraft minecraft = Minecraft.getInstance();
        long time = minecraft.level == null ? 0L : minecraft.level.getDayTime();
        long inDay = (time + 6000L) % 24000L;
        long hours = inDay / 1000L;
        long minutes = (inDay % 1000L) * 60L / 1000L;
        return String.format(Locale.ROOT, "%02d:%02dZ", hours, minutes);
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

    // ------------------------------------------------------------------------------------------------
    // Mapping and vector primitives.
    // ------------------------------------------------------------------------------------------------

    /** A world X as a pixel offset from the scope centre, through the same mapping the block face uses. */
    private static float screenX(double worldX, double originX, int range) {
        return RadarEchoField.panelToScreen(RadarEchoField.scopeCoord(worldX, originX, range) - 0.5F, RANGE_RADIUS_PX);
    }

    /** A world Z as a pixel offset from the scope centre. */
    private static float screenZ(double worldZ, double originZ, int range) {
        return RadarEchoField.panelToScreen(RadarEchoField.scopeCoord(worldZ, originZ, range) - 0.5F, RANGE_RADIUS_PX);
    }

    private static void vertex(VertexConsumer out, Matrix4f pose, float x, float y, int color) {
        out.addVertex(pose, x, y, 0.0F).setColor(color);
    }

    private static void line(VertexConsumer out, Matrix4f pose, float x1, float y1, float x2, float y2, float width, int color) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = Mth.sqrt(dx * dx + dy * dy);
        if (length < 1.0E-3F) {
            return;
        }
        float nx = -dy / length * width * 0.5F;
        float ny = dx / length * width * 0.5F;
        vertex(out, pose, x1 + nx, y1 + ny, color);
        vertex(out, pose, x2 + nx, y2 + ny, color);
        vertex(out, pose, x2 - nx, y2 - ny, color);
        vertex(out, pose, x1 - nx, y1 - ny, color);
    }

    private static void ring(VertexConsumer out, Matrix4f pose, float x, float y, float radius, float width, int color) {
        int segments = 28;
        for (int i = 0; i < segments; i++) {
            float a0 = (float) (Math.PI * 2.0D * i / segments);
            float a1 = (float) (Math.PI * 2.0D * (i + 1) / segments);
            line(out, pose, x + Mth.cos(a0) * radius, y + Mth.sin(a0) * radius, x + Mth.cos(a1) * radius, y + Mth.sin(a1) * radius, width, color);
        }
    }

    private static void disc(VertexConsumer out, Matrix4f pose, float x, float y, float radius, int color) {
        int segments = 12;
        for (int i = 0; i < segments; i++) {
            float a0 = (float) (Math.PI * 2.0D * i / segments);
            float a1 = (float) (Math.PI * 2.0D * (i + 1) / segments);
            vertex(out, pose, x, y, color);
            vertex(out, pose, x + Mth.cos(a1) * radius, y + Mth.sin(a1) * radius, color);
            vertex(out, pose, x + Mth.cos(a0) * radius, y + Mth.sin(a0) * radius, color);
            vertex(out, pose, x, y, color);
        }
    }

    private static void triangle(VertexConsumer out, Matrix4f pose, float x, float y, float half, float height, int color) {
        float top = y - height * 0.62F;
        float bottom = y + height * 0.38F;
        vertex(out, pose, x, top, color);
        vertex(out, pose, x - half, bottom, color);
        vertex(out, pose, x + half, bottom, color);
        vertex(out, pose, x, top, color);
    }

    /** The radar's TVS symbol: a triangle point down. The base corners are swapped so it winds as {@link #triangle} does. */
    private static void invertedTriangle(VertexConsumer out, Matrix4f pose, float x, float y, float half, float height, int color) {
        float bottom = y + height * 0.62F;
        float top = y - height * 0.38F;
        vertex(out, pose, x, bottom, color);
        vertex(out, pose, x + half, top, color);
        vertex(out, pose, x - half, top, color);
        vertex(out, pose, x, bottom, color);
    }

    private static void diamond(VertexConsumer out, Matrix4f pose, float x, float y, float axis, int color) {
        vertex(out, pose, x, y - axis, color);
        vertex(out, pose, x - axis, y, color);
        vertex(out, pose, x, y + axis, color);
        vertex(out, pose, x + axis, y, color);
    }
}
