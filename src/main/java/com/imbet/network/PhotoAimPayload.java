package com.imbet.network;

import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.photo.PhotoTarget;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * What a camera has to point at, on its way to a viewfinder.
 *
 * <p>The list is the server's, not the client's: the server owns the circulations - it publishes the snapshot
 * stream, and it is the side that will score the photograph - so it also owns which of them a camera would
 * accept. A viewfinder that worked out its own candidates would eventually draw a reticle around a storm the
 * shutter would refuse, and a viewfinder that lies is worse than no viewfinder.
 *
 * <p>The four settings the reading depends on travel with it rather than being read from the client's own copy of
 * the configuration, because on a server those can differ: the distance band a player is being marked against is
 * the server's band, and it is the server's band the shot will be scored with.
 *
 * @param targets            the circulations a camera here would consider, in no particular order
 * @param maxDistanceBlocks  how far the camera photographs, which is what the viewfinder says when it is empty
 * @param sweetSpotMinBlocks the near end of the band that earns full distance marks
 * @param sweetSpotMaxBlocks the far end of it
 * @param minSubjectMph      the floor below which a circulation is not worth photographing
 */
public record PhotoAimPayload(
        List<PhotoTarget> targets,
        double maxDistanceBlocks,
        double sweetSpotMinBlocks,
        double sweetSpotMaxBlocks,
        double minSubjectMph
) implements CustomPacketPayload {
    public static final Type<PhotoAimPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            ExpandedWeather2Dynamics.MODID, "photo_aim"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PhotoAimPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf),
            PhotoAimPayload::read
    );

    public PhotoAimPayload {
        List<PhotoTarget> copy = new ArrayList<>();
        if (targets != null) {
            for (PhotoTarget target : targets) {
                if (target != null && target.present() && copy.size() < PhotoTarget.MAX_SENT) {
                    copy.add(target);
                }
            }
        }
        targets = List.copyOf(copy);
        maxDistanceBlocks = Math.max(8.0D, maxDistanceBlocks);
        sweetSpotMinBlocks = Math.max(1.0D, sweetSpotMinBlocks);
        sweetSpotMaxBlocks = Math.max(sweetSpotMinBlocks, sweetSpotMaxBlocks);
        minSubjectMph = Math.max(0.0D, minSubjectMph);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Written and read in exactly the same order, three doubles of position first and then the six readings per
     * target, because a wire whose halves disagree about order is a wire that reads a storm's wind as its width
     * and never says so.
     */
    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(targets.size());
        for (PhotoTarget target : targets) {
            buf.writeUtf(target.key(), PhotoTarget.MAX_KEY_LENGTH);
            buf.writeUtf(target.label(), PhotoTarget.MAX_LABEL_LENGTH);
            buf.writeDouble(target.x());
            buf.writeDouble(target.y());
            buf.writeDouble(target.z());
            buf.writeFloat((float) target.stormMph());
            buf.writeFloat((float) target.visibleFunnelWidth());
            buf.writeFloat((float) target.debrisSwathBlocks());
            buf.writeFloat((float) target.coreRadius());
            buf.writeFloat((float) target.structureStrength());
        }
        buf.writeDouble(maxDistanceBlocks);
        buf.writeDouble(sweetSpotMinBlocks);
        buf.writeDouble(sweetSpotMaxBlocks);
        buf.writeDouble(minSubjectMph);
    }

    private static PhotoAimPayload read(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<PhotoTarget> targets = new ArrayList<>(Math.min(Math.max(count, 0), PhotoTarget.MAX_SENT));
        for (int index = 0; index < count; index++) {
            String key = buf.readUtf(PhotoTarget.MAX_KEY_LENGTH);
            String label = buf.readUtf(PhotoTarget.MAX_LABEL_LENGTH);
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            float mph = buf.readFloat();
            float width = buf.readFloat();
            float swath = buf.readFloat();
            float core = buf.readFloat();
            float structure = buf.readFloat();
            // A sender that overspends the cap is still read to the end of its own record, so the rest of the
            // packet stays aligned, but only the first MAX_SENT are kept - the same rule the constructor applies.
            if (targets.size() < PhotoTarget.MAX_SENT) {
                targets.add(new PhotoTarget(key, label, x, y, z, mph, width, swath, core, structure));
            }
        }
        return new PhotoAimPayload(targets, buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble());
    }
}
