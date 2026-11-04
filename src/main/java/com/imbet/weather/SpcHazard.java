package com.imbet.weather;

import java.util.Locale;

/**
 * The three convective hazards an outlook carries, with the official SPC probability ladders and the
 * conditional intensity group (CIG) decision matrices.
 *
 * <p>SPC probabilities are not continuous: they are issued as fixed contour levels (tornado
 * 2/5/10/15/30/45/60 percent, wind 5/15/30/45/60/75/90, hail 5/15/30/45/60), and the categorical
 * outlook is derived from the probability <em>plus</em> the expected intensity - the CIG - rather
 * than from probability alone. The matrix rows below are SPC's published conversion tables, so the
 * same probability can legitimately produce two different categories depending on how intense the
 * storms are expected to be. That is why a 10% tornado area with no CIG is only Slight while a 10%
 * area with CIG 1 is Enhanced.
 *
 * <p>Matrices are indexed by probability level and then by CIG, and hold severity numbers (2 = MRGL
 * through 8 = HYPER) so they can be declared before {@link SpcRisk} is initialised. A CIG level the
 * matrix does not define (SPC prints "N/A" for those cells) repeats the highest defined level for
 * that probability, which keeps the category defined instead of collapsing it.
 *
 * <p>CIG 0-3 is the published SPC table, copied exactly. CIG 4 and CIG 5 are the addon's extension:
 * a forecast that is beyond the significant-severe thresholds the published table tops out at, which
 * is what turns a hatched high-risk day into an extreme or hyper extreme one. Only the top rungs of
 * the tornado and wind ladders carry them, because that is where a genuinely historic outbreak would
 * be issued; hail's extension columns stay at moderate on purpose, since SPC never issues a high risk
 * on hail alone and never will on a figment of one either.
 */
public enum SpcHazard {
    TORNADO("Tornado", "TOR", 0xF06A1B9A, new int[]{2, 5, 10, 15, 30, 45, 60}, 5,
            new int[][]{
                    {2, 2, 3, 3, 3, 4},
                    {3, 3, 4, 4, 5, 5},
                    {3, 4, 4, 4, 5, 6},
                    {4, 4, 5, 5, 6, 7},
                    {4, 5, 6, 6, 7, 7},
                    {4, 5, 6, 6, 7, 8},
                    {4, 6, 6, 6, 8, 8}
            }),
    WIND("Wind", "WND", 0xF03F8F3F, new int[]{5, 15, 30, 45, 60, 75, 90}, 5,
            new int[][]{
                    {2, 2, 3, 3, 4, 4},
                    {3, 3, 4, 4, 5, 5},
                    {3, 4, 4, 4, 5, 6},
                    {4, 4, 5, 6, 7, 7},
                    {4, 5, 6, 6, 7, 8},
                    {4, 5, 6, 6, 7, 8},
                    {4, 5, 6, 6, 8, 8}
            }),
    HAIL("Hail", "HAIL", 0xF0C9C93F, new int[]{5, 15, 30, 45, 60}, 2,
            new int[][]{
                    {2, 2, 3, 3, 4, 4},
                    {3, 3, 4, 4, 5, 5},
                    {3, 4, 4, 4, 5, 5},
                    {4, 4, 5, 5, 5, 5},
                    {4, 5, 5, 5, 5, 5}
            });

    private final String displayName;
    private final String abbreviation;
    private final int barColor;
    private final int[] probabilities;
    private final int maxCig;
    private final int[][] matrix;

    SpcHazard(String displayName, String abbreviation, int barColor, int[] probabilities, int maxCig, int[][] matrix) {
        this.displayName = displayName;
        this.abbreviation = abbreviation;
        this.barColor = barColor;
        this.probabilities = probabilities;
        this.maxCig = maxCig;
        this.matrix = matrix;
    }

    public String displayName() {
        return displayName;
    }

    public String abbreviation() {
        return abbreviation;
    }

    public int barColor() {
        return barColor;
    }

    /** The issued contour levels, ascending. */
    public int[] probabilities() {
        return probabilities;
    }

    public int lowestProbability() {
        return probabilities[0];
    }

    public int highestProbability() {
        return probabilities[probabilities.length - 1];
    }

    /** The probability used to scale a display bar: the top of this hazard's own ladder. */
    public int probabilityBarScale() {
        return highestProbability();
    }

    public int maxCig() {
        return maxCig;
    }

    /**
     * A level on this hazard's probability ladder, e.g. index 1 of the tornado ladder is the 5% that
     * marks the slight-risk floor.
     */
    public int probabilityAt(int index) {
        return probabilities[Math.max(0, Math.min(probabilities.length - 1, index))];
    }

    public int clampedCig(int cig) {
        return Math.max(0, Math.min(maxCig, cig));
    }

    /**
     * The highest category this hazard can express on its own. Tornado and wind reach the top of the
     * ladder, hyper extreme, at the highest rungs with the extension CIGs; hail tops out at moderate -
     * SPC never issues a high risk on hail alone - which is why a high-risk day still shows a real
     * hail probability instead of an empty one.
     */
    public SpcRisk maxExpressible() {
        int best = 0;
        for (int probability : probabilities) {
            for (int cig = 0; cig <= maxCig; cig++) {
                best = Math.max(best, matrixSeverity(probability, cig));
            }
        }
        return SpcRisk.fromSeverity(best);
    }

    /** The categorical severity produced by this probability and conditional intensity group. */
    public int matrixSeverity(int probability, int cig) {
        if (probability < lowestProbability()) {
            return 0;
        }
        int row = 0;
        for (int index = 0; index < probabilities.length; index++) {
            if (probability >= probabilities[index]) {
                row = index;
            }
        }
        int[] cells = matrix[row];
        int column = Math.max(0, Math.min(cells.length - 1, cig));
        return cells[column];
    }

    /** Compact product text, e.g. "TOR 30%" or "TOR 30% CIG2". */
    public String productLine(int probability, int cig) {
        if (probability < lowestProbability()) {
            return abbreviation + " <" + lowestProbability() + "%";
        }
        return String.format(Locale.ROOT, "%s %d%%%s", abbreviation, probability, cig > 0 ? " CIG" + cig : "");
    }

    /**
     * The probability/CIG cells of this hazard that convert to exactly this category.
     *
     * <p>Searching the real matrix instead of hard-coding a table means the product can never
     * contradict itself: whatever cell is picked, the category shown is the one the decision matrix
     * says that probability and intensity produce.
     */
    private int[][] cellsForTier(int tierSeverity) {
        if (tierSeverity < 0 || tierSeverity >= SpcRisk.values().length) {
            return NO_CELLS;
        }
        return CELLS.get(this)[tierSeverity];
    }

    /**
     * The most plausible way to express a category for this hazard: the cell that both converts back
     * to that category and sits closest to the environment's own probability estimate and intensity.
     * Returns {probability, cig}, or {0, 0} when the category cannot be expressed by this hazard.
     */
    public int[] bestCellForTier(SpcRisk tier, double environmentProbabilityPercent, int environmentCig) {
        int[][] cells = cellsForTier(tier.severity());
        if (cells.length == 0) {
            return new int[]{0, 0};
        }
        int[] best = cells[0];
        double bestScore = Double.MAX_VALUE;
        for (int[] cell : cells) {
            double score = Math.abs(cell[0] - environmentProbabilityPercent) + Math.abs(cell[1] - environmentCig) * 9.0D;
            if (score < bestScore) {
                bestScore = score;
                best = cell;
            }
        }
        return new int[]{best[0], best[1]};
    }

    private static final int[][] NO_CELLS = new int[0][];

    /**
     * The cells that convert to each category, precomputed per hazard: CELLS[hazard][severity].
     * Built eagerly so the lookup used by the render thread and the server thread is a plain read of
     * immutable data.
     */
    private static final java.util.EnumMap<SpcHazard, int[][][]> CELLS = buildCells();

    private static java.util.EnumMap<SpcHazard, int[][][]> buildCells() {
        java.util.EnumMap<SpcHazard, int[][][]> cells = new java.util.EnumMap<>(SpcHazard.class);
        for (SpcHazard hazard : values()) {
            int[][][] perTier = new int[SpcRisk.values().length][][];
            for (SpcRisk tier : SpcRisk.values()) {
                java.util.ArrayList<int[]> found = new java.util.ArrayList<>();
                for (int probability : hazard.probabilities) {
                    for (int cig = 0; cig <= hazard.maxCig; cig++) {
                        if (hazard.matrixSeverity(probability, cig) == tier.severity()) {
                            found.add(new int[]{probability, cig});
                        }
                    }
                }
                perTier[tier.severity()] = found.toArray(new int[0][]);
            }
            cells.put(hazard, perTier);
        }
        return cells;
    }
}
