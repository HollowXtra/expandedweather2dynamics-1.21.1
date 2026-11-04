package com.imbet.alert;

/**
 * The seam between a siren's state and the sound it makes.
 *
 * <p>A siren sounds a looping tone from its own block, which is a client-side job: it needs the sound
 * manager, a positioned instance and per-tick volume easing, none of which exist on a dedicated server. But
 * the decision to sound lives in the block entity, which is common code, and a common class must never name
 * a client class - the client classes are not loaded on a dedicated server at all, and a stray reference
 * there is a crash rather than a warning.
 *
 * <p>So the block entity calls through this interface and knows nothing else. The client installs a sink
 * during setup; on a server the field stays null and {@link #update} does nothing, which is exactly right,
 * because a server has no audio to drive.
 */
public final class SirenTonePlayback {
    /** Implemented on the client only, and installed there during client setup. */
    public interface Sink {
        /**
         * Brings one siren's tone in line with its state.
         *
         * <p>Called every client tick while a player is near enough for the block entity to tick, and
         * deliberately idempotent: it starts the loop on the edge and re-targets it afterwards, rather than
         * restarting it, so the sound is continuous for as long as the warning lasts.
         */
        void update(TornadoSirenBlockEntity siren, boolean sounding);
    }

    private static Sink sink;

    private SirenTonePlayback() {
    }

    /** Called once during client setup. */
    public static void install(Sink clientSink) {
        sink = clientSink;
    }

    public static void update(TornadoSirenBlockEntity siren, boolean sounding) {
        if (sink != null) {
            sink.update(siren, sounding);
        }
    }

    /** True when a sink is installed, which is only ever the case on a client. */
    public static boolean available() {
        return sink != null;
    }
}
