package com.imbet.weather;

import com.imbet.Config;
import com.imbet.network.AlertBannerPayload;
import com.imbet.network.NetworkSafety;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Delivers one alert to one player.
 *
 * <p>The alert travels as a {@link AlertBannerPayload} and is drawn by the client banner, so this is
 * the only place an alert is ever "said". Chat output is opt-in ({@code alerts.chatLog}) and the faux
 * tones are opt-in and never the real EAS tones, so the default experience is a silent banner.
 *
 * <p>Whether the tones play is the station's decision, not this class's. A siren rings out of its own
 * block and asks for the banner silently, because otherwise standing next to your own siren would
 * double every alert; a radio asks for the tones, because it exists to be heard indoors.
 */
public final class AlertBroadcaster {
    private AlertBroadcaster() {
    }

    /**
     * Sends a banner to a player.
     *
     * @param stationDistanceBlocks distance to the station that covers this player, or a negative
     *                              value when the alert did not come from a station at all.
     * @param stationKind           the word the banner prints for that station, e.g. "SIREN".
     * @param pingEars              whether this delivery should also play the faux alert tones.
     * @return true when the packet was accepted for delivery.
     */
    public static boolean send(ServerPlayer player, AlertService.AlertResult result,
                              int stationDistanceBlocks, String stationKind, boolean pingEars) {
        if (player == null || result == null || result.level() == AlertLevel.NONE) {
            return false;
        }
        double distance = player.position().distanceTo(result.position());
        double leadDistance = player.position().distanceTo(result.projectedPosition());
        AlertBannerPayload payload = new AlertBannerPayload(
                result.productCode(),
                result.level().name(),
                result.label(),
                result.source(),
                (int) Math.round(distance),
                (int) Math.round(leadDistance),
                stationDistanceBlocks,
                stationKind
        );
        if (!NetworkSafety.sendToPlayer(player, payload)) {
            return false;
        }
        if (Config.ALERTS_CHAT_LOG.getAsBoolean()) {
            logToChat(player, result, (int) Math.round(distance), (int) Math.round(leadDistance),
                    stationDistanceBlocks, stationKind);
        }
        if (pingEars && result.level().activatesEas() && Config.ALERTS_FAUX_SOUND.get()) {
            player.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.BLOCKS, 0.8F, result.level().isWarning() ? 0.55F : 0.82F);
            player.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 0.7F, result.level().isWarning() ? 1.0F : 1.25F);
        }
        return true;
    }

    /** The broadcast record, when a player has asked to keep alerts in their chat log too. */
    private static void logToChat(ServerPlayer player, AlertService.AlertResult result, int distance,
                                  int leadDistance, int stationDistanceBlocks, String stationKind) {
        String station = stationDistanceBlocks >= 0
                ? ", sounded by " + (stationKind == null || stationKind.isBlank() ? "station" : stationKind.toLowerCase(java.util.Locale.ROOT))
                        + " " + stationDistanceBlocks + " blocks away"
                : "";
        player.sendSystemMessage(Component.literal("[Expanded Weather Fictional In-Game Alert] " + result.productCode() + " / " + result.level().label() + ": " + result.label() + " " + result.source() + ", " + distance + " blocks away, projected path " + leadDistance + " blocks away" + station + ".")
                .withStyle(result.level().isWarning() ? ChatFormatting.RED : ChatFormatting.GOLD));
    }
}
