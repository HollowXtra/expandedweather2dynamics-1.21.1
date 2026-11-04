package com.imbet.client;

import com.imbet.Config;
import com.imbet.network.PhotoAimPayload;
import com.imbet.photo.PhotoAim;
import com.imbet.photo.PhotoScore;
import com.imbet.photo.PhotoService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * The camera's viewfinder: what it is pointed at, and what that framing and that distance are worth, before the
 * shutter goes.
 *
 * <p>The score only ever spoke after the fact - take the photograph, be told the framing was half out of frame and
 * the storm was two hundred blocks too far - which meant the one thing a photographer does, compose, had no
 * feedback at all. So a held camera now draws a reticle with the frame's rim and the band that earns full marks
 * inside it, a mark where the subject actually sits, and two lines saying what a shot taken now would be given.
 *
 * <p>It shows exactly the two parts of the score that geometry decides and nothing else. The light, the
 * foreground, the rain and how much of the funnel is behind a hill are all facts about the world that a client
 * looking through a camera cannot know, so the viewfinder does not guess at them - a viewfinder that showed five
 * parts and was wrong about three would be worse than one that shows two and is right.
 *
 * <p>The frame is drawn square because that is the frame the score measures: the cone the marks come from treats
 * the lens as having one half-angle in both directions. On a wide window the rendered view is wider than that, so
 * a storm the reticle calls off the rim can still be visible on screen; the reticle is what the marks will say,
 * which is the thing it exists to tell you.
 */
public final class PhotoViewfinder {
    /** Half the box that stands for the frame's rim, in pixels: where the framing marks reach nothing. */
    private static final int RIM_PIXELS = 46;
    private static final int MARKER_PIXELS = 2;
    private static final int LINE_HEIGHT = 11;
    private static final int PLATE_MARGIN = 4;
    private static final int LOCKED_COLOR = 0xFFE8F4F8;
    private static final int PART_COLOR = 0xFFB4C6CE;
    private static final int EMPTY_COLOR = 0xFF8C9AA3;
    private static final int IDEAL_COLOR = 0xFF7BE08A;
    private static final int RIM_COLOR = 0x99D8E6EC;
    private static final int COMFORT_COLOR = 0x66A8D8C0;
    private static final int PLATE_COLOR = 0x8C0B0F12;

    /** The level the last frame drew in, so a world change cannot leave a stale reticle up. */
    private static ClientLevel lastLevel;

    private PhotoViewfinder() {
    }

    public static void onHudRenderPost(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null || minecraft.options == null || minecraft.options.hideGui) {
            return;
        }
        if (minecraft.level != lastLevel) {
            // A different world means those distances and those storms no longer mean anything here.
            lastLevel = minecraft.level;
            PhotoAimStore.clear();
            return;
        }
        if (!Config.PHOTO_ENABLED.get() || !Config.PHOTO_VIEWFINDER.get()) {
            return;
        }
        if (!PhotoService.holdsCamera(minecraft.player)) {
            return;
        }
        PhotoAimPayload payload = PhotoAimStore.current();
        if (payload == null) {
            return;
        }
        Vec3 eye = minecraft.player.getEyePosition(1.0F);
        Vec3 aimDirection = minecraft.player.getLookAngle();
        double fov = minecraft.options.fov().get();
        // Re-measured here, every frame, from where the camera is pointing now: what the server sent is the list of
        // candidates and the band, and everything that moves when a player turns is arithmetic off the aim.
        PhotoAim aim = PhotoAim.of(payload.targets(),
                eye.x, eye.y, eye.z, aimDirection.x, aimDirection.y, aimDirection.z, fov,
                payload.minSubjectMph(), payload.maxDistanceBlocks(),
                payload.sweetSpotMinBlocks(), payload.sweetSpotMaxBlocks());
        render(event.getGuiGraphics(), minecraft, aim);
    }

    /** Draws the reticle around the crosshair and the readings under it. */
    private static void render(GuiGraphics graphics, Minecraft minecraft, PhotoAim aim) {
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        int centreX = screenWidth / 2;
        int centreY = screenHeight / 2;
        boolean present = aim.present();
        int accent = present ? (aim.ideal() ? IDEAL_COLOR : LOCKED_COLOR) : EMPTY_COLOR;

        graphics.renderOutline(centreX - RIM_PIXELS, centreY - RIM_PIXELS,
                RIM_PIXELS * 2, RIM_PIXELS * 2, present ? RIM_COLOR : EMPTY_COLOR);
        int comfort = (int) Math.round(RIM_PIXELS * PhotoScore.COMFORTABLE_MAX);
        graphics.renderOutline(centreX - comfort, centreY - comfort,
                comfort * 2, comfort * 2, present ? COMFORT_COLOR : EMPTY_COLOR);

        if (present) {
            // Clamped to the rim: a subject past it is not in the photograph, and a marker wandering off the box
            // would read as a subject that is nearly in frame.
            int markerX = centreX + (int) Math.round(Mth.clamp(aim.horizontalFraction(), -1.0D, 1.0D) * RIM_PIXELS);
            int markerY = centreY - (int) Math.round(Mth.clamp(aim.verticalFraction(), -1.0D, 1.0D) * RIM_PIXELS);
            graphics.fill(markerX - MARKER_PIXELS, markerY - MARKER_PIXELS,
                    markerX + MARKER_PIXELS, markerY + MARKER_PIXELS, accent);
        }

        String subject = aim.subjectLine();
        String framing = aim.framingLine();
        String distance = aim.distanceLine();
        if (!present) {
            subject = aim.statusLine();
            framing = "";
            distance = "";
        }
        int plateTop = screenHeight - 92;
        int width = Math.max(minecraft.font.width(subject),
                Math.max(minecraft.font.width(framing), minecraft.font.width(distance)));
        int left = centreX - width / 2;
        int rows = 1 + (framing.isBlank() ? 0 : 1) + (distance.isBlank() ? 0 : 1);
        graphics.fill(left - PLATE_MARGIN, plateTop - PLATE_MARGIN,
                left + width + PLATE_MARGIN, plateTop + rows * LINE_HEIGHT + PLATE_MARGIN - 2, PLATE_COLOR);
        graphics.drawString(minecraft.font, subject, left, plateTop, accent, true);
        if (!framing.isBlank()) {
            graphics.drawString(minecraft.font, framing, left, plateTop + LINE_HEIGHT, PART_COLOR, true);
        }
        if (!distance.isBlank()) {
            graphics.drawString(minecraft.font, distance, left, plateTop + LINE_HEIGHT * 2, PART_COLOR, true);
        }
    }
}
