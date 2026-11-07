package com.imbet.weather;

import com.imbet.Config;
import com.imbet.client.CelestialSky;
import com.imbet.volcano.Volcano;
import com.imbet.volcano.VolcanoPhase;
import com.imbet.volcano.VolcanoService;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The sky's and the land's big moments, as things a player can go and see: an advancement for being there at a total
 * eclipse, a blood moon, an erupting column laced with lightning and a haboob, and a forecast in the morning or the
 * evening of an eclipse day so there is time to get to open sky for it.
 *
 * <p>The eclipses are read off the same deterministic schedule the sky draws them from, which is a pure function of
 * the day, so the server and every client agree about them without anything being synced.
 */
public final class SpectacleService {
    private static final int CHECK_INTERVAL_TICKS = 40;
    private static final long DAY_TICKS = 24000L;
    /** When the morning and evening forecasts go out, as ticks into the day: soon after sunrise and at dusk. */
    private static final long MORNING_TICK = 1000L;
    private static final long EVENING_TICK = 12500L;
    /** How close a player has to be to an erupting column, and to a squall line's front, to count as being there. */
    private static final double VOLCANO_RANGE_BLOCKS = 700.0D;
    private static final double HABOOB_RANGE_BLOCKS = 280.0D;

    private SpectacleService() {
    }

    public static void tick(ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD) {
            return;
        }
        long dayTime = level.getDayTime();
        long inDay = Math.floorMod(dayTime, DAY_TICKS);
        if (Config.CELESTIAL_ENABLED.get()) {
            forecast(level, dayTime, inDay);
        }
        if (level.getGameTime() % CHECK_INTERVAL_TICKS != 0L || !Config.ADVANCEMENTS_ENABLED.get() || level.players().isEmpty()) {
            return;
        }
        boolean totality = false;
        boolean bloodMoon = false;
        if (Config.CELESTIAL_ENABLED.get()) {
            CelestialSky.Eclipse solar = CelestialSky.eclipse(dayTime, Config.CELESTIAL_ECLIPSE_DURATION_SECONDS.get(),
                    Config.CELESTIAL_ECLIPSE_CHANCE.get());
            totality = solar.active() && solar.coverage() > 0.985D;
            CelestialSky.LunarEclipse lunar = CelestialSky.lunarEclipse(dayTime,
                    Config.CELESTIAL_LUNAR_ECLIPSE_DURATION_SECONDS.get(), lunarChance(), level.getMoonPhase());
            bloodMoon = lunar.active() && lunar.coverage() > 0.99D;
        }
        boolean clearSky = !level.isRaining();
        for (ServerPlayer player : level.players()) {
            BlockPos head = BlockPos.containing(player.getEyePosition());
            boolean openSky = clearSky && level.canSeeSky(head);
            if (totality && openSky) {
                AdvancementAwarder.award(player, AdvancementAwarder.TOTALITY);
            }
            if (bloodMoon && openSky) {
                AdvancementAwarder.award(player, AdvancementAwarder.BLOOD_MOON);
            }
            if (Config.VOLCANO_ENABLED.get()) {
                Volcano volcano = VolcanoService.nearest(level, player.getX(), player.getZ(), VOLCANO_RANGE_BLOCKS);
                if (volcano != null && volcano.phase() == VolcanoPhase.MAGMATIC) {
                    AdvancementAwarder.award(player, AdvancementAwarder.VOLCANIC_LIGHTNING);
                }
            }
            if (Config.SQUALL_LINES_ENABLED.get() && !level.getBiome(player.blockPosition()).value().hasPrecipitation()
                    && nearStrongSquallLine(level, player.position())) {
                AdvancementAwarder.award(player, AdvancementAwarder.HABOOB);
            }
        }
    }

    private static double lunarChance() {
        return Config.CELESTIAL_LUNAR_ECLIPSE.get() ? Config.CELESTIAL_LUNAR_ECLIPSE_CHANCE.get() : 0.0D;
    }

    /**
     * A morning note on a solar eclipse day and an evening one on a blood moon night. The schedule is known for the
     * whole day in advance, so the forecast is never wrong.
     */
    private static void forecast(ServerLevel level, long dayTime, long inDay) {
        long dayStart = dayTime - inDay;
        if (inDay == MORNING_TICK) {
            CelestialSky.Eclipse noon = CelestialSky.eclipse(dayStart + 6000L, Config.CELESTIAL_ECLIPSE_DURATION_SECONDS.get(),
                    Config.CELESTIAL_ECLIPSE_CHANCE.get());
            if (noon.active()) {
                announce(level, "message.expandedweather2dynamics.eclipse_forecast.solar");
            }
        } else if (inDay == EVENING_TICK) {
            CelestialSky.LunarEclipse midnight = CelestialSky.lunarEclipse(dayStart + 18000L,
                    Config.CELESTIAL_LUNAR_ECLIPSE_DURATION_SECONDS.get(), lunarChance(), level.getMoonPhase());
            if (midnight.active()) {
                announce(level, "message.expandedweather2dynamics.eclipse_forecast.lunar");
            }
        }
    }

    private static void announce(ServerLevel level, String key) {
        for (ServerPlayer player : level.players()) {
            player.sendSystemMessage(Component.translatable(key).withStyle(ChatFormatting.GOLD));
        }
    }

    /** Whether a squall line strong enough to raise dust has its front close to a position. */
    private static boolean nearStrongSquallLine(ServerLevel level, Vec3 position) {
        for (AddonWeatherEvent event : AddonWeatherEventService.activeEvents(level)) {
            if (event.type() != StormSubtype.SQUALL_LINE || event.maxWindMph() <= 50.0F
                    || event.lineStart() == null || event.lineEnd() == null) {
                continue;
            }
            if (distanceToSegment(position, event.lineStart(), event.lineEnd()) < HABOOB_RANGE_BLOCKS) {
                return true;
            }
        }
        return false;
    }

    private static double distanceToSegment(Vec3 point, Vec3 start, Vec3 end) {
        double sx = end.x - start.x;
        double sz = end.z - start.z;
        double lengthSqr = sx * sx + sz * sz;
        double t = lengthSqr < 1.0E-6D ? 0.0D
                : Math.max(0.0D, Math.min(1.0D, ((point.x - start.x) * sx + (point.z - start.z) * sz) / lengthSqr));
        double dx = point.x - (start.x + sx * t);
        double dz = point.z - (start.z + sz * t);
        return Math.sqrt(dx * dx + dz * dz);
    }
}
