package com.imbet.client;

import com.imbet.Config;
import com.imbet.radar.RadarPrecipitation;
import com.imbet.radar.RadarStormSnapshot;
import com.imbet.weather.PrecipitationField;
import com.imbet.weather.SpcRisk;
import com.imbet.weather.TornadoSizeModel;
import net.minecraft.util.Mth;

/**
 * The radar's product maths, shared by every display of it.
 *
 * <p>Everything here is expressed in <em>panel units</em>: the scope centre is 0.5, {@link #SCOPE_RADIUS}
 * is the radius of the circle the configured range maps onto, and a world position is placed by
 * {@link #scopeCoord}. The same numbers therefore drive the in-world panel on a block's top face and the
 * handheld screen in a GUI, so the two cannot disagree about where a storm is or how strong it reads - which
 * they did while each had its own copy of this: the panel drew a smooth echo at the true position and the
 * handheld drew a flat square at a guessed one.
 *
 * <p>It is deliberately free of block entities: a display is described by the world position it sweeps from
 * and its range, which is what the weather radar block and the linked handheld both have. The scope is
 * drawn by callers however they can - quads on a block face, fills in a GUI - and they must agree on the
 * field, which is the point of the class.
 */
final class RadarEchoField {
    /** Radius the configured radar range maps onto - the circle {@link #insideScope} gates on. */
    static final float SCOPE_RADIUS = 0.43F;

    /** NEXRAD-style reflectivity ramp: the dBZ at each stop and the colour a return takes there. */
    private static final float[] DBZ_STOPS = { 5.0F, 10.0F, 15.0F, 20.0F, 25.0F, 30.0F, 35.0F, 40.0F, 45.0F, 50.0F, 55.0F, 60.0F, 65.0F, 70.0F, 75.0F };
    private static final int[] DBZ_COLORS = { 0x04E9E7, 0x019FF4, 0x0300F4, 0x02FD02, 0x01C501, 0x008E00, 0xFDF802, 0xE5BC00, 0xFD9500, 0xFD0000, 0xD40000, 0xBC0000, 0xF800FD, 0x9854C6, 0xFDFDFD };
    private static final int ECHO_ALPHA = 230;

    /**
     * The faintest alpha a return may be drawn at.
     *
     * <p>A return carries its strength in how opaque it is, and the ramp bottoms out here rather than at
     * zero, just above {@link RadarShader#PASS_THROUGH_ALPHA}. A corner written at zero would interpolate
     * fragments across that threshold in the middle of a cell that is being drawn, and the program would
     * paint the scope face up through the return there; and fading toward the panel's ink at constant
     * opacity, which is what this used to do, leaves a dark ring around every shield now that the screen
     * behind it is green rather than black.
     */
    private static final int ECHO_MIN_ALPHA = 130;

    /** The faintest reflectivity drawn, and how quickly a return firms up above it. */
    static final float ECHO_FLOOR_DBZ = 4.0F;
    private static final float ECHO_FADE_DBZ = 9.0F;

    /** The calmest radial motion the velocity product marks, in m/s, and how quickly it fades in. */
    static final float VELOCITY_FLOOR_MPS = 1.5F;
    private static final float VELOCITY_FADE_MPS = 4.0F;

    /** Alpha the pass-through path is written with when its author asked for something fainter. */
    private static final float OVERLAY_ALPHA = 0.62F;

    private RadarEchoField() {
    }

    /** Side of one field cell, in panel units: the resolution both displays sample the products at. */
    static float pixelCell() {
        return 0.01075F;
    }

    /** How far a reflectivity shield reaches from its centre, as a multiple of its radius. */
    private static final float ECHO_SPAN = 2.85F;

    /**
     * How many cells across a return is drawn, at whatever resolution and in whatever unit the caller works
     * in - the display's own cell size, and the size of the return in that same unit.
     *
     * <p>Both displays take their lattice from here so a shield is sampled the same number of times across
     * whatever it is drawn on, rather than each picking a resolution and disagreeing about how fine the
     * storm's structure is.
     */
    static int echoCells(float size, float cell) {
        return Mth.clamp(Mth.ceil(size * ECHO_SPAN / cell), 8, 32);
    }

    /** The same, for the velocity product's tighter couplet. */
    static int velocityCells(float size, float cell) {
        return Mth.clamp(Mth.ceil(size * 2.20F / cell), 7, 28);
    }

    /** The same, for a risk area's disc. */
    static int riskCells(float size, float cell) {
        return Mth.clamp(Mth.ceil(size * 1.7F / cell), 5, 18);
    }

    // ------------------------------------------------------------------------------------------------
    // Placement: world position to scope position.
    // ------------------------------------------------------------------------------------------------

    /**
     * A world coordinate on the scope, as {@code 0.5 + offset}.
     *
     * <p>{@code origin} is the block the sweep is centred on; the offset added here is that block's own
     * half-block, so a position maps by its centre. The divisor used to be {@code range * 0.84} with the
     * result clamped to 0.42 - a diameter factor applied to a radius offset - so only the inner half of the
     * radar's range was ever drawn at its true distance and everything beyond it was pinned to the rim.
     */
    static float scopeCoord(double world, double origin, int range) {
        return 0.5F + scopeOffset((world - (origin + 0.5D)) / Math.max(1, range));
    }

    /** A fraction of the radar's full range, as a signed offset from the scope centre in panel units. */
    /** A world coordinate as a panel offset from the centre, with no clamp: for fields that clip themselves. */
    static float scopeOffsetUnclamped(double world, double origin, int range) {
        return (float) ((world - (origin + 0.5D)) / Math.max(1, range) * SCOPE_RADIUS);
    }

    static float scopeOffset(double rangeFraction) {
        return Mth.clamp((float) (rangeFraction * SCOPE_RADIUS), -SCOPE_RADIUS, SCOPE_RADIUS);
    }

    /**
     * Echo radius in panel units, or 0 for a return that is not itself a storm.
     *
     * <p>This is the storm's precipitation shield, so it is sized from the storm's own radius. It used to be
     * sized from the tornado's damage-path width, which meant a violent but small storm drew a broader echo
     * than a large storm with a weak tornado - and a distance from the radar told you nothing, because the
     * old mapping only resolved the inner half of the configured range.
     */
    static float returnSize(RadarStormSnapshot storm, int range) {
        if (!storm.hasStormExtent()) {
            return 0.0F;
        }
        float radiusPanel = (float) (storm.stormRadius() / Math.max(1, range) * SCOPE_RADIUS);
        // Precipitation character, which on radar is the most obvious difference between supercells: an HP
        // storm carries a broad rain shield that wraps its own hook, an LP one a tight high-based core.
        float character = storm.structureMode() == 1 ? 1.18F : storm.structureMode() == 2 ? 0.78F : 1.0F;
        float dbzSize = (float) Mth.clamp((storm.reflectivityDbz() - 30.0D) / 46.0D, 0.0D, 1.0D);
        return Mth.clamp(radiusPanel * character * (0.92F + dbzSize * 0.16F), 0.020F, 0.46F);
    }

    /**
     * A screen offset in pixels as panel units, given the pixel radius the range circle is drawn at.
     *
     * <p>It is what lets a GUI draw the same picture as a block panel: a panel measures in block faces and a
     * screen in pixels, and both are the range circle scaled, so this one conversion of the radius is all
     * that separates them. Without it a GUI has to re-derive its own placement, which is how the handheld
     * ended up plotting its own radar beside the weather radar's.
     */
    static float screenToPanel(double pixelOffset, double scopeRadiusPixels) {
        return (float) (pixelOffset / Math.max(1.0D, scopeRadiusPixels) * SCOPE_RADIUS);
    }

    /** The offset from the scope centre in pixels that a panel-unit offset lands on. */
    static float panelToScreen(double panelOffset, double scopeRadiusPixels) {
        return (float) (panelOffset / SCOPE_RADIUS * Math.max(1.0D, scopeRadiusPixels));
    }

    /** Whether a scope position is inside the round screen and far enough in from the panel's edge. */
    static boolean insidePanel(float x, float z) {
        return x >= 0.070F && x <= 0.930F && z >= 0.070F && z <= 0.930F;
    }

    /** Whether a scope position is inside the circle the range maps onto. */
    static boolean insideScope(float x, float z) {
        float dx = x - 0.5F;
        float dz = z - 0.5F;
        return dx * dx + dz * dz <= SCOPE_RADIUS * SCOPE_RADIUS;
    }

    /** The world-space motion a storm is tracking along, as a scope vector, clamped to {@code limit}. */
    static float motionTrackX(RadarStormSnapshot storm, int range, float limit) {
        return (float) Mth.clamp(storm.motionX() * 34.0D / Math.max(128.0D, range), -limit, limit);
    }

    static float motionTrackZ(RadarStormSnapshot storm, int range, float limit) {
        return (float) Mth.clamp(storm.motionZ() * 34.0D / Math.max(128.0D, range), -limit, limit);
    }

    static float motionAngle(RadarStormSnapshot storm) {
        double speed = Math.sqrt(storm.motionX() * storm.motionX() + storm.motionZ() * storm.motionZ());
        if (speed > 0.001D) {
            return (float) Math.atan2(storm.motionZ(), storm.motionX());
        }
        return (float) (storm.id() * 0.017453292519943295D);
    }

    // ------------------------------------------------------------------------------------------------
    // Vortex signatures: what the velocity product is telling the operator.
    // ------------------------------------------------------------------------------------------------

    /** No resolved rotation on this return. */
    static final int SIGNATURE_NONE = 0;
    /** A mesocyclone: organised rotation that has not tightened into a tornadic signature. */
    static final int SIGNATURE_MESO = 1;
    /** A tornadic vortex signature: a gate-to-gate couplet at a circulation that reaches the ground. */
    static final int SIGNATURE_TVS = 2;
    /** An elevated TVS: tornadic-strength rotation in the storm with nothing on the ground under it yet. */
    static final int SIGNATURE_ETVS = 3;

    /**
     * The gate-to-gate velocity difference, in m/s, that makes a couplet tornadic.
     *
     * <p>These are the operational tornado detection algorithm's own numbers, read as delta-V - the inbound and outbound
     * speeds either side of the vortex added together, which is twice the rotational speed {@code RadarService} reports.
     * A circulation that clears 36 m/s through its depth is a TVS; one already known to be on the ground needs only
     * 25 m/s at the lowest level. The old gates were set on the rotational speed instead, so only a violent tornado - over
     * 150 mph - ever showed one, where a real radar flags most tornadoes from the low end of the scale up.
     */
    static final float TVS_DELTA_V = 36.0F;
    static final float TVS_LOW_LEVEL_DELTA_V = 25.0F;

    /** The delta-V at which rotation reads as an organised mesocyclone rather than noise. */
    static final float MESO_DELTA_V = 20.0F;

    /** The structure strength that marks a mesocyclone on a storm whose couplet is not resolved at range. */
    static final float MESO_STRUCTURE_STRENGTH = 0.40F;

    /** The icon half-width and height, in panel units, so an icon is one size at every range. */
    static final float SIGNATURE_HALF = 0.021F;
    static final float SIGNATURE_HEIGHT = 0.036F;

    /**
     * The colours the WSR-88D's own overlays use: a red inverted triangle for a TVS, the same triangle in yellow for an
     * elevated one, and a yellow circle for a mesocyclone, each over a near-black outline.
     */
    static final int TVS_ICON_COLOR = 0xFFE3262C;
    static final int ETVS_ICON_COLOR = 0xFFF4DD3C;
    static final int MESO_ICON_COLOR = 0xFFF4DD3C;
    static final int SIGNATURE_OUTLINE_COLOR = 0xF00B0409;

    /**
     * Which signature a return carries.
     *
     * <p>Both displays ask here so a handheld and a radar block cannot disagree about which storm is flagged: the same
     * gate, the same icon, the same place on the scope - a TVS at the circulation, where the couplet in the velocity
     * product is, and an elevated TVS or a mesocyclone at the mesocyclone.
     */
    static int vortexSignatureTier(RadarStormSnapshot storm) {
        if (!mesocyclonic(storm)) {
            return SIGNATURE_NONE;
        }
        float deltaV = Math.abs((float) storm.rotationMps()) * 2.0F;
        if (storm.vortex()) {
            if (deltaV >= TVS_DELTA_V || (storm.hasConfirmedCirculation() && deltaV >= TVS_LOW_LEVEL_DELTA_V)) {
                return SIGNATURE_TVS;
            }
            return SIGNATURE_MESO;
        }
        if (deltaV >= TVS_DELTA_V) {
            return SIGNATURE_ETVS;
        }
        if (deltaV >= MESO_DELTA_V || storm.mesoStrength() >= MESO_STRUCTURE_STRENGTH) {
            return SIGNATURE_MESO;
        }
        return SIGNATURE_NONE;
    }

    /**
     * Whether a return's rotation is the kind the detection algorithms flag. They look for shear that is deep - through
     * several of the radar's tilts - and wide enough for the beam to resolve, which is a mesocyclone and the tornado under
     * it. A gustnado, a dust devil, a fire whirl or a landspout spins in the lowest few hundred metres, tens of metres
     * across: the beam overshoots or averages it away, and real radars almost never flag one. A waterspout counts only
     * when it hangs from a rotating storm; a fair-weather one is as shallow as a landspout.
     */
    static boolean mesocyclonic(RadarStormSnapshot storm) {
        return switch (storm.label()) {
            case "Gustnado", "Dust Devil", "Snow Devil", "Fire Whirl", "Lava Whirl", "Steam Devil", "Landspout", "Funnel Cloud" -> false;
            case "Waterspout" -> storm.mesoStrength() >= 0.20F || storm.structureStrength() >= 0.20F;
            default -> true;
        };
    }

    /** Whether a tier is drawn at the circulation rather than at the mesocyclone. */
    static boolean drawnAtCirculation(int tier) {
        return tier == SIGNATURE_TVS;
    }

    /** How large a tier's icon is. */
    static float signatureHalf(int tier) {
        return tier == SIGNATURE_MESO ? SIGNATURE_HALF * 0.80F : SIGNATURE_HALF;
    }

    /** The icon colour for a tier. */
    static int signatureColor(int tier) {
        return switch (tier) {
            case SIGNATURE_TVS -> TVS_ICON_COLOR;
            case SIGNATURE_ETVS -> ETVS_ICON_COLOR;
            default -> MESO_ICON_COLOR;
        };
    }

    /** The tag a display prints beside the icon, or an empty string for no signature. */
    static String signatureLabel(int tier) {
        return switch (tier) {
            case SIGNATURE_TVS -> "TVS";
            case SIGNATURE_ETVS -> "ETVS";
            case SIGNATURE_MESO -> "MESO";
            default -> "";
        };
    }

    /**
     * The size class a storm's damage path falls in, for the tag beside its name in a storm list: a
     * player can see at a glance that the return they are looking at is a rope rather than a monster,
     * which is the whole point of a storm keeping a temperament instead of re-rolling its width.
     *
     * <p>It reads the width the snapshot already carries, so it costs nothing on the wire.
     */
    static String sizeLabel(float damageWidthBlocks) {
        return TornadoSizeModel.classify(damageWidthBlocks,
                Config.effectiveMinDamageWidthBlocks(), Config.effectiveMaxDamageWidthBlocks()).label();
    }

    /**
     * The colour of a size tag: the ladder from the thin rope grey through to the monster red. ROPE is
     * named rather than left to the fallback because it is the band a storm reaches while it is forming
     * and while it is roping out - the two moments a player is most likely to be looking at it.
     */
    static int sizeColor(String label) {
        return switch (label) {
            case "ROPE" -> 0xFFB8C4C2;
            case "CONE" -> 0xFF8FB0AC;
            case "BARREL" -> 0xFFC9D6A0;
            case "WEDGE" -> 0xFFE0B080;
            case "MONSTER" -> 0xFFFF7A6A;
            default -> 0xFF9FB0AE;
        };
    }

    // ------------------------------------------------------------------------------------------------
    // The reflectivity product: a smooth field rather than per-cell blocks.
    // ------------------------------------------------------------------------------------------------

    /**
     * Where a storm's hook and inflow notch actually sit, as offsets from the echo centre in the storm's own
     * motion frame. {@code real} is false when the storm has no resolved structure, and the field then falls
     * back to the old procedural placement rather than pretending the storm centre is its mesocyclone.
     */
    record EchoAnchors(boolean real, float hookForward, float hookRight, float notchForward, float notchRight) {
        static final EchoAnchors PROCEDURAL = new EchoAnchors(false, 0.0F, 0.0F, 0.0F, 0.0F);
    }

    /**
     * The world X a hook echo curls around: the confirmed circulation, or the storm's own mesocyclone.
     *
     * <p>With a tornado confirmed the band wraps the tornado; without one it wraps the mesocyclone, which is
     * where the hook forms. Both displays ask here so they cannot wrap it around different things.
     */
    static double hookWorldX(RadarStormSnapshot storm) {
        return storm.hasConfirmedCirculation() ? storm.circulationX() : storm.mesoX();
    }

    /** The world Z counterpart of {@link #hookWorldX}. */
    static double hookWorldZ(RadarStormSnapshot storm) {
        return storm.hasConfirmedCirculation() ? storm.circulationZ() : storm.mesoZ();
    }

    /**
     * The anchors in the storm's own motion frame, from offsets the caller has already mapped into its units.
     *
     * <p>Only the rotation lives here: the caller subtracts the echo centre from its own coordinates, so the
     * same anchors serve a panel that measures in block faces and a screen that measures in pixels.
     */
    static EchoAnchors anchorsFor(RadarStormSnapshot storm, float hookDx, float hookDz,
                                  float notchDx, float notchDz, float cos, float sin) {
        if (!storm.hasStructure()) {
            return EchoAnchors.PROCEDURAL;
        }
        return new EchoAnchors(true,
                hookDx * cos + hookDz * sin, -hookDx * sin + hookDz * cos,
                notchDx * cos + notchDz * sin, -notchDx * sin + notchDz * cos);
    }

    /**
     * Reflectivity at a point of the echo, given in the storm's own motion frame.
     *
     * <p>{@code gridX}/{@code gridZ} are the integer lattice coordinates the point was sampled from: they
     * seed the noise, so the texture is stable per storm instead of crawling as the echo drifts.
     */
    static double reflectivityAt(RadarStormSnapshot storm, float forward, float right, float size,
                                 EchoAnchors anchors, int gridX, int gridZ) {
        return reflectivityAt(storm, forward, right, size, anchors);
    }

    /**
     * The field itself, at any point rather than at a lattice cell.
     *
     * <p>The texture is smooth fractal noise anchored in the storm's own frame, and the outline is pushed around by
     * a second, broader noise. A real echo is a mass of embedded cells with a torn edge, and a smooth ellipse with
     * per-cell static on it read as concentric stripes the moment it was drawn finely enough to see.
     */
    static double reflectivityAt(RadarStormSnapshot storm, float forward, float right, float size, EchoAnchors anchors) {
        float u = forward / Math.max(size, 1.0E-4F);
        float v = right / Math.max(size, 1.0E-4F);
        forward += echoNoise(storm.id() ^ 0x31L, u * 0.9F, v * 0.9F, 2) * size * 0.42F;
        right += echoNoise(storm.id() ^ 0x77L, u * 0.9F + 7.0F, v * 0.9F, 2) * size * 0.42F;
        float cells = echoNoise(storm.id(), u * 1.7F, v * 1.7F, 3);
        double core = Math.pow((forward - size * 0.20F) / (size * 1.42F), 2.0D) + Math.pow(right / (size * 0.76F), 2.0D);
        double dbz = storm.reflectivityDbz() - core * 26.0D + cells * 9.0D;
        // Strengths come from the storm's own structure where there is one, so the forward-flank core and the
        // hail core sit and read where the storm actually has them. The old fixed shape is the fallback, and
        // the scaling below is tuned so that fallback value (0.55 ffd) reproduces it exactly.
        double ffd = storm.hasStructure() ? storm.ffdStrength() : 0.55D;
        double hail = storm.hasStructure() ? storm.hailStrength() : storm.reflectivityDbz() >= 48.0D ? 0.70D : 0.0D;
        if (storm.hookEcho() || storm.severity() >= 3) {
            // Fallback values (ffd 0.55, hail 0.70) are chosen so these reproduce the old fixed shape exactly;
            // a structured storm instead gets its forward-flank core where its own ffd puts it.
            double flankOffset = size * (0.208D + 0.24D * ffd);
            double forwardFlank = Math.pow((forward - flankOffset) / (size * 1.08F), 2.0D) + Math.pow((right + size * 0.22F) / (size * 0.56F), 2.0D);
            dbz = Math.max(dbz, storm.reflectivityDbz() + (-14.6D + 12.0D * ffd) - forwardFlank * 17.0D + cells * 6.0D);
            double hailCore = Math.pow((forward - size * 0.40F) / (size * 0.38F), 2.0D) + Math.pow((right + size * 0.08F) / (size * 0.26F), 2.0D);
            if (hailCore < 1.0D && hail > 0.25D) {
                dbz = Math.max(dbz, storm.reflectivityDbz() + (1.0D - hailCore) * (2.3D + 11.0D * hail));
            }
            double weakEchoRegion = Math.pow((forward + size * 0.02F) / (size * 0.38F), 2.0D) + Math.pow((right - size * 0.26F) / (size * 0.32F), 2.0D);
            if (weakEchoRegion < 1.0D) {
                dbz -= (1.0D - weakEchoRegion) * 18.0D;
            }
        }
        if (forward > size * 0.25F) {
            double vNotch = Math.max(0.0D, 1.0D - Math.abs(right) / Math.max(0.001D, (forward - size * 0.15F) * 0.48D));
            dbz -= vNotch * 14.0D;
        }
        if (storm.vortex() || storm.hookEcho()) {
            double notchF = anchors.real() ? anchors.notchForward() : -size * 0.24F;
            double notchR = anchors.real() ? anchors.notchRight() : size * 0.28F;
            double notch = Math.pow((forward - notchF) / (size * 0.42F), 2.0D) + Math.pow((right - notchR) / (size * 0.34F), 2.0D);
            if (notch < 1.0D) {
                // The inflow notch is how a supercell shows its rotation; a high-precipitation storm wraps
                // rain around its own notch, which is exactly how it hides its tornado from a radar.
                dbz -= (1.0D - notch) * notchDepth(storm);
            }
            double hookF = forward - (anchors.real() ? anchors.hookForward() : -size * 0.78F);
            double hookR = right - (anchors.real() ? anchors.hookRight() : size * 0.56F);
            double hookRadius = Math.sqrt(hookF * hookF + hookR * hookR);
            double hookAngle = Math.atan2(hookR, hookF);
            if (hookRadius > size * 0.35D && hookRadius < size * 0.86D && hookAngle > -2.80D && hookAngle < 0.85D) {
                double band = 1.0D - Math.abs(hookRadius - size * 0.58D) / (size * 0.25D);
                dbz = Math.max(dbz, storm.reflectivityDbz() - 10.0D + Math.max(0.0D, band) * hookGain(storm));
            }
            // The debris ball sits on the tornado itself. It used to sit at a guessed spot past the hook,
            // which put the one unmistakable tornado signature on radar somewhere the tornado was not.
            if (storm.vortex() && storm.reflectivityDbz() >= 48.0D) {
                double ballF = forward - (anchors.real() ? anchors.hookForward() : -size * 1.10F);
                double ballR = right - (anchors.real() ? anchors.hookRight() : size * 0.82F);
                double debris = Math.sqrt(ballF * ballF + ballR * ballR);
                if (debris < size * 0.20D) {
                    dbz = Math.max(dbz, 67.0D + echoNoise(storm.id() ^ 0xD36BL, u * 9.0F, v * 9.0F, 1) * 8.0D);
                }
            }
        }
        return dbz;
    }

    /** How deeply the inflow notch cuts into the echo. Crisp for an LP storm, mostly rained in for an HP one. */
    private static double notchDepth(RadarStormSnapshot storm) {
        return switch (storm.structureMode()) {
            case 1 -> 13.0D;
            case 2 -> 40.0D;
            default -> 32.0D;
        };
    }

    /** How strong the hook band reads, which for the same reason is weakest on a rain-wrapped storm. */
    private static double hookGain(RadarStormSnapshot storm) {
        return switch (storm.structureMode()) {
            case 1 -> 9.0D;
            case 2 -> 20.0D;
            default -> 16.0D;
        };
    }

    /**
     * The colour a reflectivity value is drawn with. Interpolated between ramp stops rather than snapped to the
     * nearest band, because the discrete bands are what made a smooth storm look like a contour map.
     *
     * <p>Its opacity is what carries a return's strength: anything at or above the floor is drawn, faint, and
     * it firms up over the next few dBZ.
     */
    static int sampleColor(float dbz) {
        float fade = Mth.clamp((dbz - ECHO_FLOOR_DBZ) / ECHO_FADE_DBZ, 0.0F, 1.0F);
        return withAlpha(rampColor(Math.max(dbz, ECHO_FLOOR_DBZ)),
                ECHO_MIN_ALPHA + Math.round((ECHO_ALPHA - ECHO_MIN_ALPHA) * fade));
    }

    static int rampColor(float dbz) {
        if (dbz <= DBZ_STOPS[0]) {
            return DBZ_COLORS[0];
        }
        for (int i = 1; i < DBZ_STOPS.length; i++) {
            if (dbz <= DBZ_STOPS[i]) {
                return lerpColor(DBZ_COLORS[i - 1], DBZ_COLORS[i], (dbz - DBZ_STOPS[i - 1]) / (DBZ_STOPS[i] - DBZ_STOPS[i - 1]));
            }
        }
        return DBZ_COLORS[DBZ_COLORS.length - 1];
    }

    // ------------------------------------------------------------------------------------------------
    // The velocity and risk products.
    // ------------------------------------------------------------------------------------------------

    /**
     * Radial velocity at an offset from the echo centre, in the shape the product has always used: the
     * storm's own storm-relative flow, falling away with distance, overridden inside a couplet by the
     * circulation's rotation speed.
     */
    static float radialVelocityAt(RadarStormSnapshot storm, float px, float pz, float size,
                                  float beamX, float beamZ, float crossX, float crossZ) {
        float dist = Mth.sqrt(px * px + pz * pz);
        if (dist > size * 1.45F) {
            return 0.0F;
        }
        float velocity = (float) storm.radialVelocityMps() * Mth.clamp(1.0F - dist / (size * 1.6F), 0.0F, 1.0F);
        // A mesocyclone reads as a velocity couplet even before it produces a tornado - that is the
        // signature the velocity product exists to show, and it used to be drawn only for a confirmed
        // vortex, so a strongly rotating supercell looked like a featureless blob of inbound air.
        if (storm.vortex() || storm.mesoStrength() >= 0.35F) {
            float acrossBeam = px * crossX + pz * crossZ;
            float alongBeam = px * beamX + pz * beamZ;
            float coupletFalloff = Mth.clamp(1.0F - (Math.abs(alongBeam) / (size * 0.82F) + Math.abs(acrossBeam) / (size * 1.12F)) * 0.54F, 0.0F, 1.0F);
            if (coupletFalloff > 0.05F) {
                double strength = storm.vortex() ? storm.rotationMps() : 18.0D + storm.mesoStrength() * 46.0D;
                velocity = Math.signum(acrossBeam == 0.0F ? 0.001F : acrossBeam) * (float) Math.max(12.0D, strength) * coupletFalloff;
            }
        }
        return velocity;
    }

    /**
     * The colour a radial velocity is drawn with.
     *
     * <p>Its opacity carries the motion, exactly as the reflectivity return's does and for the same reason:
     * the program picks which face to draw from the vertex alpha, so alpha is the only channel a fade can
     * run along, and {@link #ECHO_MIN_ALPHA} is the floor it may not cross.
     */
    static int velocityColor(float radialMotion) {
        float magnitude = Math.abs(radialMotion);
        float strength = Mth.clamp(magnitude / 45.0F, 0.0F, 1.0F);
        float fade = Mth.clamp((magnitude - VELOCITY_FLOOR_MPS) / VELOCITY_FADE_MPS, 0.0F, 1.0F);
        int tint = radialMotion < 0.0F
                ? lerpColor(0x70D878, 0x00FF27, strength)
                : lerpColor(0xDA6A6A, 0xFF1414, strength);
        return withAlpha(tint, ECHO_MIN_ALPHA + Math.round((ECHO_ALPHA - ECHO_MIN_ALPHA) * fade));
    }

    /** The SPC risk a storm's hazard shades, from the categorical level it was issued. */
    static int riskColor(RadarStormSnapshot storm) {
        return switch (Mth.clamp(storm.severity(), 0, SpcRisk.maxSeverity())) {
            case 8 -> withAlpha(0xE9D9FF, 236);
            case 7 -> withAlpha(0x9B1FE0, 230);
            case 6 -> withAlpha(0xFF5BFF, 226);
            case 5 -> withAlpha(0xE83B2F, 220);
            case 4 -> withAlpha(0xF08E24, 214);
            case 3 -> withAlpha(0xF5D742, 206);
            case 2 -> withAlpha(0x48D55D, 198);
            case 1 -> withAlpha(0x8FD05F, 188);
            default -> withAlpha(0x40505A, 160);
        };
    }

    // ------------------------------------------------------------------------------------------------
    // The products drawn from what is really there: the storm's own rain field, and the wind in it.
    // ------------------------------------------------------------------------------------------------

    /** The storm's precipitation field, unpacked once for a draw, or null for a return with none. */
    static PrecipitationField.Geometry fieldOf(RadarStormSnapshot storm) {
        return storm.hasPrecipitationField() ? RadarPrecipitation.geometry(storm.precipitation()) : null;
    }

    /** How far from the storm's centre its field reaches, in blocks: the trailing rain runs about two radii downwind. */
    static double fieldReachBlocks(PrecipitationField.Geometry field) {
        return Math.max(16.0D, field.radius()) * 2.5D;
    }

    /**
     * Reflectivity at a world position, in dBZ, read off the rain that is really falling there.
     *
     * <p>The field is the one the rain in the world is drawn from, so the forward-flank core, the hook wrapping the tornado
     * and the clear notch are where the curtains and the clear slot actually are. Two things are laid over it that the
     * field does not carry: the ragged outline and embedded cells of a real echo - the field is read through a small warp,
     * so the parts keep their places and only their edges tear - and the two returns that are not rain at all, the hail
     * core beside the updraft and the debris ball lofted by a strong tornado.
     */
    static double reflectivityAtWorld(RadarStormSnapshot storm, PrecipitationField.Geometry field, double worldX, double worldZ) {
        float[] packed = storm.precipitation();
        double dx = worldX - storm.x();
        double dz = worldZ - storm.z();
        double forward = dx * RadarPrecipitation.forwardX(packed) + dz * RadarPrecipitation.forwardZ(packed);
        double right = dx * RadarPrecipitation.rightX(packed) + dz * RadarPrecipitation.rightZ(packed);
        double radius = Math.max(8.0D, field.radius());
        float u = (float) (forward / radius);
        float v = (float) (right / radius);
        double warpForward = forward + echoNoise(storm.id() ^ 0x31L, u * 2.4F, v * 2.4F, 3) * radius * 0.11D;
        double warpRight = right + echoNoise(storm.id() ^ 0x77L, u * 2.4F + 7.0F, v * 2.4F, 3) * radius * 0.11D;
        double density = PrecipitationField.densityAtLocal(field, warpForward, warpRight);
        double dbz = ECHO_FLOOR_DBZ - 1.0D;
        if (density > 0.006D) {
            double peak = Mth.clamp(storm.reflectivityDbz() + 4.0D, 38.0D, 66.0D);
            double cells = echoNoise(storm.id(), u * 3.6F, v * 3.6F, 3);
            // Most of a real echo is light and moderate rain round a much smaller heavy core, so the field is mapped with
            // contrast: thin rain reads green, the bulk yellow, and only the densest of the core reaches the reds.
            dbz = 10.0D + (peak - 10.0D) * Math.pow(density, 1.15D) + cells * (4.0D + 6.0D * density);
            if (storm.hailStrength() > 0.25F) {
                double hailForward = field.coreForward() + (field.mesoForward() - field.coreForward()) * 0.38D;
                double hailRight = field.coreRight() + (field.mesoRight() - field.coreRight()) * 0.38D;
                double q = (square(forward - hailForward) + square(right - hailRight)) / square(radius * 0.17D);
                if (q < 1.0D) {
                    dbz += (1.0D - q) * (5.0D + 11.0D * storm.hailStrength()) * Math.min(1.0D, density * 2.0D);
                }
            }
        }
        if (storm.vortex() && storm.windMph() >= 120.0D) {
            double ball = Math.max(6.0D, storm.width() * 0.60D);
            double q = (square(worldX - storm.circulationX()) + square(worldZ - storm.circulationZ())) / square(ball);
            if (q < 1.0D) {
                double debris = 50.0D + Math.min(16.0D, (storm.windMph() - 120.0D) / 9.0D)
                        + echoNoise(storm.id() ^ 0xD36BL, u * 14.0F, v * 14.0F, 1) * 5.0D;
                dbz = Math.max(dbz, debris - q * 8.0D);
            }
        }
        return dbz;
    }

    /**
     * Radial velocity at a world position, in m/s, outbound positive - or NaN where there is no echo to measure it in.
     *
     * <p>A velocity product is the wind toward or away from the radar, so this builds the wind and takes its component
     * along the beam. The storm moves in its environment's wind; low-level air is drawn in from the forward right through
     * the notch toward the mesocyclone; the rear-flank downdraft throws air out round the back of it, and the
     * forward-flank core's cold air spreads out of the rain. Over that turns the mesocyclone, broad and a sixth of the
     * storm across, and inside it the tornado, tight and fast. Taken along the beam, a vortex is a couplet - inbound on
     * one side, outbound on the other, across the beam - and a tornado's is gate to gate, right where the tornado is.
     * Where there is no rain the radar has nothing to measure, so the product is blank there, except at the tornado,
     * where the debris is the target.
     */
    static float radialVelocityAtWorld(RadarStormSnapshot storm, PrecipitationField.Geometry field, double worldX, double worldZ,
                                       double radarX, double radarZ) {
        double beamX = worldX - radarX;
        double beamZ = worldZ - radarZ;
        double beam = Math.sqrt(beamX * beamX + beamZ * beamZ);
        if (beam < 1.0D) {
            return Float.NaN;
        }
        beamX /= beam;
        beamZ /= beam;
        float[] packed = storm.precipitation();
        double forwardX = RadarPrecipitation.forwardX(packed);
        double forwardZ = RadarPrecipitation.forwardZ(packed);
        double rightX = RadarPrecipitation.rightX(packed);
        double rightZ = RadarPrecipitation.rightZ(packed);
        double dx = worldX - storm.x();
        double dz = worldZ - storm.z();
        double forward = dx * forwardX + dz * forwardZ;
        double right = dx * rightX + dz * rightZ;
        double radius = Math.max(8.0D, field.radius());
        double density = PrecipitationField.densityAtLocal(field, forward, right);
        double toVortex = storm.vortex()
                ? Math.sqrt(square(worldX - storm.circulationX()) + square(worldZ - storm.circulationZ()))
                : Double.MAX_VALUE;
        double vortexCore = Math.max(5.0D, storm.width() * 0.5D);
        if (density < 0.01D && toVortex > vortexCore * 3.0D) {
            return Float.NaN;
        }
        double sign = storm.rotationMps() < 0.0D ? -1.0D : 1.0D;
        // The environment's wind, along the storm's motion.
        double environment = 12.0D + storm.structureStrength() * 6.0D;
        double alongForward = environment;
        double alongRight = 0.0D;
        double fromMesoForward = forward - field.mesoForward();
        double fromMesoRight = right - field.mesoRight();
        double mesoDistance = Math.max(1.0D, Math.hypot(fromMesoForward, fromMesoRight));
        // Inflow through the notch, toward the mesocyclone.
        double inflow = (8.0D + 10.0D * storm.mesoStrength())
                * Math.exp(-(square(forward - field.notchForward()) + square(right - field.notchRight())) / square(radius * 0.45D));
        alongForward -= fromMesoForward / mesoDistance * inflow;
        alongRight -= fromMesoRight / mesoDistance * inflow;
        // The rear-flank downdraft, out from behind the mesocyclone.
        double rearFlank = (6.0D + 16.0D * storm.structureStrength())
                * Math.exp(-(square(forward - field.rearFlankForward()) + square(right - field.rearFlankRight())) / square(radius * 0.40D));
        alongForward += fromMesoForward / mesoDistance * rearFlank;
        alongRight += fromMesoRight / mesoDistance * rearFlank;
        // The forward-flank core's outflow.
        double fromCoreForward = forward - field.coreForward();
        double fromCoreRight = right - field.coreRight();
        double coreDistance = Math.max(1.0D, Math.hypot(fromCoreForward, fromCoreRight));
        double outflow = 5.0D * field.coreStrength() * Math.exp(-square(coreDistance) / square(radius * 0.55D));
        alongForward += fromCoreForward / coreDistance * outflow;
        alongRight += fromCoreRight / coreDistance * outflow;
        double windX = alongForward * forwardX + alongRight * rightX;
        double windZ = alongForward * forwardZ + alongRight * rightZ;
        // The mesocyclone, as a Rankine vortex.
        double mesoX = worldX - storm.mesoX();
        double mesoZ = worldZ - storm.mesoZ();
        double mesoRadius = Math.max(1.0D, Math.sqrt(mesoX * mesoX + mesoZ * mesoZ));
        double mesoCore = radius * 0.16D;
        double mesoPeak = 8.0D + storm.mesoStrength() * 26.0D;
        // A real vortex's wind falls off faster outside its core than a pure 1/r, which is what keeps a couplet a couplet
        // rather than the whole storm. Read as 1/r the two vortices' outer flow split the entire echo into green and red.
        double mesoSpeed = mesoRadius <= mesoCore ? mesoPeak * mesoRadius / mesoCore : mesoPeak * Math.pow(mesoCore / mesoRadius, 0.9D);
        windX += -mesoZ / mesoRadius * mesoSpeed * sign;
        windZ += mesoX / mesoRadius * mesoSpeed * sign;
        // The tornado, tight inside it.
        if (storm.vortex() && toVortex > 0.5D) {
            double tornadoX = worldX - storm.circulationX();
            double tornadoZ = worldZ - storm.circulationZ();
            double peak = Math.abs(storm.rotationMps());
            double speed = toVortex <= vortexCore ? peak * toVortex / vortexCore
                    : peak * Math.pow(vortexCore / toVortex, 1.6D) * Mth.clamp(1.0D - (toVortex - vortexCore * 6.0D) / (vortexCore * 4.0D), 0.0D, 1.0D);
            windX += -tornadoZ / toVortex * speed * sign;
            windZ += tornadoX / toVortex * speed * sign;
        }
        double radial = windX * beamX + windZ * beamZ;
        radial += echoNoise(storm.id() ^ 0x5EEDL, (float) (worldX / 40.0D), (float) (worldZ / 40.0D), 2) * 2.5D;
        return (float) radial;
    }

    private static double square(double value) {
        return value * value;
    }

    // ------------------------------------------------------------------------------------------------
    // A squall line: a long, thin band rather than a cell.
    // ------------------------------------------------------------------------------------------------

    /** How far from the line's middle its echo reaches, in blocks. */
    static double lineReachBlocks(RadarStormSnapshot storm) {
        float[] p = storm.precipitation();
        return Math.hypot(p[2] - p[0], p[3] - p[1]) * 0.5D + p[4] * 4.6D + p[5];
    }

    /**
     * Where a point is against the line: how far along it (0 to 1), how far ahead of its leading edge in blocks - the edge
     * bowed forward in the middle - and how far past its ends.
     */
    private static double[] lineFrame(RadarStormSnapshot storm, double worldX, double worldZ) {
        float[] p = storm.precipitation();
        double lineX = p[2] - p[0];
        double lineZ = p[3] - p[1];
        double lengthSqr = Math.max(1.0D, lineX * lineX + lineZ * lineZ);
        double length = Math.sqrt(lengthSqr);
        double t = ((worldX - p[0]) * lineX + (worldZ - p[1]) * lineZ) / lengthSqr;
        double along = Mth.clamp(t, 0.0D, 1.0D);
        double onLineX = p[0] + lineX * along;
        double onLineZ = p[1] + lineZ * along;
        double ahead = (worldX - onLineX) * p[6] + (worldZ - onLineZ) * p[7];
        double beyond = t < 0.0D ? -t * length : t > 1.0D ? (t - 1.0D) * length : 0.0D;
        // The same bow the world's rain, shelf and gust front are laid along, so the line on the scope is where the line is.
        double bow = p[5] * Math.sin(Math.PI * along);
        return new double[] {along, ahead - bow, beyond};
    }

    /**
     * A squall line's reflectivity at a world position.
     *
     * <p>A narrow convective line of heavy cells strung along its length, sharpest on its leading edge; behind it a weaker
     * transition zone, and then the broad, light trailing stratiform rain that fills the deck for a long way back. Behind
     * the apex of a bowing line the rear inflow jet cuts a notch into that stratiform rain, and at the line's cyclonic end
     * the rain curls round behind it into a comma head.
     */
    static double lineReflectivityAtWorld(RadarStormSnapshot storm, double worldX, double worldZ) {
        float[] p = storm.precipitation();
        double[] frame = lineFrame(storm, worldX, worldZ);
        double along = frame[0];
        double ahead = frame[1];
        double beyond = frame[2];
        double thickness = Math.max(16.0D, p[4]);
        float u = (float) (worldX / thickness);
        float v = (float) (worldZ / thickness);
        double cells = echoNoise(storm.id(), u * 1.3F, v * 1.3F, 3);
        double strung = echoNoise(storm.id() ^ 0x1E5L, (float) (along * 9.0D), 0.5F, 2);
        double peak = Mth.clamp(storm.reflectivityDbz() + 6.0D, 45.0D, 66.0D);
        double core = thickness * 0.30D;
        double edge = ahead > 0.0D ? ahead / (core * 0.55D) : ahead / core;
        double convective = (peak + strung * 7.0D) * Math.exp(-edge * edge);
        double behind = -ahead;
        double bowShare = Mth.clamp(p[5] / 130.0D, 0.0D, 1.0D);
        double stratiform = 0.0D;
        double transition = core * 0.8D;
        if (behind > transition) {
            // Heaviest a little way behind the transition zone - the bright band, where the snow falling out of the deck
            // melts - and lighter from there back. The region is deepest behind the middle of the line, where the rear
            // inflow feeds it, and thins toward the ends, with a ragged back edge: a lens behind the line, not a box.
            double length = Math.hypot(p[2] - p[0], p[3] - p[1]);
            double depth = Math.min(thickness * 2.4D, length * 0.32D) * Math.pow(Math.sin(Math.PI * along), 0.6D)
                    * (0.85D + 0.30D * echoNoise(storm.id() ^ 0x57A7L, (float) (along * 5.0D), 0.25F, 2));
            double rise = smoothstepD(Mth.clamp((behind - transition) / (thickness * 0.55D), 0.0D, 1.0D));
            double into = Math.max(0.0D, behind - transition - thickness * 0.55D) / Math.max(1.0D, depth);
            double fall = into < 1.0D ? 1.0D - 0.42D * into : 0.58D * Math.exp(-square((into - 1.0D) * 2.6D));
            stratiform = (34.0D + cells * 4.0D) * rise * fall;
            double notch = Math.exp(-square((along - 0.5D) / 0.12D) - square((behind - thickness * 1.2D) / (thickness * 0.6D)));
            stratiform -= notch * 16.0D * bowShare;
        }
        // The comma head, behind the cyclonic end: the end on the left of the line's track, which is the northern end of a
        // line moving east. Left of a heading (x, z) is (z, -x), with north at negative z.
        boolean startIsCyclonic = (p[0] - p[2]) * p[7] - (p[1] - p[3]) * p[6] > 0.0D;
        double endX = startIsCyclonic ? p[0] : p[2];
        double endZ = startIsCyclonic ? p[1] : p[3];
        double headX = endX - p[6] * thickness * 1.5D;
        double headZ = endZ - p[7] * thickness * 1.5D;
        double head = (30.0D + cells * 5.0D) * Math.exp(-(square(worldX - headX) + square(worldZ - headZ)) / square(thickness * 1.7D));
        double endFade = Math.exp(-square(beyond / (thickness * 1.2D)));
        return Math.max(Math.max(convective, stratiform) * endFade, head) + cells * 3.0D;
    }

    /**
     * A squall line's radial velocity at a world position, or NaN where it has no echo. The line moves with its
     * environment's wind; its cold outflow surges forward under the leading edge; and behind a bowing line the rear inflow
     * jet runs forward into it, strongest behind the apex of the bow - which is what drives the bow in the first place.
     */
    static float lineVelocityAtWorld(RadarStormSnapshot storm, double worldX, double worldZ, double radarX, double radarZ) {
        if (lineReflectivityAtWorld(storm, worldX, worldZ) < ECHO_FLOOR_DBZ) {
            return Float.NaN;
        }
        double beamX = worldX - radarX;
        double beamZ = worldZ - radarZ;
        double beam = Math.sqrt(beamX * beamX + beamZ * beamZ);
        if (beam < 1.0D) {
            return Float.NaN;
        }
        float[] p = storm.precipitation();
        double[] frame = lineFrame(storm, worldX, worldZ);
        double along = frame[0];
        double ahead = frame[1];
        double thickness = Math.max(16.0D, p[4]);
        double bowShare = Mth.clamp(p[5] / 130.0D, 0.0D, 1.0D);
        double environment = 14.0D;
        double outflow = 16.0D * Math.exp(-square((ahead + thickness * 0.09D) / (thickness * 0.6D)));
        double rearInflow = 22.0D * bowShare * Math.exp(-square((along - 0.5D) / 0.18D))
                * Math.exp(-square((-ahead - thickness * 1.6D) / (thickness * 1.2D)));
        double speed = environment + outflow + rearInflow;
        double radial = (p[6] * speed * beamX + p[7] * speed * beamZ) / beam;
        radial += echoNoise(storm.id() ^ 0x5EEDL, (float) (worldX / 40.0D), (float) (worldZ / 40.0D), 2) * 2.5D;
        return (float) radial;
    }

    private static double smoothstepD(double t) {
        return t * t * (3.0D - 2.0D * t);
    }

    // ------------------------------------------------------------------------------------------------
    // Colours and noise.
    // ------------------------------------------------------------------------------------------------

    static int lerpColor(int a, int b, float t) {
        int ar = a >> 16 & 255;
        int ag = a >> 8 & 255;
        int ab = a & 255;
        int br = b >> 16 & 255;
        int bg = b >> 8 & 255;
        int bb = b & 255;
        int r = Mth.clamp(Math.round(Mth.lerp(t, ar, br)), 0, 255);
        int g = Mth.clamp(Math.round(Mth.lerp(t, ag, bg)), 0, 255);
        int bl = Mth.clamp(Math.round(Mth.lerp(t, ab, bb)), 0, 255);
        return r << 16 | g << 8 | bl;
    }

    static int withAlpha(int rgb, int alpha) {
        return Mth.clamp(alpha, 0, 255) << 24 | rgb & 0xFFFFFF;
    }

    /**
     * A colour for the pass-through path, at the opacity its author asked for.
     *
     * <p>The program chooses its pass on the vertex alpha, so anything that must keep the colour it was
     * handed has to stay above {@link RadarShader#PASS_THROUGH_ALPHA}; the map lines were all written well
     * below it, which is why none of them were ever drawn as lines. The alpha is lifted to
     * {@link #OVERLAY_ALPHA} and the colour dimmed by the same factor, so the line lands on screen exactly
     * as faint as it was asked to be while still being itself.
     */
    static int overlay(int rgb, float opacity) {
        return withAlpha(scaleColor(rgb, Mth.clamp(opacity / OVERLAY_ALPHA, 0.0F, 1.0F)), Math.round(OVERLAY_ALPHA * 255.0F));
    }

    /** A colour's RGB scaled toward black, leaving its alpha alone. */
    static int scaleColor(int rgb, float scale) {
        int r = Mth.clamp(Math.round((rgb >> 16 & 255) * scale), 0, 255);
        int g = Mth.clamp(Math.round((rgb >> 8 & 255) * scale), 0, 255);
        int b = Mth.clamp(Math.round((rgb & 255) * scale), 0, 255);
        return r << 16 | g << 8 | b;
    }

    /** Smooth fractal noise in [-1, 1] over a storm's own frame: octaves of {@link #pixelNoise}, interpolated. */
    static float echoNoise(long stormId, float u, float v, int octaves) {
        float sum = 0.0F;
        float amplitude = 0.56F;
        for (int octave = 0; octave < octaves; octave++) {
            int ix = Mth.floor(u);
            int iz = Mth.floor(v);
            float fx = u - ix;
            float fz = v - iz;
            fx = fx * fx * (3.0F - 2.0F * fx);
            fz = fz * fz * (3.0F - 2.0F * fz);
            long seed = stormId + octave * 1031L;
            sum += amplitude * Mth.lerp(fz,
                    Mth.lerp(fx, pixelNoise(seed, ix, iz), pixelNoise(seed, ix + 1, iz)),
                    Mth.lerp(fx, pixelNoise(seed, ix, iz + 1), pixelNoise(seed, ix + 1, iz + 1)));
            u = u * 2.17F + 3.1F;
            v = v * 2.17F + 1.7F;
            amplitude *= 0.54F;
        }
        return sum;
    }

    /** A stable value in [-1, 1] for a lattice point of a storm's field, seeded by the storm. */
    static float pixelNoise(long stormId, int x, int z) {
        long n = stormId * 0x9E3779B97F4A7C15L + x * 0xBF58476D1CE4E5B9L + z * 0x94D049BB133111EBL;
        n ^= n >>> 30;
        n *= 0xBF58476D1CE4E5B9L;
        n ^= n >>> 27;
        n *= 0x94D049BB133111EBL;
        n ^= n >>> 31;
        return ((n & 0xFFFF) / 32767.5F) - 1.0F;
    }
}
