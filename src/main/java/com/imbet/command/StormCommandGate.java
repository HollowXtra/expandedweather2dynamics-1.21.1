package com.imbet.command;

import com.imbet.Config;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.neoforge.event.CommandEvent;

/**
 * Decides whether the command about to execute is a storm summoning, and enforces the summon
 * gate at the one place every command execution passes through.
 *
 * <p>The enforcement point is NeoForge's {@link CommandEvent}, which {@code Commands.performCommand}
 * posts before executing and honours cancellation - so this covers typed commands, command blocks,
 * Rcon and {@code /execute run weather2 summon ...} wrappers alike, with no mixin into brigadier.
 * The {@code /execute} case works because brigadier parses the inner command into the same node
 * chain: the wrapper never mentions "weather2" at the front of the typed line, but the parsed
 * nodes still contain the {@code weather2} and {@code summon} literals, because brigadier parsed
 * the real command before executing it.</p>
 *
 * <p>The match is deliberately narrow: {@code spawn} has to appear <em>with</em> {@code ewd} in the
 * same executed chain, and {@code summon} with {@code weather2}, so {@code /ewd help} stays
 * readable to everyone who can see the command, and Weather2's precipitation and rain-control
 * commands keep working for anyone Weather2 itself would allow.</p>
 */
public final class StormCommandGate {

    private static final String EWD_ROOT = "ewd";
    private static final String EWD_SPAWN = "spawn";
    private static final String WEATHER2_ROOT = "weather2";
    private static final String SUMMON_LITERAL = "summon";

    private StormCommandGate() {
    }

    /** NeoForge command-event hook: refuses storm summonings that fail the gate. */
    public static void onCommand(CommandEvent event) {
        CommandContextBuilder<CommandSourceStack> context = event.getParseResults().getContext();
        if (context == null) {
            return;
        }
        CommandSourceStack stack = context.getSource();
        if (!shouldBlock(event.getParseResults(), stack)) {
            return;
        }
        stack.sendFailure(CommandGate.denial());
        // A plain cancel (no exception) makes performCommand return silently; the failure
        // message above is the whole response.
        event.setCanceled(true);
    }

    /**
     * Whether this execution should be refused under the summon gate. Only ever true for a player
     * who fails {@link CommandGate#allows} - console, command blocks and Rcon pass untouched, and
     * with {@code gateStormSummonCommands} off nothing is ever blocked.
     */
    public static boolean shouldBlock(ParseResults<CommandSourceStack> parse, CommandSourceStack stack) {
        if (!Config.GATE_STORM_SUMMONS.get()) {
            return false;
        }
        if (!stack.isPlayer() || CommandGate.allows(stack)) {
            return false;
        }
        return isStormSummon(parse);
    }

    /** Whether the parsed command chain contains a storm summoning for either mod. */
    public static boolean isStormSummon(ParseResults<CommandSourceStack> parse) {
        CommandContextBuilder<CommandSourceStack> context = parse.getContext();
        if (context == null) {
            return false;
        }
        List<ParsedCommandNode<CommandSourceStack>> nodes = context.getNodes();
        boolean ewdSeen = false;
        boolean weather2Seen = false;
        for (ParsedCommandNode<CommandSourceStack> node : nodes) {
            String name = node.getNode().getName();
            boolean literal = node.getNode() instanceof LiteralCommandNode;
            if (literal && EWD_ROOT.equals(name)) {
                ewdSeen = true;
            }
            if (literal && WEATHER2_ROOT.equals(name)) {
                weather2Seen = true;
            }
            if (ewdSeen && literal && EWD_SPAWN.equals(name)) {
                return true;
            }
            if (weather2Seen && literal && SUMMON_LITERAL.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
