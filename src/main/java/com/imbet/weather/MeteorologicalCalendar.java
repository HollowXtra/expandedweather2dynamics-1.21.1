package com.imbet.weather;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The world's own calendar: which day it is, which season that day is in, and how much of the
 * season's weather character is in force.
 *
 * <p>Nothing here reads a clock or a world. A day is counted from the world's own tick count - one
 * day is 24,000 ticks, the same day a vanilla clock calls a day - and the year it belongs to is
 * whatever length the config says it is. 120 days is the mod's own compact year, ten days to a
 * month, which puts a whole spring-to-winter turn inside a few real hours of play; 365 is a real
 * calendar year, a real day per game day, for a server that wants a world to live through a year.
 * A world starts in the configured month, so a fresh save can begin in spring and see the season
 * it was tuned for rather than opening on the first of January.
 *
 * <p><b>What a season is worth.</b> A season is not one multiplier. Spring is the violent one -
 * instability and shear both rise and the tornado odds rise with them; summer is the pulse
 * season - more heat and moisture but less shear, so the sky makes many storms and few tornadoes;
 * autumn is the front season, quieter air carried on stronger flow; winter is the frontal season
 * proper - the least instability, the most shear, the most front, and almost no tornadoes. Those
 * four characters are the mod's own, held in {@link Character}, and what a modpack configures is
 * not the shape but how much of it applies: a strength of 1 is the shape as shipped, 0 makes every
 * season behave like every other, and 2 doubles the deviation from neutral.
 *
 * <p>The shape is applied as a deviation, so it can never be the thing that decides whether a
 * season is stormy: a strength of 0 is exactly the pre-existing weather, and the mod's own tuning
 * of what a sky can do is left where it was. It is a shift on top of the air, which is why turning
 * the calendar off changes nothing but the season.
 *
 * <p>This class is arithmetic and JSON parsing only - no Minecraft and no Weather2 types - so a
 * whole year, its months, its seasons and every override a datapack can write can be walked
 * without a world, which is how the numbers in the README were taken.
 */
public final class MeteorologicalCalendar {

    /** How many ticks a day is, the same day vanilla's clock counts. */
    public static final long TICKS_PER_DAY = 24000L;
    /** The months of the year, fixed at twelve however long the year is: a month is a twelfth of one. */
    public static final int MONTHS = 12;
    /** The strength a season's own character is applied with when nothing says otherwise. */
    public static final double FULL_STRENGTH = 1.0D;
    /** The strongest a season may be turned up to, by config or by datapack. */
    public static final double MAX_STRENGTH = 4.0D;
    /** How far a season may push the temperature before the shift stops being a season. */
    public static final double TEMPERATURE_LIMIT = 0.60D;
    /** How far a season may push moisture before the shift stops being a season. */
    public static final double MOISTURE_LIMIT = 0.30D;
    /** The lowest a seasonal scale may pinch an environment to, and the highest it may lift it. */
    public static final double MIN_SCALE = 0.20D;
    public static final double MAX_SCALE = 3.00D;

    private MeteorologicalCalendar() {
    }

    /**
     * The four seasons, in the northern order the calendar counts them. Each knows the month whose
     * name it is read against - the middle month of the season - which is what a month-keyed
     * multiplier reads when the season comes from an external calendar that does not speak in
     * months at all.
     */
    public enum Season {
        SPRING("Spring", 4),
        SUMMER("Summer", 7),
        AUTUMN("Autumn", 10),
        WINTER("Winter", 1);

        private final String label;
        private final int middleMonth;

        Season(String label, int middleMonth) {
            this.label = label;
            this.middleMonth = middleMonth;
        }

        public String label() {
            return label;
        }

        /** The month this season is named against: April, July, October or January. */
        public int middleMonth() {
            return middleMonth;
        }

        /**
         * The season a month falls in, by the meteorological calendar: March to May is spring,
         * June to August summer, September to November autumn, and December to February winter.
         */
        public static Season ofMonth(int month) {
            int m = wrapMonth(month);
            if (m >= 3 && m <= 5) {
                return SPRING;
            }
            if (m >= 6 && m <= 8) {
                return SUMMER;
            }
            if (m >= 9 && m <= 11) {
                return AUTUMN;
            }
            return WINTER;
        }

        /**
         * The season a name is talking about, however it was written: {@code SPRING},
         * {@code early_spring}, {@code mid spring} and {@code Early Spring} are all spring, and
         * autumn answers to fall. Null when the name says nothing about a season, which is how a
         * calendar mod this mod has never heard of is recognised as one it cannot read.
         */
        public static Season byName(String raw) {
            String name = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
            if (name.contains("winter")) {
                return WINTER;
            }
            if (name.contains("spring")) {
                return SPRING;
            }
            if (name.contains("summer")) {
                return SUMMER;
            }
            if (name.contains("autumn") || name.contains("fall")) {
                return AUTUMN;
            }
            return null;
        }
    }

    /**
     * One day of the calendar: the world's own day count, where that day sits in the year, and
     * which season it is in.
     *
     * <p>{@code worldDay} is the day the world itself is on, counted from one, which is what a
     * player means by "day 412". {@code dayOfYear} is zero-based and wraps every
     * {@code yearLength} days; {@code dayOfMonth} is one-based. {@code source} names where the
     * season came from, so a readout can say whether the sky is following the mod's own clock or
     * another mod's.
     */
    public record Day(long worldDay, int dayOfYear, int year, int yearLength, int month, int dayOfMonth,
                      Season season, String source) {

        /** A short line: "Spring, day 74 of year 1 (EWD calendar)". */
        public String label() {
            return season.label() + ", day " + (dayOfYear + 1) + " of year " + year + " (" + source + ")";
        }

        /** The day of the year, one-based, which is how a datapack writes a day window. */
        public int dayOfYearOneBased() {
            return dayOfYear + 1;
        }

        /** The month name a player would say: "March", "August". */
        public String monthLabel() {
            return MeteorologicalCalendar.monthLabel(month);
        }

        /** Whether this day sits in the hurricane window: late summer into early autumn. */
        public boolean hurricaneWindow() {
            return month == 8 || month == 9;
        }
    }

    /**
     * What one season does to the air, as offsets and scales. Temperature and moisture are offsets
     * in the same units the air itself uses; instability, shear, frontal and tornado are
     * multipliers on the environment that a season has already been asked for.
     *
     * <p>The four shipped characters: spring lifts instability, shear and the tornado odds; summer
     * lifts heat and moisture while taking shear away, which is the pulse setup - many storms, few
     * tornadoes, rain that stops as suddenly as it started; autumn lifts shear and the frontal
     * convection a low makes; winter takes the instability away, lifts shear and front activity,
     * and leaves almost nothing for a tornado to form in.
     */
    public record Character(double temperature, double moisture, double instability, double shear,
                            double frontal, double tornado) {

        /** The season that is no season at all: what a calendar with no effect must read as. */
        public static final Character NEUTRAL = new Character(0.0D, 0.0D, 1.0D, 1.0D, 1.0D, 1.0D);

        /** The shipped character of each season. */
        public static Character of(Season season) {
            return switch (season) {
                case SPRING -> new Character(0.06D, 0.08D, 1.15D, 1.15D, 1.05D, 1.45D);
                case SUMMER -> new Character(0.28D, 0.06D, 1.22D, 0.78D, 0.82D, 0.90D);
                case AUTUMN -> new Character(-0.04D, 0.0D, 0.95D, 1.08D, 1.15D, 1.00D);
                case WINTER -> new Character(-0.40D, -0.12D, 0.60D, 1.12D, 1.40D, 0.45D);
            };
        }
    }

    /**
     * A season's character with a strength applied: how much of the season's deviation from
     * neutral is actually in force today. A strength of 0 hands back the neutral character
     * whatever the season, 1 the shipped shape, and 2 twice the deviation - so the dial scales the
     * season's own personality and never invents one.
     */
    public record Effect(double temperature, double moisture, double instability, double shear,
                         double frontal, double tornado, double strength) {

        /** What an off calendar, a neutral season or a strength of zero amounts to. */
        public static final Effect NEUTRAL = new Effect(0.0D, 0.0D, 1.0D, 1.0D, 1.0D, 1.0D, 0.0D);

        /** The character of a season, deviated by how strongly that season is being applied. */
        public static Effect of(Season season, double strength) {
            double s = clamp(strength, 0.0D, MAX_STRENGTH);
            if (season == null || s <= 0.0D) {
                return NEUTRAL;
            }
            Character base = Character.of(season);
            return new Effect(
                    clamp(base.temperature() * s, -TEMPERATURE_LIMIT, TEMPERATURE_LIMIT),
                    clamp(base.moisture() * s, -MOISTURE_LIMIT, MOISTURE_LIMIT),
                    scale(base.instability(), s),
                    scale(base.shear(), s),
                    scale(base.frontal(), s),
                    scale(base.tornado(), s),
                    s);
        }

        /** A multiplier of 1 with the deviation either scaled up or down, and never through the floor. */
        private static double scale(double base, double strength) {
            return clamp(1.0D + (base - 1.0D) * strength, MIN_SCALE, MAX_SCALE);
        }
    }

    /**
     * A stretch of the year, written as days of the year, over which a severe-weather multiplier
     * applies. A window whose end is before its start wraps the new year, which is how a season
     * that straddles the boundary - a winter that runs from November into February - is written
     * without splitting it in two.
     */
    public record Window(int fromDay, int toDay, double multiplier) {

        public Window {
            multiplier = clamp(multiplier, 0.0D, MAX_STRENGTH);
        }

        /** Whether a day of the year, one-based, falls inside this window. */
        public boolean covers(int dayOfYear, int yearLength) {
            int length = Math.max(1, yearLength);
            int from = clampDay(fromDay, length);
            int to = clampDay(toDay, length);
            int day = clampDay(dayOfYear, length);
            if (from <= to) {
                return day >= from && day <= to;
            }
            return day >= from || day <= to;
        }

        private static int clampDay(int day, int yearLength) {
            int wrapped = (day - 1) % yearLength;
            return (wrapped < 0 ? wrapped + yearLength : wrapped) + 1;
        }
    }

    /**
     * What a datapack says about the calendar, read from {@code data/<namespace>/ewd_calendar/*.json}.
     * Everything in it is optional: a file may shorten the year, turn one season up, mark one month
     * as the dangerous one, or name day windows outright, and a file that says nothing about a
     * thing leaves whatever the config says about it alone.
     *
     * <p>A window is a stretch of days with a multiplier, and window multipliers stack by
     * multiplication - two windows that both cover a day both apply - so a pack can put a general
     * severe season down and carve a deadly fortnight out of it inside the same file.
     */
    public record Overrides(int yearLengthDays, Map<Season, Double> seasonStrengths, double[] monthMultipliers,
                            List<Window> windows) {

        /** No overrides at all: what a world with no datapack, or a failed reload, reads. */
        public static final Overrides NONE = new Overrides(0, Map.of(), uniformMonths(), List.of());

        public boolean present() {
            return yearLengthDays > 0 || !seasonStrengths.isEmpty() || !windows.isEmpty() || !uniform(monthMultipliers);
        }

        /** The multiplier this day gets from the windows, the product of every window covering it. */
        public double windowMultiplier(int dayOfYear, int yearLength) {
            double value = 1.0D;
            for (Window window : windows) {
                if (window.covers(dayOfYear, yearLength)) {
                    value *= window.multiplier();
                }
            }
            return clamp(value, 0.0D, MAX_STRENGTH);
        }

        /**
         * This set of overrides with another applied over it. Later files win a scalar they name -
         * a second year length replaces the first - while month multipliers and season strengths
         * multiply, and windows accumulate, so two packs can each contribute a hazard rather than
         * the second silently erasing the first.
         */
        public Overrides merged(Overrides later) {
            if (later == null || !later.present()) {
                return this;
            }
            if (!present()) {
                return later;
            }
            Map<Season, Double> seasons = new EnumMap<>(Season.class);
            seasons.putAll(seasonStrengths);
            for (Map.Entry<Season, Double> entry : later.seasonStrengths.entrySet()) {
                seasons.merge(entry.getKey(), entry.getValue(), (a, b) -> clamp(a * b, 0.0D, MAX_STRENGTH));
            }
            double[] months = new double[MONTHS];
            for (int i = 0; i < MONTHS; i++) {
                months[i] = clamp(monthMultipliers[i] * later.monthMultipliers[i], 0.0D, MAX_STRENGTH);
            }
            List<Window> combined = new ArrayList<>(windows.size() + later.windows.size());
            combined.addAll(windows);
            combined.addAll(later.windows);
            return new Overrides(later.yearLengthDays > 0 ? later.yearLengthDays : yearLengthDays, seasons, months, combined);
        }

        /** The season strength this set of overrides adds, or 1 when it says nothing about the season. */
        public double seasonStrength(Season season) {
            if (season == null) {
                return 1.0D;
            }
            Double value = seasonStrengths.get(season);
            return value == null ? 1.0D : clamp(value, 0.0D, MAX_STRENGTH);
        }

        /** The month multiplier this set of overrides adds, or 1 when it says nothing about the month. */
        public double monthMultiplier(int month) {
            int index = wrapMonth(month) - 1;
            return clamp(monthMultipliers[index], 0.0D, MAX_STRENGTH);
        }
    }

    /**
     * The day a world is on, read from its own tick count. {@code startMonth} is the month the
     * world opened in: the first day of a world is day one of that month, so a world configured to
     * open in March spends its first spring where a player can see it.
     */
    public static Day day(long worldDay, int yearLengthDays, int startMonth) {
        int yearLength = Math.max(MONTHS, yearLengthDays);
        long dayIndex = worldDay - 1L + startOfMonth(wrapMonth(startMonth), yearLength);
        long year = Math.floorDiv(dayIndex, yearLength) + 1L;
        int dayOfYear = (int) Math.floorMod(dayIndex, yearLength);
        int month = (int) Math.floorDiv((long) dayOfYear * MONTHS, yearLength) + 1;
        int dayOfMonth = dayOfYear - startOfMonth(month, yearLength) + 1;
        return new Day(Math.max(1L, worldDay), dayOfYear, (int) Math.max(1L, year), yearLength, wrapMonth(month),
                Math.max(1, dayOfMonth), Season.ofMonth(month), "EWD calendar");
    }

    /**
     * The day of the year a month opens on, zero-based. The months are twelfths of the year rounded
     * up to the day they change on, so a year of any length divides into twelve months of thirty or
     * thirty-one days and a month that has a day 30 always has a day 31 beside it.
     */
    public static int startOfMonth(int month, int yearLengthDays) {
        int yearLength = Math.max(MONTHS, yearLengthDays);
        return Math.floorDiv((wrapMonth(month) - 1) * yearLength + MONTHS - 1, MONTHS);
    }

    /** The world day a tick count is on, counted from one, the way a vanilla day counter reads. */
    public static long worldDay(long dayTimeTicks) {
        return Math.floorDiv(dayTimeTicks, TICKS_PER_DAY) + 1L;
    }

    /** The same day with its season taken from another calendar, named by whatever it called itself. */
    public static Day withSeason(Day day, Season season, String source) {
        if (season == null || day == null) {
            return day;
        }
        int month = season.middleMonth();
        int dayOfMonth = Math.max(1, day.dayOfMonth());
        return new Day(day.worldDay(), day.dayOfYear(), day.year(), day.yearLength(), month, dayOfMonth, season,
                source == null || source.isBlank() ? season.label() : source);
    }

    /**
     * How strongly the season applies on this day, from every hook at once: the season's own
     * strength, the month it is in, a datapack's season and month figures, and every day window
     * covering it. All of them multiply, and the result is clamped to what a season is allowed to
     * be worth.
     */
    public static double strength(Day day, double[] seasonStrengths, double[] monthMultipliers, Overrides overrides) {
        if (day == null || day.season() == null) {
            return 0.0D;
        }
        double value = seasonStrength(seasonStrengths, day.season());
        value *= monthMultiplier(monthMultipliers, day.month());
        if (overrides != null) {
            value *= overrides.seasonStrength(day.season());
            value *= overrides.monthMultiplier(day.month());
            value *= overrides.windowMultiplier(day.dayOfYearOneBased(), day.yearLength());
        }
        return clamp(value, 0.0D, MAX_STRENGTH);
    }

    /** A season's configured strength, or the full shipped one when the array is missing an entry. */
    public static double seasonStrength(double[] seasonStrengths, Season season) {
        if (season == null) {
            return 0.0D;
        }
        if (seasonStrengths == null || seasonStrengths.length <= season.ordinal()) {
            return FULL_STRENGTH;
        }
        return clamp(seasonStrengths[season.ordinal()], 0.0D, MAX_STRENGTH);
    }

    /** A month's configured multiplier, or 1 when the array is missing an entry. */
    public static double monthMultiplier(double[] monthMultipliers, int month) {
        if (monthMultipliers == null || monthMultipliers.length < MONTHS) {
            return 1.0D;
        }
        return clamp(monthMultipliers[wrapMonth(month) - 1], 0.0D, MAX_STRENGTH);
    }

    /** A fresh set of twelve month multipliers, every month the same. */
    public static double[] uniformMonths() {
        double[] months = new double[MONTHS];
        java.util.Arrays.fill(months, 1.0D);
        return months;
    }

    /** The twelve month multipliers in the given order, or null when the list is not twelve numbers. */
    public static double[] monthsOf(List<String> values) {
        if (values == null || values.size() != MONTHS) {
            return null;
        }
        double[] months = new double[MONTHS];
        for (int i = 0; i < MONTHS; i++) {
            try {
                months[i] = clamp(Double.parseDouble(values.get(i).trim()), 0.0D, MAX_STRENGTH);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return months;
    }

    /** The month name for a month number, January being one. */
    public static String monthLabel(int month) {
        return switch (wrapMonth(month)) {
            case 1 -> "January";
            case 2 -> "February";
            case 3 -> "March";
            case 4 -> "April";
            case 5 -> "May";
            case 6 -> "June";
            case 7 -> "July";
            case 8 -> "August";
            case 9 -> "September";
            case 10 -> "October";
            case 11 -> "November";
            default -> "December";
        };
    }

    /** A month number folded into one to twelve, so a caller never has to. */
    public static int wrapMonth(int month) {
        int wrapped = (month - 1) % MONTHS;
        return (wrapped < 0 ? wrapped + MONTHS : wrapped) + 1;
    }

    /**
     * Reads one datapack file into overrides.
     *
     * <p>The file is a single JSON object:
     * <pre>{@code
     * {
     *   "year_length_days": 365,
     *   "seasons": { "spring": 1.2, "winter": 0.8 },
     *   "months": [1.0, 1.0, 1.1, 1.2, 1.15, 1.1, 1.0, 0.9, 1.0, 1.0, 1.0, 0.9],
     *   "windows": [
     *     { "from_day": 100, "to_day": 120, "multiplier": 1.4 },
     *     { "from_day": 320, "to_day": 40, "multiplier": 1.3 }
     *   ]
     * }
     * }</pre>
     *
     * <p>Anything missing, misspelled or out of range is left at neutral rather than failing the
     * load: a pack's typo should cost that one line, not the world's weather.
     */
    public static Overrides parseOverrides(JsonObject json) {
        if (json == null) {
            return Overrides.NONE;
        }
        int yearLength = 0;
        if (json.has("year_length_days") && json.get("year_length_days").isJsonPrimitive()) {
            try {
                yearLength = Math.max(MONTHS, json.get("year_length_days").getAsInt());
            } catch (NumberFormatException | UnsupportedOperationException ignored) {
                yearLength = 0;
            }
        }
        Map<Season, Double> seasons = new EnumMap<>(Season.class);
        if (json.has("seasons") && json.get("seasons").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("seasons").entrySet()) {
                Season season = Season.byName(entry.getKey());
                if (season == null || !entry.getValue().isJsonPrimitive()) {
                    continue;
                }
                try {
                    seasons.put(season, clamp(entry.getValue().getAsDouble(), 0.0D, MAX_STRENGTH));
                } catch (NumberFormatException | UnsupportedOperationException ignored) {
                }
            }
        }
        double[] months = uniformMonths();
        if (json.has("months") && json.get("months").isJsonArray()) {
            JsonArray array = json.getAsJsonArray("months");
            if (array.size() == MONTHS) {
                double[] parsed = new double[MONTHS];
                boolean valid = true;
                for (int i = 0; i < MONTHS && valid; i++) {
                    try {
                        parsed[i] = clamp(array.get(i).getAsDouble(), 0.0D, MAX_STRENGTH);
                    } catch (NumberFormatException | UnsupportedOperationException | IllegalStateException ignored) {
                        valid = false;
                    }
                }
                if (valid) {
                    months = parsed;
                }
            }
        }
        List<Window> windows = new ArrayList<>();
        if (json.has("windows") && json.get("windows").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("windows")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject window = element.getAsJsonObject();
                Integer from = intField(window, "from_day");
                Integer to = intField(window, "to_day");
                Double multiplier = doubleField(window, "multiplier");
                if (from == null || to == null || multiplier == null) {
                    continue;
                }
                windows.add(new Window(from, to, multiplier));
            }
        }
        return new Overrides(yearLength, seasons, months, List.copyOf(windows));
    }

    private static Integer intField(JsonObject json, String name) {
        if (!json.has(name) || !json.get(name).isJsonPrimitive()) {
            return null;
        }
        try {
            return json.get(name).getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException ignored) {
            return null;
        }
    }

    private static Double doubleField(JsonObject json, String name) {
        if (!json.has(name) || !json.get(name).isJsonPrimitive()) {
            return null;
        }
        try {
            return json.get(name).getAsDouble();
        } catch (NumberFormatException | UnsupportedOperationException ignored) {
            return null;
        }
    }

    private static boolean uniform(double[] months) {
        if (months == null || months.length < MONTHS) {
            return true;
        }
        for (int i = 0; i < MONTHS; i++) {
            if (Math.abs(months[i] - 1.0D) > 1.0E-6D) {
                return false;
            }
        }
        return true;
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }
}
