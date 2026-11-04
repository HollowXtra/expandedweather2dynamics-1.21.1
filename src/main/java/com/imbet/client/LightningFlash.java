package com.imbet.client;

import com.imbet.Config;
import com.imbet.weather.RainVolumeField;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns what a lightning strike lights, instead of leaving that to vanilla's flash counter.
 *
 * <p>Vanilla's flash is a single integer. Every bolt sets it to 2 ({@code LightningBolt.tick} and
 * Weather2's own bolt both call {@code Level.setSkyFlashTime(2)}), {@code ClientLevel.tick} takes one
 * off it every tick, and two places read it:
 *
 * <ul>
 *   <li>{@code ClientLevel.getSkyColor} mixes the sky toward the flash colour by
 *       {@code clamp(flash - partialTick, 0, 1) * 0.45} - and because the fog colour is taken from
 *       the sky colour in {@code FogRenderer.setupColor}, the fog flashes with it; and</li>
 *   <li>{@code LightTexture.updateLightTexture} asks only whether it is above zero, and if it is,
 *       switches the whole lightmap's sky light to full brightness for that frame.</li>
 * </ul>
 *
 * <p>Both of those go wrong the same way, and it is why the light looks like it is flickering rather
 * than flashing. On the tick the counter is down to 1, {@code flash - partialTick} sweeps from one to
 * zero, so the sky colour crosses its whole flash range inside a single tick - twenty times a second -
 * and the lightmap's boolean flips on and off on the same beat. A storm that strikes every couple of
 * seconds therefore strobes the deck and the fog rather than lighting them. The counter also lasts two
 * ticks against a channel that is drawn for twenty-two, so the light never matches the bolt: it is
 * gone before the first afterglow.
 *
 * <p>So the flash is owned here instead. A strike is recorded as a pulse - a tick it began on, and
 * where it struck when that is known - and the level it contributes decays on the same shape the
 * channel's own program shades it with: one onset, then an afterglow. The consumers read that level
 * through the mixins in {@code ClientLevelMixin} and {@code LightTextureMixin}; vanilla's own counter
 * is cancelled in {@code setSkyFlashTime} and never consulted, which is also what keeps the two from
 * double-flashing.
 *
 * <p>Ownership is deliberately tied to the revamped bolt. The flash exists to match a channel that is
 * drawn for twenty-two ticks, so where that bolt is not being drawn -
 * the feature switched off, the program not loaded, or a shader pack in charge of lightning - vanilla's
 * flash runs exactly as it always did rather than being replaced by a light with nothing under it.
 */
public final class LightningFlash {
    /**
     * How long a pulse is worth drawing, in seconds. Set by the slower of the two envelopes: at this
     * age the smooth one is under one percent of its peak, so nothing is cut off by the cutoff.
     */
    private static final double PULSE_LIFE_SECONDS = 1.8D;
    private static final long PULSE_LIFE_TICKS = (long) Math.ceil(PULSE_LIFE_SECONDS * 20.0D);
    /**
     * How close in time two flash requests have to be to be the same strike.
     *
     * <p>Vanilla's counter is set from the bolt's own tick and the revamp sees the bolt in the frame
     * that follows, so the request and the record land on the same tick - and a bolt keeps setting the
     * counter for as long as its {@code life} is not negative, which is a couple of ticks more. Both
     * are why a strike that has already been located is not recorded a second time.
     */
    private static final long SAME_STRIKE_TICKS = 3L;

    // The default envelope: a fast onset into a two-time-constant afterglow. Measured from the shape
    // rather than tuned by eye - the fast term is the flash leaving the return stroke, the slow one is
    // the ionised air still glowing, and the weights were chosen so the curve is at three per cent of
    // its peak when the channel stops being drawn.
    private static final double ATTACK_RATE = 26.0D;
    private static final double FAST_DECAY = 3.6D;
    private static final double SLOW_DECAY = 1.8D;
    private static final double SLOW_WEIGHT = 0.12D;
    /** The unnormalised curve's own peak, so {@link #smoothEnvelope} answers in absolute brightness. */
    private static final double SMOOTH_PEAK = smoothPeak();

    // The strobe envelope is the channel's, read off the fragment program: four strokes then a tail,
    // against the ceiling that program clamps them to.
    private static final double STROBE_CEILING = 1.15D;

    // The distance curve lives in RainVolumeField, because the volumetric rain lights its own fragments
    // with the same one: a strike cannot light the rain and the sky by two different curves without the
    // rain glowing where the sky is not.

    /**
     * Peak weight of the sky and fog flash. Vanilla's own ceiling, kept so the sky at the peak of a
     * strike is exactly as bright as it always was; only the curve leading up to and away from that
     * peak changes.
     */
    private static final float SKY_FLASH_WEIGHT = 0.45F;
    /**
     * Peak weight of the cloud deck's own flash. Higher than the sky's because the deck carries the
     * light rather than being mixed into it, and this term is the mod's own - vanilla never flashes
     * {@code getCloudColor} at all, which is why a strike used to light the sky and leave the clouds
     * looking untouched.
     */
    private static final float CLOUD_FLASH_WEIGHT = 0.55F;

    /** One recorded flash: when it began, and where - or not, for a request that carried no place. */
    private record Pulse(long tick, double x, double y, double z, boolean located) {
    }

    /** Live pulses. Rarely more than one or two, since a storm's strikes are seconds apart. */
    private static final List<Pulse> PULSES = new ArrayList<>();

    private LightningFlash() {
    }

    /**
     * Whether this class is the one lighting strikes right now. Both the consumers and the counter's
     * own cancellation read this, so the flash can never be applied and cancelled separately.
     */
    public static boolean owns() {
        return Config.LIGHTNING_SKY_FLASH_ENABLED.get() && LightningRevamp.replaces();
    }

    /** A strike seen by {@link LightningRevamp}: the same event the channel is drawn from. */
    public static void recordStrike(double x, double y, double z) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !owns()) {
            return;
        }
        long now = level.getGameTime();
        synchronized (PULSES) {
            // The bolt's own flash request reaches us first - it is made from the entity's tick, and the
            // revamp only sees the entity in the frame afterwards - so this is usually the same strike
            // already recorded without a position. Give that pulse the place it was missing rather than
            // starting a second one: two pulses would double the length of the flash.
            for (int i = 0; i < PULSES.size(); i++) {
                Pulse pulse = PULSES.get(i);
                if (!pulse.located() && now - pulse.tick() <= 1L) {
                    PULSES.set(i, new Pulse(pulse.tick(), x, y, z, true));
                    return;
                }
            }
            PULSES.add(new Pulse(now, x, y, z, true));
            prune(now);
        }
    }

    /**
     * A flash request that carried no position - vanilla's counter being set by a bolt the revamp is
     * not tracking, so the flash still happens where the bolt was not one of the two entity types it
     * knows. Ignored when a pulse has already been recorded this tick, which is the ordinary case.
     */
    public static void onVanillaFlash() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        long now = level.getGameTime();
        synchronized (PULSES) {
            for (Pulse pulse : PULSES) {
                if (now - pulse.tick() <= SAME_STRIKE_TICKS) {
                    return;
                }
            }
            PULSES.add(new Pulse(now, 0.0D, 0.0D, 0.0D, false));
            prune(now);
        }
    }

    /**
     * How lit the world is by lightning this frame, from zero to one.
     *
     * <p>Sampled by every consumer rather than pushed: the sky colour, the cloud colour and the
     * lightmap are each asked for this on the frame they are built, and they may be built in any
     * order. {@code partialTick} is the frame's own interpolation, so the curve is evaluated between
     * ticks where a caller has one; the lightmap passes the same value it was handed.
     */
    public static float level(float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        return levelAt(partialTick, minecraft.gameRenderer.getMainCamera().getPosition());
    }

    /**
     * How lit a place is by lightning this frame, from zero to one.
     *
     * <p>The same curve as {@link #level}, measured from the place rather than from the eye, because the
     * question "how much light is on this piece of the world" has a different answer for the rain under a
     * strike than it does for the player watching it from two hundred blocks away. That difference is what
     * the volumetric rain is lit by: the flash lands on the water nearest the channel, and the rain the
     * strike is standing in brightens around it.
     */
    public static float levelAt(float partialTick, Vec3 position) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!owns() || minecraft.options.hideLightningFlash().get()) {
            return 0.0F;
        }
        ClientLevel level = minecraft.level;
        if (level == null) {
            return 0.0F;
        }
        long now = level.getGameTime();
        double best = 0.0D;
        Vec3 seenFrom = position == null ? minecraft.gameRenderer.getMainCamera().getPosition() : position;
        synchronized (PULSES) {
            prune(now);
            for (Pulse pulse : PULSES) {
                double age = Math.max(0.0D, now - pulse.tick() + partialTick) / 20.0D;
                if (age > PULSE_LIFE_SECONDS) {
                    continue;
                }
                double value = envelope(age) * strength(pulse, seenFrom);
                if (value > best) {
                    best = value;
                }
            }
        }
        double brightness = Config.LIGHTNING_SKY_FLASH_BRIGHTNESS.get();
        return Mth.clamp((float) (best * brightness), 0.0F, 1.0F);
    }

    /**
     * The strike a frame's light is coming from: where its channel is, and how bright it is at its own base.
     *
     * <p>Exposed for the rain's program, which lights each fragment by its own distance to the channel and
     * so cannot make do with a single level. The level here is the pulse's own - the envelope and the user's
     * brightness with no distance in it - so the falloff the caller applies is the only distance term, and
     * a strike at no particular place (there is one, when a bolt the revamp is not tracking sets the flash)
     * is nothing rather than everywhere.
     */
    public static Strike brightestStrike(float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!owns() || minecraft.options.hideLightningFlash().get()) {
            return null;
        }
        ClientLevel level = minecraft.level;
        if (level == null) {
            return null;
        }
        long now = level.getGameTime();
        Strike best = null;
        synchronized (PULSES) {
            prune(now);
            for (Pulse pulse : PULSES) {
                if (!pulse.located()) {
                    continue;
                }
                double age = Math.max(0.0D, now - pulse.tick() + partialTick) / 20.0D;
                if (age > PULSE_LIFE_SECONDS) {
                    continue;
                }
                float pulseLevel = Mth.clamp((float) (envelope(age) * Config.LIGHTNING_SKY_FLASH_BRIGHTNESS.get()),
                        0.0F, 1.0F);
                if (best == null || pulseLevel > best.level()) {
                    best = new Strike(centre(pulse), pulseLevel);
                }
            }
        }
        return best;
    }

    /** A strike's channel and how bright it is at its own base, with no distance in the level. */
    public record Strike(Vec3 centre, float level) {
    }

    /** Sky and fog weight for a level: vanilla's peak, reached smoothly and held by the afterglow. */
    public static float skyWeight(float level) {
        return Mth.clamp(level * SKY_FLASH_WEIGHT, 0.0F, 1.0F);
    }

    /** Cloud-deck weight for a level. */
    public static float cloudWeight(float level) {
        return Config.LIGHTNING_SKY_FLASH_CLOUDS.get() ? Mth.clamp(level * CLOUD_FLASH_WEIGHT, 0.0F, 1.0F) : 0.0F;
    }

    /**
     * The lightmap's sky-light darkness, with the flash blended into it.
     *
     * <p>{@code LightTexture} computes that darkness as {@code skyDarken * 0.95 + 0.05} and swaps it for
     * a flat full brightness whenever the flash counter is above zero, which is the binary flip that
     * makes the world's ambient light pulse on and off with the counter. Interpolating the darkness
     * toward full brightness by the level instead gives the same ceiling with none of the flip: at the
     * peak of a strike the lightmap is lit exactly as vanilla lit it, and either side of that peak it
     * moves instead of switching.
     */
    public static float lightmapDarken(float darken) {
        // Sampled on the tick rather than between ticks, because the value this replaces is arrived at
        // with a literal partial tick of one in the lightmap anyway: a strike's onset is a frame wide,
        // which is what a real one is.
        float level = level(0.0F);
        return level <= 0.001F ? darken : Mth.lerp(level, darken, 1.0F);
    }

    private static double envelope(double age) {
        return Config.LIGHTNING_SKY_FLASH_STROBES.get() ? strobeEnvelope(age) : smoothEnvelope(age);
    }

    /**
     * One onset, then the afterglow - what a strike's light looks like on a whole cloud deck rather
     * than on the channel itself, and the default.
     */
    private static double smoothEnvelope(double age) {
        if (age < 0.0D) {
            return 0.0D;
        }
        double rise = 1.0D - Math.exp(-age * ATTACK_RATE);
        double decay = (1.0D - SLOW_WEIGHT) * Math.exp(-age * FAST_DECAY) + SLOW_WEIGHT * Math.exp(-age * SLOW_DECAY);
        return rise * decay / SMOOTH_PEAK;
    }

    /**
     * The channel's own envelope, for players who want the sky to flicker with the bolt.
     *
     * <p>Deliberately the same numbers as {@code lightning_bolt.fsh}: the strokes at 0, 45, 105 and 190
     * milliseconds with their decays, plus the slow tail, clamped to the same ceiling and normalised by
     * it. Two systems describing one strike should not disagree about when it flickered.
     */
    private static double strobeEnvelope(double age) {
        double strokes = stroke(age, 0.000D, 1.00D, 30.0D)
                + stroke(age, 0.045D, 0.74D, 33.0D)
                + stroke(age, 0.105D, 0.56D, 36.0D)
                + stroke(age, 0.190D, 0.34D, 40.0D);
        return Math.min(strokes + stroke(age, 0.000D, 0.15D, 2.9D), STROBE_CEILING) / STROBE_CEILING;
    }

    private static double stroke(double age, double start, double amplitude, double decay) {
        double since = age - start;
        if (since < 0.0D) {
            return 0.0D;
        }
        return amplitude * Math.exp(-since * decay);
    }

    /** How much of a pulse's light reaches the eye, given how far away it struck. */
    private static double strength(Pulse pulse, Vec3 from) {
        if (!pulse.located() || !Config.LIGHTNING_SKY_FLASH_DISTANCE_FALLOFF.get()) {
            return 1.0D;
        }
        return RainVolumeField.flashFalloff(centre(pulse).distanceTo(from));
    }

    /**
     * The middle of a pulse's channel: where its light is measured from.
     *
     * <p>The middle rather than the ground strike, the way the channel's own extinction is measured, so a
     * strike directly overhead is not treated as further away than one at the same place on the ground.
     */
    private static Vec3 centre(Pulse pulse) {
        return new Vec3(pulse.x(), pulse.y() + Config.LIGHTNING_BOLT_HEIGHT_BLOCKS.get() * 0.5D, pulse.z());
    }

    /** Drops pulses that are past their life. Called with {@link #PULSES} held. */
    private static void prune(long now) {
        PULSES.removeIf(pulse -> now - pulse.tick() > PULSE_LIFE_TICKS);
    }

    /**
     * The unnormalised envelope's peak, found by sampling it once at class load.
     *
     * <p>Computed rather than written down as a constant, so the shape's constants above stay the only
     * numbers in the file: changing {@link #ATTACK_RATE} or a decay changes the peak, and the curve is
     * normalised by whatever that peak actually is instead of drifting off it.
     */
    private static double smoothPeak() {
        double peak = 0.0D;
        for (int sample = 0; sample <= 4000; sample++) {
            double age = sample * 0.0005D;
            double rise = 1.0D - Math.exp(-age * ATTACK_RATE);
            double decay = (1.0D - SLOW_WEIGHT) * Math.exp(-age * FAST_DECAY) + SLOW_WEIGHT * Math.exp(-age * SLOW_DECAY);
            peak = Math.max(peak, rise * decay);
        }
        return peak;
    }
}
