package com.imbet.client;

import com.imbet.Config;
import com.imbet.network.ForecastPolygonSnapshot;
import com.imbet.weather.ClientForecastStore;
import com.imbet.weather.ForecastPolygonGeometry;
import com.imbet.weather.ForecastPanel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The forecast display's plate polygons: cut to the scope, filled from the outside in, hatched where they
 * are significant, and outlined so their edges read at a distance.
 *
 * <p>Every area the display draws is wider than the three-block plate it is drawn on - an outlook covers
 * hundreds of blocks and a warning covers tens - so the first thing that happens to every one of them is
 * that the plate's edge cuts it, in {@link ForecastPolygonGeometry#clip}. Tying its corners to the rim
 * instead, which is what the icons do and what the areas used to do, leaves a shape that is not the area at
 * all: the corners bunch on the border, every category bigger than the plate lands on the same border, and
 * the fill drawn from those bunched corners becomes the flat slivers this replaces.
 *
 * <p>The rest follows from what the real products look like, because those conventions exist for reasons a
 * player can see:
 *
 * <ul>
 *   <li>the broadest category is laid down first and each tighter one covers it, and the nesting is read
 *       off the outlines rather than off the fills. The fills used to be solid enough to hide what was
 *       under them, which read beautifully as nested bands and turned the entire plate - and the ground
 *       around the block it stands on - into the outlook's own green. At the shipped default the fill is a
 *       tint and each category's outline carries its colour, so the fill's opacity is handed to
 *       {@link com.imbet.weather.ForecastPanel} and comes back as the alpha and width the edge earns;
 *       stacking every category's wash thicker than that, which is what a player can still configure, puts
 *       the plate back to one colour with the outlines' own strength beside the point;</li>
 *   <li>the significant-hazard areas are hatched rather than filled, which is what the hatching means on a
 *       real outlook: the hatch marks where a hazard reaches its significant threshold, and it survives
 *       being drawn over a category fill in a way a second translucent wash does not;</li>
 *   <li>an outline is drawn twice, a wide dim halo under a narrow bright core. There is no anti-aliasing in
 *       world geometry, so the halo is what stops an edge from breaking into visible steps where it runs
 *       near-parallel to a pixel row, which is most of the edges on a plate seen from above;</li>
 *   <li>a warning carries corner markers and a watch is dashed, which is how the two are told apart at a
 *       glance in the products themselves.</li>
 * </ul>
 *
 * <p>All of the arithmetic lives in {@link ForecastPolygonGeometry} and is measured there; this class only
 * turns it into vertices.
 */
final class PlatePolygonRenderer {

    /** The half-extent of the scope's own square, matching the mapping the icons and readouts use. */
    private static final float SCOPE_HALF = 1.42F;

    /** A tick at each corner of a warning polygon, as a half-width in plate units. */
    private static final float CORNER_HALF = 0.030F;

    /**
     * How sharp a turn has to be before it counts as a corner worth marking. Sits between the two shapes
     * that actually arrive: a swath or a rectangle turns by about ninety degrees, while an area built from
     * a fourteen-sided ellipse turns by under twenty-six, and marking those would draw a dotted ring round
     * a shape that has no corners at all.
     */
    private static final double CORNER_TURN_DEGREES = 35.0D;

    /** Height of each drawing above the plate face, in blocks, lowest first. */
    private static final float RISK_FILL_Y = 0.031F;
    private static final float HATCH_Y = 0.037F;
    private static final float RISK_EDGE_Y = 0.041F;
    private static final float HAZARD_EDGE_Y = 0.049F;
    private static final float ALERT_FILL_Y = 0.034F;

    /** Halo width as a multiple of the core line's, and how much of its alpha the halo keeps. */
    private static final float HALO_WIDTH = 2.1F;
    private static final float HALO_ALPHA = 0.34F;

    /**
     * The core half-width of an outline at a solid fill, before the model adds back what a dimmer fill
     * costs it: a category's edge, a hatched significant-hazard area's edge, and the hatch lines' own.
     */
    private static final float CATEGORY_EDGE_HALF = 0.0105F;
    private static final float HAZARD_EDGE_HALF = 0.0135F;

    /**
     * The alphas a hazard's edge and its hatch carry when the fill is solid, paired with what they earn on a
     * bare plate. A category's pair is not here: it is the mod's own default for that trade and lives with the
     * arithmetic that makes it, in {@link com.imbet.weather.ForecastPanel}.
     */
    private static final int HAZARD_EDGE_SOLID = 235;
    private static final int HAZARD_EDGE_BARE = 250;
    private static final int HATCH_SOLID = 190;
    private static final int HATCH_BARE = 218;

    /** How far the outline of a hatched or filled shape steps inward from its own edge, in plate units. */
    private static final float DASH_LENGTH = 0.052F;
    private static final float DASH_GAP = 0.034F;

    private PlatePolygonRenderer() {
    }

    /** One snapshot as the plate actually sees it: the part of it on the scope, and the corners to mark. */
    private record PlateShape(ForecastPolygonSnapshot snapshot, List<ForecastPolygonGeometry.Point> outline, double area) {
    }

    /**
     * The SPC outlook: nested category areas, and the hatched significant-hazard areas on top of them.
     */
    static void renderRisk(PoseStack stack, MultiBufferSource.BufferSource buffer, ClientForecastStore.Display display,
                           List<ForecastPolygonSnapshot> polygons) {
        List<PlateShape> categories = plateShapes(display, polygons, ForecastPolygonSnapshot::risk);
        List<PlateShape> hazards = plateShapes(display, polygons, ForecastPolygonSnapshot::significantHazard);
        if (categories.isEmpty() && hazards.isEmpty()) {
            return;
        }
        VertexConsumer consumer = buffer.getBuffer(plateType());
        PoseStack.Pose pose = stack.last();

        // Widest first. This is an ordering, not a preference: the category that covers the most ground has
        // to be underneath, or a tighter one would be painted over by the broader area containing it.
        categories.sort(Comparator.comparingDouble(PlateShape::area).reversed());
        double opacity = Mth.clamp(Config.FORECAST_POLYGON_FILL_OPACITY.get(), 0.05D, 1.0D);
        int categoryEdge = ForecastPanel.edgeAlpha(opacity, ForecastPanel.EDGE_ALPHA_SOLID_FILL,
                ForecastPanel.EDGE_ALPHA_BARE);
        float categoryWidth = ForecastPanel.edgeWidth(opacity, CATEGORY_EDGE_HALF);
        int slot = 0;
        for (PlateShape shape : categories) {
            fill(consumer, pose, shape, RISK_FILL_Y + slot * 0.0004F,
                    withAlpha(shape.snapshot().color(), (int) Math.round(255.0D * opacity)));
            halo(consumer, pose, shape, categoryWidth, RISK_EDGE_Y,
                    withAlpha(shape.snapshot().color(), categoryEdge), false);
            slot++;
        }

        boolean hatched = Config.FORECAST_POLYGON_HATCHING.get();
        float spacing = Mth.clamp(Config.FORECAST_POLYGON_HATCH_SPACING.get().floatValue(), 0.02F, 0.5F);
        int hazardEdge = ForecastPanel.edgeAlpha(opacity, HAZARD_EDGE_SOLID, HAZARD_EDGE_BARE);
        int hatchAlpha = ForecastPanel.edgeAlpha(opacity, HATCH_SOLID, HATCH_BARE);
        float hazardWidth = ForecastPanel.edgeWidth(opacity, HAZARD_EDGE_HALF);
        for (PlateShape shape : hazards) {
            if (hatched) {
                hatch(consumer, pose, shape, spacing, withAlpha(shape.snapshot().color(), hatchAlpha));
            }
            halo(consumer, pose, shape, hazardWidth, HAZARD_EDGE_Y,
                    withAlpha(shape.snapshot().color(), hazardEdge), true);
        }
    }

    /**
     * The live alerts: warnings and watches, with the emergency tier on top and warnings marked at their
     * corners.
     */
    static void renderAlerts(PoseStack stack, MultiBufferSource.BufferSource buffer, ClientForecastStore.Display display,
                             List<ForecastPolygonSnapshot> polygons) {
        List<PlateShape> alerts = plateShapes(display, polygons,
                polygon -> polygon.warning() || polygon.watch());
        if (alerts.isEmpty()) {
            return;
        }
        VertexConsumer consumer = buffer.getBuffer(plateType());
        PoseStack.Pose pose = stack.last();

        // Layer first, then widest first inside a layer, so a watch is under the warning inside it and an
        // emergency is over both.
        alerts.sort(Comparator
                .comparingInt((PlateShape shape) -> layer(shape.snapshot()))
                .thenComparing(Comparator.comparingDouble(PlateShape::area).reversed()));
        boolean markers = Config.FORECAST_POLYGON_CORNER_MARKERS.get();
        for (PlateShape shape : alerts) {
            int layer = layer(shape.snapshot());
            float y = ALERT_FILL_Y + layer * 0.002F;
            boolean dashed = shape.snapshot().watch();
            fill(consumer, pose, shape, y, alertFillColor(shape.snapshot()));
            halo(consumer, pose, shape, 0.0115F, y + 0.006F, withAlpha(shape.snapshot().color(), 240), dashed);
            if (markers && !dashed) {
                corners(consumer, pose, shape, y + 0.009F, withAlpha(shape.snapshot().color(), 250));
            }
        }
    }

    /**
     * The polygons as the plate sees them: projected, cut to the scope, and dropped when the cut leaves
     * nothing.
     *
     * <p>Projection is the unclamped mapping, which is the point of the class - a shape is never moved onto
     * the rim, only cut by it.
     */
    private static List<PlateShape> plateShapes(ClientForecastStore.Display display, List<ForecastPolygonSnapshot> polygons,
                                                Predicate<ForecastPolygonSnapshot> keep) {
        List<PlateShape> shapes = new ArrayList<>();
        if (polygons == null) {
            return shapes;
        }
        double originX = display.origin().getX() + 0.5D;
        double originZ = display.origin().getZ() + 0.5D;
        for (ForecastPolygonSnapshot polygon : polygons) {
            if (polygon == null || !keep.test(polygon)) {
                continue;
            }
            List<ForecastPolygonSnapshot.Point> points = polygon.points();
            if (points == null || points.size() < 3) {
                continue;
            }
            List<ForecastPolygonGeometry.Point> projected = new ArrayList<>(points.size());
            for (ForecastPolygonSnapshot.Point point : points) {
                if (point == null) {
                    continue;
                }
                projected.add(ForecastPolygonGeometry.project(point.x() - originX, point.z() - originZ, display.range()));
            }
            List<ForecastPolygonGeometry.Point> outline = ForecastPolygonGeometry.clip(projected, SCOPE_HALF);
            if (outline.size() < 3) {
                continue;
            }
            shapes.add(new PlateShape(polygon, outline, ForecastPolygonGeometry.area(outline)));
        }
        return shapes;
    }

    /** A shape's interior, as the triangles ear clipping produced for its cut outline. */
    private static void fill(VertexConsumer consumer, PoseStack.Pose pose, PlateShape shape, float y, int color) {
        List<ForecastPolygonGeometry.Point> outline = shape.outline();
        for (int[] triangle : ForecastPolygonGeometry.triangulate(outline)) {
            ForecastPolygonGeometry.Point first = outline.get(triangle[0]);
            ForecastPolygonGeometry.Point second = outline.get(triangle[1]);
            ForecastPolygonGeometry.Point third = outline.get(triangle[2]);
            RenderQuads.topPolyQuad(consumer, pose,
                    first.x(), first.z(), second.x(), second.z(),
                    third.x(), third.z(), third.x(), third.z(), y, color);
        }
    }

    /** The hatch inside a significant-hazard area, each run clipped to the shape by the geometry. */
    private static void hatch(VertexConsumer consumer, PoseStack.Pose pose, PlateShape shape, float spacing, int color) {
        for (float[] run : ForecastPolygonGeometry.hatch(shape.outline(), spacing, Math.toRadians(45.0D))) {
            RenderQuads.topLine(consumer, pose, run[0], run[1], run[2], run[3], 0.008F, HATCH_Y, color);
        }
    }

    /**
     * A shape's outline: the wide dim halo, then the narrow bright core on top of it.
     *
     * <p>The halo is drawn first and on the same plane, so the core wins where they overlap and what is left
     * showing is a soft border. Lifting the core above the halo in y rather than drawing it second would do
     * the same thing and cost the plate its flat look, so the order does it.
     */
    private static void halo(VertexConsumer consumer, PoseStack.Pose pose, PlateShape shape, float halfWidth,
                             float y, int color, boolean dashed) {
        int haloColor = withAlpha(color, (int) Math.round((color >>> 24 & 255) * HALO_ALPHA));
        line(consumer, pose, shape, halfWidth * HALO_WIDTH, y, haloColor, dashed);
        line(consumer, pose, shape, halfWidth, y + 0.0015F, color, dashed);
    }

    private static void line(VertexConsumer consumer, PoseStack.Pose pose, PlateShape shape, float halfWidth,
                             float y, int color, boolean dashed) {
        List<ForecastPolygonGeometry.Point> outline = shape.outline();
        if (dashed) {
            for (float[] run : ForecastPolygonGeometry.dashes(outline, DASH_LENGTH, DASH_GAP)) {
                RenderQuads.topLine(consumer, pose, run[0], run[1], run[2], run[3], halfWidth, y, color);
            }
            return;
        }
        for (int index = 0; index < outline.size(); index++) {
            ForecastPolygonGeometry.Point first = outline.get(index);
            ForecastPolygonGeometry.Point second = outline.get((index + 1) % outline.size());
            RenderQuads.topLine(consumer, pose, first.x(), first.z(), second.x(), second.z(), halfWidth, y, color);
        }
    }

    /** Corner markers: a tick at each vertex the outline actually turns at, as a warning is drawn. */
    private static void corners(VertexConsumer consumer, PoseStack.Pose pose, PlateShape shape, float y, int color) {
        for (ForecastPolygonGeometry.Point corner : ForecastPolygonGeometry.corners(shape.outline(), CORNER_TURN_DEGREES)) {
            RenderQuads.topQuad(consumer, pose,
                    corner.x() - CORNER_HALF, corner.z() - CORNER_HALF,
                    corner.x() + CORNER_HALF, corner.z() + CORNER_HALF, y, color);
        }
    }

    /** How deep an alert sits in the stack: a watch under the warning inside it, an emergency over both. */
    private static int layer(ForecastPolygonSnapshot polygon) {
        if (isEmergency(polygon)) {
            return 2;
        }
        return polygon.warning() ? 1 : 0;
    }

    /**
     * The fill of a live alert, which is the one place the snapshot's own colours are not used: an alert's
     * colour describes its outline and its map symbology, and the fill has to be the tier's own tone to be
     * told from the tier below it at a glance.
     */
    private static int alertFillColor(ForecastPolygonSnapshot polygon) {
        if (isEmergency(polygon)) {
            return 0xB8D823FF;
        }
        if (polygon.watch()) {
            return 0x96F6C945;
        }
        String label = normalized(polygon.label());
        String product = normalized(polygon.productCode());
        if (label.contains("severe thunderstorm") || product.contains("svr")) {
            return 0x9AFFD23F;
        }
        return 0xA8FF3030;
    }

    private static boolean isEmergency(ForecastPolygonSnapshot polygon) {
        String label = normalized(polygon.label());
        String product = normalized(polygon.productCode());
        return label.contains("emergency") || product.contains("tor-e") || product.contains("emergency");
    }

    private static String normalized(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    /**
     * The plate's render type. {@code debugQuads()} is a singleton, so asking for it here hands back the
     * same instance the plate and the icons are drawn with - one batch, one depth-sorted layer - and it is
     * the colour-only, no-cull, main-target type the rest of the plate must share.
     */
    private static RenderType plateType() {
        return RenderType.debugQuads();
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(alpha, 255)) << 24) | (color & 0x00FFFFFF);
    }
}
