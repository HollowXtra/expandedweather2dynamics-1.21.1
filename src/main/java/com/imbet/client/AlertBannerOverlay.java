package com.imbet.client;

import com.imbet.weather.AlertLevel;
import com.imbet.weather.ClientAlertStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.Locale;

/**
 * The alert banner: the fictional in-game EAS product, drawn instead of a chat line.
 *
 * <p>It is laid out like a broadcast lower-third pinned to the top of the screen - a product code tab
 * on the left, the headline product name, the hazard it is about, and the distance from the player -
 * and it is the only place alerts appear. It slides in when an alert arrives, holds while the siren
 * keeps refreshing it, fades out when the hazard is gone, and never restarts its animation for a
 * refresh of the same product. Tornado emergencies pulse; watches sit flat and gold.
 *
 * <p>When an alert arrives without a siren behind it (a server with siren gating switched off) the
 * banner simply omits the station line.
 */
public final class AlertBannerOverlay {
    private static final int WIDTH = 236;
    private static final int HEIGHT = 44;
    private static final int TAB_WIDTH = 32;
    private static final int TOP_MARGIN = 8;
    private static final float SLIDE_MILLIS = 260.0F;
    private static final float FADE_MILLIS = 700.0F;

    /** The level the last frame drew in, so a world change cannot leave a stale banner up. */
    private static ClientLevel lastLevel;

    private AlertBannerOverlay() {
    }

    public static void onHudRenderPost(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.options.hideGui) {
            return;
        }
        if (minecraft.level != lastLevel) {
            // A different world means those distances and products no longer mean anything here.
            lastLevel = minecraft.level;
            ClientAlertStore.clear();
            return;
        }
        ClientAlertStore.Alert alert = ClientAlertStore.active();
        if (alert == null) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        Font font = minecraft.font;
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();

        // Slide in from above the screen, fade out at the end of the alert's life.
        float slide = easeOut(Mth.clamp(ClientAlertStore.ageMillis() / SLIDE_MILLIS, 0.0F, 1.0F));
        float fade = Mth.clamp(ClientAlertStore.remainingMillis() / FADE_MILLIS, 0.0F, 1.0F);
        int x = (screenWidth - WIDTH) / 2;
        int y = Math.round(-HEIGHT + (TOP_MARGIN + HEIGHT) * slide);

        int accent = alert.level().color();
        boolean urgent = alert.level() == AlertLevel.TORNADO_EMERGENCY;
        // An emergency border breathes instead of sitting still, so it reads as the loudest tier.
        float accentAlpha = urgent ? 0.55F + 0.45F * (0.5F + 0.5F * Mth.sin(ClientAlertStore.ageMillis() / 130.0F)) : 1.0F;

        graphics.fill(x + 1, y + 2, x + WIDTH + 1, y + HEIGHT + 2, alpha(0x66000000, fade));
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, alpha(alert.level().isWatch() ? 0xE6121418 : 0xE60B0F12, fade));
        // Accent tab, then the plate border, both in the product's own colour.
        graphics.fill(x, y, x + TAB_WIDTH, y + HEIGHT, alpha(withAlpha(accent, 0xF0), fade * accentAlpha));
        int border = alpha(withAlpha(accent, 0x9C), fade * accentAlpha);
        graphics.fill(x, y, x + WIDTH, y + 1, border);
        graphics.fill(x, y + HEIGHT - 1, x + WIDTH, y + HEIGHT, border);
        graphics.fill(x, y, x + 1, y + HEIGHT, border);
        graphics.fill(x + WIDTH - 1, y, x + WIDTH, y + HEIGHT, border);

        String code = alert.productCode() == null || alert.productCode().isBlank() ? "ALERT" : alert.productCode();
        code = code.length() > 5 ? code.substring(0, 5) : code;
        int codeLeft = x + (TAB_WIDTH - font.width(code)) / 2;
        graphics.drawString(font, code, codeLeft, y + (HEIGHT - 8) / 2, alpha(readableOn(accent), fade), false);

        int textLeft = x + TAB_WIDTH + 8;
        int textRight = x + WIDTH - 8;
        String headline = alert.level().label().toUpperCase(Locale.ROOT);
        graphics.drawString(font, fit(font, headline, textRight - textLeft - 62), textLeft, y + 5, alpha(brighten(accent), fade), true);

        String station = alert.fromStation() ? alert.stationLabel() + " " + alert.stationDistanceBlocks() + " BLK" : "";
        if (!station.isBlank()) {
            graphics.drawString(font, station, textRight - font.width(station), y + 5, alpha(0xFF8FA6B2, fade), true);
        }

        String hazard = alert.source().isBlank() ? alert.label() : alert.label() + " " + alert.source();
        graphics.drawString(font, fit(font, hazard, textRight - textLeft), textLeft, y + 17, alpha(0xFFEAF6FF, fade), true);

        String distance = alert.distanceBlocks() + " BLOCKS AWAY  \u00b7  PATH " + alert.projectedBlocks() + " BLK";
        graphics.drawString(font, fit(font, distance, textRight - textLeft), textLeft, y + 29, alpha(0xFF90A4AE, fade), true);
    }

    private static float easeOut(float value) {
        float inverse = 1.0F - value;
        return 1.0F - inverse * inverse * inverse;
    }

    private static int alpha(int argb, float multiplier) {
        int a = (int) (((argb >>> 24) & 0xFF) * Mth.clamp(multiplier, 0.0F, 1.0F));
        return a << 24 | argb & 0xFFFFFF;
    }

    private static int withAlpha(int argb, int alpha) {
        return alpha << 24 | argb & 0xFFFFFF;
    }

    /** Black on bright accents, white on dark ones, so the product code is always legible. */
    private static int readableOn(int argb) {
        int r = argb >> 16 & 0xFF;
        int g = argb >> 8 & 0xFF;
        int b = argb & 0xFF;
        double luminance = (0.299D * r + 0.587D * g + 0.114D * b) / 255.0D;
        return luminance > 0.62D ? 0xFF0B0F12 : 0xFFFFFFFF;
    }

    /** Nudges an accent toward white so headline text on the dark plate stays readable. */
    private static int brighten(int argb) {
        int r = argb >> 16 & 0xFF;
        int g = argb >> 8 & 0xFF;
        int b = argb & 0xFF;
        int target = 225;
        r = (r + target) / 2;
        g = (g + target) / 2;
        b = (b + target) / 2;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static String fit(Font font, String text, int maxWidth) {
        String value = text == null ? "" : text;
        if (maxWidth <= 0) {
            return "";
        }
        if (font.width(value) <= maxWidth) {
            return value;
        }
        String suffix = "...";
        int limit = maxWidth - font.width(suffix);
        StringBuilder builder = new StringBuilder();
        for (char character : value.toCharArray()) {
            if (font.width(builder.toString() + character) > limit) {
                break;
            }
            builder.append(character);
        }
        return builder + suffix;
    }
}
