package com.imbet.village;

import java.util.ArrayList;
import java.util.List;

/**
 * What a surveyor writes on the sign it plants where it worked.
 *
 * <p>A rating that only exists on a survey screen is a rating nobody standing in the damage will read, so
 * the surveyor leaves the short version on the ground: what it was, how hard it blew, how sure the reading
 * is, and which track it belongs to. Four lines - a sign has four - and each one short enough to actually
 * fit on one, which is the whole reason this is arithmetic instead of a string built at the call site.
 */
public final class SurveyMarker {
    /** How many characters a sign line shows before it is clipped. */
    public static final int LINE_LENGTH = 15;

    /** A sign has four lines, and this is the same number the block does. */
    public static final int LINES = 4;

    private SurveyMarker() {
    }

    /** The four lines, in the order they read: what, how hard, how sure, which one. */
    public static List<String> lines(String efRating, int estimatedMph, String confidence, String trackId) {
        List<String> lines = new ArrayList<>(LINES);
        lines.add(fit(text(efRating, "UNRATED") + " TORNADO"));
        lines.add(fit("EST " + Math.max(0, estimatedMph) + " MPH"));
        lines.add(fit("CONF " + text(confidence, "none").toUpperCase(java.util.Locale.ROOT)));
        lines.add(fit(text(trackId, "")));
        return lines;
    }

    /** One line, clipped to what a sign shows. */
    public static String fit(String line) {
        String trimmed = line == null ? "" : line.trim();
        return trimmed.length() <= LINE_LENGTH ? trimmed : trimmed.substring(0, LINE_LENGTH);
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
