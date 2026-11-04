package com.imbet.client;

import com.imbet.chase.ChaseLogReadout;
import com.imbet.photo.PhotoAlbum;
import com.imbet.photo.PhotoAlbumMenu;
import com.imbet.photo.PhotoScore;
import com.imbet.photo.PhotoShot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * The album: what the player has photographed, best first, with each shot's breakdown on its own line.
 *
 * <p>Everything drawn here is the server's own snapshot - the album object was read back out of the open
 * buffer and is the same type the server files into - so the screen cannot show a shot the server does not
 * have, and ages are counted from the server's clock rather than the client's.
 *
 * <p>Rows rather than a contact sheet of pictures: this mod does not render photographs, so an album that
 * showed thumbnails would be inventing them. What a photograph is here is a score, a subject and a moment,
 * and those are what a chase is remembered by.
 */
public class PhotoAlbumScreen extends AbstractContainerScreen<PhotoAlbumMenu> {
    private static final int PANEL = 0xE60A1513;
    private static final int BORDER = 0xFF5DB6B2;
    private static final int TEXT = 0xFFE6F3F1;
    private static final int DIM_TEXT = 0xFF9FD5D1;
    private static final int RULE = 0x55257F6B;

    private static final int PAD = 10;
    private static final int ROW_HEIGHT = 12;
    /** How many shots the screen has room for; the album may hold more, and says so. */
    private static final int MAX_ROWS = 10;

    public PhotoAlbumScreen(PhotoAlbumMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = 252;
        imageHeight = 186;
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

        graphics.drawString(font, title, x + PAD, y + PAD - 1, TEXT, false);
        PhotoAlbum album = menu.album();
        if (album.shotsTaken() <= 0) {
            graphics.drawString(font, "No photographs filed yet.", x + PAD, y + PAD + 22, DIM_TEXT, false);
            graphics.drawString(font, "Aim at a storm and right-click.", x + PAD, y + PAD + 34, DIM_TEXT, false);
            return;
        }
        graphics.drawString(font, album.shotsTaken() + " filed   average " + album.average()
                + "   best " + album.best().total() + "/" + PhotoScore.MAX, x + PAD, y + PAD + 12, TEXT, false);
        graphics.fill(x + PAD, y + PAD + 24, x + imageWidth - PAD, y + PAD + 25, RULE);

        int lines = Math.min(MAX_ROWS, album.gallery().size());
        for (int index = 0; index < lines; index++) {
            drawShot(graphics, album.gallery().get(index), index + 1, x + PAD, y + PAD + 30 + index * ROW_HEIGHT);
        }
        if (lines < album.gallery().size()) {
            graphics.drawString(font, "... and " + (album.gallery().size() - lines) + " more",
                    x + PAD, y + PAD + 30 + lines * ROW_HEIGHT, DIM_TEXT, false);
        }
        graphics.drawString(font, "Sneak-right-click to read the parts of your last shot",
                x + PAD, y + imageHeight - PAD - 8, DIM_TEXT, false);
    }

    /** One row: its rank, the grade and score, what it was of, and how long ago it was taken. */
    private void drawShot(GuiGraphics graphics, PhotoShot shot, int rank, int x, int y) {
        String head = "#" + rank + " " + shot.grade().label() + " " + shot.total() + "/" + PhotoScore.MAX;
        graphics.drawString(font, head, x, y, gradeColor(shot), false);
        int headWidth = font.width(head) + 8;
        String subject = shot.subjectClass() + " " + shortLabel(shot) + " at "
                + ChaseLogReadout.round(shot.distanceBlocks()) + "b  "
                + ChaseLogReadout.ago(menu.nowTick() - shot.gameTime());
        graphics.drawString(font, font.plainSubstrByWidth(subject, imageWidth - PAD * 2 - headWidth), x + headWidth, y, TEXT, false);
    }

    private String shortLabel(PhotoShot shot) {
        String label = shot.targetLabel() == null || shot.targetLabel().isBlank() ? "Storm" : shot.targetLabel();
        return label.length() > 22 ? label.substring(0, 22) : label;
    }

    private static int gradeColor(PhotoShot shot) {
        return switch (shot.grade()) {
            case EXCELLENT -> 0xFF7CFF7C;
            case GREAT -> 0xFF6FE8E0;
            case GOOD -> 0xFFFFE066;
            case FAIR -> 0xFFD8D8D8;
            case POOR -> 0xFFFF7B6B;
        };
    }

}
