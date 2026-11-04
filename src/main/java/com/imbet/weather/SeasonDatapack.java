package com.imbet.weather;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.imbet.ExpandedWeather2Dynamics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The datapack half of the calendar: what a modpack says about the seasons, read from
 * {@code data/<namespace>/ewd_calendar/*.json}.
 *
 * <p>A datapack is the right place for this because it is what a modpack already ships. A pack
 * that wants its own year - a longer one, a quiet winter, a two-week severe season in the middle
 * of spring - drops one small JSON file alongside its recipes and it applies on the world it was
 * installed into, without a calendar screen, without a config edit, and without the pack having to
 * know anything about this mod's code. Everything in the file is optional and everything is
 * additive: a pack that mentions only August's multiplier leaves the rest of the year exactly as
 * the config has it.
 *
 * <p>Files are read in resource-id order and merged, so two packs can each contribute rather than
 * the alphabetically later one erasing the earlier. Every file is parsed defensively - a malformed
 * entry costs that line and nothing else - and the whole set is cleared when the world is left, so
 * a datapack never leaks its weather into the next save.
 */
public final class SeasonDatapack implements PreparableReloadListener {

    /** The directory under a namespace a calendar override lives in. */
    public static final String DIRECTORY = "ewd_calendar";

    public static final SeasonDatapack INSTANCE = new SeasonDatapack();

    private static volatile MeteorologicalCalendar.Overrides overrides = MeteorologicalCalendar.Overrides.NONE;

    private SeasonDatapack() {
    }

    /** Subscribes the listener. Files are re-read on every datapack reload, which is when they change. */
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }

    /** What the installed datapacks say, or the neutral set when they say nothing. */
    public static MeteorologicalCalendar.Overrides overrides() {
        return overrides;
    }

    /** Forgets the datapacks, which is what leaving a world does before the next one is entered. */
    public static void clear() {
        overrides = MeteorologicalCalendar.Overrides.NONE;
    }

    @Override
    public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager manager,
                                          ProfilerFiller preparationsProfiler, ProfilerFiller reloadProfiler,
                                          Executor backgroundExecutor, Executor gameExecutor) {
        MeteorologicalCalendar.Overrides parsed = read(manager);
        return barrier.wait(Unit.INSTANCE).thenAcceptAsync(ignored -> overrides = parsed, gameExecutor);
    }

    @Override
    public String getName() {
        return "EWD season calendar";
    }

    private static MeteorologicalCalendar.Overrides read(ResourceManager manager) {
        Map<ResourceLocation, Resource> found = manager.listResources(DIRECTORY, path -> path.getPath().endsWith(".json"));
        List<ResourceLocation> ids = new ArrayList<>(found.keySet());
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        MeteorologicalCalendar.Overrides merged = MeteorologicalCalendar.Overrides.NONE;
        int files = 0;
        for (ResourceLocation id : ids) {
            try (BufferedReader reader = found.get(id).openAsReader()) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                merged = merged.merged(MeteorologicalCalendar.parseOverrides(json));
                files++;
            } catch (Exception ex) {
                ExpandedWeather2Dynamics.LOGGER.warn("Could not read season calendar override {}; it is ignored.", id, ex);
            }
        }
        if (files > 0) {
            ExpandedWeather2Dynamics.LOGGER.info("Read {} season calendar override file(s) from data packs.", files);
        }
        return merged;
    }

    /** The unit the reload barrier hands around, named rather than using a raw Object. */
    private enum Unit {
        INSTANCE
    }
}
