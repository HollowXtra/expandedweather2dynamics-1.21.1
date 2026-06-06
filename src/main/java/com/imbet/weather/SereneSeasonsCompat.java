package com.imbet.weather;

import com.imbet.ExpandedWeather2Dynamics;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.Locale;

public final class SereneSeasonsCompat {
    private static final boolean LOADED = ModList.get().isLoaded("sereneseasons");
    private static boolean disabledForSession;
    private static boolean loggedFailure;

    private SereneSeasonsCompat() {
    }

    public static SeasonSample sample(ServerLevel level, BlockPos pos) {
        if (!LOADED || disabledForSession || level == null || pos == null) {
            return SeasonSample.vanilla();
        }
        try {
            Object state = seasonState(level);
            if (state == null) {
                return SeasonSample.vanilla();
            }
            Object subSeason = invokeNoArg(state, "getSubSeason");
            Object season = subSeason == null ? invokeNoArg(state, "getSeason") : invokeNoArg(subSeason, "getSeason");
            String label = labelFor(subSeason, season);
            double tempModifier = temperatureModifier(label);
            return new SeasonSample(true, label, tempModifier, moistureLift(label), hurricaneWindow(label));
        } catch (ReflectiveOperationException | LinkageError ex) {
            disabledForSession = true;
            if (!loggedFailure) {
                loggedFailure = true;
                ExpandedWeather2Dynamics.LOGGER.warn("Serene Seasons thermodynamic compat could not read the installed API; using vanilla biome fallback for this session.", ex);
            }
            return SeasonSample.vanilla();
        }
    }

    public static boolean loaded() {
        return LOADED && !disabledForSession;
    }

    private static Object seasonState(ServerLevel level) throws ReflectiveOperationException {
        Class<?> helper = Class.forName("sereneseasons.api.season.SeasonHelper");
        for (Method method : helper.getMethods()) {
            if (!method.getName().equals("getSeasonState") || method.getParameterCount() != 1 || !method.getParameterTypes()[0].isAssignableFrom(level.getClass())) {
                continue;
            }
            return method.invoke(null, level);
        }
        for (Method method : helper.getMethods()) {
            if (method.getName().equals("getSeasonState") && method.getParameterCount() == 1) {
                return method.invoke(null, level);
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object instance, String methodName) throws ReflectiveOperationException {
        if (instance == null) {
            return null;
        }
        Method method = instance.getClass().getMethod(methodName);
        method.setAccessible(true);
        return method.invoke(instance);
    }

    private static String labelFor(Object subSeason, Object season) {
        String sub = subSeason == null ? "" : subSeason.toString();
        String base = season == null ? "" : season.toString();
        String label = sub.isBlank() ? base : sub;
        return label.isBlank() ? "Serene Seasons" : label;
    }

    private static double temperatureModifier(String label) {
        String lower = normalize(label);
        if (lower.contains("winter")) {
            return -0.48D;
        }
        if (lower.contains("early_spring") || lower.contains("early spring")) {
            return -0.14D;
        }
        if (lower.contains("spring")) {
            return 0.12D;
        }
        if (lower.contains("late_summer") || lower.contains("late summer")) {
            return 0.34D;
        }
        if (lower.contains("summer")) {
            return 0.42D;
        }
        if (lower.contains("early_autumn") || lower.contains("early fall") || lower.contains("early autumn")) {
            return 0.16D;
        }
        if (lower.contains("autumn") || lower.contains("fall")) {
            return -0.08D;
        }
        return 0.0D;
    }

    private static double moistureLift(String label) {
        String lower = normalize(label);
        if (lower.contains("spring") || lower.contains("early_summer") || lower.contains("early summer")) {
            return 0.14D;
        }
        if (lower.contains("late_summer") || lower.contains("early_autumn") || lower.contains("early autumn")) {
            return 0.08D;
        }
        if (lower.contains("winter")) {
            return -0.18D;
        }
        return 0.0D;
    }

    private static boolean hurricaneWindow(String label) {
        String lower = normalize(label);
        return lower.contains("late_summer") || lower.contains("late summer") || lower.contains("early_autumn") || lower.contains("early autumn") || lower.contains("early_fall") || lower.contains("early fall");
    }

    private static String normalize(String label) {
        return label == null ? "" : label.toLowerCase(Locale.ROOT);
    }

    public record SeasonSample(boolean sereneSeasons, String label, double temperatureModifier, double moistureLift, boolean hurricaneWindow) {
        public static SeasonSample vanilla() {
            return new SeasonSample(false, "Vanilla", 0.0D, 0.0D, false);
        }
    }
}
