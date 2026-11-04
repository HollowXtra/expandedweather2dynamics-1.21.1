package com.imbet.weather;

import java.util.ArrayList;
import java.util.List;

/**
 * The forecast display's plate polygons, as arithmetic: where a world area lands on the plate, what is
 * left of it once the plate's edge has cut it, and how its inside is filled, hatched and outlined.
 *
 * <p>Everything here turns on one distinction the display used to get wrong. A <em>point</em> that is off
 * the plate has to be brought back to the plate's edge, because that is what an icon means: this storm is
 * off the scope, pinned at the rim. A <em>shape</em> that is off the plate must instead be cut by the edge,
 * because moving each of its corners onto the rim does not shorten it at all - it ties those corners into
 * the rim and leaves a different shape entirely. An outlook area five times the plate's width became a
 * blob hugging the border, every category larger than the plate landed on the same border, and a fan drawn
 * from the centroid of those tied corners produced the long straight slivers that read as flat polygons.
 * That is the difference between {@link #clip} here and the clamp the icons still use.
 *
 * <p>Nothing Minecraft here, deliberately: the claims worth making about it - that a clipped polygon covers
 * what a masked one would, that ear clipping's triangles tile the polygon exactly once and never overlap,
 * that hatching never leaves the shape - are then measured in a harness instead of argued about.
 */
public final class ForecastPolygonGeometry {
    private ForecastPolygonGeometry() {
    }

    /** A point on the plate, in plate units, with the plate's centre at the origin. */
    public record Point(float x, float z) {
    }

    /** Points closer than this are the same point: a tenth of a millimetre of plate. */
    private static final float WELD = 1.0E-4F;

    /** Plate units per block: the display shows {@code range} blocks across three plate units. */
    public static float plateScale(int range) {
        return 3.0F / Math.max(128, range);
    }

    /** Where a world offset from the display lands on the plate, unclamped. */
    public static Point project(double offsetX, double offsetZ, int range) {
        float scale = plateScale(range);
        return new Point((float) (offsetX * scale), (float) (offsetZ * scale));
    }

    /**
     * Cuts a polygon down to the part of it that lies on the plate square.
     *
     * <p>Sutherland-Hodgman against the four edges, which is exact for a clip region as simple as this one:
     * the result is the true intersection of the polygon with the plate. A shape bigger than the display
     * therefore covers the display, a shape crossing one edge loses exactly the part beyond it, and the
     * categories of an outlook stay in their own places instead of all piling onto the rim.
     *
     * <p>The result can have more corners than the input - a rotated ellipse cut by a square comes out with
     * up to sixteen - and can be concave even when the input was convex, which is exactly why the filling
     * below cannot be a fan from the centre.
     */
    public static List<Point> clip(List<Point> polygon, float half) {
        if (polygon == null) {
            return List.of();
        }
        List<Point> current = weld(polygon);
        if (current.size() < 3) {
            return List.of();
        }
        for (int edge = 0; edge < 4; edge++) {
            current = clipEdge(current, edge, half);
            if (current.size() < 3) {
                return List.of();
            }
        }
        current = weld(current);
        return current.size() < 3 ? List.of() : current;
    }

    /**
     * The polygon's interior as triangles, by ear clipping.
     *
     * <p>Returned as index triples into the polygon, so the caller can read the corners back. Ear clipping
     * rather than a fan because an outlook area cut by the plate edge, and the touched area of a warning,
     * are both concave: a fan from the centroid of a concave polygon folds back over itself and paints parts
     * of the shape twice while leaving other parts of it empty, which is what those flat overlapping
     * slivers were.
     *
     * <p>Degenerate input is handled rather than trusted: duplicate and collinear corners are dropped
     * first, and if a pass finds no ear at all - which means the outline crosses itself, as a union of
     * warning swaths can - the rest is emitted as a fan, so a self-intersecting polygon still draws
     * something recognisable instead of nothing.
     */
    public static List<int[]> triangulate(List<Point> polygon) {
        List<int[]> triangles = new ArrayList<>();
        List<Point> points = weld(polygon);
        int count = points.size();
        if (count < 3) {
            return triangles;
        }
        double signed = signedArea(points);
        boolean counterClockwise = signed >= 0.0D;
        List<Integer> remaining = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            remaining.add(index);
        }
        int guard = 0;
        while (remaining.size() > 3 && guard++ < count * count + 16) {
            boolean cut = false;
            for (int slot = 0; slot < remaining.size(); slot++) {
                int previous = remaining.get((slot + remaining.size() - 1) % remaining.size());
                int current = remaining.get(slot);
                int next = remaining.get((slot + 1) % remaining.size());
                if (isEar(points, remaining, previous, current, next, counterClockwise)) {
                    triangles.add(new int[]{previous, current, next});
                    remaining.remove(slot);
                    cut = true;
                    break;
                }
            }
            if (!cut) {
                // No ear anywhere: the outline crosses itself. Fan the rest and stop, rather than
                // dropping the shape - a warning that draws slightly wrong is better than one that
                // draws as nothing at all.
                for (int slot = 1; slot + 1 < remaining.size(); slot++) {
                    triangles.add(new int[]{remaining.get(0), remaining.get(slot), remaining.get(slot + 1)});
                }
                return triangles;
            }
        }
        if (remaining.size() == 3) {
            triangles.add(new int[]{remaining.get(0), remaining.get(1), remaining.get(2)});
        }
        return triangles;
    }

    /** The polygon's area, unsigned. */
    public static double area(List<Point> polygon) {
        List<Point> points = weld(polygon);
        return points.size() < 3 ? 0.0D : Math.abs(signedArea(points));
    }

    /**
     * The polygon's interior as parallel lines, for the hatched significant-hazard areas a real outlook
     * draws.
     *
     * <p>Each line is cut to the shape by the even-odd rule over its own crossings, so a concave shape gets
     * as many runs per line as it has spans and none of them leave the shape. Spacing is in plate units,
     * which is what makes the hatch read as texture at the display's own scale rather than getting finer
     * as an area gets smaller. Runs come back as {@code x1, z1, x2, z2}.
     */
    public static List<float[]> hatch(List<Point> polygon, float spacing, double angle) {
        List<float[]> runs = new ArrayList<>();
        List<Point> points = weld(polygon);
        if (points.size() < 3 || spacing <= WELD) {
            return runs;
        }
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double minAcross = Double.MAX_VALUE;
        double maxAcross = -Double.MAX_VALUE;
        for (Point point : points) {
            double across = -point.x() * sin + point.z() * cos;
            minAcross = Math.min(minAcross, across);
            maxAcross = Math.max(maxAcross, across);
        }
        double[] crossing = new double[points.size() + 4];
        for (double across = Math.ceil(minAcross / spacing) * spacing; across <= maxAcross; across += spacing) {
            int found = 0;
            for (int index = 0; index < points.size(); index++) {
                Point first = points.get(index);
                Point second = points.get((index + 1) % points.size());
                double firstAcross = -first.x() * sin + first.z() * cos;
                double secondAcross = -second.x() * sin + second.z() * cos;
                // The half-open test counts a crossing once even when a line passes exactly through a
                // corner, which is what keeps a run from being cut in two at every vertex.
                boolean crosses = (firstAcross <= across && secondAcross > across)
                        || (secondAcross <= across && firstAcross > across);
                if (!crosses) {
                    continue;
                }
                double along = first.x() * cos + first.z() * sin;
                double alongSecond = second.x() * cos + second.z() * sin;
                double t = (across - firstAcross) / (secondAcross - firstAcross);
                crossing[found++] = along + (alongSecond - along) * t;
            }
            if (found < 2) {
                continue;
            }
            java.util.Arrays.sort(crossing, 0, found);
            for (int index = 0; index + 1 < found; index += 2) {
                double from = crossing[index];
                double to = crossing[index + 1];
                if (to - from < WELD) {
                    continue;
                }
                runs.add(new float[]{
                        (float) (from * cos - across * sin), (float) (from * sin + across * cos),
                        (float) (to * cos - across * sin), (float) (to * sin + across * cos)
                });
            }
        }
        return runs;
    }

    /**
     * The polygon's outline as dashes, walked as one loop rather than edge by edge so the pattern flows
     * round the corners instead of restarting at every one. Runs come back as {@code x1, z1, x2, z2}.
     */
    public static List<float[]> dashes(List<Point> polygon, float dash, float gap) {
        List<float[]> runs = new ArrayList<>();
        List<Point> points = weld(polygon);
        if (points.size() < 2 || dash <= WELD) {
            return runs;
        }
        double period = dash + Math.max(gap, 0.0F);
        if (period <= WELD) {
            return runs;
        }
        double pattern = 0.0D;
        for (int index = 0; index < points.size(); index++) {
            Point first = points.get(index);
            Point second = points.get((index + 1) % points.size());
            double dx = second.x() - first.x();
            double dz = second.z() - first.z();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length < WELD) {
                continue;
            }
            double position = 0.0D;
            while (position < length - WELD) {
                double phase = (pattern + position) % period;
                if (phase < dash) {
                    double from = position;
                    double to = Math.min(length, position + (dash - phase));
                    if (to - from > WELD) {
                        runs.add(new float[]{
                                (float) (first.x() + dx * from / length), (float) (first.z() + dz * from / length),
                                (float) (first.x() + dx * to / length), (float) (first.z() + dz * to / length)
                        });
                    }
                    position = to;
                } else {
                    position += Math.min(length - position, period - phase);
                }
            }
            pattern = (pattern + length) % period;
        }
        return runs;
    }

    /**
     * The corners worth marking: vertices where the outline actually turns.
     *
     * <p>An outlook area arrives as a fourteen-sided ellipse, and a tick at every one of those vertices
     * would draw a dotted ring rather than four corners, so a vertex counts only when the direction
     * changes by more than {@code minimumTurnDegrees}. This is what a warning polygon's corner markers
     * are made from.
     */
    public static List<Point> corners(List<Point> polygon, double minimumTurnDegrees) {
        List<Point> corners = new ArrayList<>();
        List<Point> points = weld(polygon);
        int count = points.size();
        if (count < 3) {
            return corners;
        }
        // The cosine of the turn itself: a vertex counts when the direction changes by at least the
        // threshold, so ninety degrees (cosine zero) is well under it and a fourteen-gon's twenty-six
        // degrees (cosine 0.90) is well over it.
        double threshold = Math.cos(Math.toRadians(minimumTurnDegrees));
        for (int index = 0; index < count; index++) {
            Point previous = points.get((index + count - 1) % count);
            Point current = points.get(index);
            Point next = points.get((index + 1) % count);
            double inX = current.x() - previous.x();
            double inZ = current.z() - previous.z();
            double outX = next.x() - current.x();
            double outZ = next.z() - current.z();
            double inLength = Math.hypot(inX, inZ);
            double outLength = Math.hypot(outX, outZ);
            if (inLength < WELD || outLength < WELD) {
                continue;
            }
            double dot = (inX * outX + inZ * outZ) / (inLength * outLength);
            if (dot <= threshold) {
                corners.add(current);
            }
        }
        return corners;
    }

    /** True when a point is inside the polygon, by the crossing count. Used by the harness and by callers. */
    public static boolean contains(List<Point> polygon, float x, float z) {
        List<Point> points = weld(polygon);
        if (points.size() < 3) {
            return false;
        }
        boolean inside = false;
        for (int index = 0, previous = points.size() - 1; index < points.size(); previous = index++) {
            Point a = points.get(index);
            Point b = points.get(previous);
            boolean straddles = (a.z() > z) != (b.z() > z);
            if (straddles && x < (b.x() - a.x()) * (z - a.z()) / (b.z() - a.z()) + a.x()) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** The centroid of the polygon's corners, which is where a fan would have been drawn from. */
    public static Point centroid(List<Point> polygon) {
        List<Point> points = weld(polygon);
        if (points.isEmpty()) {
            return new Point(0.0F, 0.0F);
        }
        double x = 0.0D;
        double z = 0.0D;
        for (Point point : points) {
            x += point.x();
            z += point.z();
        }
        return new Point((float) (x / points.size()), (float) (z / points.size()));
    }

    private static double signedArea(List<Point> points) {
        double sum = 0.0D;
        for (int index = 0; index < points.size(); index++) {
            Point first = points.get(index);
            Point second = points.get((index + 1) % points.size());
            sum += first.x() * second.z() - second.x() * first.z();
        }
        return sum * 0.5D;
    }

    /** Drops repeated points, including the closing repeat a wrapped loop would otherwise carry. */
    private static List<Point> weld(List<Point> polygon) {
        List<Point> welded = new ArrayList<>();
        if (polygon == null) {
            return welded;
        }
        for (Point point : polygon) {
            if (point == null) {
                continue;
            }
            if (!welded.isEmpty()) {
                Point last = welded.get(welded.size() - 1);
                if (Math.abs(last.x() - point.x()) < WELD && Math.abs(last.z() - point.z()) < WELD) {
                    continue;
                }
            }
            welded.add(point);
        }
        while (welded.size() > 1) {
            Point first = welded.get(0);
            Point last = welded.get(welded.size() - 1);
            if (Math.abs(first.x() - last.x()) < WELD && Math.abs(first.z() - last.z()) < WELD) {
                welded.remove(welded.size() - 1);
            } else {
                break;
            }
        }
        return welded;
    }

    private static boolean isEar(List<Point> points, List<Integer> remaining, int previous, int current, int next,
                                 boolean counterClockwise) {
        Point a = points.get(previous);
        Point b = points.get(current);
        Point c = points.get(next);
        double cross = (b.x() - a.x()) * (c.z() - a.z()) - (b.z() - a.z()) * (c.x() - a.x());
        if (counterClockwise ? cross <= 0.0D : cross >= 0.0D) {
            return false;
        }
        for (int slot = 0; slot < remaining.size(); slot++) {
            int index = remaining.get(slot);
            if (index == previous || index == current || index == next) {
                continue;
            }
            if (insideTriangle(a, b, c, points.get(index))) {
                return false;
            }
        }
        return true;
    }

    /** Strictly inside, so a corner shared with the candidate triangle does not block it. */
    private static boolean insideTriangle(Point a, Point b, Point c, Point point) {
        double first = cross(a, b, point);
        double second = cross(b, c, point);
        double third = cross(c, a, point);
        boolean negative = first < -WELD || second < -WELD || third < -WELD;
        boolean positive = first > WELD || second > WELD || third > WELD;
        return !(negative && positive);
    }

    private static double cross(Point a, Point b, Point point) {
        return (b.x() - a.x()) * (point.z() - a.z()) - (b.z() - a.z()) * (point.x() - a.x());
    }

    /**
     * Cuts the polygon against one plate edge, keeping the side the plate lives on. Edge 0 is {@code x <=
     * half}, 1 is {@code x >= -half}, 2 is {@code z <= half} and 3 is {@code z >= -half}, so the four calls
     * in {@link #clip} are the square.
     */
    private static List<Point> clipEdge(List<Point> polygon, int edge, float half) {
        List<Point> result = new ArrayList<>(polygon.size() + 2);
        Point previous = polygon.get(polygon.size() - 1);
        double previousSide = side(previous, edge, half);
        for (Point current : polygon) {
            double currentSide = side(current, edge, half);
            boolean previousInside = previousSide >= 0.0D;
            boolean currentInside = currentSide >= 0.0D;
            if (currentInside != previousInside) {
                double t = previousSide / (previousSide - currentSide);
                result.add(new Point(
                        (float) (previous.x() + (current.x() - previous.x()) * t),
                        (float) (previous.z() + (current.z() - previous.z()) * t)
                ));
            }
            if (currentInside) {
                result.add(current);
            }
            previous = current;
            previousSide = currentSide;
        }
        return result;
    }

    private static double side(Point point, int edge, float half) {
        return switch (edge) {
            case 0 -> half - point.x();
            case 1 -> point.x() + half;
            case 2 -> half - point.z();
            default -> point.z() + half;
        };
    }
}
