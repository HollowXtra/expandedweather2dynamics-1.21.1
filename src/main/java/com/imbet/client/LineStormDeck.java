package com.imbet.client;

import com.imbet.Config;
import com.imbet.weather.AddonWeatherEvent;
import com.imbet.weather.AddonWeatherEventService;
import com.imbet.weather.ClientAddonWeatherEventStore;
import com.imbet.weather.SquallLineDeck;
import com.imbet.weather.StormSubtype;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import weather2.weathersystem.storm.StormObject;

/**
 * Where one squall-line storm's own cloud puffs go, read off the line the storm belongs to.
 *
 * <p>Weather2 spawns a storm's deck in {@code StormObject.tickRender}: a square of half-width
 * {@code size} around the storm's own {@code pos}, at {@code pos.y + 30}, gated on the player being
 * within 512 blocks and on there being no terrain ahead. That square is the entire silhouette of a
 * Weather2 storm, so a line seeded as a row of thunderstorms is drawn as a row of round masses. This
 * class answers the one question the storm object cannot answer for itself - which way the front
 * runs - so the same spawn can be drawn {@link SquallLineDeck} long and thin across it instead.
 *
 * <p>Only x and z are ever replaced. The height, the population, the puff size, the lifetime, the
 * sprite and every later movement of the puff stay Weather2's own: the deck is still spawned by
 * Weather2 in Weather2's loop, still snapped to the storm's own cloud base by Weather2's own drive,
 * and still drifts with the storm's own wind. What changes is the footprint, which is the only thing
 * that was ever making the shape.
 *
 * <p>Weather2's own two gates are re-applied to the point we are about to use - the 512 block
 * bubble around the player and the terrain-ahead check - because they were evaluated against the
 * square's own draw. A point that fails them returns null, and the caller keeps Weather2's own
 * sample, so a cell far enough away to be culled still spawns exactly what it always did.
 */
public final class LineStormDeck {
    /** Weather2's own deck height above the storm's position, mirrored so the call can be identified. */
    public static final double DECK_HEIGHT_OFFSET = 30.0D;
    /**
     * How closely a spawn call has to match that height to be the deck's own.
     *
     * <p>The spawn site is targeted by its position in {@code tickRender}'s bytecode, and a
     * deliberately narrow window on the height is what stops a shifted target from silently bending
     * some other caller's clouds: only the deck spawns at exactly {@code pos.y + 30}. The ground fog
     * spawns at the terrain surface and the funnel puffs near the storm's base, so a call from either
     * falls outside the window and is left alone.
     */
    public static final double DECK_CALL_TOLERANCE = 0.5D;
    /** How far a cell may sit from a synced line and still be drawn as a section of it, in blocks. */
    public static final double LINK_RANGE_BLOCKS = 700.0D;
    /** Weather2's own reach for the deck: no puff is spawned past this from the player. */
    private static final double WEATHER2_PLAYER_RANGE = 512.0D;

    private LineStormDeck() {
    }

    /**
     * True when this spawn is the storm's cloud deck rather than one of the other callers that share
     * {@code spawnFogParticle}. Weather2 places the deck at {@code pos.y + 30} and the other callers
     * at the terrain surface or the storm's base, so the height identifies it.
     */
    public static boolean isDeckSpawn(StormObject storm, double y) {
        return storm != null && storm.pos != null && Math.abs(y - deckHeight(storm)) <= DECK_CALL_TOLERANCE;
    }

    /**
     * The point this deck puff should be spawned at, or null for "leave Weather2's own draw alone".
     *
     * <p>Null is returned for every reason there is not a line here: the switch is off, the storm is
     * not one the server seeded onto a line, no line has synced within reach, the swing is off a
     * cell's own axis, or the point fails one of Weather2's own two gates.
     */
    public static Vec3 deckPoint(StormObject storm, double y) {
        if (!Config.SQUALL_LINE_STORM_DECK.get()) {
            return null;
        }
        if (storm == null || storm.pos == null || storm.manager == null || storm.manager.getWorld() == null) {
            return null;
        }
        // The deck's own height, and nothing else that Weather2 spawns through the same method: the ground fog
        // sits on the terrain surface and the funnel near the storm's base, so neither is at pos.y + 30.
        if (!isDeckSpawn(storm, y)) {
            return null;
        }
        if (!storm.manager.getWorld().isClientSide() || !AddonWeatherEventService.isSquallLineHelperStorm(storm)) {
            return null;
        }
        SquallLineDeck.Band band = bandFor(storm);
        if (band == null) {
            return null;
        }
        java.util.Random random = new java.util.Random();
        double along = band.alongOffset(random.nextDouble());
        double across = band.acrossOffset(random.nextDouble());
        Vec3 candidate = new Vec3(band.x(along, across), y, band.z(along, across));
        return withinWeather2sOwnGates(storm, candidate) ? candidate : null;
    }

    /** Weather2's deck height for this storm - {@code getPosTop().y + 30} for anything not a tornado. */
    public static double deckHeight(StormObject storm) {
        return storm.pos.y + DECK_HEIGHT_OFFSET;
    }

    /**
     * Weather2's own two spawn gates, evaluated against the point that will actually be spawned: the
     * puff must be inside the bubble around the player, and the storm's own motion must not be walking
     * into terrain at it.
     */
    private static boolean withinWeather2sOwnGates(StormObject storm, Vec3 candidate) {
        if (Minecraft.getInstance().player == null) {
            return false;
        }
        Vec3 anchor = new Vec3(Minecraft.getInstance().player.getX(), storm.pos.y, Minecraft.getInstance().player.getZ());
        if (candidate.distanceTo(anchor) >= WEATHER2_PLAYER_RANGE) {
            return false;
        }
        return storm.getAvoidAngleIfTerrainAtOrAheadOfPosition(storm.getAdjustedAngle(), candidate) == 0.0F;
    }

    /**
     * The section this cell belongs to, read off the nearest synced squall line: the line's own axis
     * and the storm's own size. The section stays centred on the storm's own position - a Weather2
     * storm's cloud is its own, and moving it off the storm would be a different storm - so what the
     * line supplies is the direction to stretch along, and nothing else. Null when nothing within
     * {@link #LINK_RANGE_BLOCKS} of the cell is a line.
     */
    private static SquallLineDeck.Band bandFor(StormObject storm) {
        SquallLineDeck.Band best = null;
        double closest = LINK_RANGE_BLOCKS * LINK_RANGE_BLOCKS;
        for (AddonWeatherEvent event : ClientAddonWeatherEventStore.events()) {
            if (event == null || event.type() != StormSubtype.SQUALL_LINE || !event.isLine()) {
                continue;
            }
            Vec3 start = event.lineStart();
            Vec3 end = event.lineEnd();
            if (start == null || end == null) {
                continue;
            }
            double dxLine = end.x - start.x;
            double dzLine = end.z - start.z;
            double length = Math.sqrt(dxLine * dxLine + dzLine * dzLine);
            if (length < 16.0D) {
                continue;
            }
            double axisX = dxLine / length;
            double axisZ = dzLine / length;
            double dx = storm.pos.x - start.x;
            double dz = storm.pos.z - start.z;
            double along = dx * axisX + dz * axisZ;
            double across = dz * axisX - dx * axisZ;
            double overshoot = along < 0.0D ? -along : (along > length ? along - length : 0.0D);
            double distanceSqr = overshoot * overshoot + across * across;
            if (distanceSqr >= closest) {
                continue;
            }
            closest = distanceSqr;
            // A line whose front is unknown is still a line: the bow is seeded forward of the axis, so
            // falling back to the axis' own normal keeps the stretch along the front either way.
            best = SquallLineDeck.band(storm.pos.x, storm.pos.z, axisX, axisZ, storm.size);
        }
        return best;
    }
}
