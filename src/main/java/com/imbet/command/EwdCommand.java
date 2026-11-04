package com.imbet.command;

import com.imbet.weather.AddonWeatherEvent;
import com.imbet.weather.AddonWeatherEventService;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.HurricaneShield;
import com.imbet.weather.MeteorologicalCalendar;
import com.imbet.weather.SeasonCalendarService;
import com.imbet.weather.SeasonDatapack;
import com.imbet.weather.StormClassifier;
import com.imbet.weather.StormSubtype;
import com.imbet.weather.TornadoGenesisGate;
import com.imbet.weather.TornadoWindField;
import com.imbet.Config;
import com.imbet.volcano.Volcano;
import com.imbet.volcano.VolcanoModel;
import com.imbet.volcano.VolcanoService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import weather2.weathersystem.storm.StormObject;

import java.util.List;
import java.util.Locale;

public final class EwdCommand {
    private static final String[] TYPE_SUGGESTIONS = {
            "dust_devil",
            "snow_devil",
            "gustnado",
            "fire_whirl",
            "landspout",
            "waterspout",
            "funnel_cloud",
            "squall_line",
            "wildfire",
            "flammagenitus",
            "cyclone",
            "hurricane"
    };

    private EwdCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ewd")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("help")
                        .executes(EwdCommand::help))
                .then(spawnNode())
                .then(seasonNode())
                .then(volcanoNode()));
    }

    /**
     * `/ewd season`: what day the world is on, what its season is doing to the air, and whether a
     * tornado could be born where the caller is standing.
     *
     * <p>It is one command rather than three because they are one question. A player who cannot work
     * out why their world has gone quiet wants the day, the strength the season is applying, and the
     * ground's own verdict in the same breath - and a modpack maker setting the calendar up wants
     * exactly the same three lines, which is why the numbers are printed rather than described.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> seasonNode() {
        return Commands.literal("season")
                .executes(EwdCommand::season);
    }

    private static int season(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        MeteorologicalCalendar.Day day = SeasonCalendarService.day(level);
        MeteorologicalCalendar.Effect effect = SeasonCalendarService.effect(day);
        double strength = SeasonCalendarService.strength(day);
        source.sendSuccess(() -> Component.literal("Meteorological day: " + day.label()
                        + " - " + day.monthLabel() + " " + day.dayOfMonth()
                        + " of a " + day.yearLength() + "-day year")
                .withStyle(ChatFormatting.AQUA), false);
        source.sendSuccess(() -> Component.literal(SeasonCalendarService.enabled()
                        ? String.format(Locale.ROOT,
                        "Season strength %.2f, applied as: instability x%.2f, shear x%.2f, fronts x%.2f, tornadoes x%.2f, temperature %+.2f, moisture %+.2f",
                        strength, effect.instability(), effect.shear(), effect.frontal(), effect.tornado(),
                        effect.temperature(), effect.moisture())
                        : "Season calendar is switched off (season_calendar.enabled): every day is the neutral day.")
                .withStyle(ChatFormatting.GOLD), false);
        BlockPos here = BlockPos.containing(source.getPosition());
        source.sendSuccess(() -> Component.literal("Tornado genesis here: " + TornadoGenesisGate.verdict(level, here)
                        + " - " + TornadoGenesisGate.biomePath(level, here))
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Calendar source: " + day.source()
                        + " | season calendar data packs: "
                        + (SeasonDatapack.overrides().present() ? "applied" : "none"))
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    /** `/ewd spawn <type> [windMph] [sizeBlocks]`: the weather itself. */
    private static LiteralArgumentBuilder<CommandSourceStack> spawnNode() {
        return Commands.literal("spawn")
                .then(Commands.argument("type", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(TYPE_SUGGESTIONS, builder))
                        .executes(context -> spawn(context, Double.NaN, Double.NaN))
                        .then(Commands.argument("windMph", DoubleArgumentType.doubleArg(0.0D, 250.0D))
                                .executes(context -> spawn(context, DoubleArgumentType.getDouble(context, "windMph"), Double.NaN))
                                .then(Commands.argument("sizeBlocks", DoubleArgumentType.doubleArg(1.0D, 2000.0D))
                                        .executes(context -> spawn(context,
                                                DoubleArgumentType.getDouble(context, "windMph"),
                                                DoubleArgumentType.getDouble(context, "sizeBlocks"))))));
    }

    /**
     * `/ewd volcano ...`: the one part of the mod that is not weather.
     *
     * <p>It gets its own verb because nothing about a volcano needs a storm to be present first, and because
     * the command is how the whole ladder is seen inside a few minutes rather than over a few game days:
     * place one, watch it arrive, and wake it up when you want the next rung.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> volcanoNode() {
        return Commands.literal("volcano")
                .then(Commands.literal("list")
                        .executes(EwdCommand::volcanoList))
                .then(Commands.literal("erupt")
                        .then(Commands.argument("id", LongArgumentType.longArg(0L))
                                .executes(EwdCommand::volcanoErupt)))
                .then(Commands.literal("remove")
                        .then(Commands.argument("id", LongArgumentType.longArg(0L))
                                .executes(EwdCommand::volcanoRemove)))
                .then(Commands.literal("spawn")
                        .executes(context -> volcanoSpawn(context, Double.NaN, -1))
                        .then(Commands.argument("intensity", DoubleArgumentType.doubleArg(0.0D, 6.0D))
                                .executes(context -> volcanoSpawn(context, DoubleArgumentType.getDouble(context, "intensity"), -1))
                                .then(Commands.argument("scale", IntegerArgumentType.integer(0, 40))
                                        .executes(context -> volcanoSpawn(context,
                                                DoubleArgumentType.getDouble(context, "intensity"),
                                                IntegerArgumentType.getInteger(context, "scale"))))
                                .then(Commands.argument("distanceBlocks", IntegerArgumentType.integer(64, 8000))
                                        .executes(context -> volcanoSpawnAhead(context,
                                                DoubleArgumentType.getDouble(context, "intensity"),
                                                IntegerArgumentType.getInteger(context, "distanceBlocks"))))));
    }

    private static int help(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.literal("EWD commands: /ewd spawn <dust_devil|snow_devil|gustnado|fire_whirl|landspout|waterspout|funnel_cloud|squall_line|wildfire|flammagenitus|cyclone|hurricane> [windMph] [sizeBlocks]")
                .withStyle(ChatFormatting.AQUA), false);
        source.sendSuccess(() -> Component.literal("EWD calendar: /ewd season (the world's meteorological day, what the season is doing to the air, and whether a tornado may be born where you are standing)")
                .withStyle(ChatFormatting.AQUA), false);
        source.sendSuccess(() -> Component.literal("EWD volcanoes: /ewd volcano spawn [intensity] [scale] (places one 24 blocks ahead) | /ewd volcano spawn <intensity> <distanceBlocks> | /ewd volcano erupt <id> (wake it, or push it a phase on) | /ewd volcano list | /ewd volcano remove <id>")
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /**
     * Puts a volcano where the caller is looking.
     *
     * <p>The default is deliberately placed rather than random: a mountain arriving at the player's feet is no
     * use to anyone, and the point of the command is to see the whole ladder - unrest, steam, the column, the
     * collapse, the lava - inside a few minutes. A volcano placed by hand is awake as soon as it is placed.
     */
    private static int volcanoSpawn(CommandContext<CommandSourceStack> context, double intensity, int scale) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!requireVolcanoesEnabled(source)) {
            return 0;
        }
        double requested = Double.isNaN(intensity)
                ? Config.VOLCANO_MAX_INTENSITY.get() * 0.6D
                : intensity;
        // A negative scale means the caller did not name one, and it is the only way to say that: 0 is a legal
        // size, and it builds a five block high lump that reads as a mistake rather than as a volcano.
        int size = scale < 0 ? VolcanoModel.SUMMONED_SCALE : scale;
        Vec3 target = source.getPosition().add(horizontalForward(source).scale(volcanoPlacementBlocks(size)));
        Volcano volcano = VolcanoService.spawnCommandVolcano(level, BlockPos.containing(target), source.getPosition().y, requested, size);
        reportVolcano(source, volcano);
        return 1;
    }

    /**
     * Where a volcano summoned by the bare command goes.
     *
     * <p>In front of the caller, not under them. A cone builds itself outwards from its vent, so a vent at the
     * caller's own feet means the mountain grows around them and they watch the eruption from inside a wall of
     * basalt. Twenty-four blocks ahead is close enough to see every phase of it and far enough to survive it.
     */
    private static final double VOLCANO_PLACEMENT_BLOCKS = 24.0D;

    /**
     * How far ahead the bare command puts one, given the size being asked for.
     *
     * <p>Always outside the edifice that is about to be built: a mountain's radius grows six blocks per point of
     * scale, so a volcano summoned at a fixed distance is inside itself the moment anyone asks for a large one.
     */
    private static double volcanoPlacementBlocks(int scale) {
        return Math.max(VOLCANO_PLACEMENT_BLOCKS, VolcanoModel.edificeRadiusBlocks(Math.max(0, scale)) + VOLCANO_PLACEMENT_BLOCKS);
    }

    /** Says where the volcano actually went, which is not where the caller is standing. */
    private static void reportVolcano(CommandSourceStack source, Volcano volcano) {
        source.sendSuccess(() -> Component.literal("Volcano " + volcano.id() + " at "
                        + volcano.x() + " " + volcano.ventY() + " " + volcano.z()
                        + " (VEI " + String.format(Locale.ROOT, "%.1f", volcano.intensity()) + ", scale " + volcano.scale()
                        + "), " + String.format(Locale.ROOT, "%.0f", source.getPosition().distanceTo(
                                new Vec3(volcano.x() + 0.5D, volcano.ventY(), volcano.z() + 0.5D))) + " blocks away, now in "
                        + volcano.phase().label() + ". The cone builds itself as the eruption runs; /ewd volcano erupt "
                        + volcano.id() + " pushes it on to the next phase.")
                .withStyle(ChatFormatting.GOLD), true);
    }

    /** Puts one a set distance ahead, which is how to watch an eruption rather than be inside it. */
    private static int volcanoSpawnAhead(CommandContext<CommandSourceStack> context, double intensity, int distanceBlocks) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!requireVolcanoesEnabled(source)) {
            return 0;
        }
        Vec3 ahead = source.getPosition().add(horizontalForward(source).scale(distanceBlocks));
        Volcano volcano = VolcanoService.spawnCommandVolcano(level, BlockPos.containing(ahead), source.getPosition().y, intensity,
                VolcanoModel.SUMMONED_SCALE);
        reportVolcano(source, volcano);
        return 1;
    }

    /** Wakes one up immediately, at its own next intensity, without waiting out its quiescence. */
    private static int volcanoErupt(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        long id = com.mojang.brigadier.arguments.LongArgumentType.getLong(context, "id");
        if (!VolcanoService.forceEruption(level, id)) {
            source.sendFailure(Component.literal("No volcano with id " + id + " in this world."));
            return 0;
        }
        Volcano volcano = VolcanoService.find(level, id);
        source.sendSuccess(() -> Component.literal("Volcano " + id + " is waking up (VEI "
                        + String.format(Locale.ROOT, "%.1f", volcano.intensity()) + ", now " + volcano.phase().label() + ").")
                .withStyle(ChatFormatting.RED), true);
        return 1;
    }

    private static int volcanoRemove(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        long id = com.mojang.brigadier.arguments.LongArgumentType.getLong(context, "id");
        if (!VolcanoService.remove(source.getLevel(), id)) {
            source.sendFailure(Component.literal("No volcano with id " + id + " in this world."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Volcano " + id + " removed. Its mountain stays where it is.")
                .withStyle(ChatFormatting.GRAY), true);
        return 1;
    }

    /** Spawning one is pointless while the whole system is switched off, so it says so rather than doing nothing. */
    private static boolean requireVolcanoesEnabled(CommandSourceStack source) {
        if (Config.VOLCANO_ENABLED.get()) {
            return true;
        }
        source.sendFailure(Component.literal("Volcanoes are switched off. Enable volcano.enabled in the config first."));
        return false;
    }

    private static int volcanoList(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        List<Volcano> volcanoes = VolcanoService.volcanoes(level);
        if (volcanoes.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No volcanoes in this world.").withStyle(ChatFormatting.GRAY), false);
            return 1;
        }
        for (Volcano volcano : volcanoes) {
            String line = "#" + volcano.id() + " " + volcano.phase().label()
                    + " at " + volcano.x() + " " + volcano.ventY() + " " + volcano.z()
                    + ", VEI " + String.format(Locale.ROOT, "%.1f", volcano.intensity())
                    + ", scale " + volcano.scale()
                    + ", column " + Math.round(volcano.plumeMetres()) + " m"
                    + ", ash " + Math.round(volcano.ashMm()) + " mm"
                    + ", " + blockCoords(new Vec3(volcano.x(), volcano.ventY(), volcano.z()));
            source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GOLD), false);
        }
        return volcanoes.size();
    }

    private static int spawn(CommandContext<CommandSourceStack> context, double windMph, double sizeBlocks) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        StormSubtype type = parseType(StringArgumentType.getString(context, "type"));
        if (type == null) {
            source.sendFailure(Component.literal("Unknown EWD weather type. Try: dust_devil, snow_devil, gustnado, fire_whirl, landspout, waterspout, funnel_cloud, squall_line, wildfire, flammagenitus, cyclone, hurricane."));
            return 0;
        }

        Vec3 forward = horizontalForward(source);
        Vec3 spawnPos = source.getPosition().add(forward.scale(spawnOffset(type, sizeBlocks)));
        // A hurricane or a cyclone is not an addon vortex: it is a real Weather2 water storm on the
        // 1.12.2 tropical ladder, so the command spawns the storm itself rather than an event that
        // simulates one.
        if (type == StormSubtype.HURRICANE || type == StormSubtype.CYCLONE) {
            boolean antiCyclonic = type == StormSubtype.CYCLONE;
            double wind = antiCyclonic
                    ? clampDefault(windMph, 80.0D, 58.0D, 130.0D)
                    : clampDefault(windMph, 92.0D, 74.0D, 185.0D);
            double size = antiCyclonic
                    ? clampDefault(sizeBlocks, 420.0D, 160.0D, 1400.0D)
                    : clampDefault(sizeBlocks, 560.0D, 160.0D, 1400.0D);
            StormObject storm = AddonWeatherEventService.spawnCommandTropicalStorm(level, spawnPos, forward, wind, size, antiCyclonic);
            if (storm == null) {
                source.sendFailure(Component.literal("Could not spawn " + type.label() + " - no weather manager for this level."));
                return 0;
            }
            StormSubtype subtype = StormClassifier.classify(storm, DynamicStormController.stateFor(storm));
            source.sendSuccess(() -> Component.literal("Spawned " + StormClassifier.labelFor(storm, subtype)
                    + " at " + blockCoords(storm.pos)
                    + " | wind " + Math.round(TornadoWindField.maxWindMph(storm)) + " mph"
                    + " | " + Math.round(HurricaneShield.shieldRadius(storm) * 2.0D) + " block shield")
                    .withStyle(ChatFormatting.GREEN), true);
            return 1;
        }
        AddonWeatherEvent event = AddonWeatherEventService.spawnCommandEvent(level, type, spawnPos, forward, windMph, sizeBlocks);
        if (event == null) {
            source.sendFailure(Component.literal("Could not spawn " + type.label() + "."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Spawned " + event.type().label()
                + " #" + event.id()
                + " at " + blockCoords(event.position())
                + " | wind " + Math.round(event.maxWindMph()) + " mph"
                + " | " + sizeLabel(event))
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static StormSubtype parseType(String raw) {
        String type = raw.toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (type) {
            case "dust", "dustdevil", "dust_devil" -> StormSubtype.DUST_DEVIL;
            case "snow", "snowdevil", "snow_devil" -> StormSubtype.SNOW_DEVIL;
            case "gust", "gustnado", "gustnadoe" -> StormSubtype.GUSTNADO;
            case "fire", "firewhirl", "fire_whirl", "firenado" -> StormSubtype.FIRE_WHIRL;
            case "land", "landspout" -> StormSubtype.LANDSPOUT;
            case "water", "spout", "waterspout" -> StormSubtype.WATERSPOUT;
            case "funnel", "funnelcloud", "funnel_cloud", "cold_air_funnel", "coldairfunnel" -> StormSubtype.FUNNEL_CLOUD;
            case "squall", "squall_line", "qlcs", "line" -> StormSubtype.SQUALL_LINE;
            case "wildfire", "wild_fire", "brushfire" -> StormSubtype.WILDFIRE;
            case "flammagenitus", "pyrocumulus", "pyrocb", "pyrocumulonimbus" -> StormSubtype.FLAMMAGENITUS;
            case "hurricane", "tropical_cyclone" -> StormSubtype.HURRICANE;
            case "cyclone", "anti_cyclone", "anticyclone" -> StormSubtype.CYCLONE;
            default -> null;
        };
    }

    private static Vec3 horizontalForward(CommandSourceStack source) {
        float yaw = source.getRotation().y;
        double radians = Math.toRadians(yaw);
        Vec3 forward = new Vec3(-Math.sin(radians), 0.0D, Math.cos(radians));
        return forward.horizontalDistanceSqr() < 1.0E-5D ? new Vec3(1.0D, 0.0D, 0.0D) : forward.normalize();
    }

    /** The value the command was given, or its default, clamped into the type's own range. */
    private static double clampDefault(double requested, double fallback, double min, double max) {
        double value = Double.isNaN(requested) ? fallback : requested;
        return Math.max(min, Math.min(max, value));
    }

    private static double spawnOffset(StormSubtype type, double requestedSizeBlocks) {
        return switch (type) {
            // A hurricane is a storm you stand inside, not beside: it is placed far enough out that the
            // player looks at its shield rather than spawning under the eye wall. The offset follows the
            // requested size, so asking for a bigger storm does not put you further inside it.
            case HURRICANE, CYCLONE -> clampDefault(requestedSizeBlocks, 560.0D, 160.0D, 1400.0D);
            case SQUALL_LINE -> 96.0D;
            case WILDFIRE, FLAMMAGENITUS -> 64.0D;
            case WATERSPOUT, LANDSPOUT, FUNNEL_CLOUD -> 28.0D;
            default -> 18.0D;
        };
    }

    private static String sizeLabel(AddonWeatherEvent event) {
        if (event.isLine() && event.lineStart() != null && event.lineEnd() != null) {
            return Math.round(event.lineStart().distanceTo(event.lineEnd())) + " block line";
        }
        return Math.round(event.radius() * 2.0F) + " block width";
    }

    private static String blockCoords(Vec3 position) {
        return (int) Math.floor(position.x) + " " + (int) Math.floor(position.y) + " " + (int) Math.floor(position.z);
    }
}
