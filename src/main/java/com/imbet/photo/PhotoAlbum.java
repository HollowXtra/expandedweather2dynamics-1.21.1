package com.imbet.photo;

import java.util.ArrayList;
import java.util.List;

/**
 * One player's album: how many photographs they have filed, what they were worth on average, and the best of
 * them, kept as whole records rather than as numbers.
 *
 * <p>The gallery is the album in the sense a photographer means: ranked, best first, capped. Two consequences
 * fall out of that and both are deliberate. The <b>best shot is the first entry</b> - there is no separate
 * "best" field, because a stored best and a stored gallery are two answers to the same question and a schema
 * that carries both can be loaded with them disagreeing. And the cap trims the <em>ranked</em> list, so a
 * weaker photograph is dropped rather than an older one: an album of your best shots should not forget the
 * monster wedge of three weeks ago to remember this afternoon's gust.
 *
 * <p>Lifetime totals are their own fields rather than sums over the gallery, for the same reason the chase log
 * keeps them: the gallery is capped, so the number of shots cannot be recovered from it once it fills.
 */
public final class PhotoAlbum {
    /**
     * The grade ladder's length, read off the ladder itself rather than counted by hand. A literal here would
     * be a second copy of the enum's size, and the copy is the one that would go stale: a new grade would be
     * counted into the last bucket for ever, silently.
     */
    public static final int GRADE_COUNT = PhotoScore.Grade.values().length;

    private int shotsTaken;
    private int totalSum;
    private final int[] grades = new int[GRADE_COUNT];
    private final List<PhotoShot> gallery = new ArrayList<>();

    /** How many photographs have been filed. */
    public int shotsTaken() {
        return shotsTaken;
    }

    /** The sum of every shot's total, which is what the average is computed from rather than stored. */
    public int totalSum() {
        return totalSum;
    }

    /** The average score of every shot filed, rounded, or 0 before the first one. */
    public int average() {
        return shotsTaken <= 0 ? 0 : (int) Math.round(totalSum / (double) shotsTaken);
    }

    /** The best photograph, which is the gallery's first entry, or null before the first one. */
    public PhotoShot best() {
        return gallery.isEmpty() ? null : gallery.get(0);
    }

    /** The ranked gallery, best first, as it is stored. */
    public List<PhotoShot> gallery() {
        return gallery;
    }

    /** How many photographs earned each grade, in the enum's own order. */
    public int[] grades() {
        return grades.clone();
    }

    /** How many photographs earned one grade. */
    public int gradeCount(PhotoScore.Grade grade) {
        if (grade == null) {
            return 0;
        }
        int index = grade.ordinal();
        return index >= 0 && index < grades.length ? grades[index] : 0;
    }

    /**
     * Files one photograph: the totals, the grade it earned, and its place in the ranking. The gallery is
     * capped here rather than by the caller, so an album can never be handed back over its own size.
     */
    public void note(PhotoShot shot, int gallerySize) {
        if (shot == null) {
            return;
        }
        shotsTaken++;
        totalSum += shot.total();
        int index = shot.grade().ordinal();
        if (index >= 0 && index < grades.length) {
            grades[index]++;
        }
        // Newest first, then ranked: an unstable comparison between two identical shots keeps the one that
        // was taken later, which is what the album's own tie-break order says to do.
        gallery.add(0, shot);
        trim(gallerySize);
    }

    /**
     * Ranks the gallery and drops the weakest entries past the cap. The cap has a floor of one, because an
     * album that can be asked for zero best shots would lose the photograph it exists to keep.
     */
    public void trim(int gallerySize) {
        int cap = Math.max(1, gallerySize);
        gallery.sort(PhotoAlbum::byRank);
        while (gallery.size() > cap) {
            gallery.remove(gallery.size() - 1);
        }
    }

    /** Best first, by the record's own ranking rule. */
    private static int byRank(PhotoShot left, PhotoShot right) {
        if (left.outranks(right)) {
            return -1;
        }
        return right.outranks(left) ? 1 : 0;
    }

    /** Puts a loaded album back exactly as it was written. */
    public void restore(int shotsTaken, int totalSum, int[] grades, List<PhotoShot> gallery) {
        this.shotsTaken = Math.max(0, shotsTaken);
        this.totalSum = totalSum;
        for (int index = 0; index < this.grades.length; index++) {
            this.grades[index] = grades != null && index < grades.length ? Math.max(0, grades[index]) : 0;
        }
        this.gallery.clear();
        if (gallery != null) {
            for (PhotoShot shot : gallery) {
                if (shot != null) {
                    this.gallery.add(shot);
                }
            }
        }
        rankGallery();
    }

    /**
     * Orders a loaded gallery without capping it. Trimming is the loader's job, because only it knows the
     * configured cap: ranking here is what makes the loaded album's first entry its best photograph, which is
     * the invariant the album relies on instead of a stored best field.
     */
    private void rankGallery() {
        gallery.sort(PhotoAlbum::byRank);
    }
}
