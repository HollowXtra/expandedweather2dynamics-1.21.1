package com.imbet.command;

import com.imbet.Config;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * The one rule for who may summon a storm, shared by every summoning path.
 *
 * <p>Summoning a tornado is not like summoning a sheep: the storm persists, wanders, wrecks
 * landscape and ends lives, so the bar is deliberately higher than "has permission to run a
 * command at all". The rule is permission level 2 (operator) <em>and</em> not in survival mode -
 * an operator playing survival is playing the same game everyone else is, and the survival rule
 * exists precisely so a gamemode switch is the deliberate act that unlocks storm powers.</p>
 *
 * <p>Console and command blocks have no game mode and are always allowed - automation that
 * schedules storms is a legitimate server setup. Everything else, including Weather2's own
 * /weather2 summon tree, funnels through {@link #check}.</p>
 */
public final class CommandGate {

    private CommandGate() {
    }

    /**
     * Whether this source may summon a storm right now. {@code gateStormSummonCommands} turns the
     * whole rule off for servers that want the old behaviour.
     */
    public static boolean allows(CommandSourceStack source) {
        if (!Config.GATE_STORM_SUMMONS.get()) {
            return true;
        }
        if (source == null || !source.isPlayer()) {
            // Console, command blocks, Rcon: no survival mode to be in.
            return true;
        }
        ServerPlayer player = source.getPlayer();
        return source.hasPermission(2) && player != null
                && !player.gameMode.getGameModeForPlayer().isSurvival();
    }

    /** The denial message, phrased as the rule rather than an error. */
    public static Component denial() {
        return Component.literal(
                "Storm summoning requires operator status and a game mode other than survival.");
    }
}
