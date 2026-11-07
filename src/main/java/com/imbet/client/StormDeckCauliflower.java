package com.imbet.client;

import com.imbet.Config;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.HurricaneShield;
import extendedrenderer.particle.entity.EntityRotFX;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import weather2.weathersystem.storm.StormObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reshapes Weather2's own persistent storm deck into a coherent cauliflower mass for a supercell,
 * or into the 1.12.2 hurricane shield for a tropical water storm.
 *
 * <p>Weather2's {@code StormObject} keeps two persistent particle layers for the storm's
 * cloud mass - {@code lookupParticlesCloud} (the high deck) and
 * {@code lookupParticlesCloudLower} (the low layer near the base) - and repositions every
 * particle of both from inside {@code tickRender(float)} each client tick. Because the
 * particles persist for the storm's whole life, the deck can be <em>sculpted</em>: after
 * Weather2 has placed its particles, the same positions are pulled into a cauliflower
 * silhouette before the frame draws them. No extra particles are spawned, nothing pops or
 * shimmers, and the whole mass moves as one object.
 *
 * <p>The reshape runs at {@code tickRender} TAIL, so it self-heals if disabled: Weather2's
 * placement at HEAD simply wins again the next tick. Only positions are touched - Weather2
 * sets particle scale once at spawn, so scaling here would compound every tick and the
 * silhouette is carried entirely by the geometry.
 *
 * <p>Shaping is deliberately self-calibrating rather than hard-coded. The layers' natural
 * bounds are measured from their observed population on the fly - centre from the position
 * mean, radius from the observed x/z extent, height from the deck's observed min/max - so
 * the profile adapts to whatever population Weather2 spawned for a storm of this size. The
 * hurricane shield reads its own eye radius, band count and spin from the same measured
 * radius, so the eye it opens in the deck is the eye the wind field is calm in.
 */
public final class StormDeckCauliflower {
    /** Profile: radius multiplier by altitude band (0 = deck base, 1 = deck top). */
    private static final double BASE_FLARE = 1.18D;
    private static final double WAIST = 0.72D;
    private static final double CROWN_FLARE = 1.06D;
    /** Altitude fraction of the waist - above it the crown bulges, below it the stem tapers. */
    private static final double WAIST_BAND = 0.62D;
    /** How strongly particles are pulled toward their band's target ring (0 = leave, 1 = snap). */
    private static final double RADIAL_PULL = 0.55D;
    private static final double VERTICAL_PULL = 0.45D;
    /** Per-lobe radial spread around the band ring. */
    private static final double LOBE_SPREAD = 0.36D;
    /** How far below its centre a puff's visible body reaches, as a share of its half width. */
    private static final double PUFF_VISIBLE_HALF_HEIGHT = 0.80D;

    // ---- Hurricane shield ----
    /** How hard the shield is flattened vertically: the deck is a slab, not a crown. */
    private static final double SHIELD_VERTICAL_PULL = 0.55D;
    /** The shield's outer rim, as a fraction of the disc radius, that the low collar occupies. */
    private static final double SHIELD_COLLAR_INNER = 0.92D;
    private static final double SHIELD_COLLAR_OUTER = 1.04D;
    /**
     * The eyewall tower's height, as a fraction of the shield radius.
     *
     * <p>This is what turns the shield from a plate with a hole in it into a storm: 1.12.2's eye was cleared
     * at every height, so its deck was a flat slab with a circular window punched through it - from
     * underneath, the whole eye was sky. Here the cloud floor closes in to a narrow mouth and the ring of
     * cloud that is the eye wall climbs out of that mouth, so a line of sight from below meets cloud on the
     * way in and only the aperture still reaches the sky.
     *
     * <p>Kept deliberately short. The tower is the eye wall, not a structure: at a quarter of the shield's
     * radius on a two thousand block disc it climbed five hundred blocks, which is a tower of Babel over the
     * eye rather than a wall around it, and it is the reason the eye read as a funnel even before the crown
     * was fixed.
     */
    private static final double SHIELD_TOWER_FRACTION = 0.14D;
    /** Bounds on that tower in blocks, so a small shield still shows a wall and a huge one stays sane. */
    private static final double SHIELD_TOWER_MIN_BLOCKS = 14.0D;
    private static final double SHIELD_TOWER_MAX_BLOCKS = 96.0D;
    /** How much of the deck's own thickness the tower must clear, so a thin deck still towers. */
    private static final double SHIELD_TOWER_MIN_DECK_MULTIPLIER = 0.9D;
    /** Share of the low layer spent on the ragged skirt around the tower's foot. */
    private static final double SHIELD_SKIRT_FRACTION = 0.34D;
    /** Vertical raggedness of the base lip and the skirt, as a fraction of the deck's thickness. */
    private static final double SHIELD_BASE_RAG = 0.30D;
    /** Longest catch-up the spin accumulator will integrate in one go, ticks. */
    private static final long MAX_SPIN_CATCHUP_TICKS = 400L;
    /** Spin phase per storm: {radians, last game time it was advanced}. */
    private static final Map<Long, double[]> SHIELD_SPIN = new ConcurrentHashMap<>();

    private StormDeckCauliflower() {
    }

    public static void reshape(StormObject storm) {
        if (storm == null || storm.pos == null || storm.manager == null || storm.isCloudlessStorm()) {
            return;
        }
        // A hurricane's shield has its own switch. It must not ride the supercell structure knobs: a
        // player who turns the cauliflower off has not asked for every hurricane to turn back into one.
        boolean shieldStorm = HurricaneShield.isShieldStorm(storm);
        // A squall line is deliberately not a third silhouette here. Its cells are ordinary Weather2
        // thunderstorms and they draw their own cloud: the line's shape is spawned inside Weather2's own
        // deck loop, where each cell's own puffs are laid along the front instead of in a square around
        // it (see com.imbet.client.LineStormDeck). Sculpting the same deck from here as well was what put
        // a second, hand-made shelf over the line - a cloud this mod drew rather than a storm Weather2
        // drew - and it is gone. So a line's cells are left exactly as any other thunderstorm's are left:
        // flat Weather2 layers under the classic look, a cauliflower otherwise.
        // The classic 1.12.2 look is the same bargain in reverse: it stands this mod's cauliflower
        // silhouette down so the deck stays in Weather2's own flat layers, but a hurricane is still a
        // hurricane and keeps the shield below - and a squall line's shelf is the one shape the old look
        // was made of, so it keeps its own switch too. Where the deck sits in Weather2's own flat layers,
        // a player who asked for the classic look asked for that, so both stand down under it.
        if (!shieldStorm && Config.classicLook()) {
            return;
        }
        if (!shieldStorm && (!Config.SUPERCELL_STRUCTURE_ENABLED.get()
                || !Config.SUPERCELL_STRUCTURE_CLIENT_VISUALS.get()
                || !Config.SUPERCELL_STRUCTURE_CLOUD_MASS_SHAPE.get())) {
            return;
        }
        if (DynamicStormController.isAbsorbedOrDecaying(storm)) {
            return;
        }

        HashMap<Integer, EntityRotFX> upper = storm.lookupParticlesCloud;
        HashMap<Integer, EntityRotFX> lower = storm.lookupParticlesCloudLower;
        if (upper == null || lower == null) {
            return;
        }

        DeckBounds bounds = measure(storm, upper, lower);
        if (bounds == null) {
            return;
        }

        if (shieldStorm) {
            reshapeShield(storm, upper, lower, bounds);
            return;
        }

        long gameTime = bounds.gameTime();
        double stormPhase = gameTime * 0.006D + storm.ID * 1.7D;

        // ---- Upper deck: stack the layer into banded lobe rows. ----
        for (HashMap.Entry<Integer, EntityRotFX> entry : upper.entrySet()) {
            EntityRotFX particle = entry.getValue();
            if (particle == null) {
                continue;
            }
            double x = particle.getPosX();
            double y = particle.getPosY();
            double z = particle.getPosZ();
            double dx = x - bounds.centreX();
            double dz = z - bounds.centreZ();
            double band = Mth.clamp((y - bounds.upperMinY()) / bounds.upperHeight(), 0.0D, 1.0D);
            // Stable per-particle hash keyed on the map's stable particle id: the same
            // particle keeps the same angular slot each tick, so lobes hold their place in
            // the silhouette instead of churning; the mass still rotates via stormPhase.
            double slot = hashUnit(storm.ID * 31L + entry.getKey() * 0x9E3779B97F4A7C15L);
            double angle = slot * Math.PI * 2.0D + stormPhase;
            double targetRadius = bounds.radius() * profile(band) * (1.0D - LOBE_SPREAD * 0.5D + LOBE_SPREAD * hashUnit(entry.getKey() * 7919L + 0x5DEECE66DL));
            double currentRadius = Math.sqrt(dx * dx + dz * dz);
            if (currentRadius < 1.0E-4D) {
                continue;
            }
            double blendedRadius = Mth.lerp(RADIAL_PULL, currentRadius, targetRadius);
            double scale = blendedRadius / currentRadius;
            double targetY = bounds.upperMinY() + band * bounds.upperHeight() + lobeBob(stormPhase + slot * 6.28D, band);
            particle.setPrevPosX(x);
            particle.setPrevPosY(y);
            particle.setPrevPosZ(z);
            particle.setPosX(bounds.centreX() + dx * scale);
            particle.setPosZ(bounds.centreZ() + dz * scale);
            particle.setPosY(Mth.lerp(VERTICAL_PULL, y, targetY));
        }

        // ---- Lower layer: pull it in as a tighter collar hugging just under the deck base. ----
        for (EntityRotFX particle : lower.values()) {
            if (particle == null) {
                continue;
            }
            double x = particle.getPosX();
            double y = particle.getPosY();
            double z = particle.getPosZ();
            double dx = x - bounds.centreX();
            double dz = z - bounds.centreZ();
            double currentRadius = Math.sqrt(dx * dx + dz * dz);
            if (currentRadius < 1.0E-4D) {
                continue;
            }
            double targetRadius = bounds.radius() * BASE_FLARE * 0.86D;
            double blendedRadius = Mth.lerp(RADIAL_PULL * 0.7D, currentRadius, targetRadius);
            double scale = blendedRadius / currentRadius;
            particle.setPrevPosX(x);
            particle.setPrevPosY(y);
            particle.setPrevPosZ(z);
            particle.setPosX(bounds.centreX() + dx * scale);
            particle.setPosZ(bounds.centreZ() + dz * scale);
            particle.setPosY(Mth.lerp(VERTICAL_PULL * 0.6D, y, bounds.upperMinY() - 4.0D));
        }

        double cloudBase = DynamicStormController.cloudBaseWorldY(storm);
        holdAboveCloudBase(upper.values(), cloudBase);
        holdAboveCloudBase(lower.values(), cloudBase);
        if (storm.manager.getWorld() instanceof ClientLevel level) {
            shadeBySun(level, storm, upper, lower, bounds);
        }
    }

    /**
     * Lights the mass from the sun. Every puff gets its own steady grey - Weather2 gives each a random one when it spawns,
     * and this keeps that mottling - and is then shaded by which way its part of the mass faces: the side toward the sun
     * near white, the side away blue-grey, the crown lit from above and the underside layer dark, the base of the storm.
     * Uniformly white, the deck read from any distance as a scatter of separate white balls rather than one solid storm.
     * Set fresh every tick from the puff's own grey, so it never compounds.
     */
    private static void shadeBySun(ClientLevel level, StormObject storm, HashMap<Integer, EntityRotFX> upper, HashMap<Integer, EntityRotFX> lower, DeckBounds bounds) {
        for (HashMap.Entry<Integer, EntityRotFX> entry : upper.entrySet()) {
            EntityRotFX particle = entry.getValue();
            if (particle == null) {
                continue;
            }
            double dx = particle.getPosX() - bounds.centreX();
            double dz = particle.getPosZ() - bounds.centreZ();
            double band = Mth.clamp((particle.getPosY() - bounds.upperMinY()) / bounds.upperHeight(), 0.0D, 1.0D);
            double outward = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
            // The lower part of the mass faces out and a little down; the crown faces up.
            double up = (band - 0.40D) * 1.6D;
            float lit = SunShading.lit(level, dx / outward, up, dz / outward);
            float grey = 0.80F + 0.20F * (float) hashUnit(storm.ID * 131L + entry.getKey() * 0x632BE59BD9B4E019L);
            // Down at the foot of the mass it is under its own shadow whichever way it faces.
            float underside = (float) Mth.clamp(0.72D + band * 0.70D, 0.72D, 1.0D);
            float[] colour = SunShading.cloudColour(level, grey * underside, lit, 0.58F);
            particle.setColor(colour[0], colour[1], colour[2]);
        }
        for (HashMap.Entry<Integer, EntityRotFX> entry : lower.entrySet()) {
            EntityRotFX particle = entry.getValue();
            if (particle == null) {
                continue;
            }
            double dx = particle.getPosX() - bounds.centreX();
            double dz = particle.getPosZ() - bounds.centreZ();
            double outward = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
            float lit = SunShading.lit(level, dx / outward, -0.6D, dz / outward);
            float grey = 0.58F + 0.12F * (float) hashUnit(storm.ID * 137L + entry.getKey() * 0x632BE59BD9B4E019L);
            float[] colour = SunShading.cloudColour(level, grey, lit, 0.70F);
            particle.setColor(colour[0], colour[1], colour[2]);
        }
    }

    /**
     * Gives the storm a flat underside. Each puff is held up by its own width, so the biggest stand highest and every
     * one of them stops at the same level: a sky-filling puff is well over a hundred blocks across, and without this
     * the deck sagged nearly to the ground on top of the tornado. A flat base is also simply what one looks like.
     */
    private static void holdAboveCloudBase(Iterable<EntityRotFX> puffs, double cloudBase) {
        for (EntityRotFX puff : puffs) {
            if (puff == null) {
                continue;
            }
            double floor = cloudBase + puff.getScale() * PUFF_VISIBLE_HALF_HEIGHT;
            if (puff.getPosY() < floor) {
                puff.setPosY(floor);
                puff.setPrevPosY(floor);
            }
        }
    }

    /**
     * The 1.12.2 hurricane shield: every deck particle is placed on the disc by its own stable
     * identity - inside the eye wall, on one of the spiral arms, at a height inside the slab - and the
     * whole disc is turned by the storm's own spin, doubled.
     *
     * <p>Placing rather than nudging is what makes the shield possible at all. Weather2 spawns deck
     * particles wherever its own disc falls, so a shape can only be held by putting every particle where
     * the shape wants it every tick, which is exactly what the radial fraction below does. The eye itself
     * is a recess now rather than a clearance: the floor's cloud runs all the way in to a narrow mouth, and
     * the eye wall climbs out of that mouth and closes back over the top, so the calm air and the shape are
     * read from the wall's inner surface instead of from a circle of missing cloud. The bands come from the
     * same place: the arms hold because each particle keeps its own arm, and the coil tightens toward the
     * eye because the arm angle is offset by how far out the particle sits.
     */
    private static void reshapeShield(StormObject storm, HashMap<Integer, EntityRotFX> upper, HashMap<Integer, EntityRotFX> lower, DeckBounds bounds) {
        double shieldRadius = Math.max(bounds.radius(), 8.0D);
        double innerRadius = HurricaneShield.deckInnerRadius(shieldRadius);
        double mouthRadius = HurricaneShield.wallMouthRadius(shieldRadius);
        if (innerRadius >= shieldRadius * 0.97D) {
            return;
        }
        long gameTime = bounds.gameTime();
        double phase = shieldPhase(storm, gameTime);
        double spinSign = HurricaneShield.spinSign(storm);
        double baseY = bounds.upperMinY();
        double collarY = baseY - Math.max(3.0D, bounds.upperHeight() * 0.10D);

        // The eyewall tower rides above the floor: the band between the mouth and the eye's width climbs
        // out of the aperture and becomes the wall, while everything outside that band stays down on the
        // floor. That split is the whole fix - the floor closes and the sky below becomes the aperture
        // rather than the eye.
        double tower = Mth.clamp(Math.max(bounds.upperHeight() * SHIELD_TOWER_MIN_DECK_MULTIPLIER,
                shieldRadius * SHIELD_TOWER_FRACTION), SHIELD_TOWER_MIN_BLOCKS, SHIELD_TOWER_MAX_BLOCKS);
        double column = bounds.upperHeight() + tower;
        double climbSpan = Math.max(1.0D, innerRadius - mouthRadius);

        for (HashMap.Entry<Integer, EntityRotFX> entry : upper.entrySet()) {
            EntityRotFX particle = entry.getValue();
            if (particle == null) {
                continue;
            }
            double fraction = HurricaneShield.deckRadialFraction(hashUnit(entry.getKey() * 0x2545F4914F6CDD1DL + storm.ID), shieldRadius);
            double nominalRadius = fraction * shieldRadius;
            // The tower is fed by the floor's inner band: the ring at the mouth is pinned where it is, so the
            // aperture keeps a crisp edge, and the band climbs as it approaches the eye's width, so the
            // wall's whole surface exists instead of only its top. Beyond the band the lift is zero and the
            // floor stays closed.
            double climb = Mth.clamp((nominalRadius - mouthRadius) / climbSpan, 0.0D, 1.0D);
            double lift = tower * climb;
            double band = Mth.clamp((particle.getPosY() - baseY) / bounds.upperHeight(), 0.0D, 1.0D);
            double targetY = baseY + bounds.upperHeight() * (0.22D + 0.56D * band) + lift;
            // A slow swell travelling around the disc, so the slab breathes instead of sitting frozen.
            targetY += Math.sin(phase * 0.6D + fraction * 6.28D) * 1.5D;
            double height = Mth.clamp((targetY - baseY) / column, 0.0D, 1.0D);
            // Inside the band the wall is the placement itself, not a lower bound on it: the cloud's inner
            // boundary must follow the profile exactly at every height, so the ring is a clean surface the
            // eye's calm air sits inside rather than a ragged edge. Outside the band nothing is moved, so the
            // floor keeps every ring it was sampled with.
            double radius = nominalRadius <= innerRadius
                    ? HurricaneShield.wallRadiusAt(shieldRadius, height)
                    : nominalRadius;
            placeShieldParticle(storm, particle, entry.getKey(), radius, radius / shieldRadius, phase, spinSign, bounds.centreX(), bounds.centreZ(), Mth.lerp(SHIELD_VERTICAL_PULL, particle.getPosY(), targetY));
        }

        for (HashMap.Entry<Integer, EntityRotFX> entry : lower.entrySet()) {
            EntityRotFX particle = entry.getValue();
            if (particle == null) {
                continue;
            }
            // The low layer does two jobs: a ragged skirt around the tower's foot, so the wall does not
            // float above a bare slab and no light leaks under it, and the rim collar that wraps the
            // disc's outer edge and gives the silhouette the thickness a flat slab of cloud lacks.
            double unit = hashUnit(entry.getKey() * 0x27D4EB2F165667C5L + storm.ID);
            double rag = (hashUnit(entry.getKey() * 0x2545F4914F6CDD1DL + 7L) - 0.5D) * 2.0D
                    * bounds.upperHeight() * SHIELD_BASE_RAG;
            double radius;
            double fraction;
            double targetY;
            if (unit < SHIELD_SKIRT_FRACTION) {
                // The lip of the mouth: a ragged, dense ring just outside the aperture, which is what stops
                // the funnel's foot from being a clean circle of daylight seen from a shallow angle.
                fraction = (1.02D + 0.30D * hashUnit(entry.getKey() * 0x94D049BB133111EBL + 3L))
                        * HurricaneShield.WALL_MOUTH_FRACTION * innerRadius / shieldRadius;
                radius = fraction * shieldRadius;
                targetY = collarY + rag;
            } else {
                fraction = SHIELD_COLLAR_INNER + (SHIELD_COLLAR_OUTER - SHIELD_COLLAR_INNER) * unit;
                radius = fraction * shieldRadius;
                targetY = collarY + rag;
            }
            placeShieldParticle(storm, particle, entry.getKey() + 31, radius, fraction, phase, spinSign, bounds.centreX(), bounds.centreZ(), Mth.lerp(SHIELD_VERTICAL_PULL * 0.7D, particle.getPosY(), targetY));
        }
    }

    /** Places one shield particle at its own ring, arm and height. */
    private static void placeShieldParticle(StormObject storm, EntityRotFX particle, long identity, double radius, double radiusFraction, double phase, double spinSign, double centreX, double centreZ, double y) {
        radius = Math.max(0.0D, radius);
        double angle = HurricaneShield.bandAngle(hashUnit(identity * 0x9E3779B97F4A7C15L + 17L), radiusFraction, phase, spinSign);
        double x = particle.getPosX();
        double z = particle.getPosZ();
        particle.setPrevPosX(x);
        particle.setPrevPosY(particle.getPosY());
        particle.setPrevPosZ(z);
        particle.setPosX(centreX + Math.cos(angle) * radius);
        particle.setPosZ(centreZ + Math.sin(angle) * radius);
        particle.setPosY(y);
    }

    /**
     * The shield's rotation, integrated from the storm's own spin rate rather than read from the clock:
     * a hurricane that spins up turns faster on screen, and one that stalls holds still. The accumulator
     * is advanced once per tick per storm and catches up across however long the storm was out of range,
     * so a client that flies back into view finds the bands where the storm left them instead of at an
     * angle snap.
     */
    private static double shieldPhase(StormObject storm, long gameTime) {
        double[] state = SHIELD_SPIN.computeIfAbsent(storm.ID, id -> new double[]{0.0D, gameTime});
        long last = (long) state[1];
        if (gameTime != last && gameTime > last) {
            long elapsed = Math.min(gameTime - last, MAX_SPIN_CATCHUP_TICKS);
            state[0] += HurricaneShield.spinPerTick(storm) * HurricaneShield.spinSign(storm) * elapsed;
            state[1] = gameTime;
            pruneSpin(gameTime);
        }
        return state[0];
    }

    private static void pruneSpin(long gameTime) {
        if (SHIELD_SPIN.size() <= 64) {
            return;
        }
        SHIELD_SPIN.entrySet().removeIf(entry -> gameTime - (long) entry.getValue()[1] > MAX_SPIN_CATCHUP_TICKS);
    }

    /** Observed bounds of a storm's two deck layers, measured from the live population. */
    private record DeckBounds(double centreX, double centreZ, double radius, double upperMinY, double upperHeight, long gameTime) {
    }

    private static DeckBounds measure(StormObject storm, HashMap<Integer, EntityRotFX> upper, HashMap<Integer, EntityRotFX> lower) {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        double sumX = 0.0D, sumZ = 0.0D;
        int count = 0;
        for (EntityRotFX particle : upper.values()) {
            if (particle == null) {
                continue;
            }
            minX = Math.min(minX, particle.getPosX());
            maxX = Math.max(maxX, particle.getPosX());
            minZ = Math.min(minZ, particle.getPosZ());
            maxZ = Math.max(maxZ, particle.getPosZ());
            sumX += particle.getPosX();
            sumZ += particle.getPosZ();
            count++;
        }
        for (EntityRotFX particle : lower.values()) {
            if (particle == null) {
                continue;
            }
            minX = Math.min(minX, particle.getPosX());
            maxX = Math.max(maxX, particle.getPosX());
            minZ = Math.min(minZ, particle.getPosZ());
            maxZ = Math.max(maxZ, particle.getPosZ());
            sumX += particle.getPosX();
            sumZ += particle.getPosZ();
            count++;
        }
        if (count < 4) {
            return null;
        }
        double centreX = sumX / count;
        double centreZ = sumZ / count;
        double radius = Math.max(Math.max(maxX - minX, maxZ - minZ) * 0.5D, 4.0D);

        // The deck's own base anchors band zero; only the upper layer is banded - the lower
        // layer becomes the under-collar instead.
        double upperMinY = Double.MAX_VALUE;
        double upperMaxY = -Double.MAX_VALUE;
        for (EntityRotFX particle : upper.values()) {
            if (particle == null) {
                continue;
            }
            upperMinY = Math.min(upperMinY, particle.getPosY());
            upperMaxY = Math.max(upperMaxY, particle.getPosY());
        }
        if (upperMinY > upperMaxY) {
            return null;
        }
        double upperHeight = Math.max(upperMaxY - upperMinY, 4.0D);
        long gameTime = storm.manager.getWorld() != null ? storm.manager.getWorld().getGameTime() : 0L;
        return new DeckBounds(centreX, centreZ, radius, upperMinY, upperHeight, gameTime);
    }

    /** Radius multiplier by band: skirt flare -> waist -> crown bulge. */
    private static double profile(double band) {
        if (band < WAIST_BAND) {
            double down = (WAIST_BAND - band) / WAIST_BAND;
            return Mth.lerp(down, WAIST, BASE_FLARE);
        }
        double up = (band - WAIST_BAND) / (1.0D - WAIST_BAND);
        return Mth.lerp(up, WAIST, CROWN_FLARE);
    }

    /** Small vertical wobble so lobe rows breathe instead of sitting static. */
    private static double lobeBob(double phase, double band) {
        return Math.sin(phase * 1.3D + band * 4.0D) * (1.0D + band * 2.0D);
    }

    private static double hashUnit(long seed) {
        long hash = seed * 0x9E3779B97F4A7C15L;
        hash ^= (hash >>> 33);
        hash *= 0xFF51AFD7ED558CCDL;
        hash ^= (hash >>> 29);
        hash *= 0xC4CEB9FE1A85EC53L;
        hash ^= (hash >>> 32);
        return (hash & 0xFFFFFFFFL) / (double) 0x100000000L;
    }
}
