package com.imbet.photo;

/**
 * One circulation as a camera can see it, with no world in it: where it is, what it is called, and the readings
 * the score would use on it.
 *
 * <p>This is what travels to a viewfinder. The server owns the list of circulations - it is the side that
 * publishes the snapshot stream and the side that will score the shot - so the client is handed the candidates
 * rather than working them out again for itself. A viewfinder built on a second, private list of storms would
 * eventually point at a storm the shutter would not photograph, which is the one failure this feature cannot
 * afford.
 *
 * @param key               the subject's stable identity, which is what a reticle is held against across packets
 * @param label             what the circulation is called, e.g. "EF3 Tornado"
 * @param x                 the circulation's own position, which is its foot rather than its top
 * @param y                 as above
 * @param z                 as above
 * @param stormMph          the wind that earns its EF class
 * @param visibleFunnelWidth how wide the funnel looks, in blocks, which is what the frame is measured against
 * @param debrisSwathBlocks the damage swath it is cutting, in blocks
 * @param coreRadius        the radius of its core, which is where a photograph becomes a whiteout
 * @param structureStrength how much mesocyclone and wall cloud it has above it, from 0 to 1
 */
public record PhotoTarget(
        String key,
        String label,
        double x,
        double y,
        double z,
        double stormMph,
        double visibleFunnelWidth,
        double debrisSwathBlocks,
        double coreRadius,
        double structureStrength
) {
    /**
     * How many targets one packet may carry. The count matters because the client re-picks between them every
     * frame: a viewfinder needs the candidates it could point at, and a player standing between two tornadoes
     * needs both, but an unbounded list would let one outbreak put hundreds of records on the wire five times a
     * second.
     */
    public static final int MAX_SENT = 8;

    /** The longest a label or key may be, so a packet cannot be grown by a name. */
    public static final int MAX_KEY_LENGTH = 48;
    public static final int MAX_LABEL_LENGTH = 64;

    public PhotoTarget {
        key = limit(key, MAX_KEY_LENGTH);
        label = limit(label, MAX_LABEL_LENGTH);
        stormMph = Math.max(0.0D, stormMph);
        visibleFunnelWidth = Math.max(0.0D, visibleFunnelWidth);
        debrisSwathBlocks = Math.max(0.0D, debrisSwathBlocks);
        coreRadius = Math.max(0.0D, coreRadius);
        structureStrength = Math.max(0.0D, Math.min(1.0D, structureStrength));
    }

    /** Whether this target has an identity worth holding a reticle against. */
    public boolean present() {
        return key != null && !key.isBlank();
    }

    private static String limit(String value, int length) {
        if (value == null) {
            return "";
        }
        return value.length() <= length ? value : value.substring(0, length);
    }
}
