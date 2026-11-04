package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * How far the mod lets the weather reach when an LOD renderer is installed.
 *
 * <p>{@code LodViewDistance} answers this question for the client - how far out the terrain is actually drawn -
 * and everything that culls by the render distance reads it. The server has the same problem from the other
 * side: Weather2 spawns its storms inside a box around the players (1024 blocks at the shipped defaults) and
 * forgets a storm once no player is within 1124 of it, so with an LOD mod the sky simply stops over country the
 * player can plainly see. This is that half, and it is deliberately arithmetic and mod-list only: the renderer's
 * exact distance is a client measurement, so here the distance is whichever one the config settles on, and the
 * extension happens only when an LOD mod is present at all.
 *
 * <p>Nothing here reads a world or a player, so it is safe to call from the server tick. Every caller treats a
 * zero as "leave Weather2's own number alone", so a world without an LOD renderer is bit-for-bit what it was -
 * which is the same contract the client probe keeps.
 */
public final class LodStormEnvelope {

    private LodStormEnvelope() {
    }

    /**
     * The widened radius in blocks, or 0 when the box should be left at Weather2's own size.
     *
     * <p>Gated twice: the compat has to be enabled and the storm extension has to be enabled, and an LOD mod has
     * to actually be installed - because widening the box with nothing drawing the distance would just spread the
     * same storms thinner over ground nobody can see. Both LOD mods are recognised by mod id here rather than by
     * their API, because the server side of a dedicated install often has only the id to go on.
     */
    public static int radiusBlocks() {
        try {
            if (!CompatHooks.lodRendererLoaded()) {
                return 0;
            }
            return Config.lodStormDistanceBlocks();
        } catch (Throwable ignored) {
            // A config read must never take the server tick down with it; no answer means no extension.
            return 0;
        }
    }

    /**
     * Weather2's storm spawn radius, widened to the LOD distance when one is drawing.
     *
     * <p>Only ever widened: a world whose own box is already larger than the LOD distance keeps its own, so a
     * server operator who has tuned Weather2 up is never narrowed by installing an LOD mod.
     */
    public static int spawnRadius(int vanilla) {
        int radius = radiusBlocks();
        return radius > vanilla ? radius : vanilla;
    }

    /**
     * Weather2's keep-alive radius, widened with a margin over the spawn radius.
     *
     * <p>Vanilla's pair is 1024 to spawn and 1124 to forget, and the difference is the whole reason a storm
     * spawned at the edge of the box is not deleted the tick after it appears. Widening only the spawn radius
     * would put a storm just outside the keep-alive box and erase it immediately, so the margin is kept
     * proportionally: same hundred blocks, once the distance is large.
     */
    public static int keepAliveRadius(int vanilla) {
        int radius = radiusBlocks();
        return radius > 0 ? Math.max(vanilla, radius + 100) : vanilla;
    }

    /**
     * Whether a position whose chunk is not loaded may still be seeded, because an LOD renderer is drawing it.
     *
     * <p>A loaded chunk is always a yes. An unloaded one is a yes only when the storm box was widened - so the
     * compat is on, an LOD mod is present and a distance was settled on - because that is the only situation in
     * which the ground being unreadable is not also the ground being invisible. It reads no blocks, so it can
     * never force a chunk to load, and every caller is expected to fall back to a biome reading for anything it
     * would otherwise have read from the ground.
     */
    public static boolean seedingUnloadedAllowed(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        if (level.isLoaded(pos)) {
            return true;
        }
        return radiusBlocks() > 0;
    }

    /**
     * The addon's own state-sync range, widened to match.
     *
     * <p>Weather2 sends storm objects to every client in the dimension, so a storm that exists is already drawn;
     * this mod's own state - the circulation, its satellites, its sub-vortices - is pushed only to players within
     * {@code multiplayer.syncRangeBlocks}, which would leave a distant funnel wearing its default sliver. It is
     * widened to the same distance the storm box was, so the storm and its state reach the same player.
     */
    public static int syncRange(int vanilla) {
        return spawnRadius(vanilla);
    }
}
