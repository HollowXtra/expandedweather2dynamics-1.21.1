package com.imbet.client;

import com.imbet.Config;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.DynamicStormState;
import com.imbet.weather.HailModel;
import com.imbet.weather.StormSky;
import com.imbet.weather.SupercellStructureService;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;
import weather2.weathersystem.WeatherManagerClient;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.ArrayList;

/**
 * The storm's own sky, read where the player is standing.
 *
 * <p>{@link StormSky} is the arithmetic - how dark the sky goes, how green it goes, how much of the sun that hides -
 * and this is the part that has to look at the world to feed it. It answers two questions per sky update: how much
 * of the sky over the player is a storm's mass, and how deep inside a <em>hailing</em> core's own footprint the
 * player is. The first is {@link StormShade}'s reading, already computed for the deck shading and passed in here
 * rather than looked up twice. The second is the one this class exists for, and it is deliberately the same reading
 * the hail itself is: {@link HailModel#hasHail}, {@link HailModel#sizeFor} and {@link HailModel#footprintRadiusBlocks}
 * off the same {@link SupercellStructureService.StructureSample} the server's damage pass and the clatter both run
 * on, so the sky over a hail core is green exactly where the stones are, off the same numbers.
 *
 * <p>It is read for storms within {@link StormSky#HAIL_SCAN_RANGE_BLOCKS}, not only for the nearest one, and that is
 * the point of a sky rather than of a coat: a hail core two hundred blocks away is a green sky over there, and a
 * player watching one come sees it arrive. The player's own depth inside a shaft and the green of the best core in
 * range are therefore kept separately - the first is what darkens the sky over them, the second is what green it
 * is - and each is smoothed on its own pair of rates, so a core that lifts does not take the green out of the sky
 * between one frame and the next.
 *
 * <p>It writes nothing into the level. Every reading here is consumed by this mod's own hooks and by the sun's own
 * alpha, so a world with a storm over it is still a world with clear weather in it as far as anything else is
 * concerned - which is what makes it safe to have a weather mod and a rain-dependent machine in the same world.
 */
public final class StormSkyClient {

    /** The deck's own share of the sky, smoothed. What used to be pushed into the level as a rain level. */
    private static double deck;

    /** How deep inside a hailing core's footprint the player is, smoothed. */
    private static double hailDepth;

    /** How much of a supercell's own darkness is over the player, smoothed. */
    private static double supercell;

    /** How green the sky is, smoothed, after the size gate and the config's dial. */
    private static double green;

    /**
     * How much light the eclipse in progress is taking, as a darkening value (one is no eclipse).
     *
     * <p>Kept here beside the deck's own reading because it is the same kind of thing and goes to the same
     * four places: it is darkening, so it belongs with the darkness rather than in the renderer that draws
     * the eclipse's own pieces. That split is what makes an eclipse work under a shader pack - the pack can
     * take the sky pass, but nothing can take the sky-darken value the lightmap is built from - and it means
     * the eclipse dims the world exactly as far as a storm does, by the same code and the same floor.
     */
    private static double eclipse = 1.0D;

    private StormSkyClient() {
    }

    /**
     * Reads this sky for one update and steps everything toward it.
     *
     * <p>Called from the same sky pass that had already decided the deck's own share, so the two readings come out of
     * one tick of one pass and cannot disagree about the storm the player is standing under.
     */
    public static void tick(ClientLevel level, Player player, WeatherManagerClient manager, float cover) {
        double targetHail = 0.0D;
        double targetGreen = 0.0D;
        if (Config.SKY_HAIL_GREEN.get() && level != null && player != null && manager != null) {
            Vec3 at = player.position();
            for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
                if (!(object instanceof StormObject storm) || storm.pos == null) {
                    continue;
                }
                if (at.distanceTo(storm.pos) > StormSky.HAIL_SCAN_RANGE_BLOCKS) {
                    continue;
                }
                if (DynamicStormController.isAbsorbedOrDecaying(storm)) {
                    continue;
                }
                DynamicStormState state = DynamicStormController.stateFor(storm);
                if (state == null) {
                    continue;
                }
                float core = state.hailCoreStrength();
                if (!HailModel.hasHail(state.supercellStructureStrength(), core)) {
                    continue;
                }
                HailModel.Size size = HailModel.sizeFor(core, SupercellStructureService.precipitationCharacter(storm, state));
                if (size == HailModel.Size.NONE) {
                    continue;
                }
                SupercellStructureService.StructureSample sample = SupercellStructureService.sample(storm, state);
                if (sample == null || sample.hailShaft() == null) {
                    continue;
                }
                Vec3 centre = sample.hailShaft();
                double depth = StormSky.shaftDepth(horizontalDistance(at, centre),
                        HailModel.footprintRadiusBlocks(core, sample.radius()));
                targetHail = Math.max(targetHail, depth);
                // The green is the size-gated reading, so a pea core darkens the sky over the player without
                // greening it - and a core of quarters or better greens it in proportion to how big its stones are.
                targetGreen = Math.max(targetGreen,
                        StormSky.greenness(depth, size) * Config.HAIL_SKY_GREEN_STRENGTH.get());
            }
        }
        double targetCell = level != null && player != null ? StormShade.supercell(level, player.position()) : 0.0D;
        deck = StormSky.approach(deck, cover, StormSky.DECK_RISE_RATE, StormSky.DECK_FALL_RATE);
        supercell = StormSky.approach(supercell, targetCell, StormSky.DECK_RISE_RATE, StormSky.DECK_FALL_RATE);
        hailDepth = StormSky.approach(hailDepth, targetHail, StormSky.DECK_RISE_RATE, StormSky.DECK_FALL_RATE);
        green = StormSky.approach(green, targetGreen, StormSky.GREEN_RISE_RATE, StormSky.GREEN_FALL_RATE);
        eclipse = eclipseDarkening(level);
    }

    /**
     * The eclipse in progress, read off the same deterministic schedule the renderer draws from.
     *
     * <p>Not smoothed, and deliberately: a storm's deck eases because a storm's mass is something the eye
     * tracks, but an eclipse is an event with a beginning and an end, and the coverage curve is already a
     * half-sine that ramps in and out. Easing it a second time would make the sky lag the sun by a frame or
     * two at first contact and drag the light back out after totality.
     */
    private static double eclipseDarkening(ClientLevel level) {
        if (!Config.VISUALS_ENABLED.get() || !Config.CELESTIAL_ENABLED.get() || !Config.CELESTIAL_ECLIPSE_DIM.get()) {
            return 1.0D;
        }
        if (level == null || level.effects().skyType() != DimensionSpecialEffects.SkyType.NORMAL) {
            return 1.0D;
        }
        CelestialSky.Eclipse reading = CelestialSky.eclipse(
                level.getDayTime(),
                Config.CELESTIAL_ECLIPSE_DURATION_SECONDS.get(),
                Config.CELESTIAL_ECLIPSE_CHANCE.get());
        if (!reading.active()) {
            return 1.0D;
        }
        return StormSky.eclipseDarkness(reading.coverage(), Config.CELESTIAL_ECLIPSE_DARKEN.get());
    }

    /**
     * How dark the mod's own lighting says the sky over the player is. Nought darkening means one.
     *
     * <p>The storm's deck and the eclipse are folded together here, at the one place every consumer reads,
     * so the sky, the deck, the fog, the world's ambient light and the sun's own hiding cannot disagree
     * about it. Each is gated by its own switch: the storm half needs the visuals and the storm darkening on,
     * and the eclipse half is already one when its own switch is off, so either can be turned off without
     * touching the other.
     */
    public static double darkness() {
        double storm = 1.0D;
        if (Config.VISUALS_ENABLED.get() && Config.STORM_SKY_DARKENING.get()) {
            storm = StormSky.darkness(deck, hailDepth, supercell, Config.STORM_SKY_DARKEN_SCALE.get());
        }
        return StormSky.withEclipse(storm, eclipse);
    }

    /** How green the sky over the player is, nought to one. */
    public static double greenness() {
        return Config.VISUALS_ENABLED.get() && Config.SKY_HAIL_GREEN.get() ? green : 0.0D;
    }

    /** Applies this mod's own darkening and green to a sky colour the game has already worked out. */
    public static Vec3 shadeSky(Vec3 colour) {
        return shade(colour, 1.0D);
    }

    /** Applies it to the cloud deck, which takes rather less of the green than the sky behind it does. */
    public static Vec3 shadeDeck(Vec3 colour) {
        return shade(colour, StormSky.DECK_GREEN_SHARE);
    }

    private static Vec3 shade(Vec3 colour, double greenShare) {
        double dark = darkness();
        double green = greenness();
        if (colour == null || (dark >= 0.999D && green <= 0.001D)) {
            return colour;
        }
        double[] shaded = StormSky.shade(colour.x, colour.y, colour.z, dark, green, greenShare);
        return new Vec3(shaded[0], shaded[1], shaded[2]);
    }

    /**
     * The fog, shaded by the same reading the sky is.
     *
     * <p>Registered before the tornado's own fog and the two weather fogs, so that being inside a funnel still wins:
     * this is the sky a player is standing under, and a whiteout is not standing under anything.
     */
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        if (!Config.VISUALS_ENABLED.get()) {
            return;
        }
        double dark = darkness();
        double green = greenness();
        if (dark >= 0.999D && green <= 0.001D) {
            return;
        }
        double[] shaded = StormSky.shade(event.getRed(), event.getGreen(), event.getBlue(), dark, green,
                StormSky.FOG_GREEN_SHARE);
        event.setRed((float) shaded[0]);
        event.setGreen((float) shaded[1]);
        event.setBlue((float) shaded[2]);
    }

    /**
     * The rain level the sun, the moon and the stars should be drawn against.
     *
     * <p>Vanilla draws all three with one alpha derived from this number, so this is where the mod's own sky
     * reaches the things in it: it is a floor on the game's own rain level, so a world that is really raining
     * hides the sun as it always did, and a deck over the player hides it as this mod's own darkness says. With
     * the visuals or the darkening switched off it returns the game's own number untouched.
     */
    public static float sunHidingLevel(float vanillaRainLevel) {
        if (!Config.VISUALS_ENABLED.get() || !Config.STORM_SKY_DARKENING.get()) {
            return vanillaRainLevel;
        }
        return StormSky.sunHidingLevel(vanillaRainLevel, deck, hailDepth, supercell, Config.STORM_SKY_DARKEN_SCALE.get());
    }

    /** Forgets the reading when there is no world to read it in, so a sky cannot be half remembered. */
    public static void clear() {
        deck = 0.0D;
        hailDepth = 0.0D;
        supercell = 0.0D;
        green = 0.0D;
        eclipse = 1.0D;
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
