package com.imbet.command;

import com.imbet.weather.AddonWeatherEvent;
import com.imbet.weather.AddonWeatherEventService;
import com.imbet.weather.StormSubtype;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.util.Locale;

public final class EwdCommand {
    private static final String[] TYPE_SUGGESTIONS = {
            "dust_devil",
            "snow_devil",
            "gustnado",
            "fire_whirl",
            "landspout",
            "waterspout",
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
                .then(Commands.literal("spawn")
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(TYPE_SUGGESTIONS, builder))
                                .executes(context -> spawn(context, Double.NaN, Double.NaN))
                                .then(Commands.argument("windMph", DoubleArgumentType.doubleArg(0.0D, 250.0D))
                                        .executes(context -> spawn(context, DoubleArgumentType.getDouble(context, "windMph"), Double.NaN))
                                        .then(Commands.argument("sizeBlocks", DoubleArgumentType.doubleArg(1.0D, 2000.0D))
                                                .executes(context -> spawn(context, DoubleArgumentType.getDouble(context, "windMph"), DoubleArgumentType.getDouble(context, "sizeBlocks"))))))));
    }

    private static int help(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.literal("EWD commands: /ewd spawn <dust_devil|snow_devil|gustnado|fire_whirl|landspout|waterspout|squall_line|wildfire|flammagenitus|cyclone|hurricane> [windMph] [sizeBlocks]")
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int spawn(CommandContext<CommandSourceStack> context, double windMph, double sizeBlocks) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        StormSubtype type = parseType(StringArgumentType.getString(context, "type"));
        if (type == null) {
            source.sendFailure(Component.literal("Unknown EWD weather type. Try: dust_devil, snow_devil, gustnado, fire_whirl, landspout, waterspout, squall_line, wildfire, flammagenitus, cyclone, hurricane."));
            return 0;
        }

        Vec3 forward = horizontalForward(source);
        Vec3 spawnPos = source.getPosition().add(forward.scale(spawnOffset(type)));
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
            case "squall", "squall_line", "qlcs", "line" -> StormSubtype.SQUALL_LINE;
            case "wildfire", "wild_fire", "brushfire" -> StormSubtype.WILDFIRE;
            case "flammagenitus", "pyrocumulus", "pyrocb", "pyrocumulonimbus" -> StormSubtype.FLAMMAGENITUS;
            case "hurricane", "cyclone", "tropical_cyclone" -> StormSubtype.HURRICANE;
            default -> null;
        };
    }

    private static Vec3 horizontalForward(CommandSourceStack source) {
        float yaw = source.getRotation().y;
        double radians = Math.toRadians(yaw);
        Vec3 forward = new Vec3(-Math.sin(radians), 0.0D, Math.cos(radians));
        return forward.horizontalDistanceSqr() < 1.0E-5D ? new Vec3(1.0D, 0.0D, 0.0D) : forward.normalize();
    }

    private static double spawnOffset(StormSubtype type) {
        return switch (type) {
            case HURRICANE -> 180.0D;
            case SQUALL_LINE -> 96.0D;
            case WILDFIRE, FLAMMAGENITUS -> 64.0D;
            case WATERSPOUT, LANDSPOUT -> 28.0D;
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
