package com.imbet.network;

import com.imbet.ExpandedWeather2Dynamics;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * One alert, on its way to a player's banner.
 *
 * <p>It carries the product the player is looking at rather than the storm that caused it: the code,
 * the level, what kind of hazard it is and how it was detected, plus two distances measured from that
 * player. When an alert station broadcast it, the distance from the player to that station comes
 * along too, together with the word to print for it, so the banner can say which siren or radio is
 * covering them - which is the whole point of alerts being station-gated.
 */
public record AlertBannerPayload(
        String productCode,
        String level,
        String label,
        String source,
        int distanceBlocks,
        int projectedBlocks,
        int stationDistanceBlocks,
        String stationKind
) implements CustomPacketPayload {
    public static final Type<AlertBannerPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "alert_banner"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AlertBannerPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf),
            AlertBannerPayload::read
    );

    public AlertBannerPayload {
        productCode = limit(productCode, 16);
        level = limit(level, 32);
        label = limit(label, 48);
        source = limit(source, 24);
        stationKind = limit(stationKind, 16);
        distanceBlocks = Math.max(0, distanceBlocks);
        projectedBlocks = Math.max(0, projectedBlocks);
        stationDistanceBlocks = stationDistanceBlocks < 0 ? -1 : stationDistanceBlocks;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Identity of the alert itself, so a refreshing banner is not restarted every broadcast. */
    public String key() {
        return productCode + "|" + level + "|" + label + "|" + source;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(productCode, 16);
        buf.writeUtf(level, 32);
        buf.writeUtf(label, 48);
        buf.writeUtf(source, 24);
        buf.writeVarInt(distanceBlocks);
        buf.writeVarInt(projectedBlocks);
        buf.writeVarInt(stationDistanceBlocks + 1);
        buf.writeUtf(stationKind, 16);
    }

    private static AlertBannerPayload read(RegistryFriendlyByteBuf buf) {
        return new AlertBannerPayload(
                buf.readUtf(16),
                buf.readUtf(32),
                buf.readUtf(48),
                buf.readUtf(24),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt() - 1,
                buf.readUtf(16)
        );
    }

    private static String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
