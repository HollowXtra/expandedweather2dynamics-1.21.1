package com.imbet.client;

import com.imbet.radar.ClientRadarStore;
import com.imbet.radar.RadarMenu;
import com.imbet.radar.RadarStormSnapshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

public class RadarScreen extends AbstractContainerScreen<RadarMenu> {
    private static final int PANEL = 0xEE101416;
    private static final int GRID = 0x553D8A8A;
    private static final int TEXT = 0xFFE6F3F1;

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
        graphics.renderOutline(x, y, imageWidth, imageHeight, 0xFF5DB6B2);

        int scopeX = x + 12;
        int scopeY = y + 20;
        int scopeSize = 144;
        int centerX = scopeX + scopeSize / 2;
        int centerY = scopeY + scopeSize / 2;

        graphics.fill(scopeX, scopeY, scopeX + scopeSize, scopeY + scopeSize, 0xFF06100F);
        graphics.renderOutline(scopeX, scopeY, scopeSize, scopeSize, 0xFF2F7772);
        drawRing(graphics, centerX, centerY, 64);
        drawRing(graphics, centerX, centerY, 42);
        drawRing(graphics, centerX, centerY, 21);
        graphics.fill(centerX - 1, scopeY, centerX + 1, scopeY + scopeSize, GRID);
        graphics.fill(scopeX, centerY - 1, scopeX + scopeSize, centerY + 1, GRID);

        List<RadarStormSnapshot> storms = ClientRadarStore.stormsFor(menu.radarPos());
        int range = com.imbet.Config.RADAR_RANGE_BLOCKS.get();
        for (RadarStormSnapshot storm : storms) {
            int sx = centerX + Mth.clamp((int) ((storm.x() - menu.radarPos().getX()) / range * (scopeSize / 2.0D)), -scopeSize / 2 + 3, scopeSize / 2 - 3);
            int sy = centerY + Mth.clamp((int) ((storm.z() - menu.radarPos().getZ()) / range * (scopeSize / 2.0D)), -scopeSize / 2 + 3, scopeSize / 2 - 3);
            int radius = storm.vortex() ? 4 + storm.severity() : 3 + storm.severity();
            graphics.fill(sx - radius, sy - radius, sx + radius, sy + radius, storm.color());
            graphics.renderOutline(sx - radius, sy - radius, radius * 2, radius * 2, 0xFFFFFFFF);
            drawMotion(graphics, sx, sy, storm);
        }

        graphics.drawString(font, title, x + 10, y + 8, TEXT, false);
        graphics.drawString(font, "Range " + range + " blocks", scopeX, scopeY + scopeSize + 7, 0xFF9FD5D1, false);

        int listX = x + 166;
        int listY = y + 22;
        graphics.drawString(font, "SPC / EAS", listX, y + 8, TEXT, false);
        if (storms.isEmpty()) {
            graphics.drawString(font, "No returns", listX, listY, 0xFF9FD5D1, false);
            return;
        }
        for (int i = 0; i < storms.size() && i < 9; i++) {
            RadarStormSnapshot storm = storms.get(i);
            int rowY = listY + i * 17;
            graphics.fill(listX, rowY + 1, listX + 7, rowY + 8, storm.color());
            graphics.drawString(font, storm.label(), listX + 11, rowY, TEXT, false);
            graphics.drawString(font, storm.risk(), listX + 11, rowY + 8, 0xFFB5C9C7, false);
        }
    }

    private void drawRing(GuiGraphics graphics, int centerX, int centerY, int radius) {
        graphics.renderOutline(centerX - radius, centerY - radius, radius * 2, radius * 2, GRID);
    }

    private void drawMotion(GuiGraphics graphics, int sx, int sy, RadarStormSnapshot storm) {
        int dx = Mth.clamp((int) Math.round(storm.motionX() * 80.0D), -8, 8);
        int dy = Mth.clamp((int) Math.round(storm.motionZ() * 80.0D), -8, 8);
        if (dx == 0 && dy == 0) {
            return;
        }
        graphics.fill(Math.min(sx, sx + dx), Math.min(sy, sy + dy), Math.max(sx, sx + dx) + 1, Math.max(sy, sy + dy) + 1, 0xFFFFFFFF);
    }
}
