package com.imbet.client;

import com.imbet.network.PhotoAimPayload;
import com.imbet.photo.PhotoTarget;

import java.util.List;

/**
 * The last thing the server said was worth photographing here.
 *
 * <p>Kept as the payload rather than as a parsed reading, because the reading is a frame-by-frame question: the
 * candidates and the band are the server's, and where the camera is pointing is the client's, so the two are only
 * combined in the viewfinder, every frame, on the numbers as they are now. The store is what makes that possible
 * without a packet per frame.
 *
 * <p>A world change clears it. Distances measured in the last world mean nothing in this one, and a reticle left
 * over from a level the player has left would be pointing at a storm that is not there.
 */
public final class PhotoAimStore {
    private static PhotoAimPayload current;

    private PhotoAimStore() {
    }

    /** Takes a payload from the server, which is the only writer. */
    public static void accept(PhotoAimPayload payload) {
        current = payload;
    }

    /** The last payload, or null before the first one arrives. */
    public static PhotoAimPayload current() {
        return current;
    }

    /** The candidates in it, or an empty list. */
    public static List<PhotoTarget> targets() {
        PhotoAimPayload payload = current;
        return payload == null ? List.of() : payload.targets();
    }

    /** Forgets everything, which is what a world change means. */
    public static void clear() {
        current = null;
    }
}
