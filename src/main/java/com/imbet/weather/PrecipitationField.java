package com.imbet.weather;

/**
 * Where a storm's precipitation actually is, as a function of position.
 *
 * <p>Weather2 - and, before this, our own client - treat a storm's rain as one number: it is raining
 * under the cloud, or it is not, and the rain that is drawn is a blob centred on a point. That is why
 * rain around a tornado reads as a grey mass sitting on the storm rather than as weather. The
 * Weather2 Remastered fork gets a lot further than this with a single idea worth copying: its rain is
 * a field over the storm's disc, queried per position ({@code getDownfall(pos)} - full rate inside
 * three quarters of the storm's radius, falling linearly to nothing at its edge, with the drizzle /
 * rain / heavy thresholds at 50 / 200 / 300), and it substitutes that field into vanilla's own
 * {@code isRainingAt}, so the rain in the world <em>is</em> the storm's rain rather than a decoration
 * drawn over it.
 *
 * <p>What a field buys, and what this class is for, is that a supercell's rain is not a disc either.
 * It is a set of parts with shapes and a reason for each:
 *
 * <ul>
 *   <li>the <b>forward-flank core</b>, the bulk of the rain, ahead of and across the updraft;</li>
 *   <li>the <b>hook</b>, rear-flank rain that wraps around the mesocyclone - this is the rain that
 *       wraps a tornado, and it is an <em>arc</em>, not a blob, which is the whole point: a curtain
 *       between a viewer and the funnel on the wrapped side, and open air on the other;</li>
 *   <li>the <b>rain-free notch</b>, the clear slot where inflow is being drawn up, which has to stay
 *       rain-free or the funnel is never visible from the inflow side;</li>
 *   <li>the weaker <b>rear-flank</b> and <b>stratiform</b> rain that fills in behind and downshear.</li>
 * </ul>
 *
 * <p>Kept free of Minecraft on purpose. The shapes, the falloffs and the wrapped-ness are arithmetic
 * that can be measured without a running game, which matters because everything here is invisible in
 * a log: a field that put rain in the notch, or a hook that wrapped the wrong way round, would look
 * exactly as plausible as a correct one while playing.
 */
public final class PrecipitationField {
    private PrecipitationField() {
    }

    /**
     * A storm's precipitation geometry, in storm-relative blocks: forward and right are the storm's own
     * axes, so nothing here has to know about world coordinates or which way anything is facing.
     *
     * <p>The anchors are positions relative to the storm's centre and the strengths are 0..1.
     */
    public record Geometry(
            double radius,
            double mesoForward, double mesoRight,
            double coreForward, double coreRight,
            double notchForward, double notchRight,
            double wrapForward, double wrapRight,
            double rearFlankForward, double rearFlankRight,
            double coreStrength,
            double hookStrength,
            double notchStrength,
            double rearStrength,
            double stratiformStrength
    ) {
    }

    /** How much of the whole field is wet, from nothing to a solid curtain. */
    public static final double NOTCH_CLEARANCE = 0.95D;

    /**
     * The rain density at one point, 0 = clear air and 1 = a torrential curtain.
     *
     * <p>Points are given in the storm's own frame, so these can be reasoned about - and tested - as
     * plain offsets from the storm's centre.
     */
    public static double densityAtLocal(Geometry field, double forward, double right) {
        return density(field, forward, right, 1.0D);
    }

    /**
     * The same, in world coordinates, given the storm's frame as unit vectors.
     *
     * <p>This is what a renderer or an instrument calls: it is the one place that knows how the field's
     * own axes map onto the world.
     */
    public static double densityAt(Geometry field, double centerX, double centerZ, double forwardX, double forwardZ, double rightX, double rightZ, double x, double z) {
        double dx = x - centerX;
        double dz = z - centerZ;
        double forward = dx * forwardX + dz * forwardZ;
        double right = dx * rightX + dz * rightZ;
        return density(field, forward, right, 1.0D);
    }

    /**
     * How much rain stands between a point and a viewer looking from every direction: 0 = the point is
     * in the open, 1 = it is enclosed by the curtain.
     *
     * <p>This is what "rain-wrapped" means, and it is deliberately a property of the geometry rather
     * than of the storm's average character: a storm can be pouring rain out of its forward flank and
     * still leave its tornado in the clear, and the reverse. Sampling a ring around the vortex and
     * asking what share of it is wet gives the shape of the answer for nothing.
     */
    public static double wrappedShare(Geometry field, double forward, double right, double ringRadius, int directions) {
        if (directions <= 0) {
            return 0.0D;
        }
        double wet = 0.0D;
        for (int index = 0; index < directions; index++) {
            double angle = TWO_PI * index / directions;
            double density = density(field, forward + Math.cos(angle) * ringRadius, right + Math.sin(angle) * ringRadius, 1.0D);
            // Weighted rather than counted: a thin drizzle on one side of the vortex is not a curtain.
            wet += Math.min(1.0D, density / WRAP_DENSITY);
        }
        return clamp01(wet / directions);
    }

    /** Density at or above this counts as rain you cannot see through. */
    public static final double WRAP_DENSITY = 0.45D;

    private static double density(Geometry field, double forward, double right, double scale) {
        double radius = Math.max(1.0D, field.radius());
        double coreF = field.coreForward();
        double coreR = field.coreRight();

        // The forward-flank core: the storm's main rain mass, broader across than along because it is
        // fed by the updraft and spread by the anvil, and it leans downshear.
        double core = field.coreStrength() * ellipse(forward - coreF, right - coreR, radius * 0.62D, radius * 0.44D, 0.42D);

        // The hook: a band at a fixed radius around the mesocyclone, limited to the sector the wrapped
        // rain occupies. The inner side of the band is left dry, which is what an eye-like gap in the
        // hook echo actually is, and it is also what stops the curtain from filling the vortex itself.
        double hook = field.hookStrength() * hookBand(field, forward, right, radius);

        // Rear flank: the broad, weaker rain of the rear-flank downdraft, which is what makes the
        // region behind the storm damp rather than a hole.
        double rear = field.rearStrength() * ellipse(forward - field.rearFlankForward(), right - field.rearFlankRight(),
                radius * 0.62D, radius * 0.55D, 0.55D);

        // Stratiform: light rain trailing under the anvil downshear, the drizzle that keeps falling
        // after the core has gone past.
        double stratiform = field.stratiformStrength() * ellipse(forward - radius * 0.95D, right - radius * 0.12D,
                radius * 0.95D, radius * 0.75D, 0.20D);

        double density = Math.max(Math.max(core, hook), Math.max(rear, stratiform));

        // The rain-free notch cuts through whatever is there. Applied last and to everything, because
        // it is a slot carved by the inflow through all of the precipitation, not a gap in one part.
        double cut = field.notchStrength() * notch(field, forward, right, radius);
        density *= 1.0D - NOTCH_CLEARANCE * cut;

        return clamp01(density * scale);
    }

    /**
     * The wrapped rain band around the mesocyclone, as a fraction of the hook's own strength.
     *
     * <p>Built from two pieces with different jobs. The <em>ring</em> is the arc that wraps the vortex.
     * The <em>shaft</em> is a narrow corridor joining the arc back to the forward-flank core, because
     * a hook is connected to the core - that is what makes it read as a hook on radar and in the sky
     * rather than as an unconnected band of rain.
     */
    private static double hookBand(Geometry field, double forward, double right, double radius) {
        if (field.hookStrength() <= 0.0D) {
            return 0.0D;
        }
        double fromMesoF = forward - field.mesoForward();
        double fromMesoR = right - field.mesoRight();
        double distance = Math.hypot(fromMesoF, fromMesoR);

        // The arc: a band whose radius is the distance the wrapped rain actually sits at.
        double ringRadius = Math.max(radius * 0.14D, Math.hypot(field.wrapForward() - field.mesoForward(), field.wrapRight() - field.mesoRight()));
        double radial = Math.abs(distance - ringRadius) / (radius * 0.17D);
        double ring = falloff(radial, 0.15D);

        // Only the sector the wrapped rain occupies. The sector points at where the curtain anchor is
        // relative to the mesocyclone, so it always wraps the side the storm's own structure built.
        double wrapAngle = Math.atan2(field.wrapRight() - field.mesoRight(), field.wrapForward() - field.mesoForward());
        double angle = Math.atan2(fromMesoR, fromMesoF);
        // How far round the vortex the band reaches. This has to be wide enough to pass *through* the
        // notch's own bearing, or the clear slot only grazes the band's tail and scours empty air - the
        // measured difference between a slot that cuts the rain and one that does not is 0.09 against
        // 0.52 of density. It stays well short of a full ring, because the upshear flank of the
        // mesocyclone is where the inflow is and where the rain must not be.
        double spread = 1.25D + 0.70D * clamp01(field.hookStrength());
        double sector = falloff(angleDistance(angle, wrapAngle) / spread, 0.35D);

        // And the corridor from the arc back to the core, because a hook is joined to the core.
        double shaft = segment(forward, right, field.wrapForward(), field.wrapRight(), field.coreForward(), field.coreRight(), radius * 0.13D);

        return Math.max(ring * sector, shaft * 0.85D);
    }

    /**
     * The clear slot: how much of the rain is scoured out at a point.
     *
     * <p>A wedge opening from the mesocyclone through the notch anchor and continuing out toward where
     * the inflow comes from, which is what makes the funnel visible from that side and nothing else.
     */
    private static double notch(Geometry field, double forward, double right, double radius) {
        double directionF = field.notchForward() - field.mesoForward();
        double directionR = field.notchRight() - field.mesoRight();
        double length = Math.hypot(directionF, directionR);
        if (length < 1.0E-4D) {
            directionF = -1.0D;
            directionR = 0.0D;
            length = 1.0D;
        }
        directionF /= length;
        directionR /= length;
        double fromMesoF = forward - field.mesoForward();
        double fromMesoR = right - field.mesoRight();
        double along = fromMesoF * directionF + fromMesoR * directionR;
        double lateral = Math.abs(fromMesoF * -directionR + fromMesoR * directionF);
        if (along <= 0.0D) {
            // Behind the mesocyclone there is no notch: the slot opens downshear of it, not all round.
            return 0.0D;
        }
        double wedge = falloff(lateral / (radius * 0.34D), 0.30D);
        double reach = falloff(along / (radius * 1.05D), 0.0D);
        return wedge * reach;
    }

    private static double angleDistance(double first, double second) {
        double distance = Math.abs(first - second) % TWO_PI;
        return distance > Math.PI ? TWO_PI - distance : distance;
    }

    /**
     * A blob that is solid inside a fraction of its size and falls smoothly to nothing at its edge.
     *
     * <p>The plateau is what keeps a lobe a shape rather than a spike: without it every part of the
     * field would be brightest at exactly one point and thin everywhere else, which is the same as
     * having no parts at all.
     */
    private static double ellipse(double forward, double right, double halfForward, double halfRight, double plateau) {
        double q = Math.hypot(forward / Math.max(1.0D, halfForward), right / Math.max(1.0D, halfRight));
        return falloff(q, plateau);
    }

    /** Density along a straight run between two points, tapering at both ends. */
    private static double segment(double pointForward, double pointRight, double startForward, double startRight, double endForward, double endRight, double width) {
        double sx = endForward - startForward;
        double sr = endRight - startRight;
        double lengthSqr = sx * sx + sr * sr;
        if (lengthSqr < 1.0E-4D) {
            return falloff(Math.hypot(pointForward - startForward, pointRight - startRight) / Math.max(1.0D, width), 0.0D);
        }
        double t = clamp01(((pointForward - startForward) * sx + (pointRight - startRight) * sr) / lengthSqr);
        double x = startForward + sx * t;
        double r = startRight + sr * t;
        return falloff(Math.hypot(pointForward - x, pointRight - r) / Math.max(1.0D, width), 0.0D);
    }

    /** 1 inside the plateau, smoothly down to 0 at 1 (and beyond). */
    private static double falloff(double q, double plateau) {
        if (q <= plateau) {
            return 1.0D;
        }
        if (q >= 1.0D) {
            return 0.0D;
        }
        double t = (1.0D - q) / Math.max(1.0E-4D, 1.0D - plateau);
        return t * t * (3.0D - 2.0D * t);
    }

    private static double clamp01(double value) {
        return value < 0.0D ? 0.0D : Math.min(value, 1.0D);
    }

    private static final double TWO_PI = Math.PI * 2.0D;
}
