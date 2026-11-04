package com.imbet.client;

import com.imbet.Config;
import com.imbet.radar.RadarStormSnapshot;
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
    private static final float[] DBZ_STOPS = { 5.0F, 10.0F, 18.0F, 27.0F, 36.0F, 44.0F, 51.0F, 59.0F, 67.0F, 74.0F };
    private static final int[] DBZ_COLORS = { 0x3A6EE8, 0x04E9E7, 0x02FD02, 0x008E00, 0xFDF802, 0xFD9500, 0xFD0000, 0xBC0000, 0xF800FD, 0xFDFDFD };
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
    /** A tornadic vortex signature: a gate-to-gate couplet strong enough to be marked as tornadic. */
    static final int SIGNATURE_TVS = 2;

    /**
     * The couplet strength, in m/s, at which the scope raises a tornadic vortex signature.
     *
     * <p>Sized against what {@code RadarService} actually reports, so the gates fall where the weather does:
     * a vortex's couplet is its own wind times the rotational factor (about 0.46), so 32 m/s is a gate-to-gate
     * shear of roughly 155 mph and 19 m/s - the mesocyclone gate - is around 90 mph. A real TVS algorithm is
     * looking for a couplet of that order in the mid-levels before it flags one, which is why it means a
     * genuinely violent circulation rather than any tornado at all.
     */
    static final float TVS_COUPLET_MPS = 32.0F;

    /**
     * The lower couplet a confirmed tornado needs to be marked as a TVS.
     *
     * <p>A tornado is already known to be on the ground, so the signature is a warning aid rather than a
     * maybe: from about 115 mph the couplet is unmistakable and the icon is what an operator would be looking
     * at. Below it the circulation stays a mesocyclone on the scope, which is the honest reading - a weak
     * tornado's couplet can genuinely be hard to separate from its parent mesocyclone.
     */
    static final float TVS_CONFIRMED_COUPLET_MPS = 24.0F;

    /** The couplet at which rotation reads as an organised mesocyclone rather than noise. */
    static final float MESO_COUPLET_MPS = 19.0F;

    /** The structure strength that marks a mesocyclone on a storm whose couplet is not resolved at range. */
    static final float MESO_STRUCTURE_STRENGTH = 0.45F;

    /** The icon half-width and height, in panel units, so an icon is one size at every range. */
    static final float SIGNATURE_HALF = 0.021F;
    static final float SIGNATURE_HEIGHT = 0.036F;

    /** Magenta for a tornado signature, amber for a mesocyclone, over a near-black outline. */
    static final int TVS_ICON_COLOR = 0xFFF13CE8;
    static final int MESO_ICON_COLOR = 0xFFF2C24A;
    static final int SIGNATURE_OUTLINE_COLOR = 0xF00B0409;

    /**
     * Which signature a return carries: none, a mesocyclone, or a tornadic vortex signature.
     *
     * <p>Both displays ask here so a handheld and a radar block cannot disagree about which storm is
     * flagged: the same gate, the same icon, the same place on the scope.
     */
    static int vortexSignatureTier(RadarStormSnapshot storm) {
        float couplet = Math.abs((float) storm.rotationMps());
        if (couplet >= TVS_COUPLET_MPS
                || (storm.hasConfirmedCirculation() && couplet >= TVS_CONFIRMED_COUPLET_MPS)) {
            return SIGNATURE_TVS;
        }
        if (couplet >= MESO_COUPLET_MPS || storm.mesoStrength() >= MESO_STRUCTURE_STRENGTH) {
            return SIGNATURE_MESO;
        }
        return SIGNATURE_NONE;
    }

    /** How large a tier's icon is: a TVS is the more serious marker, so it is the larger one. */
    static float signatureHalf(int tier) {
        return tier == SIGNATURE_TVS ? SIGNATURE_HALF : SIGNATURE_HALF * 0.72F;
    }

    /** The icon colour for a tier. */
    static int signatureColor(int tier) {
        return tier == SIGNATURE_TVS ? TVS_ICON_COLOR : MESO_ICON_COLOR;
    }

    /** The tag a display prints beside the icon, or an empty string for no signature. */
    static String signatureLabel(int tier) {
        return switch (tier) {
            case SIGNATURE_TVS -> "TVS";
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
        double core = Math.pow((forward - size * 0.20F) / (size * 1.42F), 2.0D) + Math.pow(right / (size * 0.76F), 2.0D);
        double dbz = storm.reflectivityDbz() - core * 26.0D + pixelNoise(storm.id(), gridX, gridZ) * 4.0D;
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
            dbz = Math.max(dbz, storm.reflectivityDbz() + (-14.6D + 12.0D * ffd) - forwardFlank * 17.0D + pixelNoise(storm.id() ^ 0x51A7L, gridX, gridZ) * 3.0D);
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
                    dbz = Math.max(dbz, 67.0D + pixelNoise(storm.id() ^ 0xD36B, gridX, gridZ) * 8.0D);
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
