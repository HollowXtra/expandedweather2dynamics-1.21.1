package com.imbet.spc;

import com.imbet.chase.ChaseLogService;
import com.imbet.instruments.SensorFeed;
import com.imbet.network.ComputerFeedPayload;
import com.imbet.photo.PhotoService;
import com.imbet.spotter.SpotterService;
import com.imbet.weather.AlertService;
import com.imbet.weather.OutbreakService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything a weather computer knows, gathered into the lines its tabs show.
 *
 * <p>It is deliberately a builder rather than four readouts printed in a row. Each of the three tabs is a
 * different kind of statement - the products in force here, the record of what the sky has already done to the
 * player reading it, and what the instruments wired to this machine are reading - and each of them already exists
 * somewhere else in the mod. The computer's job is not to work any of it out again but to put the three side by
 * side in one window, which is exactly what makes it a hub rather than a fourth instrument.
 *
 * <p>So there is nothing measured here. The warnings are the alert ladder's own list and its own colours, the logs
 * are the player's own services, and the sensors are the wire's own walk. If a line reads wrong, it reads wrong in
 * the place it came from, which is the only place it can be fixed.
 */
public final class ComputerFeed {

    /** The colour a line of the record is drawn in: the same pale text the rest of the station readouts use. */
    public static final int LOG_COLOR = 0xFFE6C87A;
    /** The colour of a line about an instrument, which is neither a warning nor a record. */
    public static final int SENSOR_COLOR = 0xFF7FC8E8;

    private ComputerFeed() {
    }

    /** The three tabs' worth of lines, as they are sent. */
    public static ComputerFeedPayload build(ServerLevel level, ServerPlayer player, BlockPos computer) {
        Vec3 center = Vec3.atCenterOf(computer);
        return new ComputerFeedPayload(computer, warningLines(level, center), logLines(player), sensorLines(level, computer));
    }

    /**
     * What is in force at the computer, strongest first.
     *
     * <p>Every distinct product rather than only the strongest, which is the whole point of a warnings tab: a
     * player standing under a severe thunderstorm warning inside an outbreak advisory and beside a tornado warning
     * is entitled to see all three, in that order, rather than only the one a siren would sound.
     */
    private static List<ComputerFeedPayload.Line> warningLines(ServerLevel level, Vec3 center) {
        List<ComputerFeedPayload.Line> lines = new ArrayList<>();
        for (AlertService.AlertResult alert : AlertService.activeAlerts(level, center)) {
            if (alert.level().redstonePower() <= 0) {
                continue;
            }
            double distance = alert.position() == null ? -1.0D : center.distanceTo(alert.position());
            lines.add(new ComputerFeedPayload.Line(String.format(Locale.ROOT, "%s %s (%s)%s",
                    alert.productCode(),
                    alert.label(),
                    alert.source(),
                    distance < 0.0D ? "" : String.format(Locale.ROOT, " - %.0f blocks", distance)),
                    alert.level().color()));
        }
        OutbreakService.Episode outbreak = OutbreakService.active(level);
        if (outbreak != null) {
            lines.add(new ComputerFeedPayload.Line(OutbreakService.progressLine(outbreak), LOG_COLOR));
        }
        if (lines.isEmpty()) {
            lines.add(new ComputerFeedPayload.Line("No products in force at this station.", SENSOR_COLOR));
        }
        return List.copyOf(lines);
    }

    /** What the sky has already done to the player reading it: chases, reports and photographs. */
    private static List<ComputerFeedPayload.Line> logLines(ServerPlayer player) {
        if (player == null) {
            return List.of(new ComputerFeedPayload.Line("No record yet.", SENSOR_COLOR));
        }
        List<ComputerFeedPayload.Line> lines = new ArrayList<>();
        addAll(lines, ChaseLogService.readoutLines(player));
        addAll(lines, SpotterService.readoutLines(player));
        addAll(lines, PhotoService.readoutLines(player));
        if (lines.isEmpty()) {
            lines.add(new ComputerFeedPayload.Line("No storms met yet - the log fills when one reaches you.", SENSOR_COLOR));
        }
        return List.copyOf(lines);
    }

    /** The instruments on this machine's own cable, which is the one thing a computer knows that a block does not. */
    private static List<ComputerFeedPayload.Line> sensorLines(ServerLevel level, BlockPos computer) {
        List<ComputerFeedPayload.Line> lines = new ArrayList<>();
        addAll(lines, SensorFeed.reportLines(level, computer));
        return List.copyOf(lines);
    }

    private static void addAll(List<ComputerFeedPayload.Line> target, List<String> source) {
        for (String line : source) {
            target.add(new ComputerFeedPayload.Line(line, SENSOR_COLOR));
        }
    }
}
