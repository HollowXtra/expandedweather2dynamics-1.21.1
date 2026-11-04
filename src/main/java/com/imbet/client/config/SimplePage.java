package com.imbet.client.config;

import java.util.List;
import java.util.Locale;

/**
 * The simple page: the nine dials, what each one does right now, and where its detail lives.
 *
 * <p>Written without Minecraft in it on purpose, because there are three things about this page that have to be
 * true and none of them are visible in a screenshot: every dial on it is a real setting of the simple section,
 * every "detail" chip points at a category and a setting that exist, and the card the player clicks is the card
 * the player can see. The screen asks this class for all three, and the harness asks it the same questions.
 */
public final class SimplePage {
    private SimplePage() {
    }

    /**
     * Where a dial's detail lives: the category key it is listed under and a setting inside it.
     *
     * <p>Keys rather than names, because a chip's label should read the way the sidebar reads - the screen
     * resolves the key to the same name the category is listed under, so the two can never disagree.
     */
    public record Target(String categoryKey, String rowKey) {
    }

    /** One dial: the setting it edits, the question it answers, and where its detail lives. */
    public record Dial(String key, String question, String detailRow, List<Target> targets) {
    }

    /** The dials, in the order the simple section declares them. */
    public static final List<Dial> DIALS = List.of(
            new Dial("stormFrequency", "How often storms come",
                    "Chance of a storm",
                    List.of(new Target("storm_types", "landspoutSpawnChance"),
                            new Target("daily_spc_outlook", "spawnMultiplierScale"))),
            new Dial("maxStorms", "How many run at once",
                    "The most of the mod's own storms",
                    List.of(new Target("storm_types", "maxAddonVortices"))),
            new Dial("stormLimitRangeBlocks", "How near one has to be to count",
                    "How near a storm counts",
                    List.of(new Target("storm_types", "maxAddonVortices"))),
            new Dial("stormSize", "How big tornadoes get",
                    "Width that damages",
                    List.of(new Target("widths", "maxDamageWidthBlocks"),
                            new Target("widths", "maxVisibleFunnelWidthBlocks"))),
            new Dial("tornadoStrength", "How violent they are",
                    "Wind of the top stage",
                    List.of(new Target("windfield", "stage5Mph"),
                            new Target("damage", "woodPickupMph"))),
            new Dial("rainStrength", "How hard it rains",
                    "Thickness of the rain",
                    List.of(new Target("rain_volume", "opacity"),
                            new Target("volumetric_downfall", "rainOpacity"))),
            new Dial("destruction", "Whether they may break anything",
                    "Damage system",
                    List.of(new Target("damage", "enabled"))),
            new Dial("seasonStrength", "How much the seasons matter",
                    "Season strength",
                    List.of(new Target("season_calendar", "yearLengthDays"),
                            new Target("season_calendar", "springStrength"),
                            new Target("season_calendar", "monthMultipliers"))),
            new Dial("onlySpawnTornadoesInPlains", "Where a tornado may be born",
                    "Plains only",
                    List.of(new Target("tornado_genesis", "plainsOnly"))));

    /** The dial with this key, or null - the page is built from this list, not from the other way round. */
    public static Dial dial(String key) {
        for (Dial dial : DIALS) {
            if (dial.key().equals(key)) {
                return dial;
            }
        }
        return null;
    }

    /**
     * What a dial does at the value it currently holds, in one line.
     *
     * <p>The numbers are read out rather than described, and the three values worth naming - off, the shipped
     * default, and everything else - are named. Two of the dials cannot read as a plain multiple, because their
     * ends are not plain multiples: rain bottoms out at a veil rather than at nothing, and destruction is a
     * switch that can only take damage away.
     */
    public static String effect(String key, Object shown) {
        if ("destruction".equals(key)) {
            boolean allowed = shown instanceof Boolean value ? value : Boolean.parseBoolean(String.valueOf(shown));
            return allowed
                    ? "Storms break blocks, scour the ground and fell trees - as far as the Damage section allows."
                    : "Nothing breaks: no scouring, no torn-off roofs and no felled trees. Storms still blow.";
        }
        if ("onlySpawnTornadoesInPlains".equals(key)) {
            boolean allowed = shown instanceof Boolean value ? value : Boolean.parseBoolean(String.valueOf(shown));
            return allowed
                    ? "A land tornado is only born over plains country, or over a biome a pack has tagged for it."
                    : "Any ground: a storm may spin a tornado up over a forest, a jungle or a mountain.";
        }
        double value = number(shown);
        return switch (key) {
            case "stormFrequency" -> value <= 0.0D
                    ? "No storms: the mod stops starting any, and the ones already going finish on their own."
                    : (isOne(value) ? "The mod's own frequency - the detailed chances below decide everything."
                    : "Every chance the mod rolls for a storm is " + times(value) + " as it ships.");
            case "maxStorms" -> value <= 0.0D
                    ? "No limit: a many-stormed sky, as the mod was built."
                    : "At most " + whole(value) + " of the mod's own storms at a time near you; "
                    + "the mod stops starting new ones until one dies.";
            case "stormLimitRangeBlocks" -> "A storm only counts against that limit within " + whole(value)
                    + " blocks; weather further off is not in your way.";
            case "stormSize" -> value <= 0.0D
                    ? "No width at all: a tornado still blows, but leaves nothing behind it."
                    : (isOne(value) ? "The mod's own widths - both the funnel and the damage swath."
                    : "Funnels and damage swaths are " + times(value) + " the size the width settings describe.");
            case "tornadoStrength" -> value <= 0.0D
                    ? "No wind: even a mile-wide wedge cannot lift a fence post."
                    : (isOne(value) ? "The mod's own intensities - an EF3 blows like an EF3."
                    : "Every wind a stage is worth is " + times(value) + "; the stage a storm reaches is unchanged.");
            case "rainStrength" -> value <= 0.0D
                    ? "The thinnest veil rain can be drawn at: a storm never stops raining entirely."
                    : (isOne(value) ? "The mod's own rain, whichever way it is drawn."
                    : "Rain is drawn " + times(value) + " as thick.");
            case "seasonStrength" -> value <= 0.0D
                    ? "No seasons: the year still turns, but every day is the neutral day."
                    : (isOne(value) ? "The mod's own year - spring's tornadoes, summer's storms, winter's fronts."
                    : "Every season is " + times(value) + " as far from the neutral day.");
            default -> "";
        };
    }

    /** The one-line description of the whole page, for the header and the footer. */
    public static final String SUMMARY =
            "The short version of the mod: nine dials that scale every detailed setting below them.";

    // ---- card geometry ----

    /**
     * How tall one dial card is, and the gap under it.
     *
     * <p>A card holds the setting's own 28-pixel row, a line for the dial's question and one for what it
     * currently does, and the gap under it - so a card cannot shrink past a control without cutting it in half,
     * and the list scrolls instead. Nine cards is 524 pixels against the 418 the body has at
     * {@code MAX_PANEL_H}, which is a scroll; seven fit without one, which is why the two halves of the config
     * that a world owner is most likely to want - the seasons and where a tornado may form - are the last two
     * rather than anywhere in particular.
     */
    public static final int CARD_H = 52;
    public static final int CARD_GAP = 7;

    /** The total height the cards need, gaps included but no trailing gap. */
    public static int contentHeight(int cards) {
        return cards <= 0 ? 0 : cards * CARD_H + (cards - 1) * CARD_GAP;
    }

    /** The top of card {@code index} inside a viewport whose top is {@code top}, scrolled by {@code scroll}. */
    public static int cardTop(int index, int top, int scroll) {
        return top + index * (CARD_H + CARD_GAP) - scroll;
    }

    /** How far the list may scroll before its last card sits against the bottom of the viewport. */
    public static int maxScroll(int cards, int viewportH) {
        return Math.max(0, contentHeight(cards) - Math.max(0, viewportH));
    }

    /**
     * Where a card's own parts sit inside it.
     *
     * <p>The setting's own row goes at the top - the same 28-pixel row the detailed page draws, so the controls
     * are the same size in both places - and the line and the detail chips share the strip under it. Named here
     * rather than written into the renderer so that "the parts fit inside the card" is arithmetic somebody can
     * check: a card that is one pixel too short cuts a control in half, and nothing else would notice.
     */
    public static final int CARD_PAD_TOP = 4;
    public static final int CARD_LINE_FROM_BOTTOM = 17;
    public static final int CARD_CHIP_FROM_BOTTOM = 20;
    public static final int CHIP_H = 14;

    /** The top of a card's sentence line, and of its chips, for a card drawn at {@code cardY}. */
    public static int cardLineY(int cardY) {
        return cardY + CARD_H - CARD_LINE_FROM_BOTTOM;
    }

    public static int cardChipY(int cardY) {
        return cardY + CARD_H - CARD_CHIP_FROM_BOTTOM;
    }

    /**
     * Which card is under this y, or -1.
     *
     * <p>The same arithmetic as {@link #cardTop}, used by both the renderer and the click handler, so a click
     * cannot land on a card other than the one under the cursor. A y inside a gap is no card at all - the gaps
     * are where the eye reads two cards as separate things, and they should behave that way - and a y outside
     * the viewport is no card either, however the card's own rect happens to fall: the scrolled-past cards are
     * not on screen, and a hit test that only knows the scroll offset would happily hand one back.
     */
    public static int indexAt(double my, int cards, int top, int scroll, int viewportH) {
        if (my < top || my > top + Math.max(0, viewportH)) {
            return -1;
        }
        int local = (int) my - top + scroll;
        if (local < 0) {
            return -1;
        }
        int stride = CARD_H + CARD_GAP;
        int index = local / stride;
        if (index < 0 || index >= cards) {
            return -1;
        }
        return local - index * stride < CARD_H ? index : -1;
    }

    // ---- formatting ----

    /** A dial's value as a multiple, at one decimal, so every card reads in the same vocabulary. */
    public static String times(double value) {
        return String.format(Locale.ROOT, "%.1f", value) + "x";
    }

    /** A dial's value as a count of blocks or storms. */
    public static String whole(double value) {
        return String.valueOf((long) value);
    }

    private static boolean isOne(double value) {
        return Math.abs(value - 1.0D) < 1.0E-9D;
    }

    /** The number behind a setting's current value, or the identity when it is not one. */
    private static double number(Object shown) {
        if (shown instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(shown));
        } catch (NumberFormatException ignored) {
            return 1.0D;
        }
    }
}
