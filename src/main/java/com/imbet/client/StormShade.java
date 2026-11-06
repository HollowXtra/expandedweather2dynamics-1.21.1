package com.imbet.client;

import com.imbet.Config;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.DynamicStormState;
import com.imbet.weather.PrecipitationField;
import com.imbet.weather.SupercellStructureService;
import com.imbet.weather.SupercellStructureService.StructureSample;
import com.imbet.weather.SupercellStructureService.SupercellMode;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import weather2.ClientTickHandler;
import weather2.weathersystem.WeatherManagerClient;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.ArrayList;

/**
 * How much sky a storm is covering where you are standing, as a fraction from clear to fully covered.
 *
 * <p>This exists because the world's darkening used to be measured from the storm's <em>centre</em>:
 * a 700 block ramp on the distance to the nearest storm, so a funnel five hundred blocks away dimmed
 * you while a cloud shield directly overhead was worth nothing in particular. What actually darkens
 * a day - and what a player can see and reason about - is the cover above them: the deck, and the
 * precipitation falling out of it.
 *
 * <p>Three things are read, and the answer is the strongest of them:
 *
 * <ul>
 *   <li><b>The storm's own cloud shield.</b> Weather2 spreads its deck across the storm's size, so a
 *       disc of that radius around the storm is the widest cloud mass there is. A cloudless storm -
 *       an LP supper with a bare updraft, or a storm the classifier has stripped - covers nothing
 *       and is skipped, which is the whole point: no deck, no shade.</li>
 *   <li><b>The supercell structure, where it is drawn.</b> The mesocyclone, the updraft, the forward
 *       flank, the rear flank, the anvil band between its core and its back-sheared end, and the wall
 *       cloud each carry their own disc or corridor, scaled by how strongly that structure is
 *       currently drawn. This is what gives the shade a shape: the darkest place on the map is under
 *       the wall cloud, the flank is a little lighter, and the anvil trails off downwind.</li>
 *   <li><b>The precipitation over you.</b> A rain shield is darker than the cloud base it hangs from,
 *       so the rain curtain gets its own weight - raised for a high-precipitation storm, lowered for
 *       a low-precipitation one, and cut down by virga, which is precisely the case of precipitation
 *       that never reaches the ground and therefore never shades it.</li>
 * </ul>
 *
 * <p>Only the strongest storm in range is allowed to answer, not their sum: cover is a ceiling, not
 * a quantity, and two overlapping decks cannot cover more than all of the sky. Everything is
 * horizontal geometry with no raycast, so it costs nothing per frame and cannot disagree with what
 * the deck is drawn from - the same structure sample the deck's own particles are spawned from.
 *
 * <p>The reading is quantised to the tick and cached, because the caller asks every client tick
 * while the answer can only change when the storm moves.
 */
public final class StormShade {

    /** How far away a storm can be and still be worth asking. Only the deck matters, and it is finite. */
    private static final int RANGE_BLOCKS = 1400;

    /** The smallest cloud disc worth shading with, so a storm whose size has not ramped up yet still
     * gets a sensible footprint rather than a dot. */
    private static final double MIN_DECK_RADIUS_BLOCKS = 90.0D;

    private static long cachedTick = Long.MIN_VALUE;
    private static float cachedCover = -1.0F;
    private static float cachedSupercell = 0.0F;

    private StormShade() {
    }

    /** The cover over a position, 0 (clear sky) to 1 (deck, wall cloud and rain directly overhead). */
    public static float cover(ClientLevel level, Vec3 position) {
        if (!Config.STORM_COVER_SHADING.get() || level == null || position == null) {
            return 0.0F;
        }
        long gameTime = level.getGameTime();
        if (gameTime == cachedTick && cachedCover >= 0.0F) {
            return cachedCover;
        }
        WeatherManagerClient manager = ClientTickHandler.weatherManager;
        if (manager == null) {
            return 0.0F;
        }
        float best = 0.0F;
        float bestSupercell = 0.0F;
        double deckScale = Config.STORM_COVER_DECK_SCALE.get();
        for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
            if (!(object instanceof StormObject storm) || storm.pos == null || storm.isDead
                    || DynamicStormController.isAbsorbedOrDecaying(storm) || storm.isCloudlessStorm()) {
                continue;
            }
            double reach = RANGE_BLOCKS + storm.getSize() * deckScale * 2.0D;
            if (horizontalDistanceSqr(position, storm.pos) > reach * reach) {
                continue;
            }
            best = Math.max(best, stormCover(storm, position, deckScale));
            bestSupercell = Math.max(bestSupercell, supercellGloom(storm, position));
        }
        cachedTick = gameTime;
        cachedCover = Mth.clamp(best, 0.0F, 1.0F);
        cachedSupercell = Mth.clamp(bestSupercell, 0.0F, 1.0F);
        return cachedCover;
    }

    /**
     * How much of a supercell's own darkness is over a position, 0 to 1: the extra it takes on top of an ordinary deck.
     * Read on the same tick as {@link #cover}, and from the same storms.
     */
    public static float supercell(ClientLevel level, Vec3 position) {
        cover(level, position);
        return cachedSupercell;
    }

    /**
     * A supercell's darkness at a position. Under its rain, its updraft base and its wall cloud it is the whole of it, and
     * it carries on past the storm's edge as a gloom that fades over two or three storm widths: a storm that size blocks
     * a good part of the sky well beyond the ground it rains on, which is why the light goes strange long before it
     * arrives. A low-precipitation storm, with its bare bright base, takes the least.
     */
    private static float supercellGloom(StormObject storm, Vec3 position) {
        DynamicStormState state = DynamicStormController.stateFor(storm);
        if (state == null) {
            return 0.0F;
        }
        float structure = state.supercellStructureStrength();
        if (structure <= 0.05F || !SupercellStructureService.isEligible(storm)) {
            return 0.0F;
        }
        StructureSample sample = SupercellStructureService.sample(storm, state);
        double radius = Math.max(40.0D, sample.radius());
        double under = Math.max(precipitationShade(storm, state, position),
                Math.max(disc(position, sample.updraft(), radius * 1.10D), disc(position, sample.mesocyclone(), radius * 0.95D)));
        double around = Math.sqrt(disc(position, sample.mesocyclone(), radius * 3.2D)) * 0.50D;
        double mode = sample.mode() == SupercellMode.HP ? 1.0D : sample.mode() == SupercellMode.CLASSIC ? 0.82D : 0.55D;
        return (float) Mth.clamp(Math.max(under, around) * structure * mode, 0.0D, 1.0D);
    }

    private static float stormCover(StormObject storm, Vec3 position, double deckScale) {
        float cover = 0.0F;
        // 1. The deck Weather2 draws across the storm's own size.
        double deckRadius = Math.max(MIN_DECK_RADIUS_BLOCKS, storm.getSize() * deckScale);
        cover = Math.max(cover, (float) disc(position, storm.pos, deckRadius));

        DynamicStormState state = DynamicStormController.stateFor(storm);
        if (state == null) {
            return cover;
        }
        float structure = state.supercellStructureStrength();
        SupercellMode mode = null;
        StructureSample sample = null;
        if (structure > 0.05F && SupercellStructureService.isEligible(storm)) {
            sample = SupercellStructureService.sample(storm, state);
            mode = sample.mode();
            double radius = Math.max(40.0D, sample.radius());
            // The mass the structure is drawn from, each part with its own reach: the wall cloud is
            // small and the darkest thing overhead, the anvil is a corridor trailing downwind.
            double mass = disc(position, sample.wallCloudCenter(), sample.wallCloudDiameter() * 0.62D);
            mass = Math.max(mass, disc(position, sample.mesocyclone(), radius * 1.05D));
            mass = Math.max(mass, disc(position, sample.updraft(), radius * 0.95D));
            mass = Math.max(mass, disc(position, sample.forwardFlank(), radius * 0.85D));
            mass = Math.max(mass, disc(position, sample.rearFlank(), radius * 0.80D));
            mass = Math.max(mass, corridor(position, sample.anvilCore(), sample.backShearedAnvil(), radius * 0.72D));
            cover = Math.max(cover, (float) (mass * structure));
        }

        // 2. The precipitation falling out of it. An LP storm's deck shades but its rain does not
        // arrive; virga is the same story told by the air below the cloud.
        float wet = precipitationWeight(storm, mode);
        float virga = SupercellStructureService.virgaStrength(storm, state);
        double curtain;
        if (sample == null) {
            // A storm with no structure of its own: its rain is a shield around it.
            curtain = disc(position, storm.pos, deckRadius) * 0.85D;
        } else {
            // A supercell's shade is drawn from its precipitation field, so it follows the rain wherever
            // the rain actually is: darkest under the forward-flank core and under the hook wrapping the
            // vortex, and lifted in the rain-free notch - the bright slot you can look up into from the
            // inflow side of a storm.
            curtain = precipitationShade(storm, state, position);
        }
        cover = Math.max(cover, (float) (curtain * wet * (1.0D - virga * 0.75D)));
        return Mth.clamp(cover, 0.0F, 1.0F);
    }

    /**
     * How much of the sky a storm's precipitation uses up directly over a position.
     *
     * <p>The field is a density, so the shade is the field with a floor under it: even thin rain veils
     * the sky somewhat - that is what a rain shaft looks like from underneath - while a torrential core
     * reads as a black ceiling. An ordinary storm, which has no field, falls back to a disc around it.
     */
    private static double precipitationShade(StormObject storm, DynamicStormState state, Vec3 position) {
        PrecipitationField.Geometry field = SupercellStructureService.precipitationGeometry(storm, state);
        StructureSample sample = SupercellStructureService.sample(storm, state);
        Vec3 forward = sample.forward();
        Vec3 right = sample.right();
        double density = PrecipitationField.densityAt(field, storm.pos.x, storm.pos.z,
                forward.x, forward.z, right.x, right.z, position.x, position.z);
        double radius = Math.max(40.0D, sample.radius());
        return Mth.lerp(density, disc(position, storm.pos, radius * 0.85D) * 0.45D, 1.0D);
    }

    /**
     * How much of a storm's moisture is reaching the ground, as a weight on its rain curtain.
     *
     * <p>A high-precipitation storm's shield is what wraps the tornado and darkens everything under
     * it; a low-precipitation one has a bare, bright base by definition. The mode carries that, and
     * where there is no mode - a storm below the structure's own eligibility, or a hurricane's own
     * rain - the stage intensity stands in for it.
     */
    private static float precipitationWeight(StormObject storm, SupercellMode mode) {
        if (mode == SupercellMode.HP) {
            return 1.00F;
        }
        if (mode == SupercellMode.CLASSIC) {
            return 0.70F;
        }
        if (mode == SupercellMode.LP) {
            return 0.25F;
        }
        return Mth.clamp(0.35F + storm.levelCurStagesIntensity * 0.60F, 0.0F, 1.0F);
    }

    /** 1 inside a disc, falling to 0 at its rim. */
    private static double disc(Vec3 position, Vec3 centre, double radius) {
        if (centre == null || radius <= 0.0D) {
            return 0.0D;
        }
        return Mth.clamp(1.0D - Math.sqrt(horizontalDistanceSqr(position, centre)) / radius, 0.0D, 1.0D);
    }

    /** 1 inside a corridor and falling to 0 at its width - the shape an anvil band or a line has. */
    private static double corridor(Vec3 position, Vec3 from, Vec3 to, double width) {
        if (from == null || to == null || width <= 0.0D) {
            return 0.0D;
        }
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double lengthSqr = dx * dx + dz * dz;
        double t = lengthSqr <= 1.0e-6D ? 0.0D
                : Mth.clamp(((position.x - from.x) * dx + (position.z - from.z) * dz) / lengthSqr, 0.0D, 1.0D);
        double closestX = from.x + dx * t;
        double closestZ = from.z + dz * t;
        double distance = Math.sqrt(square(position.x - closestX) + square(position.z - closestZ));
        return Mth.clamp(1.0D - distance / width, 0.0D, 1.0D);
    }

    private static double horizontalDistanceSqr(Vec3 a, Vec3 b) {
        return square(a.x - b.x) + square(a.z - b.z);
    }

    private static double square(double value) {
        return value * value;
    }
}
