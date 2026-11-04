package com.imbet.network;

import com.imbet.ExpandedWeather2Dynamics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * What a weather computer is showing, on its way to the player reading it.
 *
 * <p>A computer's tabs are all server-side knowledge: the products in force are decided from the storms, the logs
 * are the player's own record of what the sky did to them, and the sensor network is read out of the world. None
 * of the three can be worked out on a client, and a client that guessed at them would be the same class of bug
 * this mod has already had once - a display disagreeing with the thing it displays. So the computer sends the
 * lines, and the screen lays them out; the only thing the client decides is which tab is on top, which is a
 * viewing choice rather than a fact about the weather.
 *
 * <p>Each line carries its own colour, and the colour is decided where the words are. A warning is drawn in the
 * colour of the alert level it is - the same colour that level uses on the banner, the siren and the panel -
 * rather than in a colour the screen works out by looking for the letters TOR in a sentence.
 *
 * <p>It is bounded on the way out: a fixed ceiling on lines and a length per line. A station with a hundred
 * instruments on one cable, or an outbreak with forty warnings in force, must not be able to turn a once-a-second
 * refresh into an unbounded packet.
 */
public record ComputerFeedPayload(
        BlockPos origin,
        List<Line> warnings,
        List<Line> logs,
        List<Line> sensors
) implements CustomPacketPayload {
    public static final Type<ComputerFeedPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "computer_feed"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ComputerFeedPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf),
            ComputerFeedPayload::read
    );

    /** How many lines one tab may carry. A tab is a page, not a log file. */
    public static final int MAX_LINES = 24;

    /** How long a line may be, so one pathological label cannot eat the packet. */
    public static final int MAX_LINE_LENGTH = 96;

    /** One line of a tab: what it says, and the colour it is said in. */
    public record Line(String text, int color) {
        public Line {
            text = text == null ? "" : (text.length() <= MAX_LINE_LENGTH ? text : text.substring(0, MAX_LINE_LENGTH));
        }
    }

    public ComputerFeedPayload {
        origin = origin == null ? BlockPos.ZERO : origin.immutable();
        warnings = copy(warnings);
        logs = copy(logs);
        sensors = copy(sensors);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** The lines for one tab, so the screen never has to know which list is where. */
    public List<Line> lines(int tabIndex) {
        return switch (((tabIndex % 3) + 3) % 3) {
            case 1 -> logs;
            case 2 -> warnings;
            // The radar tab's own text is the station's instruments: they are the readings taken under the scope
            // being drawn, which is the only place on this screen where a number belongs beside the picture.
            default -> sensors;
        };
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(origin);
        writeLines(buf, warnings);
        writeLines(buf, logs);
        writeLines(buf, sensors);
    }

    public static ComputerFeedPayload read(RegistryFriendlyByteBuf buf) {
        BlockPos origin = buf.readBlockPos();
        return new ComputerFeedPayload(origin, readLines(buf), readLines(buf), readLines(buf));
    }

    private static void writeLines(RegistryFriendlyByteBuf buf, List<Line> lines) {
        buf.writeVarInt(lines.size());
        for (Line line : lines) {
            buf.writeUtf(line.text(), MAX_LINE_LENGTH);
            buf.writeInt(line.color());
        }
    }

    private static List<Line> readLines(RegistryFriendlyByteBuf buf) {
        int size = Math.min(buf.readVarInt(), MAX_LINES);
        List<Line> lines = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            lines.add(new Line(buf.readUtf(MAX_LINE_LENGTH), buf.readInt()));
        }
        return lines;
    }

    private static List<Line> copy(List<Line> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<Line> lines = new ArrayList<>(Math.min(source.size(), MAX_LINES));
        for (Line line : source) {
            if (line == null) {
                continue;
            }
            lines.add(line);
            if (lines.size() >= MAX_LINES) {
                break;
            }
        }
        return List.copyOf(lines);
    }
}
