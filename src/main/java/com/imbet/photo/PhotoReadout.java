package com.imbet.photo;

import com.imbet.chase.ChaseLogReadout;

import java.util.ArrayList;
import java.util.List;

/**
 * The words the album is printed in: the verdict on a shot you just took, the advice that explains it, and the
 * album itself as the weather computer shows it.
 *
 * <p>Two rules run through all of it. A line never claims more than the record knows - a photograph with no
 * subject in the frame is reported as nothing in the frame rather than as a score of zero, and a shot taken at
 * dawn says dawn. And every hint is attached to the deduction it is about, measured as the fraction of the
 * score that deduction cost, so the advice is ranked by how much it actually mattered: a shot that lost twelve
 * marks to rain and one to its framing is not lectured about its framing.
 *
 * <p>Ages are printed with the chase log's own formatter. Two readouts beside each other on the same screen
 * disagreeing about what "3 days ago" means would be a bug the players find before the harness does.
 */
public final class PhotoReadout {
    /** A deduction smaller than this is rounding rather than advice, and naming it would be noise. */
    public static final double MIN_HINT_LOSS = 0.02D;
    /** How many hints one shot is given. Two is enough to name both halves of a bad frame. */
    public static final int MAX_HINTS = 2;

    private static final String PART_SEPARATOR = "  ";

    private PhotoReadout() {
    }

    /** The verdict line: what it scored, out of what, and what the subject was. */
    public static String shotLine(PhotoShot shot) {
        if (shot == null) {
            return "No photograph taken";
        }
        return shot.grade().label() + " " + shot.total() + "/" + PhotoScore.MAX + " - " + subjectLine(shot);
    }

    /** The subject as the album names it: its class, what it is called, and how far away it was. */
    public static String subjectLine(PhotoShot shot) {
        String label = shot.targetLabel() == null || shot.targetLabel().isBlank() ? "Storm" : shot.targetLabel();
        return shot.subjectClass() + " " + label + " at " + ChaseLogReadout.round(shot.distanceBlocks()) + " blocks";
    }

    /** The parts, laid out in the order the score adds them, so the line can be checked against the total. */
    public static String partsLine(PhotoShot shot) {
        if (shot == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        text.append("framing ").append(shot.framing()).append("/").append(PhotoScore.FRAMING_MAX);
        text.append(PART_SEPARATOR).append("proximity ").append(shot.proximity()).append("/").append(PhotoScore.PROXIMITY_MAX);
        text.append(PART_SEPARATOR).append("subject ").append(shot.subject()).append("/").append(PhotoScore.SUBJECT_MAX);
        text.append(PART_SEPARATOR).append("structure ").append(shot.structure()).append("/").append(PhotoScore.STRUCTURE_MAX);
        text.append(PART_SEPARATOR).append("atmosphere ").append(shot.atmosphere()).append("/").append(PhotoScore.ATMOSPHERE_MAX);
        text.append(PART_SEPARATOR).append("depth ").append(shot.depth()).append("/").append(PhotoScore.DEPTH_MAX);
        return text.toString();
    }

    /** One hint and the fraction of the score the thing it is about cost. */
    private record Hint(double loss, String text) {
    }

    /**
     * What cost the shot its marks, largest deduction first, capped. A photograph that lost nothing is given
     * no advice at all, which is the check that keeps this method honest: the advice method is not allowed to
     * invent a criticism.
     */
    public static List<String> adviceLines(PhotoShot shot) {
        if (shot == null) {
            return List.of();
        }
        if (shot.insideCore()) {
            // Nothing else about this frame is worth saying: there is no photograph here at all.
            return List.of("You were inside the tornado: the lens is full of dust, and nothing outside it is in frame.");
        }
        List<Hint> hints = new ArrayList<>();
        double visibilityLoss = 1.0D - PhotoScore.visibilityFraction(shot.occlusionFraction());
        addHint(hints, visibilityLoss, "The funnel was behind something - climb, or move, for a clear line of sight.");
        addHint(hints, partLoss(shot.structure(), PhotoScore.STRUCTURE_MAX),
                "No wall cloud in frame - back up until the structure above the funnel is in shot.");
        addHint(hints, partLoss(shot.proximity(), PhotoScore.PROXIMITY_MAX), distanceHint(shot));
        addHint(hints, partLoss(shot.framing(), PhotoScore.FRAMING_MAX), framingHint(shot));
        if (shot.raining()) {
            // The rain exposure is a fixed deduction, so its size is exactly what it takes off the total.
            addHint(hints, 1.0D - PhotoScore.RAIN_EXPOSURE, "Rain between you and the storm flattens the light.");
        }
        addHint(hints, partLoss(shot.atmosphere(), PhotoScore.ATMOSPHERE_MAX),
                shot.lightning() ? "A bolt helps, but the light was flat - dawn and dusk are worth more."
                        : "Midday light is the flattest there is; dawn, dusk or a bolt are worth more.");
        addHint(hints, partLoss(shot.depth(), PhotoScore.DEPTH_MAX),
                "Nothing in the foreground - a tree line or a fence in shot gives the storm its scale.");
        hints.sort((left, right) -> Double.compare(right.loss(), left.loss()));
        List<String> advice = new ArrayList<>();
        for (Hint hint : hints) {
            if (advice.size() >= MAX_HINTS) {
                break;
            }
            advice.add(hint.text());
        }
        return advice;
    }

    /** How much of the total one part's shortfall cost, as a fraction. */
    public static double partLoss(int earned, int max) {
        return Math.max(0.0D, (max - earned) / (double) PhotoScore.MAX);
    }

    /** What to say about a distance, which is a different thing either side of the sweet spot. */
    private static String distanceHint(PhotoShot shot) {
        return shot.occlusionFraction() >= PhotoScore.VISIBLE_MAX
                ? "Close the distance as well as the line of sight: a hidden funnel is a far one for this camera."
                : "Too far for detail - close in, or let the storm come to you.";
    }

    /** What to say about a composition, which differs by which side of the comfortable band it fell on. */
    private static String framingHint(PhotoShot shot) {
        if (shot.offAxisFraction() < PhotoScore.DEAD_CENTRE) {
            return "Dead centre: give the storm a third of the frame and it reads as a photograph.";
        }
        return "The subject was running out of the edge of the frame - aim closer to it.";
    }

    private static void addHint(List<Hint> hints, double loss, String text) {
        if (loss >= MIN_HINT_LOSS) {
            hints.add(new Hint(loss, text));
        }
    }

    /** The album as the weather computer prints it: what has been filed, then the best few, best first. */
    public static List<String> lines(PhotoAlbum album, int maxGallery, long nowTick) {
        List<String> lines = new ArrayList<>();
        if (album == null || album.shotsTaken() <= 0) {
            lines.add("Storm photographs: none filed - hold the camera and right-click a storm.");
            return lines;
        }
        PhotoShot best = album.best();
        lines.add("Storm photographs: " + album.shotsTaken() + " filed, average " + album.average()
                + ", best " + best.total() + "/" + PhotoScore.MAX);
        lines.add("Best: " + shotLine(best) + ", " + ChaseLogReadout.ago(nowTick - best.gameTime()));
        int shown = Math.min(Math.max(0, maxGallery), album.gallery().size());
        for (int index = 0; index < shown; index++) {
            PhotoShot shot = album.gallery().get(index);
            lines.add("  " + (index + 1) + ". " + shot.grade().label() + " " + shot.total() + "/" + PhotoScore.MAX
                    + "  " + subjectLine(shot) + "  " + ChaseLogReadout.ago(nowTick - shot.gameTime()));
        }
        if (shown < album.gallery().size()) {
            lines.add("  ... and " + (album.gallery().size() - shown) + " more in the album");
        }
        return lines;
    }

    /** The shutter's own line, for the chat message: what was filed and what it was worth. */
    public static String filedLine(PhotoShot shot) {
        return "Photo filed: " + shotLine(shot);
    }
}
