package com.imbet.client;

import com.imbet.network.ServerboundSubmitSurveyPacket;
import com.imbet.survey.DamageIndicator;
import com.imbet.survey.DamageSurveyResult;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class DamageSurveyScreen extends Screen {
    private final DamageSurveyResult result;
    private final Set<DamageIndicator> checkedIndicators = EnumSet.noneOf(DamageIndicator.class);
    private int tab;
    private int selectedEf;

    public DamageSurveyScreen(DamageSurveyResult result) {
        super(Component.literal("Damage Survey"));
        this.result = result;
        this.selectedEf = efTier(result.efRating());
    }

    @Override
    protected void init() {
        rebuildSurveyWidgets();
    }

    private void rebuildSurveyWidgets() {
        clearWidgets();
        int panelWidth = panelWidth();
        int panelHeight = panelHeight();
        int x = (width - panelWidth) / 2;
        int y = (height - panelHeight) / 2;
        addRenderableWidget(Button.builder(Component.literal("Track"), button -> setTab(0)).bounds(x + 12, y + 34, 70, 18).build());
        addRenderableWidget(Button.builder(Component.literal("DIs"), button -> setTab(1)).bounds(x + 86, y + 34, 70, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Submit"), button -> setTab(2)).bounds(x + 160, y + 34, 80, 18).build());
        if (tab == 1) {
            int row = y + 68;
            int colX = x + 18;
            DamageIndicator[] values = DamageIndicator.values();
            for (int i = 0; i < values.length; i++) {
                DamageIndicator indicator = values[i];
                int bx = colX + (i / 5) * 206;
                int by = row + (i % 5) * 22;
                addRenderableWidget(Button.builder(Component.literal((checkedIndicators.contains(indicator) ? "[x] " : "[ ] ") + indicator.label()), button -> {
                    if (!checkedIndicators.remove(indicator)) {
                        checkedIndicators.add(indicator);
                    }
                    rebuildSurveyWidgets();
                }).bounds(bx, by, 196, 18).build());
            }
        } else if (tab == 2) {
            int bx = x + 18;
            int by = y + 84;
            for (int ef = 0; ef <= 5; ef++) {
                final int tier = ef;
                addRenderableWidget(Button.builder(Component.literal((selectedEf == tier ? "> " : "") + "EF" + tier), button -> selectedEf = tier).bounds(bx + ef * 58, by, 52, 18).build());
            }
            addRenderableWidget(Button.builder(Component.literal("File Survey"), button -> submit()).bounds(x + 18, by + 32, 116, 20).build());
        }
    }

    private void setTab(int nextTab) {
        tab = nextTab;
        rebuildSurveyWidgets();
    }

    private void submit() {
        BlockPos pos = minecraft == null || minecraft.player == null
                ? new BlockPos(Mth.floor(result.surveyX()), 0, Mth.floor(result.surveyZ()))
                : new BlockPos(Mth.floor(result.surveyX()), minecraft.player.blockPosition().getY(), Mth.floor(result.surveyZ()));
        PacketDistributor.sendToServer(new ServerboundSubmitSurveyPacket(result.tornadoId(), pos, selectedEf, suggestedMph(), List.copyOf(checkedIndicators)));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Draw our own flat dim layer instead of Minecraft's blurred menu background.
        graphics.fill(0, 0, width, height, 0xA0000000);
        int panelWidth = panelWidth();
        int panelHeight = panelHeight();
        int x = (width - panelWidth) / 2;
        int y = (height - panelHeight) / 2;
        graphics.fill(x, y, x + panelWidth, y + panelHeight, 0xFF05090B);
        graphics.fill(x + 2, y + 2, x + panelWidth - 2, y + panelHeight - 2, 0xFF0A1012);
        graphics.renderOutline(x, y, panelWidth, panelHeight, 0xFF6EC4C2);
        graphics.renderOutline(x + 2, y + 2, panelWidth - 4, panelHeight - 4, 0x6637A6A3);

        drawText(graphics, result.tornadoId() + "  Auto " + result.efRating(), x + 14, y + 12, efColor(result.efRating()));
        drawText(graphics, result.estimatedMph() + "+ mph estimate  confidence " + result.confidence(), x + 188, y + 12, 0xFFE8F5F5);
        drawText(graphics, "Width " + result.pathWidthBlocks() + " blocks / " + result.widthMiles() + " mi", x + 14, y + 57, 0xFFC2D6D6);

        if (tab == 0) {
            renderTrackTab(graphics, x, y, panelWidth, panelHeight);
        } else if (tab == 1) {
            renderIndicatorsTab(graphics, x, y, panelWidth, panelHeight);
        } else {
            renderSubmitTab(graphics, x, y, panelWidth, panelHeight);
        }
        drawText(graphics, "Fictional in-game estimate; not an official NWS rating.", x + 14, y + panelHeight - 17, 0xFF7E9898);
        for (Renderable renderable : renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    private void renderTrackTab(GuiGraphics graphics, int x, int y, int panelWidth, int panelHeight) {
        int mapX = x + 14;
        int mapY = y + 78;
        int mapSize = Math.min(200, panelHeight - 108);
        drawTrackMap(graphics, mapX, mapY, mapSize);
        int listX = mapX + mapSize + 18;
        int row = mapY + 3;
        drawText(graphics, "Track Data", listX, row, 0xFFE8F5F5);
        row += 15;
        drawText(graphics, "Track max " + Math.round(result.maxTrackWindMph()) + " mph", listX, row, 0xFFC2D6D6);
        row += 12;
        drawText(graphics, "Max width " + Math.round(result.maxTrackWidthBlocks()) + " blocks", listX, row, 0xFFC2D6D6);
        row += 12;
        drawText(graphics, "Ticks " + result.startTick() + " - " + result.endTick(), listX, row, 0xFFC2D6D6);
        row += 18;
        drawText(graphics, "Merge History", listX, row, 0xFFE8F5F5);
        row += 13;
        String merge = result.mergeSummary().isBlank() ? "None recorded" : result.mergeSummary();
        drawText(graphics, font.plainSubstrByWidth(merge, Math.max(112, panelWidth - (listX - x) - 18)), listX, row, 0xFFC2D6D6);
    }

    private void renderIndicatorsTab(GuiGraphics graphics, int x, int y, int panelWidth, int panelHeight) {
        int row = y + 184;
        drawText(graphics, "Observed scan: scoured " + result.scouredSurfaceBlocks() + ", debarked logs " + result.debarkedLogs() + ", damage columns " + result.damagedColumns(), x + 18, row, 0xFFC2D6D6);
        row += 14;
        drawText(graphics, "Checked indicators suggest EF" + efTierForMph(suggestedMph()) + " around " + suggestedMph() + " mph.", x + 18, row, 0xFFE8F5F5);
    }

    private void renderSubmitTab(GuiGraphics graphics, int x, int y, int panelWidth, int panelHeight) {
        int row = y + 126;
        drawText(graphics, "Selected rating: EF" + selectedEf, x + 18, row, efColor("EF" + selectedEf));
        row += 16;
        drawText(graphics, "Checklist suggestion: EF" + efTierForMph(suggestedMph()) + " / " + suggestedMph() + " mph", x + 18, row, 0xFFC2D6D6);
        row += 16;
        drawText(graphics, "Server compares your rating against the true recorded wind profile.", x + 18, row, 0xFF9CB4B4);
    }

    private void drawTrackMap(GuiGraphics graphics, int x, int y, int size) {
        graphics.fill(x, y, x + size, y + size, 0xFF030707);
        graphics.renderOutline(x, y, size, size, 0xFF2C7270);
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
            graphics.fill(x, y, x + 1, y + 1, color);
        }
    }

    private int suggestedMph() {
        int mph = result.estimatedMph();
        for (DamageIndicator indicator : checkedIndicators) {
            mph = Math.max(mph, indicator.minMph());
        }
        return mph;
    }

    private static int efTierForMph(int mph) {
        if (mph > 200) {
            return 5;
        }
        if (mph >= 166) {
            return 4;
        }
        if (mph >= 136) {
            return 3;
        }
        if (mph >= 111) {
            return 2;
        }
        if (mph >= 86) {
            return 1;
        }
        return 0;
    }

    private static int efTier(String ef) {
        if (ef == null || !ef.startsWith("EF") || ef.length() < 3) {
            return 0;
        }
        return Mth.clamp(ef.charAt(2) - '0', 0, 5);
    }

    private int panelWidth() {
        return Math.min(500, width - 20);
    }

    private int panelHeight() {
        return Math.min(306, height - 20);
    }

    private void drawText(GuiGraphics graphics, String text, int x, int y, int color) {
        graphics.drawString(font, text, x, y, color, false);
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
