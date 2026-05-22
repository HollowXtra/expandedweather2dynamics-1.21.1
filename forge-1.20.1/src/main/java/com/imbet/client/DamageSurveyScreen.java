package com.imbet.client;

import com.imbet.survey.DamageSurveyResult;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

public class DamageSurveyScreen extends Screen {
    private final DamageSurveyResult result;

    public DamageSurveyScreen(DamageSurveyResult result) {
        super(Component.literal("Damage Survey"));
        this.result = result;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int panelWidth = Math.min(440, width - 20);
        int panelHeight = Math.min(282, height - 20);
        int x = (width - panelWidth) / 2;
        int y = (height - panelHeight) / 2;
        graphics.fill(x, y, x + panelWidth, y + panelHeight, 0xFF05090B);
        graphics.fill(x + 2, y + 2, x + panelWidth - 2, y + panelHeight - 2, 0xFF0A1012);
        graphics.renderOutline(x, y, panelWidth, panelHeight, 0xFF6EC4C2);
        graphics.renderOutline(x + 2, y + 2, panelWidth - 4, panelHeight - 4, 0x6637A6A3);

        int text = 0xFFE8F5F5;
        int muted = 0xFFC2D6D6;
        drawText(graphics, result.tornadoId() + "  " + result.efRating(), x + 14, y + 12, efColor(result.efRating()));
        drawText(graphics, result.estimatedMph() + "+ mph estimate  confidence " + result.confidence(), x + 14, y + 27, text);
        drawText(graphics, "Width " + result.pathWidthBlocks() + " blocks / " + result.widthMiles() + " mi", x + 14, y + 42, muted);
        drawText(graphics, "Track max " + Math.round(result.maxTrackWindMph()) + " mph / " + Math.round(result.maxTrackWidthBlocks()) + " blocks", x + 14, y + 57, muted);

        int mapX = x + 14;
        int mapY = y + 78;
        int mapSize = Math.min(186, panelHeight - 108);
        drawTrackMap(graphics, mapX, mapY, mapSize);

        int listX = mapX + mapSize + 16;
        int row = mapY + 2;
        drawText(graphics, "Indicators", listX, row, text);
        row += 14;
        drawText(graphics, "Scoured: " + result.scouredSurfaceBlocks(), listX, row, muted);
        row += 12;
        drawText(graphics, "Debarked logs: " + result.debarkedLogs(), listX, row, muted);
        row += 12;
        drawText(graphics, "Damage columns: " + result.damagedColumns(), listX, row, muted);
        row += 18;
        drawText(graphics, "Track ticks", listX, row, text);
        row += 12;
        drawText(graphics, result.startTick() + " - " + result.endTick(), listX, row, muted);
        row += 18;
        drawText(graphics, "Merge history", listX, row, text);
        row += 12;
        String merge = result.mergeSummary().isBlank() ? "None recorded" : result.mergeSummary();
        drawText(graphics, font.plainSubstrByWidth(merge, Math.max(112, panelWidth - (listX - x) - 18)), listX, row, muted);
        drawText(graphics, "Recent track segment only. Fictional in-game estimate; not official.", x + 14, y + panelHeight - 17, 0xFF7E9898);
    }

    private void drawTrackMap(GuiGraphics graphics, int x, int y, int size) {
        graphics.fill(x, y, x + size, y + size, 0xFF030707);
        graphics.renderOutline(x, y, size, size, 0xFF2C7270);
        graphics.renderOutline(x + 3, y + 3, size - 6, size - 6, 0x552C7270);
        graphics.fill(x + size / 2, y + 4, x + size / 2 + 1, y + size - 4, 0x6625A8A5);
        graphics.fill(x + 4, y + size / 2, x + size - 4, y + size / 2 + 1, 0x6625A8A5);

        List<DamageSurveyResult.MapPoint> points = result.points();
        if (points.isEmpty()) {
            drawText(graphics, "No recent track", x + 8, y + 8, 0xFF88AAAA);
            return;
        }
        double minX = result.surveyX();
        double maxX = result.surveyX();
        double minZ = result.surveyZ();
        double maxZ = result.surveyZ();
        for (DamageSurveyResult.MapPoint point : points) {
            minX = Math.min(minX, point.x());
            maxX = Math.max(maxX, point.x());
            minZ = Math.min(minZ, point.z());
            maxZ = Math.max(maxZ, point.z());
        }
        double span = Math.max(32.0D, Math.max(maxX - minX, maxZ - minZ));
        double cx = (minX + maxX) * 0.5D;
        double cz = (minZ + maxZ) * 0.5D;
        int previousX = Integer.MIN_VALUE;
        int previousY = Integer.MIN_VALUE;
        for (DamageSurveyResult.MapPoint point : points) {
            int px = x + size / 2 + Mth.clamp((int) Math.round((point.x() - cx) / span * (size - 16)), -size / 2 + 5, size / 2 - 5);
            int py = y + size / 2 + Mth.clamp((int) Math.round((point.z() - cz) / span * (size - 16)), -size / 2 + 5, size / 2 - 5);
            int color = point.windMph() >= 200.0F ? 0xFFFF5BF1 : point.windMph() >= 166.0F ? 0xFFE53935 : point.windMph() >= 136.0F ? 0xFFFF9800 : point.windMph() >= 111.0F ? 0xFFFFD54F : 0xFF6EE7B7;
            if (previousX != Integer.MIN_VALUE) {
                drawLine(graphics, previousX, previousY, px, py, color);
            }
            graphics.fill(px - 2, py - 2, px + 3, py + 3, 0x55000000);
            graphics.fill(px - 1, py - 1, px + 2, py + 2, color);
            previousX = px;
            previousY = py;
        }
        int sx = x + size / 2 + Mth.clamp((int) Math.round((result.surveyX() - cx) / span * (size - 16)), -size / 2 + 5, size / 2 - 5);
        int sy = y + size / 2 + Mth.clamp((int) Math.round((result.surveyZ() - cz) / span * (size - 16)), -size / 2 + 5, size / 2 - 5);
        graphics.fill(sx - 4, sy, sx + 5, sy + 1, 0xFFFFFFFF);
        graphics.fill(sx, sy - 4, sx + 1, sy + 5, 0xFFFFFFFF);
    }

    private static void drawLine(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        for (int i = 0; i <= steps; i++) {
            float t = steps == 0 ? 0.0F : i / (float) steps;
            int x = Math.round(Mth.lerp(t, x1, x2));
            int y = Math.round(Mth.lerp(t, y1, y2));
            graphics.fill(x - 1, y - 1, x + 2, y + 2, 0x44000000);
            graphics.fill(x, y, x + 1, y + 1, color);
        }
    }

    private void drawText(GuiGraphics graphics, String text, int x, int y, int color) {
        graphics.drawString(font, text, x, y, color, true);
    }

    private static int efColor(String ef) {
        return switch (ef) {
            case "EF5" -> 0xFFFF7DFF;
            case "EF4" -> 0xFFFF5252;
            case "EF3" -> 0xFFFF9800;
            case "EF2" -> 0xFFFFD54F;
            case "EF1" -> 0xFFB8F26D;
            case "EF0" -> 0xFF6EE7B7;
            default -> 0xFF9CB4B4;
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
