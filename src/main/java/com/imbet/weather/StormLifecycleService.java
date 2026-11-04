package com.imbet.weather;

import com.imbet.Config;
import weather2.weathersystem.storm.StormObject;

/**
 * The mod's own storm structure: the lifecycle a storm actually climbs, rather than the one Weather2
 * hands it on a timer.
 *
 * <p>Weather2 advances a storm a rung whenever its own accumulation timer fills, with no dwell and no
 * relationship to the air or to how long the storm has actually existed. A fresh cell could therefore
 * climb the whole ladder in a couple of minutes and, before this existed, be wearing STAGE4 while its
 * funnel was still a thin rope - and because the windfield read its rung's band, that label was worth
 * real force. The mod already decides <em>whether</em> a storm may become tornado-capable; this decides
 * <em>how fast</em> any storm is allowed to change rung, which is the missing half of taking storm
 * structure away from Weather2.
 *
 * <p>It is deliberately thin and honest about it. It does not re-implement Weather2's staging - the
 * bookkeeping that {@code stageNext} performs (rolling the intensity ceiling, resetting the stage
 * gauge) is Weather2's and is left where it is. What it takes over is the <em>pace</em>: a storm that
 * has just changed rung is held on that rung for a configured dwell before it may climb again, so the
 * ladder is a progression over minutes rather than a countdown over seconds. A storm that the command,
 * a weather machine, a player or another feature is driving is exempt, as is anything that is not an
 * ordinary land cell, so the pace applies exactly where Weather2's own timer used to and nowhere else.
 */
public final class StormLifecycleService {

    /**
     * Our own names for the structure a storm is wearing. The boundaries are Weather2's stage numbers -
     * the mod still has to speak Weather2's language to draw and to damage - but the phases are the ones
     * this mod reasons about, and they are what the pacing below is written against.
     */
    public enum Phase {
        /** A cell that has not organised yet: no thunder, no rotation, nothing to pace. */
        DEVELOPING,
        /** A thunderstorm: the first rung that is worth holding a storm on. */
        ORGANIZED,
        /** Hail or high wind: a severe storm, still not tornadic. */
        SEVERE,
        /** Tornadogenesis or heavier: a circulation is being handed its band. */
        TORNADIC,
        /** Past its peak: Weather2 walks it down and the pace deliberately lets it. */
        DECAYING
    }

    private StormLifecycleService() {
    }

    /** The game time a storm lives at, or {@link Long#MIN_VALUE} when it cannot be read. */
    public static long gameTimeOf(StormObject storm) {
        if (storm == null || storm.manager == null || storm.manager.getWorld() == null) {
            return Long.MIN_VALUE;
        }
        return storm.manager.getWorld().getGameTime();
    }

    /** Which phase of our own lifecycle a storm is currently in. */
    public static Phase phaseOf(StormObject storm) {
        if (storm == null) {
            return Phase.DEVELOPING;
        }
        if (storm.hasStormPeaked) {
            return Phase.DECAYING;
        }
        int stage = storm.levelCurIntensityStage;
        if (stage >= StormObject.STATE_FORMING) {
            return Phase.TORNADIC;
        }
        if (stage >= StormObject.STATE_HAIL) {
            return Phase.SEVERE;
        }
        if (stage >= StormObject.STATE_THUNDER) {
            return Phase.ORGANIZED;
        }
        return Phase.DEVELOPING;
    }

    /**
     * Whether Weather2's request to advance this storm a rung should be refused right now.
     *
     * <p>The caller owns the clock: {@code lastAdvanceTick} is the game time the storm last actually
     * changed rung (or {@link Long#MIN_VALUE} for one that never has), which is held on the storm by the
     * mixin rather than serialized, because a reload resetting the dwell is harmless - a reloaded storm
     * simply may climb again on its next tick.
     */
    public static boolean shouldBlockStageAdvance(StormObject storm, long gameTime, long lastAdvanceTick) {
        if (!Config.STORM_LIFECYCLE_ENABLED.get()) {
            return false;
        }
        if (storm == null || gameTime == Long.MIN_VALUE || !isPaced(storm)) {
            return false;
        }
        // The rungs below a thunderstorm are the air noticing a storm at all. Pacing those would slow
        // the whole world's weather to make a point, so the dwell starts once a storm is worth holding.
        if (storm.levelCurIntensityStage < StormObject.STATE_THUNDER) {
            return false;
        }
        if (lastAdvanceTick == Long.MIN_VALUE) {
            return false;
        }
        long minTicks = Math.max(1L, Math.round(Config.STORM_LIFECYCLE_MIN_STAGE_SECONDS.get() * 20.0D));
        return gameTime - lastAdvanceTick < minTicks;
    }

    /**
     * Whether a storm is an ordinary land cell this pace should govern. Everything the mod or a player
     * commands - weather machines, pets, player-controlled storms, waterspouts, cyclones and the helper
     * cells a squall line or hurricane spawns - is exempt, so its staging is exactly as it was.
     */
    private static boolean isPaced(StormObject storm) {
        return !storm.weatherMachineControlled
                && !storm.isPlayerControlled()
                && !storm.isPet()
                && !storm.attrib_waterSpout
                && storm.stormType == StormObject.TYPE_LAND
                && !DynamicStormController.isNativeCyclone(storm)
                && !AddonWeatherEventService.isSquallLineHelperStorm(storm)
                && !AddonWeatherEventService.isHurricaneHelperStorm(storm);
    }
}
