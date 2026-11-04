package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The level-facing half of the calendar: what day a world is on, and what that day does to the air.
 *
 * <p>The day is counted from the world's own tick count, so it survives a restart, a save and a
 * player walking away - a world's weather history is its own, not a session's. The season it lands
 * in comes from whichever calendar the world has: with Serene Seasons installed and the sync on,
 * that mod's season is read and used, because a player running a seasons mod has told their world
 * which season it is and this mod has no business disagreeing; with no such mod, or with the sync
 * off, the mod's own cycle counts a year of its own length and the config chooses which month the
 * world opened in.
 *
 * <p>What the season is worth on a given day is read from the config and from any datapack that
 * wanted a say, and the two are multiplied rather than one replacing the other: the config is the
 * world owner's dial, the datapack is the pack's, and a pack that turns one month up has not
 * silently overruled a server that turned the whole calendar down.
 *
 * <p>The day is cached per dimension, keyed on everything that decides it, so the reflection into
 * another mod's API happens once a day per world rather than once per storm: an air mass is read
 * hundreds of times an hour and a calendar is not.
 */
public final class SeasonCalendarService {

    private static final Map<ResourceKey<Level>, Cached> CACHE = new HashMap<>();

    private SeasonCalendarService() {
    }

    /** Whether the calendar is allowed to touch the weather at all. */
    public static boolean enabled() {
        return Config.CALENDAR_ENABLED.get();
    }

    /** The day a world is on, from its own clock, with the season its calendar is showing. */
    public static MeteorologicalCalendar.Day day(ServerLevel level) {
        if (level == null) {
            return MeteorologicalCalendar.day(1L, Config.CALENDAR_YEAR_LENGTH_DAYS.get(), Config.CALENDAR_START_MONTH.get());
        }
        int yearLength = Config.CALENDAR_YEAR_LENGTH_DAYS.get();
        int startMonth = Config.CALENDAR_START_MONTH.get();
        long worldDay = MeteorologicalCalendar.worldDay(level.getDayTime());
        boolean sync = Config.CALENDAR_SYNC_SERENE_SEASONS.get() && SereneSeasonsCompat.loaded();
        Cached cached = CACHE.get(level.dimension());
        if (cached != null && cached.matches(worldDay, yearLength, startMonth, sync)) {
            return cached.day;
        }
        MeteorologicalCalendar.Day day = MeteorologicalCalendar.day(worldDay, yearLength, startMonth);
        if (sync) {
            // Serene Seasons keeps its own calendar per world and every player in it sees the same
            // season; the position is only the API's way of finding the level.
            BlockPos origin = level.getSharedSpawnPos();
            SereneSeasonsCompat.SeasonSample sample = SereneSeasonsCompat.sample(level, origin);
            MeteorologicalCalendar.Season season = MeteorologicalCalendar.Season.byName(sample.label());
            if (season != null) {
                day = MeteorologicalCalendar.withSeason(day, season, "Serene Seasons");
            }
        }
        CACHE.put(level.dimension(), new Cached(worldDay, yearLength, startMonth, sync, day));
        return day;
    }

    /**
     * What the season on this day is worth: the offsets and scales the air is shifted by. A day
     * with the calendar switched off, or a season turned down to nothing, reads neutral, which is
     * exactly the weather the mod had before any of this existed.
     */
    public static MeteorologicalCalendar.Effect effect(MeteorologicalCalendar.Day day) {
        if (day == null || !enabled()) {
            return MeteorologicalCalendar.Effect.NEUTRAL;
        }
        double strength = strength(day);
        return MeteorologicalCalendar.Effect.of(day.season(), strength);
    }

    /** The same, for a caller that only has the level. */
    public static MeteorologicalCalendar.Effect effect(ServerLevel level) {
        return effect(day(level));
    }

    /** How strongly the season applies on a day, after the config, the month and the datapacks. */
    public static double strength(MeteorologicalCalendar.Day day) {
        if (day == null || !enabled()) {
            return 0.0D;
        }
        List<? extends String> configuredMonths = Config.CALENDAR_MONTH_MULTIPLIERS.get();
        double[] months = MeteorologicalCalendar.monthsOf(configuredMonths == null ? List.of() : List.copyOf(configuredMonths));
        if (months == null) {
            months = MeteorologicalCalendar.uniformMonths();
        }
        // And the seasons dial over the whole of it, in the one place every season read goes through: the
        // configured strengths, the month multipliers and the datapacks are scaled by it together, so a world
        // that turns the seasons down gets a year that turns without the sky noticing.
        return Config.seasonStrength(
                MeteorologicalCalendar.strength(day, seasonStrengths(), months, SeasonDatapack.overrides()));
    }

    /** The four configured season strengths, in season order, so a harness or a readout can show them. */
    public static double[] seasonStrengths() {
        return new double[]{
                Config.CALENDAR_SPRING_STRENGTH.get(),
                Config.CALENDAR_SUMMER_STRENGTH.get(),
                Config.CALENDAR_AUTUMN_STRENGTH.get(),
                Config.CALENDAR_WINTER_STRENGTH.get()
        };
    }

    /** The same twelve multipliers the config holds, or a neutral set when the list is not twelve numbers. */
    public static double[] configuredMonths() {
        List<? extends String> configured = Config.CALENDAR_MONTH_MULTIPLIERS.get();
        double[] months = configured == null ? null : MeteorologicalCalendar.monthsOf(List.copyOf(configured));
        return months == null ? MeteorologicalCalendar.uniformMonths() : months;
    }

    /** Forgets the cached days, which is what leaving the overworld does. */
    public static void clear() {
        CACHE.clear();
    }

    /** A world shutting down takes its calendar with it, so the next world counts from its own day one. */
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            clear();
            SeasonDatapack.clear();
        }
    }

    private record Cached(long worldDay, int yearLength, int startMonth, boolean sync, MeteorologicalCalendar.Day day) {
        boolean matches(long worldDay, int yearLength, int startMonth, boolean sync) {
            return this.worldDay == worldDay && this.yearLength == yearLength && this.startMonth == startMonth
                    && this.sync == sync;
        }
    }
}
