package com.imbet.network;

import com.imbet.ExpandedWeather2Dynamics;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * One whole body a tornado has taken: a building off its foundation, or a tree out of the ground.
 * Carries where it stood, how long it flies, and every block it is made of as an offset from the
 * body's centre.
 *
 * <p>The block list travels with the event because the client is the one that carries the body: the
 * server removes the blocks and says what was taken, and each client draws the body from its own copy -
 * the same division of labour the storm's other effects use, where the server owns the world and the
 * client owns the pixels. Offsets are in blocks from the body's centre, so a client can rotate the whole
 * body without ever asking the server where a plank is.
 *
 * <p>{@link Kind} is what the body does with itself. A building is hauled into the circulation and held
 * there until it comes apart in the air. A tree is pulled up, slung around and THROWN: its flight ends
 * on the ground, at a landing point the server picked before it was lifted, so the client can fly it down
 * onto exactly the spot the server is going to resolve the impact at. That agreement is why the landing
 * point is in the packet - a body that is drawn one place and hits another reads as a bug.
 *
 * <p>{@code fellOnLanding} is what happens after that impact. A tree can shatter into wind-carried
 * splinters like everything else, or - when it lands on open ground soft enough to take a trunk - come
 * down in one piece and stay there: the client settles the body onto the ground at rest and the server
 * places a real felled trunk lying along the throw direction, so what is left in the world is a log the
 * player can break, not a fading afterimage. The two phases agree through the shared
 * {@link #FELLED_SETTLE_TICKS} contract.
 */
public record StructureFlightPayload(long stormId, Kind kind, double x, double y, double z, double groundY,
                                     int durationTicks, float tumbleRate,
                                     double landX, double landY, double landZ, float spinSign,
                                     boolean fellOnLanding,
                                     List<Block> blocks)
        implements CustomPacketPayload {

    /**
     * How long the drawn settle from the landing pose to its resting felled pose takes, in ticks. A
     * CONTRACT, not a knob: the server begins placing the real trunk this many ticks after the impact
     * tick, and the client finishes its settle at exactly the same tick, so the placed trunk arrives as
     * the drawn body stops - never before, never after, on every client.
     */
    public static final int FELLED_SETTLE_TICKS = 14;

    /** What the body does with its flight. */
    public enum Kind {
        /** A building: hauled up, carried around the funnel, and shed from the outside in. */
        BUILDING,
        /** A tree: pulled up and thrown, landing where the server says it lands. */
        THROWN;

        static Kind byId(int id) {
            return id == THROWN.ordinal() ? THROWN : BUILDING;
        }
    }

    /** One block of the lifted body: its offset from the body's centre, and what it is. */
    public record Block(int dx, int dy, int dz, int stateId) {
    }

    /** Hard cap on one body, so a malformed or hostile packet cannot allocate a warehouse. */
    private static final int MAX_BLOCKS = 4096;

    public static final Type<StructureFlightPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "structure_flight"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StructureFlightPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf),
            StructureFlightPayload::read
    );

    public StructureFlightPayload {
        kind = kind == null ? Kind.BUILDING : kind;
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeLong(stormId);
        buf.writeByte(kind.ordinal());
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeDouble(groundY);
        buf.writeVarInt(durationTicks);
        buf.writeFloat(tumbleRate);
        buf.writeDouble(landX);
        buf.writeDouble(landY);
        buf.writeDouble(landZ);
        buf.writeFloat(spinSign);
        buf.writeBoolean(fellOnLanding);
        buf.writeVarInt(blocks.size());
        for (Block block : blocks) {
            buf.writeVarInt(block.dx());
            buf.writeVarInt(block.dy());
            buf.writeVarInt(block.dz());
            buf.writeVarInt(block.stateId());
        }
    }

    private static StructureFlightPayload read(RegistryFriendlyByteBuf buf) {
        long stormId = buf.readLong();
        Kind kind = Kind.byId(buf.readUnsignedByte());
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        double groundY = buf.readDouble();
        int duration = buf.readVarInt();
        float tumble = buf.readFloat();
        double landX = buf.readDouble();
        double landY = buf.readDouble();
        double landZ = buf.readDouble();
        float spinSign = buf.readFloat();
        boolean fell = buf.readBoolean();
        int size = Math.max(0, Math.min(buf.readVarInt(), MAX_BLOCKS));
        List<Block> blocks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            blocks.add(new Block(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        }
        return new StructureFlightPayload(stormId, kind, x, y, z, groundY, duration, tumble,
                landX, landY, landZ, spinSign, fell, blocks);
    }
}
