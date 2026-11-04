package com.imbet.client;

import com.imbet.Config;
import com.imbet.network.ComputerFeedPayload;
import com.imbet.radar.ClientRadarStore;
import com.imbet.radar.RadarStormSnapshot;
import com.imbet.spc.ComputerTab;
import com.imbet.spc.WeatherComputerMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;
import java.util.Locale;

/**
 * The weather computer's screen: a station you read rather than a machine you poke.
 *
 * <p>Three tabs, because a station answers three questions and a machine that shows one product at a time answers
 * the wrong one as often as the right one. The scope is what the sky is doing over the station, the record is what
 * it has already done to whoever is reading, and the warnings are what is in force. All three arrive together from
 * the server (see {@link ComputerFeedPayload}) and this screen only decides which one is on top, so the tabs cost a
 * packet a second and nothing else.
 *
 * <p>The scope is drawn from the same snapshot the block face and the handheld are drawn from, through the same
 * field: every position, return size and motion track on this tab comes out of {@link RadarEchoField}, which is the
 * only place the product maths lives. What is smaller here is the resolution, not the picture - which is what keeps
 * a distance on this screen meaning the same distance as on the radar it is a copy of.
 *
 * <p>Lines are clipped rather than wrapped. A tab is a page, and a page that reflows the layout every time a storm
 * label grows is harder to read than one that cuts it off at the edge.
 */
public class WeatherComputerScreen extends AbstractContainerScreen<WeatherComputerMenu> {
    private static final int PANEL = 0xEE0A1513;
    private static final int BORDER = 0xFF5DB6B2;
    private static final int TEXT = 0xFFE6F3F1;
    private static final int DIM_TEXT = 0xFF9FD5D1;
    private static final int TAB_ACTIVE = 0xFF1E6353;
    private static final int TAB_IDLE = 0xFF10302C;
    private static final int SCOPE_FACE = 0xFF04100E;
    private static final int RING = 0x7A2FA98C;
    private static final int RIM = 0xFF1E6353;
    private static final int CROSSHAIR = 0x55257F6B;
    private static final int BEAM = 0x4CE6BC;

    /** Tab strip geometry, in pixels inside the panel. */
    private static final int TAB_INSET_X = 6;
    private static final int TAB_TOP = 20;
    private static final int TAB_WIDTH = 88;
    private static final int TAB_HEIGHT = 14;

    /** Where the content starts, below the strip and the line that says what the tab is looking at. */
    private static final int CONTENT_TOP = 50;

    /** The scope: its radius, and how far its centre sits from the panel's left edge. */
    private static final int SCOPE_RADIUS_PX = 52;
    private static final int SCOPE_CENTER_INSET_X = 64;

    /** The column the text beside the scope starts at. */
    private static final int TEXT_COLUMN_INSET_X = 126;

    /** The line height of a list of readout lines. */
    private static final int LINE_HEIGHT = 10;

    private ComputerTab tab = ComputerTab.RADAR;

    /**
     * The feed, as it last arrived.
     *
     * <p>It is held here rather than on the menu because a client-side menu is rebuilt on every window resize and
     * would drop it; the same reason the radar's own screen looks its feed up by origin in {@link ClientRadarStore}.
     * A station that stops sending leaves the last page on screen, which is what a frozen instrument should look
     * like rather than a blank one. It is cleared when a station's screen closes, so a stale page cannot be shown by
     * the next computer a player opens.
     */
    private static ComputerFeedPayload feed;

    public WeatherComputerScreen(WeatherComputerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = 276;
        imageHeight = 190;
        inventoryLabelY = imageHeight + 1000;
    }

    /** Which tab is on top. Kept for a caller that wants to open a station on a particular page. */
    public ComputerTab tab() {
        return tab;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // The panel draws its own text where it wants it, so the default centred title and inventory label are
        // suppressed rather than drawn twice.
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL);
        graphics.renderOutline(x, y, imageWidth, imageHeight, BORDER);
        graphics.drawString(font, "Weather Computer", x + TAB_INSET_X, y + 8, TEXT, false);
        drawTabs(graphics, x, y, mouseX, mouseY);
        graphics.drawString(font, tab.note(), x + TAB_INSET_X, y + 36, DIM_TEXT, false);

        // The page belongs to a station, and a station's page is only shown at that station: two computers in one
        // base send two feeds, and a screen that drew whichever arrived last would show one building's weather in
        // the other.
        ComputerFeedPayload payload = feed != null && feed.origin().equals(menu.computerPos()) ? feed : null;
        if (payload == null) {
            graphics.drawString(font, "Waiting for the station...", x + TAB_INSET_X, y + CONTENT_TOP, DIM_TEXT, false);
            return;
        }
        if (tab == ComputerTab.RADAR) {
            drawScope(graphics, x, y, partialTick, payload);
        } else {
            drawLines(graphics, x, y, payload.lines(tab.index()), imageWidth - TAB_INSET_X * 2);
        }
    }

    /** Called by the client packet handler when a station pushes its tabs. */
    public static void accept(ComputerFeedPayload payload) {
        feed = payload;
    }

    /** Forgets a station's page, so the next computer a player opens cannot show the last one's. */
    public static void clear() {
        feed = null;
    }

    /** The tabs, with the one on top marked. */
    private void drawTabs(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        for (ComputerTab candidate : ComputerTab.values()) {
            int tabX = x + TAB_INSET_X + candidate.index() * TAB_WIDTH;
            int tabY = y + TAB_TOP;
            boolean active = candidate == tab;
            boolean hovered = mouseX >= tabX && mouseX < tabX + TAB_WIDTH - 2
                    && mouseY >= tabY && mouseY < tabY + TAB_HEIGHT;
            graphics.fill(tabX, tabY, tabX + TAB_WIDTH - 2, tabY + TAB_HEIGHT,
                    active ? TAB_ACTIVE : TAB_IDLE);
            if (hovered && !active) {
                graphics.renderOutline(tabX, tabY, TAB_WIDTH - 2, TAB_HEIGHT, BORDER);
            }
            graphics.drawString(font, candidate.title(), tabX + 4, tabY + 3, active ? TEXT : DIM_TEXT, false);
        }
    }

    /** Lines of a tab, clipped to the panel's width. */
    private void drawLines(GuiGraphics graphics, int x, int y, List<ComputerFeedPayload.Line> lines, int width) {
        int row = 0;
        for (ComputerFeedPayload.Line line : lines) {
            int lineY = y + CONTENT_TOP + row * LINE_HEIGHT;
            if (lineY + LINE_HEIGHT > y + imageHeight - TAB_INSET_X) {
                break;
            }
            graphics.drawString(font, clip(line.text(), width), x + TAB_INSET_X, lineY, line.color(), false);
            row++;
        }
    }

    /** The scope, and the station's own instruments beside it. */
    private void drawScope(GuiGraphics graphics, int x, int y, float partialTick, ComputerFeedPayload payload) {
        int centerX = x + SCOPE_CENTER_INSET_X;
        int centerY = y + CONTENT_TOP + SCOPE_RADIUS_PX;
        RadarSnapshotPayloadView view = feedFor();
        List<RadarStormSnapshot> storms = view.storms();
        int range = view.range();

        fillDisc(graphics, centerX, centerY, SCOPE_RADIUS_PX, SCOPE_FACE);
        strokeCircle(graphics, centerX, centerY, Math.round(SCOPE_RADIUS_PX * 0.25F), RING);
        strokeCircle(graphics, centerX, centerY, Math.round(SCOPE_RADIUS_PX * 0.5F), RING);
        strokeCircle(graphics, centerX, centerY, Math.round(SCOPE_RADIUS_PX * 0.75F), RING);
        graphics.fill(centerX, centerY - SCOPE_RADIUS_PX, centerX + 1, centerY + SCOPE_RADIUS_PX, CROSSHAIR);
        graphics.fill(centerX - SCOPE_RADIUS_PX, centerY, centerX + SCOPE_RADIUS_PX, centerY + 1, CROSSHAIR);
        drawSweep(graphics, centerX, centerY, partialTick);
        strokeCircle(graphics, centerX, centerY, SCOPE_RADIUS_PX - 1, RIM);

        for (RadarStormSnapshot storm : storms) {
            drawReturn(graphics, storm, view.originX(), view.originZ(), range, centerX, centerY);
        }
        for (RadarStormSnapshot storm : storms) {
            drawMarkers(graphics, storm, view.originX(), view.originZ(), range, centerX, centerY);
        }

        graphics.drawString(font, String.format(Locale.ROOT, "Reflectivity, %d blocks", range),
                x + TAB_INSET_X, y + imageHeight - 18, DIM_TEXT, false);
        graphics.drawString(font, storms.size() + (storms.size() == 1 ? " return" : " returns"),
                x + TAB_INSET_X, y + imageHeight - 9, DIM_TEXT, false);

        int columnX = x + TEXT_COLUMN_INSET_X;
        int columnWidth = imageWidth - TEXT_COLUMN_INSET_X - TAB_INSET_X;
        int row = 0;
        for (RadarStormSnapshot storm : storms) {
            if (row >= 4) {
                break;
            }
            graphics.drawString(font, clip(stormLine(storm), columnWidth), columnX,
                    y + CONTENT_TOP + row * LINE_HEIGHT, storm.color() | 0xFF000000, false);
            row++;
        }
        row++;
        for (ComputerFeedPayload.Line line : payload.lines(ComputerTab.RADAR.index())) {
            if (y + CONTENT_TOP + row * LINE_HEIGHT + LINE_HEIGHT > y + imageHeight - TAB_INSET_X) {
                break;
            }
            graphics.drawString(font, clip(line.text(), columnWidth), columnX,
                    y + CONTENT_TOP + row * LINE_HEIGHT, line.color(), false);
            row++;
        }
    }

    /** The feed as the drawing code wants it: a list of storms, where the sweep is centred, and how far it sees. */
    private RadarSnapshotPayloadView feedFor() {
        var feed = ClientRadarStore.feedFor(menu.computerPos());
        if (feed == null) {
            return new RadarSnapshotPayloadView(List.of(), menu.computerPos().getX(), menu.computerPos().getZ(),
                    Config.RADAR_RANGE_BLOCKS.get());
        }
        return new RadarSnapshotPayloadView(feed.storms(), menu.computerPos().getX(), menu.computerPos().getZ(), feed.range());
    }

    private record RadarSnapshotPayloadView(List<RadarStormSnapshot> storms, double originX, double originZ, int range) {
    }

    /** One storm as a line of the list: what it is, how hard it is blowing and what it is doing. */
    private static String stormLine(RadarStormSnapshot storm) {
        return String.format(Locale.ROOT, "%s %.0f mph %s", storm.label(), storm.windMph(),
                storm.stageLabel().isBlank() ? storm.risk() : storm.stageLabel());
    }

    /** A return, at the size the shared field gives it, in the colour the storm itself carries. */
    private void drawReturn(GuiGraphics graphics, RadarStormSnapshot storm, double originX, double originZ,
                            int range, int centerX, int centerY) {
        float size = RadarEchoField.panelToScreen(RadarEchoField.returnSize(storm, range), SCOPE_RADIUS_PX);
        if (size <= 0.0F) {
            return;
        }
        float rx = scopeX(storm.x(), originX, range);
        float rz = scopeZ(storm.z(), originZ, range);
        int radius = Math.max(1, Math.round(size));
        fillDisc(graphics, Math.round(centerX + rx), Math.round(centerY + rz), radius, storm.color());
    }

    /** The markers over a return: a cross where a tornado is, and a dot for every sub-vortex. */
    private void drawMarkers(GuiGraphics graphics, RadarStormSnapshot storm, double originX, double originZ,
                             int range, int centerX, int centerY) {
        float size = RadarEchoField.panelToScreen(RadarEchoField.returnSize(storm, range), SCOPE_RADIUS_PX);
        if (size <= 0.0F) {
            return;
        }
        float circulationX = scopeX(storm.circulationX(), originX, range);
        float circulationZ = scopeZ(storm.circulationZ(), originZ, range);
        if (storm.hasConfirmedCirculation() || storm.vortex()) {
            int arm = Math.max(3, Math.round(size * 1.35F));
            int cx = Math.round(centerX + circulationX);
            int cz = Math.round(centerY + circulationZ);
            graphics.fill(cx - arm, cz, cx + arm + 1, cz + 1, 0xFFFFF9C8);
            graphics.fill(cx, cz - arm, cx + 1, cz + arm + 1, 0xFFFFF9C8);
        }
    }

    /** The beam, at the rate the block face sweeps at, so two stations do not sweep against each other. */
    private void drawSweep(GuiGraphics graphics, int centerX, int centerY, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        long ticks = minecraft.level == null ? 0L : minecraft.level.getGameTime();
        float angle = (ticks + partialTick) * 0.055F;
        for (int step = -10; step <= 10; step++) {
            float delta = step * 0.07F;
            double intensity = Math.exp(-Math.abs(delta) * 3.2D) * 0.42D + (step == 0 ? 0.55D : 0.0D);
            if (intensity < 0.05D) {
                continue;
            }
            int alpha = (int) Mth.clamp(intensity * 190.0D, 0.0D, 255.0D);
            int color = (alpha << 24) | (BEAM & 0x00FFFFFF);
            for (float t = 0.15F; t <= 1.0F; t += 0.12F) {
                int px = centerX + Math.round(Mth.cos(angle + delta) * SCOPE_RADIUS_PX * t);
                int py = centerY + Math.round(Mth.sin(angle + delta) * SCOPE_RADIUS_PX * t);
                if (insideScope(centerX, centerY, px, py)) {
                    graphics.fill(px, py, px + 1, py + 1, color);
                }
            }
        }
    }

    /** Where a world coordinate lands on this scope, in pixels, from the shared field's own projection. */
    private static float scopeX(double worldX, double originX, int range) {
        return RadarEchoField.panelToScreen(RadarEchoField.scopeCoord(worldX, originX, range) - 0.5F, SCOPE_RADIUS_PX);
    }

    private static float scopeZ(double worldZ, double originZ, int range) {
        return RadarEchoField.panelToScreen(RadarEchoField.scopeCoord(worldZ, originZ, range) - 0.5F, SCOPE_RADIUS_PX);
    }

    private static boolean insideScope(int centerX, int centerY, int px, int py) {
        int dx = px - centerX;
        int dy = py - centerY;
        return dx * dx + dy * dy <= SCOPE_RADIUS_PX * SCOPE_RADIUS_PX;
    }

    private static void fillDisc(GuiGraphics graphics, int centerX, int centerY, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int half = (int) Math.sqrt(Math.max(0, radius * radius - dy * dy));
            graphics.fill(centerX - half, centerY + dy, centerX + half + 1, centerY + dy + 1, color);
        }
    }

    private static void strokeCircle(GuiGraphics graphics, int centerX, int centerY, int radius, int color) {
        for (int step = 0; step < 360; step += 3) {
            double angle = Math.toRadians(step);
            int px = centerX + Math.round(Mth.cos((float) angle) * radius);
            int py = centerY + Math.round(Mth.sin((float) angle) * radius);
            graphics.fill(px, py, px + 1, py + 1, color);
        }
    }

    private String clip(String text, int width) {
        return font.width(text) <= width ? text : font.plainSubstrByWidth(text, Math.max(4, width - 6)) + "..";
    }

    @Override
    public void removed() {
        super.removed();
        clear();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (ComputerTab candidate : ComputerTab.values()) {
                int tabX = leftPos + TAB_INSET_X + candidate.index() * TAB_WIDTH;
                int tabY = topPos + TAB_TOP;
                if (mouseX >= tabX && mouseX < tabX + TAB_WIDTH - 2
                        && mouseY >= tabY && mouseY < tabY + TAB_HEIGHT) {
                    tab = candidate;
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
