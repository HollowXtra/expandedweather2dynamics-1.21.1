package com.imbet.client.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;
import net.neoforged.neoforge.common.ModConfigSpec.ValueSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * One editable config entry. Values are staged locally and only written to the spec when
 * {@link #apply()} runs (pressing Done). Rows receive absolute mouse coordinates and use
 * the geometry recorded during the last render call.
 */
abstract class ConfigRow {
    static final int HEIGHT = 28;
    static final int CONTROL_W = 170;

    final ConfigValue<?> value;
    final ValueSpec spec;
    final Class<?> clazz;
    final String label;
    final boolean needsRestart;

    private boolean dirty;
    private Object staged;
    private int lastX;
    private int lastY;
    private int lastW;

    ConfigRow(ConfigValue<?> value, ValueSpec spec) {
        this.value = value;
        this.spec = spec;
        this.clazz = resolveClazz(value, spec);
        this.label = pretty(last(value.getPath()));
        this.needsRestart = spec.restartType() != ModConfigSpec.RestartType.NONE;
    }

    /**
     * The runtime type edits are parsed as. NeoForge's ranged {@code defineInRange} overloads route
     * through the plain {@code define} path, which leaves {@code Object.class} on the spec - so the
     * spec's own class is useless for numbers. Fall back to the range's type, then to whatever the
     * value currently holds.
     */
    private static Class<?> resolveClazz(ConfigValue<?> cv, ValueSpec spec) {
        Class<?> declared = spec.getClazz();
        if (declared != null && declared != Object.class) {
            return declared;
        }
        ModConfigSpec.Range<?> range = spec.getRange();
        if (range != null) {
            Class<?> ranged = range.getClazz();
            if (ranged != null && ranged != Object.class) {
                return ranged;
            }
        }
        try {
            Object current = cv.get();
            if (current != null) {
                return current.getClass();
            }
        } catch (RuntimeException ignored) {
            // Config not loaded yet - treat it as free text.
        }
        return String.class;
    }

    static ConfigRow create(ConfigValue<?> cv) {
        ValueSpec spec = cv.getSpec();
        Class<?> clazz = resolveClazz(cv, spec);
        if (clazz == Boolean.class) {
            return new ToggleRow(cv, spec);
        }
        if (clazz.isEnum()) {
            return new CycleRow(cv, spec);
        }
        if (spec.getRange() != null && Number.class.isAssignableFrom(clazz) && sliderFits(clazz, spec.getRange())) {
            return new SliderRow(cv, spec);
        }
        return new TextRow(cv, spec);
    }

    /**
     * A slider can only land on a specific value when its range is short enough that a pixel of
     * travel is roughly one step. Wide ranges (hundreds of blocks, thousands of ticks) stay as text
     * fields so exact values can still be typed instead of dragged at.
     */
    private static boolean sliderFits(Class<?> clazz, ModConfigSpec.Range<?> range) {
        double span = ((Number) range.getMax()).doubleValue() - ((Number) range.getMin()).doubleValue();
        boolean integral = clazz == Integer.class || clazz == Long.class;
        return integral ? span <= 256.0D : span <= 16.0D;
    }

    Object current() {
        return value.get();
    }

    Object shown() {
        return dirty ? staged : current();
    }

    void stage(Object v) {
        staged = v;
        dirty = true;
    }

    boolean isDirty() {
        return dirty;
    }

    /** Writes the staged value to the spec. Returns false if the staged value is invalid (text rows). */
    boolean apply() {
        if (!dirty) {
            return true;
        }
        setChecked(staged);
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    void setChecked(Object v) {
        ((ConfigValue) value).set(v);
    }

    // ---- layout ----

    abstract void render(GuiGraphics g, Font font, int x, int y, int w, int mx, int my, float pt);

    int controlX() {
        return lastX + lastW - CONTROL_W;
    }

    int rowY() {
        return lastY;
    }

    void recordGeometry(int x, int y, int w) {
        this.lastX = x;
        this.lastY = y;
        this.lastW = w;
    }

    void drawLabel(GuiGraphics g, Font font, int x, int y) {
        int avail = lastW - CONTROL_W - 14;
        String t = font.plainSubstrByWidth(label, Math.max(avail, 10));
        g.drawString(font, t, x + 4, y + (HEIGHT - 9) / 2, 0xFFD6D6D6);
        if (needsRestart && font.width(t) + 66 < avail) {
            String badge = "RESTART";
            int bw = font.width(badge) + 10;
            RoundedRectRenderer.render(g, x + 4 + font.width(t) + 8, y + (HEIGHT - 12) / 2, bw, 12, 6, 0x14D9A53F);
            g.drawString(font, badge, x + 4 + font.width(t) + 13, y + (HEIGHT - 12) / 2 + 2, 0xFFC9A227);
        }
    }

    // ---- interaction (absolute mouse coordinates) ----

    boolean mouseClicked(double mx, double my, int btn) {
        return false;
    }

    boolean mouseDragged(double mx, double my, double dx, double dy) {
        return false;
    }

    void mouseReleased(double mx, double my) {
    }

    boolean mouseScrolled(double mx, double my, double delta) {
        return false;
    }

    // ---- shared helpers ----

    static String pretty(String s) {
        StringBuilder sb = new StringBuilder();
        boolean upper = true;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '_') {
                sb.append(' ');
                upper = true;
                continue;
            }
            if (Character.isUpperCase(ch) && sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                sb.append(' ');
            }
            sb.append(upper ? Character.toUpperCase(ch) : ch);
            upper = false;
        }
        return sb.toString();
    }

    static String last(List<String> path) {
        return path.isEmpty() ? "" : path.get(path.size() - 1);
    }

    static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return String.valueOf((long) v);
        }
        String s = String.format("%.4f", v);
        while (s.endsWith("0")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}

/** Pill switch with a rounded knob, filled with the screen's accent blue when on. */
final class ToggleRow extends ConfigRow {
    private static final int PILL_W = 40;
    private static final int PILL_H = 20;
    private static final int KNOB = 14;

    ToggleRow(ConfigValue<?> value, ValueSpec spec) {
        super(value, spec);
    }

    private boolean on() {
        return Boolean.TRUE.equals(shown());
    }

    @Override
    void render(GuiGraphics g, Font font, int x, int y, int w, int mx, int my, float pt) {
        recordGeometry(x, y, w);
        drawLabel(g, font, x, y);
        int cx = controlX();
        int py = y + (HEIGHT - PILL_H) / 2;
        boolean on = on();
        // The ON pill carries the screen's accent blue, so a lit control and the selected category read as the
        // same state; OFF keeps a neutral slate so a row of them stays quiet.
        RoundedRectRenderer.box(g, cx, py, PILL_W, PILL_H, PILL_H / 2f,
                on ? 0xFF2F6DB5 : 0xFF262C34, on ? 0xFF5E9BDC : 0xFF343C46);
        float kx = on ? cx + PILL_W - KNOB / 2f - 3 : cx + KNOB / 2f + 3;
        RoundedRectRenderer.render(g, kx - KNOB / 2f, py + (PILL_H - KNOB) / 2f - 1, KNOB, KNOB, 4, 0xFF0B0E12);
        RoundedRectRenderer.render(g, kx - KNOB / 2f, py + (PILL_H - KNOB) / 2f, KNOB, KNOB, 4, on ? 0xFFF4F8FC : 0xFFD3DAE3);
    }

    @Override
    boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0) {
            return false;
        }
        int cx = controlX();
        int py = rowY() + (HEIGHT - PILL_H) / 2;
        if (mx >= cx - 2 && mx <= cx + PILL_W + 2 && my >= py - 2 && my <= py + PILL_H + 2) {
            stage(!on());
            return true;
        }
        return false;
    }
}

/** Draggable track with a circle thumb and its value in a box beside it. */
final class SliderRow extends ConfigRow {
    private static final int VALUE_W = 52;
    private static final int TRACK_W = CONTROL_W - VALUE_W - 12;
    private static final double EPS = 1e-9;

    private final boolean integral;
    private final double min;
    private final double max;
    private boolean dragging;

    SliderRow(ConfigValue<?> value, ValueSpec spec) {
        super(value, spec);
        ModConfigSpec.Range<?> range = spec.getRange();
        this.min = ((Number) range.getMin()).doubleValue();
        this.max = ((Number) range.getMax()).doubleValue();
        this.integral = clazz == Integer.class || clazz == Long.class;
    }

    private double shownVal() {
        Object v = shown();
        return v instanceof Number n ? n.doubleValue() : min;
    }

    private double frac() {
        return (shownVal() - min) / Math.max(EPS, max - min);
    }

    private double valueAt(double f) {
        double v = min + Math.max(0, Math.min(1, f)) * (max - min);
        return integral ? Math.round(v) : v;
    }

    private Object typed(double v) {
        if (clazz == Integer.class) {
            return (int) v;
        }
        if (clazz == Long.class) {
            return (long) v;
        }
        if (clazz == Float.class) {
            return (float) v;
        }
        if (clazz == Double.class) {
            return v;
        }
        return (int) v;
    }

    private int trackX() {
        return controlX();
    }

    private int trackY() {
        return rowY() + HEIGHT / 2 - 2;
    }

    private boolean overTrack(double mx, double my) {
        return mx >= trackX() - 6 && mx <= trackX() + TRACK_W + 6 && my >= rowY() && my <= rowY() + HEIGHT;
    }

    @Override
    void render(GuiGraphics g, Font font, int x, int y, int w, int mx, int my, float pt) {
        recordGeometry(x, y, w);
        drawLabel(g, font, x, y);
        int cx = trackX();
        int ty = trackY();
        float kx = cx + (float) (frac() * TRACK_W);
        RoundedRectRenderer.render(g, cx, ty, TRACK_W, 4, 2f, 0xFF232C36);
        if (kx > cx + 2) {
            RoundedRectRenderer.render(g, cx, ty, kx - cx, 4, 2f, 0xFF2F6DB5);
        }
        RoundedRectRenderer.renderCircle(g, kx, ty + 2f, 7, 0xFF0B0E12);
        RoundedRectRenderer.renderCircle(g, kx, ty + 2f, 5.5f, 0xFFF4F8FC);
        // The value in its own bordered box, the way a numeric field reads rather than a floating number.
        int vx = cx + CONTROL_W - VALUE_W;
        int vy = y + (HEIGHT - 18) / 2;
        RoundedRectRenderer.box(g, vx, vy, VALUE_W, 18, 4, 0xFF12171D, 0xFF2C343E);
        String txt = fmt(shownVal());
        g.drawString(font, txt, vx + (VALUE_W - font.width(txt)) / 2, vy + 5, 0xFFE8EDF3);
    }

    @Override
    boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0) {
            return false;
        }
        if (overTrack(mx, my)) {
            dragging = true;
            setFrom(mx);
            return true;
        }
        return false;
    }

    @Override
    boolean mouseDragged(double mx, double my, double dx, double dy) {
        if (dragging) {
            setFrom(mx);
            return true;
        }
        return false;
    }

    @Override
    void mouseReleased(double mx, double my) {
        dragging = false;
    }

    @Override
    boolean mouseScrolled(double mx, double my, double delta) {
        if (!overTrack(mx, my)) {
            return false;
        }
        double step = integral ? 1 : Math.max((max - min) / 50, 1e-4);
        stage(typed(valueAt(shownVal() + Math.copySign(step, delta))));
        return true;
    }

    private void setFrom(double mx) {
        double f = (mx - trackX()) / TRACK_W;
        stage(typed(valueAt(f)));
    }
}

/** < value > selector for enums; wheel cycles too. */
final class CycleRow extends ConfigRow {
    private static final int BTN = 16;

    private final Object[] constants;

    CycleRow(ConfigValue<?> value, ValueSpec spec) {
        super(value, spec);
        Object[] constants = clazz.getEnumConstants();
        this.constants = constants == null ? new Object[0] : constants;
    }

    private int idx() {
        Object v = shown();
        for (int i = 0; i < constants.length; i++) {
            if (constants[i].equals(v)) {
                return i;
            }
        }
        return 0;
    }

    @Override
    void render(GuiGraphics g, Font font, int x, int y, int w, int mx, int my, float pt) {
        recordGeometry(x, y, w);
        drawLabel(g, font, x, y);
        int cx = controlX();
        int cy = y + (HEIGHT - BTN) / 2;
        boolean lh = mx >= cx && mx <= cx + BTN && my >= cy && my <= cy + BTN;
        boolean rh = mx >= cx + CONTROL_W - BTN && mx <= cx + CONTROL_W && my >= cy && my <= cy + BTN;
        // The value box sits between the two arrows, so the control reads as one field with steppers.
        RoundedRectRenderer.box(g, cx + BTN + 2, y + (HEIGHT - 18) / 2, CONTROL_W - 2 * BTN - 4, 18, 4, 0xFF12171D, 0xFF2C343E);
        RoundedRectRenderer.box(g, cx, cy, BTN, BTN, 4, lh ? 0xFF2F6DB5 : 0xFF1B2129, lh ? 0xFF5E9BDC : 0xFF2C343E);
        g.drawString(font, "<", cx + 5, cy + 4, lh ? 0xFFF4F8FC : 0xFF9AA4B0);
        RoundedRectRenderer.box(g, cx + CONTROL_W - BTN, cy, BTN, BTN, 4, rh ? 0xFF2F6DB5 : 0xFF1B2129, rh ? 0xFF5E9BDC : 0xFF2C343E);
        g.drawString(font, ">", cx + CONTROL_W - BTN + 5, cy + 4, rh ? 0xFFF4F8FC : 0xFF9AA4B0);
        String txt = pretty(constants[idx()].toString());
        g.drawString(font, txt, cx + CONTROL_W / 2 - font.width(txt) / 2, y + (HEIGHT - 9) / 2, 0xFFE8EDF3);
    }

    @Override
    boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0) {
            return false;
        }
        int cx = controlX();
        int cy = rowY() + (HEIGHT - BTN) / 2;
        if (mx >= cx && mx <= cx + BTN && my >= cy && my <= cy + BTN) {
            cycle(-1);
            return true;
        }
        if (mx >= cx + CONTROL_W - BTN && mx <= cx + CONTROL_W && my >= cy && my <= cy + BTN) {
            cycle(1);
            return true;
        }
        return false;
    }

    @Override
    boolean mouseScrolled(double mx, double my, double delta) {
        if (mx < controlX() - 4) {
            return false;
        }
        cycle(delta > 0 ? -1 : 1);
        return true;
    }

    private void cycle(int dir) {
        int n = constants.length;
        if (n == 0) {
            return;
        }
        stage(constants[Math.floorMod(idx() + dir, n)]);
    }
}

/** Free-text field for strings, lists (comma separated) and unbounded numbers. */
final class TextRow extends ConfigRow {
    final EditBox box;
    boolean invalid;

    TextRow(ConfigValue<?> value, ValueSpec spec) {
        super(value, spec);
        this.box = new EditBox(Minecraft.getInstance().font, 0, 0, CONTROL_W - 18, 18, Component.literal(label));
        box.setMaxLength(clazz == List.class ? 512 : 128);
        box.setValue(format(current()));
        box.setBordered(false);
    }

    static String format(Object v) {
        if (v instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object o : list) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(o);
            }
            return sb.toString();
        }
        return String.valueOf(v);
    }

    @Override
    void render(GuiGraphics g, Font font, int x, int y, int w, int mx, int my, float pt) {
        recordGeometry(x, y, w);
        drawLabel(g, font, x, y);
        int cx = controlX();
        int by = y + (HEIGHT - 18) / 2;
        RoundedRectRenderer.box(g, cx, by, CONTROL_W, 18, 4, 0xFF12171D, invalid ? 0xFFE05B5B : 0xFF2C343E);
        box.setPosition(cx + 6, by + 5);
        box.setTextColor(invalid ? 0xFFFF6B6B : 0xFFE8EDF3);
        box.render(g, mx, my, pt);
    }

    @Override
    boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0) {
            return false;
        }
        int cx = controlX();
        int by = rowY() + (HEIGHT - 18) / 2;
        if (mx >= cx && mx <= cx + CONTROL_W && my >= by && my <= by + 18) {
            box.setFocused(true);
            box.mouseClicked(mx, my, btn);
            return true;
        }
        return false;
    }

    @Override
    boolean mouseScrolled(double mx, double my, double delta) {
        return false;
    }

    /** Called when the box loses focus or on Done. Stages the parsed value when valid. */
    boolean validate() {
        try {
            Object parsed = parse();
            if (!acceptable(parsed)) {
                invalid = true;
                return false;
            }
            if (!parsed.equals(current())) {
                stage(parsed);
            }
            invalid = false;
            return true;
        } catch (Exception e) {
            invalid = true;
            return false;
        }
    }

    /**
     * Numbers are checked against the declared range directly. The range's class can differ from the
     * spec's class (see {@link ConfigRow#resolveClazz}), and the range validator rejects any value
     * whose runtime type does not match, so delegating to a mismatched validator would reject every
     * legitimate edit. Everything else still uses the spec's own validator.
     */
    private boolean acceptable(Object parsed) {
        ModConfigSpec.Range<?> range = spec.getRange();
        if (range != null && parsed instanceof Number n) {
            double v = n.doubleValue();
            return v >= ((Number) range.getMin()).doubleValue() && v <= ((Number) range.getMax()).doubleValue();
        }
        return spec.test(parsed);
    }

    @Override
    boolean apply() {
        return validate();
    }

    private Object parse() {
        String s = box.getValue().trim();
        if (clazz == String.class) {
            return s;
        }
        if (clazz == List.class) {
            List<String> list = new ArrayList<>();
            for (String part : s.split(",")) {
                if (!part.isBlank()) {
                    list.add(part.trim());
                }
            }
            return list;
        }
        if (clazz == Boolean.class) {
            if (s.equalsIgnoreCase("true")) {
                return true;
            }
            if (s.equalsIgnoreCase("false")) {
                return false;
            }
            throw new NumberFormatException(s);
        }
        if (clazz == Integer.class) {
            return Integer.parseInt(s);
        }
        if (clazz == Long.class) {
            return Long.parseLong(s);
        }
        if (clazz == Double.class) {
            return Double.parseDouble(s);
        }
        if (clazz == Float.class) {
            return Float.parseFloat(s);
        }
        return s;
    }
}