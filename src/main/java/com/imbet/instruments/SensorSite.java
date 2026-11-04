package com.imbet.instruments;

import com.imbet.alert.SensorNetwork;

/**
 * An instrument block that can be wired into a weather computer.
 *
 * <p>The three field instruments - the anemometer, the barometer and the rain gauge - have nothing else in
 * common: they read different things, on different scales, at different rates. What they do have in common is
 * that each of them is standing somewhere, and that a place with an instrument on it knows something a place
 * without one does not. This is that, as one method each: what kind of reading it takes, and what it says right
 * now in one line.
 *
 * <p>It is deliberately small. Anything larger would be a second instrument implementation living inside the
 * network, and the network's whole job is to repeat what the instruments already said rather than to say
 * anything of its own.
 *
 * <p>{@link #sensorValue()} exists for one reason: the network reports how far its instruments are spread, and
 * a spread of what needs a number rather than a sentence. It is the instrument's own native unit - miles per
 * hour, hectopascals, millimetres - and it is never converted, because the network does not compare readings
 * across kinds.
 */
public interface SensorSite {
    /** Which of the three readings this instrument supplies. */
    SensorNetwork.Kind sensorKind();

    /** What this instrument reads right now, in one line, in its own words. */
    String sensorLine();

    /** The reading as a number in the instrument's own unit, for the network's own spread arithmetic. */
    double sensorValue();
}
