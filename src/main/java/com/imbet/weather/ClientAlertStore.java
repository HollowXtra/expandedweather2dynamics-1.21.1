package com.imbet.weather;

import com.imbet.Config;
import com.imbet.network.AlertBannerPayload;

/**
 * The alert banner the client is currently showing.
 *
 * <p>A siren re-broadcasts an alert for as long as it is in force, so this store separates the two
 * clocks that matter: when a given alert <em>started</em> (which drives the slide-in animation and
 * must not restart on every refresh) and when it was last <em>updated</em> (which drives the fade,
 * so the banner holds while the hazard continues and disappears when it really is over).
 */
public final class ClientAlertStore {
    private static final long DEFAULT_DURATION_MILLIS = 8_000L;

    private static Alert content;
    private static long startedAtMillis;
    private static long updatedAtMillis;

    private ClientAlertStore() {
    }

    public record Alert(
            String productCode,
            AlertLevel level,
            String label,
            String source,
            int distanceBlocks,
            int projectedBlocks,
            int stationDistanceBlocks,
            String stationKind
    ) {
        /** True when a siren or radio in the world broadcast this, rather than a global alert. */
        public boolean fromStation() {
            return stationDistanceBlocks >= 0;
        }

        /** The word the banner prints for the covering station, never blank. */
        public String stationLabel() {
            return stationKind == null || stationKind.isBlank() ? "STATION" : stationKind;
        }

        /** Identity of the alert itself, so a refresh of the same alert is not a new alert. */
        public String key() {
            return productCode + "|" + level.name() + "|" + label + "|" + source;
        }
    }

    public static void accept(AlertBannerPayload payload) {
        if (payload == null) {
            return;
        }
        Alert incoming = new Alert(
                payload.productCode(),
                AlertLevel.byName(payload.level()),
                payload.label(),
                payload.source(),
                payload.distanceBlocks(),
                payload.projectedBlocks(),
                payload.stationDistanceBlocks(),
                payload.stationKind()
        );
        if (incoming.level() == AlertLevel.NONE) {
            return;
        }
        long now = System.currentTimeMillis();
        if (content == null || !content.key().equals(incoming.key()) || now - updatedAtMillis > durationMillis()) {
            startedAtMillis = now;
        }
        content = incoming;
        updatedAtMillis = now;
    }

    /** The banner to draw, or null when there is nothing live to show. */
    public static Alert active() {
        if (content == null || System.currentTimeMillis() - updatedAtMillis > durationMillis()) {
            content = null;
            return null;
        }
        return content;
    }

    /** Milliseconds since this alert first arrived; drives the slide-in. */
    public static long ageMillis() {
        return Math.max(0L, System.currentTimeMillis() - startedAtMillis);
    }

    /** Milliseconds until it fades out; drives the fade. */
    public static long remainingMillis() {
        return Math.max(0L, updatedAtMillis + durationMillis() - System.currentTimeMillis());
    }

    public static void clear() {
        content = null;
    }

    private static long durationMillis() {
        return Math.max(1, Config.ALERTS_BANNER_DURATION_TICKS.get()) * 50L;
    }
}
