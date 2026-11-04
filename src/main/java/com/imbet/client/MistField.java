package com.imbet.client;

import com.imbet.Config;
import com.imbet.weather.AddonWeatherEvent;
import com.imbet.weather.ClientAddonWeatherEventStore;
import com.imbet.weather.RainFogModel;
import com.imbet.weather.StormSubtype;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * How thick the air is where the player is standing, as one number the mist volume and the haze both read.
 *
 * <p>Mist is not weather, it is moisture: what decides whether you can see it is how much water the air is
 * holding, how cold that air is, and whether anything is stirring it. So the field is built from the ground
 * up rather than from the nearest storm:
 *
 * <ul>
 *   <li><b>The water around you</b> - the same nearby-water read the morning fog already uses, so a lake, a
 *       swamp or a wet forest floor carries mist and a desert carries none. This is the largest term, and it
 *       is why mist pools in hollows and over rivers rather than uniformly everywhere.</li>
 *   <li><b>Rain</b> - falling rain saturates the lowest air as it evaporates on the way down, and the ground
 *       it has just wet keeps feeding it back for a while after the rain stops. It is also what makes the
 *       inside of a storm - and the cold pool under one - the thickest mist there is.</li>
 *   <li><b>The deck overhead</b> - air under a cloud shield is shielded from the sun that would burn the mist
 *       off, which is why ground mist survives into the afternoon under a storm and not under a clear sky.</li>
 *   <li><b>The time of day</b> - mist is a night and dawn effect. Radiational cooling overnight puts the
 *       lowest air at its dew point, the sun burns it off through the morning, and only thick, humid air
 *       holds any through the middle of the day. That is why this is not a dawn-only effect like the morning
 *       fog, but it is still at its strongest around dawn.</li>
 *   <li><b>The wind</b> - moving air mixes the layer out and scours it away, so mist is a calm-air effect
 *       and a storm's inflow leaves none of it standing. This is what stops the wisps from being drawn in the
 *       middle of the one kind of weather where you would never see them.</li>
 *   <li><b>Dry and freezing air</b> - a biome without precipitation simply cannot hold this much water, and
 *       ice fog in really cold air is thinner than the dew-point fog it replaces.</li>
 * </ul>
 *
 * <p>Rain is the half of this that is not moisture at all, and it is deliberately not folded into that list.
 * Every term in it is a reason air holds water <em>quietly</em>, and a downpour has none of them: it fogs the
 * air in the middle of a windy afternoon over dry ground, because its water is falling <em>through</em> the air
 * rather than condensing out of it. So the rain brings its own mass, from its own model
 * ({@link RainFogModel}), and the two are composited rather than averaged: the air is the thicker of the two,
 * the rain's mass forms in seconds instead of over hours, and no wind term and no time of day is allowed to
 * thin it. In a squall line's curtain or a hurricane's band that is the whole look - and it is the one kind of
 * weather in which "mist is a calm-air effect" would otherwise leave the screen empty.
 *
 * <p>The same number also drives the haze: mist that is thick enough to see is thick enough to hide
 * distance, and reading both from one field is what keeps the wisps and the fog they sit in from disagreeing.
 * The haze is composed by taking the nearer of two far planes and letting tornado-pressure fog win outright,
 * so it can never fight the whiteout for the screen.
 */
public final class MistField {

    /**
     * How often the air is re-read. The ground under the player, the rain and the wind all change on the scale
     * of seconds, and the scan behind the water reading is a few hundred block lookups, so this runs on a
     * cadence rather than every tick; the field itself is smoothed every tick between readings.
     */
    private static final int AIR_SAMPLE_INTERVAL_TICKS = 20;

    /** How far out the wind is read, in blocks - wide enough to catch the storm you are walking into. */
    private static final int WIND_SAMPLE_RANGE_BLOCKS = 96;

    /** One block is one metre. Wind drives the wisps' drift, so this is the mph-to-blocks-per-second step. */
    private static final double BLOCKS_PER_SECOND_PER_MPH = 0.44704D;

    /** How fast mist closes in, and how fast it burns off. Mist forms slower than it clears. */
    private static final float RISE_RATE = 0.020F;
    private static final float FALL_RATE = 0.010F;

    /**
     * How fast the rain's own air closes in. Much faster than the moisture mist, because it really does: a
     * downpour fogs the air in a couple of seconds and a squall line's curtain arrives as a wall, and a fog
     * that took the moisture mist's half a minute to arrive would visibly lag the rain that caused it.
     */
    private static final float RAIN_RISE_RATE = 0.075F;

    /** The floor a strong wind thins the mist to, rather than clearing the air outright. */
    private static final float WIND_FLOOR = 0.12F;

    /** Below this the mist is treated as gone: nothing is drawn and no fog is pulled in. */
    private static final float VISIBLE_FLOOR = 0.015F;

    /**
     * How far a storm's rain reaches from its own centre, as a multiple of the event's radius. Generous on
     * purpose: this decides where the rain fog is thick, and being a little wide of the drawn rain reads as
     * haze hanging around the shaft, where being narrow of it would leave fog with no rain under it.
     */
    private static final double RAIN_BODY_REACH = 1.6D;

    /**
     * Where a line's curtain hangs along the front's own direction, as a multiple of the event's radius. The
     * rain of a squall line falls behind the gust front it is eating, which is the whole reason a line has a
     * clear sky in front of it and a wall of grey behind it.
     */
    private static final double RAIN_CURTAIN_BEHIND = 0.8D;

    private static float density;
    private static float humidity = 0.2F;
    private static float waterHumidity;
    private static float rain;
    private static float rainFog;
    private static float shaft;
    private static float cover;
    private static float cold = 1.0F;
    private static float windSpeedMph;
    private static float dayFactor = 1.0F;
    private static float warmth;
    private static float grime;
    private static Vec3 drift = Vec3.ZERO;
    private static long nextAirSampleTick = Long.MIN_VALUE;

    private MistField() {
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Player player = minecraft.player;
        if (level == null || player == null || !Config.MIST_ENABLED.get() || Config.classicLook()) {
            density = Mth.lerp(0.10F, density, 0.0F);
            rainFog = 0.0F;
            if (density < VISIBLE_FLOOR) {
                density = 0.0F;
            }
            return;
        }

        long gameTime = level.getGameTime();
        if (gameTime >= nextAirSampleTick) {
            nextAirSampleTick = gameTime + AIR_SAMPLE_INTERVAL_TICKS;
            sampleAir(level, player);
        }
        dayFactor = timeOfDayFactor(level.getDayTime() % 24000L);

        float wind = Mth.clamp(1.0F - windSpeedMph / Config.MIST_WIND_SPEED_MPH.get().floatValue(), WIND_FLOOR, 1.0F);
        float moisture = dayFactor * humidity * wind * cold * Config.MIST_DENSITY.get().floatValue();
        // The rain's own air, which none of those three terms touch and which the rain itself switches on and
        // off. Composited by taking the thicker rather than by adding, so a wet dawn is not twice as thick as
        // the same dawn in rain - the two are answers to the same question about the same air.
        float rainAir = rainFog * Config.MIST_RAIN_MIST.get().floatValue();
        float target = Mth.clamp(Math.max(moisture, rainAir), 0.0F, 2.0F);
        boolean rainIsThicker = rainAir > moisture;
        density = Mth.lerp(target > density ? (rainIsThicker ? RAIN_RISE_RATE : RISE_RATE) : FALL_RATE, density, target);
        if (density < VISIBLE_FLOOR && target < VISIBLE_FLOOR) {
            density = 0.0F;
        }
    }

    private static void sampleAir(ClientLevel level, Player player) {
        BlockPos pos = player.blockPosition();
        waterHumidity = MorningFogClient.nearbyWaterHumidity(level, pos);
        // Whichever of the game's own rain and this mod's storm is raining harder here. The storm's share used to
        // arrive through the level's own rain level, which this mod no longer writes to - it writes its own sky
        // instead - so the reading is asked for by name.
        rain = Mth.clamp(Math.max(level.getRainLevel(1.0F), ClientWeatherEffects.stormRainLevel()), 0.0F, 1.0F);
        cover = StormShade.cover(level, player.position());
        // How hard it is raining on the player, and how deep in the falling water they are standing. The
        // second is read from the synced weather itself, because a squall line's curtain is a body of rain
        // you walk into and out of, and vanilla's single world-wide rain level cannot tell the two apart.
        shaft = wetProximity(player.position());
        double rainHere = RainFogModel.rainAmount(rain, cover);
        rainFog = (float) RainFogModel.mistMass(rainHere, shaft);

        Biome biome = level.getBiome(pos).value();
        float temperature = biome.getBaseTemperature();
        // Dry air: a biome the world never rains on has no moisture to condense. Not a hard zero - the air
        // over a desert at dawn still hazes slightly - but enough that mist there reads as a heat shimmer
        // rather than as fog.
        float dryness = biome.hasPrecipitation() ? 0.0F : 0.55F;
        cold = temperature < 0.30F
                ? Mth.lerp(Mth.clamp((0.30F - temperature) / 0.30F, 0.0F, 1.0F), 1.0F, 0.72F)
                : 1.0F;
        humidity = Mth.clamp(0.10F + waterHumidity * 0.62F + rain * 0.50F + cover * 0.26F - dryness, 0.0F, 1.0F);

        ClientWindSampler.Wind wind = ClientWindSampler.sample(level, player.position(), WIND_SAMPLE_RANGE_BLOCKS);
        windSpeedMph = (float) wind.speedMph();
        drift = wind.vectorMph().scale(BLOCKS_PER_SECOND_PER_MPH);

        // How dirty and dark the air is: a rain-cooled cold pool under a wet deck is grey, a clear dawn is
        // not. The wisps' colour and the haze's colour both read this.
        grime = Mth.clamp(cover * 0.55F + rain * 0.75F, 0.0F, 1.0F);
        warmth = dawnWarmth(level.getDayTime() % 24000L);
    }

    /**
     * The mist's own thickness, 0 (clear air) upward. Above 1 the config was turned past its own default; the
     * volume is drawn with it, the haze is capped by {@link #haze()}.
     */
    public static float density() {
        return density;
    }

    /** The wind the wisps drift with, in blocks per second. Fog advects with the air, so it is the real wind. */
    public static Vec3 drift() {
        return drift;
    }

    /** How grey the air is, 0 (clear dawn) to 1 (rain-cooled under a wet deck). */
    public static float grime() {
        return grime;
    }

    /** How warm the dawn light on the mist is, 0 (neutral) to 1 (golden). */
    public static float warmth() {
        return warmth;
    }

    /** The haze's own strength, 0 to 1: the mist scaled by how much of it the config asked for. */
    public static float haze() {
        return Mth.clamp(density * Config.MIST_HAZE.get().floatValue(), 0.0F, 1.0F);
    }

    /**
     * The fog the rain itself brings, 0 to 1, with its own strength rather than the mist's.
     *
     * <p>Separate from {@link #haze()} because the two are separate requests. The mist's haze rides the mist
     * field, so turning the moisture mist down takes its fog with it - which is right for damp ground at dawn
     * and wrong for a wall of rain, where the fog is not condensation but the rain you are looking through.
     * This one is the rain's own mass, so a downpour is as unseeable-through whether the day's moisture model
     * likes it or not.
     */
    public static float rainHaze() {
        return Mth.clamp(rainFog * Config.MIST_RAIN_FOG.get().floatValue(), 0.0F, 1.0F);
    }

    /** How deep in falling water the player is standing, 0 (beside every storm) to 1 (inside a shaft). */
    public static float shaft() {
        return shaft;
    }

    /**
     * Whether the mist should be pulling fog in at all. False while the tornado's own pressure fog or the
     * whiteout is on: those are deliberate short-range fogs and a mist that fought them would wash the
     * funnel out of its own whiteout.
     */
    public static boolean hazeActive() {
        boolean moistureHaze = density > VISIBLE_FLOOR && Config.MIST_HAZE.get().doubleValue() > 0.0D;
        if (!moistureHaze && rainHaze() <= 0.0F) {
            return false;
        }
        if (TornadoFearClient.fogPressureActive()) {
            return false;
        }
        return !(Config.TORNADO_IMMERSION_WHITEOUT_ENABLED.get() && TornadoFearClient.whiteoutIntensity() > 0.25F);
    }

    /** Whether the wisps themselves should be drawn. */
    public static boolean wispsActive() {
        return density > VISIBLE_FLOOR && !hazeSuppressedByImmersion();
    }

    private static boolean hazeSuppressedByImmersion() {
        return Config.TORNADO_IMMERSION_WHITEOUT_ENABLED.get() && TornadoFearClient.whiteoutIntensity() > 0.25F;
    }

    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        if (!hazeActive()) {
            return;
        }
        float share = rainHaze();
        float blend = Math.max(Mth.clamp(haze() * 0.62F, 0.0F, 0.65F), Mth.clamp(share * 0.72F, 0.0F, 0.72F));
        // The colour of thick humid air: a pale grey-blue, greyed further by rain and dulled to gold by a
        // low sun. The rain's own grey is taken as a floor on that greyness rather than as a second blend, so
        // a downpour cannot be tinted golden by a low sun it is busy hiding. It is a tint on vanilla's own fog
        // colour, never a replacement, so time of day and biome still show through it.
        float greyness = Mth.clamp(Math.max(grime, (float) RainFogModel.grey(share)), 0.0F, 1.0F);
        float red = Mth.lerp(greyness, 0.88F, 0.66F);
        float green = Mth.lerp(greyness, 0.90F, 0.68F);
        float blue = Mth.lerp(greyness, 0.94F, 0.73F);
        float warm = warmth * 0.6F * (1.0F - share);
        red = Mth.lerp(warm, red, 0.96F);
        green = Mth.lerp(warm, green, 0.90F);
        blue = Mth.lerp(warm, blue, 0.83F);
        event.setRed(Mth.lerp(blend, event.getRed(), red));
        event.setGreen(Mth.lerp(blend, event.getGreen(), green));
        event.setBlue(Mth.lerp(blend, event.getBlue(), blue));
    }

    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (!hazeActive() || event.getType() != FogType.NONE) {
            return;
        }
        // Only ever nearer, and by taking the min rather than writing outright, so this composes with the
        // morning fog no matter which of the two listeners runs first.
        float mistPlane = Mth.lerp(Mth.clamp(haze(), 0.0F, 1.0F), 240.0F, 60.0F);
        // The rain's wall is its own curve, and the nearer of the two wins: the mist may be barely there while
        // the rain it is falling through has already closed the distance down to forty blocks.
        float rainPlane = (float) RainFogModel.farPlaneBlocks(rainHaze());
        event.setFarPlaneDistance(Math.min(event.getFarPlaneDistance(), Math.min(mistPlane, rainPlane)));
    }

    /**
     * How deep in falling water a position is, 0 to 1, read off the weather this client has synced: the
     * strongest body of rain the position is inside of, of all the storms and fronts currently running.
     *
     * <p>A line's rain is a curtain behind its gust front rather than a disc, so the body for a line is the
     * line's own segment shifted back along the front by the same fraction of its radius the drawn curtain
     * hangs behind it - standing in a squall line's rain is standing just behind the line, and a player a
     * hundred blocks in front of it is in the clear air it is about to eat. Everything else is a disc about
     * its own radius, which is where Weather2 puts its rain.
     */
    private static float wetProximity(Vec3 playerPos) {
        float best = 0.0F;
        for (AddonWeatherEvent event : ClientAddonWeatherEventStore.events()) {
            if (event == null || event.radius() <= 8.0F) {
                continue;
            }
            double radius = event.radius();
            Vec3 centre = event.position();
            if (centre == null) {
                continue;
            }
            if (event.type() == StormSubtype.SQUALL_LINE && event.isLine()) {
                Vec3 start = event.lineStart();
                Vec3 end = event.lineEnd();
                if (start == null || end == null) {
                    continue;
                }
                centre = lineRainCentre(event, start, end, centre, radius);
            }
            best = Math.max(best, (float) Mth.clamp(1.0D - playerPos.distanceTo(centre) / (radius * RAIN_BODY_REACH), 0.0D, 1.0D));
        }
        return best;
    }

    /**
     * Where a line's rain actually falls: on the line, but the curtain hangs behind the gust front, so the
     * centre of the wet body is the line's own centre pushed back along the front's own direction.
     */
    private static Vec3 lineRainCentre(AddonWeatherEvent event, Vec3 start, Vec3 end, Vec3 centre, double radius) {
        Vec3 line = end.subtract(start);
        if (line.horizontalDistanceSqr() < 1.0E-5D) {
            return centre;
        }
        double length = Math.sqrt(line.horizontalDistanceSqr());
        double axisX = line.x / length;
        double axisZ = line.z / length;
        Vec3 motion = event.motion();
        double backX;
        double backZ;
        if (motion != null && motion.horizontalDistanceSqr() > 1.0E-5D) {
            double speed = Math.sqrt(motion.horizontalDistanceSqr());
            backX = -motion.x / speed;
            backZ = -motion.z / speed;
        } else {
            backX = axisZ;
            backZ = -axisX;
        }
        double behind = radius * RAIN_CURTAIN_BEHIND;
        return centre.add(backX * behind, 0.0D, backZ * behind);
    }

    /**
     * How strongly the time of day favours mist, 0 to 1.
     *
     * <p>Radiational cooling overnight takes the lowest air to its dew point, so the field peaks before and
     * around sunrise, holds through the early morning, and is burned back to a haze by the middle of the day.
     * Even at noon the floor is not zero - thick humid air still hazes - and it climbs again through the
     * evening as the ground cools back toward the dew point.
     */
    private static float timeOfDayFactor(long dayTime) {
        float dawn = sunWindow(dayTime, 0L, 3600.0D);
        return Math.max(nightFloor(dayTime), dawn);
    }

    /**
     * The standing thickness of the layer at a given time, over and above the dawn peak.
     *
     * <p>Overnight the ground cools and the layer simply holds; through the middle of the day only a floor is
     * left. The two are joined by a ramp rather than a step, because the ground does not stop cooling the
     * moment the sun sets - and a step in the target is a step the field would have to hide by smoothing,
     * which would show as the mist arriving in one go a couple of seconds after sunset.
     */
    private static float nightFloor(long dayTime) {
        if (dayTime > 13200L && dayTime < 15000L) {
            return Mth.lerp((dayTime - 13200L) / 1800.0F, 0.34F, 0.55F);
        }
        if (dayTime >= 15000L && dayTime < 21000L) {
            return 0.55F;
        }
        // Down again into the pre-dawn hours, where the dawn window is already rising through this floor and
        // takes over as the stronger of the two.
        if (dayTime >= 21000L && dayTime < 22800L) {
            return Mth.lerp((dayTime - 21000L) / 1800.0F, 0.55F, 0.34F);
        }
        return 0.34F;
    }

    /** How golden the light on the mist is: a window around sunrise, and a smaller one at sunset. */
    private static float dawnWarmth(long dayTime) {
        return Math.max(sunWindow(dayTime, 0L, 2400.0D), sunWindow(dayTime, 12000L, 1800.0D) * 0.7F);
    }

    /**
     * A triangular window over the day clock: 1 at {@code centre}, falling to 0 at {@code halfWidth} ticks
     * either side of it, on a clock that wraps at 24000. Written this way rather than as a range of hours
     * because sunrise and sunset are points on a circle - a window around them has to cross midnight rather
     * than be clipped by it.
     */
    private static float sunWindow(long dayTime, long centre, double halfWidth) {
        long distance = Math.abs(dayTime - centre);
        distance = Math.min(distance, 24000L - distance);
        return Mth.clamp((float) (1.0D - distance / halfWidth), 0.0F, 1.0F);
    }
}
