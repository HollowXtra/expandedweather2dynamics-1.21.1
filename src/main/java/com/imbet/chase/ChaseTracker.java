package com.imbet.chase;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One player's open encounters, keyed by vortex.
 *
 * <p>A tracker is the state machine between "these vortices were near this player this pass" and the finished
 * records a ledger counts. It exists because an encounter is not a tick: a chase starts when a storm worth
 * logging comes into range, gets better and worse while the player stays with it, and ends when the storm
 * leaves or dies - and only the tracker knows which of those happened, because only it remembers what the
 * storm was doing a minute ago.
 *
 * <p>Nothing in here reads the world or the configuration. The caller decides what is worth observing (the
 * radius, the strength floor, whether this player counts at all); the tracker decides what those
 * observations mean. That split is what makes the arithmetic checkable outside a running game.
 */
public final class ChaseTracker {
    private final Map<String, ChaseDraft> open = new LinkedHashMap<>();
    private final List<ChaseRecord> finished = new ArrayList<>();

    /**
     * Folds one observation of one vortex into that vortex's encounter, opening it if this is the first
     * time it has been seen. The caller has already applied its own gates - this only records.
     */
    public void observe(String key, String label, double stormMph, double feltMph, double distanceBlocks,
                        double damageWidthBlocks, long tick, int spanTicks) {
        if (key == null) {
            return;
        }
        ChaseDraft draft = open.computeIfAbsent(key, newKey -> new ChaseDraft(newKey, tick));
        draft.observe(label, stormMph, feltMph, distanceBlocks, damageWidthBlocks, tick, spanTicks);
    }

    /** Whether this vortex currently has an encounter open, which is what a continuing chase asks. */
    public boolean tracking(String key) {
        return key != null && open.containsKey(key);
    }

    /** How many encounters are open, for a status line. */
    public int openCount() {
        return open.size();
    }

    /**
     * Closes every encounter not observed this pass, and every one that has been silent for longer than the
     * grace window. The grace window is what stops a storm that leaves the radius for a moment - a satellite
     * circulation that dips out of the snapshot list, a tornado that weakens below the log's own floor - from
     * being filed as two chases.
     */
    public void closeUnseen(Set<String> seenThisPass, long tick, long graceTicks) {
        List<String> ending = new ArrayList<>();
        for (Map.Entry<String, ChaseDraft> entry : open.entrySet()) {
            if (seenThisPass.contains(entry.getKey())) {
                continue;
            }
            if (entry.getValue().stale(tick, graceTicks)) {
                ending.add(entry.getKey());
            }
        }
        for (String key : ending) {
            finished.add(open.remove(key).finish());
        }
    }

    /** Closes every open encounter, for a player leaving the level or the world reloading. */
    public void closeAll() {
        for (ChaseDraft draft : open.values()) {
            finished.add(draft.finish());
        }
        open.clear();
    }

    /** Takes the records finished since the last drain, in the order they ended. */
    public List<ChaseRecord> drainFinished() {
        if (finished.isEmpty()) {
            return List.of();
        }
        List<ChaseRecord> drained = new ArrayList<>(finished);
        finished.clear();
        return drained;
    }

    /** The keys of everything currently open, for a caller that needs to report what it is tracking. */
    public Collection<String> openKeys() {
        return List.copyOf(open.keySet());
    }
}
