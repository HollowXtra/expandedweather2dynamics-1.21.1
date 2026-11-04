package com.imbet.client.config;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Screen-space shape helpers (rounded rects, circles, diamonds) for the custom config screen.
 *
 * <p>Everything here is expressed as {@link GuiGraphics#fill} spans: they join the same queued GUI
 * batch as the text and backgrounds around them, so shapes layer in call order and share the render
 * state that vanilla GUI drawing already sets up correctly. An earlier version built its own
 * immediate-mode meshes through {@code Tesselator} + {@code BufferUploader}; on 1.21.1 that produced
 * no visible pixels at all (no panel, no toggles, no sliders), leaving the screen a set of labels on
 * a black background that could not be used. Span-based fills avoid that path entirely.
 */
public final class RoundedRectRenderer {
    private RoundedRectRenderer() {
    }

    /** Filled rectangle with rounded corners. */
    public static void render(GuiGraphics g, float x, float y, float w, float h, float radius, int argb) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int x0 = Math.round(x);
        int y0 = Math.round(y);
        int x1 = Math.round(x + w);
        int y1 = Math.round(y + h);
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        float r = Math.min(radius, Math.min(w, h) / 2f);
        int ri = (int) r;
        if (ri <= 0) {
            g.fill(x0, y0, x1, y1, argb);
            return;
        }
        // Body between the corner bands.
        g.fill(x0, y0 + ri, x1, y1 - ri, argb);
        // One span per scanline through each rounded corner, inset by the circle equation.
        for (int i = 0; i < ri; i++) {
            float dy = r - i - 0.5f;
            float inset = r - (float) Math.sqrt(Math.max(0f, r * r - dy * dy));
            int ix = (int) Math.floor(inset);
            g.fill(x0 + ix, y0 + i, x1 - ix, y0 + i + 1, argb);
            g.fill(x0 + ix, y1 - i - 1, x1 - ix, y1 - i, argb);
        }
    }

    /** Filled circle, drawn as one horizontal span per scanline. */
    public static void renderCircle(GuiGraphics g, float cx, float cy, float radius, int argb) {
        if (radius <= 0) {
            return;
        }
        int x = Math.round(cx);
        int y = Math.round(cy);
        int ri = (int) Math.ceil(radius);
        for (int dy = -ri; dy <= ri; dy++) {
            double half = Math.sqrt(Math.max(0.0, radius * radius - (double) dy * dy));
            int hw = (int) Math.round(half);
            if (hw <= 0) {
                continue;
            }
            g.fill(x - hw, y + dy, x + hw, y + dy + 1, argb);
        }
    }

    /** Filled ellipse, drawn as one horizontal span per scanline. Used for a funnel's rings. */
    public static void renderEllipse(GuiGraphics g, float cx, float cy, float rx, float ry, int argb) {
        if (rx <= 0 || ry <= 0) {
            return;
        }
        int y = Math.round(cy);
        int ryi = (int) Math.ceil(ry);
        int xi = Math.round(cx);
        for (int dy = -ryi; dy <= ryi; dy++) {
            double t = dy / (double) ry;
            if (t * t > 1.0) {
                continue;
            }
            int hw = (int) Math.round(rx * Math.sqrt(1.0 - t * t));
            if (hw <= 0) {
                continue;
            }
            g.fill(xi - hw, y + dy, xi + hw, y + dy + 1, argb);
        }
    }

    /**
     * A straight line of a given thickness, walked in unit steps along its length. Everything the config
     * screen draws is a fill span, so this is too - which keeps lines in the same queued batch as the shapes
     * and the text around them instead of needing a mesh of their own.
     */
    public static void line(GuiGraphics g, float x0, float y0, float x1, float y1, float thickness, int argb) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)));
        if (steps <= 0) {
            return;
        }
        int t = Math.max(1, Math.round(thickness));
        int half = t / 2;
        for (int i = 0; i <= steps; i++) {
            double f = i / (double) steps;
            int x = Math.round((float) (x0 + dx * f));
            int y = Math.round((float) (y0 + dy * f));
            g.fill(x - half, y - half, x - half + t, y - half + t, argb);
        }
    }

    /** Small downward chevron, drawn as two stepped strokes so it reads as a thin arrow rather than a glyph. */
    public static void chevron(GuiGraphics g, int cx, int cy, boolean down, int argb) {
        int half = 4;
        for (int i = 0; i <= half; i++) {
            int dy = down ? i : -i;
            g.fill(cx - half + i, cy + dy, cx - half + i + 1, cy + dy + 1, argb);
            g.fill(cx + half - i, cy + dy, cx + half - i + 1, cy + dy + 1, argb);
        }
    }

    /** Border plus fill in one call: the border is the outer box, the fill an inset copy of it. */
    public static void box(GuiGraphics g, float x, float y, float w, float h, float radius, int fill, int border) {
        render(g, x, y, w, h, radius, border);
        render(g, x + 1, y + 1, w - 2, h - 2, Math.max(0, radius - 1), fill);
    }

    /** A square rotated 45 degrees - the toggle knob shape. */
    public static void renderDiamond(GuiGraphics g, float cx, float cy, float radius, int argb) {
        if (radius <= 0) {
            return;
        }
        int x = Math.round(cx);
        int y = Math.round(cy);
        int ri = (int) Math.ceil(radius);
        for (int dy = -ri; dy <= ri; dy++) {
            int hw = Math.round((1f - Math.abs(dy) / radius) * radius);
            if (hw <= 0) {
                continue;
            }
            g.fill(x - hw, y + dy, x + hw, y + dy + 1, argb);
        }
    }
}
