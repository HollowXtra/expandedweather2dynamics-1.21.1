package com.imbet.weather;

import com.imbet.Config;
import com.imbet.chase.ChaseScale;
import net.minecraft.util.Mth;
import weather2.weathersystem.storm.StormObject;

/**
 * Player-facing names for Weather2's storm ladder.
 *
 * <p>Weather2's {@code levelCurIntensityStage} is <em>not</em> a tornado scale. Its rungs are
 * NORMAL 0, THUNDER 1, HIGHWIND 2, HAIL 3, FORMING 4, and only then the tornado stages
 * STAGE1..STAGE5 = 5..9. Printing the raw rung ("Stage 5/8") therefore told the player a
 * just-formed, still-organizing tornado was halfway up the ladder, while the wind model - which
 * reads the rung correctly - delivered the ~20 mph a brand new tornado actually has. The number
 * was right and the label was a lie, which is exactly what "why is a stage 5 tornado 20 mph"
 * looks like from the player's seat.
 *
 * <p>Everything the player reads now goes through here: the tornado's own stage
 * (TOR1..TOR5), the genesis phase while it is still forming, and the EF class the live wind has
 * actually earned. Non-tornadic storms keep the plain ladder rung, since for them the rung is
 * the whole story.
 */
public final class StormStageLabel {

    /** Weather2's tornado stages: rung {@code STATE_FORMING + n} is tornado stage n. */
    public static final int TORNADO_STAGES = 5;

    private StormStageLabel() {
    }

    /**
     * The tornado's own stage, e.g. "TOR3", or "FORMING" for a wall cloud that has not produced a
     * condensation funnel yet. Null when the storm is not tornadic at all, so callers can fall
     * back to the plain rung.
     */
    public static String tornadoTag(int stage) {
        if (stage < StormObject.STATE_FORMING) {
            return null;
        }
        if (stage <= StormObject.STATE_FORMING) {
            return "FORMING";
        }
        // Rung STATE_FORMING + 1 (= STATE_STAGE1, rung 5) is tornado stage 1, so the offset is one
        // rung, not one stage: using +1 here printed TOR2 for a brand new tornado.
        int tornadoStage = Mth.clamp(stage - StormObject.STATE_FORMING, 1, TORNADO_STAGES);
        return "TOR" + tornadoStage;
    }

    /**
     * The EF class the given wind actually earns, on the published scale's own boundaries.
     *
     * <p>The bands are EF0 65-85, EF1 86-110, EF2 111-135, EF3 136-165, EF4 166-200 and EF5 over
     * 200 mph, measured as 3-second gusts. Those are the numbers the damage survey, the survey packet
     * and the damage tint in the survey screen already use; this method was the one place carrying the
     * band <em>label</em> one class high, so a 100 mph tornado (published EF1) printed as EF2, a 70 mph
     * one (EF0) printed as EF1, and no label this mod could print ever read EF0. The wind number beside
     * it was right the whole time - the class was the lie.
     *
     * <p>Below 65 there is no EF rating to give, so the label says so instead of printing an EF0 that
     * the wind has not earned: 65 mph is where EF0 begins, and calling a 20 mph reading EF0 was the same
     * lie in the other direction. {@code EFU} is the published scale's own "unknown/undetermined" class,
     * which is exactly what a wind under the bottom rung is. The wind model floors a tornado whose
     * condensation is on the ground at the same 65 mph, so in practice EFU appears for the cases that
     * genuinely have no rating yet: a funnel still aloft, or a weak satellite circulation.
     *
     * <p>Above 250 the label leaves the scale behind instead of overstating what it covers. That is the
     * same threshold the rest of the mod treats as past the top of the ladder, and it has to exist
     * because the wind model can be configured to run past 300 mph, which the published table, whose
     * last class is simply "over 200", has no entry for.
     */
    public static String efClass(double mph) {
        // The bands themselves live in ChaseScale, which is pure: the chase log and the survey's accuracy
        // arithmetic need to rate a wind without compiling against storm objects, and a ladder with two
        // homes is a ladder that drifts. This method is kept because everything in the mod already calls it.
        return ChaseScale.efClass(mph);
    }

    /**
     * Where the genesis timeline is, or null once the funnel has finished forming or the storm never
     * had a timeline. This is what explains a low number instead of letting it contradict the stage:
     * a funnel still reaching for the ground is allowed to be weak, and the word says so. ALOFT is
     * deliberately the aloft half of "is it on the ground yet?" - a funnel with clear air under it has
     * lifted nothing, which is why its wind may read like a strong gust rather than a tornado.
     */
    public static String formingPhase(DynamicStormState state, long gameTime) {
        if (state == null || !Config.GENESIS_ENABLED.get()
                || state.funnelBirthTick() == Long.MIN_VALUE || state.genesisDurationTicks() <= 0) {
            return null;
        }
        float progress = state.genesisProgress(gameTime);
        if (progress >= 1.0F) {
            return null;
        }
        // Ground contact is read from the RENDERER's threshold, not the timeline's touchdown phase, so
        // this word flips on the same frame the bottom of the funnel reaches the surface. A funnel the
        // player can watch touching down says TOUCHDOWN; one with clear air under it says ALOFT, and
        // that - not a guess - is the answer to "is it on the ground?"
        if (TornadoGenesis.visuallyOnTheGround(progress)) {
            return "TOUCHDOWN";
        }
        return TornadoGenesis.phase(progress) == TornadoGenesis.Phase.ORGANIZING ? "FORMING" : "ALOFT";
    }

    /**
     * The stage half of a storm's label: the tornado's own stage plus either its genesis phase (while
     * it is still forming) or the EF class its live wind earns (once it is formed). Callers append the
     * wind and size themselves, as they always have.
     */
    public static String label(StormObject storm, DynamicStormState state, double mph, long gameTime) {
        if (storm == null) {
            return "";
        }
        // A tropical system is not on the tornado ladder at all, so it never wears a TOR tag - its own
        // ladder is the one 1.12.2 named, and the tag reports where on it the storm currently sits
        // (TD1, TD2, TS, then C1..C5 once it is a hurricane).
        String tropical = tropicalTag(storm, mph);
        if (tropical != null) {
            return tropical;
        }
        String tag = tornadoTag(storm.levelCurIntensityStage);
        if (tag == null) {
            return rung(storm.levelCurIntensityStage, storm.levelStormIntensityMax);
        }
        String phase = formingPhase(state, gameTime);
        // While the funnel is still building, the phase is the honest headline - the EF of a
        // half-formed tornado is noise. Once it is formed, the live EF is what the label owes.
        return phase == null ? tag + " " + efClass(mph) : tag + " " + phase;
    }

    /** A satellite circulation is not on the parent's ladder; only its own wind is meaningful. */
    public static String satelliteLabel(double mph) {
        return "SAT " + efClass(mph);
    }

    /**
     * The tropical ladder's own tag for a water storm, or null for anything else. Like the tornado
     * tag, this is read from the storm itself, so it works on the client and in the snapshots that
     * carry only a rung.
     */
    public static String tropicalTag(StormObject storm, double mph) {
        if (!DynamicStormController.isNativeTropicalSystem(storm)) {
            return null;
        }
        String tag = TropicalCyclone.shortTag(storm.levelCurIntensityStage, mph);
        return tag == null ? rung(storm.levelCurIntensityStage, storm.levelStormIntensityMax) : tag;
    }

    private static String rung(int stage, int maxStage) {
        if (stage <= 0) {
            return "";
        }
        return maxStage > 0 ? "Stage " + stage + "/" + maxStage : "Stage " + stage;
    }
}
